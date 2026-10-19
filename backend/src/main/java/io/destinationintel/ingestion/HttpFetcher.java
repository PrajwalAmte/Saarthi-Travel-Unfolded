package io.destinationintel.ingestion;

import io.destinationintel.config.AppProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Polite HTTP client for source adapters: identifies itself with a descriptive User-Agent,
 * paces requests per host, honours Retry-After and retries transient failures with
 * exponential backoff. Requests are never parallelised against the same host.
 */
@Component
public class HttpFetcher {

    private static final Logger log = LoggerFactory.getLogger(HttpFetcher.class);
    private static final int MAX_ATTEMPTS = 4;

    public static class FetchException extends RuntimeException {
        private final int status;

        public FetchException(String message, int status) {
            super(message);
            this.status = status;
        }

        public FetchException(String message, Throwable cause) {
            super(message, cause);
            this.status = -1;
        }

        public int status() {
            return status;
        }
    }

    private final HttpClient client;
    private final String userAgent;
    private final Duration timeout;
    private final Duration pause;
    private final Map<String, Long> lastRequestByHost = new ConcurrentHashMap<>();

    public HttpFetcher(AppProperties props) {
        AppProperties.Ingestion cfg = props.ingestion();
        this.timeout = cfg.httpTimeout();
        this.pause = cfg.requestPause();
        this.userAgent = cfg.userAgent();
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String userAgent() {
        return userAgent;
    }

    public String get(String baseUrl, Map<String, String> query, String accept) {
        return send(URI.create(baseUrl + (query == null || query.isEmpty() ? "" : "?" + encode(query))), null, accept, timeout);
    }

    public String postForm(String url, Map<String, String> form, String accept) {
        return send(URI.create(url), encode(form), accept, timeout);
    }

    /** As {@link #postForm(String, Map, String)}, with its own timeout for slow endpoints such as SPARQL. */
    public String postForm(String url, Map<String, String> form, String accept, Duration requestTimeout) {
        return send(URI.create(url), encode(form), accept, requestTimeout);
    }

    public static String encode(Map<String, String> params) {
        StringJoiner j = new StringJoiner("&");
        params.forEach((k, v) -> j.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return j.toString();
    }

    private String send(URI uri, String formBody, String accept, Duration requestTimeout) {
        FetchException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            pace(uri.getHost());
            HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                    .timeout(requestTimeout)
                    .header("User-Agent", userAgent)
                    .header("Accept", accept == null ? "application/json" : accept)
                    .header("Accept-Encoding", "identity");
            if (formBody != null) {
                b.header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(formBody));
            } else {
                b.GET();
            }
            try {
                HttpResponse<String> response = client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return response.body();
                }
                last = new FetchException("HTTP " + status + " from " + uri.getHost() + uri.getPath(), status);
                if (status == 429 || status >= 500) {
                    // Without Retry-After, a 429 waits a full minute (Wikidata's query service can limit
                    // clients to one request per minute); server errors back off exponentially.
                    long fallback = status == 429 ? 65_000L : (long) (2000 * Math.pow(2, attempt - 1));
                    long waitMs = response.headers().firstValue("Retry-After").flatMap(HttpFetcher::seconds)
                            .map(s -> Math.min(180_000L, s * 1000L))
                            .orElse(fallback);
                    log.warn("Transient HTTP {} from {} (attempt {}/{}), retrying in {} ms", status, uri.getHost(),
                            attempt, MAX_ATTEMPTS, waitMs);
                    sleep(waitMs);
                    continue;
                }
                throw last;
            } catch (IOException e) {
                last = new FetchException("Network error contacting " + uri.getHost() + ": " + e.getClass().getSimpleName(), e);
                log.warn("Network error contacting {} (attempt {}/{}): {}", uri.getHost(), attempt, MAX_ATTEMPTS, e.toString());
                sleep((long) (2000 * Math.pow(2, attempt - 1)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FetchException("Interrupted while contacting " + uri.getHost(), e);
            }
        }
        throw last;
    }

    private static java.util.Optional<Long> seconds(String header) {
        try {
            return java.util.Optional.of(Long.parseLong(header.trim()));
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }

    private void pace(String host) {
        long minGap = pause.toMillis();
        if (minGap <= 0 || host == null) {
            return;
        }
        synchronized (lastRequestByHost) {
            long now = System.currentTimeMillis();
            long previous = lastRequestByHost.getOrDefault(host, 0L);
            long wait = previous + minGap - now;
            if (wait > 0) {
                sleep(wait);
            }
            lastRequestByHost.put(host, System.currentTimeMillis());
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
