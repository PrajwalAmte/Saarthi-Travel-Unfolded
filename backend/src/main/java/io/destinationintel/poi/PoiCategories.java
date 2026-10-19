package io.destinationintel.poi;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * One category vocabulary for POIs from every source. Source-specific codes (OSM tags,
 * Overture category ids) are mapped here; anything unmapped is rejected rather than guessed.
 * Places of worship are only kept from OSM when they are also tagged as tourism, heritage or
 * historic sites or carry a Wikidata link, so ordinary neighbourhood shrines do not flood guides.
 */
public final class PoiCategories {

    public record Category(String key, String group) {}

    private PoiCategories() {}

    /** Category from OSM tags, or empty when the object is not a relevant POI. */
    public static Optional<Category> fromOsm(Map<String, String> t) {
        String tourism = t.get("tourism");
        if (tourism != null) {
            Category c = switch (tourism) {
                case "hotel" -> new Category("hotel", "stay");
                case "guest_house" -> new Category("guest_house", "stay");
                case "hostel" -> new Category("hostel", "stay");
                case "motel" -> new Category("motel", "stay");
                case "apartment" -> new Category("serviced_apartment", "stay");
                case "camp_site" -> new Category("camp_site", "stay");
                case "chalet", "alpine_hut" -> new Category("cottage", "stay");
                case "attraction" -> new Category("attraction", "see");
                case "museum" -> new Category("museum", "see");
                case "gallery" -> new Category("gallery", "see");
                case "viewpoint" -> new Category("viewpoint", "see");
                case "zoo" -> new Category("zoo", "do");
                case "theme_park" -> new Category("theme_park", "do");
                case "aquarium" -> new Category("aquarium", "do");
                default -> null;
            };
            if (c != null) {
                return Optional.of(c);
            }
        }
        String historic = t.get("historic");
        if (historic != null) {
            Category c = switch (historic) {
                case "monument" -> new Category("monument", "see");
                case "memorial" -> new Category("memorial", "see");
                case "castle" -> "palace".equals(t.get("castle_type")) || "stately".equals(t.get("castle_type"))
                        ? new Category("palace", "see") : new Category("fort", "see");
                case "fort" -> new Category("fort", "see");
                case "palace" -> new Category("palace", "see");
                case "ruins" -> new Category("ruins", "see");
                case "archaeological_site" -> new Category("archaeological_site", "see");
                case "tomb" -> new Category("tomb", "see");
                case "city_gate" -> new Category("city_gate", "see");
                case "temple", "church", "mosque" -> new Category("heritage_place_of_worship", "see");
                case "heritage", "building" -> t.containsKey("heritage") || t.containsKey("wikidata")
                        ? new Category("heritage_site", "see") : null;
                default -> null;
            };
            if (c != null) {
                return Optional.of(c);
            }
        }
        String amenity = t.get("amenity");
        if (amenity != null) {
            Category c = switch (amenity) {
                case "restaurant" -> new Category("restaurant", "eat");
                case "fast_food" -> new Category("fast_food", "eat");
                case "food_court" -> new Category("food_court", "eat");
                case "ice_cream" -> new Category("ice_cream", "eat");
                case "cafe" -> new Category("cafe", "drink");
                case "bar", "biergarten" -> new Category("bar", "drink");
                case "pub" -> new Category("pub", "drink");
                case "nightclub" -> new Category("nightclub", "drink");
                case "marketplace" -> new Category("market", "buy");
                case "theatre" -> new Category("theatre", "do");
                case "arts_centre" -> new Category("arts_centre", "do");
                case "bus_station" -> new Category("bus_station", "transport");
                case "ferry_terminal" -> new Category("ferry_terminal", "transport");
                case "place_of_worship" -> t.containsKey("heritage") || t.containsKey("wikidata")
                        || t.containsKey("historic") ? new Category("place_of_worship", "see") : null;
                default -> null;
            };
            if (c != null) {
                return Optional.of(c);
            }
        }
        String leisure = t.get("leisure");
        if (leisure != null) {
            Category c = switch (leisure) {
                case "park" -> new Category("park", "see");
                case "garden" -> "private".equals(t.get("access")) ? null : new Category("garden", "see");
                case "nature_reserve" -> new Category("nature_reserve", "see");
                case "water_park" -> new Category("water_park", "do");
                default -> null;
            };
            if (c != null) {
                return Optional.of(c);
            }
        }
        if ("national_park".equals(t.get("boundary"))) {
            return Optional.of(new Category("nature_reserve", "see"));
        }
        String natural = t.get("natural");
        if ("beach".equals(natural)) {
            return Optional.of(new Category("beach", "see"));
        }
        if ("waterfall".equals(natural) || "waterfall".equals(t.get("waterway"))) {
            return Optional.of(new Category("waterfall", "see"));
        }
        if (("peak".equals(natural) || "cave_entrance".equals(natural)) && t.containsKey("wikidata")) {
            return Optional.of(new Category("peak".equals(natural) ? "peak" : "cave", "see"));
        }
        String shop = t.get("shop");
        if (shop != null) {
            Category c = switch (shop) {
                case "bakery" -> new Category("bakery", "eat");
                case "confectionery", "pastry", "sweets" -> new Category("sweet_shop", "eat");
                case "mall", "department_store" -> new Category("mall", "buy");
                case "craft", "handicraft", "art" -> new Category("handicrafts", "buy");
                default -> null;
            };
            if (c != null) {
                return Optional.of(c);
            }
        }
        if ("station".equals(t.get("railway")) || "halt".equals(t.get("railway"))) {
            boolean metro = "subway".equals(t.get("station")) || "light_rail".equals(t.get("station"))
                    || "yes".equals(t.get("subway"));
            return Optional.of(metro ? new Category("metro_station", "transport") : new Category("railway_station", "transport"));
        }
        if ("aerodrome".equals(t.get("aeroway")) && (t.containsKey("iata") || "international".equals(t.get("aerodrome")))) {
            return Optional.of(new Category("airport", "transport"));
        }
        return Optional.empty();
    }

    private static Category c(String key, String group) {
        return new Category(key, group);
    }

    /**
     * Overture schema 2 categories (taxonomy.primary, falling back to basic_category), matched
     * exactly. Codes not listed are out of scope: services, offices, shops in general, sports
     * clubs, cinemas and ordinary places of worship (Overture carries no heritage signal for
     * them; heritage temples come from Wikidata and ASI instead).
     */
    private static final Map<String, Category> OVERTURE = Map.ofEntries(
            Map.entry("restaurant", c("restaurant", "eat")),
            Map.entry("fast_food_restaurant", c("fast_food", "eat")),
            Map.entry("food_truck_stand", c("fast_food", "eat")),
            Map.entry("food_court", c("food_court", "eat")),
            Map.entry("diner", c("restaurant", "eat")),
            Map.entry("sandwich_shop", c("fast_food", "eat")),
            Map.entry("bakery", c("bakery", "eat")),
            Map.entry("ice_cream_shop", c("ice_cream", "eat")),
            Map.entry("gelato_shop", c("ice_cream", "eat")),
            Map.entry("dessert_shop", c("sweet_shop", "eat")),
            Map.entry("indian_sweets_shop", c("sweet_shop", "eat")),
            Map.entry("candy_store", c("sweet_shop", "eat")),
            Map.entry("cafe", c("cafe", "drink")),
            Map.entry("coffee_shop", c("cafe", "drink")),
            Map.entry("tea_room", c("cafe", "drink")),
            Map.entry("smoothie_juice_bar", c("juice_bar", "drink")),
            Map.entry("bar", c("bar", "drink")),
            Map.entry("cocktail_bar", c("bar", "drink")),
            Map.entry("beer_bar", c("bar", "drink")),
            Map.entry("wine_bar", c("bar", "drink")),
            Map.entry("sports_bar", c("bar", "drink")),
            Map.entry("lounge", c("bar", "drink")),
            Map.entry("gastropub", c("pub", "drink")),
            Map.entry("pub", c("pub", "drink")),
            Map.entry("brewery", c("bar", "drink")),
            Map.entry("dance_club", c("nightclub", "drink")),
            Map.entry("hotel", c("hotel", "stay")),
            Map.entry("motel", c("motel", "stay")),
            Map.entry("inn", c("hotel", "stay")),
            Map.entry("resort", c("resort", "stay")),
            Map.entry("beach_resort", c("resort", "stay")),
            Map.entry("hostel", c("hostel", "stay")),
            Map.entry("lodge", c("lodge", "stay")),
            Map.entry("lodging", c("lodge", "stay")),
            Map.entry("guest_house", c("guest_house", "stay")),
            Map.entry("bed_and_breakfast", c("guest_house", "stay")),
            Map.entry("holiday_rental_home", c("holiday_rental", "stay")),
            Map.entry("service_apartment", c("serviced_apartment", "stay")),
            Map.entry("cottage", c("cottage", "stay")),
            Map.entry("cabin", c("cottage", "stay")),
            Map.entry("campground", c("camp_site", "stay")),
            Map.entry("museum", c("museum", "see")),
            Map.entry("history_museum", c("museum", "see")),
            Map.entry("science_museum", c("museum", "see")),
            Map.entry("art_museum", c("museum", "see")),
            Map.entry("art_gallery", c("gallery", "see")),
            Map.entry("planetarium", c("museum", "see")),
            Map.entry("observatory", c("attraction", "see")),
            Map.entry("historic_site", c("heritage_site", "see")),
            Map.entry("fort", c("fort", "see")),
            Map.entry("palace", c("palace", "see")),
            Map.entry("monument", c("monument", "see")),
            Map.entry("sculpture_statue", c("monument", "see")),
            Map.entry("lighthouse", c("attraction", "see")),
            Map.entry("park", c("park", "see")),
            Map.entry("national_park", c("nature_reserve", "see")),
            Map.entry("nature_reserve", c("nature_reserve", "see")),
            Map.entry("wildlife_sanctuary", c("nature_reserve", "see")),
            Map.entry("botanical_garden", c("garden", "see")),
            Map.entry("garden", c("garden", "see")),
            Map.entry("beach", c("beach", "see")),
            Map.entry("waterfall", c("waterfall", "see")),
            Map.entry("lake", c("lake", "see")),
            Map.entry("cave", c("cave", "see")),
            Map.entry("amusement_park", c("theme_park", "do")),
            Map.entry("water_park", c("water_park", "do")),
            Map.entry("zoo", c("zoo", "do")),
            Map.entry("aquarium", c("aquarium", "do")),
            Map.entry("theatre_venue", c("theatre", "do")),
            Map.entry("performing_arts_venue", c("theatre", "do")),
            Map.entry("cultural_center", c("arts_centre", "do")),
            Map.entry("boat_tour", c("tour", "do")),
            Map.entry("shopping_mall", c("mall", "buy")),
            Map.entry("department_store", c("mall", "buy")),
            Map.entry("farmers_market", c("market", "buy")),
            Map.entry("public_market", c("market", "buy")),
            Map.entry("flea_market", c("market", "buy")),
            Map.entry("craft_store", c("handicrafts", "buy")),
            Map.entry("souvenir_shop", c("handicrafts", "buy")),
            Map.entry("train_station", c("railway_station", "transport")),
            Map.entry("metro_station", c("metro_station", "transport")),
            Map.entry("bus_station", c("bus_station", "transport")),
            Map.entry("airport", c("airport", "transport")),
            Map.entry("ferry_terminal", c("ferry_terminal", "transport")));

    /** Overture taxonomy codes that must not be mapped through their broader basic category. */
    private static final java.util.Set<String> OVERTURE_EXCLUDED = java.util.Set.of("internet_cafe", "hookah_bar",
            "hotel_bar", "airport_lounge", "ski_resort", "health_market", "airport_terminal", "drive_in_theater",
            "chocolatier", "cupcake_shop", "donut_shop", "delicatessen", "tapas_bar", "distillery", "winery");

    /**
     * Category from an Overture place's {@code taxonomy.primary} code (e.g. "indian_restaurant"),
     * falling back to its {@code basic_category} ("restaurant"). Unknown codes are rejected.
     */
    public static Optional<Category> fromOverture(String taxonomyPrimary, String basicCategory) {
        String primary = taxonomyPrimary == null ? null : taxonomyPrimary.toLowerCase(Locale.ROOT).trim();
        if (primary != null && OVERTURE_EXCLUDED.contains(primary)) {
            return Optional.empty();
        }
        if (primary != null && OVERTURE.containsKey(primary)) {
            return Optional.of(OVERTURE.get(primary));
        }
        String basic = basicCategory == null ? null : basicCategory.toLowerCase(Locale.ROOT).trim();
        // Specific restaurant cuisines ("chinese_restaurant") share the basic category "restaurant".
        if ("restaurant".equals(basic) || (primary != null && primary.endsWith("_restaurant") && basic == null)) {
            return Optional.of(OVERTURE.get("restaurant"));
        }
        if ("bar".equals(basic) || "hotel".equals(basic) || "museum".equals(basic) || "resort".equals(basic)
                || "zoo".equals(basic)) {
            return Optional.of(OVERTURE.get(basic));
        }
        return Optional.empty();
    }

    /** Whether two category groups can describe the same real place (cafés are listed under eat or drink). */
    public static boolean compatibleGroups(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        return (a.equals("eat") || a.equals("drink")) && (b.equals("eat") || b.equals("drink"))
                || (a.equals("see") || a.equals("do")) && (b.equals("see") || b.equals("do"));
    }

    /** Guide-entry section to POI category group. */
    public static String groupForEntrySection(String section) {
        return switch (section) {
            case "eat" -> "eat";
            case "drink" -> "drink";
            case "stay" -> "stay";
            case "see" -> "see";
            case "do" -> "do";
            case "buy" -> "buy";
            default -> null;
        };
    }
}
