package io.destinationintel.poi;

import io.destinationintel.common.UrlSafety;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Shared record validation and normalisation for all POI sources. Records that fail
 * structural checks are rejected with a reason; invalid optional attributes (a malformed
 * website, an unusable phone number) are dropped and counted, not guessed.
 */
public final class PoiNormalizer {


    public sealed interface Result permits Accepted, Rejected {}

    public record Accepted(NormalizedPoi poi, List<String> attributeWarnings) implements Result {}

    public record Rejected(String reason) implements Result {}

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\t]]");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
    private static final Pattern HAS_LETTER = Pattern.compile("\\p{L}");
    private static final Pattern PHONE_DIGITS = Pattern.compile("\\d");
    private static final Set<String> GENERIC_NAMES = Set.of("hotel", "restaurant", "cafe", "café", "temple", "park",
            "museum", "bar", "hostel", "lodge", "dhaba", "bus stand", "bus station", "railway station", "market",
            "garden", "beach", "monument", "fort", "tea stall", "bakery", "mall", "guest house", "resort", "shop",
            "auditorium", "amphitheatre", "open air theatre", "tea shop", "coffee shop", "juice shop", "canteen", "mess",
            "children's park", "children park", "childrens park", "kids park", "public park", "playground", "ground",
            "bus stop", "fast food", "food court", "toddy shop", "thattukada", "bed & food", "camp", "campsite",
            "camping", "base camp", "shelter", "sweet shop", "ice cream", "ice cream parlour", "cafeteria", "parking",
            "hotel and restaurant", "hotel & restaurant", "bar and restaurant", "bar & restaurant", "lodging", "view point",
            "viewpoint", "temple pond", "museum shop", "waterfall", "lake", "tourist place", "picnic spot");
    /** Features carrying a travel category in the source that are not open to travellers. */
    private static final Pattern NOT_FOR_TRAVELLERS_HOSTEL = Pattern.compile(
            "(?i)\\b(girls|boys|ladies|gents|men'?s|women'?s|students?|working women'?s?|nurses|staff|pg)\\b.*\\bhostel\\b|"
                    + "\\bhostel\\b.*\\b(girls|boys|ladies|gents|students?|block|no\\.?\\s*\\d)");
    private static final Pattern NOT_FOR_TRAVELLERS_CAMP = Pattern.compile("(?i)\\b(shepherd'?s?|shepher|army|military|labour|labor)\\b");

    private PoiNormalizer() {}

    /** India's bounding box, including the island territories; defined once in {@link io.destinationintel.common.GeoPlausibility}. */
    public static boolean insideIndia(double lat, double lon) {
        return io.destinationintel.common.GeoPlausibility.insideIndia(lat, lon);
    }

    public static Result normalize(RawPoi r) {
        if (r.externalId() == null || r.externalId().isBlank()) {
            return new Rejected("missing_source_id");
        }
        if (r.category() == null) {
            return new Rejected("unmapped_category");
        }
        if (r.latitude() == null || r.longitude() == null || r.latitude().isNaN() || r.longitude().isNaN()
                || Math.abs(r.latitude()) > 90 || Math.abs(r.longitude()) > 180) {
            return new Rejected("invalid_coordinates");
        }
        if (r.latitude() == 0 && r.longitude() == 0) {
            return new Rejected("null_island_coordinates");
        }
        if (!insideIndia(r.latitude(), r.longitude())) {
            return new Rejected("outside_india");
        }
        String name = cleanName(r.name());
        if (name == null) {
            return new Rejected("missing_name");
        }
        if (name.indexOf('�') >= 0) {
            return new Rejected("malformed_text");
        }
        if (!HAS_LETTER.matcher(name).find()) {
            return new Rejected("name_without_letters");
        }
        if (name.length() > 200) {
            return new Rejected("name_too_long");
        }
        if (GENERIC_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
            return new Rejected("generic_name");
        }
        String key = r.category().key();
        if ((key.equals("hostel") && NOT_FOR_TRAVELLERS_HOSTEL.matcher(name).find())
                || (key.equals("camp_site") && NOT_FOR_TRAVELLERS_CAMP.matcher(name).find())) {
            return new Rejected("not_for_travellers");
        }

        List<String> warnings = new ArrayList<>();
        Map<String, Object> attrs = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : r.attributes().entrySet()) {
            Object v = e.getValue();
            if (v == null) {
                continue;
            }
            switch (e.getKey()) {
                case "website" -> UrlSafety.normalize(String.valueOf(v)).ifPresentOrElse(
                        u -> attrs.put("website", u), () -> warnings.add("invalid_website"));
                case "phone" -> {
                    String phone = cleanPhone(String.valueOf(v));
                    if (phone == null) {
                        warnings.add("invalid_phone");
                    } else {
                        attrs.put("phone", phone);
                    }
                }
                case "opening_hours" -> {
                    String hours = cleanText(String.valueOf(v), 255);
                    if (hours != null) {
                        attrs.put("opening_hours", hours);
                    }
                }
                default -> {
                    if (v instanceof String s) {
                        String clean = cleanText(s, 500);
                        if (clean != null) {
                            attrs.put(e.getKey(), clean);
                        }
                    } else {
                        attrs.put(e.getKey(), v);
                    }
                }
            }
        }
        String wikidata = r.wikidataId() != null && r.wikidataId().trim().matches("^Q[0-9]+$") ? r.wikidataId().trim() : null;
        if (r.wikidataId() != null && wikidata == null) {
            warnings.add("invalid_wikidata_id");
        }
        double lat = Math.round(r.latitude() * 1e7) / 1e7;
        double lon = Math.round(r.longitude() * 1e7) / 1e7;
        String hash = hash(name + "|" + r.category().key() + "|" + lat + "|" + lon + "|" + wikidata + "|" + new TreeMap<>(attrs));
        return new Accepted(new NormalizedPoi(r.sourceId(), r.externalId(), r.sourceVersion(), r.sourceTimestamp(),
                r.datasetVersion(), name, r.categoryRaw(), r.category().key(), r.category().group(), lat, lon, wikidata,
                attrs, r.raw(), hash), warnings);
    }

    static String cleanName(String raw) {
        if (raw == null) {
            return null;
        }
        String s = Normalizer.normalize(raw, Normalizer.Form.NFC);
        s = CONTROL.matcher(s).replaceAll(" ");
        s = MULTI_SPACE.matcher(s).replaceAll(" ").trim();
        return s.isEmpty() ? null : s;
    }

    static String cleanText(String raw, int max) {
        String s = cleanName(raw);
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** Keeps the first plausible phone number: at least 6 digits, only digits, spaces, + and -. */
    static String cleanPhone(String raw) {
        for (String part : raw.split("[;,/]")) {
            String p = part.trim().replaceAll("[^0-9+\\- ]", "").replaceAll("\\s+", " ").trim();
            long digits = PHONE_DIGITS.matcher(p).results().count();
            if (digits >= 6 && digits <= 15) {
                return p;
            }
        }
        return null;
    }

    static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
