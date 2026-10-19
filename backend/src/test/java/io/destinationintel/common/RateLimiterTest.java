package io.destinationintel.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        final AtomicLong millis = new AtomicLong(1_760_000_000_000L);

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }
    }

    @Test
    void allowsExactlyTheLimitPerWindowAndResetsAfterIt() {
        TestClock clock = new TestClock();
        RateLimiter limiter = new RateLimiter(clock);
        for (int i = 0; i < 3; i++) {
            limiter.check("search", "1.2.3.4", 3, Duration.ofMinutes(1));
        }
        assertThatThrownBy(() -> limiter.check("search", "1.2.3.4", 3, Duration.ofMinutes(1)))
                .isInstanceOf(RateLimitedException.class);
        limiter.check("search", "5.6.7.8", 3, Duration.ofMinutes(1));
        clock.millis.addAndGet(Duration.ofMinutes(1).toMillis());
        limiter.check("search", "1.2.3.4", 3, Duration.ofMinutes(1));
    }

    @Test
    void concurrentHitsNeverExceedTheLimit() throws Exception {
        RateLimiter limiter = new RateLimiter(new TestClock());
        AtomicInteger allowed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 400; i++) {
            pool.submit(() -> {
                try {
                    limiter.check("contribution", "user", 25, Duration.ofHours(1));
                    allowed.incrementAndGet();
                } catch (RateLimitedException e) {
                    // expected beyond the limit
                }
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(allowed.get()).isEqualTo(25);
    }
}
