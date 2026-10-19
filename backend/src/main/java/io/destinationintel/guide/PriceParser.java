package io.destinationintel.guide;

import io.destinationintel.ranking.Preferences.PriceTier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts Indian-rupee amounts from free-text price notes such as "₹75 for lunch",
 * "Rs 1,200-2,500" or "Dorms ₹400, doubles ₹1200". Text without a rupee amount yields no
 * structured price (the original text is still kept as the fact value).
 */
public final class PriceParser {

    public record ParsedPrice(int min, int max, boolean free) {
        public int midpoint() {
            return (min + max) / 2;
        }

        public Map<String, Object> toMap(String raw) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("currency", "INR");
            m.put("min", min);
            m.put("max", max);
            if (free) {
                m.put("free", true);
            }
            m.put("raw", raw);
            return m;
        }
    }

    private static final String NUM = "([0-9]{1,3}(?:,[0-9]{2,3})+|[0-9]+(?:\\.[0-9]+)?)";
    private static final Pattern PREFIXED = Pattern.compile(
            "(?:₹|\\bRs\\.?|\\bINR|\\bRupees?)\\s?" + NUM + "(?:\\s?(k|K)\\b)?"
                    + "(?:\\s?(?:-|–|—|to)\\s?(?:₹|Rs\\.?|INR)?\\s?" + NUM + "(?:\\s?(k|K)\\b)?)?");
    private static final Pattern SUFFIXED = Pattern.compile(NUM + "\\s?(?:rupees|Rs\\.?|INR)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FREE = Pattern.compile("\\bfree( of charge| entry| admission)?\\b|\\bno (entry )?fee\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FOREIGN = Pattern.compile("(?:US\\$|USD|€|EUR|£)\\s?[0-9]");

    private static final int MAX_AMOUNT = 5_000_000;

    private PriceParser() {}

    public static Optional<ParsedPrice> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        List<Integer> amounts = new ArrayList<>();
        Matcher m = PREFIXED.matcher(text);
        while (m.find()) {
            addAmount(amounts, m.group(1), m.group(2));
            if (m.group(3) != null) {
                addAmount(amounts, m.group(3), m.group(4) != null ? m.group(4) : m.group(2));
            }
        }
        if (amounts.isEmpty()) {
            Matcher s = SUFFIXED.matcher(text);
            while (s.find()) {
                addAmount(amounts, s.group(1), null);
            }
        }
        boolean free = FREE.matcher(text).find();
        if (amounts.isEmpty()) {
            if (free && !FOREIGN.matcher(text).find()) {
                return Optional.of(new ParsedPrice(0, 0, true));
            }
            return Optional.empty();
        }
        int min = amounts.stream().mapToInt(Integer::intValue).min().orElseThrow();
        int max = amounts.stream().mapToInt(Integer::intValue).max().orElseThrow();
        if (free) {
            min = 0;
        }
        return Optional.of(new ParsedPrice(min, max, free));
    }

    private static void addAmount(List<Integer> amounts, String number, String thousandSuffix) {
        try {
            double v = Double.parseDouble(number.replace(",", ""));
            if (thousandSuffix != null) {
                v *= 1000;
            }
            if (v >= 1 && v <= MAX_AMOUNT) {
                amounts.add((int) Math.round(v));
            }
        } catch (NumberFormatException ignored) {
            // not a number after all
        }
    }

    /**
     * Price tier for a parsed price within a guide section, using the midpoint of the stated
     * range. Thresholds (INR): eat/drink 250 / 800 per item or meal; stay 2,000 / 6,000 per
     * night; see/do/buy 200 / 1,500 per person.
     */
    public static Optional<PriceTier> tierFor(String section, ParsedPrice price) {
        int value = price.midpoint();
        int[] limits = switch (section.toLowerCase(Locale.ROOT)) {
            case "eat", "drink" -> new int[] {250, 800};
            case "stay" -> new int[] {2000, 6000};
            case "see", "do", "buy" -> new int[] {200, 1500};
            default -> null;
        };
        if (limits == null) {
            return Optional.empty();
        }
        if (value <= limits[0]) {
            return Optional.of(PriceTier.BUDGET);
        }
        return Optional.of(value <= limits[1] ? PriceTier.MODERATE : PriceTier.PREMIUM);
    }

    public static String formatInr(int amount) {
        return "₹" + String.format(Locale.ENGLISH, "%,d", amount);
    }
}
