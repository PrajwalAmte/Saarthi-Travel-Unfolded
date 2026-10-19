package io.destinationintel.guide;

import io.destinationintel.config.AppProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Fact lifecycle rules. A fact's freshness is based on the most recent of (a) when its
 * source observed it and (b) when a contributor last confirmed it. Refetching a source
 * never counts as verification. Facts without any dated observation are "unknown".
 */
@Component
public class FreshnessPolicy {

    public enum Freshness { CURRENT, DUE, UNKNOWN }

    public enum VerificationStatus {
        SOURCE_BACKED, COMMUNITY_VERIFIED, COMMUNITY_REPORTED, NEEDS_REVERIFICATION, CONFLICTING_REPORTS
    }

    public record Assessment(Freshness freshness, LocalDate basisDate, LocalDate reviewDueAt,
                             VerificationStatus status) {}

    private final AppProperties.Freshness config;
    private final Clock clock;

    @Autowired
    public FreshnessPolicy(AppProperties props) {
        this(props.freshness(), Clock.systemUTC());
    }

    public FreshnessPolicy(AppProperties.Freshness config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    public int reviewWindowDays(String volatility) {
        return switch (volatility) {
            case "stable" -> config.stableDays();
            case "semi_stable" -> config.semiStableDays();
            case "volatile" -> config.volatileDays();
            default -> throw new IllegalArgumentException("Unknown volatility: " + volatility);
        };
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public Assessment assess(String volatility, String origin, LocalDate observedAt, OffsetDateTime lastConfirmationAt,
                             int acceptedConfirmations, int acceptedDenials, OffsetDateTime lastDenialAt) {
        LocalDate verified = lastConfirmationAt == null ? null : lastConfirmationAt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        LocalDate basis = latest(observedAt, verified);
        Freshness freshness;
        LocalDate due = null;
        if (basis == null) {
            freshness = Freshness.UNKNOWN;
        } else {
            due = basis.plusDays(reviewWindowDays(volatility));
            freshness = today().isAfter(due) ? Freshness.DUE : Freshness.CURRENT;
        }

        boolean denialIsLatest = acceptedDenials > 0
                && (lastConfirmationAt == null || (lastDenialAt != null && !lastDenialAt.isBefore(lastConfirmationAt)));
        VerificationStatus status;
        if (denialIsLatest) {
            status = VerificationStatus.CONFLICTING_REPORTS;
        } else if (freshness == Freshness.DUE) {
            status = VerificationStatus.NEEDS_REVERIFICATION;
        } else if (freshness == Freshness.UNKNOWN && !"stable".equals(volatility)) {
            status = VerificationStatus.NEEDS_REVERIFICATION;
        } else if (acceptedConfirmations > 0 && verified != null && !today().isAfter(verified.plusDays(reviewWindowDays(volatility)))) {
            status = VerificationStatus.COMMUNITY_VERIFIED;
        } else if ("community".equals(origin)) {
            status = VerificationStatus.COMMUNITY_REPORTED;
        } else {
            status = VerificationStatus.SOURCE_BACKED;
        }
        return new Assessment(freshness, basis, due, status);
    }

    /**
     * Recency factor in [0.1, 1] used by ranking: 1 when just observed, decaying across the
     * review window and further once overdue. Unknown observation dates score 0.35.
     */
    public double recencyFactor(String volatility, LocalDate basisDate) {
        if (basisDate == null) {
            return 0.35;
        }
        double window = reviewWindowDays(volatility);
        double age = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(basisDate, today()));
        double ratio = age / window;
        if (ratio <= 1) {
            return 1.0 - 0.3 * ratio;
        }
        return Math.max(0.1, 0.7 - 0.3 * (ratio - 1));
    }

    private static LocalDate latest(LocalDate a, LocalDate b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }
}
