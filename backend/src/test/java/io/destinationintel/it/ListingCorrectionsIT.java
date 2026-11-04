package io.destinationintel.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.destinationintel.ingestion.IngestionScheduler;
import io.destinationintel.place.PlaceRepository.PlaceUpsert;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Migration V27 run against stored data, since on an empty database it proves nothing. */
class ListingCorrectionsIT extends IntegrationTest {

    @Autowired DataSource dataSource;
    @Autowired IngestionScheduler scheduler;

    private void applyCorrections() throws IOException {
        new JdbcTemplate(dataSource).execute(new ClassPathResource("db/migration/V27__listing_quality_corrections.sql")
                .getContentAsString(StandardCharsets.UTF_8));
    }

    private UUID mappedPlace(String name, String source, String category, String group) {
        UUID poi = jdbc.sql("""
                        INSERT INTO poi (name, category, category_group, latitude, longitude, primary_source_id)
                        VALUES (:n, :c, :g, 12.3, 76.6, :s) RETURNING id""")
                .param("n", name).param("c", category).param("g", group).param("s", source).query(UUID.class).single();
        addRecord(poi, name, source, category);
        return poi;
    }

    private void addRecord(UUID poi, String name, String source, String category) {
        jdbc.sql("""
                INSERT INTO poi_source_record (source_id, external_id, poi_id, name, category, latitude, longitude)
                VALUES (:s, :x, :p, :n, :c, 12.3, 76.6)""")
                .param("s", source).param("x", source + ":" + name).param("p", poi).param("n", name).param("c", category).update();
    }

    private UUID listing(UUID destination, String section, String title, String tag, String term, String description) {
        UUID entry = jdbc.sql("""
                        INSERT INTO guide_entry (destination_id, section, title, origin, attributes)
                        VALUES (:d, :s, :t, 'ingested', CAST(:a AS jsonb)) RETURNING id""")
                .param("d", destination).param("s", section).param("t", title)
                .param("a", "{\"interests\":[{\"tag\":\"" + tag + "\",\"basis\":\"Source text mentions “" + term + "”\"}]}")
                .query(UUID.class).single();
        if (description != null) {
            jdbc.sql("""
                    INSERT INTO fact (destination_id, entry_id, section, kind, value_text, volatility, origin, observed_basis)
                    VALUES (:d, :e, :s, 'description', :v, 'stable', 'ingested', 'unknown')""")
                    .param("d", destination).param("e", entry).param("s", section).param("v", description).update();
        }
        return entry;
    }

    private String interests(UUID entry) {
        return jdbc.sql("SELECT attributes ->> 'interests' FROM guide_entry WHERE id = :e").param("e", entry)
                .query(String.class).optional().orElse(null);
    }

    private void review(UUID entry, UUID destination, String status) {
        jdbc.sql("""
                INSERT INTO ai_review (entry_id, destination_id, input_hash, prompt_version, model, fits_section, in_destination,
                                       confidence, reason, flagged, status)
                VALUES (:e, :d, 'h', 3, 'm', true, 'yes', 1, 'r', false, :s)""")
                .param("e", entry).param("d", destination).param("s", status).update();
    }

    private String[] issues(UUID entry) {
        return jdbc.sql("SELECT array_to_string(issues, ',') FROM ai_review WHERE entry_id = :e").param("e", entry)
                .query(String.class).single().split(",");
    }

    @Test
    void overtureHistoricSitesLeaveTheMapUnlessAnotherSourceBacksThem() throws IOException {
        UUID apartments = mappedPlace("Subal Apartments", "overture-places", "heritage_site", "see");
        UUID court = mappedPlace("Alipore Court", "overture-places", "heritage_site", "see");
        addRecord(court, "Alipore Court", "openstreetmap", "heritage_site");
        UUID restaurant = mappedPlace("Dosa Corner", "overture-places", "restaurant", "eat");
        UUID osmFort = mappedPlace("Old Fort", "openstreetmap", "heritage_site", "see");

        applyCorrections();

        assertThat(status(apartments)).isEqualTo("removed");
        assertThat(status(court)).as("OpenStreetMap also has it").isEqualTo("active");
        assertThat(status(restaurant)).isEqualTo("active");
        assertThat(status(osmFort)).isEqualTo("active");
    }

    private String status(UUID poi) {
        return jdbc.sql("SELECT status FROM poi WHERE id = :p").param("p", poi).query(String.class).single();
    }

    @Test
    void businessesKeepOnlyInterestsFromOrdinaryWords() throws IOException {
        UUID city = seedTestvillePlace();
        UUID nameOnly = listing(city, "stay", "Hotel Grand Park", "nature", "park", null);
        UUID address = listing(city, "eat", "Arsalan", "nature", "park", "Mughlai food near Park Circus.");
        UUID ordinary = listing(city, "stay", "Lakeside", "nature", "lake", "Rooms with lake views.");
        UUID boutique = listing(city, "stay", "The Manor", "shopping", "boutique", "A boutique hotel.");
        UUID sight = listing(city, "see", "Nehru Park", "nature", "park", "Old trees.");
        UUID bar = listing(city, "drink", "Hop Pub", "nightlife", "pub", "Craft beers.");

        applyCorrections();

        assertThat(interests(nameOnly)).isNull();
        assertThat(interests(address)).isNull();
        assertThat(interests(ordinary)).contains("nature");
        assertThat(interests(boutique)).isNull();
        assertThat(interests(sight)).as("a sight is what its name says").contains("nature");
        assertThat(interests(bar)).contains("nightlife");
    }

    @Test
    void topicsStopBeingDestinationsAndRemovedPlacesLoseTheirListings() throws IOException {
        UUID city = seedTestvillePlace();
        UUID[] places2 = tx.execute(s -> new UUID[] {
                places.upsertFromWikidata(new PlaceUpsert("Q900000040", "travel warning", "other", null, null, null, null, "Travel warning", null, 1, true, null)),
                places.upsertFromWikidata(new PlaceUpsert("Q900000041", "cinema of India", "region", null, null, null, null, "Cinema of India", null, 1, true, null))});
        UUID topic = places2[0];
        UUID removed = places2[1];
        jdbc.sql("UPDATE place SET lifecycle_status = 'removed', is_destination = false WHERE id = :p").param("p", removed).update();
        UUID hidden = listing(removed, "see", "Film City", "family", "kids", null);
        UUID kept = listing(city, "see", "Film Museum", "culture", "museum", "A museum.");

        applyCorrections();

        assertThat(jdbc.sql("SELECT lifecycle_status FROM place WHERE id = :p").param("p", topic).query(String.class).single()).isEqualTo("removed");
        assertThat(jdbc.sql("SELECT lifecycle_status FROM place WHERE id = :p").param("p", city).query(String.class).single()).isEqualTo("active");
        assertThat(jdbc.sql("SELECT status FROM guide_entry WHERE id = :e").param("e", hidden).query(String.class).single()).isEqualTo("hidden");
        assertThat(jdbc.sql("SELECT status FROM guide_entry WHERE id = :e").param("e", kept).query(String.class).single()).isEqualTo("published");
    }

    @Test
    void theShortestListingsAreMarkedThin() throws IOException {
        UUID city = seedTestvillePlace();
        UUID thin = listing(city, "eat", "San Gimignano", "food", "x", "Italian style food.");
        UUID empty = listing(city, "stay", "Park Plaza", "food", "x", null);
        UUID full = listing(city, "eat", "Arsalan", "food", "x", "Biryani and Mughlai food, with firni and mutton kosha.");
        review(thin, city, "clear");
        review(empty, city, "clear");
        review(full, city, "clear");

        applyCorrections();

        assertThat(issues(thin)).containsExactly("too_thin");
        assertThat(issues(empty)).containsExactly("too_thin");
        assertThat(issues(full)).containsExactly("");
    }

    @Test
    void aRemovedPlaceIsNoLongerRefreshed() {
        UUID removed = tx.execute(s -> places.upsertFromWikidata(new PlaceUpsert("Q900000041", "Cinema", "region", null, 12.3, 76.6, null, "Cinema", null, 1, true, null)));
        jdbc.sql("UPDATE place SET lifecycle_status = 'removed', is_destination = false WHERE id = :p").param("p", removed).update();
        for (String source : new String[] {"wikivoyage-en", "open-meteo"}) {
            jdbc.sql("INSERT INTO place_source_sync (place_id, source_id, external_id, last_synced_at) VALUES (:p, :s, 'Cinema', now() - interval '3 years')")
                    .param("p", removed).param("s", source).update();
        }

        scheduler.planRefreshes("scheduler");

        assertThat(jdbc.sql("SELECT count(*) FROM ingestion_job WHERE target_place_id = :p").param("p", removed)
                .query(Integer.class).single()).isZero();
    }
}
