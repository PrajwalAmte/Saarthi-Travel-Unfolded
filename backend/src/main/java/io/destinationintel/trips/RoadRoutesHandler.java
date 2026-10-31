package io.destinationintel.trips;

import io.destinationintel.config.AppProperties;
import io.destinationintel.ingestion.HttpFetcher;
import io.destinationintel.ingestion.JobHandler;
import io.destinationintel.ingestion.JobRepository.JobRow;
import io.destinationintel.place.PlaceRepository;
import io.destinationintel.place.PlaceRow;
import io.destinationintel.source.SourceRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Job "road_routes": car distance and drive time from a weekend-trips origin to each guided place around it, from the
 * OSRM table service over OpenStreetMap roads. Only places without a route, or whose route is older than the source's
 * refresh interval, are asked for, so the planner can queue this every run and it costs nothing once the area is done.
 */
@Component
public class RoadRoutesHandler implements JobHandler {

    static final String SOURCE_ID = "osrm";
    /** Places per table request (the public OSRM server accepts up to 100 coordinates, origin included). */
    static final int BATCH = 90;

    private final HttpFetcher http;
    private final JsonMapper mapper;
    private final PlaceRepository places;
    private final SourceRepository sources;
    private final JdbcClient jdbc;
    private final AppProperties props;

    public RoadRoutesHandler(HttpFetcher http, JsonMapper mapper, PlaceRepository places, SourceRepository sources,
                             JdbcClient jdbc, AppProperties props) {
        this.http = http;
        this.mapper = mapper;
        this.places = places;
        this.sources = sources;
        this.jdbc = jdbc;
        this.props = props;
    }

    @Override
    public String type() {
        return "road_routes";
    }

    record Target(UUID id, double lat, double lon) {}

    @Override
    public Map<String, Object> run(JobRow job) {
        if (!sources.isEnabled(SOURCE_ID)) {
            throw new PermanentFailure("The OSRM routing source is disabled in the source registry");
        }
        PlaceRow origin = places.findById(job.targetPlaceId())
                .orElseThrow(() -> new PermanentFailure("Origin destination no longer exists"));
        if (!origin.hasCoordinates()) {
            throw new PermanentFailure(origin.name() + " has no coordinates, so routes cannot be computed");
        }
        JsonNode params = job.params() == null ? mapper.createObjectNode() : mapper.readTree(job.params());
        double radiusKm = params.path("radiusKm").asDouble(350);
        List<Target> due = jdbc.sql("""
                        WITH o AS (SELECT geog FROM place WHERE id = :origin)
                        SELECT p.id, ST_Y(p.geog::geometry) AS lat, ST_X(p.geog::geometry) AS lon
                        FROM place p CROSS JOIN o
                        JOIN destination_profile dp ON dp.place_id = p.id AND dp.coverage_status <> 'indexed'
                        LEFT JOIN road_route r ON r.origin_id = :origin AND r.destination_id = p.id
                        WHERE p.is_destination AND p.lifecycle_status = 'active' AND p.geog IS NOT NULL AND p.id <> :origin
                          AND p.kind IN ('city', 'town', 'village', 'park', 'island', 'attraction')
                          AND ST_DWithin(p.geog, o.geog, :metres) AND ST_Distance(p.geog, o.geog) > :min
                          AND (r.computed_at IS NULL OR r.computed_at < now() - make_interval(days => :days))
                        ORDER BY ST_Distance(p.geog, o.geog)""")
                .param("origin", origin.id()).param("metres", radiusKm * 1000).param("min", WeekendTripsService.MIN_KM * 1000)
                .param("days", refreshDays())
                .query((rs, i) -> new Target(rs.getObject("id", UUID.class), rs.getDouble("lat"), rs.getDouble("lon"))).list();

        int routed = 0;
        int noRoute = 0;
        String base = props.ingestion().osrmUrl().replaceAll("/+$", "");
        List<List<Target>> batches = batches(due);
        for (List<Target> batch : batches) {
            String coords = coord(origin.latitude(), origin.longitude()) + ";"
                    + batch.stream().map(t -> coord(t.lat(), t.lon())).collect(Collectors.joining(";"));
            Map<String, String> q = new LinkedHashMap<>();
            q.put("sources", "0");
            q.put("annotations", "duration,distance");
            JsonNode root = mapper.readTree(http.get(base + "/table/v1/driving/" + coords, q, "application/json"));
            if (!"Ok".equals(root.path("code").asString())) {
                throw new IllegalStateException("OSRM answered " + root.path("code").asString() + ": " + root.path("message").asString());
            }
            double originSnap = root.path("sources").path(0).path("distance").asDouble(0);
            for (int i = 0; i < batch.size(); i++) {
                Route r = route(root, i + 1, originSnap);
                if (r.distanceM() == null) {
                    noRoute++;
                } else {
                    routed++;
                }
                jdbc.sql("""
                                INSERT INTO road_route (origin_id, destination_id, distance_m, duration_s, snap_m, provider, computed_at)
                                VALUES (:o, :d, :dist, :dur, :snap, :provider, now())
                                ON CONFLICT (origin_id, destination_id) DO UPDATE SET distance_m = EXCLUDED.distance_m,
                                    duration_s = EXCLUDED.duration_s, snap_m = EXCLUDED.snap_m, provider = EXCLUDED.provider,
                                    computed_at = now()""")
                        .param("o", origin.id()).param("d", batch.get(i).id()).param("dist", r.distanceM())
                        .param("dur", r.durationS()).param("snap", r.snapM()).param("provider", base).update();
            }
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("origin", origin.slug());
        stats.put("requests", batches.size());
        stats.put("routed", routed);
        stats.put("noRoute", noRoute);
        return stats;
    }

    record Route(Integer distanceM, Integer durationS, int snapM) {}

    /** The route to destination column {@code col} of a table response; no route when either matrix cell is null. */
    static Route route(JsonNode root, int col, double originSnapM) {
        JsonNode dur = root.path("durations").path(0).path(col);
        JsonNode dist = root.path("distances").path(0).path(col);
        int snap = (int) Math.round(Math.max(originSnapM, root.path("destinations").path(col).path("distance").asDouble(0)));
        if (!dur.isNumber() || !dist.isNumber()) {
            return new Route(null, null, snap);
        }
        return new Route((int) Math.round(dist.asDouble()), (int) Math.round(dur.asDouble()), snap);
    }

    private int refreshDays() {
        return jdbc.sql("SELECT refresh_interval_days FROM source WHERE id = :id").param("id", SOURCE_ID)
                .query(Integer.class).optional().orElse(90);
    }

    private static String coord(double lat, double lon) {
        return String.format(Locale.ROOT, "%.5f,%.5f", lon, lat);
    }

    /** Splits the places into table requests the router accepts. */
    static <T> List<List<T>> batches(List<T> items) {
        List<List<T>> out = new ArrayList<>();
        for (int from = 0; from < items.size(); from += BATCH) {
            out.add(items.subList(from, Math.min(items.size(), from + BATCH)));
        }
        return out;
    }
}
