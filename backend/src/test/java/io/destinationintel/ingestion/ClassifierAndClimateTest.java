package io.destinationintel.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.destinationintel.ingestion.openmeteo.ClimateAggregator;
import io.destinationintel.ingestion.openmeteo.ClimateAggregator.Daily;
import io.destinationintel.ingestion.wikidata.PlaceKindClassifier;
import io.destinationintel.ingestion.wikidata.WikidataClient;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClassifierAndClimateTest {

    @Test
    void classifiesWikidataTypes() {
        assertThat(PlaceKindClassifier.classify(List.of("city", "big city"), null)).contains("city");
        assertThat(PlaceKindClassifier.classify(List.of("district of India"), null)).contains("district");
        // An item's administrative type outranks a Wikivoyage subdivision title such as "Delhi/West".
        assertThat(PlaceKindClassifier.classify(List.of("district of Delhi"), "Delhi/West")).contains("district");
        assertThat(PlaceKindClassifier.classify(List.of("neighborhood"), "Ahmedabad/North Zone")).contains("neighbourhood");
        assertThat(PlaceKindClassifier.classify(List.of("pandemic", "disease outbreak"), "COVID-19")).isEmpty();
        assertThat(PlaceKindClassifier.classify(List.of("colonization", "client state"), null)).isEmpty();
        assertThat(PlaceKindClassifier.classify(List.of("independentism"), null)).isEmpty();
        assertThat(PlaceKindClassifier.classify(List.of("luxury train"), null)).isEmpty();
        // Wikivoyage travel topics attached to topic items are not places, even when a label says "region".
        assertThat(PlaceKindClassifier.classify(List.of("cinema by country or region"), "Indian cinema tourism")).isEmpty();
        assertThat(PlaceKindClassifier.classify(List.of("aspect in a geographic region"), "Driving in India")).isEmpty();
        assertThat(PlaceKindClassifier.classify(List.of("pilgrimage feast"), "Kumbh Mela")).isEmpty();
        assertThat(PlaceKindClassifier.classify(List.of("rail transport by country or region"), "Rail travel in India")).isEmpty();
        assertThat(PlaceKindClassifier.classify(List.of("plateau"), "Deccan")).contains("region");
        assertThat(PlaceKindClassifier.classify(List.of("hill station", "town in India"), null)).contains("town");
        assertThat(PlaceKindClassifier.classify(List.of("national park"), null)).contains("park");
        assertThat(PlaceKindClassifier.classify(List.of("municipality of West Bengal"), "Kolkata/Baranagar")).contains("neighbourhood");
        assertThat(PlaceKindClassifier.classify(List.of("modern language"), null)).isEmpty();
        assertThat(PlaceKindClassifier.classify(List.of("Hindu temple"), null)).contains("attraction");
        assertThat(PlaceKindClassifier.classify(List.of(), null)).contains("other");
    }

    @Test
    void parsesWikidataHelpers() {
        assertThat(WikidataClient.qid("http://www.wikidata.org/entity/Q10086")).isEqualTo("Q10086");
        assertThat(WikidataClient.qid("http://www.wikidata.org/entity/P31")).isNull();
        assertThat(WikidataClient.point("Point(76.65 12.30)")).containsExactly(12.30, 76.65);
        assertThat(WikidataClient.point("Point(500 12)")).isNull();
    }

    @Test
    void aggregatesMonthlyClimate() {
        List<Daily> days = new ArrayList<>();
        for (LocalDate d = LocalDate.of(2023, 1, 1); d.isBefore(LocalDate.of(2025, 1, 1)); d = d.plusDays(1)) {
            int m = d.getMonthValue();
            double rain = m == 10 ? 10.0 : m == 1 ? 0.0 : 1.0;
            days.add(new Daily(d, 25.0 + (m == 4 ? 10 : 0), 15.0 - (m == 12 ? 5 : 0), rain));
        }
        var climate = ClimateAggregator.aggregate(days);
        assertThat(climate.startYear()).isEqualTo(2023);
        assertThat(climate.endYear()).isEqualTo(2024);
        assertThat(climate.months()).hasSize(12);
        assertThat(climate.months().get(9).precipitationMm()).isEqualTo(310.0);
        assertThat(climate.months().get(9).rainyDays()).isEqualTo(31.0);
        assertThat(climate.summary()).contains("Warmest month: April").contains("Wettest month: October")
                .contains("Coolest nights: December").contains("January");
    }

    @Test
    void rejectsIncompleteClimateData() {
        List<Daily> days = List.of(new Daily(LocalDate.of(2024, 1, 1), 20.0, 10.0, 0.0));
        assertThatThrownBy(() -> ClimateAggregator.aggregate(days)).isInstanceOf(IllegalArgumentException.class);
    }
}
