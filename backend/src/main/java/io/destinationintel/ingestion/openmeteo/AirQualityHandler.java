package io.destinationintel.ingestion.openmeteo;

import io.destinationintel.common.Json;
import io.destinationintel.config.AppProperties;
import io.destinationintel.ingestion.GuideWriter;
import io.destinationintel.ingestion.GuideWriter.EvidenceUpsert;
import io.destinationintel.ingestion.GuideWriter.FactUpsert;
import io.destinationintel.ingestion.HttpFetcher;
import io.destinationintel.ingestion.JobHandler;
import io.destinationintel.ingestion.JobRepository.JobRow;
import io.destinationintel.place.PlaceRepository;
import io.destinationintel.place.PlaceRow;
import io.destinationintel.source.SourceRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Job "air_quality": the months when smog is common at a destination, from two years of the Copernicus CAMS global
 * model via the Open-Meteo air-quality API. The model gets the seasonal pattern right but reads low against CPCB
 * stations (Delhi in November is roughly half the measured level), so only a clearly polluted season is stated, with
 * that caveat, and never a number or an "air is good" claim. Downloads are shared per model grid cell.
 */
@Component
public class AirQualityHandler implements JobHandler {

    static final String SOURCE_ID = "open-meteo-air";
    /** CAMS global grid spacing, in degrees: places in the same cell get the same model values. */
    static final double CELL_DEG = 0.4;
    /** Modelled monthly mean PM2.5 (µg/m³) at or above which a month counts as smog season (NAQI "poor" starts at 91). */
    static final double SMOG_UGM3 = 90;
    static final String CPCB_LIVE = "https://airquality.cpcb.gov.in/AQI_India/";
    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    private final HttpFetcher http;
    private final JsonMapper mapper;
    private final Json json;
    private final PlaceRepository places;
    private final GuideWriter writer;
    private final SourceRepository sources;
    private final TransactionTemplate tx;
    private final JdbcClient jdbc;
    private final AppProperties props;

    public AirQualityHandler(HttpFetcher http, JsonMapper mapper, Json json, PlaceRepository places, GuideWriter writer,
                             SourceRepository sources, TransactionTemplate tx, JdbcClient jdbc, AppProperties props) {
        this.http = http;
        this.mapper = mapper;
        this.json = json;
        this.places = places;
        this.writer = writer;
        this.sources = sources;
        this.tx = tx;
        this.jdbc = jdbc;
        this.props = props;
    }

    @Override
    public String type() {
        return "air_quality";
    }

    record Cell(String key, String period, List<Double> months, String url) {}

    @Override
    public Map<String, Object> run(JobRow job) {
        if (!sources.isEnabled(SOURCE_ID)) {
            throw new PermanentFailure("The Open-Meteo air-quality source is disabled in the source registry");
        }
        PlaceRow place = places.findById(job.targetPlaceId())
                .orElseThrow(() -> new PermanentFailure("Target destination no longer exists"));
        if (!place.hasCoordinates()) {
            throw new PermanentFailure(place.name() + " has no coordinates, so air quality cannot be looked up");
        }
        double cellLat = Math.round(place.latitude() / CELL_DEG) * CELL_DEG;
        double cellLon = Math.round(place.longitude() / CELL_DEG) * CELL_DEG;
        String key = String.format(Locale.ROOT, "cams:%.1f,%.1f", cellLat, cellLon);
        boolean downloaded = false;
        Cell cell = cached(key);
        if (cell == null) {
            cell = download(key, cellLat, cellLon);
            downloaded = true;
        }
        List<Integer> smog = smogMonths(cell.months());
        LocalDate observed = LocalDate.now(ZoneOffset.UTC);
        Cell c = cell;
        tx.executeWithoutResult(s -> {
            String ingestKey = "aq:" + place.id() + ":smog";
            if (smog.isEmpty()) {
                // A season stated earlier no longer shows in the model: withdraw it rather than leave it standing.
                jdbc.sql("UPDATE fact SET status = 'superseded', updated_at = now() WHERE ingest_key = :k AND status = 'published'")
                        .param("k", ingestKey).update();
            } else {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("smogMonths", smog);
                value.put("period", c.period());
                value.put("model", "CAMS global via Open-Meteo, 0.4° grid");
                value.put("modelMonthlyPm25", c.months());
                value.put("liveAqiUrl", CPCB_LIVE);
                String text = "Air quality is usually at its worst in " + monthRanges(smog) + ", when smog is common. "
                        + "This comes from a model (Copernicus CAMS), and actual readings in these months are often higher; "
                        + "check the live AQI from CPCB before you go.";
                writer.upsertFact(new FactUpsert(place.id(), null, "tips", "health", "Smog season", text, value, "stable",
                                observed, "computed_period", ingestKey),
                        new EvidenceUpsert(SOURCE_ID, c.url(), null, "Open-Meteo Air Quality API (CAMS), " + c.period(), null,
                                "open_meteo_monthly_aggregation", null, null, observed),
                        OffsetDateTime.now(ZoneOffset.UTC));
            }
            jdbc.sql("""
                            INSERT INTO place_source_sync (place_id, source_id, external_id, last_revision, last_synced_at, last_status)
                            VALUES (:p, 'open-meteo-air', 'aq', :rev, now(), 'succeeded')
                            ON CONFLICT (place_id, source_id, external_id) DO UPDATE SET
                                last_revision = EXCLUDED.last_revision, last_synced_at = now(), last_status = 'succeeded'""")
                    .param("p", place.id()).param("rev", c.key() + "@" + c.period()).update();
        });
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("cell", key);
        stats.put("downloaded", downloaded);
        stats.put("smogMonths", smog);
        return stats;
    }

    private Cell cached(String key) {
        return jdbc.sql("""
                        SELECT cell_key, period, months::text AS months, request_url FROM air_quality_cell
                        WHERE cell_key = :k AND fetched_at > now() - make_interval(days =>
                            (SELECT refresh_interval_days FROM source WHERE id = 'open-meteo-air'))""")
                .param("k", key)
                .query((rs, i) -> new Cell(rs.getString("cell_key"), rs.getString("period"),
                        json.list(rs.getString("months")).stream().map(o -> o == null ? null : ((Number) o).doubleValue()).toList(),
                        rs.getString("request_url")))
                .optional().orElse(null);
    }

    /** Two full calendar years of hourly modelled PM2.5 at the cell centre, reduced to 12 monthly means. */
    private Cell download(String key, double lat, double lon) {
        int endYear = LocalDate.now(ZoneOffset.UTC).getYear() - 1;
        int startYear = endYear - 1;
        Map<String, String> q = new LinkedHashMap<>();
        q.put("latitude", String.format(Locale.ROOT, "%.2f", lat));
        q.put("longitude", String.format(Locale.ROOT, "%.2f", lon));
        q.put("hourly", "pm2_5");
        q.put("domains", "cams_global");
        q.put("start_date", startYear + "-01-01");
        q.put("end_date", endYear + "-12-31");
        q.put("timezone", "Asia/Kolkata");
        String base = props.ingestion().openMeteoAirQualityUrl();
        JsonNode root = mapper.readTree(http.get(base, q, "application/json"));
        if (root.path("error").asBoolean(false)) {
            throw new PermanentFailure("Open-Meteo rejected the air-quality request: " + root.path("reason").asString());
        }
        List<Double> months = monthlyMeans(root.path("hourly"));
        String period = startYear + "–" + endYear;
        String url = base + "?" + HttpFetcher.encode(q);
        jdbc.sql("""
                        INSERT INTO air_quality_cell (cell_key, period, months, request_url, fetched_at)
                        VALUES (:k, :period, CAST(:months AS jsonb), :url, now())
                        ON CONFLICT (cell_key) DO UPDATE SET period = EXCLUDED.period, months = EXCLUDED.months,
                            request_url = EXCLUDED.request_url, fetched_at = now()""")
                .param("k", key).param("period", period).param("months", json.write(months)).param("url", url).update();
        return new Cell(key, period, months, url);
    }

    /** Mean of the hourly values per calendar month (index 0 = January); null for a month with too little data. */
    static List<Double> monthlyMeans(JsonNode hourly) {
        double[] sum = new double[12];
        int[] n = new int[12];
        JsonNode time = hourly.path("time");
        JsonNode pm = hourly.path("pm2_5");
        for (int i = 0; i < time.size(); i++) {
            if (pm.path(i).isNumber()) {
                int m = Integer.parseInt(time.get(i).asString().substring(5, 7)) - 1;
                sum[m] += pm.get(i).asDouble();
                n[m]++;
            }
        }
        List<Double> out = new ArrayList<>();
        for (int m = 0; m < 12; m++) {
            // At least ten days of hourly values, or the month is unknown.
            out.add(n[m] < 240 ? null : Math.round(sum[m] / n[m] * 10) / 10.0);
        }
        return out;
    }

    static List<Integer> smogMonths(List<Double> monthlyMeans) {
        List<Integer> out = new ArrayList<>();
        for (int m = 0; m < monthlyMeans.size(); m++) {
            Double v = monthlyMeans.get(m);
            if (v != null && v >= SMOG_UGM3) {
                out.add(m + 1);
            }
        }
        return out;
    }

    /** "Nov–Jan", "Jan, Mar": consecutive months (wrapping round the year) as ranges. */
    static String monthRanges(List<Integer> months) {
        TreeSet<Integer> set = new TreeSet<>(months);
        if (set.size() == 12) {
            return "every month";
        }
        List<String> parts = new ArrayList<>();
        for (int start : set) {
            int prev = start == 1 ? 12 : start - 1;
            if (set.contains(prev)) {
                continue;
            }
            int end = start;
            while (set.contains(end == 12 ? 1 : end + 1)) {
                end = end == 12 ? 1 : end + 1;
            }
            parts.add(end == start ? MONTHS[start - 1] : MONTHS[start - 1] + "–" + MONTHS[end - 1]);
        }
        return String.join(", ", parts);
    }
}
