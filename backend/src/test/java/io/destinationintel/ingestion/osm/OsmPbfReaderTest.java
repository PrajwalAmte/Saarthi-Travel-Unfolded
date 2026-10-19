package io.destinationintel.ingestion.osm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.destinationintel.poi.PoiNormalizer;
import io.destinationintel.poi.RawPoi;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OsmPbfReaderTest {

    private static final List<PbfFixture.Node> NODES = List.of(
            new PbfFixture.Node(100, 12.3051, 76.6551, new String[] {"amenity", "restaurant", "name", "Test Mess",
                    "cuisine", "south_indian", "opening_hours", "Mo-Su 07:00-22:00", "website", "javascript:alert(1)"}),
            new PbfFixture.Node(101, 12.3, 76.6, new String[] {}),
            new PbfFixture.Node(102, 12.3002, 76.6, new String[] {}),
            new PbfFixture.Node(103, 12.3002, 76.6002, new String[] {}),
            new PbfFixture.Node(104, 12.3, 76.6002, new String[] {"highway", "bus_stop"}),
            new PbfFixture.Node(105, 12.31, 76.64, new String[] {"place", "city", "name", "Testville", "wikidata", "Q900000002"}),
            new PbfFixture.Node(106, 51.5, -0.12, new String[] {"tourism", "museum", "name", "Far Away Museum"}));

    private static final List<PbfFixture.Way> WAYS = List.of(
            new PbfFixture.Way(500, new long[] {101, 102, 103, 104, 101}, new String[] {"tourism", "hotel", "name", "Test Palace Hotel",
                    "building", "yes", "phone", "+91 821 000 0000; +91 98450 00000"}),
            new PbfFixture.Way(501, new long[] {101, 102}, new String[] {"highway", "residential", "name", "Some Road"}));

    @Test
    void decodesHeaderDenseNodesAndWays() throws IOException {
        byte[] pbf = PbfFixture.file(NODES, WAYS);
        List<String> seen = new ArrayList<>();
        OsmPbfReader.Header[] header = new OsmPbfReader.Header[1];
        OsmPbfReader.read(new ByteArrayInputStream(pbf), new OsmPbfReader.Handler() {
            @Override
            public void header(OsmPbfReader.Header h) {
                header[0] = h;
            }

            @Override
            public boolean interestingKey(String key) {
                return OsmPoiMapper.INTERESTING_KEYS.contains(key);
            }

            @Override
            public void node(long id, double lat, double lon, Map<String, String> tags, int version, long ts) {
                seen.add("n" + id + ":" + Math.round(lat * 1e4) + ":" + Math.round(lon * 1e4) + ":" + tags.get("name") + ":v" + version + ":" + ts);
            }

            @Override
            public void way(long id, long[] refs, Map<String, String> tags, int version, long ts) {
                seen.add("w" + id + ":" + refs.length + ":" + refs[2] + ":" + tags.get("name") + ":v" + version);
            }
        });
        assertThat(header[0].replicationSequence()).isEqualTo(4936L);
        assertThat(header[0].writingProgram()).isEqualTo("saarthi-test");
        assertThat(seen).containsExactly(
                "n100:123051:766551:Test Mess:v3:1700000000",
                "n105:123100:766400:Testville:v3:1700000000",
                "n106:515000:-1200:Far Away Museum:v3:1700000000",
                "w500:5:103:Test Palace Hotel:v7");
    }

    @Test
    void canDeliverEveryNodeCoordinateForWayCentroids() throws IOException {
        byte[] pbf = PbfFixture.file(NODES, WAYS);
        List<Long> ids = new ArrayList<>();
        OsmPbfReader.read(new ByteArrayInputStream(pbf), new OsmPbfReader.Handler() {
            @Override
            public boolean interestingKey(String key) {
                return false;
            }

            @Override
            public boolean wantAllNodeCoordinates() {
                return true;
            }

            @Override
            public void node(long id, double lat, double lon, Map<String, String> tags, int version, long ts) {
                ids.add(id);
            }

            @Override
            public void way(long id, long[] refs, Map<String, String> tags, int version, long ts) {}
        });
        assertThat(ids).containsExactly(100L, 101L, 102L, 103L, 104L, 105L, 106L);
    }

    @Test
    void rejectsTruncatedFiles() throws IOException {
        byte[] pbf = PbfFixture.file(NODES, WAYS);
        byte[] truncated = java.util.Arrays.copyOf(pbf, pbf.length - 20);
        assertThatThrownBy(() -> OsmPbfReader.read(new ByteArrayInputStream(truncated), new OsmPbfReader.Handler() {
            @Override
            public boolean interestingKey(String key) {
                return true;
            }

            @Override
            public void node(long id, double lat, double lon, Map<String, String> tags, int version, long ts) {}

            @Override
            public void way(long id, long[] refs, Map<String, String> tags, int version, long ts) {}
        })).isInstanceOf(IOException.class);
    }

    @Test
    void mapsAndValidatesOsmTags() {
        RawPoi mess = OsmPoiMapper.map("node", 100, 12.3051, 76.6551, Map.of("amenity", "restaurant", "name", "Test Mess",
                "website", "javascript:alert(1)", "opening_hours", "Mo-Su 07:00-22:00", "addr:street", "Sayyaji Rao Road",
                "addr:housenumber", "12", "addr:city", "Testville"), 3, 1_700_000_000L, "geofabrik-seq-1");
        assertThat(mess.externalId()).isEqualTo("node/100");
        assertThat(mess.category().key()).isEqualTo("restaurant");
        assertThat(mess.attributes()).containsEntry("address", "12 Sayyaji Rao Road, Testville");
        assertThat(mess.raw()).containsEntry("amenity", "restaurant");
        var accepted = (PoiNormalizer.Accepted) PoiNormalizer.normalize(mess);
        assertThat(accepted.poi().attributes()).doesNotContainKey("website").containsEntry("opening_hours", "Mo-Su 07:00-22:00");
        assertThat(accepted.attributeWarnings()).containsExactly("invalid_website");

        RawPoi far = OsmPoiMapper.map("node", 106, 51.5, -0.12, Map.of("tourism", "museum", "name", "Far Away Museum"), 1, 0, "x");
        assertThat(PoiNormalizer.normalize(far)).isEqualTo(new PoiNormalizer.Rejected("outside_india"));
        RawPoi unnamed = OsmPoiMapper.map("node", 107, 12.3, 76.6, Map.of("amenity", "restaurant"), 1, 0, "x");
        assertThat(PoiNormalizer.normalize(unnamed)).isEqualTo(new PoiNormalizer.Rejected("missing_name"));
        RawPoi generic = OsmPoiMapper.map("node", 108, 12.3, 76.6, Map.of("amenity", "restaurant", "name", "Restaurant"), 1, 0, "x");
        assertThat(PoiNormalizer.normalize(generic)).isEqualTo(new PoiNormalizer.Rejected("generic_name"));
        RawPoi english = OsmPoiMapper.map("node", 109, 12.3, 76.6, Map.of("historic", "fort", "name", "ಶ್ರೀರಂಗಪಟ್ಟಣ ಕೋಟೆ",
                "name:en", "Srirangapatna Fort"), 1, 0, "x");
        assertThat(english.name()).isEqualTo("Srirangapatna Fort");
        assertThat(english.attributes()).containsEntry("local_name", "ಶ್ರೀರಂಗಪಟ್ಟಣ ಕೋಟೆ");
    }

    @Test
    void categoriesExcludeOrdinaryShrinesAndUnmappedTags() {
        assertThat(OsmPoiMapper.category(Map.of("amenity", "place_of_worship", "name", "Local temple"))).isEmpty();
        assertThat(OsmPoiMapper.category(Map.of("amenity", "place_of_worship", "heritage", "2"))).isPresent();
        assertThat(OsmPoiMapper.category(Map.of("amenity", "toilets"))).isEmpty();
        assertThat(OsmPoiMapper.category(Map.of("railway", "station", "station", "subway")).orElseThrow().key()).isEqualTo("metro_station");
        assertThat(OsmPoiMapper.category(Map.of("historic", "castle", "castle_type", "palace")).orElseThrow().key()).isEqualTo("palace");
        assertThat(OsmPoiMapper.category(Map.of("aeroway", "aerodrome"))).isEmpty();
        assertThat(OsmPoiMapper.category(Map.of("aeroway", "aerodrome", "iata", "MYQ")).orElseThrow().group()).isEqualTo("transport");
    }

    @Test
    void movesEateriesTaggedAsHotelsOnlyWhenTheNameSaysNothingAboutLodging() {
        var eatery = OsmPoiMapper.map("node", 1, 12.97, 77.59, Map.of("tourism", "hotel", "name", "Sri Sakthi Mess"), 1, 0, "r");
        assertThat(eatery.category().key()).isEqualTo("restaurant");
        assertThat(eatery.attributes()).containsKey("category_note");
        var both = OsmPoiMapper.map("node", 2, 12.97, 77.59, Map.of("tourism", "hotel", "name", "Singh Hotel & Restaurant"), 1, 0, "r");
        assertThat(both.category().key()).isEqualTo("hotel");
        var lodge = OsmPoiMapper.map("node", 3, 12.97, 77.59, Map.of("tourism", "hotel", "name", "Hotel Mayura"), 1, 0, "r");
        assertThat(lodge.category().key()).isEqualTo("hotel");
    }

    @Test
    void decodesRelationsAndDeliversTheirUntaggedOuterWays() throws IOException {
        List<PbfFixture.Node> nodes = List.of(
                new PbfFixture.Node(1, 12.30, 76.60, new String[0]), new PbfFixture.Node(2, 12.30, 76.62, new String[0]),
                new PbfFixture.Node(3, 12.32, 76.62, new String[0]), new PbfFixture.Node(4, 12.32, 76.60, new String[0]));
        List<PbfFixture.Way> ways = List.of(new PbfFixture.Way(10, new long[] {1, 2, 3, 4, 1}, new String[0]),
                new PbfFixture.Way(11, new long[] {1, 2}, new String[0]));
        List<PbfFixture.Relation> relations = List.of(new PbfFixture.Relation(20,
                new long[][] {{1, 10}, {2, 21}}, new String[] {"outer", ""},
                new String[] {"type", "multipolygon", "leisure", "park", "name", "Fixture Lake Park"}));
        byte[] pbf = PbfFixture.file(nodes, ways, relations);

        java.util.List<Long> delivered = new java.util.ArrayList<>();
        long[][] members = new long[1][];
        String[][] roles = new String[1][];
        java.util.Map<String, String> relationTags = new java.util.HashMap<>();
        OsmPbfReader.read(new java.io.ByteArrayInputStream(pbf), new OsmPbfReader.Handler() {
            @Override
            public boolean interestingKey(String key) {
                return OsmPoiMapper.INTERESTING_KEYS.contains(key);
            }

            @Override
            public boolean wantNodes() {
                return false;
            }

            @Override
            public boolean wantRelations() {
                return true;
            }

            @Override
            public boolean wantUntaggedWay(long id) {
                return id == 10;
            }

            @Override
            public void node(long id, double lat, double lon, Map<String, String> tags, int version, long ts) {}

            @Override
            public void way(long id, long[] refs, Map<String, String> tags, int version, long ts) {
                delivered.add(id);
                assertThat(refs).containsExactly(1, 2, 3, 4, 1);
            }

            @Override
            public void relation(long id, long[] memberIds, int[] memberTypes, String[] memberRoles, Map<String, String> tags,
                                 int version, long ts) {
                members[0] = memberIds;
                roles[0] = memberRoles;
                relationTags.putAll(tags);
                assertThat(memberTypes).containsExactly(1, 2);
            }
        });
        assertThat(delivered).as("only the requested untagged way").containsExactly(10L);
        assertThat(members[0]).containsExactly(10, 21);
        assertThat(roles[0]).containsExactly("outer", "");
        assertThat(relationTags).containsEntry("name", "Fixture Lake Park").containsEntry("type", "multipolygon");
        assertThat(OsmExtractHandler.isAreaRelation(relationTags)).isTrue();
        assertThat(OsmExtractHandler.isAreaRelation(Map.of("type", "boundary", "boundary", "administrative"))).isFalse();
        assertThat(OsmExtractHandler.isAreaRelation(Map.of("type", "boundary", "boundary", "national_park"))).isTrue();
        assertThat(OsmExtractHandler.sample(new long[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, 4)).containsExactly(1, 4, 7, 10);
        assertThat(OsmPoiMapper.category(Map.of("boundary", "national_park", "name", "Bandipur National Park")))
                .map(PoiCategories -> PoiCategories.key()).contains("nature_reserve");
    }

    @Test
    void readsTheReleaseFromTheExtractHeaderWhenGeofabrikIsUnreachable() throws IOException {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("fixture", ".osm.pbf");
        try {
            java.nio.file.Files.write(file, PbfFixture.file(List.of(new PbfFixture.Node(1, 12.3, 76.6, new String[0])), List.of()));
            assertThat(OsmExtractHandler.releaseFromHeader(file)).isEqualTo("osm-replication-seq-4936@2025-10-09T08:53:20Z");
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }
}
