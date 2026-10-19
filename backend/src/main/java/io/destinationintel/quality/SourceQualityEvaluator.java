package io.destinationintel.quality;

import java.util.UUID;

/** Source-specific data-quality evaluation over the data actually stored for that source. */
public interface SourceQualityEvaluator {

    String sourceId();

    QualityResult evaluate(UUID runId);
}
