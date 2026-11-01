package io.destinationintel.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.destinationintel.place.PlaceRepository.PlaceUpsert;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/** Natural-language search: the model only reads the question; places and reasons come from stored, sourced data. */
@TestPropertySource(properties = {
        "app.ai.enabled=true",
        "app.ai.gemini-api-key=test-key",
        "app.ai.models=primary-model:50:6000",
        "app.ai.min-interval=0s",
        "app.ai.retry-base-delay=10ms"
})
class QuestionSearchIT extends IntegrationTest {

    @DynamicPropertySource
    static void stub(DynamicPropertyRegistry registry) {
        registry.add("app.ai.gemini-api-url", () -> SourceStub.get().baseUrl() + "/gemini");
    }

    private UUID guided(String qid, String name, String description, double lat, double lon) {
        UUID id = tx.execute(s -> {
            UUID p = places.upsertFromWikidata(new PlaceUpsert(qid, name, "town", description, lat, lon, 20_000L, name, null, 10, true, "Test State"));
            places.addAlias(p, name, "en", "label", "wikidata");
            return p;
        });
        jdbc.sql("INSERT INTO destination_profile (place_id, coverage_status, computed_at) VALUES (:p, 'usable', now())").param("p", id).update();
        return id;
    }

    private void climate(UUID place, double elevation, double aprilMaxC) {
        jdbc.sql("""
                        INSERT INTO fact (destination_id, section, kind, value_text, value_json, volatility, status, origin, observed_basis)
                        VALUES (:p, 'practical', 'climate', 'Climate.', CAST(:j AS jsonb), 'stable', 'published', 'ingested', 'computed_period')""")
                .param("p", place).param("j", """
                        {"period": "2016–2025", "elevationM": %s, "months": [{"month": 4, "meanMaxC": %s, "rainyDays": 2.0}]}"""
                        .formatted(elevation, aprilMaxC)).update();
    }

    private JsonNode ask(String q) throws Exception {
        return body(mvc.perform(get("/api/search/ask").param("q", q)).andExpect(status().isOk()).andReturn());
    }

    @Test
    void questionsBecomeFiltersOverStoredDataWithSourcedReasons() throws Exception {
        UUID testville = seedTestvillePlace();
        ingestGuide(testville);
        UUID hill = guided("Q900000070", "Hillstation", "town in Test State, India", 12.85, 76.65); // ~1.5 h by road
        UUID far = guided("Q900000071", "Farhills", "hill station in Test State, India", 13.9, 76.65); // ~4.5 h by road
        UUID hot = guided("Q900000072", "Hotplains", "hill station in Test State, India", 12.6, 76.9); // mild? no: 38°C
        climate(hill, 1452, 27);
        climate(far, 1600, 24);
        climate(hot, 1200, 38);
        jobs.enqueue("road_routes", "osrm", testville, Map.of("radiusKm", 400), "road_routes:" + UUID.randomUUID(), 1000, "admin");
        runner.execute(jobs.claimNext().orElseThrow());

        int calls = SourceStub.get().geminiCalls.size();
        JsonNode a = ask("hill station within 3 hours from Testville in April, somewhere quiet");
        assertThat(a.path("mode").asString()).isEqualTo("question");
        List<String> understood = new ArrayList<>();
        a.path("understood").forEach(n -> understood.add(n.asString()));
        assertThat(understood).containsExactly("From Testville", "Up to 3 h by road", "Mild in April", "Hill stations");
        assertThat(a.path("unsupported").path(0).asString()).isEqualTo("quiet");

        List<String> slugs = new ArrayList<>();
        a.path("results").forEach(r -> slugs.add(r.path("slug").asString()));
        assertThat(slugs).as("too far by road, and too hot in April, are left out").containsExactly("hillstation");
        String reasons = a.path("results").path(0).path("reasons").toString();
        assertThat(reasons).contains("from Testville by road").contains("About 1,450 m above sea level")
                .contains("Usually mild in April: average high 27°C");

        ask("hill station within 3 hours from Testville in April, somewhere quiet");
        assertThat(SourceStub.get().geminiCalls.size() - calls).as("the same question is read once").isEqualTo(1);

        JsonNode name = ask("Hillstation");
        assertThat(name.path("mode").asString()).as("a plain name is left to name search").isEqualTo("name");
        tx.executeWithoutResult(s -> places.addAlias(hill, "Misty Hills", "en", "alternate", "wikidata"));
        assertThat(ask("Misty Hills").path("mode").asString()).as("an exact place name never asks the model").isEqualTo("name");
        assertThat(SourceStub.get().geminiCalls.size() - calls).isEqualTo(1);
    }
}
