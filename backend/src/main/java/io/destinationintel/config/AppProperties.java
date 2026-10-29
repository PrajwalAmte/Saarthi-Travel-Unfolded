package io.destinationintel.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * Typed application configuration. Defaults live in application.yml; every value can be
 * overridden with an environment variable (see .env.example).
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String publicUrl,
        List<String> adminEmails,
        String contributorLicence,
        Security security,
        Mail mail,
        OAuth oauth,
        Ingestion ingestion,
        Freshness freshness,
        Ranking ranking,
        Coverage coverage,
        Trust trust,
        RateLimits rateLimits) {

    public AppProperties {
        adminEmails = adminEmails == null ? List.of()
                : adminEmails.stream().filter(StringUtils::hasText).map(e -> e.trim().toLowerCase()).toList();
    }

    public record Security(boolean trustForwardedHeaders) {}

    public record Mail(String from) {}

    public record OAuth(Provider google, Provider github) {
        public record Provider(String clientId, String clientSecret) {
            public boolean configured() {
                return StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret);
            }
        }
    }

    public record Ingestion(
            boolean enabled,
            boolean bootstrapOnStartup,
            List<String> seedGuides,
            int expansionPerDay,
            String contact,
            Duration httpTimeout,
            Duration requestPause,
            Duration pollInterval,
            int maxDistrictArticles,
            long minPopulationForIndex,
            int climateYears,
            String wikidataSparqlUrl,
            String wikidataApiUrl,
            String wikivoyageApiUrl,
            String commonsApiUrl,
            String openMeteoArchiveUrl,
            String osmExtractUrl,
            String osmStateUrl,
            String workDir,
            boolean keepDownloads,
            double poiDestinationRadiusKm,
            String overtureStacUrl,
            String overtureRelease,
            double overtureRadiusKm,
            double overtureMinConfidence,
            List<String> jobTypes,
            List<String> focus,
            int focusBatch) {

        public Ingestion {
            seedGuides = seedGuides == null ? List.of()
                    : seedGuides.stream().filter(StringUtils::hasText).map(String::trim).toList();
            focus = focus == null ? List.of() : focus.stream().filter(StringUtils::hasText).map(String::trim).toList();
            focusBatch = focusBatch <= 0 ? 60 : focusBatch;
            jobTypes = jobTypes == null ? List.of()
                    : jobTypes.stream().filter(StringUtils::hasText).map(String::trim).toList();
        }

        /** Whether this instance runs (and plans) jobs of this type. An empty list means every type. */
        public boolean runs(String jobType) {
            return jobTypes.isEmpty() || jobTypes.contains(jobType);
        }

        public String userAgent() {
            String base = "IndiaDestinationIntelligence/0.1 (open-source destination knowledge platform";
            return StringUtils.hasText(contact) ? base + "; " + contact.trim() + ")" : base + ")";
        }
    }

    public record Freshness(int stableDays, int semiStableDays, int volatileDays) {}

    public record Ranking(Weights weights, int diversityWindow, double nearbyRadiusKm, int majorAttractionMinSitelinks) {
        public record Weights(double preference, double evidence, double recency, double community, double geography,
                              double diversityPenalty) {}
    }

    public record Coverage(
            List<String> coreSections,
            int minEntriesPerCoreSection,
            int minCoreSectionsForUsable,
            int wellCoveredEntriesPerSection,
            double wellCoveredVerifiedShare,
            double minFreshShare,
            double graduationVerifiedShare) {}

    public record Trust(
            int promoteMinAccepted,
            double promoteMaxRejectionRatio,
            int promoteMinAccountAgeDays,
            int demoteWindowDays,
            int demoteMinRejected,
            double demoteRejectionRatio) {}

    public record RateLimits(
            int searchPerMinute,
            int requestsPerHour,
            int signInPerHour,
            int contributionsPerHour,
            int verificationsPerHour,
            int flagsPerHour) {}
}
