package io.destinationintel.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.destinationintel.ingestion.JobRepository.JobRow;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/** AI review end to end against a stubbed Gemini: it flags, an admin decides, quota and outages are survived. */
@TestPropertySource(properties = {
        "app.ai.enabled=true",
        "app.ai.gemini-api-key=test-key",
        "app.ai.model=primary-model",
        "app.ai.fallback-model=fallback-model",
        "app.ai.min-interval=0s",
        "app.ai.retry-base-delay=10ms",
        "app.ai.batch-size=4",
        "app.ai.daily-request-budget=50"
})
class AiReviewIT extends IntegrationTest {

    @DynamicPropertySource
    static void gemini(DynamicPropertyRegistry registry) {
        registry.add("app.ai.gemini-api-url", () -> SourceStub.get().baseUrl() + "/gemini");
    }

    @org.springframework.beans.factory.annotation.Autowired io.destinationintel.ai.AiPublishing publishing;

    @BeforeEach
    void resetAi() {
        jdbc.sql("TRUNCATE ai_usage, ai_enrichment, ai_overview").update();
        jdbc.sql("UPDATE app_setting SET value = 'false' WHERE key = 'ai.publish'").update();
        publishing.reset();
        SourceStub.get().geminiCalls.clear();
        SourceStub.get().busyModels.clear();
    }

    private UUID guideWithMisfiledFort() {
        UUID city = seedTestvillePlace();
        ingestGuide(city);
        UUID entry = jdbc.sql("SELECT id FROM guide_entry WHERE destination_id = :p AND section = 'eat' AND title = 'Lake View Restaurant'")
                .param("p", city).query(UUID.class).single();
        jdbc.sql("UPDATE guide_entry SET title = 'Old Fort' WHERE id = :id").param("id", entry).update();
        jdbc.sql("DELETE FROM ingestion_job WHERE job_type = 'ai_review'").update();
        return city;
    }

    private JobRow runReview(UUID city) {
        UUID id = jobs.enqueue("ai_review", null, city, Map.of(), "ai_review:" + city + ":" + UUID.randomUUID(), 100, "admin");
        runner.execute(jobs.claimNext(java.util.List.of("ai_review")).orElseThrow());
        return jobs.find(id).orElseThrow();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    @Test
    void guideImportQueuesAReviewWhenAiIsOn() {
        UUID city = seedTestvillePlace();
        ingestGuide(city);
        assertThat(jdbc.sql("SELECT count(*) FROM ingestion_job WHERE job_type = 'ai_review' AND target_place_id = :p AND status = 'queued'")
                .param("p", city).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void flagsOnlyTheDoubtfulListingAndNeverTouchesGuideContent() {
        UUID city = guideWithMisfiledFort();
        long listings = count("SELECT count(*) FROM guide_entry WHERE status = 'published' AND section <> 'neighbourhood'");
        String factsBefore = jdbc.sql("SELECT string_agg(value_text, '|' ORDER BY id) FROM fact").query(String.class).single();

        JobRow job = runReview(city);
        assertThat(job.status()).isEqualTo("succeeded");
        assertThat(count("SELECT count(*) FROM ai_review")).isEqualTo(listings);
        assertThat(count("SELECT count(*) FROM ai_review WHERE status = 'open'")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT e.title FROM ai_review r JOIN guide_entry e ON e.id = r.entry_id WHERE r.status = 'open'")
                .query(String.class).single()).isEqualTo("Old Fort");
        assertThat(count("SELECT count(*) FROM guide_entry WHERE status = 'published' AND section <> 'neighbourhood'"))
                .as("the reviewer hides nothing by itself").isEqualTo(listings);
        assertThat(jdbc.sql("SELECT string_agg(value_text, '|' ORDER BY id) FROM fact").query(String.class).single())
                .as("the reviewer never changes text").isEqualTo(factsBefore);

        int calls = SourceStub.get().geminiCalls.size();
        assertThat(runReview(city).stats()).contains("\"reviewed\": 0");
        assertThat(SourceStub.get().geminiCalls).as("unchanged listings are not sent again").hasSize(calls);
    }

    @Test
    void anAdminDecidesEachFlag() throws Exception {
        UUID city = guideWithMisfiledFort();
        runReview(city);
        Cookie admin = signIn("admin@test.example", "Admin");
        mvc.perform(get("/api/admin/ai-review/flags")).andExpect(status().isUnauthorized());
        JsonNode flags = body(mvc.perform(get("/api/admin/ai-review/flags").param("destination", "testville").cookie(admin))
                .andExpect(status().isOk()).andReturn());
        assertThat(flags.path("totalItems").asInt()).isEqualTo(1);
        JsonNode flag = flags.path("items").path(0);
        assertThat(flag.path("title").asString()).isEqualTo("Old Fort");
        assertThat(flag.path("issues").path(0).asString()).isEqualTo("wrong_section");
        assertThat(flag.path("reason").asString()).contains("fort");

        JsonNode readiness = body(mvc.perform(get("/api/admin/destinations/testville/readiness").cookie(admin)).andReturn());
        boolean flaggedCheck = false;
        for (JsonNode c : readiness.path("checks")) {
            if (c.path("key").asString().equals("ai_flags_resolved")) {
                flaggedCheck = !c.path("passed").asBoolean();
            }
        }
        assertThat(flaggedCheck).as("an open flag holds the guide back").isTrue();

        mvc.perform(post("/api/admin/ai-review/flags/" + flag.path("id").asString() + "/accept").with(csrf()).cookie(admin))
                .andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT status FROM guide_entry WHERE title = 'Old Fort'").query(String.class).single()).isEqualTo("hidden");
        assertThat(jdbc.sql("SELECT status_reason FROM guide_entry WHERE title = 'Old Fort'").query(String.class).single())
                .startsWith("Hidden after review:");
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'ai_flag.accepted'")).isEqualTo(1);
        mvc.perform(post("/api/admin/ai-review/flags/" + flag.path("id").asString() + "/dismiss").with(csrf()).cookie(admin))
                .andExpect(status().isNotFound());

        JsonNode usage = body(mvc.perform(get("/api/admin/ai-review/usage").cookie(admin)).andExpect(status().isOk()).andReturn());
        assertThat(usage.path("active").asBoolean()).isTrue();
        assertThat(usage.path("requestsToday").asInt()).isGreaterThan(0);
        assertThat(usage.path("openFlags").asInt()).isZero();
    }

    @Test
    void aBusyModelFallsBackToTheSecondModel() {
        UUID city = guideWithMisfiledFort();
        SourceStub.get().busyModels.add("primary-model");
        JobRow job = runReview(city);
        assertThat(job.status()).isEqualTo("succeeded");
        assertThat(SourceStub.get().geminiCalls).contains("primary-model", "fallback-model");
        assertThat(jdbc.sql("SELECT DISTINCT model FROM ai_review").query(String.class).single()).isEqualTo("fallback-model");
    }

    @Test
    void aSpentDailyQuotaPausesTheJobUntilTomorrowKeepingWhatWasDone() {
        UUID city = guideWithMisfiledFort();
        jdbc.sql("INSERT INTO ai_usage (day, requests) VALUES ((now() AT TIME ZONE 'UTC')::date, 49)").update();
        JobRow job = runReview(city);
        assertThat(job.status()).as("deferred, not failed").isEqualTo("queued");
        assertThat(job.attempts()).isZero();
        assertThat(job.nextAttemptAt()).isAfter(OffsetDateTime.now().plusMinutes(1));
        assertThat(job.lastError()).contains("budget");
        assertThat(count("SELECT count(*) FROM ai_review")).as("the one allowed request was kept").isEqualTo(4);
        assertThat(count("SELECT count(*) FROM ai_enrichment")).isEqualTo(4);
        assertThat(count("SELECT requests FROM ai_usage")).isEqualTo(50);
    }

    static final String LONG = "Very good dosa, a local favourite near the old market. Breakfast is ₹90 and coffee comes in "
            + "steel tumblers. It gets busy at weekends, so come early if you want a table.";

    private UUID branchWithLongText(UUID city) {
        UUID entry = jdbc.sql("""
                        SELECT e.id FROM guide_entry e WHERE e.destination_id = :p AND e.title = 'Hotel Example'
                          AND NOT EXISTS (SELECT 1 FROM fact f WHERE f.entry_id = e.id AND f.kind = 'price')""")
                .param("p", city).query(UUID.class).single();
        jdbc.sql("UPDATE fact SET value_text = :t WHERE entry_id = :e AND kind = 'description'").param("t", LONG).param("e", entry).update();
        return entry;
    }

    /** The card (or branch on a card) for a listing, as the eat tab returns it. */
    private JsonNode eatEntry(UUID id) throws Exception {
        JsonNode eat = body(mvc.perform(get("/api/destinations/testville/sections/eat")).andExpect(status().isOk()).andReturn());
        for (JsonNode e : eat.path("entries").path("items")) {
            if (e.path("id").asString().equals(id.toString())) {
                return e;
            }
            for (JsonNode b : e.path("branches")) {
                if (b.path("id").asString().equals(id.toString())) {
                    return b;
                }
            }
        }
        throw new AssertionError("listing " + id + " not on the eat tab");
    }

    @Test
    void improvementsAreCheckedStoredHiddenAndOnlyShownOncePublished() throws Exception {
        UUID city = guideWithMisfiledFort();
        UUID entry = branchWithLongText(city);
        UUID fable = jdbc.sql("SELECT id FROM guide_entry WHERE destination_id = :p AND section = 'drink' AND title = 'Hop Pub'")
                .param("p", city).query(UUID.class).single();
        jdbc.sql("UPDATE guide_entry SET title = 'Fable Cafe' WHERE id = :e").param("e", fable).update();
        jdbc.sql("UPDATE fact SET value_text = :t WHERE entry_id = :e AND kind = 'description'").param("t", LONG).param("e", fable).update();

        JobRow job = runReview(city);
        assertThat(job.status()).isEqualTo("succeeded");

        // Checked: the faithful summary is kept, the one inventing a year is rejected with the reason.
        assertThat(jdbc.sql("SELECT summary_status FROM ai_enrichment WHERE entry_id = :e").param("e", entry).query(String.class).single())
                .isEqualTo("accepted");
        assertThat(jdbc.sql("SELECT summary_rejection FROM ai_enrichment WHERE entry_id = :e").param("e", fable).query(String.class).single())
                .contains("1888");
        // Tags need their words in the text: the made-up quote is dropped.
        assertThat(jdbc.sql("SELECT tags::text FROM ai_enrichment WHERE entry_id = :e").param("e", entry).query(String.class).single())
                .contains("regional_food").doesNotContain("nightlife");
        // The price found in the text becomes a fact with the description's Wikivoyage evidence, hidden for now.
        assertThat(jdbc.sql("SELECT status FROM fact WHERE ingest_key = :k").param("k", "ai:" + entry + ":price").query(String.class).single())
                .isEqualTo("hidden");
        assertThat(jdbc.sql("""
                        SELECT e.extraction_method || ' ' || e.permalink FROM evidence e JOIN fact f ON f.id = e.fact_id
                        WHERE f.ingest_key = :k""").param("k", "ai:" + entry + ":price").query(String.class).single())
                .startsWith("ai_extracted_verbatim").contains("oldid=");
        // The overview keeps the sentence copied from a fact and rejects the one with an invented number.
        assertThat(jdbc.sql("SELECT jsonb_array_length(items) FROM ai_overview").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT rejected::text FROM ai_overview").query(String.class).single()).contains("1234");

        // Not published yet: readers see the guide exactly as the sources have it.
        JsonNode before = eatEntry(entry);
        assertThat(before.path("details").findValuesAsString("kind")).as("extracted price hidden").doesNotContain("price");
        assertThat(body(mvc.perform(get("/api/destinations/testville")).andReturn()).path("inShort").size()).isZero();

        Cookie admin = signIn("admin@test.example", "Admin");
        JsonNode preview = body(mvc.perform(get("/api/admin/ai-review/destinations/testville/preview").cookie(admin))
                .andExpect(status().isOk()).andReturn());
        assertThat(preview.path("summariesAccepted").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(preview.path("summariesRejected").asInt()).isEqualTo(1);
        assertThat(preview.path("inShortRejected").size()).isEqualTo(1);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/ai-review/publish")
                        .with(csrf()).cookie(admin).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"published\":true}"))
                .andExpect(status().isOk());

        JsonNode branch = eatEntry(entry);
        assertThat(branch.path("details").findValuesAsString("kind")).contains("price");
        JsonNode detail = body(mvc.perform(get("/api/destinations/testville")).andReturn());
        assertThat(detail.path("inShort").size()).isEqualTo(1);
        assertThat(detail.path("inShort").path(0).path("sources").path(0).path("sourceName").asString()).contains("Wikivoyage");
        assertThat(count("SELECT count(*) FROM fact WHERE ingest_key LIKE 'ai:%' AND status = 'published'")).isGreaterThan(0);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/ai-review/publish")
                        .with(csrf()).cookie(admin).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"published\":false}"))
                .andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM fact WHERE ingest_key LIKE 'ai:%' AND status = 'published'")).isZero();
        assertThat(body(mvc.perform(get("/api/destinations/testville")).andReturn()).path("inShort").size()).isZero();
    }

    @Test
    void publishedSummariesAndTagsReachTheListingCard() throws Exception {
        UUID city = seedTestvillePlace();
        ingestGuide(city);
        jdbc.sql("DELETE FROM ingestion_job WHERE job_type = 'ai_review'").update();
        // A single listing (no branches) that already has its own structured price.
        UUID entry = jdbc.sql("SELECT id FROM guide_entry WHERE destination_id = :p AND title = 'Lake View Restaurant'")
                .param("p", city).query(UUID.class).single();
        jdbc.sql("UPDATE fact SET value_text = :t WHERE entry_id = :e AND kind = 'description'").param("t", LONG).param("e", entry).update();
        runReview(city);
        publishing.set(true, null);
        JsonNode card = eatEntry(entry);
        assertThat(card.path("aiSummary").path("text").asString()).isEqualTo("Very good dosa, a local favourite near the old market.");
        assertThat(card.path("aiSummary").path("note").asString()).contains("Shortened by AI from");
        assertThat(card.path("description").path("text").asString()).as("the original stays available").isEqualTo(LONG);
        boolean regional = false;
        for (JsonNode label : card.path("labels")) {
            regional |= label.path("code").asString().equals("regional") && label.path("basis").asString().contains("noted by AI");
        }
        assertThat(regional).isTrue();
        assertThat(card.path("details").findValuesAsString("text")).as("the source's own price is not duplicated").doesNotContain("₹90");
        assertThat(count("SELECT count(*) FROM fact WHERE ingest_key LIKE 'ai:%'")).isZero();
    }
}
