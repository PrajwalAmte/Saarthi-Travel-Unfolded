package io.destinationintel.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.destinationintel.place.PlaceRepository.PlaceUpsert;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class GuideApiIT extends IntegrationTest {

    @Test
    void emptyDatabaseNeverProducesGuidesOrResults() throws Exception {
        mvc.perform(get("/api/search").param("q", "Mysuru")).andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(0));
        mvc.perform(get("/api/destinations/mysore")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
        mvc.perform(get("/api/meta")).andExpect(status().isOk())
                .andExpect(jsonPath("$.indexedDestinations").value(0));
        mvc.perform(get("/api/destinations").param("withGuide", "true")).andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void indexedPlaceWithoutContentIsHonestlyLabelled() throws Exception {
        UUID city = seedTestvillePlace();
        JsonNode d = body(mvc.perform(get("/api/destinations/testville")).andExpect(status().isOk()).andReturn());
        assertThat(d.path("guideAvailable").asBoolean()).isFalse();
        assertThat(d.path("coverage").path("status").asString()).isEqualTo("indexed");
        assertThat(d.path("summary").isNull()).isTrue();
        mvc.perform(get("/api/destinations/testville/sections/eat")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPublished").value(0))
                .andExpect(jsonPath("$.entries.items.length()").value(0));
        assertThat(city).isNotNull();
    }

    @Test
    void searchMatchesNamesAliasesPartialAndFuzzyAndDisambiguates() throws Exception {
        seedTestvillePlace();
        tx.executeWithoutResult(s -> {
            UUID other = places.upsertFromWikidata(new PlaceUpsert("Q900000003", "Other State", "state", "state of India",
                    20.0, 80.0, null, null, null, 10, true, null));
            places.addAlias(other, "Other State", "en", "label", "wikidata");
            UUID twin = places.upsertFromWikidata(new PlaceUpsert("Q900000004", "Testville", "town", "town in Other State, India",
                    20.1, 80.1, 5000L, null, null, 3, true, "Other State"));
            places.addAlias(twin, "Testville", "en", "label", "wikidata");
            places.addRelation(twin, other, "in_state", "wikidata");
        });
        JsonNode exact = body(mvc.perform(get("/api/search").param("q", "testville")).andExpect(status().isOk()).andReturn());
        assertThat(exact.path("results").size()).isEqualTo(2);
        List<String> states = new ArrayList<>();
        exact.path("results").forEach(r -> states.add(r.path("stateName").asString()));
        assertThat(states).containsExactlyInAnyOrder("Test State", "Other State");
        assertThat(exact.path("results").path(0).path("slug").asString()).isNotEqualTo(exact.path("results").path(1).path("slug").asString());

        JsonNode alias = body(mvc.perform(get("/api/search").param("q", "Testpura")).andReturn());
        assertThat(alias.path("results").path(0).path("name").asString()).isEqualTo("Testville");
        assertThat(alias.path("results").path(0).path("matchedAlias").asString()).isEqualTo("Testpura");

        assertThat(body(mvc.perform(get("/api/search").param("q", "testv")).andReturn()).path("results").size()).isGreaterThanOrEqualTo(1);
        assertThat(body(mvc.perform(get("/api/search").param("q", "Testvile")).andReturn()).path("results").path(0).path("matchType").asString())
                .isEqualTo("fuzzy");
        assertThat(body(mvc.perform(get("/api/search").param("q", "ಟೆಸ್ಟ್‌ವಿಲ್")).andReturn()).path("results").size()).isGreaterThanOrEqualTo(1);
        assertThat(body(mvc.perform(get("/api/search").param("q", "zzzzqqq")).andReturn()).path("results").size()).isZero();
    }

    @Test
    void guideSectionsRenderStoredFactsWithProvenance() throws Exception {
        UUID city = seedTestvillePlace();
        ingestGuide(city);
        JsonNode d = body(mvc.perform(get("/api/destinations/testville")).andExpect(status().isOk()).andReturn());
        assertThat(d.path("guideAvailable").asBoolean()).isTrue();
        assertThat(d.path("summary").path("text").asString()).startsWith("Testville is a fictional");
        assertThat(d.path("summary").path("evidence").path(0).path("permalink").asString()).contains("oldid=1001");
        assertThat(d.path("coverage").path("checks").size()).isGreaterThan(3);

        JsonNode eat = body(mvc.perform(get("/api/destinations/testville/sections/eat")).andExpect(status().isOk()).andReturn());
        // Four listings, but "Hotel Example" is one place with a second branch, so three places.
        assertThat(eat.path("totalPublished").asInt()).isEqualTo(3);
        assertThat(eat.path("entries").path("items").size()).isEqualTo(3);
        for (JsonNode e : eat.path("entries").path("items")) {
            if (e.path("title").asString().equals("Hotel Example")) {
                assertThat(e.path("branches").size()).isEqualTo(1);
                assertThat(e.path("branches").path(0).path("mapUrl").asString()).startsWith("https://www.google.com/maps/search/");
            } else {
                assertThat(e.path("branches").size()).isZero();
            }
        }
        JsonNode first = eat.path("entries").path("items").path(0);
        assertThat(first.path("description").path("verificationStatus").asString()).isNotBlank();
        assertThat(first.path("description").path("verificationCount").asInt()).isZero();
        assertThat(first.path("mapUrl").asString()).startsWith("https://www.google.com/maps/search/?api=1&query=");
        assertThat(eat.path("specialities").size()).isEqualTo(1);

        JsonNode explore = body(mvc.perform(get("/api/destinations/testville/sections/explore")).andReturn());
        JsonNode palace = null;
        for (JsonNode e : explore.path("entries").path("items")) {
            if (e.path("title").asString().equals("Testville Palace")) {
                palace = e;
            }
        }
        assertThat(palace).isNotNull();
        List<String> labels = new ArrayList<>();
        palace.path("labels").forEach(l -> labels.add(l.path("code").asString()));
        assertThat(labels).contains("major", "accessibility");

        JsonNode tips = body(mvc.perform(get("/api/destinations/testville/tips")).andReturn());
        assertThat(tips.path("groups").size()).isGreaterThan(0);
        JsonNode around = body(mvc.perform(get("/api/destinations/testville/getting-around")).andReturn());
        assertThat(around.path("totalFacts").asInt()).isEqualTo(3);
        JsonNode hoods = body(mvc.perform(get("/api/destinations/testville/neighbourhoods")).andReturn());
        assertThat(hoods.path("neighbourhoods").size()).isEqualTo(2);
    }

    @Test
    void preferencesChangeBackendRankingAndConstraintsFilter() throws Exception {
        UUID city = seedTestvillePlace();
        ingestGuide(city);
        JsonNode budget = body(mvc.perform(get("/api/destinations/testville/sections/stay").param("comfort", "basic")).andReturn());
        JsonNode luxury = body(mvc.perform(get("/api/destinations/testville/sections/stay").param("comfort", "luxury")).andReturn());
        assertThat(budget.path("entries").path("items").path(0).path("title").asString()).isEqualTo("Backpacker Inn");
        assertThat(luxury.path("entries").path("items").path(0).path("title").asString()).isEqualTo("Palace Heritage Hotel");
        assertThat(luxury.path("personalizationApplied").asBoolean()).isTrue();

        JsonNode filtered = body(mvc.perform(get("/api/destinations/testville/sections/eat").param("maxPrice", "budget")).andReturn());
        assertThat(filtered.path("excludedByConstraints").asInt()).isGreaterThan(0);
        filtered.path("entries").path("items").forEach(e -> assertThat(e.path("title").asString()).isNotEqualTo("Lake View Restaurant"));

        mvc.perform(get("/api/destinations/testville/sections/eat").param("category", "drink"))
                .andExpect(jsonPath("$.entries.items.length()").value(1))
                .andExpect(jsonPath("$.entries.items[0].title").value("Hop Pub"));
    }

    @Test
    void budgetIsDerivedOnlyFromRecordedPrices() throws Exception {
        UUID city = seedTestvillePlace();
        ingestGuide(city);
        JsonNode b = body(mvc.perform(get("/api/destinations/testville/budget")).andExpect(status().isOk()).andReturn());
        assertThat(b.path("emptyReason").isNull()).isTrue();
        JsonNode stays = null;
        for (JsonNode r : b.path("ranges")) {
            if (r.path("category").asString().equals("stays")) {
                stays = r;
            }
        }
        assertThat(stays).isNotNull();
        assertThat(stays.path("observations").asInt()).isEqualTo(2);
        assertThat(b.path("assumptions").size()).isGreaterThan(2);
    }

    @Test
    void factDetailIncludesEvidenceHistoryAndReviewWindow() throws Exception {
        UUID city = seedTestvillePlace();
        ingestGuide(city);
        UUID fact = jdbc.sql("SELECT id FROM fact WHERE destination_id = :p AND kind = 'price' LIMIT 1").param("p", city)
                .query(UUID.class).single();
        mvc.perform(get("/api/facts/" + fact)).andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewWindowDays").value(120))
                .andExpect(jsonPath("$.history[0].changeType").value("initial"))
                .andExpect(jsonPath("$.fact.evidence[0].sourceId").value("wikivoyage-en"));
        mvc.perform(get("/api/facts/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void invalidParametersAreRejectedWithUsefulErrors() throws Exception {
        mvc.perform(get("/api/destinations/Bad Slug!")).andExpect(status().is4xxClientError());
        seedTestvillePlace();
        mvc.perform(get("/api/destinations/testville/sections/nightclubs")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/destinations/testville/sections/eat").param("category", "<script>")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));
        mvc.perform(get("/api/facts/not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    void sitemapListsGuidesWithTheirTabsAndStateHubsButNotEmptyPlaces() throws Exception {
        UUID city = seedTestvillePlace();
        ingestGuide(city);
        tx.executeWithoutResult(st -> places.upsertFromWikidata(new PlaceUpsert("Q900000020", "Emptyville", "town", "town in Test State, India",
                12.0, 76.0, 10_000L, null, null, 1, true, "Test State")));
        // A district whose town has a guide is a hub too ("Coorg" is Kodagu district, whose guide is Madikeri's).
        tx.executeWithoutResult(st -> {
            UUID district = places.upsertFromWikidata(new PlaceUpsert("Q900000021", "Test District", "district", "district of Test State",
                    12.3, 76.6, 500_000L, null, null, 5, true, "Test State"));
            places.addRelation(city, district, "in_district", "wikidata");
        });
        JsonNode entries = body(mvc.perform(get("/api/destinations/sitemap")).andExpect(status().isOk()).andReturn());
        List<String> slugs = new ArrayList<>();
        JsonNode testville = null;
        for (JsonNode e : entries) {
            slugs.add(e.path("slug").asString());
            if (e.path("slug").asString().equals("testville")) {
                testville = e;
            }
        }
        assertThat(slugs).contains("testville", "test-state", "test-district").doesNotContain("emptyville");
        assertThat(body(mvc.perform(get("/api/destinations/test-district")).andReturn()).path("guidedPlaces").asInt()).isEqualTo(1);
        assertThat(testville.path("lastModified").isNull()).isFalse();
        List<String> tabs = new ArrayList<>();
        testville.path("tabs").forEach(t -> tabs.add(t.asString()));
        assertThat(tabs).contains("eat", "stay", "explore");
        mvc.perform(get("/api/destinations/testville")).andExpect(status().isOk());
    }
}
