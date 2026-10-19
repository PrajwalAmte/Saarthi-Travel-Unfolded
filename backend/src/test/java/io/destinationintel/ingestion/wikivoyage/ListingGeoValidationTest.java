package io.destinationintel.ingestion.wikivoyage;

import static org.assertj.core.api.Assertions.assertThat;

import io.destinationintel.place.PlaceRow;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ListingGeoValidationTest {

    private static PlaceRow place(String kind, Double lat, Double lon) {
        return new PlaceRow(UUID.randomUUID(), "x", "X", kind, null, lat, lon, null, null, null, null, null, true,
                "active", "wikidata", null, null);
    }

    @Test
    void flagsListingCoordinatesThatCannotBelongToTheDestination() {
        PlaceRow bengaluru = place("city", 12.9716, 77.5946);
        assertThat(WikivoyageGuideHandler.geoFlag(bengaluru, 12.9452, 77.5713)).as("Basavanagudi, 4 km").isNull();
        // A Puri temple listed under Bengaluru (about 1,150 km away) is a source error.
        assertThat(WikivoyageGuideHandler.geoFlag(bengaluru, 19.8048, 85.8179)).isEqualTo("far_from_destination");
        assertThat(WikivoyageGuideHandler.geoFlag(bengaluru, 51.5, -0.12)).isEqualTo("outside_india");
        assertThat(WikivoyageGuideHandler.geoFlag(bengaluru, null, null)).isNull();
    }

    @Test
    void largeDestinationsAllowMoreDistance() {
        PlaceRow karnataka = place("state", 15.3173, 75.7139);
        assertThat(WikivoyageGuideHandler.geoFlag(karnataka, 12.3052, 76.6552)).as("Mysuru in Karnataka").isNull();
        assertThat(WikivoyageGuideHandler.geoFlag(place("city", null, null), 19.8, 85.8)).as("no reference point").isNull();
    }
}
