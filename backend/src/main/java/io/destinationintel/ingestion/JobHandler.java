package io.destinationintel.ingestion;

import io.destinationintel.ingestion.JobRepository.JobRow;
import java.util.Map;

/** One kind of ingestion or maintenance job. Implementations must be idempotent. */
public interface JobHandler {

    String type();

    /** Runs the job and returns statistics to store with it. Throw to fail the job. */
    Map<String, Object> run(JobRow job);

    /** Raised when the job cannot proceed yet (for example a daily quota is spent); it is re-queued for later. */
    class Deferred extends RuntimeException {
        private final java.time.Duration delay;

        public Deferred(String message, java.time.Duration delay) {
            super(message);
            this.delay = delay;
        }

        public java.time.Duration delay() {
            return delay;
        }
    }

    /** Raised for failures that retrying cannot fix (bad input, missing article). */
    class PermanentFailure extends RuntimeException {
        public PermanentFailure(String message) {
            super(message);
        }
    }
}
