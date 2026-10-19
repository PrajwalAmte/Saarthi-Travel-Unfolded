package io.destinationintel.poi;

import io.destinationintel.common.ApiException;
import io.destinationintel.common.Json;
import io.destinationintel.place.PlaceRepository;
import io.destinationintel.place.PlaceRow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mapped places near a destination from open map sources (OpenStreetMap, Overture, ASI via
 * Wikidata). These are map data, not recommendations: nobody has reviewed them for a guide, and
 * the response says so. Only sources whose latest quality evaluation did not fail, and that are
 * enabled for publication, are included. Ordering is deterministic and carries no judgement:
 * places described by more independent sources first, then those documented in Wikidata, then
 * by name.
 */
@RestController
@Tag(name = "Mapped places", description = "Unreviewed map data near a destination, from quality-gated open sources")
public class MappedPlacesController {

    static final Set<String> GROUPS = Set.of("eat", "drink", "stay", "see", "do", "buy", "transport");

    public record SourceRef(String sourceId, String sourceName, String externalId, String url, String datasetVersion,
                            String licence) {}

    public record MappedPlace(UUID id, String name, String category, String group, double latitude, double longitude,
                              Double distanceKm, String wikidataId, Map<String, Object> details, List<String> heritage,
                              UUID guideEntryId, List<SourceRef> sources) {}

    public record MappedPlaces(String destination, String notice, Map<String, Long> countsByGroup, String group,
                               long total, int offset, int limit, List<MappedPlace> places, List<String> sourcesIncluded,
                               List<String> sourcesWithheld, List<String> attribution) {}

    private final PlaceRepository places;
    private final JdbcClient jdbc;
    private final Json json;

    public MappedPlacesController(PlaceRepository places, JdbcClient jdbc, Json json) {
        this.places = places;
        this.jdbc = jdbc;
        this.json = json;
    }

    private static final String PUBLISHABLE = """
            s.enabled AND s.publish_enabled AND s.quality_status IN ('passed', 'warning')""";

    @GetMapping("/api/destinations/{slug}/mapped-places")
    @Operation(summary = "Unreviewed mapped places near a destination, from sources that passed their quality gate")
    public MappedPlaces mappedPlaces(@PathVariable String slug, @RequestParam(required = false) String group,
                                     @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "40") int limit) {
        PlaceRow place = places.findBySlug(slug).filter(p -> "active".equals(p.lifecycleStatus()))
                .orElseThrow(() -> ApiException.notFound("Destination"));
        if (group != null && !GROUPS.contains(group)) {
            throw ApiException.badRequest("invalid_group", "Unknown group; use one of " + String.join(", ", GROUPS));
        }
        int lim = Math.max(1, Math.min(limit, 100));
        int off = Math.max(0, Math.min(offset, 10_000));

        List<String> included = jdbc.sql("SELECT s.id FROM source s WHERE " + PUBLISHABLE
                        + " AND EXISTS (SELECT 1 FROM poi_source_record r WHERE r.source_id = s.id) ORDER BY s.id")
                .query(String.class).list();
        List<String> attribution = jdbc.sql("SELECT s.attribution_text FROM source s WHERE " + PUBLISHABLE
                        + " AND EXISTS (SELECT 1 FROM poi_source_record r WHERE r.source_id = s.id) ORDER BY s.id")
                .query(String.class).list();
        List<String> withheld = jdbc.sql("SELECT s.id FROM source s WHERE NOT (" + PUBLISHABLE
                        + ") AND EXISTS (SELECT 1 FROM poi_source_record r WHERE r.source_id = s.id) ORDER BY s.id")
                .query(String.class).list();

        String visible = """
                FROM poi p
                WHERE p.destination_id = :d AND p.status = 'active'
                  AND EXISTS (SELECT 1 FROM poi_source_record r JOIN source s ON s.id = r.source_id
                              WHERE r.poi_id = p.id AND r.deleted_at IS NULL AND %s)""".formatted(PUBLISHABLE);
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.sql("SELECT p.category_group, count(*) " + visible + " GROUP BY 1 ORDER BY 1").param("d", place.id())
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getLong(2))).list().forEach(e -> counts.put(e.getKey(), e.getValue()));
        long total = group == null ? counts.values().stream().mapToLong(Long::longValue).sum() : counts.getOrDefault(group, 0L);

        record Row(UUID id, String name, String category, String group, double lat, double lon, Double km, String qid, UUID entry) {}
        List<Row> rows = jdbc.sql("SELECT p.id, p.name, p.category, p.category_group, p.latitude, p.longitude, p.destination_km, "
                        + "p.wikidata_id, (SELECT e.id FROM guide_entry e WHERE e.poi_id = p.id AND e.status = 'published' LIMIT 1) "
                        + visible + (group == null ? "" : " AND p.category_group = :g")
                        + " ORDER BY (SELECT count(DISTINCT r.source_id) FROM poi_source_record r WHERE r.poi_id = p.id AND r.deleted_at IS NULL) DESC,"
                        + " (p.wikidata_id IS NOT NULL) DESC, p.name_normalized, p.id OFFSET :off LIMIT :lim")
                .param("d", place.id()).param("g", group).param("off", off).param("lim", lim)
                .query((rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getDouble(5), rs.getDouble(6), rs.getObject(7) == null ? null : Math.round(rs.getDouble(7) * 10) / 10.0,
                        rs.getString(8), rs.getObject(9, UUID.class)))
                .list();

        List<MappedPlace> out = new ArrayList<>();
        for (Row r : rows) {
            record Rec(String sourceId, String sourceName, String licence, String externalId, String datasetVersion, String attrs) {}
            List<Rec> recs = jdbc.sql("""
                            SELECT r.source_id, s.name AS source_name, s.licence_name AS licence, r.external_id, r.dataset_version,
                                   r.attributes::text AS attrs
                            FROM poi_source_record r JOIN source s ON s.id = r.source_id
                            WHERE r.poi_id = :p AND r.deleted_at IS NULL AND %s ORDER BY r.source_id""".formatted(PUBLISHABLE))
                    .param("p", r.id()).query(Rec.class).list();
            Map<String, Object> details = new LinkedHashMap<>();
            List<String> heritage = new ArrayList<>();
            List<SourceRef> refs = new ArrayList<>();
            for (Rec rec : recs) {
                Map<String, Object> a = json.map(rec.attrs());
                for (String k : List.of("address", "opening_hours", "phone", "website", "cuisine", "local_name")) {
                    if (a.get(k) != null && !details.containsKey(k)) {
                        // Each value names the source it came from; values are never blended.
                        details.put(k, Map.of("value", a.get(k), "sourceId", rec.sourceId()));
                    }
                }
                if (a.get("asi_monument_ids") instanceof List<?> ids && !ids.isEmpty()) {
                    heritage.add("ASI monument " + String.join(", ", ids.stream().map(String::valueOf).toList()));
                }
                if (a.get("designation") instanceof List<?> d) {
                    d.forEach(x -> heritage.add(String.valueOf(x)));
                }
                refs.add(new SourceRef(rec.sourceId(), rec.sourceName(), rec.externalId(), sourceUrl(rec.sourceId(), rec.externalId()),
                        rec.datasetVersion(), rec.licence()));
            }
            out.add(new MappedPlace(r.id(), r.name(), r.category(), r.group(), r.lat(), r.lon(), r.km(), r.qid(), details,
                    heritage.stream().distinct().toList(), r.entry(), refs));
        }
        return new MappedPlaces(place.name(),
                "Mapped places from open map data. They have not been reviewed for this guide and are not recommendations; "
                        + "details may be out of date.",
                counts, group, total, off, lim, out, included, withheld, attribution);
    }

    static String sourceUrl(String sourceId, String externalId) {
        return switch (sourceId) {
            case "openstreetmap" -> externalId.matches("^(node|way|relation)/\\d+$") ? "https://www.openstreetmap.org/" + externalId : null;
            case "asi-heritage" -> externalId.matches("^Q\\d+$") ? "https://www.wikidata.org/wiki/" + externalId : null;
            default -> null;
        };
    }
}
