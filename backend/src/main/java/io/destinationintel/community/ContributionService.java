package io.destinationintel.community;

import io.destinationintel.audit.AuditService;
import io.destinationintel.common.ApiException;
import io.destinationintel.common.Json;
import io.destinationintel.common.RateLimiter;
import io.destinationintel.common.UrlSafety;
import io.destinationintel.community.ContributionRepository.ContributionRow;
import io.destinationintel.community.UserRepository.AppUserRow;
import io.destinationintel.config.AppProperties;
import io.destinationintel.guide.GuideChanges;
import io.destinationintel.moderation.ModerationQueue;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Accepts contributions from signed-in contributors. New contributors' submissions are
 * queued for moderation; trusted contributors' non-destructive edits publish immediately;
 * conflict-of-interest submissions always need a steward. Nothing a client sends can raise
 * its own privileges or approve its own work.
 */
@Service
public class ContributionService {

    public record SubmitRequest(
            @NotBlank @Pattern(regexp = "correction|closure_report|duplicate_report|incorrect_report|new_place|new_tip")
            String type,
            @NotNull UUID destinationId,
            UUID targetFactId,
            UUID targetEntryId,
            @NotNull Map<String, Object> payload,
            @Size(max = 1000) String note,
            @Size(max = 2000) String evidenceUrl,
            boolean conflictOfInterest,
            @Size(max = 500) String coiDetails,
            boolean acknowledgePossibleDuplicate) {}

    public record SubmitResult(UUID id, String status, String message) {}

    private static final Set<String> DESTRUCTIVE = Set.of("closure_report", "duplicate_report", "incorrect_report");

    private final ContributionRepository contributions;
    private final ContributionApplier applier;
    private final ModerationQueue queue;
    private final TrustService trust;
    private final AuditService audit;
    private final GuideChanges changes;
    private final RateLimiter rateLimiter;
    private final Validator validator;
    private final JdbcClient jdbc;
    private final Json json;
    private final AppProperties props;

    public ContributionService(ContributionRepository contributions, ContributionApplier applier, ModerationQueue queue,
                               TrustService trust, AuditService audit, GuideChanges changes, RateLimiter rateLimiter,
                               Validator validator, JdbcClient jdbc, Json json, AppProperties props) {
        this.contributions = contributions;
        this.applier = applier;
        this.queue = queue;
        this.trust = trust;
        this.audit = audit;
        this.changes = changes;
        this.rateLimiter = rateLimiter;
        this.validator = validator;
        this.jdbc = jdbc;
        this.json = json;
        this.props = props;
    }

    @Transactional
    public SubmitResult submit(AppUserRow user, SubmitRequest req) {
        if (user.contributorLicenceAt() == null) {
            throw ApiException.conflict("licence_required",
                    "Please accept the contributor licence (CC BY-SA 4.0) before your first contribution.");
        }
        int limit = props.rateLimits().contributionsPerHour();
        rateLimiter.check("contribution", user.id().toString(), limit, Duration.ofHours(1));
        if (contributions.countSince(user.id(), OffsetDateTime.now().minusHours(1)) >= limit) {
            throw new io.destinationintel.common.RateLimitedException(Duration.ofMinutes(10));
        }
        if (!jdbc.sql("SELECT EXISTS (SELECT 1 FROM place WHERE id = :d AND lifecycle_status = 'active')")
                .param("d", req.destinationId()).query(Boolean.class).single()) {
            throw ApiException.notFound("Destination");
        }

        Map<String, Object> payload = validatedPayload(req);
        String evidenceUrl = null;
        if (StringUtils.hasText(req.evidenceUrl())) {
            evidenceUrl = UrlSafety.normalize(req.evidenceUrl()).orElseThrow(() -> ApiException.badRequest("invalid_url",
                    "The evidence link must be a public http(s) address."));
        }
        if (req.conflictOfInterest() && !StringUtils.hasText(req.coiDetails())) {
            throw ApiException.badRequest("coi_details_required",
                    "Please describe your connection to this place (for example, owner or employee).");
        }

        String targetVolatility = null;
        String targetKind = null;
        switch (req.type()) {
            case "correction" -> {
                record Target(String status, UUID destinationId, String volatility, String kind) {}
                Target t = jdbc.sql("SELECT status, destination_id, volatility, kind FROM fact WHERE id = :id")
                        .param("id", req.targetFactId()).query(Target.class).optional()
                        .orElseThrow(() -> ApiException.badRequest("invalid_target", "A correction needs an existing fact."));
                if (!t.status().equals("published") || !t.destinationId().equals(req.destinationId())) {
                    throw ApiException.badRequest("invalid_target", "Only published facts of this destination can be corrected.");
                }
                targetVolatility = t.volatility();
                targetKind = t.kind();
            }
            case "closure_report", "duplicate_report" -> requireEntry(req.targetEntryId(), req.destinationId());
            case "incorrect_report" -> {
                if (req.targetFactId() == null && req.targetEntryId() == null) {
                    throw ApiException.badRequest("invalid_target", "Choose the fact or entry that is incorrect.");
                }
                if (req.targetEntryId() != null) {
                    requireEntry(req.targetEntryId(), req.destinationId());
                }
            }
            case "new_place" -> {
                if (!req.acknowledgePossibleDuplicate()) {
                    List<Map<String, Object>> similar = similarEntries(req.destinationId(), String.valueOf(payload.get("name")));
                    if (!similar.isEmpty()) {
                        throw ApiException.conflict("possible_duplicate",
                                "Similar places are already in this guide. Check them, or confirm that yours is different.",
                                Map.of("candidates", similar));
                    }
                }
            }
            default -> { }
        }
        validateContent(req, payload, targetKind);
        if (req.type().equals("duplicate_report")) {
            UUID other = UUID.fromString(String.valueOf(payload.get("duplicateOfEntryId")));
            if (other.equals(req.targetEntryId())) {
                throw ApiException.badRequest("invalid_duplicate", "An entry cannot be a duplicate of itself.");
            }
            requireEntry(other, req.destinationId());
        }
        if (contributions.hasPendingDuplicate(user.id(), req.type(), req.targetFactId(), req.targetEntryId(), req.destinationId())
                && !req.type().startsWith("new_")) {
            throw ApiException.conflict("duplicate_submission", "You already have a pending submission for this item.");
        }

        boolean highImpact = DESTRUCTIVE.contains(req.type()) || req.conflictOfInterest()
                || "volatile".equals(targetVolatility) || "price".equals(targetKind) || "hours".equals(targetKind);
        UUID id = contributions.insert(user.id(), req.type(), req.destinationId(), req.targetFactId(), req.targetEntryId(),
                payload, trimToNull(req.note()), evidenceUrl, req.conflictOfInterest(), trimToNull(req.coiDetails()), highImpact);
        audit.record(user.id(), "contribution.submitted", "contribution", id, req.destinationId(),
                Map.of("type", req.type(), "highImpact", highImpact, "conflictOfInterest", req.conflictOfInterest()));

        boolean autoAccept = !req.conflictOfInterest() && (user.isAdmin() || "steward".equals(user.tier())
                || "trusted".equals(user.tier()) && !DESTRUCTIVE.contains(req.type()));
        if (autoAccept) {
            ContributionRow row = contributions.findForUpdate(id).orElseThrow();
            ContributionApplier.Applied applied = applier.apply(row);
            contributions.decide(id, "accepted", "Published directly: submitted by a " + (user.isAdmin() ? "maintainer" : user.tier() + " contributor"),
                    null, applied.entryId(), applied.factId());
            audit.record(user.id(), "contribution.accepted", "contribution", id, req.destinationId(),
                    Map.of("mode", "trusted_auto_publish"));
            trust.evaluate(user.id());
            changes.destinationChanged(req.destinationId());
            return new SubmitResult(id, "accepted", "Thanks! Your contribution is published.");
        }

        List<String> reasons = new ArrayList<>();
        if (req.conflictOfInterest()) {
            reasons.add("conflict_of_interest");
        }
        if (highImpact) {
            reasons.add("high_impact");
        }
        reasons.add("contributor_tier");
        String role = highImpact ? "steward" : "trusted";
        queue.open("contribution", id, req.destinationId(), reasons, role, highImpact ? 10 : 5);
        return new SubmitResult(id, "pending", highImpact
                ? "Thanks! A city steward will review this before it is published."
                : "Thanks! Another contributor or a steward will review this before it is published.");
    }

    /** Withdraws a pending contribution, or removes the personal link from a decided one. */
    @Transactional
    public String withdraw(AppUserRow user, UUID contributionId) {
        ContributionRow c = contributions.findForUpdate(contributionId)
                .filter(r -> user.id().equals(r.userId()))
                .orElseThrow(() -> ApiException.notFound("Contribution"));
        if (c.status().equals("pending")) {
            jdbc.sql("UPDATE contribution SET status = 'withdrawn', updated_at = now() WHERE id = :id").param("id", c.id()).update();
            queue.resolve("contribution", c.id(), "withdrawn", user.id());
            audit.record(user.id(), "contribution.withdrawn", "contribution", c.id(), c.destinationId(), Map.of());
            return "withdrawn";
        }
        jdbc.sql("UPDATE contribution SET user_id = NULL, updated_at = now() WHERE id = :id").param("id", c.id()).update();
        jdbc.sql("UPDATE evidence SET contributor_user_id = NULL WHERE fact_id IN (SELECT id FROM fact WHERE contribution_id = :id)")
                .param("id", c.id()).update();
        audit.record(user.id(), "contribution.unlinked", "contribution", c.id(), c.destinationId(), Map.of());
        return "unlinked";
    }

    private Map<String, Object> validatedPayload(SubmitRequest req) {
        Class<?> type = switch (req.type()) {
            case "correction" -> ContributionPayloads.Correction.class;
            case "closure_report" -> ContributionPayloads.Closure.class;
            case "duplicate_report" -> ContributionPayloads.Duplicate.class;
            case "incorrect_report" -> ContributionPayloads.Incorrect.class;
            case "new_place" -> ContributionPayloads.NewPlace.class;
            case "new_tip" -> ContributionPayloads.NewTip.class;
            default -> throw ApiException.badRequest("invalid_type", "Unknown contribution type.");
        };
        Object typed;
        try {
            typed = json.convert(req.payload(), type);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("invalid_payload", "The submission could not be read.");
        }
        Set<ConstraintViolation<Object>> violations = validator.validate(typed);
        if (!violations.isEmpty()) {
            List<Map<String, String>> errors = violations.stream()
                    .map(v -> Map.of("field", "payload." + v.getPropertyPath(), "message", v.getMessage())).toList();
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "validation_failed",
                    "Some fields are invalid.", Map.of("errors", errors));
        }
        Map<String, Object> clean = new LinkedHashMap<>();
        json.map(json.write(typed)).forEach((k, v) -> {
            if (v instanceof String s) {
                String t = s.replaceAll("[\\p{Cntrl}&&[^\\n]]", "").trim();
                if (!t.isEmpty()) {
                    clean.put(k, t);
                }
            } else if (v != null) {
                clean.put(k, v);
            }
        });
        return clean;
    }

    private void requireEntry(UUID entryId, UUID destinationId) {
        if (entryId == null) {
            throw ApiException.badRequest("invalid_target", "Choose the entry this report is about.");
        }
        boolean ok = jdbc.sql("SELECT EXISTS (SELECT 1 FROM guide_entry WHERE id = :e AND destination_id = :d AND status = 'published')")
                .param("e", entryId).param("d", destinationId).query(Boolean.class).single();
        if (!ok) {
            throw ApiException.badRequest("invalid_target", "That entry is not a published part of this guide.");
        }
    }

    private List<Map<String, Object>> similarEntries(UUID destinationId, String name) {
        return jdbc.sql("""
                        SELECT id, title, section FROM guide_entry
                        WHERE destination_id = :d AND status = 'published'
                          AND similarity(title_normalized, idi_normalize(:name)) >= 0.55
                        ORDER BY similarity(title_normalized, idi_normalize(:name)) DESC LIMIT 5
                        """)
                .param("d", destinationId).param("name", name)
                .query((rs, i) -> Map.<String, Object>of("id", rs.getObject("id", UUID.class), "title", rs.getString("title"),
                        "section", rs.getString("section")))
                .list();
    }

    /** Highest price accepted for one item or night, in rupees; anything above is a typo or not a price. */
    static final int MAX_PLAUSIBLE_PRICE = 500_000;

    /**
     * Content checks beyond field formats: a submitted location must be plausible for the
     * destination, and prices must be readable rupee amounts in a believable range.
     */
    void validateContent(SubmitRequest req, Map<String, Object> payload, String targetKind) {
        if (req.type().equals("new_place")) {
            Object lat = payload.get("latitude");
            Object lon = payload.get("longitude");
            if ((lat == null) != (lon == null)) {
                throw ApiException.badRequest("incomplete_location", "Give both latitude and longitude, or neither.");
            }
            if (lat instanceof Number la && lon instanceof Number lo) {
                record Dest(String kind, Double latitude, Double longitude, String name) {}
                Dest d = jdbc.sql("SELECT kind, latitude, longitude, name FROM place WHERE id = :d").param("d", req.destinationId())
                        .query(Dest.class).single();
                String flag = io.destinationintel.common.GeoPlausibility.flag(d.kind(), d.latitude(), d.longitude(),
                        la.doubleValue(), lo.doubleValue());
                if (flag != null) {
                    throw ApiException.badRequest("implausible_location", flag.equals("outside_india")
                            ? "That location is outside India."
                            : "That location is more than " + Math.round(io.destinationintel.common.GeoPlausibility.limitKm(d.kind()))
                                    + " km from " + d.name() + ". Check the coordinates, or add the place to the right destination.");
                }
            }
            checkPrice(payload.get("price"));
        }
        if (req.type().equals("correction") && "price".equals(targetKind)) {
            checkPrice(payload.get("value"));
        }
    }

    static void checkPrice(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return;
        }
        String text = String.valueOf(value);
        var parsed = io.destinationintel.guide.PriceParser.parse(text);
        if (parsed.isEmpty()) {
            throw ApiException.badRequest("unreadable_price",
                    "Give the price as an amount in rupees, for example \"₹150\" or \"₹100–200 per person\", or \"free\".");
        }
        var p = parsed.get();
        if (!p.free() && (p.min() <= 0 || p.max() < p.min() || p.max() > MAX_PLAUSIBLE_PRICE)) {
            throw ApiException.badRequest("implausible_price", "That price does not look right; check the amount.");
        }
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
