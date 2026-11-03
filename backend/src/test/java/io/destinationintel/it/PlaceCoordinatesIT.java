package io.destinationintel.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.destinationintel.place.PlaceRepository.PlaceUpsert;
import io.destinationintel.place.PlaceRow;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A misplaced point moves only when Wikidata and Wikivoyage agree; Wikivoyage alone is not trusted. */
class PlaceCoordinatesIT extends IntegrationTest {

    @AfterEach
    void clearStub() {
        SourceStub.get().articleCoordinates.clear();
        SourceStub.get().wikidataEntities.clear();
    }

    private UUID place(String qid, String name, double lat, double lon) {
        return tx.execute(s -> places.upsertFromWikidata(new PlaceUpsert(qid, name, "town", "town in Gujarat, India", lat, lon, 5_000L, name, null, 5, true, null)));
    }

    private void statements(String qid, double[]... points) {
        StringBuilder claims = new StringBuilder();
        for (double[] p : points) {
            claims.append(claims.length() == 0 ? "" : ",").append("{\"rank\":\"normal\",\"mainsnak\":{\"datavalue\":{\"value\":{\"latitude\":")
                    .append(p[0]).append(",\"longitude\":").append(p[1]).append("}}}}");
        }
        SourceStub.get().wikidataEntities.put(qid, "{\"id\":\"" + qid + "\",\"claims\":{\"P625\":[" + claims + "]}}");
    }

    private void runChecks() {
        jobs.enqueue("wikivoyage_redirects", "wikivoyage-en", null, Map.of(), "wikivoyage_redirects:" + UUID.randomUUID(), 1000, "admin");
        runner.execute(jobs.claimNext().orElseThrow());
    }

    private PlaceRow row(UUID id) {
        return places.findById(id).orElseThrow();
    }

    @Test
    void twoAgreeingSourcesMoveAPlaceAndComputedDataIsCleared() {
        UUID kevadia = place("Q900000101", "Kevadia", 23.682519, 73.95729);
        statements("Q900000101", new double[] {21.875061, 73.690738}, new double[] {23.682519, 73.95729});
        SourceStub.get().articleCoordinates.put("Kevadia", new double[] {21.875061, 73.690739});
        jdbc.sql("""
                INSERT INTO place_source_sync (place_id, source_id, external_id, last_revision, last_synced_at, last_status)
                VALUES (:p, 'open-meteo', 'climate', 'x', now(), 'succeeded')""").param("p", kevadia).update();

        runChecks();
        assertThat(row(kevadia).latitude()).isEqualTo(21.875061);
        assertThat(jdbc.sql("SELECT coordinates_source FROM place WHERE id = :p").param("p", kevadia).query(String.class).single()).isEqualTo("two_sources");
        assertThat(jdbc.sql("SELECT count(*) FROM place_source_sync WHERE place_id = :p AND source_id = 'open-meteo'")
                .param("p", kevadia).query(Integer.class).single()).as("weather is worked out again").isZero();

        place("Q900000101", "Kevadia", 23.682519, 73.95729);
        assertThat(row(kevadia).latitude()).as("re-indexing keeps the confirmed point").isEqualTo(21.875061);
    }

    @Test
    void wikivoyageAloneDoesNotMoveAPlace() {
        // Rajgad Fort: one Wikidata point, and it is the Wikivoyage article that is wrong.
        UUID fort = place("Q900000102", "Rajgad", 18.2459, 73.6821);
        statements("Q900000102", new double[] {18.2459, 73.6821});
        SourceStub.get().articleCoordinates.put("Rajgad", new double[] {22.5667, 73.6667});
        // Several Wikidata points, none near the article: nothing agrees.
        UUID other = place("Q900000103", "Otherplace", 20.0, 75.0);
        statements("Q900000103", new double[] {20.0, 75.0}, new double[] {20.5, 75.5});
        SourceStub.get().articleCoordinates.put("Otherplace", new double[] {24.0, 78.0});

        runChecks();
        assertThat(row(fort).latitude()).isEqualTo(18.2459);
        assertThat(row(other).latitude()).isEqualTo(20.0);
    }
}
