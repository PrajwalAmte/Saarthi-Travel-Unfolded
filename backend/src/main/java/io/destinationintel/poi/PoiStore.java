package io.destinationintel.poi;

import io.destinationintel.common.Json;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence and identity resolution for POIs from any source. Each source record is
 * upserted by its own stable identifier; canonical POIs are created only for records that
 * match nothing. Matching is set-based SQL over the records touched by one run:
 *
 * <ol>
 *   <li>Source identity: a record keeps the POI it was linked to before.</li>
 *   <li>Wikidata id shared with an existing POI (compatible category group).</li>
 *   <li>Name similarity and distance. Cross-source: similarity >= 0.75 within 150 m.
 *       Same source (e.g. an OSM node and building way): similarity >= 0.9 within 75 m.</li>
 *   <li>Weak cross-source candidates (similarity >= 0.55 within 400 m) are written to
 *       {@code poi_match_review} and the record stays unlinked and unpublished.</li>
 *   <li>Everything else becomes a new canonical POI.</li>
 * </ol>
 */
@Component
public class PoiStore {

    public record UpsertCounts(int inserted, int changed, int unchanged) {}

    public record ResolveCounts(int sourceIdentity, int wikidata, int nameDistance, int possible, int created,
                                int mergedDuplicates, int relinkedMoved) {}

    private static final int BATCH = 400;

    private final JdbcClient jdbc;
    private final Json json;

    public PoiStore(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public static int batchSize() {
        return BATCH;
    }

    /** Upserts a batch of source records; returns inserted, changed and unchanged counts. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UpsertCounts upsert(List<NormalizedPoi> batch, UUID runId, OffsetDateTime seenAt) {
        if (batch.isEmpty()) {
            return new UpsertCounts(0, 0, 0);
        }
        StringBuilder sql = new StringBuilder("""
                INSERT INTO poi_source_record (source_id, external_id, source_version, source_timestamp, dataset_version, name,
                                               category_raw, category, category_group, latitude, longitude, attributes, raw,
                                               content_hash, last_seen_at, last_run_id)
                VALUES """);
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append("(:s").append(i).append(",:e").append(i).append(",:v").append(i).append(",:t").append(i)
                    .append(",:d").append(i).append(",:n").append(i).append(",:cr").append(i).append(",:c").append(i)
                    .append(",:g").append(i).append(",:lat").append(i).append(",:lon").append(i)
                    .append(",CAST(:a").append(i).append(" AS jsonb),CAST(:r").append(i).append(" AS jsonb),:h").append(i)
                    .append(",:seen,:run)");
        }
        sql.append("""
                 ON CONFLICT (source_id, external_id) DO UPDATE SET
                    source_version = EXCLUDED.source_version, source_timestamp = EXCLUDED.source_timestamp,
                    dataset_version = EXCLUDED.dataset_version, name = EXCLUDED.name, category_raw = EXCLUDED.category_raw,
                    category = EXCLUDED.category, category_group = EXCLUDED.category_group, latitude = EXCLUDED.latitude,
                    longitude = EXCLUDED.longitude, attributes = EXCLUDED.attributes, raw = EXCLUDED.raw,
                    changed_at = CASE WHEN poi_source_record.content_hash IS DISTINCT FROM EXCLUDED.content_hash
                                       OR poi_source_record.deleted_at IS NOT NULL THEN now() ELSE poi_source_record.changed_at END,
                    content_hash = EXCLUDED.content_hash, last_seen_at = EXCLUDED.last_seen_at,
                    last_run_id = EXCLUDED.last_run_id, deleted_at = NULL
                RETURNING (xmax = 0) AS inserted, (changed_at = now()) AS changed""");
        var spec = jdbc.sql(sql.toString()).param("seen", seenAt).param("run", runId);
        for (int i = 0; i < batch.size(); i++) {
            NormalizedPoi p = batch.get(i);
            spec = spec.param("s" + i, p.sourceId()).param("e" + i, p.externalId()).param("v" + i, p.sourceVersion())
                    .param("t" + i, p.sourceTimestamp()).param("d" + i, p.datasetVersion()).param("n" + i, p.name())
                    .param("cr" + i, p.categoryRaw()).param("c" + i, p.category()).param("g" + i, p.categoryGroup())
                    .param("lat" + i, p.latitude()).param("lon" + i, p.longitude())
                    .param("a" + i, json.write(withWikidata(p))).param("r" + i, json.write(p.raw()))
                    .param("h" + i, p.contentHash());
        }
        int inserted = 0;
        int changed = 0;
        int unchanged = 0;
        for (boolean[] row : spec.query((rs, n) -> new boolean[] {rs.getBoolean(1), rs.getBoolean(2)}).list()) {
            if (row[0]) {
                inserted++;
            } else if (row[1]) {
                changed++;
            } else {
                unchanged++;
            }
        }
        return new UpsertCounts(inserted, changed, unchanged);
    }

    private static java.util.Map<String, Object> withWikidata(NormalizedPoi p) {
        if (p.wikidataId() == null) {
            return p.attributes();
        }
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>(p.attributes());
        m.put("wikidata", p.wikidataId());
        return m;
    }

    /** Resolves the run's new and changed records to canonical POIs. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ResolveCounts resolve(String sourceId, UUID runId, OffsetDateTime runStart) {
        // 0a. Primary records that changed update their canonical POI.
        jdbc.sql("""
                        UPDATE poi p SET name = r.name, category = r.category, category_group = r.category_group,
                               latitude = r.latitude, longitude = r.longitude, wikidata_id = r.attributes ->> 'wikidata',
                               status = 'active', updated_at = now()
                        FROM poi_source_record r
                        WHERE p.seed_record_id = r.id AND r.last_run_id = :run AND r.changed_at >= :start AND r.deleted_at IS NULL
                        """).param("run", runId).param("start", runStart).update();
        // 0b. Secondary records that moved far from their POI are unlinked and re-resolved.
        int moved = jdbc.sql("""
                        UPDATE poi_source_record r SET poi_id = NULL, match_confidence = NULL, match_method = NULL, match_score = NULL
                        FROM poi p
                        WHERE r.poi_id = p.id AND p.seed_record_id <> r.id AND r.last_run_id = :run AND r.changed_at >= :start
                          AND ST_Distance(p.geog, r.geog) > 500
                        """).param("run", runId).param("start", runStart).update();
        int identity = jdbc.sql("""
                        SELECT count(*) FROM poi_source_record WHERE last_run_id = :run AND poi_id IS NOT NULL AND deleted_at IS NULL
                        """).param("run", runId).query(Integer.class).single();

        // 1. Shared Wikidata identity.
        int wikidata = jdbc.sql("""
                        UPDATE poi_source_record r SET poi_id = m.pid, match_confidence = 'high', match_method = 'wikidata', match_score = 1
                        FROM (SELECT DISTINCT ON (r2.id) r2.id AS rid, p.id AS pid
                              FROM poi_source_record r2
                              JOIN poi p ON p.wikidata_id = r2.attributes ->> 'wikidata' AND p.status = 'active'
                              WHERE r2.last_run_id = :run AND r2.poi_id IS NULL AND r2.deleted_at IS NULL
                                AND r2.match_confidence IS DISTINCT FROM 'unplaceable'
                                AND (r2.attributes ->> 'wikidata') IS NOT NULL
                                AND (p.category_group = r2.category_group
                                     OR (p.category_group IN ('eat', 'drink') AND r2.category_group IN ('eat', 'drink'))
                                     OR (p.category_group IN ('see', 'do') AND r2.category_group IN ('see', 'do')))
                              ORDER BY r2.id, ST_Distance(p.geog, r2.geog)) m
                        WHERE r.id = m.rid
                        """).param("run", runId).update();

        // 2. Name similarity and distance against existing POIs.
        int nameDistance = jdbc.sql("""
                        UPDATE poi_source_record r SET poi_id = m.pid, match_confidence = 'high', match_method = 'name_distance',
                               match_score = m.score
                        FROM (SELECT DISTINCT ON (r2.id) r2.id AS rid, c.id AS pid, c.score
                              FROM poi_source_record r2
                              CROSS JOIN LATERAL (
                                  SELECT p.id, similarity(p.name_normalized, idi_normalize(r2.name)) - ST_Distance(p.geog, r2.geog) / 1000.0 AS score,
                                         similarity(p.name_normalized, idi_normalize(r2.name)) AS sim,
                                         ST_Distance(p.geog, r2.geog) AS dist, p.primary_source_id
                                  FROM poi p
                                  WHERE p.status = 'active' AND ST_DWithin(p.geog, r2.geog, 150)
                                    AND (p.category_group = r2.category_group
                                         OR (p.category_group IN ('eat', 'drink') AND r2.category_group IN ('eat', 'drink'))
                                         OR (p.category_group IN ('see', 'do') AND r2.category_group IN ('see', 'do')))
                                    AND NOT EXISTS (SELECT 1 FROM poi_source_record o WHERE o.poi_id = p.id
                                                    AND o.source_id = r2.source_id AND o.deleted_at IS NULL)
                              ) c
                              WHERE r2.last_run_id = :run AND r2.poi_id IS NULL AND r2.deleted_at IS NULL
                                AND r2.match_confidence IS DISTINCT FROM 'unplaceable'
                                AND c.sim >= 0.75
                              ORDER BY r2.id, c.score DESC) m
                        WHERE r.id = m.rid
                        """).param("run", runId).update();

        // 3. Weak cross-source candidates go to review; the record stays unlinked.
        int possible = jdbc.sql("""
                        WITH cands AS (
                            SELECT r2.id AS rid, p.id AS pid, similarity(p.name_normalized, idi_normalize(r2.name)) AS sim,
                                   ST_Distance(p.geog, r2.geog) AS dist
                            FROM poi_source_record r2
                            JOIN poi p ON p.status = 'active' AND ST_DWithin(p.geog, r2.geog, 400)
                                     AND p.primary_source_id <> r2.source_id
                            WHERE r2.last_run_id = :run AND r2.poi_id IS NULL AND r2.deleted_at IS NULL
                                AND r2.match_confidence IS DISTINCT FROM 'unplaceable'
                              AND r2.match_confidence IS DISTINCT FROM 'possible'
                              AND similarity(p.name_normalized, idi_normalize(r2.name)) >= 0.55
                              AND NOT EXISTS (SELECT 1 FROM poi_match_review d WHERE d.record_id = r2.id
                                              AND d.candidate_poi_id = p.id AND d.status = 'distinct')),
                        inserted AS (
                            INSERT INTO poi_match_review (record_id, candidate_poi_id, score, reasons)
                            SELECT rid, pid, round((sim - dist / 1000.0)::numeric, 3),
                                   jsonb_build_array('name similarity ' || round(sim::numeric, 2), 'distance ' || round(dist::numeric) || ' m')
                            FROM cands
                            ON CONFLICT DO NOTHING
                            RETURNING record_id)
                        UPDATE poi_source_record r SET match_confidence = 'possible', match_method = 'needs_review'
                        WHERE r.id IN (SELECT DISTINCT rid FROM cands)
                        """).param("run", runId).update();

        // 4. New canonical POIs for everything still unmatched.
        int created = jdbc.sql("""
                        WITH fresh AS (
                            INSERT INTO poi (name, category, category_group, latitude, longitude, wikidata_id, primary_source_id, seed_record_id)
                            SELECT r.name, r.category, r.category_group, r.latitude, r.longitude, r.attributes ->> 'wikidata',
                                   r.source_id, r.id
                            FROM poi_source_record r
                            WHERE r.last_run_id = :run AND r.poi_id IS NULL AND r.deleted_at IS NULL AND r.match_confidence IS NULL
                            RETURNING id, seed_record_id)
                        UPDATE poi_source_record r SET poi_id = f.id, match_confidence = 'new', match_method = 'created'
                        FROM fresh f WHERE r.id = f.seed_record_id
                        """).param("run", runId).update();

        // 5. Collapse same-source duplicates created in this run (e.g. a hotel mapped as node and building).
        // A merge that removes a POI created in this run reduces the net additions; a merge of two
        // older POIs (they became duplicates through an edit) does not.
        int[] merged = mergeSameSourceDuplicates(runId, runStart);
        return new ResolveCounts(identity, wikidata, nameDistance, possible, created - merged[0], merged[0] + merged[1], moved);
    }

    /** Returns {merges dropping a POI created in this run, merges dropping an older POI}. */
    private int[] mergeSameSourceDuplicates(UUID runId, OffsetDateTime runStart) {
        List<Object[]> pairs = jdbc.sql("""
                        SELECT DISTINCT ON (b.id) a.id AS keep, b.id AS drop, b.created_at >= :start AS fresh
                        FROM poi a
                        JOIN poi_source_record ra ON ra.id = a.seed_record_id AND ra.last_run_id = :run AND ra.match_confidence = 'new'
                        JOIN poi b ON b.id > a.id AND b.category_group = a.category_group AND ST_DWithin(a.geog, b.geog, 75)
                        JOIN poi_source_record rb ON rb.id = b.seed_record_id AND rb.last_run_id = :run AND rb.match_confidence = 'new'
                        WHERE similarity(a.name_normalized, b.name_normalized) >= 0.9
                          AND (a.wikidata_id IS NULL OR b.wikidata_id IS NULL OR a.wikidata_id = b.wikidata_id)
                        ORDER BY b.id, a.id
                        """)
                .param("run", runId).param("start", runStart)
                .query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getBoolean(3)}).list();
        int[] merged = {0, 0};
        java.util.Set<UUID> dropped = new java.util.HashSet<>();
        for (Object[] pair : pairs) {
            if (dropped.contains((UUID) pair[0]) || dropped.contains((UUID) pair[1])) {
                continue;
            }
            jdbc.sql("""
                            UPDATE poi_source_record SET poi_id = :keep, match_confidence = 'high', match_method = 'duplicate_within_source'
                            WHERE poi_id = :drop
                            """).param("keep", pair[0]).param("drop", pair[1]).update();
            jdbc.sql("DELETE FROM poi WHERE id = :drop").param("drop", pair[1]).update();
            dropped.add((UUID) pair[1]);
            merged[Boolean.TRUE.equals(pair[2]) ? 0 : 1]++;
        }
        return merged;
    }

    /** Minimum number of differently named records on one exact point for it to count as a placeholder. */
    static final int STACK_THRESHOLD = 5;

    public long openReviewCount(String sourceId) {
        return jdbc.sql("""
                        SELECT count(DISTINCT m.record_id) FROM poi_match_review m JOIN poi_source_record r ON r.id = m.record_id
                        WHERE m.status = 'open' AND r.source_id = :s AND r.deleted_at IS NULL
                        """).param("s", sourceId).query(Long.class).single();
    }

    public long unplaceableCount(String sourceId) {
        return jdbc.sql("SELECT count(*) FROM poi_source_record WHERE source_id = :s AND deleted_at IS NULL AND match_confidence = 'unplaceable'")
                .param("s", sourceId).query(Long.class).single();
    }

    public record QuarantineCounts(int quarantined, int released, int poisRemoved) {}

    /**
     * Quarantines records of a source whose coordinates are placeholders: at least
     * {@link #STACK_THRESHOLD} differently named records on the same exact point (to about a metre),
     * which in practice is a town centroid used when the real location is unknown. They keep their
     * source data but are unlinked, never matched and never published. Records that are no longer
     * stacked are released for normal resolution.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public QuarantineCounts quarantineStackedCoordinates(String sourceId) {
        int released = jdbc.sql("""
                        UPDATE poi_source_record r SET match_confidence = NULL, match_method = NULL
                        WHERE r.source_id = :s AND r.deleted_at IS NULL AND r.match_confidence = 'unplaceable'
                          AND (SELECT count(DISTINCT idi_normalize(o.name)) FROM poi_source_record o
                               WHERE o.source_id = r.source_id AND o.deleted_at IS NULL
                                 AND round(o.latitude::numeric, 5) = round(r.latitude::numeric, 5)
                                 AND round(o.longitude::numeric, 5) = round(r.longitude::numeric, 5)) < :n
                        """).param("s", sourceId).param("n", STACK_THRESHOLD).update();
        int quarantined = jdbc.sql("""
                        WITH stacks AS (
                            SELECT round(latitude::numeric, 5) AS la, round(longitude::numeric, 5) AS lo
                            FROM poi_source_record WHERE source_id = :s AND deleted_at IS NULL
                            GROUP BY 1, 2 HAVING count(DISTINCT idi_normalize(name)) >= :n)
                        UPDATE poi_source_record r SET poi_id = NULL, match_confidence = 'unplaceable',
                               match_method = 'stacked_coordinates', match_score = NULL
                        FROM stacks k
                        WHERE r.source_id = :s AND r.deleted_at IS NULL AND r.match_confidence IS DISTINCT FROM 'unplaceable'
                          AND round(r.latitude::numeric, 5) = k.la AND round(r.longitude::numeric, 5) = k.lo
                        """).param("s", sourceId).param("n", STACK_THRESHOLD).update();
        int removed = jdbc.sql("""
                        UPDATE poi p SET status = 'removed', updated_at = now()
                        WHERE p.status = 'active'
                          AND NOT EXISTS (SELECT 1 FROM poi_source_record r WHERE r.poi_id = p.id AND r.deleted_at IS NULL)
                        """).update();
        return new QuarantineCounts(quarantined, released, removed);
    }

    /**
     * Refreshes planner statistics after a bulk import; without it, queries over hundreds of
     * thousands of fresh rows are planned as if the tables were still small.
     */
    public void analyze() {
        jdbc.sql("ANALYZE poi").update();
        jdbc.sql("ANALYZE poi_source_record").update();
    }

    public record DeletionResult(int recordsDeleted, int poisRemoved, boolean skipped, int missing, int active) {}

    /**
     * Marks records of a full release that were not seen in this run as deleted, and removes
     * POIs left without any active record. Refuses to delete when more than {@code maxShare}
     * of active records are missing, which indicates a truncated or partial release.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DeletionResult markMissing(String sourceId, OffsetDateTime runStart, double maxShare, double[] bbox) {
        String scope = bbox == null ? "" : " AND ST_Intersects(geog, ST_MakeEnvelope(:w, :s, :e, :n, 4326)::geography)";
        var count = jdbc.sql("SELECT count(*) FILTER (WHERE last_seen_at < :start), count(*) FROM poi_source_record "
                        + "WHERE source_id = :src AND deleted_at IS NULL" + scope)
                .param("start", runStart).param("src", sourceId);
        if (bbox != null) {
            count = count.param("w", bbox[0]).param("s", bbox[1]).param("e", bbox[2]).param("n", bbox[3]);
        }
        long[] mc = count.query((rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)}).single();
        int missing = (int) mc[0];
        int active = (int) mc[1];
        if (active > 0 && (double) missing / active > maxShare) {
            return new DeletionResult(0, 0, true, missing, active);
        }
        var del = jdbc.sql("UPDATE poi_source_record SET deleted_at = now() WHERE source_id = :src AND deleted_at IS NULL "
                        + "AND last_seen_at < :start" + scope)
                .param("src", sourceId).param("start", runStart);
        if (bbox != null) {
            del = del.param("w", bbox[0]).param("s", bbox[1]).param("e", bbox[2]).param("n", bbox[3]);
        }
        int deleted = del.update();
        int removed = jdbc.sql("""
                        UPDATE poi p SET status = 'removed', updated_at = now()
                        WHERE p.status = 'active'
                          AND NOT EXISTS (SELECT 1 FROM poi_source_record r WHERE r.poi_id = p.id AND r.deleted_at IS NULL)
                        """).update();
        return new DeletionResult(deleted, removed, false, missing, active);
    }

    /**
     * Assigns each POI touched since {@code since} to the nearest guide destination within
     * {@code radiusM}, and to the nearest district (and its state). The district is the nearest
     * district centroid, an approximation documented in the quality report.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int assignGeography(OffsetDateTime since, double radiusM) {
        int n = jdbc.sql("""
                        UPDATE poi p SET destination_id = x.did, destination_km = x.km
                        FROM (SELECT p2.id AS pid, d.id AS did, d.km
                              FROM poi p2
                              LEFT JOIN LATERAL (
                                  SELECT pl.id, ST_Distance(pl.geog, p2.geog) / 1000.0 AS km FROM place pl
                                  WHERE pl.lifecycle_status = 'active' AND pl.is_destination AND pl.geog IS NOT NULL
                                    AND pl.kind IN ('city', 'town', 'village', 'island', 'park')
                                    AND ST_DWithin(pl.geog, p2.geog, :radius)
                                  ORDER BY pl.geog <-> p2.geog LIMIT 1) d ON true
                              WHERE p2.status = 'active' AND (p2.updated_at >= :since OR p2.created_at >= :since OR p2.destination_id IS NULL)) x
                        WHERE p.id = x.pid
                        """).param("radius", radiusM).param("since", since).update();
        jdbc.sql("""
                        UPDATE poi p SET district_id = x.did, state_id = x.sid
                        FROM (SELECT p2.id AS pid, d.id AS did,
                                     coalesce((SELECT r.parent_id FROM place_relation r WHERE r.child_id = p2.destination_id
                                               AND r.relation = 'in_state' LIMIT 1),
                                              (SELECT r.parent_id FROM place_relation r WHERE r.child_id = d.id
                                               AND r.relation = 'in_state' LIMIT 1)) AS sid
                              FROM poi p2
                              LEFT JOIN LATERAL (
                                  SELECT pl.id FROM place pl
                                  WHERE pl.kind = 'district' AND pl.lifecycle_status = 'active' AND pl.geog IS NOT NULL
                                    AND ST_DWithin(pl.geog, p2.geog, 150000)
                                  ORDER BY pl.geog <-> p2.geog LIMIT 1) d ON true
                              WHERE p2.status = 'active' AND (p2.updated_at >= :since OR p2.created_at >= :since OR p2.state_id IS NULL)) x
                        WHERE p.id = x.pid
                        """).param("since", since).update();
        return n;
    }

    /** POIs a source contributes that readers can currently see (active, in a guide destination). */
    public long publishedCount(String sourceId) {
        return jdbc.sql("""
                        SELECT count(DISTINCT p.id) FROM poi p JOIN poi_source_record r ON r.poi_id = p.id AND r.deleted_at IS NULL
                        WHERE r.source_id = :s AND p.status = 'active' AND p.destination_id IS NOT NULL
                        """).param("s", sourceId).query(Long.class).single();
    }
}
