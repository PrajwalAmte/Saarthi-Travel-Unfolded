package io.destinationintel.ingestion.wikidata;

import io.destinationintel.config.AppProperties;
import io.destinationintel.ingestion.HttpFetcher;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wikidata access: SPARQL for bulk selection (paced, since the public query service is
 * shared and may rate-limit) and the Action API for entity details in batches of 50.
 */
@Component
public class WikidataClient {

    public static final String LANGUAGES = "en|hi|kn|ta|te|ml|mr|bn|gu|pa|or|as|ur";

    public record SearchCandidate(String id, String label, String description) {}

    private final HttpFetcher http;
    private final JsonMapper mapper;
    private final String sparqlUrl;
    private final String apiUrl;
    private long lastSparqlAt;

    public WikidataClient(HttpFetcher http, JsonMapper mapper, AppProperties props) {
        this.http = http;
        this.mapper = mapper;
        this.sparqlUrl = props.ingestion().wikidataSparqlUrl();
        this.apiUrl = props.ingestion().wikidataApiUrl();
    }

    /** Runs a SPARQL SELECT and returns each binding as a map of variable name to value. */
    public synchronized List<Map<String, String>> select(String query) {
        long gap = System.currentTimeMillis() - lastSparqlAt;
        if (gap < 10_000) {
            try {
                Thread.sleep(10_000 - gap);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            // The query service allows up to 60 s per query; the generic HTTP timeout is shorter.
            String body = http.postForm(sparqlUrl, Map.of("query", query), "application/sparql-results+json",
                    java.time.Duration.ofSeconds(70));
            JsonNode root = mapper.readTree(body);
            List<Map<String, String>> rows = new ArrayList<>();
            for (JsonNode b : root.path("results").path("bindings")) {
                Map<String, String> row = new LinkedHashMap<>();
                for (Map.Entry<String, JsonNode> e : b.properties()) {
                    row.put(e.getKey(), e.getValue().path("value").asString());
                }
                rows.add(row);
            }
            return rows;
        } finally {
            lastSparqlAt = System.currentTimeMillis();
        }
    }

    /** Fetches up to 50 entities. Returns the "entities" object keyed by id. */
    public JsonNode entities(Collection<String> ids, String props) {
        if (ids.isEmpty()) {
            return mapper.createObjectNode();
        }
        if (ids.size() > 50) {
            throw new IllegalArgumentException("At most 50 ids per request");
        }
        Map<String, String> q = new LinkedHashMap<>();
        q.put("action", "wbgetentities");
        q.put("format", "json");
        q.put("ids", String.join("|", ids));
        q.put("props", props);
        q.put("languages", LANGUAGES);
        q.put("maxlag", "5");
        JsonNode root = mapper.readTree(http.get(apiUrl, q, "application/json"));
        if (root.has("error")) {
            throw new HttpFetcher.FetchException("Wikidata API error: " + root.path("error").path("code").asString(), 200);
        }
        return root.path("entities");
    }

    public List<SearchCandidate> search(String text, int limit) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("action", "wbsearchentities");
        q.put("format", "json");
        q.put("language", "en");
        q.put("uselang", "en");
        q.put("type", "item");
        q.put("limit", String.valueOf(Math.min(limit, 20)));
        q.put("search", text);
        JsonNode root = mapper.readTree(http.get(apiUrl, q, "application/json"));
        List<SearchCandidate> out = new ArrayList<>();
        for (JsonNode n : root.path("search")) {
            out.add(new SearchCandidate(n.path("id").asString(), n.path("label").asString(null),
                    n.path("description").asString(null)));
        }
        return out;
    }

    public static String qid(String entityUri) {
        if (entityUri == null) {
            return null;
        }
        int i = entityUri.lastIndexOf('/');
        String id = i >= 0 ? entityUri.substring(i + 1) : entityUri;
        return id.matches("^Q[0-9]+$") ? id : null;
    }

    /** Parses a WKT "Point(lon lat)" literal into [lat, lon]. */
    public static double[] point(String wkt) {
        if (wkt == null) {
            return null;
        }
        var m = java.util.regex.Pattern.compile("Point\\(([-0-9.eE]+) ([-0-9.eE]+)\\)").matcher(wkt);
        if (!m.find()) {
            return null;
        }
        double lon = Double.parseDouble(m.group(1));
        double lat = Double.parseDouble(m.group(2));
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            return null;
        }
        return new double[] {lat, lon};
    }
}
