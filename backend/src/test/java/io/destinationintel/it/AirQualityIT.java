package io.destinationintel.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.destinationintel.place.PlaceRepository.PlaceUpsert;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class AirQualityIT extends IntegrationTest {

    private Map<String, Object> run(UUID place) {
        UUID id = jobs.enqueue("air_quality", "open-meteo-air", place, Map.of(), "air_quality:" + UUID.randomUUID(), 1000, "admin");
        runner.execute(jobs.claimNext().orElseThrow());
        return json.readValue(jobs.find(id).orElseThrow().stats(), Map.class);
    }

    @Test
    void smogSeasonIsStatedWithItsCaveatAndNeighboursShareOneDownload() throws Exception {
        UUID testville = seedTestvillePlace();
        ingestGuide(testville);
        UUID neighbour = tx.execute(s -> places.upsertFromWikidata(new PlaceUpsert("Q900000050", "Neighbourtown", "town",
                "town in Test State, India", 12.33, 76.70, 20_000L, "Neighbourtown", null, 10, true, "Test State")));

        int before = SourceStub.get().airQualityRequests.get();
        Map<String, Object> first = run(testville);
        assertThat(first.get("downloaded")).isEqualTo(true);
        assertThat(run(neighbour).get("downloaded")).as("same 0.4° model cell: no second download").isEqualTo(false);
        assertThat(SourceStub.get().airQualityRequests.get() - before).isEqualTo(1);

        String text = jdbc.sql("SELECT value_text FROM fact WHERE ingest_key = :k AND status = 'published'")
                .param("k", "aq:" + testville + ":smog").query(String.class).single();
        assertThat(text).startsWith("Air quality is usually at its worst in Nov–Jan, when smog is common.")
                .contains("from a model").contains("actual readings in these months are often higher").contains("CPCB")
                .doesNotContain("µg");

        JsonNode tips = body(mvc.perform(get("/api/destinations/testville/tips")).andExpect(status().isOk()).andReturn());
        assertThat(tips.toString()).contains("Smog season");
    }
}
