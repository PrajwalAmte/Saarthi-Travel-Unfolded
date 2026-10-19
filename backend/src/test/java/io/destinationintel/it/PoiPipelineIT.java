package io.destinationintel.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.destinationintel.ingestion.run.SyncRunRecorder;
import io.destinationintel.poi.PoiCategories.Category;
import io.destinationintel.poi.PoiPipeline;
import io.destinationintel.poi.RawPoi;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The shared POI lifecycle against a real database: idempotent re-imports, cross-source
 * matching, same-source duplicate merging, guarded deletions, run accounting and the quality
 * gate. All records are fictional fixtures around the test city.
 */
class PoiPipelineIT extends IntegrationTest {

    @Autowired PoiPipeline pipeline;
    @Autowired SyncRunRecorder recorder;

    private static final String OSM = "openstreetmap";
    private static final String OVERTURE = "overture-places";

    @BeforeEach
    void seed() {
        seedTestvillePlace();
    }

    private static RawPoi osm(String id, String name, String cat, String group, double lat, double lon) {
        return new RawPoi(OSM, id, "1", OffsetDateTime.parse("2026-01-01T00:00:00Z"), "fixture-release", name, cat,
                new Category(cat, group), lat, lon, null, Map.of("opening_hours", "Mo-Su 09:00-18:00"), Map.of("name", name));
    }

    private static RawPoi overture(String id, String name, String cat, String group, double lat, double lon) {
        return new RawPoi(OVERTURE, id, "1", null, "fixture-release", name, cat, new Category(cat, group), lat, lon, null,
                Map.of("confidence", 0.9), Map.of("names", Map.of("primary", name)));
    }

    private static final List<RawPoi> OSM_RELEASE = List.of(
            osm("node/1", "Fixture Corner Cafe", "cafe", "drink", 12.3060, 76.6560),
            osm("node/2", "Fixture Palace", "palace", "see", 12.3050, 76.6540),
            osm("way/3", "Fixture Inn Testville", "hotel", "stay", 12.3070, 76.6580),
            osm("node/4", "Fixture Bakery", "bakery", "eat", 12.3080, 76.6510),
            osm("node/5", "Fixture Bus Stand Testville", "bus_station", "transport", 12.3010, 76.6600),
            // The same palace mapped twice, two metres apart.
            osm("node/6", "Fixture Palace", "palace", "see", 12.30502, 76.65402));

    private SyncRunRecorder.RunRow importRelease(String source, List<RawPoi> records, PoiPipeline.Scope scope) {
        SyncRunRecorder.Run run = recorder.start(source, null, "fixture");
        run.datasetVersion("fixture-release");
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC);
        PoiPipeline.Session session = pipeline.open(run, start);
        for (RawPoi r : records) {
            run.fetched.incrementAndGet();
            session.accept(r);
        }
        pipeline.finish(run, session, start, scope, 20);
        return recorder.find(run.id).orElseThrow();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    @Test
    void importsMergesSameSourceDuplicatesAndIsIdempotent() {
        SyncRunRecorder.RunRow first = importRelease(OSM, OSM_RELEASE, PoiPipeline.Scope.fullRelease());
        assertThat(first.parsed()).isEqualTo(6);
        assertThat(first.inserted()).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM poi_source_record WHERE source_id = 'openstreetmap'")).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM poi WHERE status = 'active'")).as("duplicate palace merged").isEqualTo(5);
        assertThat(count("SELECT count(DISTINCT poi_id) FROM poi_source_record WHERE external_id IN ('node/2', 'node/6')")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM poi WHERE destination_id IS NULL")).as("all within reach of Testville").isZero();
        assertThat(count("SELECT count(*) FROM quality_report WHERE source_id = 'openstreetmap'")).isEqualTo(1);

        SyncRunRecorder.RunRow second = importRelease(OSM, OSM_RELEASE, PoiPipeline.Scope.fullRelease());
        assertThat(second.inserted()).isZero();
        assertThat(second.updated()).isZero();
        assertThat(second.unchanged()).isEqualTo(6);
        assertThat(second.deleted()).isZero();
        assertThat(count("SELECT count(*) FROM poi_source_record")).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM poi WHERE status = 'active'")).isEqualTo(5);
    }

    @Test
    void matchesAnotherSourceByNameAndDistanceAndKeepsBothRecords() {
        importRelease(OSM, OSM_RELEASE, PoiPipeline.Scope.fullRelease());
        UUID osmCafe = jdbc.sql("SELECT poi_id FROM poi_source_record WHERE external_id = 'node/1'").query(UUID.class).single();

        importRelease(OVERTURE, List.of(
                overture("ov-1", "Fixture Corner Café", "cafe", "drink", 12.30605, 76.65605),
                overture("ov-2", "Fixture Lakeside Resort", "resort", "stay", 12.3100, 76.6620)), PoiPipeline.Scope.fullRelease());

        var matched = jdbc.sql("SELECT poi_id, match_method FROM poi_source_record WHERE external_id = 'ov-1'")
                .query((rs, i) -> Map.of("poi", rs.getObject(1, UUID.class), "method", rs.getString(2))).single();
        assertThat(matched.get("poi")).isEqualTo(osmCafe);
        assertThat(matched.get("method")).isEqualTo("name_distance");
        assertThat(count("SELECT count(*) FROM poi WHERE status = 'active'")).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM poi_source_record WHERE poi_id = '" + osmCafe + "'"))
                .as("both source records kept for provenance").isEqualTo(2);
    }

    @Test
    void refusesMassDeletionButAppliesSmallOnes() {
        importRelease(OSM, OSM_RELEASE, PoiPipeline.Scope.fullRelease());

        SyncRunRecorder.RunRow partial = importRelease(OSM, OSM_RELEASE.subList(0, 1), PoiPipeline.Scope.fullRelease());
        assertThat(partial.deleted()).isZero();
        assertThat(partial.warnings()).contains("Deletion skipped");
        assertThat(count("SELECT count(*) FROM poi_source_record WHERE deleted_at IS NOT NULL")).isZero();

        List<RawPoi> withoutBakery = OSM_RELEASE.stream().filter(r -> !r.externalId().equals("node/4")).toList();
        SyncRunRecorder.RunRow next = importRelease(OSM, withoutBakery, PoiPipeline.Scope.fullRelease());
        assertThat(next.deleted()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM poi_source_record WHERE external_id = 'node/4' AND deleted_at IS NOT NULL")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM poi p JOIN poi_source_record r ON r.poi_id = p.id WHERE r.external_id = 'node/4' AND p.status = 'removed'"))
                .isEqualTo(1);
    }

    @Test
    void failedQualityGateWithholdsPublication() {
        // A quarter of the in-scope records fail validation, far above OSM's 5% limit.
        List<RawPoi> bad = List.of(
                osm("node/1", "Fixture Corner Cafe", "cafe", "drink", 12.3060, 76.6560),
                osm("node/2", "Fixture Palace", "palace", "see", 12.3050, 76.6540),
                osm("node/3", "Fixture Inn Testville", "hotel", "stay", 12.3070, 76.6580),
                osm("node/7", "Restaurant", "restaurant", "eat", 12.3075, 76.6590));
        SyncRunRecorder.RunRow run = importRelease(OSM, bad, PoiPipeline.Scope.fullRelease());
        assertThat(run.rejected()).isEqualTo(1);
        assertThat(run.status()).isEqualTo("quality_failed");
        assertThat(jdbc.sql("SELECT quality_status FROM source WHERE id = 'openstreetmap'").query(String.class).single()).isEqualTo("failed");
        assertThat(jdbc.sql("SELECT last_publication_at IS NULL FROM source WHERE id = 'openstreetmap'").query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT status FROM quality_report WHERE run_id = :r").param("r", run.id()).query(String.class).single())
                .isEqualTo("failed");
    }

    @Test
    void anImportThatFetchesNothingIsFlagged() {
        SyncRunRecorder.Run run = recorder.start(OSM, null, "fixture");
        String status = recorder.finish(run, false, true);
        assertThat(status).isEqualTo("succeeded_with_warnings");
        assertThat(recorder.find(run.id).orElseThrow().warnings()).containsIgnoringCase("zero");
    }

    @Test
    void mappedPlacesAreServedOnlyFromSourcesThatPassedTheirGate() throws Exception {
        importRelease(OSM, OSM_RELEASE, PoiPipeline.Scope.fullRelease());
        String status = jdbc.sql("SELECT quality_status FROM source WHERE id = 'openstreetmap'").query(String.class).single();
        assertThat(status).isIn("passed", "warning");
        var ok = json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/destinations/testville/mapped-places")).andReturn().getResponse().getContentAsString());
        assertThat(ok.path("total").asLong()).isEqualTo(5);
        assertThat(ok.path("sourcesIncluded").toString()).contains("openstreetmap");
        assertThat(ok.path("notice").asString()).contains("not recommendations");
        assertThat(ok.path("places").path(0).path("sources").path(0).path("url").asString()).startsWith("https://www.openstreetmap.org/");

        jdbc.sql("UPDATE source SET quality_status = 'failed' WHERE id = 'openstreetmap'").update();
        var withheld = json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/destinations/testville/mapped-places")).andReturn().getResponse().getContentAsString());
        assertThat(withheld.path("total").asLong()).isZero();
        assertThat(withheld.path("sourcesWithheld").toString()).contains("openstreetmap");
    }

    @Test
    void placeholderCoordinatesAreQuarantinedNotMapped() {
        // Five unrelated hotels on one exact point: a town centroid standing in for unknown locations.
        List<RawPoi> stacked = new java.util.ArrayList<>(OSM_RELEASE);
        for (int i = 1; i <= 5; i++) {
            stacked.add(overture("stack-" + i, "Fixture Stacked Hotel " + (char) ('A' + i), "hotel", "stay", 12.3050, 76.6550));
        }
        SyncRunRecorder.RunRow run = importRelease(OVERTURE, stacked.subList(OSM_RELEASE.size(), stacked.size()),
                PoiPipeline.Scope.fullRelease());
        assertThat(count("SELECT count(*) FROM poi_source_record WHERE match_confidence = 'unplaceable'")).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM poi_source_record WHERE external_id LIKE 'stack-%' AND poi_id IS NOT NULL")).isZero();
        assertThat(count("SELECT count(*) FROM poi WHERE status = 'active'")).isZero();
        assertThat(run.warnings()).contains("placeholder coordinates");
    }

    @Test
    void uncertainMatchesAreDecidedFromEvidenceAndRecordTheirRule() throws Exception {
        importRelease(OSM, OSM_RELEASE, PoiPipeline.Scope.fullRelease());
        UUID palace = jdbc.sql("SELECT poi_id FROM poi_source_record WHERE external_id = 'node/2'").query(UUID.class).single();
        // Same name about 245 m away: too far for automatic matching, decided by the evidence rules.
        // A bakery 30 m from the bus stand shares a word with it but is a different kind of place.
        importRelease(OVERTURE, List.of(
                overture("ov-palace", "Fixture Palace", "palace", "see", 12.3072, 76.6540),
                overture("ov-bakery", "Fixture Bus Stand Bakery", "bakery", "eat", 12.30125, 76.6601)), PoiPipeline.Scope.fullRelease());

        assertThat(jdbc.sql("SELECT poi_id FROM poi_source_record WHERE external_id = 'ov-palace'").query(UUID.class).single())
                .isEqualTo(palace);
        assertThat(jdbc.sql("SELECT match_method FROM poi_source_record WHERE external_id = 'ov-palace'").query(String.class).single())
                .isEqualTo("evidence:same_name_nearby");
        assertThat(count("SELECT count(*) FROM poi_source_record WHERE external_id = 'ov-bakery' AND match_confidence = 'new' AND poi_id IS NOT NULL"))
                .as("not merged into the bus stand; a place of its own").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM poi_match_review WHERE status = 'open'")).isZero();
        assertThat(count("SELECT count(*) FROM poi_match_review WHERE resolution IS NULL")).as("every decision names its rule").isZero();

        // A reviewer can still decide a pair by hand.
        UUID inn = jdbc.sql("SELECT poi_id FROM poi_source_record WHERE external_id = 'way/3'").query(UUID.class).single();
        UUID record = jdbc.sql("SELECT id FROM poi_source_record WHERE external_id = 'ov-bakery'").query(UUID.class).single();
        UUID review = jdbc.sql("INSERT INTO poi_match_review (record_id, candidate_poi_id, score) VALUES (:r, :p, 0.5) RETURNING id")
                .param("r", record).param("p", inn).query(UUID.class).single();
        jakarta.servlet.http.Cookie admin = signIn("admin@test.example", "Admin");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/admin/poi-matches/" + review)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .cookie(admin).contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"decision\":\"distinct\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertThat(jdbc.sql("SELECT resolution FROM poi_match_review WHERE id = :id").param("id", review).query(String.class).single())
                .isEqualTo("reviewer");
    }
}
