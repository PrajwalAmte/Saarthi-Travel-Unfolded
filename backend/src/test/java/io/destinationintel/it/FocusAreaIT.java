package io.destinationintel.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.destinationintel.ingestion.IngestionScheduler;
import io.destinationintel.place.PlaceRepository.PlaceUpsert;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "app.ingestion.focus=testville:100")
class FocusAreaIT extends IntegrationTest {

    @Autowired IngestionScheduler scheduler;

    private List<String> queuedGuides() {
        return jdbc.sql("""
                        SELECT p.name FROM ingestion_job j JOIN place p ON p.id = j.target_place_id
                        WHERE j.job_type = 'wikivoyage_guide' AND j.status = 'queued' ORDER BY p.name""")
                .query(String.class).list();
    }

    @Test
    void fillsGuidesAroundTheFocusCityOnlyAndOnlyOnce() {
        UUID city = seedTestvillePlace();
        ingestGuide(city);
        tx.executeWithoutResult(s -> {
            // About 60 km away, has an article, no guide yet.
            places.upsertFromWikidata(new PlaceUpsert("Q900000030", "Hillstation", "town", "hill town in Test State, India",
                    12.85, 76.65, 20_000L, "Hillstation", null, 12, true, "Test State"));
            // About 300 km away: outside the focus radius.
            places.upsertFromWikidata(new PlaceUpsert("Q900000031", "Faraway", "town", "town far away",
                    15.0, 76.65, 20_000L, "Faraway", null, 90, true, "Test State"));
            // Close, but no article to import.
            places.upsertFromWikidata(new PlaceUpsert("Q900000032", "Noarticle", "town", "town",
                    12.4, 76.7, 5_000L, null, null, 50, true, "Test State"));
        });
        jdbc.sql("DELETE FROM ingestion_job").update();

        Map<String, Object> plan = scheduler.planRefreshes("scheduler");
        assertThat(plan.get("focus")).isEqualTo(Map.of("testville", 1));
        assertThat(queuedGuides()).containsExactly("Hillstation");
        assertThat(jdbc.sql("SELECT count(*) FROM ingestion_job WHERE job_type = 'road_routes' AND status = 'queued'")
                .query(Integer.class).single()).as("drive times for the weekend-trips page are planned too").isEqualTo(1);

        scheduler.planRefreshes("scheduler");
        assertThat(queuedGuides()).as("not queued twice").containsExactly("Hillstation");
    }
}
