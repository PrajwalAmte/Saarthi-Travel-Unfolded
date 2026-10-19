package io.destinationintel.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

class RequestIT extends IntegrationTest {

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
}
