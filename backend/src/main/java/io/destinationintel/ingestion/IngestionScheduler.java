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
        if (!missingSeeds.isEmpty()) {
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
        if (!indexed) {
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
        sources.find("wikidata").ifPresent(s -> {
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
            sources.find(r[0]).ifPresent(s -> {
                OffsetDateTime last = jobs.lastSuccess(r[1], null).orElse(null);
                if (s.enabled() && (last == null || last.isBefore(OffsetDateTime.now().minusDays(s.refreshIntervalDays())))) {
                    jobs.enqueue(r[1], r[0], null, Map.of(), r[1] + ":all", 3, createdBy);
                    plan.put(r[0], "queued");
                }
            });
        }

        int wv = 0;
        int interval = sources.find("wikivoyage-en").map(s -> s.enabled() ? s.refreshIntervalDays() : -1).orElse(-1);
        if (interval > 0) {
            List<UUID> due = jdbc.sql("""
                            SELECT DISTINCT s.place_id FROM place_source_sync s
                            JOIN place p ON p.id = s.place_id
                            LEFT JOIN destination_profile dp ON dp.place_id = p.id
                            WHERE s.source_id = 'wikivoyage-en' AND s.external_id = p.wikivoyage_title
                              AND s.last_synced_at < now() - make_interval(days => :days)
                              AND dp.graduated_at IS NULL
                            """)
                    .param("days", interval).query(UUID.class).list();
            for (UUID id : due) {
                jobs.enqueue("wikivoyage_guide", "wikivoyage-en", id, Map.of(), "wikivoyage_guide:" + id, 2, createdBy);
                wv++;
            }
        }
        plan.put("wikivoyageRefreshes", wv);

        int climate = refreshDue("open-meteo", "climate", "place_source_sync s WHERE s.source_id = 'open-meteo'", createdBy);
        plan.put("climateRefreshes", climate);

        if (config.expansionPerDay() > 0) {
            List<UUID> next = jdbc.sql("""
                            SELECT p.id FROM place p
                            WHERE p.wikivoyage_title IS NOT NULL AND p.wikivoyage_title NOT LIKE '%/%'
                              AND p.kind IN ('city', 'town', 'village', 'island', 'park', 'region')
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

        jobs.enqueue("coverage_refresh", null, null, Map.of(), "coverage_refresh:all", 0, createdBy);
        return plan;
    }

    private int refreshDue(String sourceId, String jobType, String fromClause, String createdBy) {
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
