package io.destinationintel.ingestion;

import io.destinationintel.config.AppProperties;
import io.destinationintel.ingestion.JobRepository.JobRow;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Single worker that drains the ingestion queue. Jobs run one at a time so source APIs are
 * never hit in parallel; failures are recorded on the job and retried with backoff.
 */
@Component
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final JobRepository jobs;
    private final Map<String, JobHandler> handlers;
    private final boolean enabled;
    private final List<String> jobTypes;

    public JobRunner(JobRepository jobs, List<JobHandler> handlers, AppProperties props) {
        this.jobs = jobs;
        this.handlers = handlers.stream().collect(Collectors.toMap(JobHandler::type, Function.identity()));
        this.enabled = props.ingestion().enabled();
        this.jobTypes = props.ingestion().jobTypes();
        if (enabled && !jobTypes.isEmpty()) {
            log.info("Ingestion worker runs only these job types: {}", jobTypes);
        }
    }

    @Scheduled(fixedDelayString = "${app.ingestion.poll-interval}", initialDelayString = "PT15S")
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            jobs.touchImporter();
        } catch (RuntimeException e) {
            log.debug("Could not record importer heartbeat: {}", e.toString());
        }
        for (int i = 0; i < 25; i++) {
            Optional<JobRow> next = jobs.claimNext(jobTypes);
            if (next.isEmpty()) {
                return;
            }
            execute(next.get());
        }
    }

    public void execute(JobRow job) {
        JobHandler handler = handlers.get(job.jobType());
        MDC.put("requestId", "job-" + job.id().toString().substring(0, 8));
        long started = System.currentTimeMillis();
        try {
            if (handler == null) {
                jobs.fail(job.id(), "No handler registered for job type " + job.jobType(), false);
                return;
            }
            log.info("Running {} job {} (attempt {}){}", job.jobType(), job.id(), job.attempts(),
                    job.targetPlaceName() == null ? "" : " for " + job.targetPlaceName());
            Map<String, Object> stats = handler.run(job);
            jobs.succeed(job.id(), stats);
            log.info("Finished {} job {} in {} ms: {}", job.jobType(), job.id(), System.currentTimeMillis() - started, stats);
        } catch (JobHandler.PermanentFailure e) {
            log.warn("{} job {} failed permanently: {}", job.jobType(), job.id(), e.getMessage());
            jobs.fail(job.id(), e.getMessage(), false);
        } catch (RuntimeException e) {
            log.warn("{} job {} failed: {}", job.jobType(), job.id(), e.toString());
            jobs.fail(job.id(), e.getClass().getSimpleName() + ": " + e.getMessage(), true);
        } finally {
            MDC.remove("requestId");
        }
    }

    /** A single worker runs per deployment, so jobs still "running" at startup were interrupted. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        int recovered = jobs.recoverStale(Duration.ofMinutes(5));
        if (recovered > 0) {
            log.warn("Re-queued {} ingestion jobs interrupted by a restart", recovered);
        }
    }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    public void recover() {
        int recovered = jobs.recoverStale(Duration.ofMinutes(30));
        if (recovered > 0) {
            log.warn("Re-queued {} ingestion jobs that stopped without finishing", recovered);
        }
    }
}
