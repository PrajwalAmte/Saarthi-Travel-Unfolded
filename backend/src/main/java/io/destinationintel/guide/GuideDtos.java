package io.destinationintel.guide;

import io.destinationintel.common.PageResponse;
import io.destinationintel.ranking.RankingEngine.Reason;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** API response shapes for destination guides. */
public final class GuideDtos {

    private GuideDtos() {}

    public record EvidenceView(
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

    public record FactView(
            UUID id,
            UUID entryId,
            String kind,
            String section,
            String heading,
            String text,
            Map<String, Object> value,
            String volatility,
            String origin,
            LocalDate observedAt,
            String observedBasis,
            OffsetDateTime sourceRefreshedAt,
            OffsetDateTime lastVerifiedAt,
            LocalDate reviewDueAt,
            String freshness,
            String verificationStatus,
            int verificationCount,
            int denialCount,
            int pendingCorrections,
            boolean ownerSubmitted,
            List<EvidenceView> evidence) {}

    public record Label(String code, String text, String basis) {}

    public record EntryView(
            UUID id,
            String section,
            String subsection,
            String area,
            String title,
            String altTitle,
            Double latitude,
            Double longitude,
            Double distanceKm,
            String origin,
            List<Label> labels,
            FactView description,
            List<FactView> details,
            String mapUrl,
            Double score,
            List<Reason> reasons,
            List<Branch> branches) {}

    /** Another location of the same place (a chain's other outlets), shown on the one card. */
    public record Branch(
            UUID id,
            String area,
            String subsection,
            Double latitude,
            Double longitude,
            String mapUrl,
            FactView note,
            List<FactView> details) {}

    public record SectionView(
            String section,
            List<CategoryCount> categories,
            PageResponse<EntryView> entries,
            int totalPublished,
            boolean preferencesApplied,
            boolean personalizationApplied,
            String personalizationNote,
            int excludedByConstraints,
            List<String> constraintNotes,
            List<FactView> notes,
            List<FactView> specialities,
            List<ExternalLink> externalLinks) {}

    public record CategoryCount(String key, String label, int count) {}

    public record ExternalLink(String label, String url, String note) {}

    public record FactGroup(String key, String title, List<FactView> facts) {}

    public record FactSectionView(String section, List<FactGroup> groups, int totalFacts, ClimateView climate) {}

    public record ClimateMonth(int month, Double meanMaxC, Double meanMinC, Double precipitationMm, Double rainyDays) {}

    public record ClimateView(FactView fact, String period, List<ClimateMonth> months) {}

    public record AreaCount(String area, Map<String, Integer> sections, int total) {}

    public record NeighbourhoodView(EntryView entry, List<String> localities) {}

    public record NeighbourhoodSectionView(
            List<NeighbourhoodView> neighbourhoods,
            List<FactView> stayNotes,
            List<FactView> orientation,
            List<AreaCount> listingAreas) {}

    public record BudgetRange(
            String category,
            String label,
            String tier,
            Integer low,
            Integer median,
            Integer high,
            int observations,
            LocalDate earliestObserved,
            LocalDate latestObserved,
            int needingReverification,
            String unit,
            List<UUID> factIds,
            String reliability) {}

    public record BudgetView(
            String currency,
            List<BudgetRange> ranges,
            List<String> assumptions,
            int priceFactsConsidered,
            int priceFactsWithoutAmount,
            String emptyReason) {}
}
