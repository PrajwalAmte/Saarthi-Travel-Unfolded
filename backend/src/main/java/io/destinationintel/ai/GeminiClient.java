package io.destinationintel.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Minimal Gemini client for structured (JSON) answers. Every request is counted against a daily budget before it is
 * sent, requests are spaced out, and a busy or rate-limited model is retried and then swapped for the fallback model.
 */
@Component
public class GeminiClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);
    private static final int ATTEMPTS_PER_MODEL = 3;




    private final AiProperties props;
    private final JdbcClient jdbc;
    private final JsonMapper mapper;
    private final HttpClient http;

    public GeminiClient(AiProperties props, JdbcClient jdbc, JsonMapper mapper) {
        this.props = props;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    @Override
    public boolean active() {
        return props.active();
    }


    public Answer generateJson(String system, String user, Map<String, Object> jsonSchema) {
        return generateJson(system, user, jsonSchema, Purpose.BULK);
    }

    @Override
    public Answer generateJson(String system, String user, Map<String, Object> jsonSchema, Purpose purpose) {
        Map<String, Object> responseSchema = toGeminiSchema(jsonSchema);
        if (!props.active()) {
            throw new AiUnavailable("AI review is not enabled on this server");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", system))));
        body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", user)))));
        body.put("generationConfig", Map.of("temperature", 0, "responseMimeType", "application/json",
                "responseSchema", responseSchema));
        String payload = mapper.writeValueAsString(body);

        List<AiProperties.ModelSpec> models = new ArrayList<>(props.pool());
        if (purpose == Purpose.QUALITY) {
            // The scarce, stronger models first; the high-volume one only when they are busy or used up.
            models.sort(java.util.Comparator.comparingInt(AiProperties.ModelSpec::requestsPerDay));
        }
        String lastError = "no model available";
        boolean anyBudget = false;
        for (AiProperties.ModelSpec m : models) {
            if (retired.contains(m.name())) {
                continue;
            }
            for (int attempt = 1; attempt <= ATTEMPTS_PER_MODEL; attempt++) {
                if (!takeBudget(m)) {
                    lastError = m.name() + " has used today's " + m.requestsPerDay() + " requests";
                    break;
                }
                anyBudget = true;
                pace(m);
                try {
                    HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(props.geminiApiUrl() + "/models/" + m.name() + ":generateContent"))
                                    .timeout(props.requestTimeout())
                                    .header("Content-Type", "application/json")
                                    .header("x-goog-api-key", props.geminiApiKey())
                                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    int status = r.statusCode();
                    if (status == 200) {
                        JsonNode root = mapper.readTree(r.body());
                        String text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asString("");
                        int tokens = root.path("usageMetadata").path("totalTokenCount").asInt(0);
                        recordTokens(m.name(), tokens);
                        if (text.isBlank()) {
                            lastError = m.name() + " returned no content (" + root.path("candidates").path(0).path("finishReason").asString("unknown") + ")";
                            break;
                        }
                        return new Answer(text, root.path("modelVersion").asString(m.name()), tokens);
                    }
                    lastError = m.name() + " answered HTTP " + status;
                    if (status == 404) {
                        // Google retires models ("no longer available to new users"); stop asking this one.
                        log.warn("Gemini model {} is not available to this key; skipping it from now on", m.name());
                        retired.add(m.name());
                        break;
                    }
                    if (status == 429 || status >= 500) {
                        if (status == 429 && attempt == 1) {
                            // Rate-limited: another model is a better bet than waiting on this one.
                            break;
                        }
                        long wait = props.retryBaseDelay().toMillis() * (long) Math.pow(3, attempt - 1);
                        log.warn("Gemini {} busy (HTTP {}), attempt {}/{}, waiting {} ms", m.name(), status, attempt, ATTEMPTS_PER_MODEL, wait);
                        sleep(wait);
                        continue;
                    }
                    throw new AiUnavailable(lastError);
                } catch (IOException e) {
                    lastError = "network error contacting Gemini: " + e.getClass().getSimpleName();
                    sleep(props.retryBaseDelay().toMillis() * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AiUnavailable("interrupted");
                }
            }
        }
        if (!anyBudget || everyModelSpent()) {
            throw new BudgetExhausted("Today's free AI requests are used up on every model");
        }
        throw new AiUnavailable(lastError);
    }

    private final java.util.Set<String> retired = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastCalls = new java.util.concurrent.ConcurrentHashMap<>();

    /** Google's quota day starts at midnight Pacific time; budgets follow it so they match AI Studio. */
    static final String QUOTA_DAY = "(now() AT TIME ZONE 'America/Los_Angeles')::date";

    /** Counts one request against this model's daily limit and the overall cap, or refuses. */
    private boolean takeBudget(AiProperties.ModelSpec m) {
        long total = jdbc.sql("SELECT coalesce(sum(requests), 0) FROM ai_usage WHERE day = " + QUOTA_DAY)
                .query(Long.class).single();
        if (total >= props.dailyRequestBudget()) {
            return false;
        }
        return jdbc.sql("""
                        INSERT INTO ai_usage (day, model, requests) VALUES (""" + QUOTA_DAY + """
                        , :m, 1)
                        ON CONFLICT (day, model) DO UPDATE SET requests = ai_usage.requests + 1 WHERE ai_usage.requests < :rpd
                        RETURNING true""")
                .param("m", m.name()).param("rpd", m.requestsPerDay()).query(Boolean.class).optional().orElse(false);
    }

    private boolean everyModelSpent() {
        for (AiProperties.ModelSpec m : props.pool()) {
            if (retired.contains(m.name())) {
                continue;
            }
            long used = jdbc.sql("SELECT coalesce(max(requests), 0) FROM ai_usage WHERE day = " + QUOTA_DAY + " AND model = :m")
                    .param("m", m.name()).query(Long.class).single();
            if (used < m.requestsPerDay()) {
                return false;
            }
        }
        return true;
    }

    private void recordTokens(String model, int tokens) {
        jdbc.sql("UPDATE ai_usage SET tokens = tokens + :t WHERE day = " + QUOTA_DAY + " AND model = :m")
                .param("t", tokens).param("m", model).update();
    }

    /** Spaces requests to each model just under its per-minute limit (and never closer than min-interval). */
    private void pace(AiProperties.ModelSpec m) {
        long interval = Math.max(props.minInterval().toMillis(), 60_000L / m.requestsPerMinute() + 250);
        synchronized (lastCalls) {
            long wait = lastCalls.getOrDefault(m.name(), 0L) + interval - System.currentTimeMillis();
            if (wait > 0) {
                sleep(wait);
            }
            lastCalls.put(m.name(), System.currentTimeMillis());
        }
    }

    @Override
    public java.time.Duration untilQuotaReset() {
        return untilPacificMidnight();
    }

    /**
     * Gemini's response schema is an OpenAPI subset with upper-case type names ("ARRAY", "STRING"). Callers write
     * standard JSON Schema; this translates it, so no other code knows Gemini's dialect.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> toGeminiSchema(Map<String, Object> schema) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : schema.entrySet()) {
            Object v = e.getValue();
            if (e.getKey().equals("additionalProperties")) {
                continue; // not part of Gemini's schema subset
            }
            if (e.getKey().equals("type") && v instanceof String t) {
                out.put("type", t.toUpperCase(java.util.Locale.ROOT));
            } else if (v instanceof Map<?, ?> m) {
                out.put(e.getKey(), toGeminiSchema((Map<String, Object>) m));
            } else {
                out.put(e.getKey(), v);
            }
        }
        if (out.get("properties") instanceof Map<?, ?> props) {
            Map<String, Object> converted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> p : props.entrySet()) {
                converted.put(String.valueOf(p.getKey()), p.getValue() instanceof Map<?, ?> pm ? toGeminiSchema((Map<String, Object>) pm) : p.getValue());
            }
            out.put("properties", converted);
        }
        return out;
    }

    /** Time until Google's next quota day, plus a few minutes. */
    static java.time.Duration untilPacificMidnight() {
        java.time.ZoneId pacific = java.time.ZoneId.of("America/Los_Angeles");
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(pacific);
        java.time.ZonedDateTime next = now.toLocalDate().plusDays(1).atStartOfDay(pacific).plusMinutes(5);
        return java.time.Duration.between(now, next);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
