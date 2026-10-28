package io.destinationintel.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JobQueueIT extends IntegrationTest {

    @Test
    void workerLimitedToLightJobsLeavesHeavyImportsQueued() {
        jobs.enqueue("osm_extract", "openstreetmap", null, Map.of(), "osm_extract:all", 50, "admin");
        jobs.enqueue("climate", "open-meteo", null, Map.of(), "climate:x", 1, "admin");

        var claimed = jobs.claimNext(List.of("wikivoyage_guide", "climate"));
        assertThat(claimed).isPresent();
        assertThat(claimed.get().jobType()).isEqualTo("climate");
        assertThat(jobs.claimNext(List.of("wikivoyage_guide", "climate"))).isEmpty();
        assertThat(jdbc.sql("SELECT status FROM ingestion_job WHERE job_type = 'osm_extract'").query(String.class).single())
                .isEqualTo("queued");

        // An unrestricted worker takes everything, highest priority first.
        assertThat(jobs.claimNext().orElseThrow().jobType()).isEqualTo("osm_extract");
    }

    @Test
    void aPausedJobKeepsItsAttemptsAndIsMarkedAsWaiting() {
        java.util.UUID id = jobs.enqueue("climate", "open-meteo", null, Map.of(), "climate:x", 1, "admin");
        var job = jobs.claimNext().orElseThrow();
        jobs.defer(job.id(), java.time.Duration.ofHours(1), "the source asked us to slow down (HTTP 429), trying again in an hour");
        var after = jobs.find(id).orElseThrow();
        assertThat(after.status()).isEqualTo("queued");
        assertThat(after.attempts()).isZero();
        assertThat(after.lastError()).startsWith("Waiting: the source asked us to slow down");
        assertThat(after.nextAttemptAt()).isAfter(java.time.OffsetDateTime.now().plusMinutes(50));
        assertThat(jobs.claimNext()).as("not due yet").isEmpty();
    }
}
