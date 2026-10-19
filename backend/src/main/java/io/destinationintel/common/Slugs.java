package io.destinationintel.common;

import java.text.Normalizer;
import java.util.Locale;

public final class Slugs {

    private Slugs() {}

    /** ASCII, lowercase, hyphen-separated slug. Returns an empty string for non-Latin input. */
    public static String slugify(String input) {
        if (input == null) {
            return "";
        }
        String n = Normalizer.normalize(input, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        n = n.toLowerCase(Locale.ROOT).replace('&', ' ').replaceAll("[^a-z0-9]+", "-");
        n = n.replaceAll("(^-+|-+$)", "");
        return n.length() > 80 ? n.substring(0, 80).replaceAll("-+$", "") : n;
    }

    /** Lowercase, accent-free, punctuation-free text used for de-duplication keys in Java. */
    public static String normalizeKey(String input) {
        if (input == null) {
            return "";
        }
        String n = Normalizer.normalize(input, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s]+", " ").trim();
    }
}
