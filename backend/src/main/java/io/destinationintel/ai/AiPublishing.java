package io.destinationintel.ai;

import java.util.UUID;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whether readers see AI improvements (summaries, tags, extracted facts, "In short"). Stored in the database, not in an
 * environment variable, so every instance sharing the database agrees. Off until an admin switches it on.
 */
@Component
public class AiPublishing {

    static final String KEY = "ai.publish";
    private static final long CACHE_MS = 30_000;

    private final JdbcClient jdbc;
    private final CacheManager caches;
    private volatile boolean cached;
    private volatile long cachedAt;

    public AiPublishing(JdbcClient jdbc, CacheManager caches) {
        this.jdbc = jdbc;
        this.caches = caches;
    }

    public boolean published() {
        long now = System.currentTimeMillis();
        if (now - cachedAt > CACHE_MS) {
            cached = jdbc.sql("SELECT value FROM app_setting WHERE key = :k").param("k", KEY).query(String.class)
                    .optional().map("true"::equals).orElse(false);
            cachedAt = now;
        }
        return cached;
    }

    /** Switches reader-visible AI improvements on or off everywhere at once. */
    @Transactional
    public int set(boolean on, UUID admin) {
        jdbc.sql("""
                        INSERT INTO app_setting (key, value, updated_by, updated_at) VALUES (:k, :v, :u, now())
                        ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_by = EXCLUDED.updated_by, updated_at = now()""")
                .param("k", KEY).param("v", String.valueOf(on)).param("u", admin).update();
        int facts = jdbc.sql("UPDATE fact SET status = :to, updated_at = now() WHERE ingest_key LIKE 'ai:%' AND status = :from")
                .param("to", on ? "published" : "hidden").param("from", on ? "hidden" : "published").update();
        cached = on;
        cachedAt = System.currentTimeMillis();
        clearGuideCaches();
        return facts;
    }

    public void clearGuideCaches() {
        for (String name : new String[] {"guide-base", "destination-detail"}) {
            var c = caches.getCache(name);
            if (c != null) {
                c.clear();
            }
        }
    }

    /** For tests: forget the cached value. */
    public void reset() {
        cachedAt = 0;
    }
}
