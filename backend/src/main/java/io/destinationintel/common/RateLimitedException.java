package io.destinationintel.common;

import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;

public class RateLimitedException extends ApiException {

    private final Duration retryAfter;

    public RateLimitedException(Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                "Too many requests. Please wait a little before trying again.",
                Map.of("retryAfterSeconds", Math.max(1, retryAfter.toSeconds())));
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
