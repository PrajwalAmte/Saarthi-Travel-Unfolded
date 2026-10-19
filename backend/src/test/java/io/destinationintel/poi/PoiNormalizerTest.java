package io.destinationintel.poi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PoiNormalizerTest {

    private static final PoiCategories.Category RESTAURANT = new PoiCategories.Category("restaurant", "eat");

    private static RawPoi poi(String id, String name, Double lat, Double lon, Map<String, Object> attrs) {
        return new RawPoi("test-source", id, "1", null, "release-1", name, "restaurant", RESTAURANT, lat, lon, null,
                attrs, Map.of("name", String.valueOf(name)));
    }

    private static String reason(PoiNormalizer.Result r) {
        return r instanceof PoiNormalizer.Rejected rej ? rej.reason() : null;
    }

    @Test
    void rejectsStructurallyInvalidRecordsWithAReason() {
        assertThat(reason(PoiNormalizer.normalize(poi(null, "Udupi Grand", 12.97, 77.59, Map.of())))).isEqualTo("missing_source_id");
        assertThat(reason(PoiNormalizer.normalize(poi("n1", "Udupi Grand", null, 77.59, Map.of())))).isEqualTo("invalid_coordinates");
        assertThat(reason(PoiNormalizer.normalize(poi("n1", "Udupi Grand", 95.0, 77.59, Map.of())))).isEqualTo("invalid_coordinates");
        assertThat(reason(PoiNormalizer.normalize(poi("n1", "Udupi Grand", 0.0, 0.0, Map.of())))).isEqualTo("null_island_coordinates");
        assertThat(reason(PoiNormalizer.normalize(poi("n1", "Udupi Grand", 51.5, -0.12, Map.of())))).isEqualTo("outside_india");
        assertThat(reason(PoiNormalizer.normalize(poi("n1", "   ", 12.97, 77.59, Map.of())))).isEqualTo("missing_name");
        assertThat(reason(PoiNormalizer.normalize(poi("n1", "12345", 12.97, 77.59, Map.of())))).isEqualTo("name_without_letters");
        assertThat(reason(PoiNormalizer.normalize(poi("n1", "Restaurant", 12.97, 77.59, Map.of())))).isEqualTo("generic_name");
        assertThat(reason(PoiNormalizer.normalize(poi("n1", "Bad � name", 12.97, 77.59, Map.of())))).isEqualTo("malformed_text");
        PoiNormalizer.Result unmapped = PoiNormalizer.normalize(new RawPoi("test-source", "n1", "1", null, "r", "X Café",
                "bank", null, 12.97, 77.59, null, Map.of(), Map.of()));
        assertThat(reason(unmapped)).isEqualTo("unmapped_category");
    }

    @Test
    void skipsFeaturesThatAreNotOpenToTravellers() {
        PoiCategories.Category hostel = new PoiCategories.Category("hostel", "stay");
        PoiCategories.Category camp = new PoiCategories.Category("camp_site", "stay");
        assertThat(reason(PoiNormalizer.normalize(new RawPoi("s", "1", null, null, null, "Girls Hostel Block B", "hostel", hostel,
                12.97, 77.59, null, Map.of(), Map.of())))).isEqualTo("not_for_travellers");
        assertThat(reason(PoiNormalizer.normalize(new RawPoi("s", "2", null, null, null, "Shepherd's Camp", "camp_site", camp,
                34.1, 77.5, null, Map.of(), Map.of())))).isEqualTo("not_for_travellers");
        assertThat(PoiNormalizer.normalize(new RawPoi("s", "3", null, null, null, "Zostel Leh", "hostel", hostel,
                34.16, 77.58, null, Map.of(), Map.of()))).isInstanceOf(PoiNormalizer.Accepted.class);
        assertThat(reason(PoiNormalizer.normalize(poi("n1", "Tea Shop", 12.97, 77.59, Map.of())))).isEqualTo("generic_name");
    }

    @Test
    void dropsInvalidOptionalAttributesAndCountsThem() {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("website", "javascript:alert(1)");
        attrs.put("phone", "12");
        attrs.put("opening_hours", "Mo-Su 08:00-22:00");
        PoiNormalizer.Accepted a = (PoiNormalizer.Accepted) PoiNormalizer.normalize(poi("n1", "  Vidyarthi   Bhavan ", 12.9452, 77.5713, attrs));
        assertThat(a.poi().name()).isEqualTo("Vidyarthi Bhavan");
        assertThat(a.poi().attributes()).containsOnlyKeys("opening_hours");
        assertThat(a.attributeWarnings()).containsExactlyInAnyOrder("invalid_website", "invalid_phone");
    }

    @Test
    void contentHashIgnoresAttributeOrderButSeesRealChanges() {
        Map<String, Object> a1 = new LinkedHashMap<>();
        a1.put("phone", "+91 80 2667 7588");
        a1.put("cuisine", "south_indian");
        Map<String, Object> a2 = new LinkedHashMap<>();
        a2.put("cuisine", "south_indian");
        a2.put("phone", "+91 80 2667 7588");
        String h1 = ((PoiNormalizer.Accepted) PoiNormalizer.normalize(poi("n1", "Vidyarthi Bhavan", 12.9452, 77.5713, a1))).poi().contentHash();
        String h2 = ((PoiNormalizer.Accepted) PoiNormalizer.normalize(poi("n1", "Vidyarthi Bhavan", 12.9452, 77.5713, a2))).poi().contentHash();
        String moved = ((PoiNormalizer.Accepted) PoiNormalizer.normalize(poi("n1", "Vidyarthi Bhavan", 12.9460, 77.5713, a2))).poi().contentHash();
        assertThat(h1).isEqualTo(h2);
        assertThat(moved).isNotEqualTo(h1);
    }

    @Test
    void overtureCategoriesAreMatchedExactlyAndOrdinaryPlacesOfWorshipAreOutOfScope() {
        assertThat(PoiCategories.fromOverture("indian_restaurant", "restaurant")).contains(new PoiCategories.Category("restaurant", "eat"));
        assertThat(PoiCategories.fromOverture("coffee_shop", "coffee_shop")).contains(new PoiCategories.Category("cafe", "drink"));
        assertThat(PoiCategories.fromOverture("beach_resort", "resort")).contains(new PoiCategories.Category("resort", "stay"));
        assertThat(PoiCategories.fromOverture("palace", "historic_site")).contains(new PoiCategories.Category("palace", "see"));
        assertThat(PoiCategories.fromOverture("bus_station", "public_transit_facility_or_service"))
                .contains(new PoiCategories.Category("bus_station", "transport"));
        assertThat(PoiCategories.fromOverture("hindu_place_of_worship", "hindu_place_of_worship")).isEmpty();
        assertThat(PoiCategories.fromOverture("paint_store", "hardware_home_and_garden_store")).isEmpty();
        assertThat(PoiCategories.fromOverture("parking", "parking")).isEmpty();
        assertThat(PoiCategories.fromOverture("internet_cafe", "cafe")).as("not a café for travellers").isEmpty();
        assertThat(PoiCategories.fromOverture("airport_terminal", "airport")).isEmpty();
        assertThat(PoiCategories.fromOverture("health_market", "market")).isEmpty();
        assertThat(PoiCategories.fromOverture(null, null)).isEmpty();
    }
}
