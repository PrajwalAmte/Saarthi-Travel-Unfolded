package io.destinationintel.ingestion;

import io.destinationintel.ingestion.JobRepository.JobRow;
import java.util.Map;

/** One kind of ingestion or maintenance job. Implementations must be idempotent. */
public interface JobHandler {

    String type();

    /** Runs the job and returns statistics to store with it. Throw to fail the job. */
    Map<String, Object> run(JobRow job);

    /** Raised for failures that retrying cannot fix (bad input, missing article). */
    class PermanentFailure extends RuntimeException {
        public PermanentFailure(String message) {
            super(message);
        }
    }
}
