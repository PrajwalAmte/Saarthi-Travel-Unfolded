package io.destinationintel.it;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only stand-in for the Wikivoyage and Wikidata APIs, serving fixture articles. It lets
 * ingestion be exercised end to end without network access; it is never used outside tests.
 */
public final class SourceStub {

    public record Article(long pageId, long revisionId, String timestamp, String fixture) {}

    private static final SourceStub INSTANCE = new SourceStub();

    private final HttpServer server;
    private final Map<String, Article> articles = new ConcurrentHashMap<>();
    public final AtomicInteger requests = new AtomicInteger();

    private SourceStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/wv/api.php", exchange -> {
            requests.incrementAndGet();
            Map<String, String> q = query(exchange.getRequestURI().getRawQuery());
            Article a = articles.get(q.getOrDefault("titles", ""));
            String body;
            if (a == null) {
                body = "{\"batchcomplete\":true,\"query\":{\"pages\":[{\"ns\":0,\"title\":\"" + q.get("titles") + "\",\"missing\":true}]}}";
            } else {
                String content = fixture(a.fixture()).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
                body = "{\"batchcomplete\":true,\"query\":{\"pages\":[{\"pageid\":" + a.pageId() + ",\"ns\":0,\"title\":\""
                        + q.get("titles") + "\",\"revisions\":[{\"revid\":" + a.revisionId() + ",\"timestamp\":\"" + a.timestamp()
                        + "\",\"slots\":{\"main\":{\"contentmodel\":\"wikitext\",\"content\":\"" + content + "\"}}}]}]}}";
            }
            respond(exchange, body);
        });
        server.createContext("/wd/api.php", exchange -> {
            requests.incrementAndGet();
            Map<String, String> q = query(exchange.getRequestURI().getRawQuery());
            StringBuilder sb = new StringBuilder("{\"entities\":{");
            String[] ids = q.getOrDefault("ids", "").split("\\|");
            for (int i = 0; i < ids.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                StringBuilder sitelinks = new StringBuilder();
                int n = ids[i].equals("Q999999001") ? 40 : 2;
                for (int s = 0; s < n; s++) {
                    if (s > 0) {
                        sitelinks.append(',');
                    }
                    sitelinks.append("\"l").append(s).append("wiki\":{\"site\":\"l").append(s).append("wiki\",\"title\":\"x\"}");
                }
                sb.append('"').append(ids[i]).append("\":{\"id\":\"").append(ids[i]).append("\",\"sitelinks\":{")
                        .append(sitelinks).append("}}");
            }
            respond(exchange, sb.append("}}").toString());
        });
        server.start();
    }

    public static SourceStub get() {
        return INSTANCE;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void article(String title, Article article) {
        articles.put(title, article);
    }

    public void remove(String title) {
        articles.remove(title);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null) {
            return out;
        }
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                out.put(URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    static String fixture(String name) {
        try (InputStream in = SourceStub.class.getResourceAsStream("/fixtures/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
