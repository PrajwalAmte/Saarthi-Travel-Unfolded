package io.destinationintel.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

class CommunityIT extends IntegrationTest {

    private UUID fact(UUID city, String entryKey, String kind) {
        return jdbc.sql("""
                        SELECT f.id FROM fact f JOIN guide_entry e ON e.id = f.entry_id
                        WHERE e.destination_id = :p AND e.ingest_key = :k AND f.kind = :kind AND f.status = 'published'
                        """).param("p", city).param("k", entryKey).param("kind", kind).query(UUID.class).single();
    }

    private String correction(UUID city, UUID fact, String value) {
        return """
                {"type":"correction","destinationId":"%s","targetFactId":"%s","payload":{"value":"%s"},
                 "evidenceUrl":"https://example.org/menu"}""".formatted(city, fact, value);
    }

    @Test
    void emailSignInIsSingleUseAndGrantsConfiguredAdmin() throws Exception {
        Cookie admin = signIn("admin@test.example", "Admin");
        mvc.perform(get("/api/auth/session").cookie(admin)).andExpect(jsonPath("$.user.admin").value(true));
        String token = mail.lastTokenFor("admin@test.example");
        mvc.perform(post("/api/auth/email/verify").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_token"));
        Cookie other = signIn("someone@test.example", "Someone");
        mvc.perform(get("/api/auth/session").cookie(other)).andExpect(jsonPath("$.user.admin").value(false))
                .andExpect(jsonPath("$.user.tier").value("contributor"));
        assertThat(jdbc.sql("SELECT count(*) FROM login_token WHERE token_hash = :t").param("t", token).query(Long.class).single())
                .as("raw tokens are never stored").isZero();
    }

    @Test
    void stateChangingRequestsNeedCsrfAndAuthentication() throws Exception {
        UUID city = seedTestvillePlace();
        // Every security error has the same problem+json shape as other errors; a missing CSRF token is told
        // apart from a missing permission so the client can refresh its token and retry once.
        mvc.perform(post("/api/contributions").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("invalid_csrf")).andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").value("Forbidden"));
        mvc.perform(post("/api/contributions").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("unauthenticated"));
        Cookie c = signIn("c@test.example", "C");
        mvc.perform(post("/api/me/licence").cookie(c).contentType(MediaType.APPLICATION_JSON).content("{\"accept\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/moderation/queue").cookie(c)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
        mvc.perform(get("/api/admin/ingestion/jobs").cookie(c)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/ingestion/jobs")).andExpect(status().isUnauthorized());
        assertThat(city).isNotNull();
    }

    @Test
    void clientsCannotGrantThemselvesPrivileges() throws Exception {
        Cookie c = signIn("c@test.example", "C");
        mvc.perform(patch("/api/me").with(csrf()).cookie(c).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"C2\",\"tier\":\"steward\",\"admin\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tier").value("contributor")).andExpect(jsonPath("$.admin").value(false));
    }

    @Test
    void contributorCorrectionGoesThroughModerationAndIsAudited() throws Exception {
        UUID city = seedTestvillePlace();
        ingestGuide(city);
        UUID price = fact(city, "wv:42:eat:hotel-example:1", "price");
        Cookie contributor = signIn("c@test.example", "Contributor");

        mvc.perform(post("/api/contributions").with(csrf()).cookie(contributor).contentType(MediaType.APPLICATION_JSON)
                        .content(correction(city, price, "₹60 for a dosa")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("licence_required"));
        mvc.perform(post("/api/me/licence").with(csrf()).cookie(contributor).contentType(MediaType.APPLICATION_JSON).content("{\"accept\":true}"))
                .andExpect(status().isOk());
        JsonNode submitted = body(mvc.perform(post("/api/contributions").with(csrf()).cookie(contributor)
                .contentType(MediaType.APPLICATION_JSON).content(correction(city, price, "₹60 for a dosa")))
                .andExpect(status().isCreated()).andReturn());
        assertThat(submitted.path("status").asString()).isEqualTo("pending");
        mvc.perform(get("/api/facts/" + price)).andExpect(jsonPath("$.fact.pendingCorrections").value(1))
                .andExpect(jsonPath("$.fact.text").value("₹35"));

        Cookie second = signIn("second@test.example", "Second");
        JsonNode pendingConfirm = body(mvc.perform(post("/api/facts/" + price + "/verifications").with(csrf()).cookie(second)
                .contentType(MediaType.APPLICATION_JSON).content("{\"verdict\":\"confirm\"}")).andReturn());
        assertThat(pendingConfirm.path("status").asString()).isEqualTo("pending");

        Cookie admin = signIn("admin@test.example", "Admin");
        JsonNode queue = body(mvc.perform(get("/api/moderation/queue").cookie(admin)).andExpect(status().isOk()).andReturn());
        assertThat(queue.path("totalItems").asInt()).isEqualTo(2);
        JsonNode item = null;
        for (JsonNode i : queue.path("items")) {
            if (i.path("itemType").asString().equals("contribution")) {
                item = i;
            }
        }
        assertThat(item).isNotNull();
        assertThat(item.path("requiresRole").asString()).isEqualTo("steward");
        assertThat(item.path("details").path("targetValue").asString()).isEqualTo("₹35");

        mvc.perform(post("/api/moderation/items/" + item.path("id").asString() + "/decision").with(csrf()).cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"accept\",\"reason\":\"Checked\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("accepted"));

        assertThat(jdbc.sql("SELECT status FROM fact WHERE id = :f").param("f", price).query(String.class).single()).isEqualTo("superseded");
        assertThat(jdbc.sql("SELECT status FROM verification WHERE id = :v").param("v", UUID.fromString(pendingConfirm.path("id").asString()))
                .query(String.class).single()).as("answers about a replaced value are withdrawn").isEqualTo("withdrawn");
        assertThat(body(mvc.perform(get("/api/moderation/queue").cookie(admin)).andReturn()).path("totalItems").asInt()).isZero();
        JsonNode eat = body(mvc.perform(get("/api/destinations/testville/sections/eat").param("size", "50")).andReturn());
        String newPrice = null;
        for (JsonNode e : eat.path("entries").path("items")) {
            if (e.path("title").asString().equals("Hotel Example") && e.path("details").size() > 0) {
                for (JsonNode f : e.path("details")) {
                    if (f.path("kind").asString().equals("price")) {
                        newPrice = f.path("text").asString();
                        assertThat(f.path("origin").asString()).isEqualTo("community");
                        assertThat(f.path("verificationStatus").asString()).isEqualTo("community_reported");
                        assertThat(f.path("evidence").path(0).path("contributorName").asString()).isEqualTo("Contributor");
                    }
                }
            }
        }
        assertThat(newPrice).isEqualTo("₹60 for a dosa");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action IN ('contribution.submitted', 'contribution.accepted')")
                .query(Long.class).single()).isEqualTo(2);

        // A source refresh must not overwrite the accepted community correction.
        ingestGuide(city);
        assertThat(jdbc.sql("SELECT status FROM fact WHERE id = :f").param("f", price).query(String.class).single()).isEqualTo("superseded");
    }

    @Test
    void contributorsCannotReviewHighImpactOrTheirOwnWork() throws Exception {
        UUID city = seedTestvillePlace();
        ingestGuide(city);
        Cookie author = signInWithLicence("author@test.example", "Author");
        mvc.perform(post("/api/contributions").with(csrf()).cookie(author).contentType(MediaType.APPLICATION_JSON)
                .content(correction(city, fact(city, "wv:42:eat:hotel-example:1", "price"), "₹50"))).andExpect(status().isCreated());

        Cookie trusted = signIn("trusted@test.example", "Trusted");
        jdbc.sql("UPDATE app_user SET tier = 'trusted' WHERE email = 'trusted@test.example'").update();
        JsonNode queue = body(mvc.perform(get("/api/moderation/queue").cookie(trusted)).andExpect(status().isOk()).andReturn());
        assertThat(queue.path("totalItems").asInt()).as("high-impact items need a steward").isZero();

        UUID itemId = jdbc.sql("SELECT id FROM moderation_item LIMIT 1").query(UUID.class).single();
        mvc.perform(post("/api/moderation/items/" + itemId + "/decision").with(csrf()).cookie(trusted)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"accept\"}"))
                .andExpect(status().isForbidden());

        jdbc.sql("UPDATE app_user SET tier = 'steward' WHERE email = 'author@test.example'").update();
        mvc.perform(post("/api/moderation/items/" + itemId + "/decision").with(csrf()).cookie(author)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"accept\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void trustedContributorsPublishDirectly() throws Exception {
        UUID city = seedTestvillePlace();
        ingestGuide(city);
        Cookie trusted = signInWithLicence("trusted@test.example", "Trusted");
        jdbc.sql("UPDATE app_user SET tier = 'trusted' WHERE email = 'trusted@test.example'").update();
        String newPlace = """
                {"type":"new_place","destinationId":"%s","payload":{"section":"eat","name":"Corner Dosa Stall",
                 "description":"Crisp dosas made on a cast-iron griddle, a local breakfast favourite.","price":"₹50"}}"""
                .formatted(city);
        JsonNode r = body(mvc.perform(post("/api/contributions").with(csrf()).cookie(trusted)
                .contentType(MediaType.APPLICATION_JSON).content(newPlace)).andExpect(status().isCreated()).andReturn());
        assertThat(r.path("status").asString()).isEqualTo("accepted");
        JsonNode eat = body(mvc.perform(get("/api/destinations/testville/sections/eat").param("size", "50")).andReturn());
        boolean found = false;
        for (JsonNode e : eat.path("entries").path("items")) {
            if (e.path("title").asString().equals("Corner Dosa Stall")) {
                found = true;
                assertThat(e.path("origin").asString()).isEqualTo("community");
            }
        }
        assertThat(found).isTrue();

        // Duplicate detection for a similarly named place.
        String dup = newPlace.replace("Corner Dosa Stall", "Corner Dosa Stal");
        mvc.perform(post("/api/contributions").with(csrf()).cookie(trusted).contentType(MediaType.APPLICATION_JSON).content(dup))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("possible_duplicate"))
                .andExpect(jsonPath("$.candidates[0].title").value("Corner Dosa Stall"));
    }

    @Test
    void verificationsAreRealRecordsAndVolatileOnesNeedASecondContributor() throws Exception {
        UUID city = seedTestvillePlace();
        ingestGuide(city);
        UUID description = fact(city, "wv:42:see:testville-palace:1", "description");
        UUID price = fact(city, "wv:42:see:testville-palace:1", "price");
        Cookie a = signIn("a@test.example", "A");
        Cookie b = signIn("b@test.example", "B");

        mvc.perform(post("/api/facts/" + description + "/verifications").with(csrf()).cookie(a)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"verdict\":\"confirm\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("accepted"));
        mvc.perform(get("/api/facts/" + description)).andExpect(jsonPath("$.fact.verificationCount").value(1))
                .andExpect(jsonPath("$.fact.verificationStatus").value("community_verified"));
        mvc.perform(post("/api/facts/" + description + "/verifications").with(csrf()).cookie(a)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"verdict\":\"confirm\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("already_verified"));

        mvc.perform(post("/api/facts/" + price + "/verifications").with(csrf()).cookie(a)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"verdict\":\"confirm\"}"))
                .andExpect(jsonPath("$.status").value("pending"));
        mvc.perform(get("/api/facts/" + price)).andExpect(jsonPath("$.fact.verificationCount").value(0));
        mvc.perform(post("/api/facts/" + price + "/verifications").with(csrf()).cookie(b)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"verdict\":\"confirm\"}"))
                .andExpect(jsonPath("$.status").value("accepted"));
        mvc.perform(get("/api/facts/" + price)).andExpect(jsonPath("$.fact.verificationCount").value(2));

        mvc.perform(post("/api/facts/" + description + "/verifications").with(csrf()).cookie(b)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"verdict\":\"deny\",\"note\":\"Closed for renovation\"}"))
                .andExpect(jsonPath("$.status").value("pending"));
        mvc.perform(post("/api/facts/" + description + "/verifications").with(csrf()).cookie(a)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"verdict\":\"deny\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void flagsReachStewardsAndAccountDeletionAnonymises() throws Exception {
        UUID city = seedTestvillePlace();
        ingestGuide(city);
        UUID entry = jdbc.sql("SELECT id FROM guide_entry WHERE destination_id = :p AND title = 'Hop Pub'").param("p", city)
                .query(UUID.class).single();
        Cookie c = signInWithLicence("c@test.example", "Named Person");
        mvc.perform(post("/api/flags").with(csrf()).cookie(c).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"entry\",\"targetId\":\"" + entry + "\",\"reason\":\"self_promotion\"}"))
                .andExpect(status().isCreated());
        assertThat(jdbc.sql("SELECT requires_role FROM moderation_item WHERE item_type = 'abuse_flag'").query(String.class).single())
                .isEqualTo("steward");

        mvc.perform(post("/api/contributions").with(csrf()).cookie(c).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"type":"new_tip","destinationId":"%s","payload":{"section":"tips","text":"Carry cash for autos; many drivers refuse UPI late at night."}}"""
                        .formatted(city))).andExpect(status().isCreated());

        mvc.perform(delete("/api/me").with(csrf()).cookie(c)).andExpect(status().isNoContent());
        assertThat(jdbc.sql("SELECT count(*) FROM app_user WHERE email = 'c@test.example'").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT status FROM contribution WHERE type = 'new_tip'").query(String.class).single()).isEqualTo("withdrawn");
        assertThat(jdbc.sql("SELECT display_name FROM app_user WHERE status = 'deleted'").query(String.class).single())
                .isEqualTo("Former contributor");
        mvc.perform(get("/api/me").cookie(c)).andExpect(status().isUnauthorized());
    }
}
