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
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);
    private static final int ATTEMPTS_PER_MODEL = 3;

    public record Answer(String json, String model, int tokens) {}

    /** The day's request budget is used up; try again after midnight UTC. */
    public static class BudgetExhausted extends RuntimeException {
        public BudgetExhausted(String message) {
            super(message);
        }
    }

    /** The model service failed in a way that retrying now will not fix. */
    public static class AiUnavailable extends RuntimeException {
        public AiUnavailable(String message) {
            super(message);
        }
    }

    private final AiProperties props;
    private final JdbcClient jdbc;
    private final JsonMapper mapper;
    private final HttpClient http;
    private long lastCallAt;

    public GeminiClient(AiProperties props, JdbcClient jdbc, JsonMapper mapper) {
        this.props = props;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    public boolean active() {
        return props.active();
    }

    public Answer generateJson(String system, String user, Map<String, Object> responseSchema) {
        if (!props.active()) {
            throw new AiUnavailable("AI review is not enabled on this server");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", system))));
        body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", user)))));
        body.put("generationConfig", Map.of("temperature", 0, "responseMimeType", "application/json",
                "responseSchema", responseSchema));
        String payload = mapper.writeValueAsString(body);

        List<String> models = new ArrayList<>(List.of(props.model()));
        if (props.fallbackModel() != null && !props.fallbackModel().equals(props.model())) {
            models.add(props.fallbackModel());
        }
        String lastError = "no attempt made";
        for (String model : models) {
            for (int attempt = 1; attempt <= ATTEMPTS_PER_MODEL; attempt++) {
                takeBudget();
                pace();
                try {
                    HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(props.geminiApiUrl() + "/models/" + model + ":generateContent"))
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
                        recordTokens(tokens);
                        if (text.isBlank()) {
                            lastError = model + " returned no content (" + root.path("candidates").path(0).path("finishReason").asString("unknown") + ")";
                            break;
                        }
                        return new Answer(text, root.path("modelVersion").asString(model), tokens);
                    }
                    lastError = model + " answered HTTP " + status;
                    if (status == 429 || status >= 500) {
                        long wait = props.retryBaseDelay().toMillis() * (long) Math.pow(3, attempt - 1);
                        log.warn("Gemini {} busy or rate-limited (HTTP {}), attempt {}/{}, waiting {} ms", model, status, attempt,
                                ATTEMPTS_PER_MODEL, wait);
                        sleep(wait);
                        continue;
                    }
                    // Bad request, bad key or unknown model: retrying the same request cannot help.
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
        throw new AiUnavailable(lastError);
    }

    /** Counts one request against today's budget, or refuses when it is spent. */
    private void takeBudget() {
        boolean ok = jdbc.sql("""
                        INSERT INTO ai_usage (day, requests) VALUES ((now() AT TIME ZONE 'UTC')::date, 1)
                        ON CONFLICT (day) DO UPDATE SET requests = ai_usage.requests + 1 WHERE ai_usage.requests < :budget
                        RETURNING true""")
                .param("budget", props.dailyRequestBudget()).query(Boolean.class).optional().orElse(false);
        if (!ok) {
            throw new BudgetExhausted("Today's AI request budget of " + props.dailyRequestBudget() + " is used up");
        }
    }

    private void recordTokens(int tokens) {
        jdbc.sql("UPDATE ai_usage SET tokens = tokens + :t WHERE day = (now() AT TIME ZONE 'UTC')::date")
                .param("t", tokens).update();
    }

    private synchronized void pace() {
        long wait = lastCallAt + props.minInterval().toMillis() - System.currentTimeMillis();
        if (wait > 0) {
            sleep(wait);
        }
        lastCallAt = System.currentTimeMillis();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
