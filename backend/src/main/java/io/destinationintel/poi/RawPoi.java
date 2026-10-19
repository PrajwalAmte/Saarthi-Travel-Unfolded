package io.destinationintel.poi;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * A POI as read from one source, before validation. {@code raw} holds the source's original
 * attributes (e.g. OSM tags) and is stored unchanged for provenance.
 */
public record RawPoi(
        String sourceId,
        String externalId,
        String sourceVersion,
        OffsetDateTime sourceTimestamp,
        String datasetVersion,
        String name,
        String categoryRaw,
        PoiCategories.Category category,
        Double latitude,
        Double longitude,
        String wikidataId,
        Map<String, Object> attributes,
        Map<String, Object> raw) {}
