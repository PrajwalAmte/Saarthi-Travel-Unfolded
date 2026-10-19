package io.destinationintel.ingestion;

import io.destinationintel.guide.CoverageService;
import io.destinationintel.ingestion.JobRepository.JobRow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Job "coverage_refresh": recomputes coverage for one destination, or for every destination
 * with guide content. Freshness changes with the calendar, so this runs daily.
 */
@Component
public class CoverageRefreshHandler implements JobHandler {

    private final CoverageService coverage;
    private final JdbcClient jdbc;
    private final JobRepository jobs;

    public CoverageRefreshHandler(CoverageService coverage, JdbcClient jdbc, JobRepository jobs) {
        this.coverage = coverage;
        this.jdbc = jdbc;
        this.jobs = jobs;
    }

    @Override
    public String type() {
        return "coverage_refresh";
    }

    @Override
    public Map<String, Object> run(JobRow job) {
        List<UUID> targets = job.targetPlaceId() != null ? List.of(job.targetPlaceId())
                : jdbc.sql("""
                        SELECT DISTINCT destination_id FROM fact WHERE status = 'published'
                        UNION SELECT place_id FROM destination_profile
                        """).query(UUID.class).list();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        int i = 0;
        for (UUID id : targets) {
            byStatus.merge(coverage.refresh(id).status(), 1, Integer::sum);
            if (++i % 25 == 0) {
                jobs.heartbeat(job.id());
            }
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("destinations", targets.size());
        stats.put("byStatus", byStatus);
        return stats;
    }
}
