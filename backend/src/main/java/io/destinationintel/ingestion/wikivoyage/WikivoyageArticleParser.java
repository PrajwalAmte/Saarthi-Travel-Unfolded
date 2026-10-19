package io.destinationintel.ingestion.wikivoyage;

import io.destinationintel.ingestion.wikivoyage.WikiText.Template;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a Wikivoyage article into sections, listings, region lists and specialities.
 * Pure and deterministic: the same wikitext always yields the same structure.
 */
public final class WikivoyageArticleParser {

    /** Bump when parsing rules change so unchanged revisions are re-processed. */
    public static final int VERSION = 4;

    public static final Set<String> LISTING_TEMPLATES = Set.of("see", "do", "buy", "eat", "drink", "sleep", "go", "listing");

    public record Section(int level, String heading, String topHeading, String subHeading, String body, int order) {}

    public record Listing(String type, String topHeading, String subHeading, Map<String, String> fields, int order) {
        public String field(String key) {
            String v = fields.get(key);
            return v == null || v.isBlank() ? null : v.trim();
        }
    }

    public record Region(String label, String linkTarget, String description, List<String> items) {}

    public record Speciality(String name, String description, String topHeading) {}

    public record ParsedArticle(
            String lead,
            List<Section> sections,
            List<Listing> listings,
            List<Region> regions,
            List<Speciality> specialities,
            List<String> isPartOf,
            String articleStatus,
            List<String> foodTerms) {}

    private static final Pattern HEADING = Pattern.compile("^(={2,6})\\s*(.+?)\\s*\\1\\s*$", Pattern.MULTILINE);
    private static final Pattern SPECIALITY_LINE = Pattern.compile(
            "^\\*+[ \\t]*'''[ \\t]*([^'\\n]{2,60}?)[ \\t]*'''[ \\t]*(?:\\([^)\\n]{0,80}\\)[ \\t]*)?[-–—:,]?[ \\t]*(.*)$",
            Pattern.MULTILINE);
    /** Sub-headings under Eat/Drink/Buy whose bold bullets describe dishes or products, not venues. */
    private static final Pattern SPECIALITY_HEADING = Pattern.compile(
            "(?i)special|cuisine|dish|local food|sweets|snacks|delicac|what to (eat|try|buy)|souvenir|products|handicraft");
    /** Venue-like wording near the start of a bullet description (addresses, directions, business types). */
    private static final Pattern VENUE_START = Pattern.compile(
            "(?i)^.{0,40}\\b(road|rd|opposite|opp|complex|near|hotel|restaurant|caf[eé]|pub|bar|mess|bakery|shop|mall"
                    + "|street|circle|nagar|layout|cross|main|junction)\\b|^(it )?is a (small |nice )?(restaurant|place|caf)");
    private static final Pattern BOLD_TERM = Pattern.compile("'''([^'\\n]{3,40})'''");
    private static final Pattern STATUS = Pattern.compile(
            "^(outline|usable|guide|star)(city|region|park|district|airport|itinerary|topic|country|)$");

    private WikivoyageArticleParser() {}

    public static ParsedArticle parse(String wikitext) {
        String text = wikitext.replace("\r\n", "\n");
        List<int[]> headingPositions = new ArrayList<>();
        List<String> headingTexts = new ArrayList<>();
        List<Integer> headingLevels = new ArrayList<>();
        Matcher hm = HEADING.matcher(text);
        while (hm.find()) {
            headingPositions.add(new int[] {hm.start(), hm.end()});
            headingTexts.add(WikiText.toPlainInline(hm.group(2)));
            headingLevels.add(hm.group(1).length());
        }

        String lead = headingPositions.isEmpty() ? text : text.substring(0, headingPositions.get(0)[0]);
        List<Section> sections = new ArrayList<>();
        List<Listing> listings = new ArrayList<>();
        List<Region> regions = new ArrayList<>();
        List<Speciality> specialities = new ArrayList<>();
        List<String> foodTerms = new ArrayList<>();

        String top = null;
        String sub = null;
        int order = 0;
        collectListings(lead, null, null, listings);
        regions.addAll(regionLists(lead));
        for (int h = 0; h < headingPositions.size(); h++) {
            int level = headingLevels.get(h);
            String heading = headingTexts.get(h);
            if (level == 2) {
                top = heading;
                sub = null;
            } else if (level == 3) {
                sub = heading;
            }
            int bodyStart = headingPositions.get(h)[1];
            int bodyEnd = h + 1 < headingPositions.size() ? headingPositions.get(h + 1)[0] : text.length();
            String body = text.substring(bodyStart, bodyEnd);
            sections.add(new Section(level, heading, top, level == 2 ? null : sub, body, order++));
            collectListings(body, top, level == 2 ? null : sub, listings);
            regions.addAll(regionLists(body));
            String topKey = top == null ? "" : top.toLowerCase(Locale.ROOT);
            if (level == 2 && (topKey.equals("eat") || topKey.equals("drink"))) {
                Matcher bm = BOLD_TERM.matcher(stripListingTemplates(body));
                while (bm.find()) {
                    String term = WikiText.toPlainInline(bm.group(1));
                    if (term.length() >= 4 && !foodTerms.contains(term)) {
                        foodTerms.add(term);
                    }
                }
            }
            boolean specialityContext = level == 2 || sub != null && SPECIALITY_HEADING.matcher(sub).find();
            if ((topKey.equals("eat") || topKey.equals("drink") || topKey.equals("buy")) && specialityContext) {
                Matcher sm = SPECIALITY_LINE.matcher(stripListingTemplates(body));
                while (sm.find()) {
                    String name = WikiText.toPlainInline(sm.group(1));
                    String desc = WikiText.toPlainInline(sm.group(2));
                    if (name.length() >= 3 && desc.length() >= 25 && !VENUE_START.matcher(desc).find()) {
                        specialities.add(new Speciality(name, desc, top));
                    }
                }
            }
        }

        List<String> partOf = new ArrayList<>();
        String status = null;
        for (Template t : WikiText.templates(text)) {
            if (t.name().equals("ispartof") && !t.positional().isEmpty()) {
                partOf.add(t.positional().get(0).trim());
            }
            Matcher sm = STATUS.matcher(t.name().replace(" ", ""));
            if (status == null && sm.matches()) {
                status = sm.group(1);
            }
        }
        return new ParsedArticle(lead, sections, listings, regions, specialities, partOf, status, foodTerms);
    }

    private static void collectListings(String body, String top, String sub, List<Listing> out) {
        for (Template t : WikiText.templates(body)) {
            if (!LISTING_TEMPLATES.contains(t.name())) {
                continue;
            }
            Map<String, String> fields = new LinkedHashMap<>(t.params());
            String type = t.name().equals("listing") ? typeFromHeading(top, fields.get("type")) : t.name();
            if (type == null) {
                continue;
            }
            out.add(new Listing(type, top, sub, fields, out.size()));
        }
    }

    static String typeFromHeading(String top, String explicitType) {
        if (explicitType != null && LISTING_TEMPLATES.contains(explicitType.trim().toLowerCase(Locale.ROOT))) {
            return explicitType.trim().toLowerCase(Locale.ROOT);
        }
        if (top == null) {
            return null;
        }
        return switch (top.toLowerCase(Locale.ROOT)) {
            case "see" -> "see";
            case "do" -> "do";
            case "buy" -> "buy";
            case "eat" -> "eat";
            case "drink" -> "drink";
            case "sleep" -> "sleep";
            default -> null;
        };
    }

    private static List<Region> regionLists(String body) {
        List<Region> out = new ArrayList<>();
        for (Template t : WikiText.templates(body)) {
            if (!t.name().equals("regionlist")) {
                continue;
            }
            for (int n = 1; n <= 40; n++) {
                String rawName = t.param("region" + n + "name");
                if (rawName == null || rawName.isBlank()) {
                    continue;
                }
                String label = WikiText.toPlainInline(rawName);
                String target = WikiText.firstLinkTarget(rawName);
                String description = WikiText.toPlainInline(t.param("region" + n + "description") == null ? ""
                        : t.param("region" + n + "description"));
                List<String> items = new ArrayList<>();
                String rawItems = t.param("region" + n + "items");
                if (rawItems != null) {
                    for (String item : WikiText.toPlainInline(rawItems).split(",")) {
                        String it = item.trim();
                        if (!it.isEmpty() && it.length() <= 80) {
                            items.add(it);
                        }
                    }
                }
                if (!label.isBlank()) {
                    out.add(new Region(label, target, description, items));
                }
            }
        }
        return out;
    }

    private static String stripListingTemplates(String body) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        for (Template t : WikiText.templates(body)) {
            out.append(body, i, t.start());
            if (!LISTING_TEMPLATES.contains(t.name())) {
                out.append(body, t.start(), t.end());
            }
            i = t.end();
        }
        out.append(body.substring(Math.min(i, body.length())));
        return out.toString();
    }

    /** Prose of a section with listings, region lists and nested templates removed. */
    public static String prose(String body) {
        return WikiText.toPlain(stripListingTemplates(body));
    }
}
