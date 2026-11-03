package io.destinationintel.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.destinationintel.place.PlaceRepository.PlaceUpsert;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/** A city's own minimum distance keeps its suburbs out, and a crowded group keeps its best-known places. */
@TestPropertySource(properties = "app.ingestion.focus=testville:400:50")
class WeekendTripsLimitsIT extends IntegrationTest {

    private void guided(String qid, String name, double lat, int sitelinks) {
        UUID id = tx.execute(s -> places.upsertFromWikidata(new PlaceUpsert(qid, name, "town", "town in Test State, India",
                lat, 76.65, 20_000L, name, null, sitelinks, true, "Test State")));
        jdbc.sql("INSERT INTO destination_profile (place_id, coverage_status, computed_at) VALUES (:p, 'usable', now())")
                .param("p", id).update();
    }

    @Test
    void suburbsAreLeftOutAndCrowdedBandsKeepTheBestKnown() throws Exception {
        seedTestvillePlace();
        guided("Q900000200", "Satellite Town", 12.70, 300); // ~44 km: under this city's 50 km minimum
        for (int i = 0; i < 42; i++) {
            guided("Q9000002" + (10 + i), "Small Place " + i, 12.90 + i * 0.005, i + 1); // ~66-90 km
        }
        guided("Q900000260", "Famous Place", 13.50, 500); // ~133 km, the furthest but the best known

        JsonNode origin = body(mvc.perform(get("/api/trips/origins")).andExpect(status().isOk()).andReturn()).path(0);
        assertThat(origin.path("minKm").asInt()).isEqualTo(50);
        assertThat(origin.path("total").asInt()).as("the count needs no page build and matches the page").isEqualTo(43);

        JsonNode page = body(mvc.perform(get("/api/trips/weekend-from/testville")).andExpect(status().isOk()).andReturn());
        assertThat(page.path("total").asInt()).isEqualTo(43);
        JsonNode band = page.path("bands").path(0);
        assertThat(band.path("total").asInt()).isEqualTo(43);
        List<String> names = new ArrayList<>();
        band.path("trips").forEach(t -> names.add(t.path("name").asString()));
        assertThat(names).hasSize(40).as("best known kept, then nearest first").contains("Famous Place").endsWith("Famous Place")
                .doesNotContain("Satellite Town", "Small Place 0", "Small Place 1", "Small Place 2");
        assertThat(page.path("method").toString()).contains("more than 50 km");
    }
}
