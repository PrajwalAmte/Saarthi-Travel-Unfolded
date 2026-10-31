package io.destinationintel.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.destinationintel.place.PlaceRepository.PlaceUpsert;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

@TestPropertySource(properties = "app.ingestion.focus=testville:400")
class WeekendTripsIT extends IntegrationTest {

    private UUID guidedPlace(String qid, String name, double lat, double lon) {
        UUID id = tx.execute(s -> places.upsertFromWikidata(new PlaceUpsert(qid, name, "town", "town in Test State, India",
                lat, lon, 20_000L, name, null, 10, true, "Test State")));
        jdbc.sql("INSERT INTO destination_profile (place_id, coverage_status, computed_at) VALUES (:p, 'usable', now())")
                .param("p", id).update();
        return id;
    }

    private void fact(UUID place, String section, String kind, String text, String json) {
        UUID f = jdbc.sql("""
                        INSERT INTO fact (destination_id, section, kind, value_text, value_json, volatility, status, origin, observed_basis)
                        VALUES (:p, :section, :kind, :text, CAST(:json AS jsonb), 'stable', 'published', 'ingested', 'unknown')
                        RETURNING id""")
                .param("p", place).param("section", section).param("kind", kind).param("text", text).param("json", json)
                .query(UUID.class).single();
        jdbc.sql("""
                        INSERT INTO evidence (fact_id, source_id, url, permalink, extraction_method, retrieved_at)
                        VALUES (:f, 'wikivoyage-en', 'https://en.wikivoyage.org/wiki/X', 'https://en.wikivoyage.org/w/index.php?oldid=1',
                                'test', now())""")
                .param("f", f).update();
    }

    @Test
    void weekendTripsAreBuiltOnlyFromStoredSourcedData() throws Exception {
        UUID origin = seedTestvillePlace();
        ingestGuide(origin);
        UUID hill = guidedPlace("Q900000040", "Hillstation", 12.85, 76.65); // ~60 km north
        guidedPlace("Q900000041", "Suburb", 12.35, 76.66); // ~5 km: a day out, not a weekend trip
        guidedPlace("Q900000042", "Faraway", 18.0, 76.65); // ~630 km: out of range
        fact(hill, "overview", "summary", "Hillstation is a cool hill town known for coffee estates and waterfalls. It has much more.", null);
        fact(hill, "getting_there", "transport", "Buses run every hour from Testville; the drive takes about two hours.", null);
        fact(hill, "overview", "climate", "Mild.", """
                {"period": "2016–2025", "months": [
                  {"month": 1, "meanMaxC": 27.0, "rainyDays": 1.0},
                  {"month": 4, "meanMaxC": 34.0, "rainyDays": 3.0},
                  {"month": 7, "meanMaxC": 24.0, "rainyDays": 20.0},
                  {"month": 11, "meanMaxC": 28.0, "rainyDays": 5.0}]}""");

        JsonNode r = body(mvc.perform(get("/api/trips/weekend-from/testville")).andExpect(status().isOk()).andReturn());
        assertThat(r.path("originName").asString()).isEqualTo("Testville");
        assertThat(r.path("maxKm").asInt()).as("radius from the focus setting").isEqualTo(400);
        assertThat(r.path("total").asInt()).as("not too close, not too far").isEqualTo(1);
        JsonNode band = r.path("bands").path(0);
        assertThat(band.path("label").asString()).isEqualTo("Within 150 km");
        JsonNode t = band.path("trips").path(0);
        assertThat(t.path("name").asString()).isEqualTo("Hillstation");
        assertThat(t.path("distanceKm").asInt()).isBetween(55, 65);
        assertThat(t.path("knownFor").asString()).startsWith("Hillstation is a cool hill town");
        assertThat(t.path("knownForSource").path("url").asString()).contains("oldid=");
        assertThat(t.path("gettingThere").asString()).contains("Buses run every hour");
        List<Integer> months = new ArrayList<>();
        t.path("comfortableMonths").forEach(m -> months.add(m.asInt()));
        assertThat(months).as("mild and mostly dry only").containsExactly(1, 11);
        assertThat(r.path("method").toString()).contains("straight line").contains("not by road");
        assertThat(r.path("bandsBy").asString()).isEqualTo("distance");
        assertThat(t.path("driveMinutes").isNull()).as("no drive time until routes are computed").isTrue();

        JsonNode origins = body(mvc.perform(get("/api/trips/origins")).andExpect(status().isOk()).andReturn());
        assertThat(origins.path(0).path("slug").asString()).isEqualTo("testville");
        mvc.perform(get("/api/trips/weekend-from/no-such-place")).andExpect(status().isNotFound());
    }

    @Test
    void driveTimesComeFromTheRouterOnceAndGroupTripsByHours() throws Exception {
        UUID origin = seedTestvillePlace();
        ingestGuide(origin);
        guidedPlace("Q900000043", "Hillstation", 12.85, 76.65); // ~60 km straight, ~75 km by road, ~1.5 h
        guidedPlace("Q900000044", "Coastal", 13.9, 76.65); // ~180 km straight, ~4.5 h by road
        guidedPlace("Q900000045", "Island", 12.2, 77.0); // the stub router finds no road here

        int before = SourceStub.get().osrmRequests.get();
        runRoutes(origin);
        assertThat(SourceStub.get().osrmRequests.get() - before).as("one table request for all three places").isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM road_route WHERE origin_id = :o").param("o", origin).query(Integer.class).single())
                .as("a no-route answer is stored too, so it is not asked again").isEqualTo(3);
        runRoutes(origin);
        assertThat(SourceStub.get().osrmRequests.get() - before).as("nothing is due the second time").isEqualTo(1);

        caches.getCache("destination-detail").clear();
        JsonNode r = body(mvc.perform(get("/api/trips/weekend-from/testville")).andExpect(status().isOk()).andReturn());
        assertThat(r.path("bandsBy").asString()).isEqualTo("drive_time");
        assertThat(r.path("bands").path(0).path("label").asString()).isEqualTo("Up to 3 hours by road");
        JsonNode hill = r.path("bands").path(0).path("trips").path(0);
        assertThat(hill.path("name").asString()).isEqualTo("Hillstation");
        assertThat(hill.path("roadKm").asInt()).isBetween(70, 80);
        assertThat(hill.path("driveMinutes").asInt()).isBetween(85, 95);
        assertThat(r.path("bands").path(1).path("label").asString()).isEqualTo("3 to 5 hours by road");
        JsonNode last = r.path("bands").path(2);
        assertThat(last.path("key").asString()).isEqualTo("no-route");
        assertThat(last.path("trips").path(0).path("name").asString()).isEqualTo("Island");
        assertThat(r.path("method").toString()).contains("OSRM").contains("OpenStreetMap").contains("without traffic");
    }

    private void runRoutes(UUID origin) {
        jobs.enqueue("road_routes", "osrm", origin, java.util.Map.of("radiusKm", 400), "road_routes:" + origin + ":" + UUID.randomUUID(),
                1000, "admin");
        runner.execute(jobs.claimNext().orElseThrow());
    }
}
