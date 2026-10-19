package io.destinationintel.moderation;

import io.destinationintel.audit.AuditService;
import io.destinationintel.common.ApiException;
import io.destinationintel.common.Json;
import io.destinationintel.common.PageResponse;
import io.destinationintel.community.ContributionApplier;
import io.destinationintel.community.ContributionRepository;
import io.destinationintel.community.ContributionRepository.ContributionRow;
import io.destinationintel.community.TrustService;
import io.destinationintel.community.UserRepository;
import io.destinationintel.community.UserRepository.AppUserRow;
import io.destinationintel.guide.GuideChanges;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moderation queue for trusted contributors, city stewards and admins. Trusted contributors
 * may only decide normal-impact items; high-impact, conflict-of-interest and abuse items
 * need a steward for that destination (or an admin). Nobody can decide their own items.
 */
@Service
public class ModerationService {

    public record QueueItem(
            UUID id,
            String itemType,
            UUID itemId,
            UUID destinationId,
            String destinationSlug,
            String destinationName,
            List<Object> reasons,
            String requiresRole,
            int priority,
            String status,
            String resolution,
            OffsetDateTime createdAt,
            OffsetDateTime resolvedAt,
            Map<String, Object> details) {}

    record ItemRow(UUID id, String itemType, UUID itemId, UUID destinationId, String destinationSlug,
                   String destinationName, String reasons, String requiresRole, int priority, String status,
                   String resolution, OffsetDateTime createdAt, OffsetDateTime resolvedAt) {}

    private final JdbcClient jdbc;
    private final Json json;
    private final ContributionRepository contributions;
    private final ContributionApplier applier;
    private final ModerationQueue queue;
    private final TrustService trust;
    private final UserRepository users;
    private final AuditService audit;
    private final GuideChanges changes;

    public ModerationService(JdbcClient jdbc, Json json, ContributionRepository contributions, ContributionApplier applier,
                             ModerationQueue queue, TrustService trust, UserRepository users, AuditService audit,
                             GuideChanges changes) {
        this.jdbc = jdbc;
        this.json = json;
        this.contributions = contributions;
        this.applier = applier;
        this.queue = queue;
        this.trust = trust;
        this.users = users;
        this.audit = audit;
        this.changes = changes;
    }

    private static final String VISIBLE = """
            FROM moderation_item m
            LEFT JOIN place p ON p.id = m.destination_id
            WHERE m.status = :status
              AND (CAST(:dest AS uuid) IS NULL OR m.destination_id = :dest)
              AND (:admin
                   OR m.requires_role = 'trusted'
                   OR (:steward AND EXISTS (
                        SELECT 1 FROM steward_assignment sa WHERE sa.user_id = :viewer AND (sa.place_id = m.destination_id
                          OR EXISTS (SELECT 1 FROM place_relation r WHERE r.child_id = m.destination_id AND r.parent_id = sa.place_id)))))
              AND NOT EXISTS (SELECT 1 FROM contribution c WHERE m.item_type = 'contribution' AND c.id = m.item_id AND c.user_id = :viewer)
              AND NOT EXISTS (SELECT 1 FROM verification v WHERE m.item_type = 'verification' AND v.id = m.item_id AND v.user_id = :viewer)
            """;

    public PageResponse<QueueItem> queue(AppUserRow viewer, String status, UUID destinationId, int page, int size) {
        String st = "resolved".equals(status) ? "resolved" : "open";
        long total = jdbc.sql("SELECT count(*) " + VISIBLE).params(visibility(viewer, st, destinationId))
                .query(Long.class).single();
        List<ItemRow> rows = jdbc.sql("""
                        SELECT m.id, m.item_type, m.item_id, m.destination_id, p.slug AS destination_slug,
                               p.name AS destination_name, m.reasons::text AS reasons, m.requires_role, m.priority, m.status,
                               m.resolution, m.created_at, m.resolved_at
                        """ + VISIBLE + """
                         ORDER BY m.priority DESC, m.created_at LIMIT :limit OFFSET :offset""")
                .params(visibility(viewer, st, destinationId))
                .param("limit", size).param("offset", page * size)
                .query(ItemRow.class).list();
        List<QueueItem> items = rows.stream().map(r -> new QueueItem(r.id(), r.itemType(), r.itemId(), r.destinationId(),
                r.destinationSlug(), r.destinationName(), json.list(r.reasons()), r.requiresRole(), r.priority(), r.status(),
                r.resolution(), r.createdAt(), r.resolvedAt(), details(r))).toList();
        return PageResponse.of(items, page, size, total);
    }

    private Map<String, Object> visibility(AppUserRow viewer, String status, UUID destinationId) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("status", status);
        p.put("dest", destinationId);
        p.put("admin", viewer.isAdmin());
        p.put("steward", "steward".equals(viewer.tier()));
        p.put("viewer", viewer.id());
        return p;
    }

    private Map<String, Object> details(ItemRow r) {
        Map<String, Object> d = new LinkedHashMap<>();
        switch (r.itemType()) {
            case "contribution" -> jdbc.sql("""
                            SELECT c.type, c.payload::text AS payload, c.note, c.evidence_url, c.conflict_of_interest, c.coi_details,
                                   c.high_impact, c.status, c.created_at, c.target_fact_id, c.target_entry_id,
                                   u.display_name AS submitter, u.tier AS submitter_tier,
                                   (SELECT count(*) FROM contribution x WHERE x.user_id = c.user_id AND x.status = 'accepted') AS submitter_accepted,
                                   f.kind AS target_kind, f.value_text AS target_value, f.status AS target_status,
                                   coalesce(e.title, fe.title) AS target_title
                            FROM contribution c
                            LEFT JOIN app_user u ON u.id = c.user_id
                            LEFT JOIN fact f ON f.id = c.target_fact_id
                            LEFT JOIN guide_entry fe ON fe.id = f.entry_id
                            LEFT JOIN guide_entry e ON e.id = c.target_entry_id
                            WHERE c.id = :id
                            """).param("id", r.itemId())
                    .query((rs, i) -> {
                        d.put("type", rs.getString("type"));
                        d.put("payload", json.map(rs.getString("payload")));
                        d.put("note", rs.getString("note"));
                        d.put("evidenceUrl", rs.getString("evidence_url"));
                        d.put("conflictOfInterest", rs.getBoolean("conflict_of_interest"));
                        d.put("coiDetails", rs.getString("coi_details"));
                        d.put("highImpact", rs.getBoolean("high_impact"));
                        d.put("contributionStatus", rs.getString("status"));
                        d.put("submittedAt", rs.getObject("created_at", OffsetDateTime.class));
                        d.put("submitter", rs.getString("submitter"));
                        d.put("submitterTier", rs.getString("submitter_tier"));
                        d.put("submitterAccepted", rs.getLong("submitter_accepted"));
                        d.put("targetFactId", rs.getObject("target_fact_id", UUID.class));
                        d.put("targetEntryId", rs.getObject("target_entry_id", UUID.class));
                        d.put("targetKind", rs.getString("target_kind"));
                        d.put("targetValue", rs.getString("target_value"));
                        d.put("targetStatus", rs.getString("target_status"));
                        d.put("targetTitle", rs.getString("target_title"));
                        return 1;
                    }).list();
            case "verification" -> jdbc.sql("""
                            SELECT v.verdict, v.note, v.evidence_url, v.status, v.created_at, u.display_name AS submitter,
                                   f.id AS fact_id, f.kind, f.value_text, f.volatility, e.title AS entry_title
                            FROM verification v
                            LEFT JOIN app_user u ON u.id = v.user_id
                            JOIN fact f ON f.id = v.fact_id
                            LEFT JOIN guide_entry e ON e.id = f.entry_id
                            WHERE v.id = :id
                            """).param("id", r.itemId())
                    .query((rs, i) -> {
                        d.put("verdict", rs.getString("verdict"));
                        d.put("note", rs.getString("note"));
                        d.put("evidenceUrl", rs.getString("evidence_url"));
                        d.put("verificationStatus", rs.getString("status"));
                        d.put("submittedAt", rs.getObject("created_at", OffsetDateTime.class));
                        d.put("submitter", rs.getString("submitter"));
                        d.put("factId", rs.getObject("fact_id", UUID.class));
                        d.put("factKind", rs.getString("kind"));
                        d.put("factValue", rs.getString("value_text"));
                        d.put("volatility", rs.getString("volatility"));
                        d.put("entryTitle", rs.getString("entry_title"));
                        return 1;
                    }).list();
            case "abuse_flag" -> jdbc.sql("""
                            SELECT f.target_type, f.target_id, f.reason, f.details, f.created_at, u.display_name AS reporter
                            FROM abuse_flag f LEFT JOIN app_user u ON u.id = f.reporter_user_id WHERE f.id = :id
                            """).param("id", r.itemId())
                    .query((rs, i) -> {
                        d.put("targetType", rs.getString("target_type"));
                        d.put("targetId", rs.getObject("target_id", UUID.class));
                        d.put("reason", rs.getString("reason"));
                        d.put("details", rs.getString("details"));
                        d.put("submittedAt", rs.getObject("created_at", OffsetDateTime.class));
                        d.put("reporter", rs.getString("reporter"));
                        return 1;
                    }).list();
            default -> { }
        }
        return d;
    }

    /**
     * Decides an open item. For abuse flags, "accept" means the report is upheld and the
     * optional action is applied; "reject" dismisses it.
     */
    @Transactional
    public String decide(AppUserRow viewer, UUID itemId, String decision, String reason, String flagAction) {
        ItemRow item = jdbc.sql("""
                        SELECT m.id, m.item_type, m.item_id, m.destination_id, NULL AS destination_slug, NULL AS destination_name,
                               m.reasons::text AS reasons, m.requires_role, m.priority, m.status, m.resolution, m.created_at,
                               m.resolved_at
                        FROM moderation_item m WHERE m.id = :id FOR UPDATE
                        """)
                .param("id", itemId).query(ItemRow.class).optional()
                .orElseThrow(() -> ApiException.notFound("Moderation item"));
        if (!item.status().equals("open")) {
            throw ApiException.conflict("already_resolved", "This item has already been resolved.");
        }
        authorize(viewer, item);
        boolean accept = "accept".equals(decision);
        String note = reason == null || reason.isBlank() ? null : reason.trim();
        String outcome;
        switch (item.itemType()) {
            case "contribution" -> {
                ContributionRow c = contributions.findForUpdate(item.itemId()).orElseThrow(() -> ApiException.notFound("Contribution"));
                if (viewer.id().equals(c.userId())) {
                    throw ApiException.forbidden("You cannot review your own contribution.");
                }
                if (!c.status().equals("pending")) {
                    throw ApiException.conflict("already_decided", "This contribution has already been decided.");
                }
                if (accept) {
                    ContributionApplier.Applied applied = applier.apply(c);
                    contributions.decide(c.id(), "accepted", note, viewer.id(), applied.entryId(), applied.factId());
                    outcome = "accepted";
                } else {
                    contributions.decide(c.id(), "rejected", note, viewer.id(), null, null);
                    outcome = "rejected";
                }
                audit.record(viewer.id(), "contribution." + outcome, "contribution", c.id(), c.destinationId(),
                        note == null ? Map.of() : Map.of("reason", note));
                if (c.userId() != null) {
                    trust.evaluate(c.userId());
                }
                changes.destinationChanged(c.destinationId());
            }
            case "verification" -> {
                record V(UUID id, UUID factId, UUID userId, String verdict, String status) {}
                V v = jdbc.sql("SELECT id, fact_id, user_id, verdict, status FROM verification WHERE id = :id FOR UPDATE")
                        .param("id", item.itemId()).query(V.class).optional().orElseThrow(() -> ApiException.notFound("Verification"));
                if (viewer.id().equals(v.userId())) {
                    throw ApiException.forbidden("You cannot review your own verification.");
                }
                if (!v.status().equals("pending")) {
                    throw ApiException.conflict("already_decided", "This verification has already been decided.");
                }
                outcome = accept ? "accepted" : "rejected";
                jdbc.sql("""
                                UPDATE verification SET status = :s, decided_by = :by, decided_at = now(), decision_reason = :r
                                WHERE id = :id
                                """)
                        .param("s", outcome).param("by", viewer.id()).param("r", note).param("id", v.id()).update();
                if (accept && v.verdict().equals("confirm")) {
                    jdbc.sql("UPDATE fact SET last_verified_at = now(), updated_at = now() WHERE id = :f").param("f", v.factId()).update();
                }
                audit.record(viewer.id(), "verification." + outcome, "fact", v.factId(), item.destinationId(),
                        Map.of("verification", v.id()));
                if (v.userId() != null) {
                    trust.evaluate(v.userId());
                }
                changes.destinationChanged(item.destinationId());
            }
            case "abuse_flag" -> {
                outcome = accept ? "actioned" : "dismissed";
                String applied = accept ? applyFlagAction(viewer, item.itemId(), flagAction) : "none";
                jdbc.sql("""
                                UPDATE abuse_flag SET status = :s, resolved_by = :by, resolved_at = now(), resolution = :r
                                WHERE id = :id
                                """)
                        .param("s", outcome).param("by", viewer.id())
                        .param("r", (note == null ? "" : note + " ") + "(action: " + applied + ")").param("id", item.itemId()).update();
                audit.record(viewer.id(), "flag." + outcome, "abuse_flag", item.itemId(), item.destinationId(),
                        Map.of("action", applied));
                changes.destinationChanged(item.destinationId());
            }
            default -> throw new IllegalStateException("Unknown item type");
        }
        queue.resolve(item.itemType(), item.itemId(), outcome + (note == null ? "" : ": " + note), viewer.id());
        return outcome;
    }

    private String applyFlagAction(AppUserRow viewer, UUID flagId, String action) {
        record F(String targetType, UUID targetId) {}
        F f = jdbc.sql("SELECT target_type, target_id FROM abuse_flag WHERE id = :id").param("id", flagId)
                .query(F.class).single();
        String a = action == null ? "none" : action;
        switch (a) {
            case "none" -> { }
            case "hide_entry" -> {
                if (!f.targetType().equals("entry")) {
                    throw ApiException.badRequest("invalid_action", "Only entries can be hidden.");
                }
                jdbc.sql("UPDATE guide_entry SET status = 'hidden', status_reason = 'Hidden after an upheld abuse report', updated_at = now() WHERE id = :id")
                        .param("id", f.targetId()).update();
            }
            case "retract_fact" -> {
                if (!f.targetType().equals("fact")) {
                    throw ApiException.badRequest("invalid_action", "Only facts can be retracted.");
                }
                jdbc.sql("UPDATE fact SET status = 'retracted', updated_at = now() WHERE id = :id").param("id", f.targetId()).update();
            }
            case "reject_contribution" -> {
                if (!f.targetType().equals("contribution")) {
                    throw ApiException.badRequest("invalid_action", "Only contributions can be rejected.");
                }
                ContributionRow c = contributions.findForUpdate(f.targetId()).orElseThrow(() -> ApiException.notFound("Contribution"));
                if (c.status().equals("pending")) {
                    contributions.decide(c.id(), "rejected", "Rejected after an upheld abuse report", viewer.id(), null, null);
                    queue.resolve("contribution", c.id(), "rejected: abuse report upheld", viewer.id());
                } else if (c.status().equals("accepted")) {
                    applier.revert(c);
                    contributions.decide(c.id(), "reverted", "Reverted after an upheld abuse report", viewer.id(), null, null);
                }
                if (c.userId() != null) {
                    trust.evaluate(c.userId());
                }
            }
            case "suspend_user" -> {
                if (!viewer.isAdmin()) {
                    throw ApiException.forbidden("Only administrators can suspend accounts.");
                }
                UUID userId = f.targetType().equals("user") ? f.targetId()
                        : f.targetType().equals("contribution") ? contributions.find(f.targetId()).map(ContributionRow::userId).orElse(null)
                        : null;
                if (userId == null) {
                    throw ApiException.badRequest("invalid_action", "No account is associated with this report.");
                }
                users.setStatus(userId, "suspended");
                users.recordTrustEvent(userId, "suspended", null, null, "Abuse report upheld", viewer.id());
            }
            default -> throw ApiException.badRequest("invalid_action", "Unknown action.");
        }
        return a;
    }

    /** Reverts an accepted contribution (stewards for their destinations, admins anywhere). */
    @Transactional
    public void revert(AppUserRow viewer, UUID contributionId, String reason) {
        ContributionRow c = contributions.findForUpdate(contributionId).orElseThrow(() -> ApiException.notFound("Contribution"));
        if (!trust.canModerateDestination(viewer, c.destinationId())) {
            throw ApiException.forbidden("Only a steward for this destination or an administrator can revert contributions.");
        }
        if (!c.status().equals("accepted")) {
            throw ApiException.conflict("not_accepted", "Only accepted contributions can be reverted.");
        }
        applier.revert(c);
        contributions.decide(c.id(), "reverted", reason == null || reason.isBlank() ? "Reverted by a steward" : reason.trim(),
                viewer.id(), null, null);
        audit.record(viewer.id(), "contribution.reverted", "contribution", c.id(), c.destinationId(),
                Map.of("reason", reason == null ? "" : reason));
        if (c.userId() != null) {
            trust.evaluate(c.userId());
        }
        changes.destinationChanged(c.destinationId());
    }

    private void authorize(AppUserRow viewer, ItemRow item) {
        if (viewer.isAdmin()) {
            return;
        }
        if (item.requiresRole().equals("steward")) {
            if (!trust.canModerateDestination(viewer, item.destinationId())) {
                throw ApiException.forbidden("This item needs a steward for this destination.");
            }
            return;
        }
        if (!viewer.atLeastTrusted()) {
            throw ApiException.forbidden("Only trusted contributors can review submissions.");
        }
    }

    public List<String> reasonsOf(String json) {
        List<String> out = new ArrayList<>();
        this.json.list(json).forEach(o -> out.add(String.valueOf(o)));
        return out;
    }
}
