package io.destinationintel.ingestion.wikidata;

import io.destinationintel.common.GeoPlausibility;
import io.destinationintel.config.AppProperties;
import io.destinationintel.ingestion.HttpFetcher;
import io.destinationintel.ingestion.JobRepository;
import io.destinationintel.ingestion.run.SyncRunRecorder.Run;
import io.destinationintel.place.PlaceRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consistency passes run after a nationwide Wikidata index. Each one corrects a specific, measured
 * problem from a named source and records where the result came from; nothing is guessed.
 *
 * <ol>
 *   <li>Active places that no nationwide query returned are re-checked against their current item,
 *       so items that are not places (a pandemic, a language variety) leave the index.</li>
 *   <li>A district without a point, or whose point is more than {@value #HQ_LIMIT_KM} km from its own
 *       headquarters (P36), uses the headquarters' coordinates.</li>
 *   <li>A place still without coordinates uses its Wikivoyage article's coordinates.</li>
 *   <li>A place more than {@value #FAR_KM} km from its stated district that is closer to another
 *       district is resolved with OpenStreetMap as the tie-breaker: if the OSM place node agrees
 *       with the district, Wikidata's coordinates are the error and OSM's are used; otherwise the
 *       district relation is the error and is dropped (with the state relation if the nearest
 *       district lies in another state). No replacement relation is invented.</li>
 * </ol>
 */
@Service
public class IndexConsistency {

    static final double HQ_LIMIT_KM = 100;
    static final double FAR_KM = 200;

    private final WikidataClient client;
    private final PlaceRepository places;
    private final JdbcClient jdbc;
    private final HttpFetcher http;
    private final JsonMapper mapper;
    private final JobRepository jobs;
    private final String wikivoyageApi;

    public IndexConsistency(WikidataClient client, PlaceRepository places, JdbcClient jdbc, HttpFetcher http, JsonMapper mapper,
                            JobRepository jobs, AppProperties props) {
        this.client = client;
        this.places = places;
        this.jdbc = jdbc;
        this.http = http;
        this.mapper = mapper;
        this.jobs = jobs;
        this.wikivoyageApi = props.ingestion().wikivoyageApiUrl();
    }

    public Map<String, Object> run(Set<String> seen, Run run, UUID jobId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("revalidatedUnseen", revalidateUnseen(seen, run, jobId));
        stats.put("districtPointsFromHeadquarters", repairDistrictPoints(run, jobId));
        stats.put("coordinatesFromWikivoyage", fillFromWikivoyage(run, jobId));
        stats.put("districtRelations", checkDistrictRelations(run));
        return stats;
    }

    private record Known(String qid, String wikivoyageTitle, String kind) {}

    /** Re-checks active places that no nationwide query returned; removes those that are not places. */
    Map<String, Integer> revalidateUnseen(Set<String> seen, Run run, UUID jobId) {
        List<Known> unseen = jdbc.sql("""
                        SELECT wikidata_id AS qid, wikivoyage_title, kind FROM place
                        WHERE lifecycle_status = 'active' AND is_destination AND wikidata_id IS NOT NULL ORDER BY wikidata_id
                        """).query(Known.class).list().stream().filter(k -> !seen.contains(k.qid())).toList();
        int checked = 0;
        int removed = 0;
        int coordinates = 0;
        int reclassified = 0;
        for (int i = 0; i < unseen.size(); i += 50) {
            List<Known> batch = unseen.subList(i, Math.min(unseen.size(), i + 50));
            JsonNode entities = client.entities(batch.stream().map(Known::qid).toList(), "claims");
            Map<String, EntityInfo> infos = new LinkedHashMap<>();
            Set<String> typeIds = new LinkedHashSet<>();
            for (Known k : batch) {
                run.fetched.incrementAndGet();
                JsonNode node = entities.path(k.qid());
                if (node.isMissingNode() || node.has("missing")) {
                    // Possibly merged into another item: counted, never removed on this evidence alone.
                    run.reject("entity_missing");
                    continue;
                }
                EntityInfo info = EntityInfo.parse(node);
                infos.put(k.qid(), info);
                typeIds.addAll(info.instanceOf());
            }
            Map<String, String> typeLabels = labels(new ArrayList<>(typeIds));
            for (Known k : batch) {
                EntityInfo info = infos.get(k.qid());
                if (info == null) {
                    continue;
                }
                run.parsed.incrementAndGet();
                checked++;
                String kind = info.instanceOf().contains(WikidataIndexer.STATE) ? "state"
                        : info.instanceOf().contains(WikidataIndexer.UNION_TERRITORY) ? "union_territory"
                        : info.instanceOf().contains(WikidataIndexer.DISTRICT) ? "district"
                        : PlaceKindClassifier.classify(info.instanceOf().stream().map(typeLabels::get).filter(l -> l != null).toList(),
                                k.wikivoyageTitle()).orElse(null);
                String reason = kind == null ? "not_a_destination"
                        : !info.countries().isEmpty() && !info.countries().contains(WikidataIndexer.INDIA) ? "outside_india"
                        : info.latitude() != null && !GeoPlausibility.insideIndia(info.latitude(), info.longitude()) ? "outside_india"
                        : null;
                if (reason != null) {
                    run.reject(reason);
                    int r = places.softRemoveIfUnused(k.qid());
                    removed += r;
                    run.deleted.addAndGet(r);
                    continue;
                }
                if (!kind.equals(k.kind())) {
                    reclassified += jdbc.sql("UPDATE place SET kind = :k, updated_at = now() WHERE wikidata_id = :q")
                            .param("k", kind).param("q", k.qid()).update();
                }
                if (info.latitude() != null) {
                    coordinates += jdbc.sql("""
                                    UPDATE place SET latitude = :lat, longitude = :lon, coordinates_source = 'wikidata', updated_at = now()
                                    WHERE wikidata_id = :q AND (latitude IS DISTINCT FROM :lat OR longitude IS DISTINCT FROM :lon)
                                    """).param("lat", info.latitude()).param("lon", info.longitude()).param("q", k.qid()).update();
                }
            }
            heartbeat(jobId);
        }
        run.updated.addAndGet(reclassified + coordinates);
        return Map.of("checked", checked, "removed", removed, "reclassified", reclassified, "coordinatesUpdated", coordinates);
    }

    private record District(UUID id, String qid, String name, Double latitude, Double longitude) {}

    /** Districts without a point, or whose point contradicts their headquarters, use the headquarters' point. */
    Map<String, Object> repairDistrictPoints(Run run, UUID jobId) {
        List<District> districts = jdbc.sql("""
                        SELECT id, wikidata_id AS qid, name, latitude, longitude FROM place
                        WHERE kind = 'district' AND lifecycle_status = 'active' AND wikidata_id IS NOT NULL ORDER BY wikidata_id
                        """).query(District.class).list();
        Map<String, String> hqOf = new HashMap<>();
        for (int i = 0; i < districts.size(); i += 50) {
            List<String> batch = districts.subList(i, Math.min(districts.size(), i + 50)).stream().map(District::qid).toList();
            JsonNode entities = client.entities(batch, "claims");
            for (String q : batch) {
                String hq = preferredItem(entities.path(q).path("claims").path("P36"));
                if (hq != null) {
                    hqOf.put(q, hq);
                }
            }
            heartbeat(jobId);
        }
        Map<String, double[]> hqPoint = points(new ArrayList<>(new LinkedHashSet<>(hqOf.values())));
        List<String> filled = new ArrayList<>();
        List<String> corrected = new ArrayList<>();
        for (District d : districts) {
            double[] hq = hqPoint.get(hqOf.get(d.qid()));
            if (hq == null) {
                continue;
            }
            boolean missing = d.latitude() == null;
            boolean contradicts = !missing && GeoPlausibility.haversineKm(d.latitude(), d.longitude(), hq[0], hq[1]) > HQ_LIMIT_KM;
            if (!missing && !contradicts) {
                continue;
            }
            jdbc.sql("""
                            UPDATE place SET latitude = :lat, longitude = :lon, coordinates_source = 'wikidata_headquarters', updated_at = now()
                            WHERE id = :id
                            """).param("lat", hq[0]).param("lon", hq[1]).param("id", d.id()).update();
            (missing ? filled : corrected).add(d.name());
        }
        if (!corrected.isEmpty()) {
            run.warn(corrected.size() + " district points contradicted their own headquarters by more than " + (int) HQ_LIMIT_KM
                    + " km and now use the headquarters' coordinates: " + String.join(", ", corrected.subList(0, Math.min(10, corrected.size()))));
        }
        run.updated.addAndGet(filled.size() + corrected.size());
        return Map.of("filled", filled.size(), "corrected", corrected.size(), "correctedDistricts", corrected);
    }

    private record Missing(UUID id, String title) {}

    /** Places still without coordinates take them from their Wikivoyage article, when it states any. */
    int fillFromWikivoyage(Run run, UUID jobId) {
        List<Missing> missing = jdbc.sql("""
                        SELECT id, wikivoyage_title AS title FROM place
                        WHERE lifecycle_status = 'active' AND is_destination AND latitude IS NULL AND wikivoyage_title IS NOT NULL
                        ORDER BY wikivoyage_title
                        """).query(Missing.class).list();
        int filled = 0;
        for (int i = 0; i < missing.size(); i += 50) {
            List<Missing> batch = missing.subList(i, Math.min(missing.size(), i + 50));
            Map<String, String> q = new LinkedHashMap<>();
            q.put("action", "query");
            q.put("prop", "coordinates");
            // The API returns coordinates for 10 pages per request unless asked for more.
            q.put("colimit", "max");
            q.put("coprimary", "primary");
            q.put("titles", String.join("|", batch.stream().map(m -> m.title().replace('_', ' ')).toList()));
            q.put("format", "json");
            q.put("formatversion", "2");
            JsonNode root = mapper.readTree(http.get(wikivoyageApi, q, "application/json"));
            Map<String, String> normalized = new HashMap<>();
            for (JsonNode n : root.path("query").path("normalized")) {
                normalized.put(n.path("to").asString(), n.path("from").asString());
            }
            Map<String, double[]> byTitle = new HashMap<>();
            for (JsonNode page : root.path("query").path("pages")) {
                for (JsonNode c : page.path("coordinates")) {
                    if (c.path("primary").asBoolean(false) && "earth".equals(c.path("globe").asString("earth"))) {
                        String title = page.path("title").asString();
                        byTitle.put(normalized.getOrDefault(title, title), new double[] {c.path("lat").asDouble(), c.path("lon").asDouble()});
                    }
                }
            }
            for (Missing m : batch) {
                double[] pt = byTitle.get(m.title().replace('_', ' '));
                if (pt == null || !GeoPlausibility.insideIndia(pt[0], pt[1])) {
                    continue;
                }
                filled += jdbc.sql("""
                                UPDATE place SET latitude = :lat, longitude = :lon, coordinates_source = 'wikivoyage', updated_at = now()
                                WHERE id = :id AND latitude IS NULL
                                """).param("lat", pt[0]).param("lon", pt[1]).param("id", m.id()).update();
            }
            heartbeat(jobId);
        }
        run.updated.addAndGet(filled);
        return filled;
    }

    private record Far(UUID id, String name, double latitude, double longitude, UUID districtId, String districtName,
                       UUID nearestId, Double osmLat, Double osmLon, Double osmToDistrictKm) {}

    /** Resolves places implausibly far from their stated district, with OSM as the tie-breaker. */
    public Map<String, Object> checkDistrictRelations(Run run) {
        List<Far> far = jdbc.sql("""
                        SELECT p.id, p.name, p.latitude, p.longitude, d.id AS district_id, d.name AS district_name,
                               (SELECT n.id FROM place n WHERE n.kind = 'district' AND n.lifecycle_status = 'active'
                                  AND n.geog IS NOT NULL ORDER BY n.geog <-> p.geog LIMIT 1) AS nearest_id,
                               (i.attributes ->> 'latitude')::float8 AS osm_lat, (i.attributes ->> 'longitude')::float8 AS osm_lon,
                               ST_Distance(d.geog, ST_SetSRID(ST_MakePoint((i.attributes ->> 'longitude')::float8,
                                   (i.attributes ->> 'latitude')::float8), 4326)::geography) / 1000.0 AS osm_to_district_km
                        FROM place p
                        JOIN place_relation r ON r.child_id = p.id AND r.relation = 'in_district'
                        JOIN place d ON d.id = r.parent_id
                        LEFT JOIN LATERAL (SELECT attributes FROM place_source_identity
                                           WHERE place_id = p.id AND source_id = 'openstreetmap' LIMIT 1) i ON true
                        WHERE p.lifecycle_status = 'active' AND p.geog IS NOT NULL AND d.geog IS NOT NULL
                          AND ST_Distance(p.geog, d.geog) > :far
                        ORDER BY p.name
                        """).param("far", FAR_KM * 1000).query(Far.class).list();
        List<String> coordinatesFromOsm = new ArrayList<>();
        List<String> relationsDropped = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        for (Far f : far) {
            if (f.osmLat() != null && f.osmToDistrictKm() != null && f.osmToDistrictKm() <= FAR_KM
                    && GeoPlausibility.insideIndia(f.osmLat(), f.osmLon())) {
                jdbc.sql("""
                                UPDATE place SET latitude = :lat, longitude = :lon, coordinates_source = 'openstreetmap', updated_at = now()
                                WHERE id = :id
                                """).param("lat", f.osmLat()).param("lon", f.osmLon()).param("id", f.id()).update();
                coordinatesFromOsm.add(f.name());
            } else if (f.nearestId() != null && !f.nearestId().equals(f.districtId())) {
                jdbc.sql("DELETE FROM place_relation WHERE child_id = :c AND relation = 'in_district'").param("c", f.id()).update();
                // If the nearest district belongs to another state, the state came from the same faulty chain.
                jdbc.sql("""
                                DELETE FROM place_relation r WHERE r.child_id = :c AND r.relation = 'in_state'
                                  AND NOT EXISTS (SELECT 1 FROM place_relation n WHERE n.child_id = :nearest
                                                  AND n.relation = 'in_state' AND n.parent_id = r.parent_id)
                                  AND EXISTS (SELECT 1 FROM place_relation n WHERE n.child_id = :nearest AND n.relation = 'in_state')
                                """).param("c", f.id()).param("nearest", f.nearestId()).update();
                relationsDropped.add(f.name() + " (stated: " + f.districtName() + ")");
            } else {
                kept.add(f.name() + " (" + f.districtName() + ")");
            }
        }
        if (!coordinatesFromOsm.isEmpty()) {
            run.warn("Coordinates taken from OpenStreetMap where Wikidata's disagreed with the stated district: "
                    + String.join(", ", coordinatesFromOsm));
        }
        if (!relationsDropped.isEmpty()) {
            run.warn("District relations dropped as implausible (the place is nearer another district and no third source "
                    + "confirms it): " + String.join(", ", relationsDropped));
        }
        run.updated.addAndGet(coordinatesFromOsm.size() + relationsDropped.size());
        return Map.of("coordinatesFromOsm", coordinatesFromOsm, "relationsDropped", relationsDropped,
                "keptLargeDistrict", kept);
    }

    /** Coordinates of items: from the index when present there, otherwise from Wikidata. */
    private Map<String, double[]> points(List<String> qids) {
        Map<String, double[]> out = new HashMap<>();
        List<String> fetch = new ArrayList<>();
        for (String q : qids) {
            var known = places.findByWikidataId(q).filter(p -> p.hasCoordinates() && "active".equals(p.lifecycleStatus()));
            if (known.isPresent()) {
                out.put(q, new double[] {known.get().latitude(), known.get().longitude()});
            } else {
                fetch.add(q);
            }
        }
        for (int i = 0; i < fetch.size(); i += 50) {
            List<String> batch = fetch.subList(i, Math.min(fetch.size(), i + 50));
            JsonNode entities = client.entities(batch, "claims");
            for (String q : batch) {
                EntityInfo info = EntityInfo.parse(entities.path(q));
                if (info.latitude() != null && GeoPlausibility.insideIndia(info.latitude(), info.longitude())) {
                    out.put(q, new double[] {info.latitude(), info.longitude()});
                }
            }
        }
        return out;
    }

    private Map<String, String> labels(List<String> ids) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < ids.size(); i += 50) {
            List<String> batch = ids.subList(i, Math.min(ids.size(), i + 50));
            JsonNode entities = client.entities(batch, "labels");
            for (String id : batch) {
                String label = entities.path(id).path("labels").path("en").path("value").asString(null);
                if (label != null) {
                    out.put(id, label);
                }
            }
        }
        return out;
    }

    /** The preferred-rank, still-current item value of a property, or the first current one. */
    static String preferredItem(JsonNode statements) {
        String first = null;
        for (JsonNode st : statements) {
            String rank = st.path("rank").asString();
            if ("deprecated".equals(rank) || st.path("qualifiers").has("P582")) {
                continue;
            }
            String id = st.path("mainsnak").path("datavalue").path("value").path("id").asString(null);
            if (id == null) {
                continue;
            }
            if ("preferred".equals(rank)) {
                return id;
            }
            if (first == null) {
                first = id;
            }
        }
        return first;
    }

    private void heartbeat(UUID jobId) {
        if (jobId != null) {
            jobs.heartbeat(jobId);
        }
    }
}
