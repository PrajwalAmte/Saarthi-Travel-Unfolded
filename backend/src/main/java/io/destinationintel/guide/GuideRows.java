package io.destinationintel.guide;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Raw rows read from the guide tables. */
public final class GuideRows {

    private GuideRows() {}

    public record EntryRow(
            UUID id,
            UUID destinationId,
            String section,
            String subsection,
            String area,
            String title,
            String altTitle,
            Double latitude,
            Double longitude,
            String wikidataId,
            Integer sitelinkCount,
            String attributes,
            String status,
            String statusReason,
            String origin,
            OffsetDateTime lastSeenInSourceAt,
            OffsetDateTime createdAt,
            Double distanceKm) {}

    public record FactRow(
            UUID id,
            UUID destinationId,
            UUID entryId,
            String section,
            String kind,
            String heading,
            String valueText,
            String valueJson,
            String volatility,
            String status,
            String origin,
            LocalDate observedAt,
            String observedBasis,
            OffsetDateTime sourceRefreshedAt,
            OffsetDateTime lastVerifiedAt,
            UUID contributionId,
            OffsetDateTime createdAt,
            int acceptedConfirmations,
            int acceptedDenials,
            OffsetDateTime lastConfirmationAt,
            OffsetDateTime lastDenialAt,
            int pendingCorrections,
            boolean ownerSubmitted) {}

    public record EvidenceRow(
            UUID id,
            UUID factId,
            String sourceId,
            String sourceName,
            String sourceCategory,
            String licenceName,
            String licenceUrl,
            String url,
            String permalink,
            String title,
            String excerpt,
            String extractionMethod,
            String sourceRevision,
            OffsetDateTime sourceRevisionAt,
            LocalDate observedAt,
            OffsetDateTime retrievedAt,
            String contributorName) {}

    public record ObservationRow(
            UUID id,
            UUID factId,
            String valueText,
            String valueJson,
            LocalDate observedAt,
            String changeType,
            String sourceId,
            OffsetDateTime recordedAt) {}
}
