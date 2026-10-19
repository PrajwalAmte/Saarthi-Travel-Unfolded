package io.destinationintel.ingestion.overture;

import io.destinationintel.common.Json;
import io.destinationintel.config.AppProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Reads Overture's public STAC catalog to find a release of the places theme and the GeoParquet
 * files covering India. Only files under the official release buckets are accepted.
 */
@Component
public class OvertureCatalog {

    /** India's bounding box, the same one POI validation uses (west, south, east, north). */
    static final double[] INDIA = {68.0, 6.4, 97.5, 37.6};

    private static final List<String> ALLOWED_PREFIXES = List.of(
            "https://overturemaps-us-west-2.s3.us-west-2.amazonaws.com/release/",
            "https://overturemapswestus2.blob.core.windows.net/release/");

    private final Json json;
    private final AppProperties.Ingestion config;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20)).build();

    public OvertureCatalog(Json json, AppProperties props) {
        this.json = json;
        this.config = props.ingestion();
    }

    /** The configured release, or the catalog's latest when none is configured. */
    public OvertureRelease resolve(String requested) {
        String root = config.overtureStacUrl();
        JsonNode catalog = get(root);
        String release = requested != null && !requested.isBlank() ? requested : text(catalog, "latest");
        if (release == null || !release.matches("^\\d{4}-\\d{2}-\\d{2}\\.\\d+$")) {
            throw new IllegalStateException("The Overture catalog did not name a usable release: " + release);
        }
        String base = root.substring(0, root.lastIndexOf('/') + 1) + release + "/";
        JsonNode releaseCatalog = get(base + "catalog.json");
        String schema = text(releaseCatalog, "schema:version");
        JsonNode collection = get(base + "places/place/collection.json");
        List<OvertureRelease.DataFile> files = new ArrayList<>();
        for (JsonNode link : collection.path("links")) {
            if (!"item".equals(text(link, "rel"))) {
                continue;
            }
            JsonNode item = get(link.path("href").asString());
            double[] bbox = new double[4];
            for (int i = 0; i < 4; i++) {
                bbox[i] = item.path("bbox").path(i).asDouble();
            }
            if (!intersects(bbox, INDIA)) {
                continue;
            }
            JsonNode asset = item.path("assets").path("aws");
            String href = text(asset, "href");
            if (href == null || ALLOWED_PREFIXES.stream().noneMatch(href::startsWith) || !href.contains("/" + release + "/")) {
                throw new IllegalStateException("Unexpected Overture data location: " + href);
            }
            files.add(new OvertureRelease.DataFile(href.substring(href.lastIndexOf('/') + 1), href,
                    asset.path("file:size").asLong(-1), item.path("properties").path("num_rows").asLong(-1), bbox));
        }
        if (files.isEmpty()) {
            throw new IllegalStateException("No Overture places files intersect India in release " + release);
        }
        return new OvertureRelease(release, schema, files);
    }

    static boolean intersects(double[] a, double[] b) {
        return a[0] <= b[2] && a[2] >= b[0] && a[1] <= b[3] && a[3] >= b[1];
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private JsonNode get(String url) {
        if (!url.startsWith("https://stac.overturemaps.org/")) {
            throw new IllegalStateException("Refusing to read a catalog outside stac.overturemaps.org: " + url);
        }
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", config.userAgent()).timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                throw new IllegalStateException("Overture catalog " + url + " returned HTTP " + r.statusCode());
            }
            return json.tree(r.body());
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the Overture catalog: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading the Overture catalog", e);
        }
    }
}
