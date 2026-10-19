package io.destinationintel.ingestion.overture;

import io.destinationintel.common.Json;
import io.destinationintel.config.AppProperties;
import io.destinationintel.ingestion.JobHandler;
import io.destinationintel.ingestion.JobRepository;
import io.destinationintel.ingestion.JobRepository.JobRow;
import io.destinationintel.ingestion.run.SyncRunRecorder;
import io.destinationintel.ingestion.run.SyncRunRecorder.Run;
import io.destinationintel.poi.PoiCategories;
import io.destinationintel.poi.PoiPipeline;
import io.destinationintel.poi.RawPoi;
import io.destinationintel.source.SourceRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Job "overture_places": imports Overture Maps places around India's indexed destinations from
 * a release's GeoParquet files. The release and its files come from Overture's STAC catalog; each
 * file is checked against the catalogued size and row count before use. Records are read with an
 * embedded DuckDB (its built-in Parquet reader only, no extensions or network access) and passed
 * through the shared POI pipeline, which matches them against OpenStreetMap and existing POIs.
 *
 * <p>Overture is a complement to OSM: only places within a box of the configured radius around
 * indexed cities, towns, villages, islands and parks, with confidence at or above the configured
 * minimum, in mapped travel categories and not marked outside India, are imported.
 */
@Component
public class OverturePlacesHandler implements JobHandler {

    public static final String SOURCE_ID = "overture-places";
    private static final Logger log = LoggerFactory.getLogger(OverturePlacesHandler.class);

    private final OvertureCatalog catalog;
    private final PoiPipeline pipeline;
    private final SyncRunRecorder recorder;
    private final SourceRepository sources;
    private final JobRepository jobs;
    private final JdbcClient jdbc;
    private final Json json;
    private final AppProperties.Ingestion config;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20)).build();

    public OverturePlacesHandler(OvertureCatalog catalog, PoiPipeline pipeline, SyncRunRecorder recorder,
                                 SourceRepository sources, JobRepository jobs, JdbcClient jdbc, Json json, AppProperties props) {
        this.catalog = catalog;
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
        return "overture_places";
    }

    private record Destination(double lat, double lon) {}

    @Override
    public Map<String, Object> run(JobRow job) {
        if (!sources.isEnabled(SOURCE_ID)) {
            throw new PermanentFailure("The Overture Maps source is disabled in the source registry");
        }
        Map<String, Object> params = json.map(job.params());
        boolean force = Boolean.TRUE.equals(params.get("force"));
        String requested = params.get("release") instanceof String r ? r : config.overtureRelease();
        double radiusKm = config.overtureRadiusKm();
        Run run = recorder.start(SOURCE_ID, job.id(), "india:destinations-" + Math.round(radiusKm) + "km");
        try {
            OvertureRelease release = catalog.resolve(requested);
            run.datasetVersion(release.release());
            Optional<String> previous = jdbc.sql("""
                            SELECT dataset_version FROM source_sync_run WHERE source_id = :s AND scope = :scope
                              AND status IN ('succeeded', 'succeeded_with_warnings') ORDER BY started_at DESC LIMIT 1
                            """).param("s", SOURCE_ID).param("scope", run.scope).query(String.class).optional();
            if (!force && previous.isPresent() && previous.get().equals(release.release())) {
                run.warn("Release " + release.release() + " was already imported; nothing to do.");
                recorder.finish(run, false, false);
                return Map.of("status", "unchanged", "release", release.release());
            }
            if (!"2.0.0".equals(release.schemaVersion()) && !String.valueOf(release.schemaVersion()).startsWith("2.")) {
                throw new PermanentFailure("Overture schema " + release.schemaVersion()
                        + " is not supported by this adapter (expected 2.x); the column mapping must be reviewed first.");
            }

            recorder.stage(run, "fetch");
            List<Path> files = new ArrayList<>();
            for (OvertureRelease.DataFile f : release.files()) {
                files.add(fetch(release.release(), f, job));
            }
            recorder.stage(run, "validate");
            verifyRowCounts(release, files);

            List<Destination> destinations = jdbc.sql("""
                            SELECT latitude, longitude FROM place
                            WHERE lifecycle_status = 'active' AND is_destination AND latitude IS NOT NULL
                              AND kind IN ('city', 'town', 'village', 'island', 'park')
                            """).query((rs, i) -> new Destination(rs.getDouble(1), rs.getDouble(2))).list();
            if (destinations.isEmpty()) {
                throw new IllegalStateException("No indexed destinations to import Overture places around");
            }

            OffsetDateTime runStart = OffsetDateTime.now(ZoneOffset.UTC);
            recorder.stage(run, "parse");
            PoiPipeline.Session session = pipeline.open(run, runStart);
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("release", release.release());
            stats.put("files", files.stream().map(p -> p.getFileName().toString()).toList());
            stats.put("destinationsCovered", destinations.size());
            read(files, destinations, radiusKm, release.release(), run, session, job);
            stats.putAll(pipeline.finish(run, session, runStart, new PoiPipeline.Scope(true, null, 0.3),
                    config.poiDestinationRadiusKm()));
            stats.put("run", run.summary());
            cleanDownloads(release.release(), config.keepDownloads());
            return stats;
        } catch (RuntimeException e) {
            recorder.fail(run, e.getMessage());
            throw e;
        }
    }

    /** Downloads a file once per release; a complete earlier download of the same file is reused. */
    Path fetch(String release, OvertureRelease.DataFile f, JobRow job) {
        if (!f.name().matches("^[A-Za-z0-9._-]+\\.parquet$")) {
            throw new IllegalStateException("Unexpected Overture file name " + f.name());
        }
        Path dir = Path.of(config.workDir(), "overture", release);
        Path target = dir.resolve(f.name());
        try {
            Files.createDirectories(dir);
            if (Files.exists(target) && Files.size(target) == f.size()) {
                log.info("Reusing downloaded Overture file {}", f.name());
                return target;
            }
            Path partial = dir.resolve(f.name() + ".part");
            HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(f.href()))
                    .header("User-Agent", config.userAgent()).timeout(Duration.ofMinutes(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() != 200) {
                throw new IOException("Overture download returned HTTP " + r.statusCode() + " for " + f.name());
            }
            long written;
            try (InputStream in = r.body()) {
                written = Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
            }
            if (f.size() > 0 && written != f.size()) {
                Files.deleteIfExists(partial);
                throw new IOException("Overture file " + f.name() + " truncated: " + written + " of " + f.size() + " bytes");
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("Downloaded Overture file {} ({} MB)", f.name(), written / (1024 * 1024));
            jobs.heartbeat(job.id());
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Overture download interrupted", e);
        }
    }

    private Connection duckdb() throws SQLException {
        Properties p = new Properties();
        p.setProperty("jdbc_stream_results", "true");
        Connection c = DriverManager.getConnection("jdbc:duckdb:", p);
        try (Statement s = c.createStatement()) {
            s.execute("SET autoinstall_known_extensions = false");
            s.execute("SET autoload_known_extensions = false");
            s.execute("SET memory_limit = '1GB'");
            s.execute("SET threads = 4");
            s.execute("SET temp_directory = '" + Path.of(config.workDir(), "overture", "duckdb-tmp").toString().replace("'", "") + "'");
        }
        return c;
    }

    private static String fileList(List<Path> files) {
        return "[" + String.join(", ", files.stream().map(p -> "'" + p.toString().replace("'", "''") + "'").toList()) + "]";
    }

    /** The Parquet footers must parse and report exactly the row counts the catalog publishes. */
    void verifyRowCounts(OvertureRelease release, List<Path> files) {
        try (Connection c = duckdb(); Statement s = c.createStatement()) {
            for (int i = 0; i < files.size(); i++) {
                long expected = release.files().get(i).rows();
                try (ResultSet rs = s.executeQuery("SELECT sum(num_rows) FROM parquet_file_metadata('"
                        + files.get(i).toString().replace("'", "''") + "')")) {
                    rs.next();
                    long actual = rs.getLong(1);
                    if (expected > 0 && actual != expected) {
                        Files.deleteIfExists(files.get(i));
                        throw new IllegalStateException("Overture file " + files.get(i).getFileName() + " has " + actual
                                + " rows; the catalog lists " + expected);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Overture file failed validation: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final String QUERY = """
            SELECT p.id, p.version, p.confidence, p.names."primary" AS name, map_extract_value(p.names.common, 'en') AS name_en,
                   p.basic_category, p.taxonomy."primary" AS taxonomy_primary,
                   array_to_string(p.taxonomy.hierarchy, '>') AS hierarchy,
                   p.websites[1] AS website, p.phones[1] AS phone,
                   p.addresses[1].freeform AS address, p.addresses[1].locality AS locality,
                   p.addresses[1].postcode AS postcode, p.addresses[1].region AS region, p.addresses[1].country AS country,
                   p.operating_status, p.brand.names."primary" AS brand, p.brand.wikidata AS brand_wikidata,
                   list_distinct(list_transform(p.sources, s -> s.dataset)) AS datasets,
                   list_max(list_transform(p.sources, s -> s.update_time)) AS updated,
                   to_json(p.names)::VARCHAR AS names_json, to_json(p.sources)::VARCHAR AS sources_json,
                   to_json(p.taxonomy)::VARCHAR AS taxonomy_json, to_json(p.addresses)::VARCHAR AS addresses_json,
                   to_json(p.websites)::VARCHAR AS websites_json, to_json(p.phones)::VARCHAR AS phones_json,
                   (p.bbox.ymin + p.bbox.ymax) / 2 AS lat, (p.bbox.xmin + p.bbox.xmax) / 2 AS lon
            FROM read_parquet(%s) p
            WHERE p.bbox.xmin BETWEEN 68 AND 97.5 AND p.bbox.ymin BETWEEN 6.4 AND 37.6 AND p.confidence >= ?
              AND EXISTS (SELECT 1 FROM dest_cell d
                          WHERE d.cy = floor(p.bbox.ymin / 0.25)::INTEGER AND d.cx = floor(p.bbox.xmin / 0.25)::INTEGER
                            AND p.bbox.ymin BETWEEN d.lat - d.dlat AND d.lat + d.dlat
                            AND p.bbox.xmin BETWEEN d.lon - d.dlon AND d.lon + d.dlon)
            """;

    void read(List<Path> files, List<Destination> destinations, double radiusKm, String release, Run run,
              PoiPipeline.Session session, JobRow job) {
        try (Connection c = duckdb()) {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TEMP TABLE dest_cell (cy INTEGER, cx INTEGER, lat DOUBLE, lon DOUBLE, dlat DOUBLE, dlon DOUBLE)");
            }
            // Each destination's box is registered in every 0.25-degree grid cell it touches, so the
            // spatial filter is a hash join on the cell followed by an exact box test.
            try (PreparedStatement ins = c.prepareStatement("INSERT INTO dest_cell VALUES (?, ?, ?, ?, ?, ?)")) {
                for (Destination d : destinations) {
                    double dlat = radiusKm / 111.0;
                    double dlon = radiusKm / (111.0 * Math.max(0.2, Math.cos(Math.toRadians(d.lat()))));
                    for (int cy = (int) Math.floor((d.lat() - dlat) / 0.25); cy <= (int) Math.floor((d.lat() + dlat) / 0.25); cy++) {
                        for (int cx = (int) Math.floor((d.lon() - dlon) / 0.25); cx <= (int) Math.floor((d.lon() + dlon) / 0.25); cx++) {
                            ins.setInt(1, cy);
                            ins.setInt(2, cx);
                            ins.setDouble(3, d.lat());
                            ins.setDouble(4, d.lon());
                            ins.setDouble(5, dlat);
                            ins.setDouble(6, dlon);
                            ins.addBatch();
                        }
                    }
                }
                ins.executeBatch();
            }
            try (PreparedStatement q = c.prepareStatement(QUERY.formatted(fileList(files)))) {
                q.setDouble(1, config.overtureMinConfidence());
                q.setFetchSize(2000);
                try (ResultSet rs = q.executeQuery()) {
                    long rows = 0;
                    while (rs.next()) {
                        run.fetched.incrementAndGet();
                        RawPoi poi = map(rs, release, run);
                        if (poi != null) {
                            session.accept(poi);
                        }
                        if (++rows % 50_000 == 0) {
                            jobs.heartbeat(job.id());
                            log.info("Overture: {} rows read", rows);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Reading Overture places failed: " + e.getMessage(), e);
        }
    }

    /** Maps one row, or records why it is skipped and returns null. */
    RawPoi map(ResultSet rs, String release, Run run) throws SQLException {
        String taxonomy = rs.getString("taxonomy_primary");
        String basic = rs.getString("basic_category");
        Optional<PoiCategories.Category> category = PoiCategories.fromOverture(taxonomy, basic);
        if (category.isEmpty()) {
            run.parsed.incrementAndGet();
            run.reject("unmapped_category");
            return null;
        }
        String country = rs.getString("country");
        if (country != null && !country.isBlank() && !country.toUpperCase().startsWith("IN")) {
            run.parsed.incrementAndGet();
            run.reject("outside_india_country");
            return null;
        }
        String status = rs.getString("operating_status");
        if ("permanently_closed".equals(status)) {
            run.parsed.incrementAndGet();
            run.reject("permanently_closed");
            return null;
        }
        String primaryName = rs.getString("name");
        String english = rs.getString("name_en");
        String name = english != null && !english.isBlank() ? english : primaryName;

        Map<String, Object> attrs = new LinkedHashMap<>();
        putIfPresent(attrs, "website", rs.getString("website"));
        putIfPresent(attrs, "phone", rs.getString("phone"));
        String address = composeAddress(rs.getString("address"), rs.getString("locality"), rs.getString("postcode"));
        putIfPresent(attrs, "address", address);
        putIfPresent(attrs, "brand", rs.getString("brand"));
        if (primaryName != null && !primaryName.equals(name)) {
            attrs.put("local_name", primaryName);
        }
        attrs.put("confidence", Math.round(rs.getDouble("confidence") * 1000) / 1000.0);
        if ("temporarily_closed".equals(status)) {
            attrs.put("operating_status", status);
        }
        // Sorted so the content hash does not change when only the list order differs.
        List<String> datasets = stringList(rs.getArray("datasets")).stream().sorted().toList();
        attrs.put("contributing_datasets", datasets);

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", rs.getString("id"));
        raw.put("version", rs.getInt("version"));
        raw.put("confidence", rs.getDouble("confidence"));
        raw.put("basic_category", basic);
        raw.put("taxonomy", json.read(rs.getString("taxonomy_json"), Object.class));
        raw.put("names", json.read(rs.getString("names_json"), Object.class));
        raw.put("addresses", json.read(rs.getString("addresses_json"), Object.class));
        raw.put("websites", json.read(rs.getString("websites_json"), Object.class));
        raw.put("phones", json.read(rs.getString("phones_json"), Object.class));
        raw.put("sources", json.read(rs.getString("sources_json"), Object.class));
        raw.put("operating_status", status);
        raw.put("brand_wikidata", rs.getString("brand_wikidata"));

        return new RawPoi(SOURCE_ID, rs.getString("id"), String.valueOf(rs.getInt("version")), parseTime(rs.getString("updated")),
                release, name, taxonomy != null ? taxonomy : basic, category.get(), rs.getDouble("lat"), rs.getDouble("lon"),
                null, attrs, raw);
    }

    static String composeAddress(String freeform, String locality, String postcode) {
        List<String> parts = new ArrayList<>();
        if (freeform != null && !freeform.isBlank()) {
            parts.add(freeform.trim());
        }
        if (locality != null && !locality.isBlank() && (freeform == null || !freeform.contains(locality))) {
            parts.add(locality.trim());
        }
        if (postcode != null && !postcode.isBlank() && (freeform == null || !freeform.contains(postcode))) {
            parts.add(postcode.trim());
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static void putIfPresent(Map<String, Object> m, String k, String v) {
        if (v != null && !v.isBlank()) {
            m.put(k, v.trim());
        }
    }

    private static List<String> stringList(java.sql.Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object o : (Object[]) array.getArray()) {
            if (o != null) {
                out.add(o.toString());
            }
        }
        return out;
    }

    static OffsetDateTime parseTime(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(s);
        } catch (DateTimeParseException e) {
            try {
                return java.time.LocalDateTime.parse(s.replace(' ', 'T')).atOffset(ZoneOffset.UTC);
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    /**
     * Keeps the working directory bounded after a completed run: other releases are always
     * removed, the current one too unless downloads are kept. A failed run keeps its files so a
     * retry can reuse them.
     */
    private void cleanDownloads(String current, boolean keepCurrent) {
        Path root = Path.of(config.workDir(), "overture");
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path d : dirs.toList()) {
                String name = d.getFileName().toString();
                boolean isCurrent = name.equals(current);
                if (Files.isDirectory(d) && (!isCurrent || !keepCurrent) && name.matches("^\\d{4}-\\d{2}-\\d{2}\\.\\d+$")) {
                    try (Stream<Path> walk = Files.walk(d)) {
                        for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                            Files.deleteIfExists(p);
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Could not clean old Overture downloads: {}", e.getMessage());
        }
    }
}
