package io.destinationintel.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RateLimitTest {

    @Test
    void aTooManyRequestsAnswerIsRecognisedEvenWhenWrapped() {
        var e429 = new HttpFetcher.FetchException("HTTP 429 from archive-api.open-meteo.com/v1/archive", 429);
        assertThat(JobRunner.rateLimited(e429)).isTrue();
        assertThat(JobRunner.rateLimited(new IllegalStateException("climate failed", e429))).isTrue();
        assertThat(JobRunner.rateLimited(new HttpFetcher.FetchException("HTTP 500", 500))).isFalse();
        assertThat(JobRunner.rateLimited(new IllegalStateException("boom"))).isFalse();
    }
}
