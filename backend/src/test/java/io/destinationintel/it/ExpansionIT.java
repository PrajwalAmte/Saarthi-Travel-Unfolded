package io.destinationintel.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.destinationintel.ingestion.IngestionScheduler;
import io.destinationintel.place.PlaceRepository.PlaceUpsert;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "app.ingestion.expansion-per-day=40")
class ExpansionIT extends IntegrationTest {

    @Autowired IngestionScheduler scheduler;

    @Test
    void dailyExpansionAddsIndianDestinationsNotRegionsOrRangesAbroad() {
        seedTestvillePlace();
        tx.executeWithoutResult(s -> {
            UUID state = places.findByWikidataId("Q900000001").orElseThrow().id();
            UUID town = places.upsertFromWikidata(new PlaceUpsert("Q900000010", "Otherville", "town", "town in Test State, India",
                    12.0, 76.0, 50_000L, "Otherville", null, 20, true, "Test State"));
            places.addRelation(town, state, "in_state", "wikidata");
            // Huge sitelink counts would put these first, but a region or range is not a place to visit.
            places.upsertFromWikidata(new PlaceUpsert("Q900000011", "Mountain Range", "region", "mountain range in Asia",
                    30.0, 80.0, null, "Mountain Range", null, 900, true, null));
            places.upsertFromWikidata(new PlaceUpsert("Q900000012", "Foreign Town", "town", "town abroad",
                    40.0, 60.0, 90_000L, "Foreign Town", null, 500, true, null));
        });

        scheduler.planRefreshes("scheduler");

        List<String> queued = jdbc.sql("""
                        SELECT p.name FROM ingestion_job j JOIN place p ON p.id = j.target_place_id
                        WHERE j.job_type = 'wikivoyage_guide' AND j.status = 'queued' ORDER BY p.name""")
                .query(String.class).list();
        assertThat(queued).containsExactly("Otherville", "Testville");
    }

    @Test
    void guidesReadByAnOlderParserAreReadAgain() {
        UUID city = seedTestvillePlace();
        ingestGuide(city);
        jdbc.sql("DELETE FROM ingestion_job").update();
        scheduler.planRefreshes("scheduler");
        assertThat(jdbc.sql("SELECT count(*) FROM ingestion_job WHERE job_type = 'wikivoyage_guide' AND target_place_id = :p")
                .param("p", city).query(Long.class).single()).as("fresh and current: nothing to do").isZero();

        jdbc.sql("UPDATE place_source_sync SET parser_version = parser_version - 1 WHERE place_id = :p").param("p", city).update();
        scheduler.planRefreshes("scheduler");
        assertThat(jdbc.sql("SELECT count(*) FROM ingestion_job WHERE job_type = 'wikivoyage_guide' AND target_place_id = :p")
                .param("p", city).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void guidesThatNeverGotWeatherDataAreCaughtUpInSmallBatches() {
        UUID city = seedTestvillePlace();
        ingestGuide(city);
        jdbc.sql("DELETE FROM ingestion_job").update();
        jdbc.sql("DELETE FROM place_source_sync WHERE source_id = 'open-meteo'").update();
        var plan = scheduler.planRefreshes("scheduler");
        assertThat(plan.get("climateMissing")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM ingestion_job WHERE job_type = 'climate' AND target_place_id = :p")
                .param("p", city).query(Long.class).single()).isEqualTo(1);
        assertThat(scheduler.planRefreshes("scheduler").get("climateMissing")).as("already queued").isEqualTo(0);
    }
}
