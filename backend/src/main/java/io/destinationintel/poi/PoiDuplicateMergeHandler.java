package io.destinationintel.poi;

import io.destinationintel.ingestion.JobHandler;
import io.destinationintel.ingestion.JobRepository.JobRow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Job "poi_duplicate_merge": applies to earlier decisions what resolution now does for new ones. Places
 * that a record matched alongside the place it joined are merged when the evidence says they are one, and
 * earlier "names differ" decisions get the shadow rule's verdict. Safe to run again.
 */
@Component
public class PoiDuplicateMergeHandler implements JobHandler {

    private record Duplicate(UUID joined, UUID other) {}

    private record Named(UUID reviewId, String recordName, String candidateName, double distanceM) {}

    private final JdbcClient jdbc;
    private final PoiMerger merger;

    public PoiDuplicateMergeHandler(JdbcClient jdbc, PoiMerger merger) {
        this.jdbc = jdbc;
        this.merger = merger;
    }

    @Override
    public String type() {
        return "poi_duplicate_merge";
    }

    @Override
    public Map<String, Object> run(JobRow job) {
        List<Duplicate> duplicates = jdbc.sql("""
                        SELECT DISTINCT r.poi_id AS joined, m.candidate_poi_id AS other
                        FROM poi_match_review m JOIN poi_source_record r ON r.id = m.record_id AND r.deleted_at IS NULL
                        WHERE m.resolution = 'another_candidate_matched_better' AND r.poi_id IS NOT NULL AND m.candidate_poi_id IS NOT NULL
                        """).query(Duplicate.class).list();
        int merged = 0;
        for (Duplicate d : duplicates) {
            merged += merger.mergeIfSame(d.joined(), d.other()) ? 1 : 0;
        }
        List<Named> names = jdbc.sql("""
                        SELECT m.id AS review_id, r.name AS record_name, p.name AS candidate_name, ST_Distance(r.geog, p.geog) AS distance_m
                        FROM poi_match_review m JOIN poi_source_record r ON r.id = m.record_id AND r.deleted_at IS NULL
                        JOIN poi p ON p.id = m.candidate_poi_id AND p.status = 'active'
                        WHERE m.resolution = 'names_differ' AND m.shadow_rule IS NULL
                        """).query(Named.class).list();
        int shadow = 0;
        for (Named n : names) {
            if (MatchEvidence.shadowNameMatch(n.recordName(), n.candidateName(), n.distanceM())) {
                jdbc.sql("UPDATE poi_match_review SET shadow_rule = :rule WHERE id = :id")
                        .param("rule", MatchEvidence.SHADOW_RULE).param("id", n.reviewId()).update();
                shadow++;
            }
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("pairsConsidered", duplicates.size());
        stats.put("placesMerged", merged);
        stats.put("namesDifferChecked", names.size());
        stats.put("shadowWouldMatch", shadow);
        return stats;
    }
}
