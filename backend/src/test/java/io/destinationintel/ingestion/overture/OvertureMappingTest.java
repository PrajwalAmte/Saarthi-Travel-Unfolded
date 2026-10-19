package io.destinationintel.ingestion.overture;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class OvertureMappingTest {

    @Test
    void selectsOnlyCatalogFilesWhoseBoundingBoxTouchesIndia() {
        double[] southAsia = {42.149, -84.98, 106.66, 18.11};
        double[] pacific = {106.66, -84.73, 179.99, 18.11};
        double[] europe = {6.28, 41.11, 42.14, 47.38};
        assertThat(OvertureCatalog.intersects(southAsia, OvertureCatalog.INDIA)).isTrue();
        assertThat(OvertureCatalog.intersects(pacific, OvertureCatalog.INDIA)).isFalse();
        assertThat(OvertureCatalog.intersects(europe, OvertureCatalog.INDIA)).isFalse();
    }

    @Test
    void composesAddressesWithoutRepeatingParts() {
        assertThat(OverturePlacesHandler.composeAddress("32 Gandhi Bazaar Main Rd", "Bengaluru", "560004"))
                .isEqualTo("32 Gandhi Bazaar Main Rd, Bengaluru, 560004");
        assertThat(OverturePlacesHandler.composeAddress("32 Gandhi Bazaar Main Rd, Bengaluru 560004", "Bengaluru", "560004"))
                .isEqualTo("32 Gandhi Bazaar Main Rd, Bengaluru 560004");
        assertThat(OverturePlacesHandler.composeAddress(null, null, " ")).isNull();
    }

    @Test
    void parsesSourceUpdateTimesAndIgnoresGarbage() {
        assertThat(OverturePlacesHandler.parseTime("2025-03-01T10:15:30.000Z")).isEqualTo(OffsetDateTime.parse("2025-03-01T10:15:30Z"));
        assertThat(OverturePlacesHandler.parseTime("2025-03-01 10:15:30")).isEqualTo(OffsetDateTime.parse("2025-03-01T10:15:30Z"));
        assertThat(OverturePlacesHandler.parseTime("last tuesday")).isNull();
        assertThat(OverturePlacesHandler.parseTime(null)).isNull();
    }
}
