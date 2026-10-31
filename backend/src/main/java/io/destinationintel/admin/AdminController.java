package io.destinationintel.admin;

import io.destinationintel.audit.AuditService;
import io.destinationintel.common.ApiException;
import io.destinationintel.common.Json;
import io.destinationintel.common.PageResponse;
import io.destinationintel.community.CurrentUser;
import io.destinationintel.community.UserRepository;
import io.destinationintel.community.UserRepository.AppUserRow;
import io.destinationintel.guide.GuideReadinessService;
import io.destinationintel.ingestion.IngestionScheduler;
import io.destinationintel.ingestion.JobRepository;
import io.destinationintel.ingestion.JobRepository.JobRow;
import io.destinationintel.place.PlaceRepository;
import io.destinationintel.place.PlaceRow;
import io.destinationintel.request.RequestService;
import io.destinationintel.source.SourceRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@Tag(name = "Administration", description = "Ingestion operations, source registry, stewards and accounts (admins only)")
public class AdminController {

    public record JobView(UUID id, String jobType, String sourceId, String targetPlaceName, String targetPlaceSlug,
                          Map<String, Object> params, String status, int priority, int attempts, int maxAttempts,
                          OffsetDateTime nextAttemptAt, OffsetDateTime startedAt, OffsetDateTime finishedAt,
                          String lastError, Map<String, Object> stats, String createdBy, OffsetDateTime createdAt) {}

    public record EnqueueBody(
            @NotBlank @Pattern(regexp = "wikidata_index|wikivoyage_guide|climate|place_image|coverage_refresh|osm_extract|overture_places|heritage_monuments|quality_evaluation|match_resolution|road_routes|nearest_transport|air_quality|fact_check") String type,
            @Pattern(regexp = "^[a-z0-9-]{1,40}$") String sourceId,
            @Size(max = 140) String placeSlug,
            boolean force) {}

    public record SourceToggle(@NotNull Boolean enabled) {}

    public record StewardBody(@NotNull UUID userId, @NotBlank @Size(max = 140) String placeSlug) {}

    public record SuspendBody(@NotBlank @Size(max = 500) String reason) {}

    public record UserView(UUID id, String displayName, String email, String tier, boolean admin, String status,
                           OffsetDateTime createdAt, OffsetDateTime lastLoginAt) {}

    private final CurrentUser current;
    private final JobRepository jobs;
    private final IngestionScheduler scheduler;
    private final SourceRepository sources;
    private final PlaceRepository places;
    private final UserRepository users;
    private final RequestService requests;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final Json json;
    private final GuideReadinessService readiness;

    public AdminController(CurrentUser current, JobRepository jobs, IngestionScheduler scheduler, SourceRepository sources,
                           PlaceRepository places, UserRepository users, RequestService requests, AuditService audit,
                           JdbcClient jdbc, Json json, GuideReadinessService readiness) {
        this.current = current;
        this.jobs = jobs;
        this.scheduler = scheduler;
        this.sources = sources;
        this.places = places;
        this.users = users;
        this.requests = requests;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.readiness = readiness;
    }

    @GetMapping("/destinations/{slug}/readiness")
    @Operation(summary = "Is this guide clean and complete enough to feature? Lists each check that passes or fails")
    public GuideReadinessService.Readiness readiness(@PathVariable String slug) {
        return readiness.check(slug);
    }

    @GetMapping("/ingestion/jobs")
    @Operation(summary = "Ingestion jobs with status, attempts, errors and statistics")
    public PageResponse<JobView> jobs(@RequestParam(required = false) String status,
                                      @RequestParam(required = false) String type,
                                      @RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer size) {
        int p = PageResponse.clampPage(page);
        int s = PageResponse.clampSize(size, 25);
        List<JobView> items = jobs.list(blankToNull(status), blankToNull(type), s, p * s).stream().map(this::view).toList();
        return PageResponse.of(items, p, s, jobs.count(blankToNull(status), blankToNull(type)));
    }

    @PostMapping("/ingestion/jobs")
    @Transactional
    @Operation(summary = "Queue an ingestion job (idempotent while an identical job is queued or running)")
    public JobView enqueue(@Valid @RequestBody EnqueueBody body) {
        AppUserRow admin = current.require();
        PlaceRow place = null;
        if (body.placeSlug() != null && !body.placeSlug().isBlank()) {
            place = places.findBySlug(body.placeSlug().trim()).orElseThrow(() -> ApiException.notFound("Destination"));
        }
        boolean needsPlace = java.util.Set.of("wikivoyage_guide", "climate", "place_image", "road_routes", "air_quality", "fact_check")
                .contains(body.type());
        if (needsPlace && place == null) {
            throw ApiException.badRequest("place_required", "This job type needs a destination.");
        }
        String source = switch (body.type()) {
            case "wikidata_index" -> "wikidata";
            case "wikivoyage_guide" -> "wikivoyage-en";
            case "climate" -> "open-meteo";
            case "place_image" -> "wikimedia-commons";
            case "osm_extract" -> "openstreetmap";
            case "overture_places" -> "overture-places";
            case "heritage_monuments" -> "asi-heritage";
            case "road_routes" -> "osrm";
            case "nearest_transport" -> "ourairports";
            case "air_quality" -> "open-meteo-air";
            case "fact_check" -> "official-website";
            default -> null;
        };
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        if (body.force()) {
            params.put("force", true);
        }
        if (body.type().equals("road_routes")) {
            params.put("radiusKm", 350);
        }
        if (body.type().equals("quality_evaluation")) {
            if (body.sourceId() == null || sources.find(body.sourceId()).isEmpty()) {
                throw ApiException.badRequest("source_required", "Choose the source to evaluate.");
            }
            params.put("sourceId", body.sourceId());
        }
        String dedup = body.type() + ":" + (place == null ? "all" : place.id()) + (body.sourceId() == null ? "" : ":" + body.sourceId());
        UUID id = jobs.enqueue(body.type(), source, place == null ? null : place.id(), params, dedup, 25, "admin");
        audit.record(admin.id(), "ingestion.job_enqueued", "ingestion_job", id, place == null ? null : place.id(),
                Map.of("type", body.type(), "force", body.force()));
        return jobs.find(id).map(this::view).orElseThrow();
    }

    @PostMapping("/ingestion/jobs/{id}/retry")
    @Transactional
    public Map<String, Object> retry(@PathVariable UUID id) {
        boolean ok = jobs.retry(id);
        if (!ok) {
            throw ApiException.conflict("not_retryable", "Only failed or cancelled jobs without an active duplicate can be retried.");
        }
        audit.record(current.require().id(), "ingestion.job_retried", "ingestion_job", id, null, Map.of());
        return Map.of("id", id, "status", "queued");
    }

    @PostMapping("/ingestion/jobs/{id}/cancel")
    @Transactional
    public Map<String, Object> cancel(@PathVariable UUID id) {
        if (!jobs.cancel(id)) {
            throw ApiException.conflict("not_cancellable", "Only queued jobs can be cancelled.");
        }
        audit.record(current.require().id(), "ingestion.job_cancelled", "ingestion_job", id, null, Map.of());
        return Map.of("id", id, "status", "cancelled");
    }

    @PostMapping("/ingestion/plan")
    @Transactional
    @Operation(summary = "Run the bootstrap and refresh planner now")
    public Map<String, Object> plan() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("bootstrap", scheduler.enqueueBootstrap("admin"));
        out.put("refresh", scheduler.planRefreshes("admin"));
        audit.record(current.require().id(), "ingestion.planned", "ingestion", "planner", null, Map.of());
        return out;
    }

    @PatchMapping("/sources/{id}")
    @Transactional
    @Operation(summary = "Enable or disable a source adapter")
    public Map<String, Object> toggleSource(@PathVariable String id, @Valid @RequestBody SourceToggle body) {
        sources.find(id).orElseThrow(() -> ApiException.notFound("Source"));
        sources.setEnabled(id, body.enabled());
        audit.record(current.require().id(), body.enabled() ? "source.enabled" : "source.disabled", "source", id, null, Map.of());
        return Map.of("id", id, "enabled", body.enabled());
    }

    @GetMapping("/users")
    public List<UserView> users(@RequestParam(defaultValue = "") String q) {
        return users.search(q.trim(), 50).stream().map(u -> new UserView(u.id(), u.displayName(), u.email(), u.tier(),
                u.isAdmin(), u.status(), u.createdAt(), u.lastLoginAt())).toList();
    }

    @PostMapping("/stewards")
    @Transactional
    @Operation(summary = "Appoint a contributor as city steward for a destination")
    public Map<String, Object> appoint(@Valid @RequestBody StewardBody body) {
        AppUserRow admin = current.require();
        AppUserRow user = users.findById(body.userId()).filter(AppUserRow::active)
                .orElseThrow(() -> ApiException.notFound("Active contributor"));
        PlaceRow place = places.findBySlug(body.placeSlug().trim()).orElseThrow(() -> ApiException.notFound("Destination"));
        jdbc.sql("""
                        INSERT INTO steward_assignment (user_id, place_id, assigned_by) VALUES (:u, :p, :by)
                        ON CONFLICT (user_id, place_id) DO NOTHING
                        """).param("u", user.id()).param("p", place.id()).param("by", admin.id()).update();
        if (!"steward".equals(user.tier())) {
            users.setTier(user.id(), "steward");
        }
        users.recordTrustEvent(user.id(), "steward_assigned", user.tier(), "steward", "Appointed steward for " + place.name(), admin.id());
        audit.record(admin.id(), "user.steward_assigned", "user", user.id(), place.id(), Map.of("place", place.slug()));
        return Map.of("userId", user.id(), "place", place.slug(), "tier", "steward");
    }

    @DeleteMapping("/stewards/{userId}/{placeSlug}")
    @Transactional
    public Map<String, Object> removeSteward(@PathVariable UUID userId, @PathVariable String placeSlug) {
        AppUserRow admin = current.require();
        PlaceRow place = places.findBySlug(placeSlug).orElseThrow(() -> ApiException.notFound("Destination"));
        jdbc.sql("DELETE FROM steward_assignment WHERE user_id = :u AND place_id = :p")
                .param("u", userId).param("p", place.id()).update();
        boolean remaining = !users.stewardPlaces(userId).isEmpty();
        if (!remaining) {
            users.setTier(userId, "trusted");
        }
        users.recordTrustEvent(userId, "steward_removed", "steward", remaining ? "steward" : "trusted",
                "Steward role removed for " + place.name(), admin.id());
        audit.record(admin.id(), "user.steward_removed", "user", userId, place.id(), Map.of("place", place.slug()));
        return Map.of("userId", userId, "tier", remaining ? "steward" : "trusted");
    }

    @PostMapping("/users/{id}/suspend")
    @Transactional
    public Map<String, Object> suspend(@PathVariable UUID id, @Valid @RequestBody SuspendBody body) {
        AppUserRow admin = current.require();
        if (admin.id().equals(id)) {
            throw ApiException.badRequest("invalid_target", "You cannot suspend your own account.");
        }
        users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
        users.setStatus(id, "suspended");
        users.recordTrustEvent(id, "suspended", null, null, body.reason(), admin.id());
        audit.record(admin.id(), "user.suspended", "user", id, null, Map.of("reason", body.reason()));
        return Map.of("id", id, "status", "suspended");
    }

    @PostMapping("/users/{id}/reinstate")
    @Transactional
    public Map<String, Object> reinstate(@PathVariable UUID id) {
        AppUserRow admin = current.require();
        AppUserRow user = users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
        if (!"suspended".equals(user.status())) {
            throw ApiException.conflict("not_suspended", "This account is not suspended.");
        }
        users.setStatus(id, "active");
        users.recordTrustEvent(id, "reinstated", null, null, "Reinstated by an administrator", admin.id());
        audit.record(admin.id(), "user.reinstated", "user", id, null, Map.of());
        return Map.of("id", id, "status", "active");
    }

    @GetMapping("/requests")
    @Operation(summary = "Destination requests ordered by demand")
    public List<RequestService.RequestRow> requests(@RequestParam(required = false) String status,
                                                    @RequestParam(required = false) Integer page) {
        int p = PageResponse.clampPage(page);
        return requests.list(blankToNull(status), 50, p * 50);
    }

    private JobView view(JobRow j) {
        return new JobView(j.id(), j.jobType(), j.sourceId(), j.targetPlaceName(), j.targetPlaceSlug(), json.map(j.params()),
                j.status(), j.priority(), j.attempts(), j.maxAttempts(), j.nextAttemptAt(), j.startedAt(), j.finishedAt(),
                j.lastError(), json.map(j.stats()), j.createdBy(), j.createdAt());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
