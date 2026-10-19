package io.destinationintel.poi;

import io.destinationintel.config.AppProperties;
import io.destinationintel.ingestion.JobHandler;
import io.destinationintel.ingestion.JobRepository.JobRow;
import io.destinationintel.quality.QualityService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Job "match_resolution": decides every open match review from evidence (see {@link MatchEvidence}),
 * places the records that became places of their own, and re-evaluates the affected sources.
 * Imports run the same resolution themselves; this job clears a backlog without a full re-import.
 */
@Component
public class MatchResolutionHandler implements JobHandler {

    private final MatchReviewResolver resolver;
    private final PoiStore store;
    private final QualityService quality;
    private final JdbcClient jdbc;
    private final AppProperties.Ingestion config;

    public MatchResolutionHandler(MatchReviewResolver resolver, PoiStore store, QualityService quality, JdbcClient jdbc,
                                  AppProperties props) {
        this.resolver = resolver;
        this.store = store;
        this.quality = quality;
        this.jdbc = jdbc;
        this.config = props.ingestion();
    }

    @Override
    public String type() {
        return "match_resolution";
    }

    @Override
    public Map<String, Object> run(JobRow job) {
        List<String> sources = jdbc.sql("""
                        SELECT DISTINCT r.source_id FROM poi_match_review m JOIN poi_source_record r ON r.id = m.record_id
                        WHERE m.status = 'open' ORDER BY 1
                        """).query(String.class).list();
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("resolution", resolver.resolveOpen(null));
        stats.put("guideEntryLinks", resolver.resolveEntryLinks());
        stats.put("geographyAssigned", store.assignGeography(start, config.poiDestinationRadiusKm() * 1000));
        Map<String, Object> reports = new LinkedHashMap<>();
        for (String source : sources) {
            QualityService.ReportView r = quality.evaluate(source, null, "match_resolution");
            reports.put(source, Map.of("status", r.status(), "reportId", r.id().toString()));
        }
        stats.put("quality", reports);
        return stats;
    }
}
