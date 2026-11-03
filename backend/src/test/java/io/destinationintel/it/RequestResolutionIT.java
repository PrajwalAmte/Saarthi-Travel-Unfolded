package io.destinationintel.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.destinationintel.ingestion.IngestionScheduler;
import io.destinationintel.place.PlaceRepository.PlaceUpsert;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** A requested landmark or old name reaches its guide; only a real state in the hint can rule a place out. */
class RequestResolutionIT extends IntegrationTest {

    @Autowired IngestionScheduler scheduler;

    @AfterEach
    void clearStub() {
        SourceStub.get().redirects.clear();
        SourceStub.get().wikidataSearch.clear();
        SourceStub.get().wikidataEntities.clear();
    }

    private String submit(String json) throws Exception {
        String code = body(mvc.perform(post("/api/requests").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn()).path("trackingCode").asString();
        drainResolve();
        return code;
    }

    private void drainResolve() {
        jobs.claimNext(java.util.List.of("resolve_request")).ifPresent(runner::execute);
    }

    private String field(String code, String column) {
        return jdbc.sql("SELECT " + column + "::text FROM destination_request WHERE tracking_code = :c").param("c", code)
                .query(String.class).single();
    }

    private long aliases(String alias) {
        return jdbc.sql("SELECT count(*) FROM place_alias WHERE alias = :a").param("a", alias).query(Long.class).single();
    }

    @Test
    void aWikivoyageRedirectLeadsToTheGuideEvenWithAFreeTextHint() throws Exception {
        UUID testville = seedTestvillePlace();
        SourceStub.get().redirects.put("Testville", java.util.List.of("Statue of Testville"));

        String code = submit("{\"query\":\"statue of testville\",\"state\":\"Vadodara\"}");
        assertThat(field(code, "resolved_place_id")).isEqualTo(testville.toString());
        assertThat(field(code, "status")).isEqualTo("processing");
        assertThat(field(code, "status_detail")).startsWith("“statue of testville” is covered by the guide to Testville.");
        assertThat(aliases("statue of testville")).as("search finds it from now on").isEqualTo(1);
    }

    @Test
    void aRealStateInTheHintStillRulesOutAPlaceElsewhere() throws Exception {
        seedTestvillePlace();
        tx.executeWithoutResult(s -> places.upsertFromWikidata(new PlaceUpsert("Q900000080", "Other State", "state", "state of India",
                25.0, 80.0, 1_000_000L, null, "IN-OS", 50, true, null)));
        SourceStub.get().redirects.put("Testville", java.util.List.of("Statue of Testville"));

        String code = submit("{\"query\":\"Statue of Testville\",\"state\":\"Other State\"}");
        assertThat(field(code, "status")).isEqualTo("unresolvable");
    }

    @Test
    void aLandmarkReachesThePlaceWikidataLocatesItIn() throws Exception {
        UUID testville = seedTestvillePlace();
        SourceStub.get().wikidataSearch.put("Big Statue", "{\"id\":\"Q900000090\",\"label\":\"Big Statue\",\"description\":\"statue in Test State\"}");
        SourceStub.get().wikidataEntities.put("Q900000090", """
                {"id":"Q900000090","labels":{"en":{"language":"en","value":"Big Statue"}},"sitelinks":{},"claims":{
                 "P17":[{"rank":"normal","mainsnak":{"datavalue":{"value":{"id":"Q668"}}}}],
                 "P131":[{"rank":"normal","mainsnak":{"datavalue":{"value":{"id":"Q900000002"}}}}],
                 "P31":[{"rank":"normal","mainsnak":{"datavalue":{"value":{"id":"Q179700"}}}}]}}""");

        String code = submit("{\"query\":\"Big Statue\"}");
        assertThat(field(code, "resolved_place_id")).isEqualTo(testville.toString());
        assertThat(field(code, "status_detail")).contains("is covered by the guide to Testville");
        assertThat(aliases("Big Statue")).isEqualTo(1);
    }

    @Test
    void stuckRequestsAreRetriedDailyAndHealThemselves() throws Exception {
        UUID testville = seedTestvillePlace();
        String code = submit("{\"query\":\"Statue of Testville\"}");
        assertThat(field(code, "status")).isEqualTo("unresolvable");

        assertThat(scheduler.planRefreshes("scheduler").get("unresolvedRetried")).as("just tried: not yet").isEqualTo(0);
        jdbc.sql("UPDATE destination_request SET updated_at = now() - interval '2 days'").update();
        SourceStub.get().redirects.put("Testville", java.util.List.of("Statue of Testville"));
        assertThat(scheduler.planRefreshes("scheduler").get("unresolvedRetried")).isEqualTo(1);

        drainResolve();
        assertThat(field(code, "resolved_place_id")).isEqualTo(testville.toString());
    }

    @Test
    void titlesAreCasedTheWayWikivoyageWritesThem() {
        assertThat(io.destinationintel.request.ResolveRequestHandler.titleCase("statue of unity")).isEqualTo("Statue of Unity");
    }
}
