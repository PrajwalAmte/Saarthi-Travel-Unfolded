package io.destinationintel.trips;

import io.destinationintel.common.ApiException;
import io.destinationintel.common.Json;
import io.destinationintel.config.AppProperties;
import io.destinationintel.ingestion.wikivoyage.WikiText;
import io.destinationintel.place.PlaceRepository;
import io.destinationintel.place.PlaceRow;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * "Weekend trips from X", assembled only from stored, sourced data: road distance and drive time (OSRM over
 * OpenStreetMap, straight-line distance until a route is computed), guide depth, what each
 * place is known for and how to get there (quoted from its sources), and the months its 10-year weather averages make
 * comfortable. Nothing is written by a model; the page updates itself as guides are added.
 */
@Service
public class WeekendTripsService {

    /** Closer than this is a day out, not a weekend trip. */
    static final double MIN_KM = 30;
    static final int MAX_TRIPS = 80;
    /** A month is comfortable when its average daily high is at most this and it has at most MAX_RAINY_DAYS. */
    static final double COMFORTABLE_MAX_C = 32;
    static final double MAX_RAINY_DAYS = 6;
    /** Drive times are not shown when the router had to move a point further than this to reach a road. */
    static final int MAX_SNAP_M = 5000;
    /** Drive-time bands, in minutes. */
    static final int[][] HOURS_BANDS = {{0, 180}, {180, 300}, {300, Integer.MAX_VALUE}};

    public record Source(String name, String url) {}

    public record Trip(String slug, String name, String kind, String state, int distanceKm, Integer roadKm,
                       Integer driveMinutes, String coverageStatus,
                       int placesToEat, int placesToStay, int thingsToDo, String knownFor, Source knownForSource,
                       String gettingThere, Source gettingThereSource, Hub airport, Hub station,
                       List<Integer> comfortableMonths, String climatePeriod,
                       String imageUrl, String imageCredit) {}

    /** Nearest airport with scheduled flights or railway station, straight-line km (OurAirports / OpenStreetMap). */
    public record Hub(String name, String code, int distanceKm) {}

    public record Band(String key, String label, List<Trip> trips) {}

    /** {@code bandsBy} is "drive_time" once routes exist for the origin, otherwise "distance". */
    public record WeekendTrips(String originSlug, String originName, int maxKm, int total, OffsetDateTime generatedAt,
                               String bandsBy, OffsetDateTime routesComputedAt, List<Band> bands, List<String> method) {}

    public record Origin(String slug, String name, int radiusKm) {}

    private final JdbcClient jdbc;
    private final PlaceRepository places;
    private final Json json;
    private final AppProperties props;

    public WeekendTripsService(JdbcClient jdbc, PlaceRepository places, Json json, AppProperties props) {
        this.jdbc = jdbc;
        this.places = places;
        this.json = json;
        this.props = props;
    }

    /** Cities with a weekend-trips page: the configured focus areas. */
    public List<Origin> origins() {
        List<Origin> out = new ArrayList<>();
        for (String spec : props.ingestion().focus()) {
            String[] f = spec.split(":");
            int km;
            try {
                km = f.length > 1 ? (int) Double.parseDouble(f[1].trim()) : 300;
            } catch (NumberFormatException e) {
                continue;
            }
            places.findBySlug(f[0].trim()).ifPresent(p -> out.add(new Origin(p.slug(), p.name(), km)));
        }
        return out;
    }

    @Cacheable(cacheNames = "destination-detail", key = "'weekend:' + #slug + ':' + #maxKm")
    public WeekendTrips weekendTrips(String slug, int maxKm) {
        PlaceRow origin = places.findBySlug(slug).orElseThrow(() -> ApiException.notFound("Destination"));
        if (origin.latitude() == null || origin.longitude() == null) {
            throw ApiException.notFound("Destination");
        }
        int radius = Math.max(50, Math.min(maxKm, 500));
        List<Trip> trips = jdbc.sql("""
                        WITH o AS (SELECT geog FROM place WHERE id = :origin)
                        SELECT p.id, p.slug, p.name, p.kind, dp.coverage_status,
                               round(ST_Distance(p.geog, o.geog) / 1000)::int AS distance_km,
                               CASE WHEN rr.snap_m <= :maxSnap THEN round(rr.distance_m / 1000.0)::int END AS road_km,
                               CASE WHEN rr.snap_m <= :maxSnap THEN round(rr.duration_s / 60.0)::int END AS drive_minutes,
                               (SELECT s.name FROM place_relation r JOIN place s ON s.id = r.parent_id
                                 WHERE r.child_id = p.id AND r.relation = 'in_state' LIMIT 1) AS state,
                               (SELECT count(DISTINCT (e.section, e.title_normalized)) FROM guide_entry e WHERE e.destination_id = p.id
                                 AND e.status = 'published' AND e.section IN ('eat', 'drink')) AS eat,
                               (SELECT count(DISTINCT (e.section, e.title_normalized)) FROM guide_entry e WHERE e.destination_id = p.id
                                 AND e.status = 'published' AND e.section = 'stay') AS stay,
                               (SELECT count(DISTINCT (e.section, e.title_normalized)) FROM guide_entry e WHERE e.destination_id = p.id
                                 AND e.status = 'published' AND e.section IN ('see', 'do', 'buy')) AS explore,
                               img.image_url, CASE WHEN img.image_url IS NULL THEN NULL
                                    ELSE concat_ws(', ', nullif(img.artist, ''), img.licence_name) END AS image_credit
                        FROM place p CROSS JOIN o
                        JOIN destination_profile dp ON dp.place_id = p.id AND dp.coverage_status <> 'indexed'
                        LEFT JOIN place_image img ON img.place_id = p.id
                        LEFT JOIN road_route rr ON rr.origin_id = :origin AND rr.destination_id = p.id
                        WHERE p.is_destination AND p.lifecycle_status = 'active' AND p.geog IS NOT NULL AND p.id <> :origin
                          AND p.kind IN ('city', 'town', 'village', 'park', 'island', 'attraction')
                          AND ST_DWithin(p.geog, o.geog, :metres) AND ST_Distance(p.geog, o.geog) > :min
                        ORDER BY distance_km
                        LIMIT :limit""")
                .param("origin", origin.id()).param("metres", radius * 1000.0).param("min", MIN_KM * 1000)
                .param("limit", MAX_TRIPS).param("maxSnap", MAX_SNAP_M)
                .query((rs, i) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    FactWithSource summary = fact(id, "kind = 'summary'");
                    // The guide's own words, not the computed nearest-airport and station lines.
                    FactWithSource there = fact(id, "section = 'getting_there' AND coalesce(f.ingest_key, '') NOT LIKE 'nearest:%'");
                    Map<String, Object> climate = climate(id);
                    return new Trip(rs.getString("slug"), rs.getString("name"), rs.getString("kind"), rs.getString("state"),
                            rs.getInt("distance_km"), rs.getObject("road_km", Integer.class),
                            rs.getObject("drive_minutes", Integer.class), rs.getString("coverage_status"), rs.getInt("eat"), rs.getInt("stay"),
                            rs.getInt("explore"), summary == null ? null : clip(summary.text(), 240),
                            summary == null ? null : summary.source(), there == null ? null : clip(there.text(), 240),
                            there == null ? null : there.source(), hub(id, "airport"), hub(id, "station"), comfortableMonths(climate),
                            climate == null ? null : String.valueOf(climate.getOrDefault("period", "")),
                            rs.getString("image_url"), rs.getString("image_credit"));
                }).list();

        OffsetDateTime routesAt = jdbc.sql("SELECT max(computed_at) FROM road_route WHERE origin_id = :o AND distance_m IS NOT NULL")
                .param("o", origin.id()).query(OffsetDateTime.class).optional().orElse(null);
        boolean byTime = trips.stream().anyMatch(t -> t.driveMinutes() != null);
        List<Band> bands = byTime ? driveTimeBands(trips) : distanceBands(trips, radius);
        List<String> method = new ArrayList<>();
        method.add(byTime
                ? "Drive times and road distances are computed by OSRM from OpenStreetMap roads (© OpenStreetMap contributors) "
                        + "for a car at typical speeds, without traffic, stops or breaks, so allow extra time. Spot a wrong road? "
                        + "It can be fixed at openstreetmap.org/fixthemap."
                : "Distances are in a straight line from the centre of " + origin.name() + ", not by road; road journeys are longer.");
        method.add("Places listed are those with a guide on Saarthi within " + radius + " km in a straight line; more are added as guides are built.");
        method.add("\"Known for\" and \"Getting there\" are quoted from each place's guide, with the source linked.");
        method.add("Comfortable months are computed from 10-year weather averages (Open-Meteo): an average daily high of at most "
                + (int) COMFORTABLE_MAX_C + "°C and at most " + (int) MAX_RAINY_DAYS + " rainy days.");
        return new WeekendTrips(origin.slug(), origin.name(), radius, trips.size(), OffsetDateTime.now(),
                byTime ? "drive_time" : "distance", routesAt, bands, List.copyOf(method));
    }

    /** Bands by drive time, nearest first; places the router could not reach come last, by straight-line distance. */
    static List<Band> driveTimeBands(List<Trip> trips) {
        List<Band> bands = new ArrayList<>();
        for (int[] b : HOURS_BANDS) {
            List<Trip> in = trips.stream()
                    .filter(t -> t.driveMinutes() != null && t.driveMinutes() > b[0] && t.driveMinutes() <= b[1])
                    .sorted(java.util.Comparator.comparing(Trip::driveMinutes)).toList();
            if (!in.isEmpty()) {
                String label = b[1] == Integer.MAX_VALUE ? "Over " + b[0] / 60 + " hours by road"
                        : b[0] == 0 ? "Up to " + b[1] / 60 + " hours by road" : b[0] / 60 + " to " + b[1] / 60 + " hours by road";
                bands.add(new Band("hours-" + b[0] / 60, label, in));
            }
        }
        List<Trip> unrouted = trips.stream().filter(t -> t.driveMinutes() == null).toList();
        if (!unrouted.isEmpty()) {
            bands.add(new Band("no-route", "Drive time not known yet", unrouted));
        }
        return bands;
    }

    static List<Band> distanceBands(List<Trip> trips, int radius) {
        List<Band> bands = new ArrayList<>();
        int[][] edges = {{(int) MIN_KM, 150}, {150, 250}, {250, radius}};
        for (int[] e : edges) {
            if (e[0] >= radius) {
                continue;
            }
            int to = Math.min(e[1], radius);
            List<Trip> in = trips.stream().filter(t -> t.distanceKm() > e[0] && t.distanceKm() <= to).toList();
            if (!in.isEmpty()) {
                bands.add(new Band("km-" + to, "Within " + to + " km", in));
            }
        }
        return bands;
    }

    record FactWithSource(String text, Source source) {}

    private FactWithSource fact(UUID placeId, String where) {
        return jdbc.sql("""
                        SELECT f.value_text AS text, s.name AS source_name, coalesce(e.permalink, e.url) AS source_url
                        FROM fact f
                        LEFT JOIN evidence e ON e.fact_id = f.id
                        LEFT JOIN source s ON s.id = e.source_id
                        WHERE f.destination_id = :p AND f.entry_id IS NULL AND f.status = 'published'""" + " AND " + where + """
                         ORDER BY f.created_at LIMIT 1""")
                .param("p", placeId)
                .query((rs, i) -> new FactWithSource(rs.getString("text"),
                        rs.getString("source_name") == null ? null : new Source(rs.getString("source_name"), rs.getString("source_url"))))
                .optional().orElse(null);
    }

    private Hub hub(UUID placeId, String which) {
        return jdbc.sql("SELECT value_json::text FROM fact WHERE ingest_key = :k AND status = 'published'")
                .param("k", "nearest:" + placeId + ":" + which).query(String.class).optional().map(json::map)
                .filter(m -> m.get("name") != null && m.get("distanceKm") instanceof Number)
                .map(m -> new Hub(String.valueOf(m.get("name")), m.get("iataCode") == null ? null : String.valueOf(m.get("iataCode")),
                        ((Number) m.get("distanceKm")).intValue()))
                .orElse(null);
    }

    private Map<String, Object> climate(UUID placeId) {
        return jdbc.sql("SELECT value_json::text FROM fact WHERE destination_id = :p AND kind = 'climate' AND status = 'published' LIMIT 1")
                .param("p", placeId).query(String.class).optional().map(json::map).orElse(null);
    }

    /** Months (1-12) whose 10-year averages are mild and mostly dry, by the rule stated on the page. */
    static List<Integer> comfortableMonths(Map<String, Object> climate) {
        List<Integer> out = new ArrayList<>();
        if (climate == null || !(climate.get("months") instanceof List<?> months)) {
            return out;
        }
        for (Object o : months) {
            if (o instanceof Map<?, ?> m && m.get("month") instanceof Number month && m.get("meanMaxC") instanceof Number max) {
                double rainy = m.get("rainyDays") instanceof Number r ? r.doubleValue() : 0;
                if (max.doubleValue() <= COMFORTABLE_MAX_C && rainy <= MAX_RAINY_DAYS) {
                    out.add(month.intValue());
                }
            }
        }
        return out;
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : WikiText.truncateAtSentence(flat, max);
    }
}
