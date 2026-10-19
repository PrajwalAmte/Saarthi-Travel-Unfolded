package io.destinationintel.admin;

import io.destinationintel.community.MailService;
import io.destinationintel.config.AppProperties;
import io.destinationintel.config.OAuthProviders;
import io.destinationintel.guide.FreshnessPolicy;
import io.destinationintel.ingestion.JobRepository;
import io.destinationintel.source.SourceController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Platform", description = "Configuration state and public operational status")
public class MetaController {

    public record Meta(
            long indexedDestinations,
            Map<String, Long> guidesByCoverage,
            boolean emailSignInAvailable,
            List<String> oauthProviders,
            String contributorLicence,
            Map<String, Integer> reviewWindowsDays,
            boolean ingestionEnabled) {}

    public record JobFailure(String jobType, String target, String error, OffsetDateTime at) {}

    public record IngestionStatus(
            List<SourceController.PublicSource> sources,
            Map<String, Long> jobsByStatus,
            OffsetDateTime lastSuccessfulJobAt,
            List<JobFailure> recentFailures) {}

    private final JdbcClient jdbc;
    private final MailService mail;
    private final OAuthProviders oauth;
    private final FreshnessPolicy freshness;
    private final JobRepository jobs;
    private final SourceController sources;
    private final AppProperties props;

    public MetaController(JdbcClient jdbc, MailService mail, OAuthProviders oauth, FreshnessPolicy freshness,
                          JobRepository jobs, SourceController sources, AppProperties props) {
        this.jdbc = jdbc;
        this.mail = mail;
        this.oauth = oauth;
        this.freshness = freshness;
        this.jobs = jobs;
        this.sources = sources;
        this.props = props;
    }

    @GetMapping("/api/meta")
    @Operation(summary = "Real index size, available sign-in methods and lifecycle defaults")
    public Meta meta() {
        long indexed = jdbc.sql("SELECT count(*) FROM place WHERE is_destination AND lifecycle_status = 'active'")
                .query(Long.class).single();
        Map<String, Long> byCoverage = new java.util.LinkedHashMap<>();
        jdbc.sql("SELECT coverage_status, count(*) AS n FROM destination_profile GROUP BY coverage_status ORDER BY coverage_status")
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getLong(2))).list()
                .forEach(e -> byCoverage.put(e.getKey(), e.getValue()));
        return new Meta(indexed, byCoverage, mail.configured(), oauth.enabled(), props.contributorLicence(),
                Map.of("stable", freshness.reviewWindowDays("stable"), "semi_stable", freshness.reviewWindowDays("semi_stable"),
                        "volatile", freshness.reviewWindowDays("volatile")),
                props.ingestion().enabled());
    }

    @GetMapping("/api/status/ingestion")
    @Operation(summary = "Public ingestion health: source sync state, job counts and recent failures")
    public IngestionStatus ingestion() {
        List<JobFailure> failures = jdbc.sql("""
                        SELECT j.job_type, p.name AS target, j.last_error AS error, coalesce(j.finished_at, j.started_at) AS at
                        FROM ingestion_job j LEFT JOIN place p ON p.id = j.target_place_id
                        WHERE j.status = 'failed' OR (j.status = 'queued' AND j.last_error IS NOT NULL)
                        ORDER BY coalesce(j.finished_at, j.started_at) DESC NULLS LAST LIMIT 10
                        """).query(JobFailure.class).list();
        OffsetDateTime lastSuccess = jdbc.sql("SELECT max(finished_at) FROM ingestion_job WHERE status = 'succeeded'")
                .query(OffsetDateTime.class).optional().orElse(null);
        return new IngestionStatus(sources.list(), jobs.countsByStatus(), lastSuccess, failures);
    }
}
