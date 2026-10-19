package io.destinationintel.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/** The validation gate in front of community contributions, and rolling an accepted one back. */
class CommunityGateIT extends IntegrationTest {

    private UUID priceFact(UUID city) {
        return jdbc.sql("""
                        SELECT f.id FROM fact f JOIN guide_entry e ON e.id = f.entry_id
                        WHERE e.destination_id = :p AND e.ingest_key = 'wv:42:eat:hotel-example:1' AND f.kind = 'price'
                          AND f.status = 'published'
                        """).param("p", city).query(UUID.class).single();
    }

    private org.springframework.test.web.servlet.ResultActions submit(Cookie who, String json) throws Exception {
        return mvc.perform(post("/api/contributions").with(csrf()).cookie(who).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String newPlace(UUID city, String extra) {
        return """
                {"type":"new_place","destinationId":"%s","acknowledgePossibleDuplicate":true,"payload":{"section":"eat",
                 "name":"Fixture Darshini","description":"A standing-only darshini serving idli and coffee all morning."%s}}"""
                .formatted(city, extra);
    }

    @Test
    void rejectsUnreadableAndImplausiblePrices() throws Exception {
        UUID city = seedTestvillePlace();
        ingestGuide(city);
        UUID price = priceFact(city);
        Cookie c = signInWithLicence("c@test.example", "Contributor");
        String correction = """
                {"type":"correction","destinationId":"%s","targetFactId":"%s","payload":{"value":"%s"}}""";
        submit(c, correction.formatted(city, price, "quite cheap")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("unreadable_price"));
        // An absurd amount is refused whether the parser reads it as too large or not at all.
        submit(c, correction.formatted(city, price, "₹99999999")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.oneOf("implausible_price", "unreadable_price")));
        submit(c, correction.formatted(city, price, "₹60 for a dosa")).andExpect(status().isCreated());
        submit(c, newPlace(city, ",\"price\":\"₹0\"")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.oneOf("implausible_price", "unreadable_price")));
    }

    @Test
    void rejectsLocationsThatCannotBelongToTheDestination() throws Exception {
        UUID city = seedTestvillePlace();
        Cookie c = signInWithLicence("c@test.example", "Contributor");
        // Mumbai coordinates submitted for the test city in southern Karnataka (about 850 km away).
        submit(c, newPlace(city, ",\"latitude\":19.076,\"longitude\":72.8777")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("implausible_location"));
        submit(c, newPlace(city, ",\"latitude\":12.31")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("incomplete_location"));
        submit(c, newPlace(city, ",\"latitude\":12.31,\"longitude\":76.66")).andExpect(status().isCreated());
    }

    @Test
    void repeatedConfirmationsFromOneContributorCountOnce() throws Exception {
        UUID city = seedTestvillePlace();
        ingestGuide(city);
        UUID price = priceFact(city);
        Cookie c = signIn("c@test.example", "Contributor");
        String confirm = "{\"verdict\":\"confirm\"}";
        mvc.perform(post("/api/facts/" + price + "/verifications").with(csrf()).cookie(c).contentType(MediaType.APPLICATION_JSON)
                .content(confirm)).andExpect(status().is2xxSuccessful());
        mvc.perform(post("/api/facts/" + price + "/verifications").with(csrf()).cookie(c).contentType(MediaType.APPLICATION_JSON)
                .content(confirm)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("already_verified"));
        assertThat(jdbc.sql("SELECT count(*) FROM verification WHERE fact_id = :f").param("f", price).query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void anAcceptedContributionCanBeRolledBack() throws Exception {
        UUID city = seedTestvillePlace();
        Cookie admin = signInWithLicence("admin@test.example", "Admin");
        JsonNode created = body(submit(admin, newPlace(city, ",\"latitude\":12.31,\"longitude\":76.66"))
                .andExpect(status().isCreated()).andReturn());
        assertThat(created.path("status").asString()).isEqualTo("accepted");
        UUID contribution = UUID.fromString(created.path("id").asString());
        assertThat(jdbc.sql("SELECT count(*) FROM guide_entry WHERE destination_id = :d AND title = 'Fixture Darshini' AND status = 'published'")
                .param("d", city).query(Long.class).single()).isEqualTo(1);

        mvc.perform(post("/api/moderation/contributions/" + contribution + "/revert").with(csrf()).cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Test rollback\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("reverted"));
        assertThat(jdbc.sql("SELECT count(*) FROM guide_entry WHERE destination_id = :d AND title = 'Fixture Darshini' AND status = 'published'")
                .param("d", city).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT status FROM contribution WHERE id = :c").param("c", contribution).query(String.class).single())
                .isEqualTo("reverted");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'contribution.reverted'").query(Long.class).single()).isEqualTo(1);
    }
}
