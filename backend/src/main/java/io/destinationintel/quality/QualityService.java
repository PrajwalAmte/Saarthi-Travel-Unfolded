package io.destinationintel.quality;

import io.destinationintel.common.ApiException;
import io.destinationintel.common.Json;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs source evaluators, stores their reports and sets each source's quality status. The
 * status gates publication of records from that source (see the POI publication rules).
 */
@Service
public class QualityService {

    private static final Logger log = LoggerFactory.getLogger(QualityService.class);

    public record ReportRow(UUID id, String sourceId, UUID runId, String scope, OffsetDateTime generatedAt, String status,
                            Double score, String scoreComponents, String metrics, String checks, String samples,
                            String limitations, UUID previousId) {}

    public record ReportView(UUID id, String sourceId, UUID runId, String scope, OffsetDateTime generatedAt, String status,
                             Double score, Map<String, Object> scoreComponents, Map<String, Object> metrics,
                             List<Object> checks, List<Object> samples, List<Object> limitations, UUID previousId) {}

    private final Map<String, SourceQualityEvaluator> evaluators;
    private final JdbcClient jdbc;
    private final Json json;

    public QualityService(List<SourceQualityEvaluator> evaluators, JdbcClient jdbc, Json json) {
        this.evaluators = evaluators.stream().collect(Collectors.toMap(SourceQualityEvaluator::sourceId, Function.identity()));
        this.jdbc = jdbc;
        this.json = json;
    }

    public boolean supports(String sourceId) {
        return evaluators.containsKey(sourceId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReportView evaluate(String sourceId, UUID runId, String scope) {
        SourceQualityEvaluator evaluator = evaluators.get(sourceId);
        if (evaluator == null) {
            throw ApiException.badRequest("no_evaluator", "No quality evaluator is defined for source " + sourceId);
        }
        QualityResult result = evaluator.evaluate(runId);
        UUID previous = jdbc.sql("SELECT id FROM quality_report WHERE source_id = :s ORDER BY generated_at DESC LIMIT 1")
                .param("s", sourceId).query(UUID.class).optional().orElse(null);
        UUID id = jdbc.sql("""
                        INSERT INTO quality_report (source_id, run_id, scope, status, score, score_components, metrics, checks,
                                                    samples, limitations, previous_id)
                        VALUES (:s, :run, :scope, :status, :score, CAST(:components AS jsonb), CAST(:metrics AS jsonb),
                                CAST(:checks AS jsonb), CAST(:samples AS jsonb), CAST(:limitations AS jsonb), :previous)
                        RETURNING id
                        """)
                .param("s", sourceId).param("run", runId).param("scope", scope).param("status", result.status())
                .param("score", result.score()).param("components", json.write(result.scoreComponents()))
                .param("metrics", json.write(result.metrics())).param("checks", json.write(result.checks()))
                .param("samples", json.write(result.samples())).param("limitations", json.write(result.limitations()))
                .param("previous", previous)
                .query(UUID.class).single();
        jdbc.sql("UPDATE source SET quality_status = :status, quality_evaluated_at = now(), updated_at = now() WHERE id = :s")
                .param("status", result.status()).param("s", sourceId).update();
        log.info("Quality evaluation of {}: {} (score {})", sourceId, result.status(), result.score());
        return find(id).orElseThrow();
    }

    public Optional<ReportView> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query(ReportRow.class).optional().map(this::view);
    }

    public Optional<ReportView> latest(String sourceId) {
        return jdbc.sql(SELECT + " WHERE source_id = :s ORDER BY generated_at DESC LIMIT 1").param("s", sourceId)
                .query(ReportRow.class).optional().map(this::view);
    }

    public List<ReportView> history(String sourceId, int limit) {
        return jdbc.sql(SELECT + " WHERE source_id = :s ORDER BY generated_at DESC LIMIT :limit").param("s", sourceId)
                .param("limit", limit).query(ReportRow.class).list().stream().map(this::view).toList();
    }

    /** Whether records from this source may be shown to readers. */
    public boolean publishable(String sourceId) {
        return jdbc.sql("""
                        SELECT enabled AND publish_enabled AND quality_status IN ('passed', 'warning') FROM source WHERE id = :s
                        """).param("s", sourceId).query(Boolean.class).optional().orElse(false);
    }

    private static final String SELECT = """
            SELECT id, source_id, run_id, scope, generated_at, status, score::double precision AS score,
                   score_components::text AS score_components, metrics::text AS metrics, checks::text AS checks,
                   samples::text AS samples, limitations::text AS limitations, previous_id
            FROM quality_report""";

    private ReportView view(ReportRow r) {
        return new ReportView(r.id(), r.sourceId(), r.runId(), r.scope(), r.generatedAt(), r.status(), r.score(),
                json.map(r.scoreComponents()), json.map(r.metrics()), json.list(r.checks()), json.list(r.samples()),
                json.list(r.limitations()), r.previousId());
    }
}
