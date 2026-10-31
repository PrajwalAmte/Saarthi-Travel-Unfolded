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
    public final AtomicInteger osrmRequests = new AtomicInteger();
    public final AtomicInteger airQualityRequests = new AtomicInteger();
    /** Paths of every official-website page the checker read. */
    public final java.util.List<String> siteRequests = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Model names of every Gemini call received, in order. */
    public final java.util.List<String> geminiCalls = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Models whose free daily quota Google reports as used (429, per-day quota). */
    public final java.util.Set<String> dailySpentModels = ConcurrentHashMap.newKeySet();
    /** Models over their per-minute limit (429, per-minute quota). */
    public final java.util.Set<String> minuteLimitedModels = ConcurrentHashMap.newKeySet();
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
        // Open-Meteo air quality: two years of hourly PM2.5, smoggy (120) in Nov-Jan and clean (30) otherwise.
        server.createContext("/airquality", exchange -> {
            requests.incrementAndGet();
            airQualityRequests.incrementAndGet();
            Map<String, String> q = query(exchange.getRequestURI().getRawQuery());
            java.time.LocalDate day = java.time.LocalDate.parse(q.get("start_date"));
            java.time.LocalDate end = java.time.LocalDate.parse(q.get("end_date"));
            StringBuilder time = new StringBuilder();
            StringBuilder pm = new StringBuilder();
            for (; !day.isAfter(end); day = day.plusDays(1)) {
                int m = day.getMonthValue();
                for (int h = 0; h < 24; h++) {
                    if (time.length() > 0) {
                        time.append(',');
                        pm.append(',');
                    }
                    time.append('"').append(day).append(String.format("T%02d:00", h)).append('"');
                    pm.append(m == 11 || m == 12 || m == 1 ? "120.0" : "30.0");
                }
            }
            respond(exchange, "{\"hourly\":{\"time\":[" + time + "],\"pm2_5\":[" + pm + "]}}");
        });
        // Official websites for the checker: a hotel whose tariff is one link away, a palace stating its hours and fee,
        // and a section robots.txt forbids.
        server.createContext("/robots.txt", exchange -> respond(exchange, "User-agent: *\nDisallow: /site/private/\n"));
        server.createContext("/site/", exchange -> {
            requests.incrementAndGet();
            siteRequests.add(exchange.getRequestURI().getPath());
            String path = exchange.getRequestURI().getPath();
            String body = switch (path) {
                case "/site/hotel/" -> "<html><head><title>Palace Heritage Hotel</title></head><body><h1>Welcome</h1>"
                        + "<p>A heritage hotel in a former royal guest house.</p><a href=\"/site/hotel/tariff\">Tariff</a>"
                        + "<a href=\"https://elsewhere.example/\">Partner</a><script>var price='₹1';</script></body></html>";
                case "/site/hotel/tariff" -> "<html><head><title>Tariff</title></head><body>"
                        + "<p>Heritage rooms from ₹10,500 per night, breakfast included.</p></body></html>";
                case "/site/palace/" -> "<html><head><title>Testville Palace</title></head><body>"
                        + "<p>Open daily 10 am to 5:30 pm. Tickets ₹100 for adults.</p></body></html>";
                default -> null;
            };
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, b.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(b);
            }
        });
        // OurAirports export: a scheduled airport near Testville, an unscheduled airstrip nearer, and filler far away.
        server.createContext("/ourairports/airports.csv", exchange -> {
            requests.incrementAndGet();
            StringBuilder csv = new StringBuilder("\"id\",\"ident\",\"type\",\"name\",\"latitude_deg\",\"longitude_deg\",\"elevation_ft\","
                    + "\"continent\",\"iso_country\",\"iso_region\",\"municipality\",\"scheduled_service\",\"icao_code\",\"iata_code\"\n");
            csv.append("1,\"VOTV\",\"medium_airport\",\"Testville Airport, Mandakalli\",12.23,76.66,2000,\"AS\",\"IN\",\"IN-KA\",\"Testville\",\"yes\",\"VOTV\",\"TVX\"\n");
            csv.append("2,\"IN-0001\",\"small_airport\",\"Testville Flying Club\",12.31,76.65,2000,\"AS\",\"IN\",\"IN-KA\",\"Testville\",\"no\",\"\",\"\"\n");
            csv.append("3,\"VOXX\",\"closed\",\"Old Strip\",12.306,76.655,2000,\"AS\",\"IN\",\"IN-KA\",\"Testville\",\"no\",\"\",\"\"\n");
            csv.append("4,\"EGLL\",\"large_airport\",\"Heathrow\",51.47,-0.45,83,\"EU\",\"GB\",\"GB-ENG\",\"London\",\"yes\",\"EGLL\",\"LHR\"\n");
            for (int i = 0; i < 60; i++) {
                csv.append(100 + i).append(",\"IN-F").append(i).append("\",\"small_airport\",\"Filler ").append(i)
                        .append("\",28.").append(i).append(",77.0,700,\"AS\",\"IN\",\"IN-DL\",\"\",\"no\",\"\",\"\"\n");
            }
            respond(exchange, csv.toString());
        });
        // OSRM table service: road distance = 1.25 x straight line, at 50 km/h; a point at latitude 12.2 has no road.
        server.createContext("/osrm/table/v1/driving/", exchange -> {
            requests.incrementAndGet();
            osrmRequests.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            String[] pts = path.substring(path.lastIndexOf('/') + 1).split(";");
            double[] o = lonLat(pts[0]);
            StringBuilder dur = new StringBuilder("[0");
            StringBuilder dist = new StringBuilder("[0");
            StringBuilder dest = new StringBuilder("[{\"distance\":0}");
            for (int i = 1; i < pts.length; i++) {
                double[] d = lonLat(pts[i]);
                boolean noRoad = Math.abs(d[1] - 12.2) < 1e-6;
                double km = haversineKm(o[1], o[0], d[1], d[0]) * 1.25;
                dur.append(',').append(noRoad ? "null" : String.valueOf(km / 50 * 3600));
                dist.append(',').append(noRoad ? "null" : String.valueOf(km * 1000));
                dest.append(",{\"distance\":12.5}");
            }
            respond(exchange, "{\"code\":\"Ok\",\"durations\":[" + dur + "]],\"distances\":[" + dist + "]],"
                    + "\"sources\":[{\"distance\":3.2}],\"destinations\":" + dest + "]}");
        });
        server.createContext("/gemini/models/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String model = path.substring(path.lastIndexOf('/') + 1, path.indexOf(':', path.lastIndexOf('/')));
            geminiCalls.add(model);
            if (model.startsWith("gone-")) {
                // A model Google has retired for new keys.
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            if (dailySpentModels.contains(model) || minuteLimitedModels.contains(model)) {
                String quota = dailySpentModels.contains(model) ? "GenerateRequestsPerDayPerProjectPerModel-FreeTier"
                        : "GenerateRequestsPerMinutePerProjectPerModel-FreeTier";
                byte[] b = ("{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\",\"details\":[{\"violations\":[{\"quotaId\":\""
                        + quota + "\"}]}]}}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(429, b.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(b);
                }
                return;
            }
            if (busyModels.contains(model)) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            tools.jackson.databind.json.JsonMapper m = tools.jackson.databind.json.JsonMapper.builder().build();
            String req = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String prompt = m.readTree(req).path("contents").path(0).path("parts").path(0).path("text").asString();
            if (prompt.startsWith("Check this fact.")) {
                respondGemini(exchange, m, model, checkerAnswer(m, prompt));
                return;
            }
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
                    // "Unsure" in a title makes the reviewer less certain, so the flag waits for a person.
                    v.put("confidence", title.contains("Unsure") ? 0.75 : 0.95)
                            .put("reason", misfiled ? "The text describes a fort, not food." : "Fits.");
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

    /** The checker's answer: answer from the page when it states the value, else follow a "Tariff" link. */
    private static String checkerAnswer(tools.jackson.databind.json.JsonMapper m, String prompt) {
        boolean price = prompt.contains("\nFact: price");
        String recorded = prompt.substring(prompt.indexOf("Recorded value: ") + 16, prompt.indexOf('\n', prompt.indexOf("Recorded value: ")));
        String text = prompt.substring(prompt.indexOf("\nText:\n") + 7, prompt.indexOf("\n\nLinks:"));
        String[] links = prompt.substring(prompt.indexOf("\n\nLinks:\n") + 9).split("\n");
        var out = m.createObjectNode();
        java.util.regex.Matcher pm = java.util.regex.Pattern.compile("[^.]*₹([\\d,]+)[^.]*").matcher(text);
        java.util.regex.Matcher hm = java.util.regex.Pattern.compile("Open daily [^.]*").matcher(text);
        if (price && pm.find()) {
            boolean same = recorded.replace(",", "").contains(pm.group(1).replace(",", ""));
            out.put("action", "answer").put("verdict", same ? "confirmed" : "changed").put("quote", pm.group().trim())
                    .put("reason", "The page states the price.");
            if (!same) {
                out.put("value", "from ₹" + pm.group(1));
            }
        } else if (!price && hm.find()) {
            out.put("action", "answer").put("verdict", "confirmed").put("quote", hm.group()).put("reason", "The page states the hours.");
        } else {
            int tariff = -1;
            for (String l : links) {
                if (l.contains("Tariff")) {
                    tariff = Integer.parseInt(l.substring(0, l.indexOf('.')));
                }
            }
            if (tariff > 0) {
                out.put("action", "follow_link").put("link", tariff).put("verdict", "not_stated").put("reason", "The tariff page should say.");
            } else {
                out.put("action", "answer").put("verdict", "not_stated").put("reason", "Not on the page.");
            }
        }
        return m.writeValueAsString(out);
    }

    private static void respondGemini(com.sun.net.httpserver.HttpExchange exchange, tools.jackson.databind.json.JsonMapper m,
                                      String model, String text) throws IOException {
        var resp = m.createObjectNode();
        resp.putArray("candidates").addObject().putObject("content").putArray("parts").addObject().put("text", text);
        resp.putObject("usageMetadata").put("totalTokenCount", 100);
        resp.put("modelVersion", model);
        respond(exchange, m.writeValueAsString(resp));
    }

    private static double[] lonLat(String pair) {
        String[] p = pair.split(",");
        return new double[] {Double.parseDouble(p[0]), Double.parseDouble(p[1])};
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLon / 2), 2);
        return 6371 * 2 * Math.asin(Math.sqrt(a));
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
