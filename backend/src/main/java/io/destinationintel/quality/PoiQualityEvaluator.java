package io.destinationintel.quality;

import io.destinationintel.ingestion.run.SyncRunRecorder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Quality evaluation for POI sources (OpenStreetMap, Overture). Everything is measured from the
 * stored records of the source. Thresholds are per source: OSM is evaluated as the national
 * base layer, Overture as a complement whose value is new, non-duplicate coverage.
 */
public class PoiQualityEvaluator implements SourceQualityEvaluator {

    /** Source-specific acceptance thresholds. */
    public record Thresholds(double maxRejectionPct, double minStatesCovered, double maxResidualDuplicatePct,
                             double minProvenancePct, double maxConflictPct, double minDestinationAssignedPct,
                             double maxUnresolvedPct, double maxMissingLocationPct) {}

    private final String sourceId;
    private final Thresholds t;
    private final JdbcClient jdbc;
    private final SyncRunRecorder recorder;

    public PoiQualityEvaluator(String sourceId, Thresholds thresholds, JdbcClient jdbc, SyncRunRecorder recorder) {
        this.sourceId = sourceId;
        this.t = thresholds;
        this.jdbc = jdbc;
        this.recorder = recorder;
    }

    @Override
    public String sourceId() {
        return sourceId;
    }

    /** Rejection reasons that mean "not something a guide lists" rather than "bad data". */
    /** In-scope records the source cannot place on a map: a coverage gap, not invalid data. */
    static final List<String> COVERAGE_GAPS = List.of("missing_coordinates");

    static final List<String> OUT_OF_SCOPE = List.of("missing_name", "unmapped_category", "outside_india_country",
            "permanently_closed", "not_for_travellers");

    private static final tools.jackson.databind.ObjectMapper MAPPER = new tools.jackson.databind.ObjectMapper();

    static Map<String, Long> rejectionReasons(String json) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        MAPPER.readTree(json).properties().forEach(e -> out.put(e.getKey(), e.getValue().asLong()));
        return out;
    }

    private long count(String sql) {
        return jdbc.sql(sql).param("s", sourceId).query(Long.class).single();
    }

    @Override
    public QualityResult evaluate(UUID runId) {
        Map<String, Object> m = QualityResult.newMetrics();
        List<QualityCheck> checks = new ArrayList<>();
        List<String> limitations = new ArrayList<>();

        long records = count("SELECT count(*) FROM poi_source_record WHERE source_id = :s AND deleted_at IS NULL");
        long deletedRecords = count("SELECT count(*) FROM poi_source_record WHERE source_id = :s AND deleted_at IS NOT NULL");
        long linked = count("SELECT count(*) FROM poi_source_record WHERE source_id = :s AND deleted_at IS NULL AND poi_id IS NOT NULL");
        long possible = count("SELECT count(*) FROM poi_source_record WHERE source_id = :s AND deleted_at IS NULL AND match_confidence = 'possible'");
        long created = count("SELECT count(*) FROM poi_source_record WHERE source_id = :s AND deleted_at IS NULL AND match_confidence = 'new'");
        long matched = count("SELECT count(*) FROM poi_source_record WHERE source_id = :s AND deleted_at IS NULL AND match_confidence IN ('high', 'source_identity')");
        long pois = count("""
                SELECT count(DISTINCT p.id) FROM poi p JOIN poi_source_record r ON r.poi_id = p.id AND r.deleted_at IS NULL
                WHERE r.source_id = :s AND p.status = 'active'""");
        m.put("activeRecords", records);
        m.put("deletedRecords", deletedRecords);
        m.put("canonicalPois", pois);
        m.put("recordsMatchedToExistingPois", matched);
        m.put("recordsCreatingNewPois", created);
        m.put("recordsAwaitingMatchReview", possible);

        // Run-level rejection rate.
        SyncRunRecorder.RunRow run = runId == null ? null : recorder.find(runId).orElse(null);
        double rejectionPct = 0;
        if (run != null) {
            // Unnamed or uncategorised features are outside a travel guide's scope, not invalid data,
            // so they are reported separately from records that failed validation.
            Map<String, Long> reasons = rejectionReasons(run.rejectionReasons());
            long outOfScope = OUT_OF_SCOPE.stream().mapToLong(r -> reasons.getOrDefault(r, 0L)).sum();
            long gaps = COVERAGE_GAPS.stream().mapToLong(r -> reasons.getOrDefault(r, 0L)).sum();
            long invalid = run.rejected() - outOfScope - gaps;
            long inScope = run.parsed() - outOfScope;
            if (gaps > 0 || t.maxMissingLocationPct() < 100) {
                checks.add(QualityCheck.atMost("missing_location", "completeness", QualityCheck.WARNING,
                        "In-scope records without usable coordinates (%)", Pct.of(gaps, inScope), t.maxMissingLocationPct(),
                        gaps + " records cannot be placed on a map and are not published as POIs"));
            }
            inScope -= gaps;
            rejectionPct = Pct.of(invalid, inScope);
            m.put("run", Map.of("fetched", run.fetched(), "parsed", run.parsed(), "outOfScope", outOfScope,
                    "missingLocation", gaps, "invalid", invalid, "inserted", run.inserted(), "updated", run.updated(), "unchanged", run.unchanged(),
                    "deleted", run.deleted(), "rejectionReasons", reasons));
            checks.add(QualityCheck.atMost("rejection_rate", "schema", QualityCheck.CRITICAL,
                    "Share of in-scope records rejected by validation (%)", rejectionPct, t.maxRejectionPct(),
                    invalid + " of " + inScope + " rejected"));
            checks.add(QualityCheck.info("out_of_scope", "schema", "Features skipped as out of scope (unnamed, unmapped category, other country, closed, not open to travellers)",
                    outOfScope, null));
        }

        // Completeness.
        Map<String, Double> completeness = new LinkedHashMap<>();
        if (records > 0) {
            for (String key : List.of("address", "phone", "website", "opening_hours", "wikidata")) {
                completeness.put(key, Pct.of(count("SELECT count(*) FROM poi_source_record WHERE source_id = :s AND deleted_at IS NULL"
                        + " AND jsonb_exists(attributes, '" + key + "')"), records));
            }
        }
        m.put("attributeCompletenessPct", completeness);
        long withVersion = count("SELECT count(*) FROM poi_source_record WHERE source_id = :s AND deleted_at IS NULL AND source_version IS NOT NULL");
        long withDataset = count("SELECT count(*) FROM poi_source_record WHERE source_id = :s AND deleted_at IS NULL AND dataset_version IS NOT NULL");
        long withRaw = count("SELECT count(*) FROM poi_source_record WHERE source_id = :s AND deleted_at IS NULL AND raw <> '{}'::jsonb");
        double provenancePct = records == 0 ? 0 : Math.min(Pct.of(withDataset, records), Pct.of(withRaw, records));
        m.put("provenancePct", Map.of("sourceVersion", Pct.of(withVersion, records), "datasetVersion", Pct.of(withDataset, records),
                "originalAttributes", Pct.of(withRaw, records), "sourceIdentifier", records == 0 ? 0.0 : 100.0));
        checks.add(QualityCheck.atLeast("provenance", "provenance", QualityCheck.CRITICAL,
                "Records keeping release version and original attributes (%)", provenancePct, t.minProvenancePct(),
                "every record also keeps its source identifier (unique constraint)"));

        // Geography.
        long outside = count("""
                SELECT count(*) FROM poi_source_record WHERE source_id = :s AND deleted_at IS NULL
                AND NOT (latitude BETWEEN 6.4 AND 37.6 AND longitude BETWEEN 68 AND 97.5)""");
        checks.add(QualityCheck.atMost("outside_india", "geographic", QualityCheck.CRITICAL,
                "Stored records outside India's bounding box", outside, 0, null));
        long stackedPois = jdbc.sql("""
                SELECT coalesce(sum(n), 0) FROM (
                    SELECT count(*) AS n FROM poi p JOIN poi_source_record r ON r.poi_id = p.id AND r.source_id = :s AND r.deleted_at IS NULL
                    WHERE p.status = 'active' GROUP BY round(p.latitude::numeric, 5), round(p.longitude::numeric, 5)
                    HAVING count(DISTINCT p.name_normalized) >= 5) x
                """).param("s", sourceId).query(Long.class).single();
        m.put("poisStackedOnIdenticalCoordinates", stackedPois);
        checks.add(QualityCheck.atMost("stacked_coordinates", "geographic", QualityCheck.WARNING,
                "POIs sharing one exact coordinate with 4+ other differently named POIs (%)", Pct.of(stackedPois, pois), 1.0,
                "often a placeholder coordinate or an undifferentiated building"));
        long unplaceable = count("""
                SELECT count(*) FROM poi_source_record WHERE source_id = :s AND deleted_at IS NULL AND match_confidence = 'unplaceable'""");
        m.put("recordsWithPlaceholderCoordinates", unplaceable);
        checks.add(QualityCheck.atMost("placeholder_coordinates", "geographic", QualityCheck.WARNING,
                "Records on placeholder coordinates, kept but not mapped (%)", Pct.of(unplaceable, records), 5,
                unplaceable + " records share an exact point with 4 or more differently named places"));
        double assignedPct = Pct.of(count("""
                SELECT count(DISTINCT p.id) FROM poi p JOIN poi_source_record r ON r.poi_id = p.id AND r.deleted_at IS NULL
                WHERE r.source_id = :s AND p.status = 'active' AND p.destination_id IS NOT NULL"""), pois);
        checks.add(QualityCheck.atLeast("destination_assigned", "coverage", QualityCheck.WARNING,
                "POIs within reach of an indexed destination (%)", assignedPct, t.minDestinationAssignedPct(),
                "POIs farther than the destination radius from every indexed city or town are kept but not shown in guides"));
        List<Map<String, Object>> byState = jdbc.sql("""
                SELECT coalesce(s.name, '(no district within 150 km)') AS state, count(DISTINCT p.id) AS pois
                FROM poi p JOIN poi_source_record r ON r.poi_id = p.id AND r.deleted_at IS NULL AND r.source_id = :s
                LEFT JOIN place s ON s.id = p.state_id
                WHERE p.status = 'active' GROUP BY 1 ORDER BY 2 DESC
                """).param("s", sourceId).query((rs, i) -> Map.<String, Object>of("state", rs.getString(1), "pois", rs.getLong(2))).list();
        m.put("poisByStateApprox", byState);
        long statesCovered = jdbc.sql("""
                SELECT count(*) FROM place s WHERE s.kind IN ('state', 'union_territory')
                  AND EXISTS (SELECT 1 FROM poi p JOIN poi_source_record r ON r.poi_id = p.id AND r.deleted_at IS NULL
                              AND r.source_id = :s WHERE p.state_id = s.id AND p.status = 'active')
                """).param("s", sourceId).query(Long.class).single();
        long statesTotal = jdbc.sql("SELECT count(*) FROM place WHERE kind IN ('state', 'union_territory')").query(Long.class).single();
        m.put("statesAndUtsCovered", statesCovered + " of " + statesTotal);
        checks.add(QualityCheck.atLeast("state_coverage", "coverage", QualityCheck.WARNING,
                "States and union territories with at least one POI", statesCovered, t.minStatesCovered(),
                "state assigned from the nearest district centroid (approximate near borders)"));
        m.put("poisByCategory", jdbc.sql("""
                SELECT p.category_group, p.category, count(DISTINCT p.id) FROM poi p
                JOIN poi_source_record r ON r.poi_id = p.id AND r.deleted_at IS NULL AND r.source_id = :s
                WHERE p.status = 'active' GROUP BY 1, 2 ORDER BY 1, 3 DESC
                """).param("s", sourceId).query((rs, i) -> Map.<String, Object>of("group", rs.getString(1), "category", rs.getString(2),
                "pois", rs.getLong(3))).list());

        // Residual duplicates: different canonical POIs that still look identical.
        // Counted from this source's POIs outwards, using the exact-name index, so the check stays
        // fast with hundreds of thousands of POIs.
        long residual = jdbc.sql("""
                SELECT count(*) FROM poi b
                WHERE b.status = 'active'
                  AND EXISTS (SELECT 1 FROM poi_source_record r WHERE r.poi_id = b.id AND r.source_id = :s AND r.deleted_at IS NULL)
                  AND EXISTS (SELECT 1 FROM poi a WHERE a.status = 'active' AND a.id < b.id
                                AND a.name_normalized = b.name_normalized AND a.category_group = b.category_group
                                AND ST_DWithin(a.geog, b.geog, 50))
                """).param("s", sourceId).query(Long.class).single();
        double residualPct = Pct.of(residual, pois);
        m.put("residualDuplicates", residual);
        checks.add(QualityCheck.atMost("residual_duplicates", "duplicates", QualityCheck.CRITICAL,
                "Distinct POIs with the same name within 50 m (%)", residualPct, t.maxResidualDuplicatePct(), residual + " pairs"));
        double unresolvedPct = Pct.of(possible, records);
        checks.add(QualityCheck.atMost("unresolved_matches", "duplicates", QualityCheck.WARNING,
                "Records waiting for match review (%)", unresolvedPct, t.maxUnresolvedPct(),
                "uncertain matches are not merged or published automatically"));

        // Cross-source consistency for POIs that other sources also describe.
        long multi = count("""
                SELECT count(*) FROM poi_source_record r JOIN poi_source_record o ON o.poi_id = r.poi_id
                     AND o.source_id <> r.source_id AND o.deleted_at IS NULL
                WHERE r.source_id = :s AND r.deleted_at IS NULL""");
        if (multi > 0) {
            long nameConflicts = count("""
                    SELECT count(*) FROM poi_source_record r JOIN poi_source_record o ON o.poi_id = r.poi_id
                         AND o.source_id <> r.source_id AND o.deleted_at IS NULL
                    WHERE r.source_id = :s AND r.deleted_at IS NULL AND similarity(idi_normalize(r.name), idi_normalize(o.name)) < 0.5""");
            long coordConflicts = count("""
                    SELECT count(*) FROM poi_source_record r JOIN poi_source_record o ON o.poi_id = r.poi_id
                         AND o.source_id <> r.source_id AND o.deleted_at IS NULL
                    WHERE r.source_id = :s AND r.deleted_at IS NULL AND ST_Distance(r.geog, o.geog) > 100""");
            long categoryConflicts = count("""
                    SELECT count(*) FROM poi_source_record r JOIN poi_source_record o ON o.poi_id = r.poi_id
                         AND o.source_id <> r.source_id AND o.deleted_at IS NULL
                    WHERE r.source_id = :s AND r.deleted_at IS NULL AND r.category <> o.category""");
            m.put("crossSource", Map.of("pairs", multi, "nameConflicts", nameConflicts, "coordinatesOver100m", coordConflicts,
                    "categoryDiffers", categoryConflicts));
            checks.add(QualityCheck.atMost("cross_source_conflicts", "consistency", QualityCheck.WARNING,
                    "Matched pairs whose names disagree (%)", Pct.of(nameConflicts, multi), t.maxConflictPct(),
                    "conflicting values are kept per source, not overwritten"));
        }

        // Freshness of the source's own edits.
        m.put("lastEditAge", jdbc.sql("""
                SELECT jsonb_build_object(
                    'within1Year', count(*) FILTER (WHERE source_timestamp > now() - interval '1 year'),
                    'within5Years', count(*) FILTER (WHERE source_timestamp > now() - interval '5 years'),
                    'older', count(*) FILTER (WHERE source_timestamp <= now() - interval '5 years'),
                    'unknown', count(*) FILTER (WHERE source_timestamp IS NULL))::text
                FROM poi_source_record WHERE source_id = :s AND deleted_at IS NULL
                """).param("s", sourceId).query(String.class).single());

        // Guide entry links.
        long entryLinks = count("""
                SELECT count(DISTINCT e.id) FROM guide_entry e JOIN poi_source_record r ON r.poi_id = e.poi_id AND r.deleted_at IS NULL
                WHERE r.source_id = :s AND e.status = 'published'""");
        long entries = jdbc.sql("SELECT count(*) FROM guide_entry WHERE status = 'published' AND section <> 'neighbourhood'")
                .query(Long.class).single();
        m.put("guideEntriesLinked", entryLinks + " of " + entries);

        // Stratified sample for manual inspection: two per category group from different states.
        List<Map<String, Object>> samples = jdbc.sql("""
                SELECT * FROM (
                    SELECT p.category_group, p.category, p.name, r.external_id, r.name AS source_name, p.latitude, p.longitude,
                           d.name AS destination, s.name AS state, r.attributes::text AS attributes, r.match_confidence,
                           row_number() OVER (PARTITION BY p.category_group ORDER BY md5(p.id::text)) AS rn
                    FROM poi p JOIN poi_source_record r ON r.poi_id = p.id AND r.source_id = :s AND r.deleted_at IS NULL
                    LEFT JOIN place d ON d.id = p.destination_id LEFT JOIN place s ON s.id = p.state_id
                    WHERE p.status = 'active') x
                WHERE rn <= 2 ORDER BY category_group, rn
                """).param("s", sourceId).query((rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("group", rs.getString("category_group"));
                    row.put("category", rs.getString("category"));
                    row.put("name", rs.getString("name"));
                    row.put("sourceId", rs.getString("external_id"));
                    row.put("latitude", rs.getDouble("latitude"));
                    row.put("longitude", rs.getDouble("longitude"));
                    row.put("destination", rs.getString("destination"));
                    row.put("state", rs.getString("state"));
                    row.put("match", rs.getString("match_confidence"));
                    row.put("attributes", rs.getString("attributes"));
                    return row;
                }).list();

        if (records == 0) {
            checks.add(QualityCheck.atLeast("non_empty", "completeness", QualityCheck.CRITICAL,
                    "Active records stored for this source", 0, 1, "zero records means the import produced nothing usable"));
        }

        Map<String, Double> components = new LinkedHashMap<>();
        if (run != null) {
            components.put("validity", 100 - rejectionPct);
        }
        components.put("provenance", provenancePct);
        components.put("uniqueness", 100 - residualPct);
        components.put("destinationReach", assignedPct);
        if (!completeness.isEmpty()) {
            components.put("contactOrAddress", Math.max(completeness.getOrDefault("address", 0.0),
                    Math.max(completeness.getOrDefault("phone", 0.0), completeness.getOrDefault("website", 0.0))));
        }
        limitations.add("Mapped POIs are not recommendations; they appear in guides only as clearly labelled map data or when linked to a reviewed guide entry.");
        limitations.add("State assignment uses the nearest district centroid and can be wrong close to state borders.");
        return new QualityResult(m, checks, components, samples, limitations);
    }
}
