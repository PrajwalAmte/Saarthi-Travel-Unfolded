package io.destinationintel.ingestion.transport;

import io.destinationintel.config.AppProperties;
import io.destinationintel.ingestion.GuideWriter;
import io.destinationintel.ingestion.GuideWriter.EvidenceUpsert;
import io.destinationintel.ingestion.GuideWriter.FactUpsert;
import io.destinationintel.ingestion.HttpFetcher;
import io.destinationintel.ingestion.JobHandler;
import io.destinationintel.ingestion.JobRepository.JobRow;
import io.destinationintel.source.SourceRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Job "nearest_transport": for guided destinations, the nearest airport with scheduled flights (OurAirports, public
 * domain) and the nearest railway station on OpenStreetMap, stored as computed "getting there" facts that link to
 * the record they came from. Distances are straight-line and say so. The airport list is re-downloaded at most once
 * per refresh interval; everything else is a database lookup, so the planner can queue this every run.
 */
@Component
public class NearestTransportHandler implements JobHandler {

    static final String AIRPORTS = "ourairports";
    /** Destinations handled per job, so one run never holds the worker for long. */
    static final int BATCH = 300;
    /** A station further than this is not "the way in by train" and is not stated. */
    static final double MAX_STATION_KM = 60;

    private final HttpFetcher http;
    private final JdbcClient jdbc;
    private final GuideWriter writer;
    private final SourceRepository sources;
    private final TransactionTemplate tx;
    private final AppProperties props;

    public NearestTransportHandler(HttpFetcher http, JdbcClient jdbc, GuideWriter writer, SourceRepository sources,
                                   TransactionTemplate tx, AppProperties props) {
        this.http = http;
        this.jdbc = jdbc;
        this.writer = writer;
        this.sources = sources;
        this.tx = tx;
        this.props = props;
    }

    @Override
    public String type() {
        return "nearest_transport";
    }

    @Override
    public Map<String, Object> run(JobRow job) {
        if (!sources.isEnabled(AIRPORTS)) {
            throw new PermanentFailure("The OurAirports source is disabled in the source registry");
        }
        int interval = refreshDays();
        int loaded = airportsDue(interval) ? loadAirports() : -1;
        List<UUID> due = due(interval);
        int airports = 0;
        int stations = 0;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        LocalDate today = now.toLocalDate();
        for (UUID place : due) {
            Integer[] written = tx.execute(s -> {
                int a = nearestAirport(place, today, now) ? 1 : 0;
                int st = nearestStation(place, today, now) ? 1 : 0;
                jdbc.sql("""
                                INSERT INTO place_source_sync (place_id, source_id, external_id, last_revision, last_synced_at, last_status)
                                VALUES (:p, 'ourairports', 'nearest', :rev, now(), 'succeeded')
                                ON CONFLICT (place_id, source_id, external_id) DO UPDATE SET
                                    last_revision = EXCLUDED.last_revision, last_synced_at = now(), last_status = 'succeeded'""")
                        .param("p", place).param("rev", today.toString()).update();
                return new Integer[] {a, st};
            });
            airports += written[0];
            stations += written[1];
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("airportsLoaded", loaded < 0 ? "unchanged" : loaded);
        stats.put("destinations", due.size());
        stats.put("withAirport", airports);
        stats.put("withStation", stations);
        stats.put("moreDue", due.size() == BATCH);
        return stats;
    }

    /** Guided destinations without nearest-transport facts, or whose facts are older than the refresh interval. */
    private List<UUID> due(int interval) {
        return jdbc.sql("""
                        SELECT p.id FROM place p
                        JOIN destination_profile dp ON dp.place_id = p.id AND dp.coverage_status <> 'indexed'
                        LEFT JOIN place_source_sync s ON s.place_id = p.id AND s.source_id = 'ourairports' AND s.external_id = 'nearest'
                        WHERE p.geog IS NOT NULL AND p.lifecycle_status = 'active'
                          AND (s.last_synced_at IS NULL OR s.last_synced_at < now() - make_interval(days => :days))
                        ORDER BY s.last_synced_at NULLS FIRST, coalesce(p.sitelink_count, 0) DESC
                        LIMIT :n""")
                .param("days", interval).param("n", BATCH).query(UUID.class).list();
    }

    private boolean nearestAirport(UUID place, LocalDate today, OffsetDateTime now) {
        return jdbc.sql("""
                        SELECT a.ident, a.name, a.iata_code, round(ST_Distance(a.geog, p.geog) / 1000)::int AS km
                        FROM place p, airport a
                        WHERE p.id = :p AND a.scheduled
                        ORDER BY a.geog <-> p.geog LIMIT 1""")
                .param("p", place)
                .query((rs, i) -> {
                    String code = rs.getString("iata_code");
                    String name = rs.getString("name") + (code == null || code.isBlank() ? "" : " (" + code + ")");
                    int km = rs.getInt("km");
                    String text = km <= 2 ? "This place has its own airport with scheduled flights: " + name + "."
                            : "Nearest airport with scheduled flights: " + name + ", about " + km + " km in a straight line.";
                    Map<String, Object> value = new HashMap<>();
                    value.put("name", rs.getString("name"));
                    value.put("iataCode", code);
                    value.put("distanceKm", km);
                    value.put("basis", "straight_line");
                    String url = "https://ourairports.com/airports/" + rs.getString("ident") + "/";
                    writer.upsertFact(new FactUpsert(place, null, "getting_there", "transport", "Nearest airport", text, value,
                                    "semi_stable", today, "computed_period", "nearest:" + place + ":airport"),
                            new EvidenceUpsert(AIRPORTS, url, null, "OurAirports: " + rs.getString("name"), null,
                                    "computed_nearest", null, null, today), now);
                    return true;
                }).optional().orElse(false);
    }

    private boolean nearestStation(UUID place, LocalDate today, OffsetDateTime now) {
        return jdbc.sql("""
                        SELECT s.name, r.external_id, round(ST_Distance(s.geog, p.geog) / 1000)::int AS km
                        FROM place p
                        JOIN LATERAL (SELECT id, name, geog FROM poi WHERE category = 'railway_station' AND status = 'active'
                                      AND ST_DWithin(geog, p.geog, :metres) ORDER BY geog <-> p.geog LIMIT 1) s ON true
                        JOIN poi_source_record r ON r.poi_id = s.id AND r.source_id = 'openstreetmap' AND r.deleted_at IS NULL
                        WHERE p.id = :p LIMIT 1""")
                .param("p", place).param("metres", MAX_STATION_KM * 1000)
                .query((rs, i) -> {
                    int km = rs.getInt("km");
                    String text = (km <= 2 ? "Railway station in town: " + rs.getString("name") + "."
                            : "Nearest railway station on the map: " + rs.getString("name") + ", about " + km
                                    + " km in a straight line.")
                            + " Small stations may see few trains; check times with Indian Railways.";
                    Map<String, Object> value = new HashMap<>();
                    value.put("name", rs.getString("name"));
                    value.put("distanceKm", km);
                    value.put("basis", "straight_line");
                    String url = "https://www.openstreetmap.org/" + rs.getString("external_id");
                    writer.upsertFact(new FactUpsert(place, null, "getting_there", "transport", "Nearest railway station", text,
                                    value, "semi_stable", today, "computed_period", "nearest:" + place + ":station"),
                            new EvidenceUpsert("openstreetmap", url, null, "OpenStreetMap: " + rs.getString("name"), null,
                                    "computed_nearest", null, null, today), now);
                    return true;
                }).optional().orElse(false);
    }

    private boolean airportsDue(int interval) {
        OffsetDateTime last = jdbc.sql("SELECT max(loaded_at) FROM airport").query(OffsetDateTime.class).optional().orElse(null);
        return last == null || last.isBefore(OffsetDateTime.now().minusDays(interval));
    }

    /** Replaces the airport table with the Indian airports in the current OurAirports export. */
    int loadAirports() {
        String csv = http.get(props.ingestion().ourAirportsUrl(), null, "text/csv");
        List<String[]> rows = new ArrayList<>();
        List<String> lines = csv.lines().toList();
        if (lines.isEmpty()) {
            throw new IllegalStateException("OurAirports returned an empty file");
        }
        List<String> header = List.of(parse(lines.get(0)));
        int ident = header.indexOf("ident");
        int type = header.indexOf("type");
        int name = header.indexOf("name");
        int lat = header.indexOf("latitude_deg");
        int lon = header.indexOf("longitude_deg");
        int country = header.indexOf("iso_country");
        int town = header.indexOf("municipality");
        int scheduled = header.indexOf("scheduled_service");
        int iata = header.indexOf("iata_code");
        if (ident < 0 || lat < 0 || scheduled < 0 || country < 0) {
            throw new PermanentFailure("OurAirports changed its file layout; airports were not updated");
        }
        for (String line : lines.subList(1, lines.size())) {
            String[] f = parse(line);
            if (f.length > scheduled && "IN".equals(f[country]) && !"closed".equals(f[type])) {
                rows.add(f);
            }
        }
        if (rows.size() < 50) {
            throw new IllegalStateException("OurAirports listed only " + rows.size() + " Indian airports; keeping the previous list");
        }
        tx.executeWithoutResult(s -> {
            jdbc.sql("DELETE FROM airport").update();
            for (String[] f : rows) {
                jdbc.sql("""
                                INSERT INTO airport (ident, name, iata_code, municipality, kind, scheduled, latitude, longitude)
                                VALUES (:ident, :name, nullif(:iata, ''), nullif(:town, ''), :kind, :scheduled, :lat, :lon)
                                ON CONFLICT (ident) DO NOTHING""")
                        .param("ident", f[ident]).param("name", f[name]).param("iata", f[iata]).param("town", f[town])
                        .param("kind", f[type]).param("scheduled", "yes".equals(f[scheduled]))
                        .param("lat", Double.parseDouble(f[lat])).param("lon", Double.parseDouble(f[lon])).update();
            }
        });
        return rows.size();
    }

    /** One CSV record (RFC 4180 quoting; OurAirports never puts line breaks inside fields). */
    static String[] parse(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(String[]::new);
    }

    private int refreshDays() {
        return jdbc.sql("SELECT refresh_interval_days FROM source WHERE id = :id").param("id", AIRPORTS)
                .query(Integer.class).optional().orElse(30);
    }
}
