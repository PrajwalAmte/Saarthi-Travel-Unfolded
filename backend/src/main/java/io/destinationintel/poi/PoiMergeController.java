package io.destinationintel.poi;

import io.destinationintel.audit.AuditService;
import io.destinationintel.community.CurrentUser;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Administrators see the merges made automatically, can undo any of them, and can read what the shadow rule would do. */
@RestController
@Tag(name = "Administration")
public class PoiMergeController {

    public record MergeItem(UUID id, String keptName, String mergedName, String rule, double distanceM, OffsetDateTime createdAt,
                            OffsetDateTime undoneAt) {}

    public record ShadowItem(String recordName, String candidateName, double distanceM) {}

    private final JdbcClient jdbc;
    private final PoiMerger merger;
    private final CurrentUser current;
    private final AuditService audit;

    public PoiMergeController(JdbcClient jdbc, PoiMerger merger, CurrentUser current, AuditService audit) {
        this.jdbc = jdbc;
        this.merger = merger;
        this.current = current;
        this.audit = audit;
    }

    @GetMapping("/api/admin/poi-merges")
    @Operation(summary = "Recent merges of map places, and pairs the shadow rule would match")
    public Map<String, Object> recent(@RequestParam(defaultValue = "20") int limit) {
        int lim = Math.max(1, Math.min(limit, 200));
        long total = jdbc.sql("SELECT count(*) FROM poi_merge WHERE undone_at IS NULL").query(Long.class).single();
        List<MergeItem> items = jdbc.sql("""
                        SELECT m.id, k.name AS kept_name, g.name AS merged_name, m.rule, m.distance_m, m.created_at, m.undone_at
                        FROM poi_merge m JOIN poi k ON k.id = m.kept_poi_id JOIN poi g ON g.id = m.merged_poi_id
                        ORDER BY m.created_at DESC, m.id LIMIT :lim
                        """).param("lim", lim).query(MergeItem.class).list();
        long shadowTotal = jdbc.sql("SELECT count(*) FROM poi_match_review WHERE shadow_rule IS NOT NULL").query(Long.class).single();
        List<ShadowItem> shadow = jdbc.sql("""
                        SELECT r.name AS record_name, p.name AS candidate_name, ST_Distance(r.geog, p.geog) AS distance_m
                        FROM poi_match_review m JOIN poi_source_record r ON r.id = m.record_id JOIN poi p ON p.id = m.candidate_poi_id
                        WHERE m.shadow_rule IS NOT NULL ORDER BY md5(m.id::text) LIMIT :lim
                        """).param("lim", lim).query(ShadowItem.class).list();
        return Map.of("total", total, "items", items, "shadowTotal", shadowTotal, "shadowSample", shadow);
    }

    @PostMapping("/api/admin/poi-merges/{id}/undo")
    @Operation(summary = "Undo a merge: both places return as they were, and the pair is not merged again automatically")
    @Transactional
    public Map<String, String> undo(@PathVariable UUID id) {
        UUID user = current.require().id();
        merger.undo(id, user);
        audit.record(user, "poi_merge.undone", "poi_merge", id, null, Map.of());
        return Map.of("status", "undone");
    }
}
