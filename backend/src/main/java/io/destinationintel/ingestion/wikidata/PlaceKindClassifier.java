package io.destinationintel.ingestion.wikidata;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Maps Wikidata "instance of" labels to the platform's place kinds. Items that are not
 * places (languages, cuisines, festivals) are excluded. Deterministic and order-independent:
 * the highest-priority matching kind wins.
 */
public final class PlaceKindClassifier {

    private static final List<String> EXCLUDE = List.of("language", "cuisine", "festival", "ethnic", "religio",
            "dynasty", "phrasebook", "railway line", "itinerary", "river", "people", "tribe", "caste", "script",
            "pandemic", "epidemic", "outbreak", "public health", "colonization", "independentism", "dialect",
            "luxury train", "passenger train", "cinema", "aspect in a", "aspect of", "feast", "travel topic", "by country");

    /** Whole-label exclusions: entities that are not travel destinations of India. */
    private static final java.util.Set<String> EXCLUDE_EXACT = java.util.Set.of("country", "sovereign state",
            "writing system", "abugida", "alphabet", "wikimedia list article", "list", "social movement",
            "independence movement", "political movement", "historical period", "historical country", "former country",
            "sea", "bay", "ocean", "gulf", "strait", "marginal sea", "war", "battle", "organization", "airline",
            "geographic region of the world", "subregion", "continent", "cultural region of the world", "client state");

    private record Rule(String kind, List<String> keywords) {}

    private static final List<Rule> RULES = List.of(
            new Rule("state", List.of("state of india")),
            new Rule("union_territory", List.of("union territory")),
            new Rule("district", List.of("district of india", "district of")),
            new Rule("neighbourhood", List.of("neighbourhood", "neighborhood", "suburb", "locality", "quarter")),
            new Rule("city", List.of("city", "metropolis", "megacity", "municipal corporation")),
            new Rule("town", List.of("town", "hill station", "municipality", "nagar panchayat", "municipal council",
                    "cantonment")),
            new Rule("village", List.of("village")),
            new Rule("park", List.of("national park", "wildlife", "sanctuary", "tiger reserve", "protected area",
                    "biosphere", "reserve forest", "nature reserve")),
            new Rule("island", List.of("island", "archipelago", "atoll")),
            new Rule("region", List.of("region", "mountain range", "valley", "division", "plateau", "coast",
                    "subdivision", "taluk", "tehsil", "territory", "group of settlements", "human-geographic", "hills",
                    "metropolitan area", "urban area")),
            new Rule("town", List.of("human settlement", "settlement", "populated place", "planned community")),
            new Rule("attraction", List.of("temple", "fort", "palace", "beach", "lake", "waterfall", "monument",
                    "mountain", "hill", "cave", "archaeological", "site", "church", "mosque", "heritage", "pilgrimage",
                    "museum", "garden", "dam", "tomb", "glacier", "pass")));

    private PlaceKindClassifier() {}

    public static Optional<String> classify(Collection<String> typeLabels, String wikivoyageTitle) {
        List<String> labels = typeLabels.stream().filter(l -> l != null && !l.isBlank())
                .map(l -> l.toLowerCase(Locale.ROOT)).toList();
        for (String label : labels) {
            if (EXCLUDE_EXACT.contains(label)) {
                return Optional.empty();
            }
            for (String ex : EXCLUDE) {
                if (label.contains(ex)) {
                    return Optional.empty();
                }
            }
        }
        // An administrative type stated by the item outranks the shape of its Wikivoyage title:
        // "Delhi/West" is a Wikivoyage subdivision page, but its item is West Delhi district.
        for (Rule rule : RULES.subList(0, 3)) {
            for (String label : labels) {
                for (String kw : rule.keywords()) {
                    if (label.contains(kw)) {
                        return Optional.of(rule.kind());
                    }
                }
            }
        }
        if (wikivoyageTitle != null && wikivoyageTitle.contains("/")) {
            return Optional.of("neighbourhood");
        }
        for (Rule rule : RULES) {
            for (String label : labels) {
                for (String kw : rule.keywords()) {
                    if (label.contains(kw)) {
                        return Optional.of(rule.kind());
                    }
                }
            }
        }
        return Optional.of("other");
    }
}
