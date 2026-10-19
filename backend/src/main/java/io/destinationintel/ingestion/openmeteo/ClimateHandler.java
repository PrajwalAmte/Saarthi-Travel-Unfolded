package io.destinationintel.ingestion.openmeteo;

import io.destinationintel.config.AppProperties;
import io.destinationintel.guide.CoverageService;
import io.destinationintel.ingestion.GuideWriter;
import io.destinationintel.ingestion.GuideWriter.EvidenceUpsert;
import io.destinationintel.ingestion.GuideWriter.FactUpsert;
import io.destinationintel.ingestion.HttpFetcher;
import io.destinationintel.ingestion.JobHandler;
import io.destinationintel.ingestion.JobRepository.JobRow;
import io.destinationintel.ingestion.openmeteo.ClimateAggregator.Climate;
import io.destinationintel.ingestion.openmeteo.ClimateAggregator.Daily;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Job "climate": monthly climate averages for a destination from the Open-Meteo historical
 * weather API (ERA5 reanalysis, CC BY 4.0). Only the derived monthly aggregates are stored.
 */
@Component
public class ClimateHandler implements JobHandler {

    static final String SOURCE_ID = "open-meteo";

    private final HttpFetcher http;
    private final JsonMapper mapper;
    private final PlaceRepository places;
    private final GuideWriter writer;
    private final SourceRepository sources;
    private final CoverageService coverage;
    private final TransactionTemplate tx;
    private final JdbcClient jdbc;
    private final AppProperties props;
    private final io.destinationintel.ingestion.run.SyncRunRecorder recorder;
    private final io.destinationintel.quality.QualityService quality;

    public ClimateHandler(HttpFetcher http, JsonMapper mapper, PlaceRepository places, GuideWriter writer,
                          SourceRepository sources, CoverageService coverage, TransactionTemplate tx, JdbcClient jdbc,
                          AppProperties props, io.destinationintel.ingestion.run.SyncRunRecorder recorder, io.destinationintel.quality.QualityService quality) {
        this.http = http;
        this.mapper = mapper;
        this.places = places;
        this.writer = writer;
        this.sources = sources;
        this.coverage = coverage;
        this.tx = tx;
        this.jdbc = jdbc;
        this.props = props;
        this.recorder = recorder;
        this.quality = quality;
    }

    @Override
    public String type() {
        return "climate";
    }

    @Override
    public Map<String, Object> run(JobRow job) {
        if (!sources.isEnabled(SOURCE_ID)) {
            throw new PermanentFailure("The Open-Meteo source is disabled in the source registry");
        }
        PlaceRow place = places.findById(job.targetPlaceId())
                .orElseThrow(() -> new PermanentFailure("Target destination no longer exists"));
        if (!place.hasCoordinates()) {
            throw new PermanentFailure(place.name() + " has no coordinates, so climate data cannot be looked up");
        }
        int endYear = LocalDate.now(ZoneOffset.UTC).getYear() - 1;
        int startYear = endYear - Math.max(1, props.ingestion().climateYears()) + 1;
        Map<String, String> q = new LinkedHashMap<>();
        q.put("latitude", String.format(Locale.ROOT, "%.4f", place.latitude()));
        q.put("longitude", String.format(Locale.ROOT, "%.4f", place.longitude()));
        q.put("start_date", startYear + "-01-01");
        q.put("end_date", endYear + "-12-31");
        q.put("daily", "temperature_2m_max,temperature_2m_min,precipitation_sum");
        q.put("timezone", "Asia/Kolkata");
        String base = props.ingestion().openMeteoArchiveUrl();
        String requestUrl = base + "?" + HttpFetcher.encode(q);

        io.destinationintel.ingestion.run.SyncRunRecorder.Run run = recorder.start(SOURCE_ID, job.id(), "place:" + place.slug());
        try {
            JsonNode root = mapper.readTree(http.get(base, q, "application/json"));
            run.fetched.incrementAndGet();
            if (root.path("error").asBoolean(false)) {
                throw new PermanentFailure("Open-Meteo rejected the request: " + root.path("reason").asString());
            }
            Climate climate = ClimateAggregator.aggregate(parse(root.path("daily")));
            run.parsed.incrementAndGet();
            run.datasetVersion("era5:" + climate.startYear() + "-" + climate.endYear());
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("period", climate.startYear() + "–" + climate.endYear());
            value.put("dataset", "ERA5 reanalysis via Open-Meteo");
            value.put("gridLatitude", root.path("latitude").asDouble());
            value.put("gridLongitude", root.path("longitude").asDouble());
            value.put("elevationM", root.path("elevation").asDouble());
            value.put("months", climate.months().stream().map(ClimateAggregator.MonthStats::toMap).toList());
            LocalDate observed = LocalDate.of(climate.endYear(), 12, 31);
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            FactUpsert fact = new FactUpsert(place.id(), null, "practical", "climate",
                    "Climate averages " + climate.startYear() + "–" + climate.endYear(), climate.summary(), value,
                    "stable", observed, "computed_period", "openmeteo:" + place.id() + ":climate");
            EvidenceUpsert ev = new EvidenceUpsert(SOURCE_ID, requestUrl, null,
                    "Open-Meteo Historical Weather API (ERA5)", null, "open_meteo_monthly_aggregation", null, null, observed);
            GuideWriter.Outcome outcome = tx.execute(s -> {
                GuideWriter.Outcome o = writer.upsertFact(fact, ev, now);
                jdbc.sql("""
                                INSERT INTO place_source_sync (place_id, source_id, external_id, last_revision, last_synced_at, last_status)
                                VALUES (:p, 'open-meteo', 'climate', :rev, now(), 'succeeded')
                                ON CONFLICT (place_id, source_id, external_id) DO UPDATE SET
                                    last_revision = EXCLUDED.last_revision, last_synced_at = now(), last_status = 'succeeded'
                                """)
                        .param("p", place.id()).param("rev", climate.startYear() + "-" + climate.endYear()).update();
                return o;
            });
            coverage.refresh(place.id());
            switch (outcome == null ? GuideWriter.Outcome.UNCHANGED : outcome) {
                case CREATED -> run.inserted.incrementAndGet();
                case UPDATED -> run.updated.incrementAndGet();
                default -> run.unchanged.incrementAndGet();
            }
            recorder.stage(run, "quality");
            String qualityStatus = quality.evaluate(SOURCE_ID, run.id, run.scope).status();
            run.published.set(1);
            recorder.markPublished(SOURCE_ID);
            recorder.finish(run, "failed".equals(qualityStatus), true);
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("quality", qualityStatus);
            stats.put("period", value.get("period"));
            stats.put("outcome", outcome == null ? null : outcome.name().toLowerCase(Locale.ROOT));
            return stats;
        } catch (RuntimeException e) {
            recorder.fail(run, e.getMessage());
            throw e;
        }
    }

    static List<Daily> parse(JsonNode daily) {
        List<Daily> out = new ArrayList<>();
        JsonNode time = daily.path("time");
        JsonNode max = daily.path("temperature_2m_max");
        JsonNode min = daily.path("temperature_2m_min");
        JsonNode pr = daily.path("precipitation_sum");
        for (int i = 0; i < time.size(); i++) {
            out.add(new Daily(LocalDate.parse(time.get(i).asString()), num(max.get(i)), num(min.get(i)), num(pr.get(i))));
        }
        return out;
    }

    private static Double num(JsonNode n) {
        return n == null || n.isNull() || !n.isNumber() ? null : n.asDouble();
    }
}
