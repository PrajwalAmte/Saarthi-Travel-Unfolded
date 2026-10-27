package io.destinationintel.quality;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Quality of guide content parsed from Wikivoyage: provenance, geography, duplicates and freshness. */
@Component
public class WikivoyageQualityEvaluator implements SourceQualityEvaluator {

    private final JdbcClient jdbc;

    public WikivoyageQualityEvaluator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String sourceId() {
        return "wikivoyage-en";
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    @Override
    public QualityResult evaluate(UUID runId) {
        Map<String, Object> m = QualityResult.newMetrics();
        List<QualityCheck> checks = new ArrayList<>();
        String wvFacts = "FROM fact f WHERE f.status = 'published' AND f.origin = 'ingested' AND f.ingest_key LIKE 'wv:%'";
        long facts = count("SELECT count(*) " + wvFacts);
        long entries = count("SELECT count(*) FROM guide_entry WHERE status = 'published' AND origin = 'ingested' AND ingest_key LIKE 'wv:%'");
        m.put("publishedFacts", facts);
        m.put("publishedEntries", entries);
        m.put("guides", count("SELECT count(DISTINCT destination_id) " + wvFacts));
        checks.add(QualityCheck.atLeast("non_empty", "completeness", QualityCheck.CRITICAL, "Published Wikivoyage facts", facts, 1, null));

        long withEvidence = count("SELECT count(*) " + wvFacts + " AND EXISTS (SELECT 1 FROM evidence e WHERE e.fact_id = f.id AND e.source_id = 'wikivoyage-en' AND e.url IS NOT NULL)");
        long withRevision = count("SELECT count(*) " + wvFacts + " AND EXISTS (SELECT 1 FROM evidence e WHERE e.fact_id = f.id AND e.permalink LIKE '%oldid=%')");
        checks.add(QualityCheck.atLeast("provenance", "provenance", QualityCheck.CRITICAL,
                "Facts with a Wikivoyage evidence link (%)", Pct.of(withEvidence, facts), 100, null));
        checks.add(QualityCheck.atLeast("revision_permalinks", "provenance", QualityCheck.CRITICAL,
                "Facts citing an exact article revision (%)", Pct.of(withRevision, facts), 99, null));

        long withDescription = count("""
                SELECT count(*) FROM guide_entry e WHERE e.status = 'published' AND e.ingest_key LIKE 'wv:%' AND e.section <> 'neighbourhood'
                  AND EXISTS (SELECT 1 FROM fact f WHERE f.entry_id = e.id AND f.kind = 'description' AND f.status = 'published')""");
        long listingEntries = count("SELECT count(*) FROM guide_entry WHERE status = 'published' AND ingest_key LIKE 'wv:%' AND section <> 'neighbourhood'");
        checks.add(QualityCheck.atLeast("descriptions", "completeness", QualityCheck.WARNING,
                "Listings with a description (%)", Pct.of(withDescription, listingEntries), 80, null));
        long flagged = count("SELECT count(*) FROM guide_entry WHERE ingest_key LIKE 'wv:%' AND geo_flag IS NOT NULL");
        m.put("entriesWithImplausibleCoordinates", flagged);
        long withCoords = count("SELECT count(*) FROM guide_entry WHERE status = 'published' AND ingest_key LIKE 'wv:%' AND latitude IS NOT NULL");
        m.put("entriesWithCoordinatesPct", Pct.of(withCoords, listingEntries));
        long badCoords = count("""
                SELECT count(*) FROM guide_entry e JOIN place p ON p.id = e.destination_id
                WHERE e.status = 'published' AND e.geog IS NOT NULL AND p.geog IS NOT NULL
                  AND ST_Distance(e.geog, p.geog) > CASE WHEN p.kind IN ('state', 'union_territory', 'region', 'park') THEN 600000 ELSE 80000 END""");
        checks.add(QualityCheck.atMost("implausible_coordinates", "geographic", QualityCheck.CRITICAL,
                "Published entries plotted implausibly far from their destination", badCoords, 0,
                "such coordinates are removed and flagged during ingestion"));
        long dupes = count("""
                SELECT count(*) FROM guide_entry a JOIN guide_entry b ON b.id > a.id AND b.destination_id = a.destination_id
                     AND b.status = 'published' AND b.title_normalized = a.title_normalized AND b.section = a.section
                     AND (ST_DWithin(a.geog, b.geog, 150)
                          OR EXISTS (SELECT 1 FROM fact fa JOIN fact fb ON fb.entry_id = b.id AND fb.kind = 'address' AND fb.status = 'published'
                                     WHERE fa.entry_id = a.id AND fa.kind = 'address' AND fa.status = 'published'
                                       AND idi_normalize(fa.value_text) = idi_normalize(fb.value_text)))
                WHERE a.status = 'published'""");
        checks.add(QualityCheck.atMost("duplicate_entries", "duplicates", QualityCheck.WARNING,
                "Same-name entries at the same place or address (%)", Pct.of(dupes, listingEntries), 0.5,
                "branches of a chain at different addresses are shown as one place with its other locations"));
        // Text that shows an import defect to readers: empty brackets and "(see )" left by dropped templates, and
        // brackets that never close. Any such text fails the import, so the defect cannot reach a guide again.
        long artifacts = count("SELECT count(*) " + wvFacts + " AND (f.value_text ~* '\\((?:see|see also|cf\\.?|compare|also)[ \\t,;:]*\\)' OR f.value_text ~ '[(\\[][ \\t]*[)\\]]')");
        checks.add(QualityCheck.atMost("text_artifacts", "text", QualityCheck.CRITICAL,
                "Published texts with empty brackets or a dangling \"(see )\"", artifacts, 0,
                "left behind when a wiki template is dropped; WikiText.cleanArtifacts removes them on import"));
        long unbalanced = count("SELECT count(*) " + wvFacts + " AND f.kind IN ('description', 'address', 'what_to_order', 'summary')"
                + " AND length(f.value_text) - length(replace(f.value_text, '(', '')) <> length(f.value_text) - length(replace(f.value_text, ')', ''))");
        checks.add(QualityCheck.atMost("unbalanced_brackets", "text", QualityCheck.WARNING,
                "Descriptions and addresses with unbalanced brackets (%)", Pct.of(unbalanced, facts), 0.5,
                "usually truncated or half-stripped markup"));
        // A landmark under "Eat" or "Stay" is only right when its text is about food or lodging ("food courts at India Gate").
        long misfiled = count("""
                SELECT count(*) FROM guide_entry e
                WHERE e.status = 'published' AND e.ingest_key LIKE 'wv:%' AND e.section IN ('eat', 'drink', 'stay')
                  AND e.title ~* '\\m(gate|fort|temple|palace|museum|memorial|tomb|mosque|church|monument|bridge|lake|beach|garden|zoo|ghat)\\M'
                  AND NOT EXISTS (SELECT 1 FROM fact f WHERE f.entry_id = e.id AND f.kind = 'description' AND f.status = 'published'
                                  AND f.value_text ~* '(food|restaurant|caf[eé]|eat|meal|dine|dining|cuisine|snack|stall|thali|biryani|hotel|room|guest ?house|lodg|dorm|hostel|bed|stay)')""");
        checks.add(QualityCheck.atMost("misfiled_landmarks", "relevance", QualityCheck.WARNING,
                "Landmarks listed under Eat or Stay whose text is not about food or lodging (%)", Pct.of(misfiled, listingEntries), 0.5,
                "an eating or sleeping listing must say what it offers"));
        long chainPlaces = count("""
                SELECT count(*) FROM (SELECT destination_id, section, title_normalized FROM guide_entry
                    WHERE status = 'published' AND section <> 'neighbourhood' GROUP BY 1, 2, 3 HAVING count(*) > 1) x""");
        m.put("placesShownWithSeveralBranches", chainPlaces);
        m.put("textArtifacts", artifacts);
        m.put("misfiledLandmarks", misfiled);
        m.put("aiOpenFlags", count("""
                SELECT count(*) FROM ai_review r JOIN guide_entry e ON e.id = r.entry_id
                WHERE r.status = 'open' AND e.ingest_key LIKE 'wv:%'"""));
        long sameNameElsewhere = count("""
                SELECT count(*) FROM (SELECT destination_id, title_normalized FROM guide_entry WHERE status = 'published'
                    AND section <> 'neighbourhood' GROUP BY 1, 2 HAVING count(*) > 1) x""");
        m.put("namesListedMoreThanOnceInADestination", sameNameElsewhere);

        long dated = count("SELECT count(*) " + wvFacts + " AND f.observed_at IS NOT NULL");
        long volatileFacts = count("SELECT count(*) " + wvFacts + " AND f.volatility = 'volatile'");
        long staleVolatile = count("SELECT count(*) " + wvFacts + " AND f.volatility = 'volatile' AND (f.observed_at IS NULL OR f.observed_at < now() - interval '120 days') AND f.last_verified_at IS NULL");
        m.put("factsWithObservationDatePct", Pct.of(dated, facts));
        m.put("volatileFactsStalePct", Pct.of(staleVolatile, volatileFacts));
        checks.add(QualityCheck.info("stale_volatile", "freshness",
                "Volatile facts (prices, hours) past their review window or undated (%)", Pct.of(staleVolatile, volatileFacts),
                "shown to readers as 'needs re-verification', never as current"));
        long prices = count("SELECT count(*) " + wvFacts + " AND f.kind = 'price'");
        long parsed = count("SELECT count(*) " + wvFacts + " AND f.kind = 'price' AND jsonb_exists(f.value_json, 'min')");
        m.put("pricesWithRupeeAmountPct", Pct.of(parsed, prices));

        List<Map<String, Object>> samples = jdbc.sql("""
                SELECT p.name AS destination, e.section, e.title, e.subsection,
                       (SELECT left(value_text, 140) FROM fact f WHERE f.entry_id = e.id AND f.kind = 'description' AND f.status = 'published' LIMIT 1) AS description,
                       (SELECT value_text FROM fact f WHERE f.entry_id = e.id AND f.kind = 'price' AND f.status = 'published' LIMIT 1) AS price,
                       (SELECT observed_at::text FROM fact f WHERE f.entry_id = e.id AND f.status = 'published' ORDER BY observed_at DESC NULLS LAST LIMIT 1) AS observed
                FROM guide_entry e JOIN place p ON p.id = e.destination_id
                WHERE e.status = 'published' AND e.ingest_key LIKE 'wv:%' AND e.section <> 'neighbourhood'
                ORDER BY md5(e.id::text) LIMIT 10
                """).query((rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (String c : new String[] {"destination", "section", "title", "subsection", "description", "price", "observed"}) {
                        row.put(c, rs.getString(c));
                    }
                    return row;
                }).list();
        Map<String, Double> components = new LinkedHashMap<>();
        components.put("provenance", Pct.of(withRevision, facts));
        components.put("descriptions", Pct.of(withDescription, listingEntries));
        components.put("dated", Pct.of(dated, facts));
        components.put("uniqueness", 100 - Pct.of(dupes, listingEntries));
        return new QualityResult(m, checks, components, samples, List.of(
                "Many Wikivoyage listings were last edited years ago; their prices and hours are labelled as needing re-verification.",
                "Prose sections carry no per-paragraph dates, so their observation date is recorded as unknown."));
    }
}
