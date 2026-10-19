package io.destinationintel.it;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.destinationintel.ingestion.JobRepository;
import io.destinationintel.ingestion.JobRunner;
import io.destinationintel.place.PlaceRepository;
import io.destinationintel.place.PlaceRepository.PlaceUpsert;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base for integration tests: a real PostgreSQL + PostGIS database (Testcontainers), Flyway
 * migrations, the full Spring context and MockMvc. External sources are replaced by
 * {@link SourceStub} and outgoing mail by {@link CapturingMailSender}; both exist only in tests.
 */
@SpringBootTest(properties = {
        "app.ingestion.enabled=false",
        "app.ingestion.bootstrap-on-startup=false",
        "app.ingestion.request-pause=0s",
        "app.admin-emails=admin@test.example",
        "app.rate-limits.requests-per-hour=1000",
        "app.rate-limits.sign-in-per-hour=1000",
        "spring.mail.host=smtp.test.invalid",
        "logging.level.io.destinationintel=WARN"
})
@AutoConfigureMockMvc
@Import(IntegrationTest.TestBeans.class)
public abstract class IntegrationTest {

    static final PostgreSQLContainer POSTGRES;

    static {
        String image = System.getProperty("test.postgis.image");
        if (image == null || image.isBlank() || image.startsWith("${")) {
            image = System.getenv().getOrDefault("TEST_POSTGIS_IMAGE", "postgis/postgis:17-3.5");
        }
        POSTGRES = new PostgreSQLContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"));
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.ingestion.wikivoyage-api-url", () -> SourceStub.get().baseUrl() + "/wv/api.php");
        registry.add("app.ingestion.wikidata-api-url", () -> SourceStub.get().baseUrl() + "/wd/api.php");
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        CapturingMailSender capturingMailSender() {
            return new CapturingMailSender();
        }
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcClient jdbc;
    @Autowired protected JsonMapper json;
    @Autowired protected CapturingMailSender mail;
    @Autowired protected PlaceRepository places;
    @Autowired protected JobRepository jobs;
    @Autowired protected JobRunner runner;
    @Autowired protected TransactionTemplate tx;
    @Autowired protected CacheManager caches;

    @BeforeEach
    void resetDatabase() {
        jdbc.sql("""
                TRUNCATE place, app_user, ingestion_job, destination_request, audit_log, login_token,
                         spring_session, source_sync_run, quality_report, poi_match_review, poi_source_record, poi,
                         place_source_identity CASCADE
                """).update();
        jdbc.sql("""
                UPDATE source SET enabled = true, last_success_at = NULL, last_error = NULL, last_error_at = NULL,
                       consecutive_failures = 0, last_publication_at = NULL, quality_status = 'not_evaluated',
                       quality_evaluated_at = NULL, dataset_version = NULL, publish_enabled = true""").update();
        caches.getCacheNames().forEach(n -> caches.getCache(n).clear());
        mail.sent.clear();
        SourceStub.get().article("Testville", new SourceStub.Article(42, 1001, "2026-10-01T00:00:00Z", "testville-v1.wiki"));
    }

    /** Indexes a state and the fictional city "Testville" (fixture data, tests only). */
    protected UUID seedTestvillePlace() {
        return tx.execute(s -> {
            UUID state = places.upsertFromWikidata(new PlaceUpsert("Q900000001", "Test State", "state", "state of India",
                    12.9, 76.0, 1_000_000L, null, "IN-TS", 50, true, null));
            places.addAlias(state, "Test State", "en", "label", "wikidata");
            UUID city = places.upsertFromWikidata(new PlaceUpsert("Q900000002", "Testville", "city", "city in Test State, India",
                    12.305, 76.655, 900_000L, "Testville", null, 60, true, "Test State"));
            places.addAlias(city, "Testville", "en", "label", "wikidata");
            places.addAlias(city, "Testpura", "en", "alternate", "wikidata");
            places.addAlias(city, "ಟೆಸ್ಟ್‌ವಿಲ್", "kn", "local_script", "wikidata");
            places.addRelation(city, state, "in_state", "wikidata");
            return city;
        });
    }

    /** Runs a Wikivoyage ingestion job for the place through the real job queue and runner. */
    protected JobRepository.JobRow ingestGuide(UUID placeId) {
        UUID jobId = jobs.enqueue("wikivoyage_guide", "wikivoyage-en", placeId, Map.of(), "wikivoyage_guide:" + placeId + ":" + UUID.randomUUID(),
                100, "admin");
        JobRepository.JobRow job = jobs.claimNext().orElseThrow();
        runner.execute(job);
        return jobs.find(jobId).orElseThrow();
    }

    /** Signs a contributor in through the real email-link flow and returns the session cookie. */
    protected Cookie signIn(String email, String displayName) throws Exception {
        mvc.perform(post("/api/auth/email/start").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"displayName\":\"" + displayName + "\"}"))
                .andExpect(status().isAccepted());
        String token = mail.lastTokenFor(email);
        MvcResult r = mvc.perform(post("/api/auth/email/verify").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk()).andReturn();
        Cookie session = r.getResponse().getCookie("SESSION");
        if (session == null) {
            throw new IllegalStateException("No session cookie issued");
        }
        return session;
    }

    protected Cookie signInWithLicence(String email, String name) throws Exception {
        Cookie c = signIn(email, name);
        mvc.perform(post("/api/me/licence").with(csrf()).cookie(c).contentType(MediaType.APPLICATION_JSON).content("{\"accept\":true}"))
                .andExpect(status().isOk());
        return c;
    }

    protected JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    protected UUID userId(String email) {
        return jdbc.sql("SELECT id FROM app_user WHERE email = :e").param("e", email).query(UUID.class).single();
    }
}
