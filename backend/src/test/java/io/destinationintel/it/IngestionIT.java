package io.destinationintel.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.destinationintel.ingestion.JobRepository.JobRow;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IngestionIT extends IntegrationTest {

    private long count(String sql, UUID place) {
        return jdbc.sql(sql).param("p", place).query(Long.class).single();
    }

    @Test
    void ingestsFixtureArticleIntoTypedSourceTaggedFacts() {
        UUID city = seedTestvillePlace();
        JobRow job = ingestGuide(city);
        assertThat(job.status()).isEqualTo("succeeded");

        assertThat(count("SELECT count(*) FROM guide_entry WHERE destination_id = :p AND status = 'published'", city)).isEqualTo(10);
        assertThat(count("SELECT count(*) FROM fact f WHERE f.destination_id = :p AND NOT EXISTS (SELECT 1 FROM evidence e WHERE e.fact_id = f.id)", city))
                .as("every fact has evidence").isZero();
        assertThat(count("SELECT count(*) FROM fact WHERE destination_id = :p AND kind = 'price' AND volatility <> 'volatile'", city)).isZero();
        assertThat(count("SELECT count(*) FROM fact WHERE destination_id = :p AND last_verified_at IS NOT NULL", city))
                .as("ingestion never marks facts verified").isZero();
        String coverage = jdbc.sql("SELECT coverage_status FROM destination_profile WHERE place_id = :p").param("p", city)
                .query(String.class).single();
        assertThat(coverage).isIn("building", "usable", "needs_refresh");
        assertThat(jdbc.sql("SELECT last_success_at FROM source WHERE id = 'wikivoyage-en'").query(OffsetDateTime.class).optional()).isPresent();
        assertThat(count("SELECT count(*) FROM ingestion_job WHERE target_place_id = :p AND job_type IN ('climate', 'place_image') AND status = 'queued'", city))
                .isEqualTo(2);
    }

    @Test
    void rerunningTheSameRevisionCreatesNoDuplicatesAndKeepsVerificationTimestamps() {
        UUID city = seedTestvillePlace();
        ingestGuide(city);
        long entries = count("SELECT count(*) FROM guide_entry WHERE destination_id = :p", city);
        long facts = count("SELECT count(*) FROM fact WHERE destination_id = :p", city);
        long evidence = count("SELECT count(*) FROM evidence e JOIN fact f ON f.id = e.fact_id WHERE f.destination_id = :p", city);
        jdbc.sql("UPDATE fact SET last_verified_at = '2026-01-01T00:00:00Z' WHERE destination_id = :p AND kind = 'summary'").param("p", city).update();

        JobRow second = ingestGuide(city);
        assertThat(second.stats()).contains("\"unchanged\"");
        assertThat(second.stats()).as("re-checking an unchanged article is not a warning").contains("\"syncStatus\": \"succeeded\"");
        assertThat(count("SELECT count(*) FROM guide_entry WHERE destination_id = :p", city)).isEqualTo(entries);
        assertThat(count("SELECT count(*) FROM fact WHERE destination_id = :p", city)).isEqualTo(facts);
        assertThat(count("SELECT count(*) FROM evidence e JOIN fact f ON f.id = e.fact_id WHERE f.destination_id = :p", city)).isEqualTo(evidence);
        assertThat(jdbc.sql("SELECT last_verified_at FROM fact WHERE destination_id = :p AND kind = 'summary'").param("p", city)
                .query(OffsetDateTime.class).single()).isEqualTo(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void newRevisionSupersedesChangedValuesKeepsHistoryAndHidesRemovedListings() {
        UUID city = seedTestvillePlace();
        ingestGuide(city);
        UUID oldPrice = jdbc.sql("""
                        SELECT f.id FROM fact f JOIN guide_entry e ON e.id = f.entry_id
                        WHERE e.destination_id = :p AND e.ingest_key = 'wv:42:eat:hotel-example:1' AND f.kind = 'price'
                        """).param("p", city).query(UUID.class).single();

        SourceStub.get().article("Testville", new SourceStub.Article(42, 1002, "2026-10-10T00:00:00Z", "testville-v2.wiki"));
        JobRow job = ingestGuide(city);
        assertThat(job.status()).isEqualTo("succeeded");

        assertThat(jdbc.sql("SELECT status FROM fact WHERE id = :id").param("id", oldPrice).query(String.class).single()).isEqualTo("superseded");
        record Current(UUID id, String valueText, java.time.LocalDate observedAt, UUID supersedesFactId) {}
        Current current = jdbc.sql("""
                        SELECT f.id, f.value_text, f.observed_at, f.supersedes_fact_id FROM fact f JOIN guide_entry e ON e.id = f.entry_id
                        WHERE e.ingest_key = 'wv:42:eat:hotel-example:1' AND f.kind = 'price' AND f.status = 'published'
                        """).query(Current.class).single();
        assertThat(current.valueText()).isEqualTo("₹45");
        assertThat(current.observedAt()).isEqualTo(java.time.LocalDate.of(2026, 9, 1));
        assertThat(current.supersedesFactId()).isEqualTo(oldPrice);
        assertThat(count("SELECT count(*) FROM fact_observation WHERE fact_id = :p AND change_type = 'source_update'", current.id())).isEqualTo(1);

        assertThat(jdbc.sql("SELECT status FROM guide_entry WHERE destination_id = :p AND title = 'Quiet Lake'").param("p", city)
                .query(String.class).single()).isEqualTo("hidden");
    }

    @Test
    void missingArticleFailsPermanentlyAndIsRecorded() {
        UUID city = seedTestvillePlace();
        SourceStub.get().remove("Testville");
        JobRow job = ingestGuide(city);
        assertThat(job.status()).isEqualTo("failed");
        assertThat(job.lastError()).contains("was not found");
        assertThat(jdbc.sql("SELECT last_status FROM place_source_sync WHERE place_id = :p AND source_id = 'wikivoyage-en'")
                .param("p", city).query(String.class).single()).isEqualTo("not_found");
    }

    @Test
    void disabledSourceIsNotConsumed() {
        UUID city = seedTestvillePlace();
        jdbc.sql("UPDATE source SET enabled = false WHERE id = 'wikivoyage-en'").update();
        int before = SourceStub.get().requests.get();
        JobRow job = ingestGuide(city);
        assertThat(job.status()).isEqualTo("failed");
        assertThat(job.lastError()).contains("disabled");
        assertThat(SourceStub.get().requests.get()).isEqualTo(before);
    }

    @Test
    void enqueueIsIdempotentWhileAJobIsActive() {
        UUID city = seedTestvillePlace();
        UUID a = jobs.enqueue("climate", "open-meteo", city, java.util.Map.of(), "climate:" + city, 1, "system");
        UUID b = jobs.enqueue("climate", "open-meteo", city, java.util.Map.of(), "climate:" + city, 5, "system");
        assertThat(b).isEqualTo(a);
        assertThat(jobs.find(a).orElseThrow().priority()).isEqualTo(5);
    }

    @Test
    void graduatedDestinationsStopConsumingCollectedSources() {
        UUID city = seedTestvillePlace();
        ingestGuide(city);
        jdbc.sql("UPDATE destination_profile SET graduated_at = now() WHERE place_id = :p").param("p", city).update();
        JobRow job = ingestGuide(city);
        assertThat(job.status()).isEqualTo("succeeded");
        assertThat(job.stats()).contains("graduated");
    }

    @Test
    void aGuideRefreshDoesNotRefetchWeatherOrPhotosThatAreStillFresh() {
        UUID city = seedTestvillePlace();
        ingestGuide(city);
        jdbc.sql("DELETE FROM ingestion_job WHERE job_type IN ('climate', 'place_image')").update();
        // Weather and a photo were fetched recently.
        jdbc.sql("""
                INSERT INTO place_source_sync (place_id, source_id, external_id, last_revision, last_synced_at, last_status)
                VALUES (:p, 'open-meteo', 'x', 'x', now() - interval '10 days', 'succeeded')""").param("p", city).update();
        jdbc.sql("""
                INSERT INTO place_image (place_id, source_id, file_title, image_url, page_url, licence_name, fetched_at)
                VALUES (:p, 'wikimedia-commons', 'x.jpg', 'https://upload.wikimedia.org/x.jpg', 'https://commons.wikimedia.org/wiki/File:x.jpg', 'CC0', now())""")
                .param("p", city).update();

        SourceStub.get().article("Testville", new SourceStub.Article(42, 1002, "2026-10-10T00:00:00Z", "testville-v2.wiki"));
        try {
            ingestGuide(city);
        } finally {
            SourceStub.get().article("Testville", new SourceStub.Article(42, 1001, "2026-10-01T00:00:00Z", "testville-v1.wiki"));
        }
        assertThat(count("SELECT count(*) FROM ingestion_job WHERE target_place_id = :p AND job_type IN ('climate', 'place_image')", city))
                .as("ten-year weather averages and a recent photo are not fetched again").isZero();

        jdbc.sql("UPDATE place_source_sync SET last_synced_at = now() - interval '400 days' WHERE place_id = :p AND source_id = 'open-meteo'")
                .param("p", city).update();
        ingestGuide(city);
        assertThat(count("SELECT count(*) FROM ingestion_job WHERE target_place_id = :p AND job_type = 'climate'", city))
                .as("but a year-old one is refreshed").isEqualTo(1);
    }
}
