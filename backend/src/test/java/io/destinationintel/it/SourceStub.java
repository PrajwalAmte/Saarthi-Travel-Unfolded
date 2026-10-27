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
    /** Model names of every Gemini call received, in order. */
    public final java.util.List<String> geminiCalls = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Models that answer 503 "high demand". */
    public final java.util.Set<String> busyModels = ConcurrentHashMap.newKeySet();

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
        // Gemini stand-in. Listing batches: titles containing "Fort" under eat are flagged; long descriptions get their
        // first sentence as the summary, except titles containing "Fable" whose summary invents a year (the guard must
        // reject it); "₹" amounts are returned as prices and "local favourite" as a regional_food tag. Overview requests
        // get one faithful sentence and one with an invented number.
        server.createContext("/gemini/models/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String model = path.substring(path.lastIndexOf('/') + 1, path.indexOf(':', path.lastIndexOf('/')));
            geminiCalls.add(model);
            if (busyModels.contains(model)) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            tools.jackson.databind.json.JsonMapper m = tools.jackson.databind.json.JsonMapper.builder().build();
            String req = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String prompt = m.readTree(req).path("contents").path(0).path("parts").path(0).path("text").asString();
            tools.jackson.databind.JsonNode input = m.readTree(prompt.substring(prompt.indexOf('[')));
            tools.jackson.databind.node.ArrayNode out = m.createArrayNode();
            if (prompt.contains("Facts (JSON)")) {
                String first = input.path(0).path("text").asString();
                String sentence = first.contains(".") ? first.substring(0, first.indexOf('.') + 1) : first;
                out.addObject().put("topic", "best_for").put("text", sentence).putArray("factIds").add("f1");
                out.addObject().put("topic", "when_to_go").put("text", "Visit in 1234 for the festival.").putArray("factIds").add("f1");
            } else {
                for (tools.jackson.databind.JsonNode l : input) {
                    String title = l.path("title").asString();
                    String desc = l.path("description").asString();
                    boolean misfiled = title.contains("Fort") && l.path("section").asString().equals("eat");
                    var v = out.addObject();
                    v.put("id", l.path("id").asString()).put("fitsSection", !misfiled).put("inDestination", "yes");
                    var issues = v.putArray("issues");
                    if (misfiled) {
                        issues.add("wrong_section");
                    }
                    v.put("suggestedSection", misfiled ? "see" : l.path("section").asString());
                    v.put("confidence", 0.95).put("reason", misfiled ? "The text describes a fort, not food." : "Fits.");
                    String summary = "";
                    if (desc.length() >= 140) {
                        summary = title.contains("Fable") ? "Founded in 1888, a much-loved institution."
                                : desc.substring(0, desc.indexOf('.') + 1);
                    }
                    v.put("summary", summary);
                    var tags = v.putArray("tags");
                    if (desc.contains("local favourite")) {
                        tags.addObject().put("tag", "regional_food").put("quote", "a local favourite");
                    }
                    tags.addObject().put("tag", "nightlife").put("quote", "words that are not in the text");
                    var facts = v.putArray("facts");
                    java.util.regex.Matcher price = java.util.regex.Pattern.compile("₹\\d+").matcher(desc);
                    if (price.find()) {
                        facts.addObject().put("kind", "price").put("quote", price.group());
                    }
                }
            }
            var text = m.writeValueAsString(out);
            var resp = m.createObjectNode();
            resp.putArray("candidates").addObject().putObject("content").putArray("parts").addObject().put("text", text);
            resp.putObject("usageMetadata").put("totalTokenCount", 100);
            resp.put("modelVersion", model);
            respond(exchange, m.writeValueAsString(resp));
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
