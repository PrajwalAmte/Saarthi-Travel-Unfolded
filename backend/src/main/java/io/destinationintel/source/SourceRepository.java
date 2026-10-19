package io.destinationintel.source;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class SourceRepository {

    public record SourceRow(
            String id,
            String name,
            String category,
            String accessMethod,
            String baseUrl,
            String licenceName,
            String licenceUrl,
            String attributionText,
            boolean shareAlike,
            String usageNotes,
            String rateLimitPolicy,
            int refreshIntervalDays,
            boolean enabled,
            OffsetDateTime lastFetchAt,
            OffsetDateTime lastSuccessAt,
            String lastError,
            OffsetDateTime lastErrorAt,
            int consecutiveFailures) {}

    private static final String COLUMNS = """
            id, name, category, access_method, base_url, licence_name, licence_url, attribution_text, share_alike,
            usage_notes, rate_limit_policy, refresh_interval_days, enabled, last_fetch_at, last_success_at,
            last_error, last_error_at, consecutive_failures""";

    private final JdbcClient jdbc;

    public SourceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<SourceRow> all() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM source ORDER BY CASE category WHEN 'community' THEN 2 ELSE 1 END, name")
                .query(SourceRow.class).list();
    }

    public Optional<SourceRow> find(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM source WHERE id = :id").param("id", id).query(SourceRow.class).optional();
    }

    public boolean isEnabled(String id) {
        return jdbc.sql("SELECT enabled FROM source WHERE id = :id").param("id", id).query(Boolean.class)
                .optional().orElse(false);
    }

    public void setEnabled(String id, boolean enabled) {
        jdbc.sql("UPDATE source SET enabled = :e, updated_at = now() WHERE id = :id")
                .param("e", enabled).param("id", id).update();
    }

    /** Sync bookkeeping is written in its own transaction so failures are recorded even on rollback. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFetchStarted(String id) {
        jdbc.sql("UPDATE source SET last_fetch_at = now(), updated_at = now() WHERE id = :id").param("id", id).update();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSuccess(String id) {
        jdbc.sql("""
                        UPDATE source SET last_success_at = now(), consecutive_failures = 0, updated_at = now()
                        WHERE id = :id
                        """).param("id", id).update();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailure(String id, String error) {
        jdbc.sql("""
                        UPDATE source SET last_error = :error, last_error_at = now(),
                               consecutive_failures = consecutive_failures + 1, updated_at = now()
                        WHERE id = :id
                        """)
                .param("error", truncate(error)).param("id", id).update();
    }

    static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }
}
