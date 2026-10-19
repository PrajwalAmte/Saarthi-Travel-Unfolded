package io.destinationintel.quality;

import io.destinationintel.common.Json;
import io.destinationintel.ingestion.JobHandler;
import io.destinationintel.ingestion.JobRepository.JobRow;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Job "quality_evaluation": re-evaluates one source's stored data on demand or on schedule. */
@Component
public class QualityEvaluationHandler implements JobHandler {

    private final QualityService quality;
    private final Json json;

    public QualityEvaluationHandler(QualityService quality, Json json) {
        this.quality = quality;
        this.json = json;
    }

    @Override
    public String type() {
        return "quality_evaluation";
    }

    @Override
    public Map<String, Object> run(JobRow job) {
        Object source = json.map(job.params()).get("sourceId");
        if (!(source instanceof String sourceId) || !quality.supports(sourceId)) {
            throw new PermanentFailure("No quality evaluator for " + source);
        }
        QualityService.ReportView r = quality.evaluate(sourceId, null, "scheduled");
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("sourceId", sourceId);
        stats.put("status", r.status());
        stats.put("score", r.score());
        stats.put("reportId", r.id().toString());
        return stats;
    }
}
