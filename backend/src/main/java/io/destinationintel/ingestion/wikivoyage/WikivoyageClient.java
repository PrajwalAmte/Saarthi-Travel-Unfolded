package io.destinationintel.ingestion.wikivoyage;

import io.destinationintel.config.AppProperties;
import io.destinationintel.ingestion.HttpFetcher;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
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
            throw new HttpFetcher.FetchException("Wikivoyage API error: " + root.path("error").path("code").asString(), 200);
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
}
