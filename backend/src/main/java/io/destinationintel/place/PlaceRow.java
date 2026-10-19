package io.destinationintel.place;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PlaceRow(
        UUID id,
        String slug,
        String name,
        String kind,
        String description,
        Double latitude,
        Double longitude,
        Long population,
        String wikidataId,
        String wikivoyageTitle,
        String isoCode,
        Integer sitelinkCount,
        boolean isDestination,
        String lifecycleStatus,
        String origin,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    static final String COLUMNS = """
            p.id, p.slug, p.name, p.kind, p.description, p.latitude, p.longitude, p.population, p.wikidata_id,
            p.wikivoyage_title, p.iso_code, p.sitelink_count, p.is_destination, p.lifecycle_status, p.origin,
            p.created_at, p.updated_at""";

    public boolean hasCoordinates() {
        return latitude != null && longitude != null;
    }
}
