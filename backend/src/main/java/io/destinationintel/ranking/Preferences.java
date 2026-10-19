package io.destinationintel.ranking;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reader preferences. Soft preferences (food, budget, comfort, interests) influence ranking;
 * hard constraints (maxPrice, accessibleOnly) filter entries before ranking.
 */
public record Preferences(
        FoodStyle food,
        PriceTier budget,
        Comfort comfort,
        Set<Interest> interests,
        PriceTier maxPrice,
        boolean accessibleOnly) {

    public enum FoodStyle { REGIONAL, POPULAR }

    public enum PriceTier {
        BUDGET, MODERATE, PREMIUM;

        public static Optional<PriceTier> parse(String s) {
            if (s == null || s.isBlank()) {
                return Optional.empty();
            }
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "budget", "budget-friendly", "budget_friendly" -> Optional.of(BUDGET);
                case "moderate", "mid-range", "midrange", "mid_range" -> Optional.of(MODERATE);
                case "premium", "splurge" -> Optional.of(PREMIUM);
                default -> Optional.empty();
            };
        }

        public String label() {
            return switch (this) {
                case BUDGET -> "Budget-friendly";
                case MODERATE -> "Moderate";
                case PREMIUM -> "Premium";
            };
        }
    }

    public enum Comfort {
        BASIC, COMFORTABLE, LUXURY;

        PriceTier asTier() {
            return switch (this) {
                case BASIC -> PriceTier.BUDGET;
                case COMFORTABLE -> PriceTier.MODERATE;
                case LUXURY -> PriceTier.PREMIUM;
            };
        }
    }

    public enum Interest { FOOD, CULTURE, NATURE, ADVENTURE, NIGHTLIFE, SHOPPING, FAMILY }

    public Preferences {
        interests = interests == null || interests.isEmpty() ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(interests));
    }

    public static Preferences none() {
        return new Preferences(null, null, null, Set.of(), null, false);
    }

    public boolean hasSoftPreferences() {
        return food != null || budget != null || comfort != null || !interests.isEmpty();
    }

    public static Preferences parse(String food, String budget, String comfort, String interests, String maxPrice,
                                    Boolean accessibleOnly) {
        return new Preferences(
                parseEnum(FoodStyle.class, food),
                PriceTier.parse(budget).orElse(null),
                parseEnum(Comfort.class, comfort),
                interests == null ? Set.of() : Arrays.stream(interests.split(","))
                        .map(i -> parseEnum(Interest.class, i))
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toSet()),
                PriceTier.parse(maxPrice).orElse(null),
                Boolean.TRUE.equals(accessibleOnly));
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (normalized.equals("AUTHENTIC")) {
            normalized = "REGIONAL";
        }
        if (normalized.equals("FAMILY_FRIENDLY")) {
            normalized = "FAMILY";
        }
        try {
            return Enum.valueOf(type, normalized);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
