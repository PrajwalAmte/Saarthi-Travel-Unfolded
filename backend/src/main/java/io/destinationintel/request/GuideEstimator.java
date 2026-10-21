package io.destinationintel.request;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Tells a requester how long their guide is likely to take, from the real queue: the jobs that
 * will run before theirs, each costed at the median time that job type actually took recently.
 * The importer runs one job at a time, so the waits add up. When nothing has run lately the
 * importer is reported as not running instead of promising a time that depends on it.
 */
@Component
public class GuideEstimator {

    public record Estimate(Integer minMinutes, Integer maxMinutes, String summary, String basis, boolean importerRunning,
                           int jobsAhead) {}

    /** Used only for job types with fewer than {@link #MIN_SAMPLES} recent runs to learn from. */
    private static final Map<String, Double> FALLBACK_SECONDS = Map.of(
            "resolve_request", 30.0, "wikivoyage_guide", 150.0, "climate", 20.0, "place_image", 30.0);
    private static final double DEFAULT_SECONDS = 120;
    private static final int MIN_SAMPLES = 3;

    private final JdbcClient jdbc;

    public GuideEstimator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private record JobInfo(String jobType, String status, int priority, OffsetDateTime nextAttemptAt, OffsetDateTime createdAt,
                           OffsetDateTime startedAt) {}

    record Typical(double seconds, int samples) {}

    /**
     * @param jobId             the request's current job (resolve or guide job)
     * @param guideStillToQueue true when the job is only the lookup step and a guide build will follow it
     */
    public Optional<Estimate> estimate(UUID jobId, boolean guideStillToQueue) {
        if (jobId == null) {
            return Optional.empty();
        }
        JobInfo job = jdbc.sql("SELECT job_type, status, priority, next_attempt_at, created_at, started_at FROM ingestion_job WHERE id = :id")
                .param("id", jobId).query(JobInfo.class).optional().orElse(null);
        if (job == null || !(job.status().equals("queued") || job.status().equals("running"))) {
            return Optional.empty();
        }
        Map<String, Typical> typical = typicalDurations();
        double expected = 0;
        int ahead = 0;
        if (job.status().equals("running")) {
            double elapsed = Duration.between(job.startedAt() == null ? OffsetDateTime.now() : job.startedAt(), OffsetDateTime.now()).toSeconds();
            expected += Math.max(30, seconds(typical, job.jobType()) - elapsed);
        } else {
            long backoff = Math.max(0, Duration.between(OffsetDateTime.now(), job.nextAttemptAt()).toSeconds());
            expected += backoff;
            for (Map.Entry<String, Long> e : jobsAhead(jobId, job).entrySet()) {
                expected += e.getValue() * seconds(typical, e.getKey());
                ahead += e.getValue();
            }
            // Whatever is running right now finishes first, on average half-way through.
            for (String running : runningJobTypes(jobId)) {
                expected += seconds(typical, running) / 2;
                ahead++;
            }
            expected += seconds(typical, job.jobType());
        }
        if (guideStillToQueue) {
            expected += seconds(typical, "wikivoyage_guide");
        }
        return Optional.of(describe(expected, ahead, importerRunning(), typical.get("wikivoyage_guide")));
    }

    static Estimate describe(double expectedSeconds, int jobsAhead, boolean importerRunning, Typical guide) {
        int lo = (int) Math.max(1, Math.round(expectedSeconds * 0.8 / 60));
        int hi = (int) Math.max(lo + 1, Math.ceil(expectedSeconds * 1.6 / 60));
        String range = range(lo, hi);
        String summary = importerRunning ? "About " + range
                : "The importer is not running right now, so we cannot promise a time. Your request is saved, and once it "
                + "resumes it should take about " + range + ".";
        StringBuilder basis = new StringBuilder();
        basis.append(jobsAhead == 0 ? "Nothing is queued ahead of your request. "
                : jobsAhead + (jobsAhead == 1 ? " import is" : " imports are") + " ahead of your request. ");
        if (guide != null && guide.samples() >= MIN_SAMPLES) {
            basis.append("Recent guides took about ").append(minutes(guide.seconds())).append(" each (median of ")
                    .append(guide.samples()).append(" imports).");
        } else {
            basis.append("Too few guides have been built recently to measure, so this uses a typical figure.");
        }
        return new Estimate(lo, hi, summary, basis.toString(), importerRunning, jobsAhead);
    }

    private static String minutes(double seconds) {
        long m = Math.round(seconds / 60);
        return m <= 1 ? "a minute" : m + " minutes";
    }

    static String range(int lo, int hi) {
        if (hi < 90) {
            return lo + "–" + hi + " minutes";
        }
        if (hi > 24 * 60) {
            return "more than a day";
        }
        double l = Math.max(0.5, Math.round(lo / 30.0) / 2.0);
        double h = Math.max(l + 0.5, Math.round(hi / 30.0) / 2.0);
        return hours(l) + "–" + hours(h) + " hours";
    }

    private static String hours(double h) {
        return h == Math.floor(h) ? String.valueOf((long) h) : String.valueOf(h);
    }

    private static double seconds(Map<String, Typical> typical, String type) {
        Typical t = typical.get(type);
        if (t != null && t.samples() >= MIN_SAMPLES) {
            return t.seconds();
        }
        return FALLBACK_SECONDS.getOrDefault(type, DEFAULT_SECONDS);
    }

    private Map<String, Typical> typicalDurations() {
        Map<String, Typical> out = new HashMap<>();
        jdbc.sql("""
                        SELECT job_type,
                               percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM finished_at - started_at)) AS median,
                               count(*) AS samples
                        FROM ingestion_job
                        WHERE status = 'succeeded' AND started_at IS NOT NULL AND finished_at > now() - interval '14 days'
                        GROUP BY job_type""")
                .query((rs, i) -> Map.entry(rs.getString("job_type"), new Typical(rs.getDouble("median"), rs.getInt("samples"))))
                .list().forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /** Due jobs the worker will pick before this one: same ordering as {@code JobRepository.claimNext}. */
    private Map<String, Long> jobsAhead(UUID id, JobInfo job) {
        Map<String, Long> out = new HashMap<>();
        jdbc.sql("""
                        SELECT job_type, count(*) AS n FROM ingestion_job
                        WHERE status = 'queued' AND next_attempt_at <= now() AND id <> :id
                          AND (priority > :p OR (priority = :p AND (next_attempt_at, created_at) < (:na, :ca)))
                        GROUP BY job_type""")
                .param("id", id).param("p", job.priority()).param("na", job.nextAttemptAt()).param("ca", job.createdAt())
                .query((rs, i) -> Map.entry(rs.getString("job_type"), rs.getLong("n")))
                .list().forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    private java.util.List<String> runningJobTypes(UUID except) {
        return jdbc.sql("SELECT job_type FROM ingestion_job WHERE status = 'running' AND id <> :id")
                .param("id", except).query(String.class).list();
    }

    /** True when the importer polled in the last 3 minutes, or is partway through a long job. */
    boolean importerRunning() {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM importer_heartbeat WHERE seen_at > now() - interval '3 minutes')
                            OR EXISTS (SELECT 1 FROM ingestion_job WHERE status = 'running'
                                        AND coalesce(heartbeat_at, started_at) > now() - interval '30 minutes')""")
                .query(Boolean.class).single();
    }
}
