package io.destinationintel.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.destinationintel.ingestion.run.SyncRunRecorder;
import io.destinationintel.ingestion.wikidata.IndexConsistency;
import io.destinationintel.place.PlaceRepository.PlaceUpsert;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Places implausibly far from their stated district (fictional fixture geography). OSM breaks the
 * tie between Wikidata's coordinates and its district relation; no relation is ever invented.
 */
class IndexConsistencyIT extends IntegrationTest {

    @Autowired IndexConsistency consistency;
    @Autowired SyncRunRecorder recorder;

    private UUID place(String qid, String name, String kind, Double lat, Double lon) {
        return tx.execute(s -> places.upsertFromWikidata(new PlaceUpsert(qid, name, kind, null, lat, lon, null, null, null, 1,
                true, null)));
    }

    private long relations(UUID child, String relation) {
        return jdbc.sql("SELECT count(*) FROM place_relation WHERE child_id = :c AND relation = :r").param("c", child)
                .param("r", relation).query(Long.class).single();
    }

    @Test
    void resolvesFarDistrictsWithOsmAsTieBreaker() {
        UUID stateA = place("Q900000101", "Fixture State A", "state", 12.5, 76.5);
        UUID stateB = place("Q900000102", "Fixture State B", "state", 15.5, 78.5);
        UUID districtX = place("Q900000111", "Fixture District X", "district", 12.30, 76.60);
        UUID districtY = place("Q900000112", "Fixture District Y", "district", 15.00, 78.00);
        tx.executeWithoutResult(s -> {
            places.addRelation(districtX, stateA, "in_state", "wikidata");
            places.addRelation(districtY, stateB, "in_state", "wikidata");
        });
        // Wrong relation: the town sits beside district Y but Wikidata files it under X (and so state A).
        UUID misfiled = place("Q900000121", "Fixture Misfiled Town", "town", 15.05, 78.05);
        // Wrong coordinates: OSM puts the town beside its stated district X.
        UUID misplaced = place("Q900000122", "Fixture Misplaced Town", "town", 15.10, 78.10);
        // Genuinely far but still nearest to its own (large) district.
        UUID remote = place("Q900000123", "Fixture Remote Village", "village", 12.30, 74.30);
        tx.executeWithoutResult(s -> {
            for (UUID p : new UUID[] {misfiled, misplaced, remote}) {
                places.addRelation(p, districtX, "in_district", "wikidata");
                places.addRelation(p, stateA, "in_state", "wikidata");
            }
        });
        jdbc.sql("""
                INSERT INTO place_source_identity (place_id, source_id, external_id, attributes)
                VALUES (:p, 'openstreetmap', 'node/1', '{"place": "town", "latitude": 12.31, "longitude": 76.62}')
                """).param("p", misplaced).update();

        SyncRunRecorder.Run run = recorder.start("wikidata", null, "fixture");
        consistency.checkDistrictRelations(run);

        assertThat(relations(misfiled, "in_district")).as("implausible district dropped").isZero();
        assertThat(relations(misfiled, "in_state")).as("state from the same faulty chain dropped").isZero();
        assertThat(jdbc.sql("SELECT coordinates_source FROM place WHERE id = :p").param("p", misplaced).query(String.class).single())
                .isEqualTo("openstreetmap");
        assertThat(relations(misplaced, "in_district")).as("relation kept once coordinates are corrected").isEqualTo(1);
        assertThat(relations(remote, "in_district")).as("far but nearest to its own district").isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM place_relation r JOIN place d ON d.id = r.parent_id "
                + "WHERE r.child_id = :p AND d.id = :y").param("p", misfiled).param("y", districtY).query(Long.class).single())
                .as("no replacement relation is invented").isZero();
    }
}
