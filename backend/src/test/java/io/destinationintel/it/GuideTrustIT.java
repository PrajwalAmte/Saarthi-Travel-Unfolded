package io.destinationintel.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.destinationintel.quality.QualityCheck;
import io.destinationintel.quality.QualityResult;
import io.destinationintel.quality.WikivoyageQualityEvaluator;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.JsonNode;

/** The defects an outside review found in live guides, pinned so they cannot come back unnoticed. */
class GuideTrustIT extends IntegrationTest {

    @Autowired WikivoyageQualityEvaluator evaluator;
    @Autowired DataSource dataSource;

    private UUID ingestedTestville() {
        UUID city = seedTestvillePlace();
        ingestGuide(city);
        return city;
    }

    private QualityCheck check(QualityResult r, String key) {
        return r.checks().stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow();
    }

    private UUID firstEatFactId(UUID city) {
        return jdbc.sql("""
                        SELECT f.id FROM fact f JOIN guide_entry e ON e.id = f.entry_id
                        WHERE e.destination_id = :p AND e.section = 'eat' AND f.kind = 'description' AND f.status = 'published' LIMIT 1""")
                .param("p", city).query(UUID.class).single();
    }

    @Test
    void aChainListedUnderSeveralAreasIsOnePlaceWithItsBranches() throws Exception {
        UUID city = ingestedTestville();
        JsonNode before = body(mvc.perform(get("/api/destinations/testville/sections/eat")).andExpect(status().isOk()).andReturn());
        int places = before.path("totalPublished").asInt();
        String title = jdbc.sql("SELECT title FROM guide_entry WHERE destination_id = :p AND section = 'eat' ORDER BY title LIMIT 1")
                .param("p", city).query(String.class).single();

        long existing = jdbc.sql("SELECT count(*) FROM guide_entry WHERE destination_id = :p AND section = 'eat' AND title = :t")
                .param("p", city).param("t", title).query(Long.class).single();
        for (String area : new String[] {"South Kolkata", "North Kolkata"}) {
            jdbc.sql("""
                    INSERT INTO guide_entry (destination_id, section, subsection, area, title, latitude, longitude, attributes, status, origin, ingest_key)
                    SELECT destination_id, section, subsection, :area, title, latitude, longitude, attributes, status, origin, 'test:branch:' || :area
                    FROM guide_entry WHERE destination_id = :p AND section = 'eat' AND title = :t AND ingest_key NOT LIKE 'test:%' LIMIT 1""")
                    .param("area", area).param("p", city).param("t", title).update();
        }
        caches.getCacheNames().forEach(n -> caches.getCache(n).clear());

        JsonNode after = body(mvc.perform(get("/api/destinations/testville/sections/eat")).andExpect(status().isOk()).andReturn());
        assertThat(after.path("totalPublished").asInt()).as("branches are not counted as extra places").isEqualTo(places);
        long cards = 0;
        JsonNode lead = null;
        for (JsonNode e : after.path("entries").path("items")) {
            if (e.path("title").asString().equals(title)) {
                cards++;
                lead = e;
            }
        }
        assertThat(cards).as("one card for the chain").isEqualTo(1);
        assertThat(lead.path("branches").size()).isEqualTo((int) existing + 2 - 1);
        assertThat(lead.path("branches").findValuesAsString("area")).contains("South Kolkata", "North Kolkata");
        mvc.perform(get("/api/destinations/testville")).andExpect(status().isOk());
    }

    @Test
    void emptyBracketsFailTheQualityGateAndTheCleanupMigrationRemovesThem() throws Exception {
        UUID city = ingestedTestville();
        QualityResult clean = evaluator.evaluate(UUID.randomUUID());
        assertThat(check(clean, "text_artifacts").passed()).isTrue();

        UUID fact = firstEatFactId(city);
        jdbc.sql("UPDATE fact SET value_text = 'Booking office: 11A, Free School St, Kolkata ().' WHERE id = :id").param("id", fact).update();
        UUID second = jdbc.sql("SELECT f.id FROM fact f WHERE f.destination_id = :p AND f.kind = 'description' AND f.id <> :id AND f.status = 'published' LIMIT 1")
                .param("p", city).param("id", fact).query(UUID.class).single();
        jdbc.sql("UPDATE fact SET value_text = 'A heritage cafe on the first floor (see ), with a view (really nice.' WHERE id = :id").param("id", second).update();

        QualityResult dirty = evaluator.evaluate(UUID.randomUUID());
        assertThat(check(dirty, "text_artifacts").passed()).as("critical check fails").isFalse();
        assertThat(check(dirty, "text_artifacts").severity()).isEqualTo(QualityCheck.CRITICAL);
        assertThat(check(dirty, "unbalanced_brackets").actual()).isGreaterThan(0);

        try (var conn = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new org.springframework.core.io.ByteArrayResource(
                    new ClassPathResource("db/migration/V13__clean_template_leftovers.sql").getContentAsString(StandardCharsets.UTF_8)
                            .getBytes(StandardCharsets.UTF_8)));
        }
        assertThat(jdbc.sql("SELECT value_text FROM fact WHERE id = :id").param("id", fact).query(String.class).single())
                .isEqualTo("Booking office: 11A, Free School St, Kolkata.");
        assertThat(jdbc.sql("SELECT value_text FROM fact WHERE id = :id").param("id", second).query(String.class).single())
                .isEqualTo("A heritage cafe on the first floor, with a view (really nice.");
        assertThat(check(evaluator.evaluate(UUID.randomUUID()), "text_artifacts").passed()).isTrue();
    }

    @Test
    void aLandmarkUnderEatMustSayWhatItOffers() {
        UUID city = ingestedTestville();
        UUID entry = jdbc.sql("SELECT id FROM guide_entry WHERE destination_id = :p AND section = 'eat' LIMIT 1").param("p", city).query(UUID.class).single();
        jdbc.sql("UPDATE guide_entry SET title = 'Old Fort' WHERE id = :id").param("id", entry).update();
        jdbc.sql("UPDATE fact SET value_text = 'A fine 17th century fortification.' WHERE entry_id = :id AND kind = 'description'").param("id", entry).update();
        assertThat(check(evaluator.evaluate(UUID.randomUUID()), "misfiled_landmarks").actual()).isGreaterThan(0);

        jdbc.sql("UPDATE fact SET value_text = 'Food courts on both sides of the fort serve Indian snacks.' WHERE entry_id = :id AND kind = 'description'").param("id", entry).update();
        assertThat(check(evaluator.evaluate(UUID.randomUUID()), "misfiled_landmarks").actual()).isZero();
    }

    @Test
    void readinessReportExplainsWhyAGuideIsNotYetReadyAndNeedsAdmin() throws Exception {
        ingestedTestville();
        mvc.perform(get("/api/admin/destinations/testville/readiness")).andExpect(status().isUnauthorized());
        Cookie admin = signIn("admin@test.example", "Admin");
        JsonNode r = body(mvc.perform(get("/api/admin/destinations/testville/readiness").cookie(admin)).andExpect(status().isOk()).andReturn());
        assertThat(r.path("name").asString()).isEqualTo("Testville");
        assertThat(r.path("ready").asBoolean()).as("a ten-listing fixture is not a launch guide").isFalse();
        assertThat(r.path("verified").asBoolean()).isFalse();
        assertThat(r.path("checks").findValuesAsString("key")).contains("eat_places", "clean_text", "budget", "current_prices_and_hours", "confirmed_by_locals");
        for (JsonNode c : r.path("checks")) {
            if (c.path("key").asString().equals("clean_text")) {
                assertThat(c.path("passed").asBoolean()).isTrue();
            }
            if (c.path("key").asString().equals("eat_places")) {
                assertThat(c.path("passed").asBoolean()).isFalse();
            }
        }
        mvc.perform(get("/api/admin/destinations/no-such-place/readiness").cookie(admin)).andExpect(status().isNotFound());
    }

    @Autowired io.destinationintel.guide.CoverageService coverage;

    @Test
    void aPlaceWithNoListingsIsNotCountedAsAGuide() throws Exception {
        UUID city = ingestedTestville();
        assertThat(coverage.refresh(city).status()).isNotEqualTo("indexed");
        jdbc.sql("UPDATE guide_entry SET status = 'hidden' WHERE destination_id = :p").param("p", city).update();
        caches.getCacheNames().forEach(n -> caches.getCache(n).clear());
        assertThat(coverage.refresh(city).status()).as("a climate note alone is not a guide").isEqualTo("indexed");
    }

    @Test
    void recheckWorklistListsStalePricesAndHoursInTheOrderTravellersSeeThem() throws Exception {
        ingestedTestville();
        JsonNode w = body(mvc.perform(get("/api/destinations/testville/recheck")).andExpect(status().isOk()).andReturn());
        assertThat(w.path("name").asString()).isEqualTo("Testville");
        assertThat(w.path("items").size()).isGreaterThan(0);
        assertThat(w.path("shown").asInt()).isEqualTo(w.path("items").size());
        assertThat(w.path("totalDue").asInt()).isGreaterThanOrEqualTo(w.path("shown").asInt());

        int lastSection = -1;
        int lastRank = 0;
        java.util.List<String> order = java.util.List.of("eat", "stay", "explore");
        for (JsonNode item : w.path("items")) {
            int section = order.indexOf(item.path("section").asString());
            assertThat(section).isGreaterThanOrEqualTo(lastSection);
            if (section != lastSection) {
                lastRank = 0;
            }
            assertThat(item.path("rank").asInt()).as("listings keep the order readers see").isGreaterThanOrEqualTo(lastRank);
            lastSection = section;
            lastRank = item.path("rank").asInt();
            assertThat(item.path("fact").path("kind").asString()).isIn("price", "hours");
            assertThat(item.path("fact").path("freshness").asString()).isNotEqualTo("current");
            assertThat(item.path("mapUrl").asString()).startsWith("https://www.google.com/maps/");
        }
        // The fixture's "Hotel Example" price dates from 2021, so it is on the list with its listing's rank.
        boolean listed = false;
        for (JsonNode item : w.path("items")) {
            listed |= item.path("title").asString().equals("Hotel Example") && item.path("fact").path("kind").asString().equals("price");
        }
        assertThat(listed).isTrue();
        mvc.perform(get("/api/destinations/testville/recheck").param("perSection", "1")).andExpect(status().isOk());
        mvc.perform(get("/api/destinations/no-such-place/recheck")).andExpect(status().isNotFound());
    }
}
