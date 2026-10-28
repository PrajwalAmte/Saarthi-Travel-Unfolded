package io.destinationintel.ai;

import io.destinationintel.audit.AuditService;
import io.destinationintel.common.ApiException;
import io.destinationintel.common.PageResponse;
import io.destinationintel.community.CurrentUser;
import io.destinationintel.guide.GuideChanges;
import io.destinationintel.ingestion.JobRepository;
import io.destinationintel.place.PlaceRepository;
import io.destinationintel.place.PlaceRow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin review of AI flags: accept (the listing is hidden) or dismiss. Nothing is hidden without an admin. */
@RestController
@RequestMapping("/api/admin/ai-review")
@Tag(name = "AI review", description = "Listings an AI reviewer found doubtful, for an admin to decide (admins only)")
public class AiReviewController {

    public record Flag(UUID id, UUID entryId, String destinationSlug, String destinationName, String section, String title,
                       String area, String description, boolean fitsSection, String inDestination, List<String> issues,
                       String suggestedSection, double confidence, String reason, String model, OffsetDateTime createdAt) {}

    public record Usage(boolean active, String model, String fallbackModel, int requestsToday, int dailyBudget, long tokensToday,
                        long openFlags, long reviewedListings, long queuedJobs, long decidedAutomatically, List<ModelUsage> models) {}

    /** One model's use today (Google's quota day) against its free limits. */
    public record ModelUsage(String name, int requestsToday, int requestsPerDay, int requestsPerMinute) {}

    private final JdbcClient jdbc;
    private final CurrentUser current;
    private final AuditService audit;
    private final GuideChanges changes;
    private final JobRepository jobs;
    private final PlaceRepository places;
    private final AiProperties props;
    private final AiPublishing publishing;
    private final tools.jackson.databind.json.JsonMapper mapper;
    private final ListingFixes fixes;
    private final FlagDecider decider;

    public record PreviewEntry(UUID entryId, String section, String title, String area, String original, String summary,
                               String summaryStatus, String summaryRejection, Object tags, Object extracted) {}

    public record Preview(String slug, String name, boolean published, int listings, int enriched, int summariesAccepted,
                          int summariesRejected, int tagged, int factsExtracted, Object inShort, Object inShortRejected,
                          List<PreviewEntry> entries) {}

    public AiReviewController(JdbcClient jdbc, CurrentUser current, AuditService audit, GuideChanges changes, JobRepository jobs,
                              PlaceRepository places, AiProperties props, AiPublishing publishing,
                              tools.jackson.databind.json.JsonMapper mapper, ListingFixes fixes, FlagDecider decider) {
        this.jdbc = jdbc;
        this.current = current;
        this.audit = audit;
        this.changes = changes;
        this.jobs = jobs;
        this.places = places;
        this.props = props;
        this.publishing = publishing;
        this.mapper = mapper;
        this.fixes = fixes;
        this.decider = decider;
    }

    @GetMapping("/publish")
    @Operation(summary = "Whether readers see AI improvements (summaries, tags, extracted prices and hours, In short)")
    public Map<String, Object> publishState() {
        return Map.of("published", publishing.published());
    }

    @org.springframework.web.bind.annotation.PutMapping("/publish")
    @Transactional
    @Operation(summary = "Show or hide AI improvements to readers, everywhere at once")
    public Map<String, Object> setPublish(@org.springframework.web.bind.annotation.RequestBody Map<String, Boolean> body) {
        UUID admin = current.require().id();
        boolean on = Boolean.TRUE.equals(body.get("published"));
        int facts = publishing.set(on, admin);
        audit.record(admin, on ? "ai.published" : "ai.unpublished", "app_setting", "ai.publish", null, Map.of("facts", facts));
        return Map.of("published", on, "message", on ? "Readers now see AI improvements." : "AI improvements are hidden from readers.");
    }

    @GetMapping("/destinations/{slug}/preview")
    @Operation(summary = "Before and after for one destination: original text, AI summary (or why it was rejected), tags, extracted facts")
    public Preview preview(@PathVariable String slug) {
        PlaceRow p = places.findBySlug(slug).orElseThrow(() -> ApiException.notFound("Destination"));
        List<PreviewEntry> entries = jdbc.sql("""
                        SELECT DISTINCT ON (a.entry_id) a.entry_id, e.section, e.title, e.area,
                               (SELECT f.value_text FROM fact f WHERE f.entry_id = e.id AND f.kind = 'description' AND f.status = 'published' LIMIT 1) AS original,
                               a.summary, a.summary_status, a.summary_rejection, a.tags::text AS tags, a.extracted::text AS extracted
                        FROM ai_enrichment a JOIN guide_entry e ON e.id = a.entry_id
                        WHERE a.destination_id = :d AND e.status = 'published' AND a.prompt_version = :v
                        ORDER BY a.entry_id, a.created_at DESC""")
                .param("d", p.id()).param("v", ListingReviewHandler.PROMPT_VERSION)
                .query((rs, i) -> new PreviewEntry(rs.getObject("entry_id", UUID.class), rs.getString("section"), rs.getString("title"),
                        rs.getString("area"), rs.getString("original"), rs.getString("summary"), rs.getString("summary_status"),
                        rs.getString("summary_rejection"), mapper.readTree(rs.getString("tags")), mapper.readTree(rs.getString("extracted"))))
                .list();
        Map<String, Object> ov = jdbc.sql("SELECT items::text AS items, rejected::text AS rejected FROM ai_overview WHERE destination_id = :d ORDER BY created_at DESC LIMIT 1")
                .param("d", p.id()).query().listOfRows().stream().findFirst().orElse(Map.of());
        int listings = jdbc.sql("SELECT count(*) FROM guide_entry WHERE destination_id = :d AND status = 'published' AND section <> 'neighbourhood'").param("d", p.id()).query(Long.class).single().intValue();
        return new Preview(p.slug(), p.name(), publishing.published(), listings, entries.size(),
                (int) entries.stream().filter(e -> "accepted".equals(e.summaryStatus())).count(),
                (int) entries.stream().filter(e -> "rejected".equals(e.summaryStatus())).count(),
                (int) entries.stream().filter(e -> e.tags() instanceof tools.jackson.databind.JsonNode n && n.size() > 0).count(),
                entries.stream().mapToInt(e -> e.extracted() instanceof tools.jackson.databind.JsonNode n ? n.size() : 0).sum(),
                ov.get("items") == null ? List.of() : mapper.readTree(String.valueOf(ov.get("items"))),
                ov.get("rejected") == null ? List.of() : mapper.readTree(String.valueOf(ov.get("rejected"))),
                entries);
    }

    @GetMapping("/usage")
    @Operation(summary = "Whether AI review is on, today's request use against the budget, and open flags")
    public Usage usage() {
        Map<String, Integer> used = new java.util.HashMap<>();
        long tokens = 0;
        for (Map<String, Object> row : jdbc.sql("SELECT model, requests, tokens FROM ai_usage WHERE day = " + GeminiClient.QUOTA_DAY)
                .query().listOfRows()) {
            used.put(String.valueOf(row.get("model")), ((Number) row.get("requests")).intValue());
            tokens += ((Number) row.get("tokens")).longValue();
        }
        List<ModelUsage> models = props.pool().stream()
                .map(m -> new ModelUsage(m.name(), used.getOrDefault(m.name(), 0), m.requestsPerDay(), m.requestsPerMinute())).toList();
        int requests = used.values().stream().mapToInt(Integer::intValue).sum();
        int budget = Math.min(props.dailyRequestBudget(), models.stream().mapToInt(ModelUsage::requestsPerDay).sum());
        return new Usage(props.active(), models.isEmpty() ? props.model() : models.get(0).name(), null, requests, budget, tokens,
                count("SELECT count(*) FROM ai_review WHERE status = 'open'"),
                count("SELECT count(DISTINCT entry_id) FROM ai_review"),
                count("SELECT count(*) FROM ingestion_job WHERE job_type = 'ai_review' AND status IN ('queued', 'running')"),
                count("SELECT count(*) FROM ai_review WHERE decided_automatically"), models);
    }

    @GetMapping("/flags")
    @Operation(summary = "Open flags, most confident first, optionally for one destination")
    public PageResponse<Flag> flags(@RequestParam(required = false) String destination, @RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer size) {
        int p = PageResponse.clampPage(page);
        int s = PageResponse.clampSize(size, 25);
        String where = " WHERE r.status = 'open' AND (CAST(:slug AS text) IS NULL OR d.slug = :slug)";
        long total = jdbc.sql("SELECT count(*) FROM ai_review r JOIN place d ON d.id = r.destination_id" + where)
                .param("slug", destination).query(Long.class).single();
        List<Flag> items = jdbc.sql("""
                        SELECT r.id, r.entry_id, d.slug AS destination_slug, d.name AS destination_name, e.section, e.title, e.area,
                               (SELECT f.value_text FROM fact f WHERE f.entry_id = e.id AND f.kind = 'description' AND f.status = 'published' LIMIT 1) AS description,
                               r.fits_section, r.in_destination, array_to_string(r.issues, ',') AS issues, r.suggested_section,
                               r.confidence, r.reason, r.model, r.created_at
                        FROM ai_review r JOIN place d ON d.id = r.destination_id JOIN guide_entry e ON e.id = r.entry_id""" + where + """
                         ORDER BY r.confidence DESC, r.created_at LIMIT :limit OFFSET :offset""")
                .param("slug", destination).param("limit", s).param("offset", p * s)
                .query((rs, i) -> new Flag(rs.getObject("id", UUID.class), rs.getObject("entry_id", UUID.class),
                        rs.getString("destination_slug"), rs.getString("destination_name"), rs.getString("section"),
                        rs.getString("title"), rs.getString("area"), rs.getString("description"), rs.getBoolean("fits_section"),
                        rs.getString("in_destination"),
                        rs.getString("issues") == null || rs.getString("issues").isEmpty() ? List.of() : List.of(rs.getString("issues").split(",")),
                        rs.getString("suggested_section"), rs.getDouble("confidence"), rs.getString("reason"), rs.getString("model"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
        return PageResponse.of(items, p, s, total);
    }

    @PostMapping("/flags/{id}/accept")
    @Operation(summary = "Agree with the flag: the listing is hidden from the guide, with the reason recorded")
    public Map<String, Object> accept(@PathVariable UUID id) {
        requireOpen(id);
        fixes.hide(id, current.require().id(), false);
        return Map.of("status", "hidden", "message", "The listing is hidden from the guide.");
    }

    @PostMapping("/flags/{id}/move")
    @Operation(summary = "Move the listing to the section where it belongs (by default the one the reviewer suggests)")
    public Map<String, Object> move(@PathVariable UUID id,
                                    @org.springframework.web.bind.annotation.RequestBody(required = false) Map<String, String> body) {
        requireOpen(id);
        String to = body != null && body.get("section") != null ? body.get("section")
                : jdbc.sql("SELECT suggested_section FROM ai_review WHERE id = :id").param("id", id).query(String.class).optional().orElse(null);
        fixes.move(id, to == null ? "" : to, current.require().id(), false);
        return Map.of("status", "moved", "message", "Moved to " + SECTION_NAMES.getOrDefault(to, to) + ".");
    }

    private static final Map<String, String> SECTION_NAMES = Map.of("eat", "places to eat", "drink", "cafés and drinks",
            "stay", "places to stay", "see", "sights", "do", "things to do", "buy", "shopping");

    @PostMapping("/flags/{id}/dismiss")
    @Operation(summary = "Disagree with the flag: the listing stays as it is")
    public Map<String, Object> dismiss(@PathVariable UUID id) {
        requireOpen(id);
        fixes.keep(id, current.require().id(), false, false);
        return Map.of("status", "dismissed", "message", "The listing stays in the guide.");
    }

    @PostMapping("/flags/{id}/undo")
    @Operation(summary = "Reverse a move or a hide (made automatically or by an admin)")
    public Map<String, Object> undo(@PathVariable UUID id) {
        fixes.undo(id, current.require().id());
        return Map.of("status", "undone", "message", "Undone: the listing is back as the source has it.");
    }

    public record Decision(UUID id, String destinationSlug, String destinationName, String title, String section, String resolution,
                           String previousSection, boolean automatic, String reason, double confidence, OffsetDateTime decidedAt) {}

    @GetMapping("/decisions")
    @Operation(summary = "Recent decisions on flags, newest first; automatic ones can be undone")
    public PageResponse<Decision> decisions(@RequestParam(required = false) Boolean automatic, @RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size) {
        int p = PageResponse.clampPage(page);
        int sz = PageResponse.clampSize(size, 25);
        String where = " WHERE r.status IN ('accepted', 'dismissed') AND r.resolution IS NOT NULL"
                + " AND (CAST(:auto AS boolean) IS NULL OR r.decided_automatically = :auto)";
        long total = jdbc.sql("SELECT count(*) FROM ai_review r" + where).param("auto", automatic).query(Long.class).single();
        List<Decision> items = jdbc.sql("""
                        SELECT r.id, d.slug AS destination_slug, d.name AS destination_name, e.title, e.section, r.resolution,
                               r.previous_section, r.decided_automatically AS automatic, r.reason, r.confidence, r.resolved_at AS decided_at
                        FROM ai_review r JOIN guide_entry e ON e.id = r.entry_id JOIN place d ON d.id = r.destination_id""" + where + """
                         ORDER BY r.resolved_at DESC LIMIT :limit OFFSET :offset""")
                .param("auto", automatic).param("limit", sz).param("offset", p * sz).query(Decision.class).list();
        return PageResponse.of(items, p, sz, total);
    }

    @PostMapping("/decide-now")
    @Operation(summary = "Apply the automatic rules to every open flag now (they also run every 30 minutes)")
    public Map<String, Object> decideNow() {
        int n = decider.sweep();
        return Map.of("decided", n, "message", n + " flags decided automatically. The rest need a person.");
    }

    private void requireOpen(UUID id) {
        boolean open = jdbc.sql("SELECT EXISTS (SELECT 1 FROM ai_review WHERE id = :id AND status = 'open')").param("id", id)
                .query(Boolean.class).single();
        if (!open) {
            throw ApiException.notFound("Open flag");
        }
    }

    @PostMapping("/destinations/{slug}")
    @Operation(summary = "Queue an AI review of one destination's listings")
    public Map<String, Object> queue(@PathVariable String slug) {
        requireActive();
        PlaceRow p = places.findBySlug(slug).orElseThrow(() -> ApiException.notFound("Destination"));
        UUID job = jobs.enqueue("ai_review", null, p.id(), Map.of(), "ai_review:" + p.id(), 4, "admin");
        return Map.of("jobId", job, "message", "AI review of " + p.name() + " is queued.");
    }

    @PostMapping("/queue-guided")
    @Operation(summary = "Queue an AI review for every destination that has a guide, biggest guides first")
    public Map<String, Object> queueGuided() {
        requireActive();
        List<UUID> ids = jdbc.sql("""
                        SELECT p.id FROM place p JOIN destination_profile dp ON dp.place_id = p.id AND dp.coverage_status <> 'indexed'
                        ORDER BY (SELECT count(*) FROM guide_entry e WHERE e.destination_id = p.id AND e.status = 'published') DESC""")
                .query(UUID.class).list();
        int n = 0;
        for (UUID id : ids) {
            jobs.enqueue("ai_review", null, id, Map.of(), "ai_review:" + id, 3, "admin");
            n++;
        }
        return Map.of("queued", n, "message", n + " destinations queued. Reviews stop for the day when the request budget is used up and continue tomorrow.");
    }

    private void requireActive() {
        if (!props.active()) {
            throw ApiException.conflict("ai_disabled", "AI review is off. Set AI_REVIEW_ENABLED=true and GEMINI_KEY on the backend.");
        }
    }



    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
