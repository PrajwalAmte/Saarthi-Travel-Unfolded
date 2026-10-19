package io.destinationintel.ranking;

import io.destinationintel.ranking.Preferences.Interest;
import io.destinationintel.ranking.Preferences.PriceTier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Evidence-derived attributes of a guide entry, as stored in {@code guide_entry.attributes}.
 * Every attribute carries a human-readable basis explaining which source text produced it.
 *
 * <pre>
 * {
 *   "priceTier": {"value": "budget", "basis": "Listed under \"Budget\" in the source"},
 *   "interests": [{"tag": "culture", "basis": "Source text mentions \"palace\""}],
 *   "foodStyle": [{"tag": "regional", "basis": "Mentions local speciality \"Mysore Pak\""}],
 *   "accessibility": {"value": "wheelchair", "basis": "Source text mentions wheelchair access"},
 *   "major": {"sitelinks": 45, "basis": "Covered by Wikipedia in 45 languages (Wikidata)"}
 * }
 * </pre>
 */
public record EntryAttributes(
        Attribute priceTier,
        List<Tag> interests,
        List<Tag> foodStyle,
        Attribute accessibility,
        Attribute major,
        Attribute temporarilyClosed) {

    public record Attribute(String value, String basis) {}

    public record Tag(String tag, String basis) {}

    public static final EntryAttributes EMPTY = new EntryAttributes(null, List.of(), List.of(), null, null, null);

    public EntryAttributes {
        interests = interests == null ? List.of() : List.copyOf(interests);
        foodStyle = foodStyle == null ? List.of() : List.copyOf(foodStyle);
    }

    @SuppressWarnings("unchecked")
    public static EntryAttributes fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return EMPTY;
        }
        return new EntryAttributes(
                attribute(map.get("priceTier")),
                tags(map.get("interests")),
                tags(map.get("foodStyle")),
                attribute(map.get("accessibility")),
                attribute(map.get("major")),
                attribute(map.get("temporarilyClosed")));
    }

    public Optional<PriceTier> tier() {
        return priceTier == null ? Optional.empty() : PriceTier.parse(priceTier.value());
    }

    public Optional<Tag> interest(Interest interest) {
        String key = interest.name().toLowerCase(Locale.ROOT);
        return interests.stream().filter(t -> key.equals(t.tag())).findFirst();
    }

    public Optional<Tag> food(String style) {
        return foodStyle.stream().filter(t -> style.equals(t.tag())).findFirst();
    }

    @SuppressWarnings("unchecked")
    private static Attribute attribute(Object o) {
        if (o instanceof Map<?, ?> m) {
            Object value = m.get("value");
            if (value == null && m.get("sitelinks") != null) {
                value = String.valueOf(m.get("sitelinks"));
            }
            Object basis = m.get("basis");
            return new Attribute(value == null ? null : String.valueOf(value), basis == null ? null : String.valueOf(basis));
        }
        return null;
    }

    private static List<Tag> tags(Object o) {
        List<Tag> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m && m.get("tag") != null) {
                    Object basis = m.get("basis");
                    out.add(new Tag(String.valueOf(m.get("tag")), basis == null ? null : String.valueOf(basis)));
                }
            }
        }
        return out;
    }
}
