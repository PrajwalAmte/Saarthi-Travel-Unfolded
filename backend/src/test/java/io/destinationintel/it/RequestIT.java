package io.destinationintel.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.destinationintel.request.RequestNotifications;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

class RequestIT extends IntegrationTest {

    @Autowired RequestNotifications notifications;

    private JsonNode request(String json) throws Exception {
        return body(mvc.perform(post("/api/requests").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn());
    }

    @Test
    void requestForIndexedPlaceQueuesIngestionWithoutClaimingSuccess() throws Exception {
        UUID city = seedTestvillePlace();
        JsonNode r = request("{\"query\":\"Testpura\"}");
        assertThat(r.path("status").asString()).isEqualTo("processing");
        assertThat(r.path("destination").path("slug").asString()).isEqualTo("testville");
        assertThat(r.path("destination").path("coverageStatus").asString()).isEqualTo("indexed");
        assertThat(jdbc.sql("SELECT count(*) FROM ingestion_job WHERE job_type = 'wikivoyage_guide' AND target_place_id = :p AND status = 'queued'")
                .param("p", city).query(Long.class).single()).isEqualTo(1);

        String code = r.path("trackingCode").asString();
        runner.execute(jobs.claimNext().orElseThrow());
        mvc.perform(get("/api/requests/" + code)).andExpect(status().isOk())
                .andExpect(jsonPath("$.destination.coverageStatus").value(org.hamcrest.Matchers.not("indexed")));
    }

    @Test
    void duplicateRequestsAreMergedAsDemand() throws Exception {
        seedTestvillePlace();
        String code = request("{\"query\":\"Testville\"}").path("trackingCode").asString();
        assertThat(request("{\"query\":\"  testville \"}").path("trackingCode").asString()).isEqualTo(code);
        assertThat(request("{\"query\":\"Testpura\"}").path("trackingCode").asString()).isEqualTo(code);
        mvc.perform(get("/api/requests/" + code)).andExpect(jsonPath("$.demandCount").value(3));
    }

    @Test
    void unknownPlacesAreQueuedForResolution() throws Exception {
        JsonNode r = request("{\"query\":\"Nowhereabad\",\"state\":\"Test State\"}");
        assertThat(r.path("status").asString()).isEqualTo("queued");
        assertThat(r.path("destination").isNull()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM ingestion_job WHERE job_type = 'resolve_request'").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void invalidRequestsAreRejected() throws Exception {
        mvc.perform(post("/api/requests").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"x\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation_failed"));
        mvc.perform(post("/api/requests").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"12345\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_query"));
        mvc.perform(get("/api/requests/doesnotexist1")).andExpect(status().isNotFound());
        mvc.perform(get("/api/requests/../../etc")).andExpect(status().is4xxClientError());
    }

    @Test
    void queuedRequestShowsAnEstimateFromTheRealQueue() throws Exception {
        seedTestvillePlace();
        // 4 earlier guide jobs finished in about 90 s each, so the estimate is measured, not guessed.
        for (int i = 0; i < 4; i++) {
            jdbc.sql("""
                    INSERT INTO ingestion_job (job_type, dedup_key, status, started_at, finished_at, created_by)
                    VALUES ('wikivoyage_guide', :k, 'succeeded', now() - interval '10 minutes', now() - interval '10 minutes' + interval '90 seconds', 'system')""")
                    .param("k", "old-" + i).update();
        }
        // 3 bulk imports are queued at a lower priority than reader requests, so they do not delay this one.
        for (int i = 0; i < 3; i++) {
            jdbc.sql("INSERT INTO ingestion_job (job_type, dedup_key, priority, created_by) VALUES ('wikivoyage_guide', :k, 20, 'system')")
                    .param("k", "bulk-" + i).update();
        }
        jobs.touchImporter();
        JsonNode r = request("{\"query\":\"Testpura\"}");
        JsonNode e = r.path("estimate");
        assertThat(e.path("importerRunning").asBoolean()).isTrue();
        assertThat(e.path("jobsAhead").asInt()).isZero();
        assertThat(e.path("minMinutes").asInt()).isEqualTo(1);
        assertThat(e.path("summary").asString()).startsWith("About 1");
        assertThat(e.path("basis").asString()).contains("Nothing is queued ahead").contains("median of 4 imports");

        // A job at higher priority than the request is counted in front of it.
        jdbc.sql("INSERT INTO ingestion_job (job_type, dedup_key, priority, created_by) VALUES ('wikivoyage_guide', 'urgent', 30, 'system')").update();
        JsonNode after = body(mvc.perform(get("/api/requests/" + r.path("trackingCode").asString())).andReturn());
        assertThat(after.path("estimate").path("jobsAhead").asInt()).isEqualTo(1);
        assertThat(after.path("estimate").path("maxMinutes").asInt()).isGreaterThan(e.path("maxMinutes").asInt() - 1);
    }

    @Test
    void estimateAdmitsWhenTheImporterIsNotRunning() throws Exception {
        seedTestvillePlace();
        JsonNode e = request("{\"query\":\"Testpura\"}").path("estimate");
        assertThat(e.path("importerRunning").asBoolean()).isFalse();
        assertThat(e.path("summary").asString()).contains("not running").contains("cannot promise");
    }

    @Test
    void emailIsSentOnceWhenTheGuideIsReadyAndThenErased() throws Exception {
        seedTestvillePlace();
        JsonNode r = request("{\"query\":\"Testpura\",\"email\":\"  Reader@Test.Example \"}");
        String code = r.path("trackingCode").asString();
        assertThat(jdbc.sql("SELECT email FROM request_notification").query(String.class).single()).isEqualTo("reader@test.example");

        assertThat(notifications.deliverDue()).isZero();
        assertThat(mail.sent).isEmpty();

        runner.execute(jobs.claimNext().orElseThrow());
        jdbc.sql("UPDATE destination_profile SET coverage_status = 'usable'").update();
        assertThat(notifications.deliverDue()).isEqualTo(1);
        assertThat(mail.sent).hasSize(1);
        assertThat(mail.sent.get(0).getTo()).containsExactly("reader@test.example");
        assertThat(mail.sent.get(0).getText()).contains("/d/testville").contains("removed from our records");

        assertThat(jdbc.sql("SELECT email IS NULL FROM request_notification").query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT outcome FROM request_notification").query(String.class).single()).isEqualTo("sent_published");
        assertThat(notifications.deliverDue()).isZero();
        assertThat(mail.sent).hasSize(1);
        mvc.perform(get("/api/requests/" + code)).andExpect(jsonPath("$.status").value("published"));
    }

    @Test
    void readersCanAddAnEmailLaterAndDuplicatesAreIgnored() throws Exception {
        seedTestvillePlace();
        String code = request("{\"query\":\"Testpura\"}").path("trackingCode").asString();
        assertThat(jdbc.sql("SELECT count(*) FROM request_notification").query(Long.class).single()).isZero();
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/requests/" + code + "/notify").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"late@test.example\"}")).andExpect(status().isAccepted());
        }
        assertThat(jdbc.sql("SELECT count(*) FROM request_notification").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void readersAreToldWhenThePlaceCannotBeFound() throws Exception {
        request("{\"query\":\"Nowhereabad\",\"email\":\"lost@test.example\"}");
        jdbc.sql("UPDATE destination_request SET status = 'unresolvable'").update();
        assertThat(notifications.deliverDue()).isEqualTo(1);
        assertThat(mail.sent.get(0).getSubject()).contains("could not find");
        assertThat(mail.sent.get(0).getText()).contains("/request?q=Nowhereabad");
        assertThat(jdbc.sql("SELECT outcome FROM request_notification").query(String.class).single()).isEqualTo("sent_not_found");
    }

    @Test
    void unreadableEmailsAreRejectedAndUnansweredOnesExpire() throws Exception {
        seedTestvillePlace();
        mvc.perform(post("/api/requests").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"Testpura\",\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT count(*) FROM destination_request").query(Long.class).single()).isZero();

        request("{\"query\":\"Testpura\",\"email\":\"old@test.example\"}");
        jdbc.sql("UPDATE request_notification SET created_at = now() - interval '91 days'").update();
        notifications.deliverDue();
        assertThat(jdbc.sql("SELECT outcome FROM request_notification").query(String.class).single()).isEqualTo("expired");
        assertThat(jdbc.sql("SELECT email IS NULL FROM request_notification").query(Boolean.class).single()).isTrue();
        assertThat(mail.sent).isEmpty();
    }

    @Test
    void aThinGuideStillCompletesTheRequestAndSaysHonestlyWhatItHas() throws Exception {
        seedTestvillePlace();
        String code = request("{\"query\":\"Testpura\",\"email\":\"starter@test.example\"}").path("trackingCode").asString();
        runner.execute(jobs.claimNext().orElseThrow());
        // Everything the source has is in, but not enough for a full guide.
        jdbc.sql("UPDATE destination_profile SET coverage_status = 'building'").update();

        JsonNode view = body(mvc.perform(get("/api/requests/" + code)).andReturn());
        assertThat(view.path("status").asString()).isEqualTo("building");
        assertThat(view.path("statusLabel").asString()).isEqualTo("Starter guide ready");
        assertThat(view.path("statusDetail").asString()).contains("places so far").contains("can add the places it is missing")
                .doesNotContain("quality bar");

        assertThat(notifications.deliverDue()).as("the reader is not left waiting for content that is not coming").isEqualTo(1);
        assertThat(mail.sent.get(0).getSubject()).isEqualTo("A starter guide for Testville is ready");
        assertThat(mail.sent.get(0).getText()).contains("/d/testville").contains("/contribute/testville").contains("starter guide");
    }

    @Test
    void askingForAStateIsAnsweredWithItsPlacesGuidesRightAway() throws Exception {
        UUID city = seedTestvillePlace();
        ingestGuide(city);
        JsonNode r = request("{\"query\":\"Test State\",\"email\":\"state@test.example\"}");
        assertThat(r.path("status").asString()).isEqualTo("published");
        assertThat(r.path("statusDetail").asString()).contains("Test State is a state").contains("1 place has one");
        assertThat(r.path("destination").path("slug").asString()).isEqualTo("test-state");

        JsonNode detail = body(mvc.perform(get("/api/destinations/test-state")).andReturn());
        assertThat(detail.path("guidedPlaces").asInt()).isEqualTo(1);

        assertThat(notifications.deliverDue()).as("no waiting for a guide that is its places").isEqualTo(1);
        assertThat(mail.sent.get(mail.sent.size() - 1).getSubject()).isEqualTo("Guides for places in Test State");
        assertThat(mail.sent.get(mail.sent.size() - 1).getText()).contains("/d/test-state").contains("pick a town or city");
    }
}
