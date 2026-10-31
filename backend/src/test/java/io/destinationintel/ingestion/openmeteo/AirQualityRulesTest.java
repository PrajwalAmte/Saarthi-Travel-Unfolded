package io.destinationintel.ingestion.openmeteo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class AirQualityRulesTest {

    @Test
    void onlyClearlyPollutedMonthsCountAsSmogSeason() {
        // Modelled monthly means for Delhi (2023–2025): the winter peak is the only part the page states.
        List<Double> delhi = Arrays.asList(121.0, 70.0, 65.0, 52.0, 64.0, 59.0, 48.0, 49.0, 44.0, 63.0, 104.0, 109.0);
        assertThat(AirQualityHandler.smogMonths(delhi)).containsExactly(1, 11, 12);
        assertThat(AirQualityHandler.monthRanges(AirQualityHandler.smogMonths(delhi))).isEqualTo("Nov–Jan");
        List<Double> bengaluru = Arrays.asList(30.0, 27.0, 28.0, 28.0, 24.0, 11.0, 5.0, 11.0, 9.0, 25.0, 25.0, 28.0);
        assertThat(AirQualityHandler.smogMonths(bengaluru)).isEmpty();
        assertThat(AirQualityHandler.smogMonths(Arrays.asList(null, 95.0))).containsExactly(2);
    }

    @Test
    void monthRangesWrapRoundTheYear() {
        assertThat(AirQualityHandler.monthRanges(List.of(10, 11, 12, 1, 3))).isEqualTo("Mar, Oct–Jan");
        assertThat(AirQualityHandler.monthRanges(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12))).isEqualTo("every month");
    }
}
