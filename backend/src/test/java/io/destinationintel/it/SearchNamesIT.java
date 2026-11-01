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
import tools.jackson.databind.JsonNode;

/** Landmarks and renamed towns find their guide through Wikivoyage redirects; unrelated names are not offered. */
class SearchNamesIT extends IntegrationTest {

    private JsonNode search(String q) throws Exception {
        return body(mvc.perform(get("/api/search").param("q", q)).andExpect(status().isOk()).andReturn()).path("results");
    }

    private List<String> aliases(UUID place) {
        return jdbc.sql("SELECT alias FROM place_alias WHERE place_id = :p AND alias_type = 'redirect' ORDER BY alias")
                .param("p", place).query(String.class).list();
    }

    private void importRedirects() {
        jobs.enqueue("wikivoyage_redirects", "wikivoyage-en", null, Map.of(), "wikivoyage_redirects:" + UUID.randomUUID(), 1000, "admin");
        runner.execute(jobs.claimNext().orElseThrow());
    }

    @Test
    void redirectsBecomeSearchNamesAndLooseMatchesOnCommonWordsAreDropped() throws Exception {
        UUID testville = seedTestvillePlace();
        tx.executeWithoutResult(s -> {
            UUID kushal = places.upsertFromWikidata(new PlaceUpsert("Q900000060", "Kushalnagara", "town", "town in Test State, India",
                    12.46, 75.96, 30_000L, null, null, 20, true, "Test State"));
            places.addAlias(kushal, "Kushalnagara", "en", "label", "wikidata");
            places.addAlias(kushal, "Kushal Nagar", "en", "alternate", "wikidata");
        });
        assertThat(search("ekta nagar")).as("sharing only the word nagar is not a match").isEmpty();
        assertThat(search("statue of unity")).isEmpty();

        SourceStub.get().redirects.put("Testville", List.of("Statue of Testville", "Ekta Testnagar", "TVL", "Testville/East"));
        importRedirects();
        assertThat(aliases(testville)).as("codes and sub-pages are not names").containsExactly("Ekta Testnagar", "Statue of Testville");

        JsonNode hit = search("statue of testville").path(0);
        assertThat(hit.path("slug").asString()).isEqualTo("testville");
        assertThat(hit.path("matchedAlias").asString()).isEqualTo("Statue of Testville");
        assertThat(search("ekta testnagar").path(0).path("slug").asString()).as("a renamed town").isEqualTo("testville");

        List<String> slugs = new ArrayList<>();
        search("testvile").forEach(h -> slugs.add(h.path("slug").asString()));
        assertThat(slugs).as("typos still work").contains("testville");

        SourceStub.get().redirects.put("Testville", List.of("Statue of Testville"));
        importRedirects();
        assertThat(aliases(testville)).as("names Wikivoyage dropped are removed").containsExactly("Statue of Testville");
        SourceStub.get().redirects.clear();
    }

    @Test
    void questionsFallBackToNameSearchWhenAiIsOff() throws Exception {
        seedTestvillePlace();
        JsonNode a = body(mvc.perform(get("/api/search/ask").param("q", "hill station within 3 hours of Testville"))
                .andExpect(status().isOk()).andReturn());
        assertThat(a.path("mode").asString()).isEqualTo("unavailable");
    }
}
