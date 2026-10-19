package io.destinationintel.ingestion.osm;

import io.destinationintel.poi.PoiCategories;
import io.destinationintel.poi.RawPoi;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Maps OSM tags of one element to a {@link RawPoi}. Pure; all original tags are kept as {@code raw}. */
public final class OsmPoiMapper {

    public static final String SOURCE_ID = "openstreetmap";

    /** Tag keys whose presence makes an element worth decoding. */
    public static final Set<String> INTERESTING_KEYS = Set.of("amenity", "tourism", "historic", "leisure", "shop", "natural",
            "waterway", "railway", "aeroway", "place", "heritage", "boundary");

    private OsmPoiMapper() {}

    public static Optional<PoiCategories.Category> category(Map<String, String> tags) {
        return PoiCategories.fromOsm(tags);
    }

    public static RawPoi map(String type, long id, double lat, double lon, Map<String, String> tags, int version,
                             long timestampSeconds, String datasetVersion) {
        PoiCategories.Category category = PoiCategories.fromOsm(tags).orElse(null);
        String local = tags.get("name");
        String english = tags.get("name:en");
        String name = english != null && !english.isBlank() ? english : local;
        Map<String, Object> attrs = new LinkedHashMap<>();
        if (category != null && category.key().equals("hotel") && looksLikeEateryOnly(name)) {
            // In Indian usage "hotel" often means an eatery, and such places are frequently tagged
            // tourism=hotel. Only names that say eatery and nothing about lodging are moved.
            category = new PoiCategories.Category("restaurant", "eat");
            attrs.put("category_note", "tagged tourism=hotel; the name describes an eatery");
        }
        if (local != null && name != null && !local.equals(name)) {
            attrs.put("local_name", local);
        }
        put(attrs, "website", first(tags, "website", "contact:website", "url"));
        put(attrs, "phone", first(tags, "phone", "contact:phone", "contact:mobile"));
        put(attrs, "opening_hours", tags.get("opening_hours"));
        put(attrs, "cuisine", tags.get("cuisine"));
        put(attrs, "wheelchair", tags.get("wheelchair"));
        put(attrs, "stars", tags.get("stars"));
        put(attrs, "brand", tags.get("brand"));
        put(attrs, "heritage", tags.get("heritage"));
        put(attrs, "heritage_operator", tags.get("heritage:operator"));
        put(attrs, "asi_ref", first(tags, "ref:asi", "asi:ref"));
        put(attrs, "iata", tags.get("iata"));
        put(attrs, "fee", tags.get("fee"));
        String address = address(tags);
        put(attrs, "address", address);
        put(attrs, "postcode", tags.get("addr:postcode"));
        OffsetDateTime ts = timestampSeconds > 0 ? OffsetDateTime.ofInstant(Instant.ofEpochSecond(timestampSeconds), ZoneOffset.UTC) : null;
        String categoryRaw = category == null ? null : rawCategory(tags);
        return new RawPoi(SOURCE_ID, type + "/" + id, String.valueOf(version), ts, datasetVersion, name, categoryRaw, category,
                lat, lon, tags.get("wikidata"), attrs, new TreeMap<>(tags));
    }

    private static final java.util.regex.Pattern EATERY = java.util.regex.Pattern.compile(
            "(?i)\\b(restaurant|restaurent|dhaba|mess|tiffins?|idli|idly|dosa|biryani|biriyani|bhojanalaya|bhojnalaya|canteen|"
                    + "cafe|caf\u00e9|bakery|sweets|juice|food|fast food|meals|kitchen|eatery|chaat)\\b");
    private static final java.util.regex.Pattern LODGING = java.util.regex.Pattern.compile(
            "(?i)\\b(hotel|lodge|lodging|inn|residency|resort|rooms?|guest ?house|stay|homestay|hostel|suites?|regency|"
                    + "palace|retreat|cottages?|villa|boarding)\\b");

    static boolean looksLikeEateryOnly(String name) {
        return name != null && EATERY.matcher(name).find() && !LODGING.matcher(name).find();
    }

    static String rawCategory(Map<String, String> t) {
        for (String k : new String[] {"tourism", "historic", "amenity", "leisure", "natural", "waterway", "shop", "railway", "aeroway"}) {
            if (t.containsKey(k)) {
                return k + "=" + t.get(k);
            }
        }
        return null;
    }

    static String address(Map<String, String> t) {
        StringBuilder sb = new StringBuilder();
        String house = t.get("addr:housenumber");
        String street = t.get("addr:street");
        if (street != null) {
            sb.append(house == null ? "" : house + " ").append(street);
        }
        for (String k : new String[] {"addr:suburb", "addr:place", "addr:city"}) {
            String v = t.get(k);
            if (v != null && sb.indexOf(v) < 0) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(v);
            }
        }
        if (sb.length() == 0 && t.get("addr:full") != null) {
            sb.append(t.get("addr:full"));
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String first(Map<String, String> t, String... keys) {
        for (String k : keys) {
            String v = t.get(k);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static void put(Map<String, Object> m, String k, String v) {
        if (v != null && !v.isBlank()) {
            m.put(k, v);
        }
    }
}
