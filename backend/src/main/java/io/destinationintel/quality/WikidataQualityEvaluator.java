package io.destinationintel.quality;

import io.destinationintel.ingestion.run.SyncRunRecorder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Quality of the Wikidata destination index: identities, coordinates, administrative
 * relations, aliases and whether well-known alternate names actually resolve in search.
 */
@Component
public class WikidataQualityEvaluator implements SourceQualityEvaluator {

    /**
     * Alternate names that a working index must resolve. These are long-established historical or
     * transliterated names (QIDs verified against Wikidata), checked against the stored aliases as
     * a regression test of identity resolution; they are not data written anywhere. "Calcutta" is
     * kept deliberately: Wikidata currently lists no such English alias for Kolkata, so the probe
     * surfaces that real search gap.
     */
    static final Map<String, String> IDENTITY_PROBES = Map.of(
            "bombay", "Q1156",
            "madras", "Q1352",
            "calcutta", "Q1348",
            "bangalore", "Q1355",
            "mysuru", "Q10086",
            "banaras", "Q79980",
            "trivandrum", "Q167715",
            "cochin", "Q1800",
            "pondicherry", "Q66743",
            "gurugram", "Q238508");

    private final JdbcClient jdbc;
    private final SyncRunRecorder recorder;

    public WikidataQualityEvaluator(JdbcClient jdbc, SyncRunRecorder recorder) {
        this.jdbc = jdbc;
        this.recorder = recorder;
    }

    @Override
    public String sourceId() {
        return "wikidata";
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    @Override
    public QualityResult evaluate(UUID runId) {
        Map<String, Object> m = QualityResult.newMetrics();
        List<QualityCheck> checks = new ArrayList<>();
        String active = "lifecycle_status = 'active' AND is_destination AND wikidata_id IS NOT NULL";
        long places = count("SELECT count(*) FROM place WHERE " + active);
        long distinct = count("SELECT count(DISTINCT wikidata_id) FROM place WHERE " + active);
        long removed = count("SELECT count(*) FROM place WHERE lifecycle_status = 'removed'");
        m.put("activeDestinations", places);
        m.put("distinctWikidataEntities", distinct);
        m.put("removedAfterValidation", removed);
        m.put("byKind", jdbc.sql("SELECT kind, count(*) FROM place WHERE " + active + " GROUP BY kind ORDER BY 2 DESC")
                .query((rs, i) -> Map.of("kind", rs.getString(1), "count", rs.getLong(2))).list());
        if (runId != null) {
            recorder.find(runId).ifPresent(r -> m.put("run", Map.of("fetched", r.fetched(), "parsed", r.parsed(),
                    "rejected", r.rejected(), "inserted", r.inserted(), "updated", r.updated(), "deleted", r.deleted(),
                    "rejectionReasons", r.rejectionReasons())));
        }
        checks.add(QualityCheck.atLeast("non_empty", "completeness", QualityCheck.CRITICAL,
                "Destinations in the index", places, 1000, "fewer suggests a failed or partial sync, not a real empty result"));
        checks.add(QualityCheck.atMost("duplicate_identities", "duplicates", QualityCheck.CRITICAL,
                "Places sharing a Wikidata id", places - distinct, 0, null));

        long withCoords = count("SELECT count(*) FROM place WHERE " + active + " AND latitude IS NOT NULL");
        double coordsPct = Pct.of(withCoords, places);
        // Where coordinates came from when Wikidata had none or contradicted itself.
        m.put("coordinatesBySource", jdbc.sql("SELECT coalesce(coordinates_source, 'none'), count(*) FROM place WHERE " + active
                        + " GROUP BY 1 ORDER BY 2 DESC")
                .query((rs, i) -> Map.of("source", rs.getString(1), "count", rs.getLong(2))).list());
        checks.add(QualityCheck.atLeast("coordinates", "completeness", QualityCheck.CRITICAL,
                "Destinations with coordinates (%)", coordsPct, 97, (places - withCoords) + " without"));
        long outside = count("SELECT count(*) FROM place WHERE " + active
                + " AND latitude IS NOT NULL AND NOT (latitude BETWEEN 6.4 AND 37.6 AND longitude BETWEEN 68 AND 97.5)");
        checks.add(QualityCheck.atMost("outside_india", "geographic", QualityCheck.CRITICAL,
                "Destinations with coordinates outside India", outside, 0, null));

        long needState = count("SELECT count(*) FROM place p WHERE " + active.replace("lifecycle_status", "p.lifecycle_status")
                .replace("is_destination", "p.is_destination").replace("wikidata_id", "p.wikidata_id")
                + " AND p.kind NOT IN ('state', 'union_territory', 'region', 'other')");
        long withState = count("""
                SELECT count(*) FROM place p WHERE p.lifecycle_status = 'active' AND p.is_destination AND p.wikidata_id IS NOT NULL
                  AND p.kind NOT IN ('state', 'union_territory', 'region', 'other')
                  AND EXISTS (SELECT 1 FROM place_relation r WHERE r.child_id = p.id AND r.relation = 'in_state')""");
        double statePct = Pct.of(withState, needState);
        checks.add(QualityCheck.atLeast("state_relation", "geographic", QualityCheck.CRITICAL,
                "Settlements, districts and parks linked to a state or UT (%)", statePct, 95,
                (needState - withState) + " without a state"));
        long badRelations = count("""
                SELECT count(*) FROM place_relation r JOIN place s ON s.id = r.parent_id
                WHERE (r.relation = 'in_state' AND s.kind NOT IN ('state', 'union_territory'))
                   OR (r.relation = 'in_district' AND s.kind <> 'district')""");
        checks.add(QualityCheck.atMost("invalid_relations", "consistency", QualityCheck.CRITICAL,
                "Relations whose parent has the wrong kind", badRelations, 0, null));
        long farDistrict = count("""
                SELECT count(*) FROM place p JOIN place_relation r ON r.child_id = p.id AND r.relation = 'in_district'
                JOIN place d ON d.id = r.parent_id
                WHERE p.geog IS NOT NULL AND d.geog IS NOT NULL AND ST_Distance(p.geog, d.geog) > 200000""");
        checks.add(QualityCheck.atMost("implausible_district", "geographic", QualityCheck.WARNING,
                "Places more than 200 km from their district's centre", farDistrict, 5, "suggests a wrong administrative link"));

        long dupNames = count("""
                SELECT count(*) FROM place a JOIN place b ON b.id > a.id AND b.name_normalized = a.name_normalized
                     AND b.kind = a.kind AND b.lifecycle_status = 'active' AND b.is_destination
                     AND ST_DWithin(a.geog, b.geog, 5000)
                WHERE a.lifecycle_status = 'active' AND a.is_destination""");
        m.put("sameNameSameKindWithin5km", dupNames);
        checks.add(QualityCheck.atMost("near_duplicates", "duplicates", QualityCheck.WARNING,
                "Destinations with the same name and kind within 5 km (%)", Pct.of(dupNames, places), 0.5,
                "possible duplicate Wikidata items; not merged automatically"));
        long other = count("SELECT count(*) FROM place WHERE " + active + " AND kind = 'other'");
        checks.add(QualityCheck.atMost("unclassified", "consistency", QualityCheck.WARNING,
                "Destinations of unclassified kind (%)", Pct.of(other, places), 5, other + " places"));

        long withLocal = count("""
                SELECT count(DISTINCT a.place_id) FROM place_alias a JOIN place p ON p.id = a.place_id
                WHERE a.alias_type = 'local_script' AND p.lifecycle_status = 'active' AND p.is_destination""");
        double localPct = Pct.of(withLocal, places);
        double aliasesPer = places == 0 ? 0 : Math.round(100.0 * count("""
                SELECT count(*) FROM place_alias a JOIN place p ON p.id = a.place_id
                WHERE p.lifecycle_status = 'active' AND p.is_destination""") / places) / 100.0;
        m.put("aliasesPerDestination", aliasesPer);
        m.put("aliasLanguages", jdbc.sql("""
                SELECT language, count(*) FROM place_alias WHERE alias_type IN ('local_script', 'alternate') GROUP BY 1 ORDER BY 2 DESC
                """).query((rs, i) -> Map.of("language", rs.getString(1), "aliases", rs.getLong(2))).list());
        checks.add(QualityCheck.atLeast("local_script_names", "completeness", QualityCheck.WARNING,
                "Destinations with a name in an Indian script (%)", localPct, 60, null));

        int resolved = 0;
        List<Map<String, Object>> probes = new ArrayList<>();
        for (Map.Entry<String, String> probe : IDENTITY_PROBES.entrySet()) {
            String top = jdbc.sql("""
                            SELECT p.wikidata_id FROM place_alias a JOIN place p ON p.id = a.place_id
                            WHERE a.normalized = idi_normalize(:q) AND p.lifecycle_status = 'active' AND p.is_destination
                            ORDER BY coalesce(p.sitelink_count, 0) DESC LIMIT 1
                            """).param("q", probe.getKey()).query(String.class).optional().orElse(null);
            boolean ok = probe.getValue().equals(top);
            resolved += ok ? 1 : 0;
            probes.add(Map.of("query", probe.getKey(), "expected", probe.getValue(), "resolvedTo", top == null ? "nothing" : top,
                    "ok", ok));
        }
        m.put("identityProbes", probes);
        checks.add(QualityCheck.atLeast("identity_resolution", "accuracy", QualityCheck.CRITICAL,
                "Historical and alternate names resolving to the right destination", resolved, IDENTITY_PROBES.size() - 1,
                resolved + " of " + IDENTITY_PROBES.size() + " probes"));

        List<Map<String, Object>> samples = jdbc.sql("""
                SELECT * FROM (
                    SELECT p.name, p.kind, p.wikidata_id, s.name AS state, d.name AS district, p.latitude, p.longitude,
                           (SELECT string_agg(a.alias, ', ' ORDER BY a.alias) FROM (SELECT alias FROM place_alias
                             WHERE place_id = p.id AND alias_type IN ('alternate', 'local_script') LIMIT 4) a) AS aliases,
                           row_number() OVER (PARTITION BY s.id ORDER BY md5(p.id::text)) AS rn
                    FROM place p
                    JOIN place_relation rs ON rs.child_id = p.id AND rs.relation = 'in_state' JOIN place s ON s.id = rs.parent_id
                    LEFT JOIN place_relation rd ON rd.child_id = p.id AND rd.relation = 'in_district' LEFT JOIN place d ON d.id = rd.parent_id
                    WHERE p.lifecycle_status = 'active' AND p.kind IN ('city', 'town')) x
                WHERE rn = 1 ORDER BY state LIMIT 12
                """).query((rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", rs.getString("name"));
                    row.put("kind", rs.getString("kind"));
                    row.put("wikidata", rs.getString("wikidata_id"));
                    row.put("state", rs.getString("state"));
                    row.put("district", rs.getString("district"));
                    row.put("latitude", rs.getDouble("latitude"));
                    row.put("longitude", rs.getDouble("longitude"));
                    row.put("aliases", rs.getString("aliases"));
                    return row;
                }).list();

        Map<String, Double> components = new LinkedHashMap<>();
        components.put("coordinates", coordsPct);
        components.put("stateRelations", statePct);
        components.put("localScriptNames", localPct);
        components.put("identityProbes", Pct.of(resolved, IDENTITY_PROBES.size()));
        components.put("uniqueness", 100 - Pct.of(dupNames, places));
        return new QualityResult(m, checks, components, samples, List.of(
                "Administrative relations come from Wikidata's 'located in' chain and inherit its errors.",
                "Places outside the nationwide queries are indexed only when requested."));
    }
}
