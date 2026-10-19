package io.destinationintel.ingestion.wikidata;

import io.destinationintel.common.Json;
import io.destinationintel.ingestion.JobHandler;
import io.destinationintel.ingestion.JobRepository.JobRow;
import io.destinationintel.ingestion.run.SyncRunRecorder;
import io.destinationintel.ingestion.run.SyncRunRecorder.Run;
import io.destinationintel.quality.QualityService;
import io.destinationintel.source.SourceRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Job "wikidata_index": the nationwide index, or specific items via {"qids": [...]}. Every run
 * is recorded with real counts; nationwide runs are followed by a quality evaluation.
 */
@Component
public class WikidataIndexHandler implements JobHandler {

    private final WikidataIndexer indexer;
    private final SourceRepository sources;
    private final SyncRunRecorder recorder;
    private final QualityService quality;
    private final JdbcClient jdbc;
    private final Json json;

    public WikidataIndexHandler(WikidataIndexer indexer, SourceRepository sources, SyncRunRecorder recorder,
                                QualityService quality, JdbcClient jdbc, Json json) {
        this.indexer = indexer;
        this.sources = sources;
        this.recorder = recorder;
        this.quality = quality;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public String type() {
        return "wikidata_index";
    }

    @Override
    public Map<String, Object> run(JobRow job) {
        if (!sources.isEnabled("wikidata")) {
            throw new PermanentFailure("The Wikidata source is disabled in the source registry");
        }
        Map<String, Object> params = json.map(job.params());
        boolean targeted = params.get("qids") instanceof List<?> list && !list.isEmpty();
        Run run = recorder.start("wikidata", job.id(), targeted ? "items" : "nationwide");
        try {
            Map<String, Object> stats = new LinkedHashMap<>();
            if (targeted) {
                List<String> qids = ((List<?>) params.get("qids")).stream().map(String::valueOf)
                        .filter(q -> q.matches("^Q[0-9]+$")).toList();
                run.fetched.addAndGet(qids.size());
                run.parsed.addAndGet(qids.size());
                List<UUID> ids = indexer.indexItems(qids);
                run.updated.addAndGet(ids.size());
                stats.put("requested", qids.size());
                stats.put("indexed", ids.size());
                recorder.finish(run, false, true);
            } else {
                stats.putAll(indexer.indexAll(run, job.id()));
                recorder.stage(run, "quality");
                QualityService.ReportView report = quality.evaluate("wikidata", run.id, run.scope);
                boolean failed = "failed".equals(report.status());
                stats.put("quality", Map.of("status", report.status(), "reportId", report.id().toString()));
                if (!failed) {
                    run.published.set(jdbc.sql("SELECT count(*) FROM place WHERE is_destination AND lifecycle_status = 'active'")
                            .query(Long.class).single());
                    recorder.markPublished("wikidata");
                }
                stats.put("status", recorder.finish(run, failed, true));
            }
            stats.put("run", run.summary());
            return stats;
        } catch (RuntimeException e) {
            recorder.fail(run, e.getMessage());
            throw e;
        }
    }
}
