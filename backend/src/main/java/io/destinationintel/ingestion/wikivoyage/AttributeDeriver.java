package io.destinationintel.ingestion.wikivoyage;

import io.destinationintel.guide.PriceParser;
import io.destinationintel.guide.PriceParser.ParsedPrice;
import io.destinationintel.ranking.Preferences.PriceTier;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives ranking attributes from a listing's own source text. Each attribute records the
 * exact basis (heading, price or matched term) so explanations can quote real evidence.
 * Nothing is inferred without a textual basis; "hidden gem" is never assigned here.
 */
public final class AttributeDeriver {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    private static final Map<String, List<String>> INTEREST_TERMS = new LinkedHashMap<>();

    static {
        INTEREST_TERMS.put("culture", List.of("temple", "palace", "museum", "fort", "church", "mosque", "heritage",
                "monument", "gallery", "cathedral", "tomb", "mausoleum", "dargah", "gurudwara", "gurdwara", "monastery",
                "historic", "ruins", "architecture", "art", "dance", "music", "theatre", "theater", "crafts", "basilica",
                "synagogue", "stupa", "mandir", "math", "colonial"));
        INTEREST_TERMS.put("nature", List.of("park", "garden", "lake", "hill", "hills", "waterfall", "falls", "forest",
                "wildlife", "sanctuary", "beach", "river", "birds", "bird", "botanical", "valley", "nature", "reserve",
                "viewpoint", "sunset", "sunrise", "dam", "backwaters", "mangrove", "tea estate", "plantation"));
        INTEREST_TERMS.put("adventure", List.of("trek", "trekking", "hike", "hiking", "rafting", "paragliding",
                "climbing", "kayaking", "diving", "snorkelling", "snorkeling", "zipline", "camping", "safari", "surfing",
                "bungee", "skiing", "cycling tour", "rappelling"));
        INTEREST_TERMS.put("nightlife", List.of("pub", "bar", "brewery", "microbrewery", "nightclub", "club", "lounge",
                "nightlife", "cocktails", "cocktail", "beer", "live music", "dj"));
        INTEREST_TERMS.put("shopping", List.of("market", "bazaar", "mall", "emporium", "shopping", "silk", "handicraft",
                "handicrafts", "boutique", "antiques", "souvenirs"));
        INTEREST_TERMS.put("food", List.of("food walk", "food tour", "cooking class", "street food", "tasting"));
        INTEREST_TERMS.put("family", List.of("zoo", "amusement", "aquarium", "planetarium", "science centre",
                "science center", "kids", "children's", "family-friendly", "family friendly", "toy train", "water park",
                "playground", "theme park"));
    }

    private static final List<String> REGIONAL_TERMS = List.of("local", "traditional", "authentic", "regional",
            "home-style", "homestyle", "home style", "heritage", "ethnic", "native", "village-style");
    private static final List<String> POPULAR_TERMS = List.of("popular", "famous", "well-known", "well known",
            "crowded", "queue", "queues", "iconic", "institution", "legendary", "must-try", "must try", "busy");

    private static final Map<String, PriceTier> HEADING_TIERS = new LinkedHashMap<>();

    static {
        HEADING_TIERS.put("budget", PriceTier.BUDGET);
        HEADING_TIERS.put("cheap", PriceTier.BUDGET);
        HEADING_TIERS.put("inexpensive", PriceTier.BUDGET);
        HEADING_TIERS.put("mid-range", PriceTier.MODERATE);
        HEADING_TIERS.put("mid range", PriceTier.MODERATE);
        HEADING_TIERS.put("midrange", PriceTier.MODERATE);
        HEADING_TIERS.put("moderate", PriceTier.MODERATE);
        HEADING_TIERS.put("splurge", PriceTier.PREMIUM);
        HEADING_TIERS.put("expensive", PriceTier.PREMIUM);
        HEADING_TIERS.put("luxury", PriceTier.PREMIUM);
        HEADING_TIERS.put("upscale", PriceTier.PREMIUM);
        HEADING_TIERS.put("high-end", PriceTier.PREMIUM);
    }

    private AttributeDeriver() {}

    public static Map<String, Object> derive(String section, String subHeading, String title, String description,
                                             Optional<ParsedPrice> price, LocalDate priceObservedAt,
                                             Collection<String> specialityNames, Integer sitelinks,
                                             int majorThreshold, String sourceName) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        String text = ((title == null ? "" : title) + " . " + (description == null ? "" : description)).toLowerCase(Locale.ROOT);

        tierFromHeading(subHeading).ifPresentOrElse(
                t -> attrs.put("priceTier", Map.of("value", t.name().toLowerCase(Locale.ROOT),
                        "basis", "Listed under “" + subHeading.trim() + "” in " + sourceName)),
                () -> price.flatMap(p -> PriceParser.tierFor(section, p)).ifPresent(t -> attrs.put("priceTier", Map.of(
                        "value", t.name().toLowerCase(Locale.ROOT),
                        "basis", priceBasis(price.get(), priceObservedAt, sourceName)))));

        List<Map<String, String>> interests = new ArrayList<>();
        switch (section) {
            case "eat" -> interests.add(Map.of("tag", "food", "basis", "Listed as a place to eat in " + sourceName));
            case "buy" -> interests.add(Map.of("tag", "shopping", "basis", "Listed as a place to shop in " + sourceName));
            default -> { }
        }
        for (Map.Entry<String, List<String>> e : INTEREST_TERMS.entrySet()) {
            if (interests.stream().anyMatch(m -> m.get("tag").equals(e.getKey()))) {
                continue;
            }
            if (e.getKey().equals("nightlife") && !section.equals("drink") && !section.equals("do")) {
                continue;
            }
            firstTerm(text, e.getValue()).ifPresent(term -> interests.add(Map.of("tag", e.getKey(),
                    "basis", "Source text mentions “" + term + "”")));
        }
        if (!interests.isEmpty()) {
            attrs.put("interests", interests);
        }

        if (section.equals("eat") || section.equals("drink")) {
            List<Map<String, String>> food = new ArrayList<>();
            Optional<String> speciality = specialityNames.stream()
                    .filter(s -> s.length() >= 4 && containsTerm(text, s.toLowerCase(Locale.ROOT)))
                    .findFirst();
            if (speciality.isPresent()) {
                food.add(Map.of("tag", "regional", "basis", "Mentions local speciality “" + speciality.get() + "”"));
            } else {
                firstTerm(text, REGIONAL_TERMS).ifPresent(term -> food.add(Map.of("tag", "regional",
                        "basis", "Described as “" + term + "” in the source")));
            }
            firstTerm(text, POPULAR_TERMS).ifPresent(term -> food.add(Map.of("tag", "popular",
                    "basis", "Described as “" + term + "” in the source")));
            if (!food.isEmpty()) {
                attrs.put("foodStyle", food);
            }
        }

        firstTerm(text, List.of("wheelchair", "step-free", "accessible toilet")).ifPresent(term ->
                attrs.put("accessibility", Map.of("value", "noted", "basis", "Source text mentions “" + term + "”")));

        if (sitelinks != null && sitelinks >= majorThreshold && (section.equals("see") || section.equals("do"))) {
            Map<String, Object> major = new LinkedHashMap<>();
            major.put("sitelinks", sitelinks);
            major.put("basis", "Has Wikipedia articles in " + sitelinks + " languages (Wikidata)");
            attrs.put("major", major);
        }
        return attrs;
    }

    static Optional<PriceTier> tierFromHeading(String heading) {
        if (heading == null) {
            return Optional.empty();
        }
        String h = heading.toLowerCase(Locale.ROOT).trim();
        for (Map.Entry<String, PriceTier> e : HEADING_TIERS.entrySet()) {
            if (h.equals(e.getKey()) || h.startsWith(e.getKey() + " ") || h.endsWith(" " + e.getKey())) {
                return Optional.of(e.getValue());
            }
        }
        return Optional.empty();
    }

    private static String priceBasis(ParsedPrice p, LocalDate observedAt, String sourceName) {
        String amount = p.free() && p.max() == 0 ? "Free entry"
                : p.min() == p.max() ? PriceParser.formatInr(p.min())
                : PriceParser.formatInr(p.min()) + "–" + PriceParser.formatInr(p.max());
        String where = sourceName.startsWith("a contributor") ? " reported by a contributor" : " recorded in the listing";
        return "Price " + amount + where + (observedAt == null ? "" : " (" + MONTH.format(observedAt) + ")");
    }

    static Optional<String> firstTerm(String lowerText, List<String> terms) {
        for (String term : terms) {
            if (containsTerm(lowerText, term)) {
                return Optional.of(term);
            }
        }
        return Optional.empty();
    }

    static boolean containsTerm(String lowerText, String term) {
        Matcher m = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(term) + "(?![\\p{L}\\p{N}])").matcher(lowerText);
        return m.find();
    }
}
