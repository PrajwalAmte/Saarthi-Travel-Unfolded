package io.destinationintel.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.destinationintel.poi.MatchReviewResolver;
import io.destinationintel.poi.PoiDuplicateMergeHandler;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Places listed twice become one, with every merge able to be undone exactly. */
class PoiMergeIT extends IntegrationTest {

    @Autowired MatchReviewResolver resolver;
    @Autowired PoiDuplicateMergeHandler handler;

    private UUID record(String external, UUID poi, String name, String group, String phone, double lat) {
        return jdbc.sql("""
                        INSERT INTO poi_source_record (source_id, external_id, poi_id, name, category, category_group, latitude, longitude, attributes, match_confidence)
                        VALUES ('openstreetmap', :x, :p, :n, 'hotel', :g, :lat, 76.6, CAST(:a AS jsonb), 'new') RETURNING id""")
                .param("x", external).param("p", poi).param("n", name).param("g", group).param("lat", lat)
                .param("a", phone == null ? "{}" : "{\"phone\":\"" + phone + "\"}").query(UUID.class).single();
    }

    private UUID place(String name, String phone, double lat) {
        UUID poi = jdbc.sql("""
                        INSERT INTO poi (name, category, category_group, latitude, longitude, primary_source_id)
                        VALUES (:n, 'hotel', 'stay', :lat, 76.6, 'openstreetmap') RETURNING id""")
                .param("n", name).param("lat", lat).query(UUID.class).single();
        UUID seed = record("seed-" + poi, poi, name, "stay", phone, lat);
        jdbc.sql("UPDATE poi SET seed_record_id = :s WHERE id = :p").param("s", seed).param("p", poi).update();
        return poi;
    }

    private UUID newRecord(String name, double lat, UUID... candidates) {
        UUID record = jdbc.sql("""
                        INSERT INTO poi_source_record (source_id, external_id, name, category, category_group, latitude, longitude, attributes, match_confidence)
                        VALUES ('overture-places', :x, :n, 'hotel', 'stay', :lat, 76.6, '{}', 'possible') RETURNING id""")
                .param("x", "ov-" + name).param("n", name).param("lat", lat).query(UUID.class).single();
        for (UUID c : candidates) {
            jdbc.sql("INSERT INTO poi_match_review (record_id, candidate_poi_id, score) VALUES (:r, :c, 0.5)").param("r", record).param("c", c).update();
        }
        return record;
    }

    private String statusOf(UUID poi) {
        return jdbc.sql("SELECT status FROM poi WHERE id = :p").param("p", poi).query(String.class).single();
    }

    private int recordsOn(UUID poi) {
        return jdbc.sql("SELECT count(*) FROM poi_source_record WHERE poi_id = :p").param("p", poi).query(Integer.class).single();
    }

    private UUID entryOn(UUID poi) {
        UUID city = seedTestvillePlace();
        return jdbc.sql("INSERT INTO guide_entry (destination_id, section, title, origin, poi_id) VALUES (:d, 'stay', 'Hotel', 'ingested', :p) RETURNING id")
                .param("d", city).param("p", poi).query(UUID.class).single();
    }

    @Test
    void aRecordMatchingTwoPlacesMergesThemAndTheMergeCanBeUndoneForGood() throws Exception {
        UUID first = place("Hotel Kediyoor", null, 12.3000);
        UUID second = place("Kediyoor Hotels", null, 12.3001);
        UUID entry = entryOn(second);
        UUID secondSeed = jdbc.sql("SELECT seed_record_id FROM poi WHERE id = :p").param("p", second).query(UUID.class).single();
        UUID record = newRecord("Hotel Kediyoor", 12.30005, first, second);

        resolver.resolveOpen(null);

        UUID joined = jdbc.sql("SELECT poi_id FROM poi_source_record WHERE id = :r").param("r", record).query(UUID.class).single();
        UUID other = joined.equals(first) ? second : first;
        assertThat(statusOf(other)).isEqualTo("removed");
        assertThat(statusOf(joined)).isEqualTo("active");
        assertThat(recordsOn(other)).isZero();
        assertThat(recordsOn(joined)).as("its own, the other place's and the new record").isEqualTo(3);
        assertThat(jdbc.sql("SELECT poi_id FROM guide_entry WHERE id = :e").param("e", entry).query(UUID.class).single()).isEqualTo(joined);

        UUID merge = jdbc.sql("SELECT id FROM poi_merge").query(UUID.class).single();
        Cookie admin = signIn("admin@test.example", "Admin");
        mvc.perform(post("/api/admin/poi-merges/" + merge + "/undo").with(csrf()).cookie(admin)).andExpect(status().isOk());

        assertThat(statusOf(first)).isEqualTo("active");
        assertThat(statusOf(second)).isEqualTo("active");
        assertThat(recordsOn(other)).isEqualTo(1);
        assertThat(recordsOn(joined)).isEqualTo(2);
        assertThat(jdbc.sql("SELECT poi_id FROM guide_entry WHERE id = :e").param("e", entry).query(UUID.class).single()).isEqualTo(second);
        assertThat(jdbc.sql("SELECT seed_record_id FROM poi WHERE id = :p").param("p", second).query(UUID.class).single()).isEqualTo(secondSeed);

        mvc.perform(post("/api/admin/poi-merges/" + merge + "/undo").with(csrf()).cookie(admin)).andExpect(status().isConflict());
        handler.run(null);
        assertThat(statusOf(other)).as("an undone merge is not repeated").isEqualTo("active");
    }

    @Test
    void placesAreLeftAloneWhenTheEvidenceDoesNotSayTheyAreOne() {
        UUID hotel = place("Hotel Lotus", "+911111111111", 12.3100);
        UUID grand = place("Hotel Lotus Grand", "+912222222222", 12.3101);
        newRecord("Hotel Lotus", 12.3100, hotel, grand);

        resolver.resolveOpen(null);

        assertThat(statusOf(hotel)).isEqualTo("active");
        assertThat(statusOf(grand)).as("one name merely contains the other").isEqualTo("active");
        assertThat(jdbc.sql("SELECT count(*) FROM poi_merge").query(Integer.class).single()).isZero();
    }

    @Test
    void theJobHandlesEarlierDecisionsAndRecordsTheShadowVerdictWithoutActingOnIt() {
        UUID joined = place("Hotel Manorama", null, 12.3200);
        UUID duplicate = place("hotel manorama", null, 12.3201);
        UUID record = record("old", joined, "Hotel Manorama", "stay", null, 12.3200);
        jdbc.sql("INSERT INTO poi_match_review (record_id, candidate_poi_id, score, status, resolution) VALUES (:r, :c, 0.5, 'distinct', 'another_candidate_matched_better')")
                .param("r", record).param("c", duplicate).update();

        UUID falls = place("Dainthlen falls", null, 12.3300);
        UUID walker = record("walker", null, "Dainthlen Waterfalls Viewpoint", "stay", null, 12.33005);
        UUID fallsReview = jdbc.sql("INSERT INTO poi_match_review (record_id, candidate_poi_id, score, status, resolution) VALUES (:r, :c, 0.5, 'distinct', 'names_differ') RETURNING id")
                .param("r", walker).param("c", falls).query(UUID.class).single();

        var stats = handler.run(null);

        assertThat(stats.get("placesMerged")).isEqualTo(1);
        assertThat(statusOf(duplicate)).isEqualTo("removed");
        assertThat(jdbc.sql("SELECT shadow_rule FROM poi_match_review WHERE id = :id").param("id", fallsReview).query(String.class).single()).isEqualTo("name_words_shadow");
        assertThat(jdbc.sql("SELECT status FROM poi_match_review WHERE id = :id").param("id", fallsReview).query(String.class).single()).isEqualTo("distinct");
        assertThat(handler.run(null).get("placesMerged")).as("safe to run again").isEqualTo(0);
    }
}
