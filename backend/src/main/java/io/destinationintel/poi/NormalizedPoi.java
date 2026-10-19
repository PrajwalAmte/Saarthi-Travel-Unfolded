package io.destinationintel.poi;

import java.time.OffsetDateTime;
import java.util.Map;

/** A validated, normalised POI record ready for persistence and identity resolution. */
public record NormalizedPoi(
        String sourceId,
        String externalId,
        String sourceVersion,
        OffsetDateTime sourceTimestamp,
        String datasetVersion,
        String name,
        String categoryRaw,
        String category,
        String categoryGroup,
        double latitude,
        double longitude,
        String wikidataId,
        Map<String, Object> attributes,
        Map<String, Object> raw,
        String contentHash) {}
