package io.destinationintel.ai;

import io.destinationintel.ingestion.HttpFetcher;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads a place's own website for the checker, carefully: only http(s), only the site already on record (no hop to
 * another host, even by redirect), never a private or local address, robots.txt respected, two seconds between
 * requests to a host, at most 1.5 MB per page. Returns the visible text and the same-site links.
 */
@Component
public class SiteReader {

    public record Link(String text, String url) {}

    public record Page(String url, String title, String text, List<Link> links) {}

    static final int MAX_BYTES = 1_500_000;
    static final int MAX_TEXT = 12_000;
    static final int MAX_LINKS = 40;
    static final long PAUSE_MS = 2000;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final String userAgent;
    private final boolean allowPrivateHosts;
    /** Tests only: "domain=host:port" sends requests for that domain (and its subdomains) to a local stub. */
    private final String[] hostOverride;
    private final Map<String, Long> lastRequest = new ConcurrentHashMap<>();
    private final Map<String, List<String>> robots = new ConcurrentHashMap<>();

    public SiteReader(HttpFetcher http, @Value("${app.ai.checker-allow-private-hosts:false}") boolean allowPrivateHosts,
                      @Value("${app.ai.checker-host-override:}") String hostOverride) {
        this.userAgent = http.userAgent();
        this.allowPrivateHosts = allowPrivateHosts;
        this.hostOverride = hostOverride == null || !hostOverride.contains("=") ? null : hostOverride.split("=", 2);
    }

    /** Forgets cached robots.txt rules, so each checking run sees the site's current rules. */
    public void forgetRobots() {
        robots.clear();
    }

    /** The page, or empty when it may not or cannot be read (blocked, not HTML, off-site, error). */
    public Optional<Page> read(String url, String site) {
        try {
            URI uri = URI.create(url);
            for (int hop = 0; hop < 4; hop++) {
                if (!allowed(uri, site)) {
                    return Optional.empty();
                }
                HttpResponse<InputStream> r = send(uri);
                int status = r.statusCode();
                if (status >= 300 && status < 400) {
                    String location = r.headers().firstValue("location").orElse(null);
                    r.body().close();
                    if (location == null) {
                        return Optional.empty();
                    }
                    uri = uri.resolve(location);
                    continue;
                }
                String type = r.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
                if (status != 200 || !(type.contains("text/html") || type.contains("application/xhtml"))) {
                    r.body().close();
                    return Optional.empty();
                }
                String html;
                try (InputStream in = r.body()) {
                    html = new String(in.readNBytes(MAX_BYTES), StandardCharsets.UTF_8);
                }
                return Optional.of(parse(uri, html, site));
            }
            return Optional.empty();
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    static Page parse(URI uri, String html, String site) {
        Document doc = Jsoup.parse(html, uri.toString());
        doc.select("script, style, noscript, svg, iframe, form").remove();
        String text = doc.body() == null ? "" : doc.body().text();
        if (text.length() > MAX_TEXT) {
            text = text.substring(0, MAX_TEXT);
        }
        Map<String, Link> links = new LinkedHashMap<>();
        for (Element a : doc.select("a[href]")) {
            String href = a.absUrl("href").replaceAll("#.*$", "");
            String label = a.text().trim();
            if (href.isEmpty() || label.isEmpty() || links.size() >= MAX_LINKS || href.equals(uri.toString())) {
                continue;
            }
            try {
                URI target = URI.create(href);
                if (("http".equals(target.getScheme()) || "https".equals(target.getScheme())) && sameSite(target.getHost(), site)) {
                    links.putIfAbsent(href, new Link(label.length() > 80 ? label.substring(0, 80) : label, href));
                }
            } catch (IllegalArgumentException ignored) {
                // Not a usable link.
            }
        }
        return new Page(uri.toString(), doc.title(), text, List.copyOf(links.values()));
    }

    private HttpResponse<InputStream> send(URI uri) throws IOException, InterruptedException {
        pace(uri.getHost());
        URI target = uri;
        String h = uri.getHost();
        if (hostOverride != null && h != null && (h.equals(hostOverride[0]) || h.endsWith("." + hostOverride[0]))) {
            target = URI.create("http://" + hostOverride[1] + uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
        }
        HttpRequest req = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(20)).header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml").GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofInputStream());
    }

    private boolean allowed(URI uri, String site) throws IOException, InterruptedException {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || !("http".equals(scheme) || "https".equals(scheme)) || !sameSite(host, site) || !publicHost(host)) {
            return false;
        }
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        for (String prefix : robotsDisallowed(uri)) {
            if (path.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    /** The same site: equal hosts, ignoring a leading "www.". */
    static boolean sameSite(String host, String site) {
        if (host == null || site == null) {
            return false;
        }
        return bare(host).equals(bare(site));
    }

    private static String bare(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    private boolean publicHost(String host) {
        if (allowPrivateHosts) {
            return true;
        }
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
                        || a.isMulticastAddress() || (a instanceof Inet6Address && (a.getAddress()[0] & 0xfe) == 0xfc)) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** Disallowed path prefixes for us (our own group, else "*"); none when robots.txt is missing. */
    private List<String> robotsDisallowed(URI uri) throws IOException, InterruptedException {
        String key = uri.getScheme() + "://" + uri.getAuthority();
        List<String> cached = robots.get(key);
        if (cached != null) {
            return cached;
        }
        List<String> rules = List.of();
        HttpResponse<InputStream> r = send(URI.create(key + "/robots.txt"));
        try (InputStream in = r.body()) {
            if (r.statusCode() == 200) {
                rules = disallowed(new String(in.readNBytes(200_000), StandardCharsets.UTF_8), userAgent);
            } else if (r.statusCode() == 401 || r.statusCode() == 403) {
                rules = List.of("/");
            }
        }
        robots.put(key, rules);
        return rules;
    }

    static List<String> disallowed(String robotsTxt, String userAgent) {
        String agent = userAgent.toLowerCase(Locale.ROOT);
        List<String> ours = new ArrayList<>();
        List<String> any = new ArrayList<>();
        boolean matchesUs = false;
        boolean matchesAny = false;
        boolean inAgents = false;
        boolean oursFound = false;
        for (String raw : robotsTxt.split("\\R")) {
            String line = raw.replaceAll("#.*$", "").trim();
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String field = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (field.equals("user-agent")) {
                if (!inAgents) {
                    matchesUs = false;
                    matchesAny = false;
                }
                inAgents = true;
                String v = value.toLowerCase(Locale.ROOT);
                if (v.equals("*")) {
                    matchesAny = true;
                } else if (!v.isEmpty() && agent.contains(v)) {
                    matchesUs = true;
                    oursFound = true;
                }
            } else {
                inAgents = false;
                if (field.equals("disallow") && !value.isEmpty()) {
                    if (matchesUs) {
                        ours.add(value);
                    }
                    if (matchesAny) {
                        any.add(value);
                    }
                }
            }
        }
        return oursFound ? ours : any;
    }

    private void pace(String host) throws InterruptedException {
        long now = System.currentTimeMillis();
        Long last = lastRequest.put(host, now);
        if (last != null && now - last < PAUSE_MS) {
            Thread.sleep(PAUSE_MS - (now - last));
            lastRequest.put(host, System.currentTimeMillis());
        }
    }
}
