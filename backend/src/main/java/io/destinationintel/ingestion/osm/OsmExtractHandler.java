package io.destinationintel.ingestion.osm;

import io.destinationintel.common.Json;
import io.destinationintel.config.AppProperties;
import io.destinationintel.ingestion.JobHandler;
import io.destinationintel.ingestion.JobRepository;
import io.destinationintel.ingestion.JobRepository.JobRow;
import io.destinationintel.ingestion.run.SyncRunRecorder;
import io.destinationintel.ingestion.run.SyncRunRecorder.Run;
import io.destinationintel.poi.PoiPipeline;
import io.destinationintel.poi.RawPoi;
import io.destinationintel.source.SourceRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Job "osm_extract": release-based OpenStreetMap import. Downloads the Geofabrik extract,
 * verifies it against the published MD5, and stream-parses it twice: first ways (to learn
 * which nodes are needed for building centroids), then nodes. Named POIs in mapped categories
 * go through the shared POI pipeline; place nodes with Wikidata tags are recorded as extra
 * source identities of indexed places. The release (Geofabrik replication sequence) is
 * recorded; an unchanged release is not reprocessed unless forced.
 */
@Component
public class OsmExtractHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(OsmExtractHandler.class);

    private final PoiPipeline pipeline;
    private final SyncRunRecorder recorder;
    private final SourceRepository sources;
    private final JobRepository jobs;
    private final JdbcClient jdbc;
    private final Json json;
    private final AppProperties.Ingestion config;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20)).build();

    public OsmExtractHandler(PoiPipeline pipeline, SyncRunRecorder recorder, SourceRepository sources, JobRepository jobs,
                             JdbcClient jdbc, Json json, AppProperties props) {
        this.pipeline = pipeline;
        this.recorder = recorder;
        this.sources = sources;
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.json = json;
        this.config = props.ingestion();
    }

    @Override
    public String type() {
        return "osm_extract";
    }

    @Override
    public Map<String, Object> run(JobRow job) {
        if (!sources.isEnabled(OsmPoiMapper.SOURCE_ID)) {
            throw new PermanentFailure("The OpenStreetMap source is disabled in the source registry");
        }
        Map<String, Object> params = json.map(job.params());
        boolean force = Boolean.TRUE.equals(params.get("force"));
        String url = params.get("url") instanceof String u && u.startsWith("https://") ? u : config.osmExtractUrl();
        Run run = recorder.start(OsmPoiMapper.SOURCE_ID, job.id(), "release:" + url);
        try {
            String release = releaseVersion();
            run.datasetVersion(release);
            Optional<String> previous = jdbc.sql("""
                            SELECT dataset_version FROM source_sync_run WHERE source_id = 'openstreetmap' AND scope = :scope
                              AND status IN ('succeeded', 'succeeded_with_warnings') ORDER BY started_at DESC LIMIT 1
                            """).param("scope", run.scope).query(String.class).optional();
            if (!force && release != null && previous.isPresent() && release.equals(previous.get())) {
                run.warn("Release " + release + " was already imported; nothing to do.");
                recorder.finish(run, false, false);
                return Map.of("status", "unchanged", "release", release);
            }

            recorder.stage(run, "fetch");
            Path file = reusableDownload(url, release).orElseGet(() -> download(url, job, release));
            OffsetDateTime runStart = OffsetDateTime.now(ZoneOffset.UTC);
            try {
                recorder.stage(run, "parse");
                PoiPipeline.Session session = pipeline.open(run, runStart);
                Map<String, Object> stats = new LinkedHashMap<>();
                if (!lastChecksumVerified) {
                    run.warn("Geofabrik's published checksum could not be fetched; the download was checked for completeness only.");
                }
                // Without Geofabrik's state file the release is read from the extract's own header.
                String effectiveRelease = release != null ? release : releaseFromHeader(file);
                if (release == null) {
                    run.warn("Geofabrik's release state could not be fetched; the release was taken from the extract header ("
                            + effectiveRelease + ").");
                }
                run.datasetVersion(effectiveRelease);
                stats.putAll(parse(file, run, session, effectiveRelease, job));
                stats.putAll(pipeline.finish(run, session, runStart, PoiPipeline.Scope.fullRelease(),
                        config.poiDestinationRadiusKm()));
                stats.put("release", effectiveRelease);
                stats.put("run", run.summary());
                return stats;
            } finally {
                if (!config.keepDownloads()) {
                    Files.deleteIfExists(file);
                    Files.deleteIfExists(file.resolveSibling("osm-extract.release"));
                }
            }
        } catch (RuntimeException | IOException e) {
            recorder.fail(run, e.getMessage());
            if (e instanceof PermanentFailure pf) {
                throw pf;
            }
            throw new IllegalStateException("OSM import failed: " + e.getMessage(), e);
        }
    }

    /** Geofabrik replication sequence and timestamp identify the release. */
    String releaseVersion() {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(config.osmStateUrl()))
                    .header("User-Agent", config.userAgent()).timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                return null;
            }
            String seq = null;
            String ts = null;
            for (String line : r.body().split("\n")) {
                if (line.startsWith("sequenceNumber=")) {
                    seq = line.substring("sequenceNumber=".length()).trim();
                }
                if (line.startsWith("timestamp=")) {
                    ts = line.substring("timestamp=".length()).trim().replace("\\:", ":");
                }
            }
            return seq == null ? null : "geofabrik-seq-" + seq + (ts == null ? "" : "@" + ts);
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * A kept download of the same release is reused when its checksum still matches the
     * published one, so a retry after a failure later in the pipeline does not fetch it again.
     */
    Optional<Path> reusableDownload(String url, String release) {
        Path dir = Path.of(config.workDir());
        Path target = dir.resolve("osm-extract.osm.pbf");
        Path marker = dir.resolve("osm-extract.release");
        try {
            if (release == null || !Files.exists(target) || !Files.exists(marker)
                    || !release.equals(Files.readString(marker).trim())) {
                return Optional.empty();
            }
            String published = publishedMd5(url);
            if (published == null) {
                return Optional.empty();
            }
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            try (InputStream in = new DigestInputStream(Files.newInputStream(target), md5)) {
                in.transferTo(OutputStream.nullOutputStream());
            }
            if (!published.equalsIgnoreCase(HexFormat.of().formatHex(md5.digest()))) {
                return Optional.empty();
            }
            lastChecksumVerified = true;
            log.info("Reusing downloaded OSM extract for release {} (checksum verified)", release);
            return Optional.of(target);
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            return Optional.empty();
        }
    }

    Path download(String url, JobRow job, String release) {
        Path dir = Path.of(config.workDir());
        Path target = dir.resolve("osm-extract.osm.pbf");
        Path partial = dir.resolve("osm-extract.osm.pbf.part");
        try {
            Files.createDirectories(dir);
            Files.deleteIfExists(dir.resolve("osm-extract.release"));
            HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", config.userAgent()).timeout(Duration.ofMinutes(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() != 200) {
                throw new IOException("Extract download returned HTTP " + r.statusCode());
            }
            long expected = r.headers().firstValueAsLong("Content-Length").orElse(-1);
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            long written;
            try (InputStream in = new DigestInputStream(r.body(), md5)) {
                written = Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
            }
            if (expected > 0 && written != expected) {
                throw new IOException("Extract download truncated: " + written + " of " + expected + " bytes");
            }
            String actual = HexFormat.of().formatHex(md5.digest());
            String published = publishedMd5(url);
            if (published != null && !published.equalsIgnoreCase(actual)) {
                throw new IOException("Extract checksum mismatch: expected " + published + ", got " + actual);
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            if (release != null) {
                Files.writeString(dir.resolve("osm-extract.release"), release);
            }
            lastChecksumVerified = published != null;
            log.info("Downloaded OSM extract ({} MB, md5 {}{})", written / (1024 * 1024), actual,
                    published == null ? ", no published checksum" : ", verified");
            jobs.heartbeat(job.id());
            return target;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Download interrupted", e);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String publishedMd5(String url) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url + ".md5"))
                    .header("User-Agent", config.userAgent()).timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                return null;
            }
            String token = r.body().trim().split("\\s+")[0];
            return token.matches("^[0-9a-fA-F]{32}$") ? token : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Whether the last download was checked against Geofabrik's published MD5 (jobs run one at a time). */
    private volatile boolean lastChecksumVerified;

    /** The release recorded in the extract itself: its replication sequence and timestamp. */
    static String releaseFromHeader(Path file) {
        String[] out = {null};
        try (InputStream in = Files.newInputStream(file)) {
            OsmPbfReader.read(in, new OsmPbfReader.Handler() {
                @Override
                public void header(OsmPbfReader.Header h) {
                    out[0] = releaseLabel(h);
                    throw new HeaderRead();
                }

                @Override
                public boolean interestingKey(String key) {
                    return false;
                }

                @Override
                public void node(long id, double lat, double lon, Map<String, String> tags, int version, long ts) {}

                @Override
                public void way(long id, long[] refs, Map<String, String> tags, int version, long ts) {}
            });
        } catch (HeaderRead e) {
            // Stopped deliberately after the header.
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        if (out[0] == null) {
            throw new IllegalStateException("The extract carries no replication information to identify its release");
        }
        return out[0];
    }

    static String releaseLabel(OsmPbfReader.Header h) {
        if (h.replicationSequence() == null && h.replicationTimestamp() == null) {
            return null;
        }
        String ts = h.replicationTimestamp() == null ? ""
                : "@" + java.time.Instant.ofEpochSecond(h.replicationTimestamp()).toString();
        return "osm-replication-seq-" + (h.replicationSequence() == null ? "unknown" : h.replicationSequence()) + ts;
    }

    private static final class HeaderRead extends RuntimeException {
        HeaderRead() {
            super(null, null, false, false);
        }
    }

    /** Nodes kept per outer way of a relation: enough for a centre, bounded for very long boundaries. */
    static final int RELATION_SAMPLES_PER_WAY = 64;

    /** Multipolygons, and boundaries that describe parks or protected areas, are areas a guide can list. */
    static boolean isAreaRelation(Map<String, String> tags) {
        String type = tags.get("type");
        if ("multipolygon".equals(type)) {
            return true;
        }
        if ("boundary".equals(type)) {
            String boundary = tags.get("boundary");
            return "national_park".equals(boundary) || "protected_area".equals(boundary);
        }
        return false;
    }

    /** Evenly spaced node references including both ends. */
    static long[] sample(long[] refs, int max) {
        if (refs.length <= max) {
            return refs;
        }
        long[] out = new long[max];
        for (int i = 0; i < max; i++) {
            out[i] = refs[(int) Math.round((double) i * (refs.length - 1) / (max - 1))];
        }
        return out;
    }

    /** Area relation kept between passes: its outer way ids and all tags. */
    private record RelationCandidate(long id, long[] outerWays, Map<String, String> tags, int version, long timestamp) {}

    /** Way candidate kept between the two passes (all tags kept for provenance). */
    private record WayCandidate(long id, long[] refs, Map<String, String> tags, int version, long timestamp) {}

    Map<String, Object> parse(Path file, Run run, Consumer<RawPoi> sink, String release, JobRow job) throws IOException {
        // Pass 0: relations. Multipolygons (and park/protected-area boundaries) in mapped categories;
        // their outer ways are fetched in pass 1 and their nodes in pass 2.
        List<RelationCandidate> relations = new ArrayList<>();
        long[] relationsSeen = {0};
        long[] nestedMembersIgnored = {0};
        try (InputStream in = Files.newInputStream(file)) {
            OsmPbfReader.read(in, new OsmPbfReader.Handler() {
                @Override
                public boolean interestingKey(String key) {
                    return OsmPoiMapper.INTERESTING_KEYS.contains(key);
                }

                @Override
                public boolean wantNodes() {
                    return false;
                }

                @Override
                public boolean wantWays() {
                    return false;
                }

                @Override
                public boolean wantRelations() {
                    return true;
                }

                @Override
                public void node(long id, double lat, double lon, Map<String, String> tags, int version, long ts) {}

                @Override
                public void way(long id, long[] refs, Map<String, String> tags, int version, long ts) {}

                @Override
                public void relation(long id, long[] memberIds, int[] memberTypes, String[] roles, Map<String, String> tags,
                                     int version, long ts) {
                    if (!isAreaRelation(tags) || OsmPoiMapper.category(tags).isEmpty()) {
                        return;
                    }
                    long[] outer = new long[memberIds.length];
                    int n = 0;
                    for (int i = 0; i < memberIds.length; i++) {
                        if (memberTypes[i] == 1 && (roles[i].isEmpty() || roles[i].equals("outer"))) {
                            outer[n++] = memberIds[i];
                        } else if (memberTypes[i] == 2) {
                            nestedMembersIgnored[0]++;
                        }
                    }
                    if (n > 0) {
                        relations.add(new RelationCandidate(id, Arrays.copyOf(outer, n), tags, version, ts));
                    }
                }

                @Override
                public void relationSeen() {
                    relationsSeen[0]++;
                }
            });
        }
        jobs.heartbeat(job.id());
        long[] memberWays = relations.stream().flatMapToLong(r -> Arrays.stream(r.outerWays())).sorted().distinct().toArray();
        Map<Long, long[]> memberRefs = new HashMap<>();
        log.info("OSM pass 0: {} area relations with {} outer ways", relations.size(), memberWays.length);

        // Pass 1: ways (POI candidates, and the outer ways of candidate relations).
        List<WayCandidate> ways = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file)) {
            OsmPbfReader.read(in, new OsmPbfReader.Handler() {
                @Override
                public boolean interestingKey(String key) {
                    return OsmPoiMapper.INTERESTING_KEYS.contains(key);
                }

                @Override
                public boolean wantNodes() {
                    return false;
                }

                @Override
                public void node(long id, double lat, double lon, Map<String, String> tags, int version, long ts) {}

                @Override
                public boolean wantUntaggedWay(long id) {
                    return memberWays.length > 0 && Arrays.binarySearch(memberWays, id) >= 0;
                }

                @Override
                public void way(long id, long[] refs, Map<String, String> tags, int version, long ts) {
                    if (refs.length > 0 && OsmPoiMapper.category(tags).isPresent()) {
                        ways.add(new WayCandidate(id, refs, tags, version, ts));
                    }
                    if (memberWays.length > 0 && Arrays.binarySearch(memberWays, id) >= 0) {
                        memberRefs.put(id, sample(refs, RELATION_SAMPLES_PER_WAY));
                    }
                }
            });
        }
        jobs.heartbeat(job.id());
        long[] needed = java.util.stream.LongStream.concat(ways.stream().flatMapToLong(w -> Arrays.stream(w.refs())),
                memberRefs.values().stream().flatMapToLong(Arrays::stream)).sorted().distinct().toArray();
        double[] lats = new double[needed.length];
        double[] lons = new double[needed.length];
        Arrays.fill(lats, Double.NaN);
        log.info("OSM pass 1: {} candidate ways need {} nodes", ways.size(), needed.length);

        // Pass 2: nodes (POI nodes are emitted directly; way nodes are collected for centroids).
        long[] placeLinks = {0, 0};
        Map<String, String> placeWikidata = new HashMap<>();
        long[] processed = {0};
        try (InputStream in = Files.newInputStream(file)) {
            OsmPbfReader.read(in, new OsmPbfReader.Handler() {
                @Override
                public boolean interestingKey(String key) {
                    return OsmPoiMapper.INTERESTING_KEYS.contains(key);
                }

                @Override
                public boolean wantWays() {
                    return false;
                }

                @Override
                public boolean wantAllNodeCoordinates() {
                    return needed.length > 0;
                }

                @Override
                public void node(long id, double lat, double lon, Map<String, String> tags, int version, long ts) {
                    int idx = needed.length == 0 ? -1 : Arrays.binarySearch(needed, id);
                    if (idx >= 0) {
                        lats[idx] = lat;
                        lons[idx] = lon;
                    }
                    if (tags.isEmpty()) {
                        return;
                    }
                    String place = tags.get("place");
                    if (place != null && tags.get("wikidata") != null) {
                        placeWikidata.put(tags.get("wikidata"), "node/" + id + "|" + lat + "|" + lon + "|" + place);
                    }
                    if (OsmPoiMapper.category(tags).isPresent()) {
                        run.fetched.incrementAndGet();
                        sink.accept(OsmPoiMapper.map("node", id, lat, lon, tags, version, ts, release));
                        if (++processed[0] % 50_000 == 0) {
                            jobs.heartbeat(job.id());
                        }
                    }
                }

                @Override
                public void way(long id, long[] refs, Map<String, String> tags, int version, long ts) {}
            });
        }
        jobs.heartbeat(job.id());

        long unresolvedGeometry = 0;
        for (WayCandidate w : ways) {
            run.fetched.incrementAndGet();
            double sumLat = 0;
            double sumLon = 0;
            int n = 0;
            for (long ref : w.refs()) {
                int idx = Arrays.binarySearch(needed, ref);
                if (idx >= 0 && !Double.isNaN(lats[idx])) {
                    sumLat += lats[idx];
                    sumLon += lons[idx];
                    n++;
                }
            }
            if (n == 0 || n < w.refs().length / 2) {
                run.parsed.incrementAndGet();
                run.reject("unresolved_way_geometry");
                unresolvedGeometry++;
                continue;
            }
            sink.accept(OsmPoiMapper.map("way", w.id(), sumLat / n, sumLon / n, w.tags(), w.version(), w.timestamp(), release));
        }

        long unresolvedRelations = 0;
        for (RelationCandidate r : relations) {
            run.fetched.incrementAndGet();
            double sumLat = 0;
            double sumLon = 0;
            int n = 0;
            int waysFound = 0;
            for (long wayId : r.outerWays()) {
                long[] refs = memberRefs.get(wayId);
                if (refs == null) {
                    continue;
                }
                waysFound++;
                for (long ref : refs) {
                    int idx = Arrays.binarySearch(needed, ref);
                    if (idx >= 0 && !Double.isNaN(lats[idx])) {
                        sumLat += lats[idx];
                        sumLon += lons[idx];
                        n++;
                    }
                }
            }
            // Members outside the extract (areas crossing the border) leave a partial ring; at least
            // half of the outer ways must be present for the centre to be meaningful.
            if (n == 0 || waysFound < (r.outerWays().length + 1) / 2) {
                run.parsed.incrementAndGet();
                run.reject("unresolved_relation_geometry");
                unresolvedRelations++;
                continue;
            }
            sink.accept(OsmPoiMapper.map("relation", r.id(), sumLat / n, sumLon / n, r.tags(), r.version(), r.timestamp(), release));
        }

        int linked = linkPlaces(placeWikidata, placeLinks);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("candidateWays", ways.size());
        stats.put("unresolvedWayGeometry", unresolvedGeometry);
        stats.put("areaRelations", relations.size());
        stats.put("unresolvedRelationGeometry", unresolvedRelations);
        stats.put("nestedRelationMembersIgnored", nestedMembersIgnored[0]);
        stats.put("relationsInExtract", relationsSeen[0]);
        stats.put("placeNodesWithWikidata", placeWikidata.size());
        stats.put("placesLinked", linked);
        stats.put("placeCoordinatesFilled", placeLinks[1]);
        if (nestedMembersIgnored[0] > 0) {
            run.warn(nestedMembersIgnored[0] + " members of area relations are themselves relations and were not followed; "
                    + "those areas are placed from their direct outer ways only.");
        }
        return stats;
    }

    /** Records OSM place nodes as identities of indexed places; fills coordinates a place lacks. */
    int linkPlaces(Map<String, String> placeWikidata, long[] counts) {
        int linked = 0;
        List<Map.Entry<String, String>> entries = new ArrayList<>(placeWikidata.entrySet());
        for (int i = 0; i < entries.size(); i += 500) {
            for (Map.Entry<String, String> e : entries.subList(i, Math.min(entries.size(), i + 500))) {
                String[] p = e.getValue().split("\\|");
                Optional<java.util.UUID> place = jdbc.sql("SELECT id FROM place WHERE wikidata_id = :q")
                        .param("q", e.getKey()).query(java.util.UUID.class).optional();
                if (place.isEmpty()) {
                    continue;
                }
                jdbc.sql("""
                                INSERT INTO place_source_identity (place_id, source_id, external_id, attributes, last_seen_at)
                                VALUES (:p, 'openstreetmap', :x, CAST(:a AS jsonb), now())
                                ON CONFLICT (source_id, external_id) DO UPDATE SET place_id = EXCLUDED.place_id,
                                    attributes = EXCLUDED.attributes, last_seen_at = now()
                                """)
                        .param("p", place.get()).param("x", p[0])
                        .param("a", json.write(Map.of("place", p[3], "latitude", Double.parseDouble(p[1]),
                                "longitude", Double.parseDouble(p[2]))))
                        .update();
                linked++;
                counts[1] += jdbc.sql("""
                                UPDATE place SET latitude = :lat, longitude = :lon, coordinates_source = 'openstreetmap', updated_at = now()
                                WHERE id = :p AND latitude IS NULL
                                """).param("lat", Double.parseDouble(p[1])).param("lon", Double.parseDouble(p[2]))
                        .param("p", place.get()).update();
            }
        }
        counts[0] = linked;
        return linked;
    }
}
