package io.destinationintel.ingestion.run;

import io.destinationintel.common.Json;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records one execution of a source adapter in {@code source_sync_run}. Counts are real
 * record-level counts reported by the adapter; stage transitions and warnings are written in
 * their own transactions so a failure part-way still leaves an accurate record.
 */
@Component
public class SyncRunRecorder {

    /** Mutable counters for a run in progress. */
    public static final class Run {
        public final UUID id;
        public final String sourceId;
        public final String scope;
        public final AtomicLong fetched = new AtomicLong();
        public final AtomicLong parsed = new AtomicLong();
        public final AtomicLong rejected = new AtomicLong();
        public final AtomicLong inserted = new AtomicLong();
        public final AtomicLong updated = new AtomicLong();
        public final AtomicLong unchanged = new AtomicLong();
        public final AtomicLong deleted = new AtomicLong();
        public final AtomicLong published = new AtomicLong();
        public final AtomicLong canonicalAdded = new AtomicLong();
        public final AtomicLong matchedExisting = new AtomicLong();
        public final AtomicLong unresolved = new AtomicLong();
        final Map<String, Long> rejectionReasons = new LinkedHashMap<>();
        final List<String> warnings = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        String datasetVersion;

        Run(UUID id, String sourceId, String scope) {
            this.id = id;
            this.sourceId = sourceId;
            this.scope = scope;
        }

        public synchronized void reject(String reason) {
            rejected.incrementAndGet();
            rejectionReasons.merge(reason, 1L, Long::sum);
        }

        public synchronized void warn(String warning) {
            if (warnings.size() < 200 && !warnings.contains(warning)) {
                warnings.add(warning);
            }
        }

        public synchronized void error(String error) {
            if (errors.size() < 200) {
                errors.add(error);
            }
        }

        public void datasetVersion(String version) {
            this.datasetVersion = version;
        }

        public synchronized Map<String, Long> rejectionReasons() {
            return Map.copyOf(rejectionReasons);
        }

        public synchronized List<String> warnings() {
            return List.copyOf(warnings);
        }

        public Map<String, Object> summary() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runId", id.toString());
            m.put("fetched", fetched.get());
            m.put("parsed", parsed.get());
            m.put("rejected", rejected.get());
            m.put("inserted", inserted.get());
            m.put("updated", updated.get());
            m.put("unchanged", unchanged.get());
            m.put("deleted", deleted.get());
            m.put("published", published.get());
            m.put("canonicalAdded", canonicalAdded.get());
            m.put("matchedExisting", matchedExisting.get());
            m.put("unresolved", unresolved.get());
            synchronized (this) {
                if (!rejectionReasons.isEmpty()) {
                    m.put("rejectionReasons", Map.copyOf(rejectionReasons));
                }
                if (!warnings.isEmpty()) {
                    m.put("warnings", List.copyOf(warnings));
                }
            }
            return m;
        }
    }

    public record RunRow(UUID id, String sourceId, UUID jobId, String scope, String datasetVersion, String status,
                         String stage, OffsetDateTime startedAt, OffsetDateTime finishedAt, long fetched, long parsed,
                         long rejected, long inserted, long updated, long unchanged, long deleted, long published,
                         long canonicalAdded, long matchedExisting, long unresolved, String rejectionReasons,
                         String warnings, String errors) {}

    private static final String COLUMNS = """
            id, source_id, job_id, scope, dataset_version, status, stage, started_at, finished_at, fetched, parsed,
            rejected, inserted, updated, unchanged, deleted, published, canonical_added, matched_existing, unresolved,
            rejection_reasons::text AS rejection_reasons, warnings::text AS warnings, errors::text AS errors""";

    private final JdbcClient jdbc;
    private final Json json;

    public SyncRunRecorder(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Run start(String sourceId, UUID jobId, String scope) {
        UUID id = jdbc.sql("""
                        INSERT INTO source_sync_run (source_id, job_id, scope) VALUES (:s, :j, :scope) RETURNING id
                        """)
                .param("s", sourceId).param("j", jobId).param("scope", scope).query(UUID.class).single();
        jdbc.sql("UPDATE source SET last_fetch_at = now(), updated_at = now() WHERE id = :s").param("s", sourceId).update();
        return new Run(id, sourceId, scope);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void stage(Run run, String stage) {
        jdbc.sql("UPDATE source_sync_run SET stage = :stage WHERE id = :id").param("stage", stage).param("id", run.id).update();
        flushCounts(run);
    }

    /**
     * Finishes a run. Status is derived from evidence, not assumed: an unexpected zero or a
     * sharp drop in fetched records versus the previous successful run of the same scope is a
     * warning, and a failed quality gate is reported as {@code quality_failed}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String finish(Run run, boolean qualityFailed, boolean expectRecords) {
        Optional<Long> previous = jdbc.sql("""
                        SELECT fetched FROM source_sync_run
                        WHERE source_id = :s AND scope = :scope AND id <> :id AND status IN ('succeeded', 'succeeded_with_warnings')
                        ORDER BY started_at DESC LIMIT 1
                        """)
                .param("s", run.sourceId).param("scope", run.scope).param("id", run.id).query(Long.class).optional();
        long fetched = run.fetched.get();
        if (fetched == 0 && expectRecords) {
            run.warn("The source returned zero records; this run did not change any data.");
        }
        if (previous.isPresent() && previous.get() > 0) {
            double ratio = (double) fetched / previous.get();
            if (fetched == 0) {
                run.warn("Unexpected zero records: the previous successful run fetched " + previous.get() + ".");
            } else if (ratio < 0.5 || ratio > 2.0) {
                run.warn("Record count changed sharply: " + previous.get() + " previously, " + fetched + " now.");
            }
        }
        String status = qualityFailed ? "quality_failed" : run.warnings().isEmpty() ? "succeeded" : "succeeded_with_warnings";
        flushCounts(run);
        jdbc.sql("""
                        UPDATE source_sync_run SET status = :status, stage = 'done', finished_at = now(), dataset_version = :v
                        WHERE id = :id
                        """)
                .param("status", status).param("v", run.datasetVersion).param("id", run.id).update();
        jdbc.sql("""
                        UPDATE source SET last_success_at = now(), consecutive_failures = 0,
                               dataset_version = coalesce(:v, dataset_version), updated_at = now()
                        WHERE id = :s
                        """).param("v", run.datasetVersion).param("s", run.sourceId).update();
        return status;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Run run, String error) {
        run.error(error == null ? "Unknown error" : error);
        flushCounts(run);
        jdbc.sql("UPDATE source_sync_run SET status = 'failed', finished_at = now(), dataset_version = :v WHERE id = :id")
                .param("v", run.datasetVersion).param("id", run.id).update();
        jdbc.sql("""
                        UPDATE source SET last_error = :e, last_error_at = now(), consecutive_failures = consecutive_failures + 1,
                               updated_at = now()
                        WHERE id = :s
                        """).param("e", truncate(error)).param("s", run.sourceId).update();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPublished(String sourceId) {
        jdbc.sql("UPDATE source SET last_publication_at = now(), updated_at = now() WHERE id = :s").param("s", sourceId).update();
    }

    private void flushCounts(Run run) {
        jdbc.sql("""
                        UPDATE source_sync_run SET fetched = :fetched, parsed = :parsed, rejected = :rejected, inserted = :inserted,
                               updated = :updated, unchanged = :unchanged, deleted = :deleted, published = :published,
                               canonical_added = :canonical, matched_existing = :matched, unresolved = :unresolved,
                               rejection_reasons = CAST(:reasons AS jsonb), warnings = CAST(:warnings AS jsonb),
                               errors = CAST(:errors AS jsonb)
                        WHERE id = :id
                        """)
                .param("fetched", run.fetched.get()).param("parsed", run.parsed.get()).param("rejected", run.rejected.get())
                .param("inserted", run.inserted.get()).param("updated", run.updated.get())
                .param("unchanged", run.unchanged.get()).param("deleted", run.deleted.get())
                .param("published", run.published.get()).param("canonical", run.canonicalAdded.get())
                .param("matched", run.matchedExisting.get()).param("unresolved", run.unresolved.get())
                .param("reasons", json.write(run.rejectionReasons())).param("warnings", json.write(run.warnings()))
                .param("errors", json.write(run.errors))
                .param("id", run.id).update();
    }

    public List<RunRow> recent(String sourceId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM source_sync_run WHERE (CAST(:s AS text) IS NULL OR source_id = :s)
                        ORDER BY started_at DESC LIMIT :limit""")
                .param("s", sourceId).param("limit", limit).query(RunRow.class).list();
    }

    public Optional<RunRow> latest(String sourceId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM source_sync_run WHERE source_id = :s ORDER BY started_at DESC LIMIT 1")
                .param("s", sourceId).query(RunRow.class).optional();
    }

    public Optional<RunRow> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM source_sync_run WHERE id = :id").param("id", id).query(RunRow.class).optional();
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() > 1000 ? s.substring(0, 1000) : s;
    }
}
