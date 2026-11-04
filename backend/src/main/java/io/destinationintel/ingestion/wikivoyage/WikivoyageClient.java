package io.destinationintel.ingestion.wikivoyage;

import io.destinationintel.config.AppProperties;
import io.destinationintel.ingestion.HttpFetcher;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fetches the latest revision of a Wikivoyage article through the MediaWiki Action API. */
@Component
public class WikivoyageClient {

    public record Revision(long pageId, String title, long revisionId, OffsetDateTime timestamp, String content) {}

    private final HttpFetcher http;
    private final JsonMapper mapper;
    private final String apiUrl;

    public WikivoyageClient(HttpFetcher http, JsonMapper mapper, AppProperties props) {
        this.http = http;
        this.mapper = mapper;
        this.apiUrl = props.ingestion().wikivoyageApiUrl();
    }

    public Optional<Revision> latest(String title) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("action", "query");
        q.put("prop", "revisions");
        q.put("rvprop", "ids|timestamp|content");
        q.put("rvslots", "main");
        q.put("titles", title);
        q.put("redirects", "1");
        q.put("format", "json");
        q.put("formatversion", "2");
        q.put("maxlag", "5");
        JsonNode root = mapper.readTree(http.get(apiUrl, q, "application/json"));
        if (root.has("error")) {
            throw HttpFetcher.mediaWikiError("Wikivoyage", root.path("error").path("code").asString());
        }
        JsonNode page = root.path("query").path("pages").path(0);
        if (page.isMissingNode() || page.path("missing").asBoolean(false) || page.path("invalid").asBoolean(false)) {
            return Optional.empty();
        }
        JsonNode rev = page.path("revisions").path(0);
        if (rev.isMissingNode()) {
            return Optional.empty();
        }
        return Optional.of(new Revision(page.path("pageid").asLong(), page.path("title").asString(),
                rev.path("revid").asLong(), OffsetDateTime.parse(rev.path("timestamp").asString()),
                rev.path("slots").path("main").path("content").asString("")));
    }

    /** Each article's primary coordinates (at most 50 titles per call), keyed by title with spaces; none if unstated. */
    public Map<String, double[]> coordinatesOf(List<String> titles) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("action", "query");
        q.put("prop", "coordinates");
        q.put("coprimary", "primary");
        q.put("colimit", "max");
        q.put("titles", titles.stream().map(t -> t.replace('_', ' ')).collect(java.util.stream.Collectors.joining("|")));
        q.put("format", "json");
        q.put("formatversion", "2");
        q.put("maxlag", "5");
        JsonNode root = mapper.readTree(http.get(apiUrl, q, "application/json"));
        if (root.has("error")) {
            throw HttpFetcher.mediaWikiError("Wikivoyage", root.path("error").path("code").asString());
        }
        Map<String, double[]> out = new HashMap<>();
        for (JsonNode page : root.path("query").path("pages")) {
            JsonNode c = page.path("coordinates").path(0);
            if (c.has("lat") && "earth".equals(c.path("globe").asString("earth"))) {
                out.put(page.path("title").asString(), new double[] {c.path("lat").asDouble(), c.path("lon").asDouble()});
            }
        }
        return out;
    }

    /** The article a title leads to (following a redirect), or empty when Wikivoyage has no such page. */
    public Optional<String> articleFor(String title) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("action", "query");
        q.put("titles", title);
        q.put("redirects", "1");
        q.put("format", "json");
        q.put("formatversion", "2");
        q.put("maxlag", "5");
        JsonNode root = mapper.readTree(http.get(apiUrl, q, "application/json"));
        if (root.has("error")) {
            throw HttpFetcher.mediaWikiError("Wikivoyage", root.path("error").path("code").asString());
        }
        JsonNode page = root.path("query").path("pages").path(0);
        if (page.isMissingNode() || page.path("missing").asBoolean(false) || page.path("invalid").asBoolean(false)) {
            return Optional.empty();
        }
        return Optional.of(page.path("title").asString());
    }

    /**
     * The titles that redirect to each of the given articles (at most 50 per call), keyed by the title as asked.
     * Follows the API's continuation, so long redirect lists are complete.
     */
    public Map<String, List<String>> redirectsTo(List<String> titles) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        titles.forEach(t -> out.put(t, new ArrayList<>()));
        Map<String, String> asked = new HashMap<>();
        titles.forEach(t -> asked.put(t.replace('_', ' '), t));
        Map<String, String> q = new LinkedHashMap<>();
        q.put("action", "query");
        q.put("prop", "redirects");
        q.put("rdprop", "title");
        q.put("rdnamespace", "0");
        q.put("rdlimit", "max");
        q.put("titles", String.join("|", titles));
        q.put("format", "json");
        q.put("formatversion", "2");
        q.put("maxlag", "5");
        for (int round = 0; round < 20; round++) {
            JsonNode root = mapper.readTree(http.get(apiUrl, q, "application/json"));
            if (root.has("error")) {
                throw HttpFetcher.mediaWikiError("Wikivoyage", root.path("error").path("code").asString());
            }
            for (JsonNode n : root.path("query").path("normalized")) {
                String from = asked.remove(n.path("from").asString());
                if (from != null) {
                    asked.put(n.path("to").asString(), from);
                }
            }
            for (JsonNode page : root.path("query").path("pages")) {
                String key = asked.get(page.path("title").asString());
                if (key == null) {
                    continue;
                }
                for (JsonNode r : page.path("redirects")) {
                    out.get(key).add(r.path("title").asString());
                }
            }
            JsonNode cont = root.path("continue");
            if (!cont.has("rdcontinue")) {
                break;
            }
            q.put("rdcontinue", cont.path("rdcontinue").asString());
            q.put("continue", cont.path("continue").asString(""));
        }
        return out;
    }
}
