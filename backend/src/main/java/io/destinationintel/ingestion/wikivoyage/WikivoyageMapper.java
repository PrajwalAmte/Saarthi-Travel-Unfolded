package io.destinationintel.ingestion.wikivoyage;

import io.destinationintel.common.Slugs;
import io.destinationintel.common.UrlSafety;
import io.destinationintel.guide.PriceParser;
import io.destinationintel.guide.PriceParser.ParsedPrice;
import io.destinationintel.ingestion.GuideWriter.EntryUpsert;
import io.destinationintel.ingestion.GuideWriter.EvidenceUpsert;
import io.destinationintel.ingestion.GuideWriter.FactUpsert;
import io.destinationintel.ingestion.wikivoyage.WikivoyageArticleParser.Listing;
import io.destinationintel.ingestion.wikivoyage.WikivoyageArticleParser.ParsedArticle;
import io.destinationintel.ingestion.wikivoyage.WikivoyageArticleParser.Region;
import io.destinationintel.ingestion.wikivoyage.WikivoyageArticleParser.Section;
import io.destinationintel.ingestion.wikivoyage.WikivoyageArticleParser.Speciality;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps a parsed Wikivoyage article to guide entries and typed facts with evidence.
 * Low-information listings (no description, price, hours or address) are skipped rather
 * than published as empty recommendations.
 */
public final class WikivoyageMapper {

    public static final String SOURCE_ID = "wikivoyage-en";
    private static final String SOURCE_NAME = "Wikivoyage";

    public record ArticleContext(
            UUID destinationId,
            String title,
            long pageId,
            long revisionId,
            OffsetDateTime revisionAt,
            String area,
            boolean includeProse,
            boolean includeRegions,
            Collection<String> specialityNames,
            Map<String, Integer> sitelinksByQid,
            int majorThreshold) {}

    public record MappedFact(FactUpsert fact, EvidenceUpsert evidence) {
        MappedFact withEntry(UUID entryId) {
            FactUpsert f = fact;
            return new MappedFact(new FactUpsert(f.destinationId(), entryId, f.section(), f.kind(), f.heading(),
                    f.valueText(), f.valueJson(), f.volatility(), f.observedAt(), f.observedBasis(), f.ingestKey()), evidence);
        }
    }

    public record MappedEntry(EntryUpsert entry, List<MappedFact> facts) {
        public List<MappedFact> factsFor(UUID entryId) {
            return facts.stream().map(f -> f.withEntry(entryId)).toList();
        }
    }

    public record MappedArticle(List<MappedEntry> entries, List<MappedFact> destinationFacts, int skippedListings) {}

    private record ProseRule(String section, String kind, String volatility) {}

    private static final Pattern RUPEE = Pattern.compile("(₹|\\bRs\\.?\\s?[0-9]|\\bINR\\b)");

    private WikivoyageMapper() {}

    public static MappedArticle map(ParsedArticle article, ArticleContext ctx) {
        List<MappedEntry> entries = new ArrayList<>();
        List<MappedFact> destinationFacts = new ArrayList<>();
        String prefix = "wv:" + ctx.pageId() + ":";

        if (ctx.includeProse()) {
            String summary = summary(article.lead());
            if (summary != null) {
                destinationFacts.add(fact(ctx, null, "overview", "summary", null, summary, null, "stable", null,
                        prefix + "summary", "wikivoyage_section_parser", summary));
            }
            for (Section s : article.sections()) {
                ProseRule rule = proseRule(s);
                if (rule == null) {
                    continue;
                }
                String text = WikivoyageArticleParser.prose(s.body());
                if (rule.section().equals("eat") || rule.section().equals("drink") || rule.section().equals("buy")) {
                    text = withoutSpecialityLines(text);
                }
                text = WikiText.truncateAtSentence(text.trim(), 1200);
                if (text.length() < 60) {
                    continue;
                }
                String key = prefix + "prose:" + Slugs.slugify(s.topHeading()) + ":"
                        + (s.subHeading() == null || s.level() == 2 ? "_intro" : Slugs.slugify(s.subHeading()))
                        + ":" + (s.level() > 3 ? Slugs.slugify(s.heading()) + ":" : "") + rule.kind();
                String heading = s.level() == 2 ? null : s.heading();
                destinationFacts.add(fact(ctx, null, rule.section(), rule.kind(), heading, text, null, rule.volatility(),
                        null, key, "wikivoyage_section_parser", text));
            }
            Map<String, Integer> specialityCounts = new HashMap<>();
            for (Speciality sp : article.specialities()) {
                String section = sp.topHeading().toLowerCase(Locale.ROOT).equals("buy") ? "buy"
                        : sp.topHeading().toLowerCase(Locale.ROOT).equals("drink") ? "drink" : "eat";
                String slug = Slugs.slugify(sp.name());
                int n = specialityCounts.merge(slug, 1, Integer::sum);
                String text = sp.name() + " — " + WikiText.truncateAtSentence(sp.description(), 600);
                destinationFacts.add(fact(ctx, null, section, "speciality", sp.name(), text, null, "stable", null,
                        prefix + "speciality:" + section + ":" + slug + (n > 1 ? ":" + n : ""),
                        "wikivoyage_section_parser", text));
            }
        }

        if (ctx.includeRegions()) {
            for (Region r : article.regions()) {
                String key = prefix + "region:" + Slugs.slugify(r.label());
                Map<String, Object> attrs = new LinkedHashMap<>();
                if (!r.items().isEmpty()) {
                    attrs.put("localities", r.items());
                }
                if (r.linkTarget() != null) {
                    attrs.put("sourceArticle", r.linkTarget());
                }
                String description = !r.description().isBlank() ? r.description()
                        : r.items().isEmpty() ? null : "Areas included: " + String.join(", ", r.items()) + ".";
                if (description == null) {
                    continue;
                }
                EntryUpsert entry = new EntryUpsert(ctx.destinationId(), "neighbourhood", null, null, r.label(), null,
                        null, null, null, null, attrs, key);
                List<MappedFact> facts = List.of(fact(ctx, null, "neighbourhood", "neighbourhood_character", null,
                        description, null, "stable", null, key + ":description", "wikivoyage_section_parser", description));
                entries.add(new MappedEntry(entry, facts));
            }
        }

        int skipped = 0;
        Map<String, Integer> occurrences = new HashMap<>();
        for (Listing l : article.listings()) {
            Optional<MappedEntry> mapped = mapListing(l, ctx, prefix, occurrences);
            if (mapped.isPresent()) {
                entries.add(mapped.get());
            } else {
                skipped++;
            }
        }
        return new MappedArticle(entries, destinationFacts, skipped);
    }

    static Optional<MappedEntry> mapListing(Listing l, ArticleContext ctx, String prefix, Map<String, Integer> occurrences) {
        String section = switch (l.type()) {
            case "see" -> "see";
            case "do" -> "do";
            case "buy" -> "buy";
            case "eat" -> "eat";
            case "drink" -> "drink";
            case "sleep" -> "stay";
            default -> null;
        };
        if (section == null) {
            return Optional.empty();
        }
        String name = l.field("name") == null ? null : WikiText.toPlainInline(l.field("name"));
        if (name == null || name.isBlank() || name.length() > 200) {
            return Optional.empty();
        }
        String content = clean(l.field("content") != null ? l.field("content") : l.field("description"));
        String address = clean(l.field("address"));
        String directions = clean(l.field("directions"));
        String hours = clean(l.field("hours"));
        String checkin = clean(l.field("checkin"));
        String checkout = clean(l.field("checkout"));
        String priceText = clean(l.field("price"));
        String phone = clean(l.field("phone"));
        Optional<String> website = UrlSafety.normalize(l.field("url"));
        LocalDate observed = parseDate(l.field("lastedit"));

        boolean hasDescription = content != null && content.length() >= 15;
        boolean hasDetails = address != null && (priceText != null || hours != null || phone != null);
        if (!hasDescription && !hasDetails) {
            return Optional.empty();
        }

        String slug = Slugs.slugify(name);
        if (slug.isEmpty()) {
            slug = "listing";
        }
        int n = occurrences.merge(section + ":" + slug, 1, Integer::sum);
        String key = prefix + section + ":" + slug + ":" + n;

        if (priceText == null && content != null && RUPEE.matcher(content).find()) {
            priceText = priceSentences(content);
        }
        Optional<ParsedPrice> price = PriceParser.parse(priceText);
        String qid = l.field("wikidata") != null && l.field("wikidata").trim().matches("^Q[0-9]+$") ? l.field("wikidata").trim() : null;
        Integer sitelinks = qid == null ? null : ctx.sitelinksByQid().get(qid);
        Map<String, Object> attrs = AttributeDeriver.derive(section, l.subHeading(), name, content, price, observed,
                ctx.specialityNames(), sitelinks, ctx.majorThreshold(), SOURCE_NAME);

        Double lat = coordinate(l.field("lat"), 6, 37.5);
        Double lon = coordinate(l.field("long"), 68, 97.5);
        if (lat == null || lon == null) {
            lat = null;
            lon = null;
        }
        String alt = l.field("alt") == null ? null : WikiText.toPlainInline(l.field("alt"));
        EntryUpsert entry = new EntryUpsert(ctx.destinationId(), section, l.subHeading(), ctx.area(), name,
                alt == null || alt.isBlank() ? null : alt, lat, lon, qid, sitelinks, attrs, key);

        List<MappedFact> facts = new ArrayList<>();
        String basis = observed == null ? "unknown" : "source_listing_date";
        if (hasDescription) {
            String text = WikiText.truncateAtSentence(content, 1500);
            facts.add(fact(ctx, null, section, "description", null, text, null, "semi_stable", observed,
                    key + ":description", "wikivoyage_listing_parser", text, basis));
        }
        if (priceText != null) {
            final String priceRaw = priceText;
            facts.add(fact(ctx, null, section, "price", null, priceRaw, price.map(p -> p.toMap(priceRaw)).orElse(null),
                    "volatile", observed, key + ":price", "wikivoyage_listing_parser", priceRaw, basis));
        }
        String hoursText = hours;
        if (section.equals("stay") && (checkin != null || checkout != null)) {
            hoursText = (checkin == null ? "" : "Check-in: " + checkin) + (checkin != null && checkout != null ? "; " : "")
                    + (checkout == null ? "" : "Check-out: " + checkout) + (hours == null ? "" : "; " + hours);
        }
        if (hoursText != null && !hoursText.isBlank()) {
            facts.add(fact(ctx, null, section, "hours", null, hoursText, null, "volatile", observed, key + ":hours",
                    "wikivoyage_listing_parser", hoursText, basis));
        }
        if (address != null || directions != null) {
            String a = address == null ? directions : directions == null ? address : address + " (" + directions + ")";
            facts.add(fact(ctx, null, section, "address", null, a, null, "semi_stable", observed, key + ":address",
                    "wikivoyage_listing_parser", a, basis));
        }
        if (phone != null) {
            facts.add(fact(ctx, null, section, "phone", null, phone, null, "semi_stable", observed, key + ":phone",
                    "wikivoyage_listing_parser", phone, basis));
        }
        if (website.isPresent()) {
            facts.add(fact(ctx, null, section, "website", null, website.get(), Map.of("url", website.get()),
                    "semi_stable", observed, key + ":website", "wikivoyage_listing_parser", website.get(), basis));
        }
        return Optional.of(new MappedEntry(entry, facts));
    }

    static ProseRule proseRule(Section s) {
        String top = s.topHeading() == null ? "" : s.topHeading().toLowerCase(Locale.ROOT).trim();
        String sub = s.subHeading() == null ? "" : s.subHeading().toLowerCase(Locale.ROOT).trim();
        boolean intro = s.level() == 2;
        return switch (top) {
            case "understand" -> switch (sub) {
                case "climate", "weather", "when to go", "when to visit" -> s.level() == 3 ? new ProseRule("practical", "seasonality", "stable") : null;
                case "orientation", "layout" -> s.level() == 3 ? new ProseRule("practical", "orientation", "stable") : null;
                case "visitor information", "tourist information", "tourist offices", "tourist office" ->
                        s.level() == 3 ? new ProseRule("tips", "general_tip", "semi_stable") : null;
                default -> null;
            };
            case "orientation" -> new ProseRule("practical", "orientation", "stable");
            case "climate", "when to go" -> new ProseRule("practical", "seasonality", "stable");
            case "get in" -> new ProseRule("getting_there", "transport", "semi_stable");
            case "get around" -> new ProseRule("getting_around", "transport", "semi_stable");
            case "stay safe" -> new ProseRule("tips", "safety", "semi_stable");
            case "stay healthy" -> new ProseRule("tips", "health", "semi_stable");
            case "respect" -> new ProseRule("tips", "customs", "stable");
            case "connect" -> new ProseRule("tips", "connectivity", "semi_stable");
            case "cope" -> new ProseRule("tips", "general_tip", "semi_stable");
            case "talk" -> intro ? new ProseRule("tips", "general_tip", "stable") : null;
            case "eat" -> intro ? new ProseRule("eat", "general_tip", "stable") : null;
            case "drink" -> intro ? new ProseRule("drink", "general_tip", "stable") : null;
            case "buy" -> intro ? new ProseRule("buy", "general_tip", "semi_stable") : null;
            case "see" -> intro ? new ProseRule("see", "general_tip", "stable") : null;
            case "sleep" -> intro ? new ProseRule("stay", "accommodation_note", "semi_stable") : null;
            default -> null;
        };
    }

    /** First paragraph(s) of the lead, up to about 600 characters. */
    static String summary(String lead) {
        String plain = WikiText.toPlain(lead);
        StringBuilder out = new StringBuilder();
        for (String para : plain.split("\n\n")) {
            String p = para.replace('\n', ' ').trim();
            if (p.isEmpty() || p.startsWith("•")) {
                continue;
            }
            if (out.length() > 0 && out.length() + p.length() > 600) {
                break;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(p);
        }
        String s = WikiText.truncateAtSentence(out.toString().trim(), 700);
        return s.length() < 40 ? null : s;
    }

    private static String withoutSpecialityLines(String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n")) {
            if (!line.startsWith("•")) {
                out.append(line).append('\n');
            }
        }
        return out.toString().trim();
    }

    private static String priceSentences(String content) {
        StringBuilder out = new StringBuilder();
        for (String sentence : content.split("(?<=[.!?])\\s+")) {
            if (RUPEE.matcher(sentence).find()) {
                if (out.length() > 0) {
                    out.append(' ');
                }
                out.append(sentence.trim());
            }
        }
        String s = out.toString();
        return s.isBlank() ? null : WikiText.truncateAtSentence(s, 300);
    }

    private static MappedFact fact(ArticleContext ctx, UUID entryId, String section, String kind, String heading,
                                   String text, Map<String, Object> json, String volatility, LocalDate observed,
                                   String key, String method, String excerpt) {
        return fact(ctx, entryId, section, kind, heading, text, json, volatility, observed, key, method, excerpt,
                observed == null ? "unknown" : "source_listing_date");
    }

    private static MappedFact fact(ArticleContext ctx, UUID entryId, String section, String kind, String heading,
                                   String text, Map<String, Object> json, String volatility, LocalDate observed,
                                   String key, String method, String excerpt, String basis) {
        FactUpsert f = new FactUpsert(ctx.destinationId(), entryId, section, kind, heading, text, json, volatility,
                observed, basis, key);
        EvidenceUpsert ev = new EvidenceUpsert(SOURCE_ID, articleUrl(ctx.title()), permalink(ctx.title(), ctx.revisionId()),
                "Wikivoyage: " + ctx.title(), WikiText.truncateAtSentence(excerpt, 1500), method,
                String.valueOf(ctx.revisionId()), ctx.revisionAt(), observed);
        return new MappedFact(f, ev);
    }

    public static String articleUrl(String title) {
        return "https://en.wikivoyage.org/wiki/" + encodeTitle(title);
    }

    public static String permalink(String title, long revisionId) {
        return "https://en.wikivoyage.org/w/index.php?title=" + encodeTitle(title) + "&oldid=" + revisionId;
    }

    private static String encodeTitle(String title) {
        return URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8).replace("%2F", "/");
    }

    private static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String s = WikiText.toPlainInline(raw);
        return s.isBlank() ? null : s;
    }

    static LocalDate parseDate(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        try {
            if (s.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
                LocalDate d = LocalDate.parse(s);
                return d.isAfter(LocalDate.now().plusDays(1)) ? null : d;
            }
            Matcher m = Pattern.compile("^(\\d{4})-(\\d{1,2})$").matcher(s);
            if (m.matches()) {
                return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), 1);
            }
        } catch (java.time.DateTimeException e) {
            return null;
        }
        return null;
    }

    private static Double coordinate(String raw, double min, double max) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            double v = Double.parseDouble(raw.trim());
            return v >= min && v <= max ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
