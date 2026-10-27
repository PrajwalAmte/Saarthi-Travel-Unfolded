package io.destinationintel.ai;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks, in plain code, that AI output says nothing its source does not. A model can be fluent and wrong; these rules
 * do not trust it: every number and every proper name in a rewrite must already be in the source, and every quote
 * used as evidence must appear in the source word for word.
 */
public final class Faithfulness {

    private Faithfulness() {}

    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,.]*");
    /** A capitalised word: a name, unless it merely starts a sentence. */
    private static final Pattern WORD = Pattern.compile("[\\p{L}][\\p{L}'’\\-]*");
    static final int SUMMARY_MAX = 320;
    /** Praise a rewrite may only use if the original does: a summary must never sell harder than its source. */
    static final Pattern PRAISE = Pattern.compile("\\b(best|finest|famous|renowned|celebrated|iconic|world[- ]class|must[- ]see|must[- ]visit|"
            + "stunning|spectacular|breathtaking|amazing|incredible|unmissable|legendary|premier|global|popular|"
            + "beloved|acclaimed|exceptional|outstanding|excellent|great|wonderful|perfect|luxurious)\\b", Pattern.CASE_INSENSITIVE);
    /** Warnings and criticism: if the original has any, the rewrite must keep at least one of them. */
    static final Pattern CRITICISM = Pattern.compile("\\b(dirty|filthy|rude|lazy|unfriendly|overpriced|over-priced|rip[- ]?off|scam\\w*|tout\\w*|"
            + "avoid|beware|careful|drab|shabby|run[- ]down|noisy|loud|smelly|unhygienic|mediocre|disappointing|poor|bad|worst|slow|"
            + "crowded|overcrowded|cramped|unsafe|dangerous|nothing cosy|not worth|unreliable|expensive|pricey|basic|tired)\\b",
            Pattern.CASE_INSENSITIVE);
    /** Below this a description is already short; a rewrite would only add risk. */
    static final int SUMMARY_MIN_SOURCE = 140;

    /** Lower-cased, accents and curly quotes folded, whitespace collapsed: the form both sides are compared in. */
    static String norm(String s) {
        if (s == null) {
            return "";
        }
        String t = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        t = t.replace('“', '"').replace('”', '"').replace('’', '\'').replace('‘', '\'').replace('–', '-').replace('—', '-');
        return t.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /** True when the quote appears in the source word for word (ignoring case, accents and spacing). */
    public static boolean quotedIn(String quote, String source) {
        String q = norm(quote).replaceAll("^[\"'\\s]+|[\"'\\s.]+$", "");
        return q.length() >= 3 && norm(source).contains(q);
    }

    /** Every number in the text, without thousands separators or a trailing full stop. */
    static Set<String> numbers(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = NUMBER.matcher(text == null ? "" : text);
        while (m.find()) {
            out.add(m.group().replace(",", "").replaceAll("\\.+$", ""));
        }
        return out;
    }

    /** Capitalised words that are not the first word of a sentence: in practice, names. */
    static List<String> names(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(text == null ? "" : text);
        int lastEnd = 0;
        boolean sentenceStart = true;
        while (m.find()) {
            String between = text.substring(lastEnd, m.start());
            if (lastEnd > 0 && between.matches(".*[.!?:;]\\s*[\"'“(]*\\s*$")) {
                sentenceStart = true;
            }
            String w = m.group().replaceAll("('s|’s|['’-])+$", "");
            if (!sentenceStart && !w.isEmpty() && Character.isUpperCase(w.charAt(0)) && w.length() > 1) {
                out.add(w);
            }
            sentenceStart = false;
            lastEnd = m.end();
        }
        return out;
    }

    /**
     * Why a rewrite must not be shown, or empty when it is faithful. {@code known} holds text the reader already sees
     * next to it (title, area, destination), whose names may be repeated.
     */
    public static Optional<String> summaryProblem(String summary, String source, String... known) {
        return check(summary, source, true, known);
    }

    private static Optional<String> check(String summary, String source, boolean keepCriticism, String... known) {
        if (summary == null || summary.isBlank()) {
            return Optional.of("empty");
        }
        if (summary.length() > SUMMARY_MAX) {
            return Optional.of("longer than " + SUMMARY_MAX + " characters");
        }
        if (source != null && summary.length() > source.length()) {
            return Optional.of("longer than the original");
        }
        String src = norm(source);
        StringBuilder allowed = new StringBuilder(src);
        for (String k : known) {
            allowed.append(' ').append(norm(k));
        }
        String allowedText = allowed.toString();
        String allowedDigits = allowedText.replace(",", "");
        for (String n : numbers(summary)) {
            if (!allowedDigits.contains(n)) {
                return Optional.of("number \"" + n + "\" is not in the original");
            }
        }
        for (String name : names(summary)) {
            if (!nameSupported(name, allowedText)) {
                return Optional.of("name \"" + name + "\" is not in the original");
            }
        }
        Matcher praise = PRAISE.matcher(summary);
        while (praise.find()) {
            if (!Pattern.compile("\\b" + Pattern.quote(praise.group().toLowerCase(Locale.ROOT)) + "\\b").matcher(src).find()) {
                return Optional.of("adds praise (\"" + praise.group() + "\") the original does not use");
            }
        }
        if (keepCriticism && CRITICISM.matcher(src).find() && !CRITICISM.matcher(summary).find()) {
            Matcher c = CRITICISM.matcher(src);
            c.find();
            return Optional.of("drops the original's criticism or warning (\"" + c.group() + "\")");
        }
        return Optional.empty();
    }

    /** "Dutch-styled" is supported by "Dutch"; "TVs" by "TV". Every capitalised part must be found. */
    static boolean nameSupported(String name, String allowedText) {
        String n = norm(name);
        if (allowedText.contains(n)) {
            return true;
        }
        if (n.endsWith("s") && n.length() > 2 && allowedText.contains(n.substring(0, n.length() - 1))) {
            return true;
        }
        if (!name.matches(".*[-–].*")) {
            return false;
        }
        for (String part : name.split("[-–]")) {
            if (!part.isEmpty() && Character.isUpperCase(part.charAt(0)) && !allowedText.contains(norm(part))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Why a generated sentence must not be shown, or empty when every number and name in it comes from the cited
     * facts (or the destination's own name).
     */
    public static Optional<String> citedSentenceProblem(String sentence, List<String> citedTexts, String... known) {
        if (sentence == null || sentence.isBlank()) {
            return Optional.of("empty");
        }
        if (citedTexts.isEmpty()) {
            return Optional.of("cites no fact");
        }
        // One sentence covers one topic, so it need not repeat warnings its sources give about other topics.
        return check(sentence, String.join(" ", citedTexts) + " " + String.join(" ", known), false, known);
    }
}
