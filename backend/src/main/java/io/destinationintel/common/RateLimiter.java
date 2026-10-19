package io.destinationintel.common;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fixed-window in-memory rate limiter. Suitable for a single API instance; durable limits
 * that must survive restarts (contribution volume) are additionally enforced in SQL.
 */
@Component
public class RateLimiter {

    private record Window(long startMillis, AtomicInteger count) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public RateLimiter() {
        this(Clock.systemUTC());
    }

    RateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Records one hit for {@code bucket}/{@code key}, throwing {@link RateLimitedException}
     * once more than {@code limit} hits have been recorded within {@code window}.
     */
    public void check(String bucket, String key, int limit, Duration window) {
        if (limit <= 0) {
            return;
        }
        long now = clock.millis();
        long windowMillis = window.toMillis();
        String id = bucket + ':' + key;
        // The hit is counted inside compute, which is atomic per key, so it always lands on the
        // window that is current at that moment (never on one being replaced at a rollover).
        Window w = windows.compute(id, (k, existing) -> {
            Window current = existing == null || now - existing.startMillis() >= windowMillis
                    ? new Window(now, new AtomicInteger())
                    : existing;
            current.count().incrementAndGet();
            return current;
        });
        if (w.count().get() > limit) {
            long remaining = windowMillis - (now - w.startMillis());
            throw new RateLimitedException(Duration.ofMillis(Math.max(1000, remaining)));
        }
    }

    /** Drops windows idle for two hours; every limit uses a window of one hour or less. */
    @Scheduled(fixedDelay = 300_000)
    void evictExpired() {
        long now = clock.millis();
        windows.entrySet().removeIf(e -> now - e.getValue().startMillis() > Duration.ofHours(2).toMillis());
    }
}
