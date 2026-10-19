package io.destinationintel.poi;

import io.destinationintel.common.Json;
import io.destinationintel.ingestion.GuideWriter;
import io.destinationintel.ingestion.GuideWriter.EvidenceUpsert;
import io.destinationintel.ingestion.GuideWriter.FactUpsert;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Links guide entries (recommendations from Wikivoyage or contributors) to the mapped POI they
 * describe, then attaches the POI sources' own hours, phone and website as separate,
 * source-tagged facts. Values from different sources are kept side by side, never merged.
 *
 * <p>Rules: same Wikidata id; or, for entries with coordinates, name similarity >= 0.6 within
 * 250 m in a compatible category; or, for entries without coordinates, an exact name match
 * with exactly one POI in the same destination. Ambiguous cases go to review.</p>
 */
@Component
public class PoiEntryLinker {

    public record LinkCounts(int linked, int review, int factsCreated, int factsUpdated, int coordinatesFilled,
                             int coordinateConflicts) {}

    private record Link(UUID entryId, UUID poiId, String method) {}

    private record SourceAttr(UUID entryId, String section, UUID destinationId, String sourceId, String externalId,
                              String sourceVersion, OffsetDateTime sourceTimestamp, String datasetVersion,
                              String attributes) {}

    private final JdbcClient jdbc;
    private final GuideWriter writer;
    private final Json json;

    public PoiEntryLinker(JdbcClient jdbc, GuideWriter writer, Json json) {
        this.jdbc = jdbc;
        this.writer = writer;
        this.json = json;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LinkCounts link(String sourceId, OffsetDateTime runAt) {
        jdbc.sql("UPDATE guide_entry e SET poi_id = NULL FROM poi p WHERE e.poi_id = p.id AND p.status <> 'active'").update();

        List<Link> links = jdbc.sql("""
                        WITH entries AS (
                            SELECT e.id, e.destination_id, e.title_normalized, e.wikidata_id, e.geog,
                                   CASE e.section WHEN 'stay' THEN 'stay' WHEN 'buy' THEN 'buy' WHEN 'eat' THEN 'eat'
                                        WHEN 'drink' THEN 'drink' WHEN 'see' THEN 'see' WHEN 'do' THEN 'do' END AS grp
                            FROM guide_entry e
                            WHERE e.status = 'published' AND e.poi_id IS NULL AND e.section <> 'neighbourhood'),
                        by_wikidata AS (
                            SELECT DISTINCT ON (en.id) en.id AS eid, p.id AS pid, 'wikidata' AS method
                            FROM entries en JOIN poi p ON p.status = 'active' AND en.wikidata_id IS NOT NULL
                                 AND p.wikidata_id = en.wikidata_id
                            ORDER BY en.id, p.id),
                        by_distance AS (
                            SELECT DISTINCT ON (en.id) en.id AS eid, p.id AS pid, 'name_distance' AS method
                            FROM entries en JOIN poi p ON p.status = 'active' AND en.geog IS NOT NULL
                                 AND ST_DWithin(p.geog, en.geog, 250)
                                 AND (p.category_group = en.grp OR (p.category_group IN ('eat', 'drink') AND en.grp IN ('eat', 'drink'))
                                      OR (p.category_group IN ('see', 'do') AND en.grp IN ('see', 'do')))
                                 AND similarity(p.name_normalized, en.title_normalized) >= 0.6
                            WHERE en.id NOT IN (SELECT eid FROM by_wikidata)
                            ORDER BY en.id, similarity(p.name_normalized, en.title_normalized) DESC, ST_Distance(p.geog, en.geog)),
                        by_name AS (
                            SELECT en.id AS eid, min(p.id::text)::uuid AS pid, 'name_in_destination' AS method
                            FROM entries en JOIN poi p ON p.status = 'active' AND en.geog IS NULL
                                 AND p.destination_id = en.destination_id AND p.name_normalized = en.title_normalized
                                 AND (p.category_group = en.grp OR (p.category_group IN ('eat', 'drink') AND en.grp IN ('eat', 'drink'))
                                      OR (p.category_group IN ('see', 'do') AND en.grp IN ('see', 'do')))
                            WHERE en.id NOT IN (SELECT eid FROM by_wikidata)
                            GROUP BY en.id HAVING count(*) = 1)
                        SELECT eid AS entry_id, pid AS poi_id, method FROM by_wikidata
                        UNION ALL SELECT eid, pid, method FROM by_distance
                        UNION ALL SELECT eid, pid, method FROM by_name
                        """).query(Link.class).list();
        for (Link l : links) {
            jdbc.sql("""
                            UPDATE guide_entry SET poi_id = :p,
                                   attributes = attributes || jsonb_build_object('poiLink', jsonb_build_object('method', CAST(:m AS text))),
                                   updated_at = now()
                            WHERE id = :e AND poi_id IS NULL
                            """).param("p", l.poiId()).param("m", l.method()).param("e", l.entryId()).update();
        }

        int review = jdbc.sql("""
                        INSERT INTO poi_match_review (record_id, candidate_entry_id, score, reasons)
                        SELECT p.seed_record_id, e.id, 0.5,
                               jsonb_build_array('same name in destination, entry has no coordinates', 'several mapped places share this name')
                        FROM guide_entry e
                        JOIN poi p ON p.status = 'active' AND p.destination_id = e.destination_id
                             AND p.name_normalized = e.title_normalized AND p.seed_record_id IS NOT NULL
                        WHERE e.status = 'published' AND e.poi_id IS NULL AND e.geog IS NULL
                          AND (SELECT count(*) FROM poi p2 WHERE p2.status = 'active' AND p2.destination_id = e.destination_id
                               AND p2.name_normalized = e.title_normalized) > 1
                        ON CONFLICT DO NOTHING
                        """).update();

        int filled = jdbc.sql("""
                        UPDATE guide_entry e SET latitude = p.latitude, longitude = p.longitude,
                               attributes = e.attributes || jsonb_build_object('coordinatesSource',
                                   (SELECT r.source_id || ':' || r.external_id FROM poi_source_record r WHERE r.id = p.seed_record_id)),
                               updated_at = now()
                        FROM poi p
                        WHERE e.poi_id = p.id AND e.latitude IS NULL AND e.geo_flag IS NULL
                        """).update();
        int conflicts = jdbc.sql("""
                        SELECT count(*) FROM guide_entry e JOIN poi p ON p.id = e.poi_id
                        WHERE e.geog IS NOT NULL AND ST_Distance(e.geog, p.geog) > 300
                        """).query(Integer.class).single();

        int created = 0;
        int updated = 0;
        List<SourceAttr> attrs = jdbc.sql("""
                        SELECT e.id AS entry_id, e.section, e.destination_id, r.source_id, r.external_id, r.source_version,
                               r.source_timestamp, r.dataset_version, r.attributes::text AS attributes
                        FROM guide_entry e JOIN poi_source_record r ON r.poi_id = e.poi_id AND r.deleted_at IS NULL
                        WHERE e.status = 'published' AND r.source_id = :s
                        """).param("s", sourceId).query(SourceAttr.class).list();
        for (SourceAttr a : attrs) {
            Map<String, Object> m = json.map(a.attributes());
            for (String[] kv : new String[][] {{"opening_hours", "hours", "volatile"}, {"phone", "phone", "semi_stable"},
                    {"website", "website", "semi_stable"}}) {
                Object v = m.get(kv[0]);
                if (!(v instanceof String text) || text.isBlank()) {
                    continue;
                }
                LocalDate observed = a.sourceTimestamp() == null ? null
                        : a.sourceTimestamp().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
                Map<String, Object> valueJson = kv[1].equals("website") ? Map.of("url", text) : null;
                FactUpsert f = new FactUpsert(a.destinationId(), a.entryId(), a.section(), kv[1], null, text, valueJson,
                        kv[2], observed, observed == null ? "unknown" : "source_listing_date",
                        "poi:" + a.sourceId() + ":" + a.externalId() + ":" + a.entryId() + ":" + kv[1]);
                GuideWriter.Outcome o = writer.upsertFact(f, evidence(a, text, observed), runAt);
                if (o == GuideWriter.Outcome.CREATED) {
                    created++;
                } else if (o == GuideWriter.Outcome.UPDATED) {
                    updated++;
                }
            }
        }
        writer.hideMissing("poi:" + sourceId + ":", runAt);
        return new LinkCounts(links.size(), review, created, updated, filled, conflicts);
    }

    /**
     * Withdraws the facts a source added to guide entries, used when the source fails its quality
     * gate. Facts that readers have independently confirmed stay, as everywhere else.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int withdraw(String sourceId) {
        return writer.hideMissing("poi:" + sourceId + ":", OffsetDateTime.now().plusYears(100));
    }

    private static EvidenceUpsert evidence(SourceAttr a, String text, LocalDate observed) {
        String url;
        String title;
        if (a.sourceId().equals("openstreetmap")) {
            String[] parts = a.externalId().split("/");
            url = "https://www.openstreetmap.org/" + a.externalId();
            title = "OpenStreetMap " + parts[0] + " " + (parts.length > 1 ? parts[1] : "") + " (version " + a.sourceVersion() + ")";
        } else {
            url = "https://docs.overturemaps.org/guides/places/";
            title = "Overture Maps Places " + a.datasetVersion() + ", id " + a.externalId();
        }
        return new EvidenceUpsert(a.sourceId(), url, null, title, text, "poi_attribute_link", a.sourceVersion(),
                null, observed);
    }
}
