package io.destinationintel.guide;

import static org.assertj.core.api.Assertions.assertThat;

import io.destinationintel.TestConfigs;
import io.destinationintel.guide.FreshnessPolicy.Freshness;
import io.destinationintel.guide.FreshnessPolicy.VerificationStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class FreshnessPolicyTest {

    private final FreshnessPolicy policy = new FreshnessPolicy(TestConfigs.freshness(),
            Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void volatileFactBecomesDueAfterItsWindow() {
        var current = policy.assess("volatile", "ingested", LocalDate.of(2026, 8, 1), null, 0, 0, null);
        assertThat(current.freshness()).isEqualTo(Freshness.CURRENT);
        assertThat(current.status()).isEqualTo(VerificationStatus.SOURCE_BACKED);
        assertThat(current.reviewDueAt()).isEqualTo(LocalDate.of(2026, 11, 29));

        var due = policy.assess("volatile", "ingested", LocalDate.of(2021, 3, 26), null, 0, 0, null);
        assertThat(due.freshness()).isEqualTo(Freshness.DUE);
        assertThat(due.status()).isEqualTo(VerificationStatus.NEEDS_REVERIFICATION);
    }

    @Test
    void unknownObservationDateIsNeverPresentedAsCurrent() {
        var semi = policy.assess("semi_stable", "ingested", null, null, 0, 0, null);
        assertThat(semi.freshness()).isEqualTo(Freshness.UNKNOWN);
        assertThat(semi.status()).isEqualTo(VerificationStatus.NEEDS_REVERIFICATION);
        var stable = policy.assess("stable", "ingested", null, null, 0, 0, null);
        assertThat(stable.status()).isEqualTo(VerificationStatus.SOURCE_BACKED);
    }

    @Test
    void communityConfirmationRefreshesAnOldFact() {
        var verified = policy.assess("semi_stable", "ingested", LocalDate.of(2017, 9, 15),
                OffsetDateTime.parse("2026-10-01T09:00:00Z"), 1, 0, null);
        assertThat(verified.freshness()).isEqualTo(Freshness.CURRENT);
        assertThat(verified.status()).isEqualTo(VerificationStatus.COMMUNITY_VERIFIED);
        assertThat(verified.basisDate()).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test
    void latestDenialMeansConflictingReports() {
        var conflicting = policy.assess("semi_stable", "ingested", LocalDate.of(2026, 9, 1),
                OffsetDateTime.parse("2026-09-02T00:00:00Z"), 1, 1, OffsetDateTime.parse("2026-10-01T00:00:00Z"));
        assertThat(conflicting.status()).isEqualTo(VerificationStatus.CONFLICTING_REPORTS);
        var resolved = policy.assess("semi_stable", "ingested", LocalDate.of(2026, 9, 1),
                OffsetDateTime.parse("2026-10-05T00:00:00Z"), 2, 1, OffsetDateTime.parse("2026-10-01T00:00:00Z"));
        assertThat(resolved.status()).isEqualTo(VerificationStatus.COMMUNITY_VERIFIED);
    }

    @Test
    void communityReportsAreLabelledAsSuch() {
        var reported = policy.assess("semi_stable", "community", LocalDate.of(2026, 10, 10), null, 0, 0, null);
        assertThat(reported.status()).isEqualTo(VerificationStatus.COMMUNITY_REPORTED);
    }

    @Test
    void recencyFactorDecaysAndHandlesUnknown() {
        assertThat(policy.recencyFactor("volatile", LocalDate.of(2026, 10, 15))).isEqualTo(1.0);
        assertThat(policy.recencyFactor("volatile", LocalDate.of(2026, 6, 1))).isLessThan(1.0).isGreaterThan(0.6);
        assertThat(policy.recencyFactor("volatile", LocalDate.of(2015, 1, 1))).isEqualTo(0.1);
        assertThat(policy.recencyFactor("stable", null)).isEqualTo(0.35);
    }
}
