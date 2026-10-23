package io.destinationintel.ingestion.wikivoyage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.jsoup.parser.Parser;

/** Minimal, deterministic MediaWiki markup handling: template parsing and plain-text conversion. */
public final class WikiText {

    public record Template(String name, Map<String, String> params, List<String> positional, int start, int end) {
        public String param(String key) {
            String v = params.get(key);
            return v == null ? null : v.trim();
        }
    }

    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern REF = Pattern.compile("<ref[^>/]*/>|<ref[^>]*>.*?</ref>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern GALLERY = Pattern.compile("<gallery[^>]*>.*?</gallery>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern BR = Pattern.compile("<br\\s*/?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("</?[a-zA-Z][^>]*>");
    private static final Pattern EXT_LINK_LABEL = Pattern.compile("\\[(?:https?:)?//[^\\s\\]]+\\s+([^\\]]+)]");
    private static final Pattern EXT_LINK_BARE = Pattern.compile("\\[(?:https?:)?//[^\\s\\]]+]");
    private static final Pattern BOLD_ITALIC = Pattern.compile("'{2,5}");
    /** What is left when a template inside brackets is dropped: "()", "[]", "(see )". */
    private static final Pattern EMPTY_BRACKETS = Pattern.compile("[ \\t]*[(\\[][ \\t]*[)\\]]");
    private static final Pattern DANGLING_SEE = Pattern.compile(
            "[ \\t]*\\((?:see|see also|cf\\.?|compare|also)[ \\t,;:]*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SPACE_BEFORE_PUNCT = Pattern.compile("[ \\t]+([,.;:])");
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t\\u00A0]+");

    private WikiText() {}

    /** Finds all top-level templates (nested templates are part of their parent's parameters). */
    public static List<Template> templates(String text) {
        List<Template> out = new ArrayList<>();
        int i = 0;
        while (true) {
            int start = text.indexOf("{{", i);
            if (start < 0) {
                break;
            }
            int end = matchingClose(text, start);
            if (end < 0) {
                break;
            }
            out.add(parseTemplate(text.substring(start + 2, end - 2), start, end));
            i = end;
        }
        return out;
    }

    /** Returns the index just after the "}}" closing the template opened at {@code start}, or -1. */
    static int matchingClose(String text, int start) {
        int depth = 0;
        int i = start;
        while (i < text.length() - 1) {
            if (text.startsWith("{{", i)) {
                depth++;
                i += 2;
            } else if (text.startsWith("}}", i)) {
                depth--;
                i += 2;
                if (depth == 0) {
                    return i;
                }
            } else {
                i++;
            }
        }
        return -1;
    }

    static Template parseTemplate(String inner, int start, int end) {
        List<String> parts = splitTopLevel(inner);
        String name = parts.isEmpty() ? "" : parts.get(0).trim().replace('_', ' ').toLowerCase(Locale.ROOT);
        Map<String, String> params = new LinkedHashMap<>();
        List<String> positional = new ArrayList<>();
        for (int p = 1; p < parts.size(); p++) {
            String part = parts.get(p);
            int eq = topLevelEquals(part);
            if (eq > 0) {
                params.put(part.substring(0, eq).trim().toLowerCase(Locale.ROOT), part.substring(eq + 1));
            } else {
                positional.add(part);
            }
        }
        return new Template(name, params, positional, start, end);
    }

    /** Splits on "|" that are not inside nested templates or links. */
    static List<String> splitTopLevel(String s) {
        List<String> parts = new ArrayList<>();
        int braces = 0;
        int brackets = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (s.startsWith("{{", i)) {
                braces++;
                cur.append("{{");
                i++;
                continue;
            }
            if (s.startsWith("}}", i) && braces > 0) {
                braces--;
                cur.append("}}");
                i++;
                continue;
            }
            if (s.startsWith("[[", i)) {
                brackets++;
                cur.append("[[");
                i++;
                continue;
            }
            if (s.startsWith("]]", i) && brackets > 0) {
                brackets--;
                cur.append("]]");
                i++;
                continue;
            }
            if (c == '|' && braces == 0 && brackets == 0) {
                parts.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        parts.add(cur.toString());
        return parts;
    }

    private static int topLevelEquals(String part) {
        int braces = 0;
        int brackets = 0;
        for (int i = 0; i < part.length(); i++) {
            if (part.startsWith("{{", i)) {
                braces++;
                i++;
            } else if (part.startsWith("}}", i)) {
                braces--;
                i++;
            } else if (part.startsWith("[[", i)) {
                brackets++;
                i++;
            } else if (part.startsWith("]]", i)) {
                brackets--;
                i++;
            } else if (part.charAt(i) == '=' && braces == 0 && brackets == 0) {
                return i;
            }
        }
        return -1;
    }

    /** Converts wiki markup to readable plain text, keeping paragraph and bullet breaks. */
    public static String toPlain(String wikitext) {
        if (wikitext == null || wikitext.isEmpty()) {
            return "";
        }
        String s = COMMENT.matcher(wikitext).replaceAll("");
        s = REF.matcher(s).replaceAll("");
        s = GALLERY.matcher(s).replaceAll("");
        s = removeTables(s);
        s = replaceTemplates(s);
        s = replaceLinks(s);
        s = EXT_LINK_LABEL.matcher(s).replaceAll("$1");
        s = EXT_LINK_BARE.matcher(s).replaceAll("");
        s = BR.matcher(s).replaceAll(" ");
        s = TAG.matcher(s).replaceAll("");
        s = BOLD_ITALIC.matcher(s).replaceAll("");
        s = Parser.unescapeEntities(s, false);
        StringBuilder out = new StringBuilder();
        for (String line : s.split("\n")) {
            String l = cleanArtifacts(MULTI_SPACE.matcher(line).replaceAll(" ").trim());
            if (l.startsWith("*") || l.startsWith("#")) {
                l = ("• " + l.replaceFirst("^[*#:]+\\s*", "")).trim();
            } else if (l.startsWith(":") || l.startsWith(";")) {
                l = l.replaceFirst("^[:;]+\\s*", "");
            }
            if (l.equals("•")) {
                continue;
            }
            out.append(l).append('\n');
        }
        return out.toString().replaceAll("\n{3,}", "\n\n").trim();
    }

    /**
     * Removes leftovers of dropped templates so a listing never shows "Kolkata ()" or "(see )". The same rules are
     * applied to stored text by the migration that cleaned existing guides, and the quality gate fails an import
     * that still contains them.
     */
    public static String cleanArtifacts(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String s = DANGLING_SEE.matcher(text).replaceAll("");
        s = EMPTY_BRACKETS.matcher(s).replaceAll("");
        s = SPACE_BEFORE_PUNCT.matcher(s).replaceAll("$1");
        return s.trim();
    }

    /** True when the text still contains an empty bracket pair or a dangling "(see )". */
    public static boolean hasArtifacts(String text) {
        return text != null && (EMPTY_BRACKETS.matcher(text).find() || DANGLING_SEE.matcher(text).find());
    }

    private static String removeTables(String s) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        for (String line : s.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("{|")) {
                depth++;
                continue;
            }
            if (depth > 0) {
                if (t.startsWith("|}")) {
                    depth--;
                }
                continue;
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String replaceTemplates(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        for (Template t : templates(s)) {
            out.append(s, i, t.start());
            out.append(inlineTemplateText(t));
            i = t.end();
        }
        out.append(s.substring(Math.min(i, s.length())));
        return out.toString();
    }

    /** Text rendered for well-known inline templates; everything else is dropped. */
    static String inlineTemplateText(Template t) {
        String n = t.name();
        switch (n) {
            case "iata", "station", "lang", "nowrap", "nobr", "smaller", "small" -> {
                return t.positional().isEmpty() ? nameParam(t) : toPlainInline(t.positional().get(t.positional().size() - 1));
            }
            case "convert", "cvt" -> {
                if (t.positional().size() >= 2) {
                    return t.positional().get(0).trim() + " " + t.positional().get(1).trim();
                }
                return "";
            }
            case "marker", "listing", "see", "do", "buy", "eat", "drink", "sleep", "go" -> {
                return nameParam(t);
            }
            case "w", "wp", "wikipedia" -> {
                return t.positional().isEmpty() ? "" : toPlainInline(t.positional().get(t.positional().size() - 1));
            }
            default -> {
                return "";
            }
        }
    }

    private static String nameParam(Template t) {
        String name = t.param("name");
        return name == null ? "" : toPlainInline(name);
    }

    private static String replaceLinks(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int open = s.indexOf("[[", i);
            if (open < 0) {
                out.append(s.substring(i));
                break;
            }
            out.append(s, i, open);
            int depth = 0;
            int j = open;
            int close = -1;
            while (j < s.length() - 1) {
                if (s.startsWith("[[", j)) {
                    depth++;
                    j += 2;
                } else if (s.startsWith("]]", j)) {
                    depth--;
                    j += 2;
                    if (depth == 0) {
                        close = j;
                        break;
                    }
                } else {
                    j++;
                }
            }
            if (close < 0) {
                out.append(s.substring(open));
                break;
            }
            String inner = s.substring(open + 2, close - 2);
            String lower = inner.toLowerCase(Locale.ROOT);
            if (lower.startsWith("file:") || lower.startsWith("image:") || lower.startsWith("category:")
                    || lower.matches("^[a-z]{2,3}:.*")) {
                // drop media, categories and interlanguage links
            } else {
                int pipe = inner.lastIndexOf('|');
                out.append(pipe >= 0 ? inner.substring(pipe + 1) : inner.replaceFirst("#.*$", ""));
            }
            i = close;
        }
        return out.toString();
    }

    /** Plain text for a single-line value (listing fields). */
    public static String toPlainInline(String wikitext) {
        return toPlain(wikitext).replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    /** Link target of the first internal link in a value, if any ("[[Bengaluru/North|North]]" -> "Bengaluru/North"). */
    public static String firstLinkTarget(String wikitext) {
        if (wikitext == null) {
            return null;
        }
        var m = Pattern.compile("\\[\\[([^|\\]]+)").matcher(wikitext);
        return m.find() ? m.group(1).trim().replace('_', ' ') : null;
    }

    /** Trims text to at most {@code max} characters, ending at a sentence boundary when possible. */
    public static String truncateAtSentence(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        String cut = text.substring(0, max);
        int end = Math.max(cut.lastIndexOf(". "), Math.max(cut.lastIndexOf(".\n"), cut.lastIndexOf("\n")));
        if (end > max / 2) {
            return cut.substring(0, end + 1).trim();
        }
        int space = cut.lastIndexOf(' ');
        return (space > 0 ? cut.substring(0, space) : cut).trim() + "…";
    }
}
