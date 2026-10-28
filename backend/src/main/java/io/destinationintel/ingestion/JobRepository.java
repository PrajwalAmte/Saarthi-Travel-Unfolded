package io.destinationintel.ingestion;

import io.destinationintel.common.Json;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Postgres-backed job queue. Enqueueing is idempotent per dedup key while a job is active. */
@Repository
public class JobRepository {

    public record JobRow(
            UUID id,
            String jobType,
            String sourceId,
            UUID targetPlaceId,
            String targetPlaceName,
            String targetPlaceSlug,
            String params,
            String dedupKey,
            String status,
            int priority,
            int attempts,
            int maxAttempts,
            OffsetDateTime nextAttemptAt,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            String lastError,
            String stats,
            String createdBy,
            OffsetDateTime createdAt) {}

    private static final String SELECT = """
            SELECT j.id, j.job_type, j.source_id, j.target_place_id, p.name AS target_place_name,
                   p.slug AS target_place_slug, j.params::text AS params, j.dedup_key, j.status, j.priority,
                   j.attempts, j.max_attempts, j.next_attempt_at, j.started_at, j.finished_at, j.last_error,
                   j.stats::text AS stats, j.created_by, j.created_at
            FROM ingestion_job j LEFT JOIN place p ON p.id = j.target_place_id
            """;

    private final JdbcClient jdbc;
    private final Json json;

    public JobRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Enqueues a job unless an identical one (same dedup key) is already queued or running.
     * Returns the id of the new or existing active job.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public UUID enqueue(String type, String sourceId, UUID targetPlaceId, Map<String, ?> params, String dedupKey,
                        int priority, String createdBy) {
        Optional<UUID> inserted = jdbc.sql("""
                        INSERT INTO ingestion_job (job_type, source_id, target_place_id, params, dedup_key, priority, created_by)
                        VALUES (:type, :source, :place, CAST(:params AS jsonb), :dedup, :priority, :createdBy)
                        ON CONFLICT (dedup_key) WHERE status IN ('queued', 'running') DO NOTHING
                        RETURNING id
                        """)
                .param("type", type).param("source", sourceId).param("place", targetPlaceId)
                .param("params", json.write(params == null ? Map.of() : params)).param("dedup", dedupKey)
                .param("priority", priority).param("createdBy", createdBy)
                .query(UUID.class).optional();
        if (inserted.isPresent()) {
            return inserted.get();
        }
        // Raise the priority of the existing job if the new request is more urgent.
        jdbc.sql("UPDATE ingestion_job SET priority = greatest(priority, :p) WHERE dedup_key = :dedup AND status = 'queued'")
                .param("p", priority).param("dedup", dedupKey).update();
        return jdbc.sql("SELECT id FROM ingestion_job WHERE dedup_key = :dedup AND status IN ('queued', 'running') LIMIT 1")
                .param("dedup", dedupKey).query(UUID.class).single();
    }

    /** Records that the importer is alive; read by the request status page to say whether work is progressing. */
    public void touchImporter() {
        jdbc.sql("""
                        INSERT INTO importer_heartbeat (id, seen_at) VALUES (1, now())
                        ON CONFLICT (id) DO UPDATE SET seen_at = EXCLUDED.seen_at""").update();
    }

    /** Atomically claims the next due job of any type, or returns empty. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<JobRow> claimNext() {
        return claimNext(List.of());
    }

    /** Atomically claims the next due job among the given types (empty = any type), or returns empty. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<JobRow> claimNext(List<String> types) {
        Optional<UUID> id = jdbc.sql("""
                        UPDATE ingestion_job SET status = 'running', attempts = attempts + 1, started_at = now(),
                               heartbeat_at = now(), finished_at = NULL
                        WHERE id = (
                            SELECT id FROM ingestion_job
                            WHERE status = 'queued' AND next_attempt_at <= now()
                              AND (:anyType OR job_type = ANY (string_to_array(:types, ',')))
                            ORDER BY priority DESC, next_attempt_at, created_at
                            FOR UPDATE SKIP LOCKED LIMIT 1)
                        RETURNING id
                        """)
                .param("anyType", types.isEmpty()).param("types", String.join(",", types))
                .query(UUID.class).optional();
        return id.flatMap(this::find);
    }

    public Optional<JobRow> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE j.id = :id").param("id", id).query(JobRow.class).optional();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(UUID id, Map<String, ?> stats) {
        jdbc.sql("""
                        UPDATE ingestion_job SET status = 'succeeded', finished_at = now(), last_error = NULL,
                               stats = CAST(:stats AS jsonb)
                        WHERE id = :id
                        """)
                .param("stats", json.write(stats == null ? Map.of() : stats)).param("id", id).update();
    }

    /** Records a failure; the job is re-queued with backoff until max attempts are exhausted. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID id, String error, boolean retryable) {
        jdbc.sql("""
                        UPDATE ingestion_job SET
                            status = CASE WHEN :retryable AND attempts < max_attempts THEN 'queued' ELSE 'failed' END,
                            next_attempt_at = now() + make_interval(mins => (5 * power(3, attempts - 1))::int),
                            finished_at = CASE WHEN :retryable AND attempts < max_attempts THEN NULL ELSE now() END,
                            last_error = :error
                        WHERE id = :id
                        """)
                .param("retryable", retryable).param("error", error == null ? "Unknown error"
                        : error.length() > 2000 ? error.substring(0, 2000) : error)
                .param("id", id).update();
    }

    /** Marks a job's note as a pause, not an error, so it is shown as "waiting". */
    public static final String WAITING_PREFIX = "Waiting: ";

    /** Puts a job back in the queue for later without spending one of its attempts. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void defer(UUID id, Duration delay, String reason) {
        jdbc.sql("""
                        UPDATE ingestion_job SET status = 'queued', attempts = greatest(attempts - 1, 0), started_at = NULL,
                               next_attempt_at = now() + make_interval(secs => :secs), last_error = :reason
                        WHERE id = :id""")
                .param("secs", Math.max(60, delay.toSeconds())).param("reason", WAITING_PREFIX + reason).param("id", id).update();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void heartbeat(UUID id) {
        jdbc.sql("UPDATE ingestion_job SET heartbeat_at = now() WHERE id = :id AND status = 'running'")
                .param("id", id).update();
    }

    /** Re-queues jobs left "running" by a crashed or restarted instance. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recoverStale(Duration olderThan) {
        return jdbc.sql("""
                        UPDATE ingestion_job SET status = CASE WHEN attempts < max_attempts THEN 'queued' ELSE 'failed' END,
                               last_error = 'Interrupted: the worker stopped before the job finished',
                               finished_at = CASE WHEN attempts < max_attempts THEN NULL ELSE now() END
                        WHERE status = 'running' AND coalesce(heartbeat_at, started_at) < now() - make_interval(secs => :secs)
                        """)
                .param("secs", olderThan.toSeconds()).update();
    }

    @Transactional
    public boolean retry(UUID id) {
        return jdbc.sql("""
                        UPDATE ingestion_job SET status = 'queued', next_attempt_at = now(), max_attempts = attempts + 1,
                               last_error = NULL
                        WHERE id = :id AND status IN ('failed', 'cancelled')
                          AND NOT EXISTS (SELECT 1 FROM ingestion_job o WHERE o.dedup_key = ingestion_job.dedup_key
                                          AND o.status IN ('queued', 'running'))
                        """)
                .param("id", id).update() > 0;
    }

    @Transactional
    public boolean cancel(UUID id) {
        return jdbc.sql("UPDATE ingestion_job SET status = 'cancelled', finished_at = now() WHERE id = :id AND status = 'queued'")
                .param("id", id).update() > 0;
    }

    public List<JobRow> list(String status, String type, int limit, int offset) {
        return jdbc.sql(SELECT + """
                         WHERE (CAST(:status AS text) IS NULL OR j.status = :status)
                          AND (CAST(:type AS text) IS NULL OR j.job_type = :type)
                        ORDER BY j.created_at DESC LIMIT :limit OFFSET :offset""")
                .param("status", status).param("type", type).param("limit", limit).param("offset", offset)
                .query(JobRow.class).list();
    }

    public long count(String status, String type) {
        return jdbc.sql("""
                        SELECT count(*) FROM ingestion_job j
                        WHERE (CAST(:status AS text) IS NULL OR j.status = :status)
                          AND (CAST(:type AS text) IS NULL OR j.job_type = :type)
                        """)
                .param("status", status).param("type", type).query(Long.class).single();
    }

    public Map<String, Long> countsByStatus() {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        jdbc.sql("SELECT status, count(*) AS n FROM ingestion_job GROUP BY status ORDER BY status")
                .query((rs, i) -> Map.entry(rs.getString("status"), rs.getLong("n")))
                .list().forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    public Optional<OffsetDateTime> lastSuccess(String type, UUID placeId) {
        return jdbc.sql("""
                        SELECT max(finished_at) FROM ingestion_job
                        WHERE job_type = :type AND status = 'succeeded'
                          AND (CAST(:place AS uuid) IS NULL OR target_place_id = :place)
                        """)
                .param("type", type).param("place", placeId).query(OffsetDateTime.class).optional();
    }
}
