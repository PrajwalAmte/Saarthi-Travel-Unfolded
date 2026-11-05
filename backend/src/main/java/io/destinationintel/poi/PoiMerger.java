package io.destinationintel.poi;

import io.destinationintel.common.ApiException;
import io.destinationintel.common.Json;
import io.destinationintel.poi.MatchEvidence.Decision;
import io.destinationintel.poi.MatchEvidence.Outcome;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Merges map places that are one real place, deciding from the same evidence as record matching. A
 * merge can always be undone, and an undone pair is never merged again automatically.
 */
@Component
public class PoiMerger {

    /** Evidence strong enough to merge two established places. A name merely containing the other is not. */
    private static final Set<String> MERGE_RULES = Set.of("shared_phone", "shared_website", "same_name_same_spot",
            "same_name_nearby", "spelling_variant");

    private record Place(UUID id, String name, String categoryGroup, String attributes, long records) {}

    private final JdbcClient jdbc;
    private final Json json;

    public PoiMerger(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Merges the two places if the evidence says they are one. Returns whether it did. */
    @Transactional
    public boolean mergeIfSame(UUID first, UUID second) {
        if (first.equals(second)) {
            return false;
        }
        // Locked in a fixed order, so two merges touching the same places cannot deadlock.
        List<UUID> locked = jdbc.sql("""
                        SELECT id FROM poi WHERE id IN (:a, :b) AND status = 'active' ORDER BY id FOR UPDATE
                        """).param("a", first).param("b", second).query(UUID.class).list();
        boolean undone = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM poi_merge WHERE undone_at IS NOT NULL
                                       AND ((kept_poi_id = :a AND merged_poi_id = :b) OR (kept_poi_id = :b AND merged_poi_id = :a)))
                        """).param("a", first).param("b", second).query(Boolean.class).single();
        if (locked.size() != 2 || undone) {
            return false;
        }
        Place a = load(first);
        Place b = load(second);
        double distance = jdbc.sql("SELECT ST_Distance(x.geog, y.geog) FROM poi x, poi y WHERE x.id = :a AND y.id = :b")
                .param("a", first).param("b", second).query(Double.class).single();
        Decision decision = MatchEvidence.decide(side(a), side(b), distance);
        if (decision.outcome() != Outcome.SAME || !MERGE_RULES.contains(decision.rule())) {
            return false;
        }
        Place kept = b.records() > a.records() ? b : a;
        Place merged = kept == a ? b : a;
        jdbc.sql("""
                        WITH records AS (
                            UPDATE poi_source_record SET poi_id = :kept WHERE poi_id = :merged AND deleted_at IS NULL RETURNING id),
                        entries AS (
                            UPDATE guide_entry SET poi_id = :kept WHERE poi_id = :merged RETURNING id),
                        place AS (
                            SELECT seed_record_id FROM poi WHERE id = :merged)
                        INSERT INTO poi_merge (kept_poi_id, merged_poi_id, rule, distance_m, merged_seed_record_id, moved_records, moved_entries)
                        SELECT :kept, :merged, :rule, :distance, (SELECT seed_record_id FROM place),
                               coalesce((SELECT array_agg(id) FROM records), '{}'), coalesce((SELECT array_agg(id) FROM entries), '{}')
                        """).param("kept", kept.id()).param("merged", merged.id()).param("rule", decision.rule())
                .param("distance", distance).update();
        // Without its seed, a later change to that record cannot bring this place back.
        jdbc.sql("UPDATE poi SET status = 'removed', seed_record_id = NULL, updated_at = now() WHERE id = :id")
                .param("id", merged.id()).update();
        jdbc.sql("""
                        UPDATE poi_match_review m SET candidate_poi_id = :kept
                        WHERE m.candidate_poi_id = :merged AND m.status = 'open'
                          AND NOT EXISTS (SELECT 1 FROM poi_match_review o WHERE o.record_id = m.record_id AND o.candidate_poi_id = :kept)
                        """).param("kept", kept.id()).param("merged", merged.id()).update();
        return true;
    }

    /** Puts both places back as they were. Later records that joined the kept place stay with it. */
    @Transactional
    public void undo(UUID mergeId, UUID userId) {
        record Row(UUID keptPoiId, UUID mergedPoiId, UUID mergedSeedRecordId, boolean undone, boolean keptActive) {}
        Row row = jdbc.sql("""
                        SELECT m.kept_poi_id, m.merged_poi_id, m.merged_seed_record_id, m.undone_at IS NOT NULL AS undone,
                               k.status = 'active' AS kept_active
                        FROM poi_merge m JOIN poi k ON k.id = m.kept_poi_id WHERE m.id = :id FOR UPDATE OF m
                        """).param("id", mergeId).query(Row.class).optional().orElseThrow(() -> ApiException.notFound("Merge"));
        if (row.undone()) {
            throw ApiException.conflict("already_undone", "This merge has already been undone.");
        }
        if (!row.keptActive()) {
            throw ApiException.conflict("merged_again", "The place kept has since been merged into another. Undo that merge first.");
        }
        jdbc.sql("UPDATE poi SET status = 'active', seed_record_id = CAST(:seed AS uuid), updated_at = now() WHERE id = :id")
                .param("seed", row.mergedSeedRecordId() == null ? null : row.mergedSeedRecordId().toString()).param("id", row.mergedPoiId()).update();
        jdbc.sql("""
                        UPDATE poi_source_record r SET poi_id = m.merged_poi_id FROM poi_merge m
                        WHERE m.id = :id AND r.id = ANY (m.moved_records) AND r.poi_id = m.kept_poi_id
                        """).param("id", mergeId).update();
        jdbc.sql("""
                        UPDATE guide_entry e SET poi_id = m.merged_poi_id FROM poi_merge m
                        WHERE m.id = :id AND e.id = ANY (m.moved_entries) AND e.poi_id = m.kept_poi_id
                        """).param("id", mergeId).update();
        jdbc.sql("UPDATE poi_merge SET undone_at = now(), undone_by = :u WHERE id = :id").param("u", userId).param("id", mergeId).update();
    }

    private Place load(UUID id) {
        return jdbc.sql("""
                        SELECT p.id, p.name, p.category_group,
                               (SELECT jsonb_agg(o.attributes)::text FROM poi_source_record o WHERE o.poi_id = p.id AND o.deleted_at IS NULL) AS attributes,
                               (SELECT count(*) FROM poi_source_record o WHERE o.poi_id = p.id AND o.deleted_at IS NULL) AS records
                        FROM poi p WHERE p.id = :id
                        """).param("id", id).query(Place.class).single();
    }

    private MatchEvidence.Side side(Place place) {
        return MatchReviewResolver.side(place.name(), place.categoryGroup(), MatchReviewResolver.attributeSets(json, place.attributes()));
    }
}
