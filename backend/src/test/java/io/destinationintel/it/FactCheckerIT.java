package io.destinationintel.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/** The checker agent end to end: stubbed model, stubbed official websites, real queue, guards, history and undo. */
@TestPropertySource(properties = {
        "app.ai.enabled=true",
        "app.ai.gemini-api-key=test-key",
        "app.ai.models=primary-model:50:6000",
        "app.ai.min-interval=0s",
        "app.ai.retry-base-delay=10ms",
        "app.ai.checker-allow-private-hosts=true"
})
class FactCheckerIT extends IntegrationTest {

    @Autowired io.destinationintel.ai.AiPublishing publishing;

    @DynamicPropertySource
    static void stub(DynamicPropertyRegistry registry) {
        registry.add("app.ai.gemini-api-url", () -> SourceStub.get().baseUrl() + "/gemini");
        registry.add("app.ai.checker-host-override", () -> "site.test=" + SourceStub.get().baseUrl().replaceFirst("^https?://", ""));
    }

    @BeforeEach
    void publish() {
        jdbc.sql("""
                INSERT INTO app_setting (key, value) VALUES ('ai.publish', 'true')
                ON CONFLICT (key) DO UPDATE SET value = 'true'""").update();
        publishing.reset();
    }

    private void website(String title, String url) {
        int n = jdbc.sql("""
                        UPDATE fact SET value_text = :u, value_json = jsonb_build_object('url', :u)
                        WHERE kind = 'website' AND entry_id IN (SELECT id FROM guide_entry WHERE title = :t)""")
                .param("u", url).param("t", title).update();
        assertThat(n).as("website fact for " + title).isPositive();
    }

    private String fact(String title, String kind, String status) {
        return jdbc.sql("""
                        SELECT f.value_text FROM fact f JOIN guide_entry e ON e.id = f.entry_id
                        WHERE e.title = :t AND f.kind = :k AND f.status = :s ORDER BY f.created_at DESC LIMIT 1""")
                .param("t", title).param("k", kind).param("s", status).query(String.class).optional().orElse(null);
    }

    @Test
    void staleFactsAreConfirmedOrReplacedFromTheOfficialSiteWithQuotesAndCanBeUndone() throws Exception {
        UUID testville = seedTestvillePlace();
        ingestGuide(testville);
        website("Palace Heritage Hotel", "http://hotel.site.test/site/hotel/");
        website("Testville Palace", "http://palace.site.test/site/palace/");
        SourceStub.get().siteRequests.clear();

        UUID job = jobs.enqueue("fact_check", "official-website", testville, Map.of(), "fact_check:" + UUID.randomUUID(), 1000, "admin");
        runner.execute(jobs.claimNext().orElseThrow());
        assertThat(jobs.find(job).orElseThrow().status()).isEqualTo("succeeded");

        // The hotel's price: not on the home page, so the agent followed the site's own "Tariff" link (never "Partner").
        assertThat(SourceStub.get().siteRequests).contains("/site/hotel/", "/site/hotel/tariff");
        assertThat(fact("Palace Heritage Hotel", "price", "published")).isEqualTo("from ₹10,500");
        assertThat(fact("Palace Heritage Hotel", "price", "superseded")).as("the old value is kept as history").isEqualTo("₹9000");
        String excerpt = jdbc.sql("""
                        SELECT e.excerpt FROM evidence e JOIN fact f ON f.id = e.fact_id
                        WHERE f.value_text = 'from ₹10,500' AND e.source_id = 'official-website'""").query(String.class).single();
        assertThat(excerpt).isEqualTo("Heritage rooms from ₹10,500 per night, breakfast included");

        // The palace's hours are re-dated to today, backed by the quote.
        assertThat(jdbc.sql("""
                        SELECT f.observed_at = current_date FROM fact f JOIN guide_entry e ON e.id = f.entry_id
                        WHERE e.title = 'Testville Palace' AND f.kind = 'hours' AND f.status = 'published'""")
                .query(Boolean.class).single()).isTrue();

        Cookie admin = signIn("admin@test.example", "Admin");
        JsonNode checks = body(mvc.perform(get("/api/admin/ai-review/checks").cookie(admin)).andExpect(status().isOk()).andReturn());
        assertThat(checks.path("totalItems").asInt()).isGreaterThanOrEqualTo(2);
        String changedId = null;
        for (JsonNode c : checks.path("items")) {
            if (c.path("verdict").asString().equals("changed")) {
                changedId = c.path("id").asString();
                assertThat(c.path("pagesRead").asInt()).isEqualTo(2);
                assertThat(c.path("outcome").asString()).isEqualTo("applied");
            }
        }
        assertThat(changedId).isNotNull();

        mvc.perform(post("/api/admin/ai-review/checks/" + changedId + "/undo").cookie(admin).with(csrf()))
                .andExpect(status().isOk());
        assertThat(fact("Palace Heritage Hotel", "price", "published")).as("undo brings the old value back").isEqualTo("₹9000");

        // Checked facts are not checked again for a month.
        int pages = SourceStub.get().siteRequests.size();
        jobs.enqueue("fact_check", "official-website", testville, Map.of(), "fact_check:" + UUID.randomUUID(), 1000, "admin");
        runner.execute(jobs.claimNext().orElseThrow());
        assertThat(SourceStub.get().siteRequests.size()).isEqualTo(pages);
    }
}
