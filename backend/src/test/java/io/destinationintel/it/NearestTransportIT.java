package io.destinationintel.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class NearestTransportIT extends IntegrationTest {

    private void station(String name, double lat, double lon, String osmId) {
        UUID poi = jdbc.sql("""
                        INSERT INTO poi (name, category, category_group, latitude, longitude, primary_source_id)
                        VALUES (:n, 'railway_station', 'transport', :lat, :lon, 'openstreetmap') RETURNING id""")
                .param("n", name).param("lat", lat).param("lon", lon).query(UUID.class).single();
        jdbc.sql("""
                        INSERT INTO poi_source_record (poi_id, source_id, external_id, name, category, category_group, latitude, longitude)
                        VALUES (:p, 'openstreetmap', :x, :n, 'railway_station', 'transport', :lat, :lon)""")
                .param("p", poi).param("x", osmId).param("n", name).param("lat", lat).param("lon", lon).update();
    }

    private Map<String, Object> run() {
        UUID id = jobs.enqueue("nearest_transport", "ourairports", null, Map.of(), "nearest_transport:" + UUID.randomUUID(), 1000, "admin");
        runner.execute(jobs.claimNext().orElseThrow());
        return json.readValue(jobs.find(id).orElseThrow().stats(), Map.class);
    }

    @Test
    void nearestScheduledAirportAndStationBecomeSourcedGettingThereFacts() throws Exception {
        UUID testville = seedTestvillePlace();
        ingestGuide(testville);
        station("Testville Junction", 12.31, 76.645, "node/111");
        station("Far Halt", 13.5, 76.6, "node/222"); // over 60 km away: never "the nearest station"

        Map<String, Object> stats = run();
        assertThat(stats.get("airportsLoaded")).as("Indian, not closed").isEqualTo(62);
        assertThat(stats.get("withAirport")).isEqualTo(1);
        assertThat(stats.get("withStation")).isEqualTo(1);

        String airport = jdbc.sql("SELECT value_text FROM fact WHERE ingest_key = :k").param("k", "nearest:" + testville + ":airport")
                .query(String.class).single();
        assertThat(airport).as("the airstrip without flights is skipped")
                .isEqualTo("Nearest airport with scheduled flights: Testville Airport, Mandakalli (TVX), about 8 km in a straight line.");
        String evidence = jdbc.sql("""
                        SELECT e.url FROM evidence e JOIN fact f ON f.id = e.fact_id WHERE f.ingest_key = :k""")
                .param("k", "nearest:" + testville + ":station").query(String.class).single();
        assertThat(evidence).isEqualTo("https://www.openstreetmap.org/node/111");

        JsonNode tab = body(mvc.perform(get("/api/destinations/testville/getting-around")).andExpect(status().isOk()).andReturn());
        assertThat(tab.toString()).contains("Railway station in town: Testville Junction.").contains("check times with Indian Railways");

        assertThat(run().get("destinations")).as("nothing is due again until the refresh interval").isEqualTo(0);
    }
}
