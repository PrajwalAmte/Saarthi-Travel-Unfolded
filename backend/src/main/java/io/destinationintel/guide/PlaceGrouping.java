package io.destinationintel.guide;

import io.destinationintel.guide.GuideRows.EntryRow;
import java.text.Normalizer;
import java.util.Collection;
import java.util.Locale;

/**
 * Wikivoyage lists a chain once per branch ("Arsalan" under seven areas). A reader should see one place
 * with its branches, so listings with the same name in the same section are treated as one place.
 */
final class PlaceGrouping {

    private PlaceGrouping() {}

    /** Same section and same name once case, accents, punctuation and a leading "the" are ignored. */
    static String key(EntryRow e) {
        return e.section() + "|" + normalizeName(e.title());
    }

    static String normalizeName(String title) {
        if (title == null) {
            return "";
        }
        String s = Normalizer.normalize(title, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replace("&", " and ").replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        return s.startsWith("the ") ? s.substring(4) : s;
    }

    /** How many distinct places the listings amount to. */
    static int countPlaces(Collection<EntryRow> entries) {
        return (int) entries.stream().map(PlaceGrouping::key).distinct().count();
    }
}
