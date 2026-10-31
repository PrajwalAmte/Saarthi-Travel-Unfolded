package io.destinationintel.ingestion;

import io.destinationintel.config.AppProperties;
import io.destinationintel.source.SourceRepository;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Plans ingestion work. Bootstrap indexes the configured launch destinations first (via the
 * Action API) and then the nationwide index; the periodic planner re-enqueues refreshes per
 * each source's refresh interval. Enqueueing is idempotent, so the planner can run often.
 */
@Component
public class IngestionScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestionScheduler.class);

    private final JobRepository jobs;
    private final SourceRepository sources;
    private final JdbcClient jdbc;
    private final AppProperties.Ingestion config;

    public IngestionScheduler(JobRepository jobs, SourceRepository sources, JdbcClient jdbc, AppProperties props) {
        this.jobs = jobs;
        this.sources = sources;
        this.jdbc = jdbc;
        this.config = props.ingestion();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        if (!config.enabled() || !config.bootstrapOnStartup()) {
            log.info("Ingestion bootstrap skipped (enabled={}, bootstrapOnStartup={})", config.enabled(), config.bootstrapOnStartup());
            return;
        }
        try {
            Map<String, Object> plan = enqueueBootstrap("bootstrap");
            log.info("Ingestion bootstrap planned: {}", plan);
        } catch (RuntimeException e) {
            // Planning failures must not stop the API from serving; the six-hourly planner tries again.
            log.error("Ingestion bootstrap could not be planned; the API keeps running and the scheduled planner will retry", e);
        }
    }

    /** Enqueues the launch destinations and the nationwide index if they have not been ingested yet. */
    public Map<String, Object> enqueueBootstrap(String createdBy) {
        Map<String, Object> plan = new LinkedHashMap<>();
        List<String> seeds = config.seedGuides().stream().filter(q -> q.matches("^Q[0-9]+$")).toList();
        List<String> missingSeeds = seeds.stream()
                .filter(q -> !jdbc.sql("""
                                SELECT EXISTS (SELECT 1 FROM place p JOIN place_source_sync s ON s.place_id = p.id
                                               WHERE p.wikidata_id = :q AND s.source_id = 'wikivoyage-en')
                                """).param("q", q).query(Boolean.class).single())
                .toList();
        if (!missingSeeds.isEmpty() && config.runs("wikidata_index")) {
            jobs.enqueue("wikidata_index", "wikidata", null, Map.of("qids", missingSeeds),
                    "wikidata_index:seeds:" + String.join(",", missingSeeds), 30, createdBy);
            for (String q : missingSeeds) {
                jobs.enqueue("wikivoyage_guide", "wikivoyage-en", null, Map.of("wikidataId", q),
                        "wikivoyage_guide:qid:" + q, 20, createdBy);
            }
        }
        plan.put("seedGuides", missingSeeds);
        boolean indexed = jobs.lastSuccess("wikidata_index", null).isPresent()
                && jdbc.sql("SELECT count(*) FROM place").query(Long.class).single() > 100;
        if (!indexed && config.runs("wikidata_index")) {
            jobs.enqueue("wikidata_index", "wikidata", null, Map.of(), "wikidata_index:all", 5, createdBy);
            plan.put("nationwideIndex", "queued");
        }
        return plan;
    }

    @Scheduled(cron = "0 15 */6 * * *")
    public void plan() {
        if (!config.enabled()) {
            return;
        }
        Map<String, Object> plan = planRefreshes("scheduler");
        log.info("Ingestion refresh planned: {}", plan);
    }

    public Map<String, Object> planRefreshes(String createdBy) {
        Map<String, Object> plan = new LinkedHashMap<>();
        sources.find("wikidata").filter(s -> config.runs("wikidata_index")).ifPresent(s -> {
            OffsetDateTime last = jobs.lastSuccess("wikidata_index", null).orElse(null);
            if (s.enabled() && (last == null || last.isBefore(OffsetDateTime.now().minusDays(s.refreshIntervalDays())))) {
                jobs.enqueue("wikidata_index", "wikidata", null, Map.of(), "wikidata_index:all", 5, createdBy);
                plan.put("wikidata", "queued");
            }
        });

        // Release-based map sources. Each job compares the release with the last imported one and
        // does nothing when it has not changed, so planning by interval is safe.
        for (String[] r : new String[][] {{"openstreetmap", "osm_extract"}, {"overture-places", "overture_places"},
                {"asi-heritage", "heritage_monuments"}}) {
            if (!config.runs(r[1])) {
                plan.put(r[0], "not run on this instance");
                continue;
            }
            sources.find(r[0]).ifPresent(s -> {
                OffsetDateTime last = jobs.lastSuccess(r[1], null).orElse(null);
                if (s.enabled() && (last == null || last.isBefore(OffsetDateTime.now().minusDays(s.refreshIntervalDays())))) {
                    jobs.enqueue(r[1], r[0], null, Map.of(), r[1] + ":all", 3, createdBy);
                    plan.put(r[0], "queued");
                }
            });
        }

        int wv = 0;
        int interval = !config.runs("wikivoyage_guide") ? -1 : sources.find("wikivoyage-en").map(s -> s.enabled() ? s.refreshIntervalDays() : -1).orElse(-1);
        if (interval > 0) {
            List<UUID> due = jdbc.sql("""
                            SELECT DISTINCT s.place_id FROM place_source_sync s
                            JOIN place p ON p.id = s.place_id
                            LEFT JOIN destination_profile dp ON dp.place_id = p.id
                            WHERE s.source_id = 'wikivoyage-en' AND s.external_id = p.wikivoyage_title
                              AND (s.last_synced_at < now() - make_interval(days => :days)
                                   OR coalesce(s.parser_version, 0) <> :parser)
                              AND dp.graduated_at IS NULL
                            """)
                    .param("days", interval).param("parser", io.destinationintel.ingestion.wikivoyage.WikivoyageArticleParser.VERSION)
                    .query(UUID.class).list();
            for (UUID id : due) {
                jobs.enqueue("wikivoyage_guide", "wikivoyage-en", id, Map.of(), "wikivoyage_guide:" + id, 2, createdBy);
                wv++;
            }
        }
        plan.put("wikivoyageRefreshes", wv);

        int climate = refreshDue("open-meteo", "climate", "place_source_sync s WHERE s.source_id = 'open-meteo'", createdBy);
        plan.put("climateRefreshes", climate);
        plan.put("climateMissing", climateMissing(createdBy));
        plan.put("airQualityMissing", airQualityMissing(createdBy));

        if (config.expansionPerDay() > 0 && config.runs("wikivoyage_guide")) {
            List<UUID> next = jdbc.sql("""
                            SELECT p.id FROM place p
                            WHERE p.wikivoyage_title IS NOT NULL AND p.wikivoyage_title NOT LIKE '%/%'
                              AND p.kind IN ('city', 'town', 'village', 'island', 'park')
                              AND p.is_destination
                              AND EXISTS (SELECT 1 FROM place_relation r WHERE r.child_id = p.id AND r.relation = 'in_state')
                              AND NOT EXISTS (SELECT 1 FROM place_source_sync s WHERE s.place_id = p.id AND s.source_id = 'wikivoyage-en')
                              AND NOT EXISTS (SELECT 1 FROM ingestion_job j WHERE j.target_place_id = p.id
                                              AND j.job_type = 'wikivoyage_guide' AND j.created_at > now() - interval '1 day')
                            ORDER BY coalesce(p.sitelink_count, 0) DESC, coalesce(p.population, 0) DESC, p.name
                            LIMIT :n
                            """)
                    .param("n", Math.max(1, config.expansionPerDay() / 4)).query(UUID.class).list();
            next.forEach(id -> jobs.enqueue("wikivoyage_guide", "wikivoyage-en", id, Map.of(), "wikivoyage_guide:" + id, 1, createdBy));
            plan.put("expansion", next.size());
        }

        plan.put("focus", focusArea(createdBy));
        plan.put("nearestTransport", nearestTransport(createdBy));

        if (config.runs("coverage_refresh")) {
            jobs.enqueue("coverage_refresh", null, null, Map.of(), "coverage_refresh:all", 0, createdBy);
        }
        return plan;
    }

    /**
     * Fills the guides around each focus city: destinations within its radius that have a Wikivoyage article but no
     * guide, nearest first and then best known, a batch per run. Places already imported (even if the article gave
     * no listings) are not tried again, so the area converges instead of looping.
     */
    Map<String, Integer> focusArea(String createdBy) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String spec : config.focus()) {
            String[] f = spec.split(":");
            String slug = f[0].trim();
            double km;
            try {
                km = f.length > 1 ? Double.parseDouble(f[1].trim()) : 300;
            } catch (NumberFormatException e) {
                log.warn("Ignoring focus area {}: radius is not a number", spec);
                continue;
            }
            List<UUID> next = !config.runs("wikivoyage_guide") ? List.of() : jdbc.sql("""
                            WITH centre AS (SELECT geog FROM place WHERE slug = :slug AND geog IS NOT NULL)
                            SELECT p.id FROM place p CROSS JOIN centre
                            WHERE p.is_destination AND p.lifecycle_status = 'active' AND p.geog IS NOT NULL
                              AND p.wikivoyage_title IS NOT NULL AND p.wikivoyage_title NOT LIKE '%/%'
                              AND p.kind IN ('city', 'town', 'village', 'park', 'island', 'attraction', 'district')
                              AND ST_DWithin(p.geog, centre.geog, :metres)
                              AND NOT EXISTS (SELECT 1 FROM place_source_sync s WHERE s.place_id = p.id AND s.source_id = 'wikivoyage-en')
                              AND NOT EXISTS (SELECT 1 FROM ingestion_job j WHERE j.target_place_id = p.id AND j.job_type = 'wikivoyage_guide'
                                              AND (j.status IN ('queued', 'running') OR j.created_at > now() - interval '7 days'))
                            ORDER BY ST_Distance(p.geog, centre.geog) <= :near DESC, coalesce(p.sitelink_count, 0) DESC, p.name
                            LIMIT :n""")
                    .param("slug", slug).param("metres", km * 1000).param("near", Math.min(km, 150) * 1000)
                    .param("n", config.focusBatch()).query(UUID.class).list();
            next.forEach(id -> jobs.enqueue("wikivoyage_guide", "wikivoyage-en", id, Map.of(), "wikivoyage_guide:" + id, 20, createdBy));
            out.put(slug, next.size());
            if (config.runs("road_routes")) {
                // Drive times for the weekend-trips page; the job only asks the router for places not yet routed.
                jdbc.sql("SELECT id FROM place WHERE slug = :slug AND geog IS NOT NULL").param("slug", slug).query(UUID.class).optional()
                        .ifPresent(origin -> jobs.enqueue("road_routes", "osrm", origin, Map.of("radiusKm", km),
                                "road_routes:" + origin, 5, createdBy));
            }
        }
        return out;
    }

    /** Nearest airport and railway station for guides that lack them; one job works through them in batches. */
    private boolean nearestTransport(String createdBy) {
        if (!config.runs("nearest_transport")) {
            return false;
        }
        boolean due = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM place p JOIN destination_profile dp ON dp.place_id = p.id AND dp.coverage_status <> 'indexed'
                        LEFT JOIN place_source_sync s ON s.place_id = p.id AND s.source_id = 'ourairports' AND s.external_id = 'nearest'
                        WHERE p.geog IS NOT NULL AND (s.last_synced_at IS NULL
                           OR s.last_synced_at < now() - make_interval(days => (SELECT refresh_interval_days FROM source WHERE id = 'ourairports'))))""")
                .query(Boolean.class).single();
        if (due) {
            jobs.enqueue("nearest_transport", "ourairports", null, Map.of(), "nearest_transport:all", 1, createdBy);
        }
        return due;
    }

    /** Guides that never got their weather data (for example after the weather service refused for a while). */
    private int climateMissing(String createdBy) {
        if (!config.runs("climate")) {
            return 0;
        }
        List<UUID> missing = jdbc.sql("""
                        SELECT p.id FROM place p JOIN destination_profile dp ON dp.place_id = p.id AND dp.coverage_status <> 'indexed'
                        WHERE p.latitude IS NOT NULL
                          AND NOT EXISTS (SELECT 1 FROM place_source_sync s WHERE s.place_id = p.id AND s.source_id = 'open-meteo')
                          AND NOT EXISTS (SELECT 1 FROM ingestion_job j WHERE j.target_place_id = p.id AND j.job_type = 'climate'
                                          AND j.status IN ('queued', 'running'))
                        ORDER BY coalesce(p.sitelink_count, 0) DESC LIMIT :n""")
                .param("n", CLIMATE_CATCH_UP_PER_RUN).query(UUID.class).list();
        missing.forEach(id -> jobs.enqueue("climate", "open-meteo", id, Map.of(), "climate:" + id, 1, createdBy));
        return missing.size();
    }

    /** Guides without a smog-season check (or whose check is a year old), a few per run: it shares Open-Meteo's allowance. */
    private int airQualityMissing(String createdBy) {
        if (!config.runs("air_quality")) {
            return 0;
        }
        List<UUID> due = jdbc.sql("""
                        SELECT p.id FROM place p JOIN destination_profile dp ON dp.place_id = p.id AND dp.coverage_status <> 'indexed'
                        LEFT JOIN place_source_sync s ON s.place_id = p.id AND s.source_id = 'open-meteo-air'
                        WHERE p.latitude IS NOT NULL
                          AND (s.last_synced_at IS NULL OR s.last_synced_at < now() - make_interval(days =>
                               (SELECT refresh_interval_days FROM source WHERE id = 'open-meteo-air')))
                          AND NOT EXISTS (SELECT 1 FROM ingestion_job j WHERE j.target_place_id = p.id AND j.job_type = 'air_quality'
                                          AND j.status IN ('queued', 'running'))
                        ORDER BY s.last_synced_at NULLS FIRST, coalesce(p.sitelink_count, 0) DESC LIMIT :n""")
                .param("n", CLIMATE_CATCH_UP_PER_RUN).query(UUID.class).list();
        due.forEach(id -> jobs.enqueue("air_quality", "open-meteo-air", id, Map.of(), "air_quality:" + id, 1, createdBy));
        return due.size();
    }

    /** Small batches, so catching up never trips the weather service's rate limit again. */
    static final int CLIMATE_CATCH_UP_PER_RUN = 25;

    private int refreshDue(String sourceId, String jobType, String fromClause, String createdBy) {
        if (!config.runs(jobType)) {
            return 0;
        }
        int interval = sources.find(sourceId).map(s -> s.enabled() ? s.refreshIntervalDays() : -1).orElse(-1);
        if (interval <= 0) {
            return 0;
        }
        List<UUID> due = jdbc.sql("SELECT DISTINCT s.place_id FROM " + fromClause
                        + " AND s.last_synced_at < now() - make_interval(days => :days)")
                .param("days", interval).query(UUID.class).list();
        due.forEach(id -> jobs.enqueue(jobType, sourceId, id, Map.of(), jobType + ":" + id, 1, createdBy));
        return due.size();
    }
}
