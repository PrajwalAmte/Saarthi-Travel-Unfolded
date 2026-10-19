package io.destinationintel.poi;

import io.destinationintel.audit.AuditService;
import io.destinationintel.common.ApiException;
import io.destinationintel.community.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Review of uncertain POI matches (administrators). Matching never merges a weak candidate on its
 * own; a reviewer decides "same place" (the record joins the candidate POI) or "distinct" (the
 * pair is never proposed again and the record becomes its own POI at the next import).
 */
@RestController
@Tag(name = "Administration")
public class PoiMatchReviewController {

    public record ReviewItem(UUID id, double score, String reasons, String recordSource, String recordExternalId,
                             String recordName, String recordCategory, double recordLatitude, double recordLongitude,
                             UUID candidatePoiId, String candidateName, String candidateCategory, Double distanceM,
                             String candidateSources) {}

    public record Decision(@NotBlank @Pattern(regexp = "same|distinct") String decision) {}

    private final JdbcClient jdbc;
    private final CurrentUser current;
    private final AuditService audit;

    public PoiMatchReviewController(JdbcClient jdbc, CurrentUser current, AuditService audit) {
        this.jdbc = jdbc;
        this.current = current;
        this.audit = audit;
    }

    @GetMapping("/api/admin/poi-matches")
    @Operation(summary = "Open uncertain matches between a source record and an existing mapped place")
    public Map<String, Object> open(@RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) String source) {
        int lim = Math.max(1, Math.min(limit, 200));
        long total = jdbc.sql("""
                        SELECT count(*) FROM poi_match_review m JOIN poi_source_record r ON r.id = m.record_id
                        WHERE m.status = 'open' AND m.candidate_poi_id IS NOT NULL AND (CAST(:s AS text) IS NULL OR r.source_id = :s)
                        """).param("s", source).query(Long.class).single();
        List<ReviewItem> items = jdbc.sql("""
                        SELECT m.id, m.score, m.reasons::text AS reasons, r.source_id AS record_source,
                               r.external_id AS record_external_id, r.name AS record_name, r.category AS record_category,
                               r.latitude AS record_latitude, r.longitude AS record_longitude,
                               p.id AS candidate_poi_id, p.name AS candidate_name, p.category AS candidate_category,
                               round(ST_Distance(p.geog, r.geog)::numeric, 1)::float8 AS distance_m,
                               (SELECT string_agg(DISTINCT o.source_id, ', ') FROM poi_source_record o
                                WHERE o.poi_id = p.id AND o.deleted_at IS NULL) AS candidate_sources
                        FROM poi_match_review m JOIN poi_source_record r ON r.id = m.record_id JOIN poi p ON p.id = m.candidate_poi_id
                        WHERE m.status = 'open' AND (CAST(:s AS text) IS NULL OR r.source_id = :s)
                        ORDER BY m.score DESC, m.id LIMIT :lim
                        """).param("s", source).param("lim", lim).query(ReviewItem.class).list();
        return Map.of("total", total, "items", items);
    }

    @PostMapping("/api/admin/poi-matches/{id}")
    @Operation(summary = "Decide an uncertain match: the same place, or distinct places")
    @Transactional
    public Map<String, String> decide(@PathVariable UUID id, @Valid @RequestBody Decision body) {
        var user = current.require();
        record Review(UUID recordId, UUID candidatePoiId, String status) {}
        Review rv = jdbc.sql("SELECT record_id, candidate_poi_id, status FROM poi_match_review WHERE id = :id FOR UPDATE")
                .param("id", id).query(Review.class).optional().orElseThrow(() -> ApiException.notFound("Match review"));
        if (!rv.status().equals("open")) {
            throw ApiException.conflict("already_decided", "This match has already been decided.");
        }
        if (body.decision().equals("same")) {
            jdbc.sql("""
                            UPDATE poi_source_record SET poi_id = :p, match_confidence = 'high', match_method = 'reviewed', match_score = NULL
                            WHERE id = :r
                            """).param("p", rv.candidatePoiId()).param("r", rv.recordId()).update();
            jdbc.sql("""
                            UPDATE poi_match_review SET status = CASE WHEN id = :id THEN 'merged' ELSE 'distinct' END,
                                   resolution = 'reviewer', resolved_at = now(), resolved_by = :u
                            WHERE record_id = :r AND status = 'open'
                            """).param("id", id).param("r", rv.recordId()).param("u", user.id()).update();
        } else {
            jdbc.sql("UPDATE poi_match_review SET status = 'distinct', resolution = 'reviewer', resolved_at = now(), resolved_by = :u WHERE id = :id")
                    .param("id", id).param("u", user.id()).update();
            // With no open candidates left the record is resolved as a new place at the next import.
            jdbc.sql("""
                            UPDATE poi_source_record r SET match_confidence = NULL, match_method = NULL
                            WHERE r.id = :r AND r.match_confidence = 'possible'
                              AND NOT EXISTS (SELECT 1 FROM poi_match_review m WHERE m.record_id = r.id AND m.status = 'open')
                            """).param("r", rv.recordId()).update();
        }
        audit.record(user.id(), "poi_match.decided", "poi_match_review", id, null, Map.of("decision", body.decision()));
        return Map.of("outcome", body.decision().equals("same") ? "merged" : "distinct");
    }
}
