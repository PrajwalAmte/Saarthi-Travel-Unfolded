package io.destinationintel.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/** Optional AI review of ingested listings (Gemini). Off unless enabled and a key is set. */
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(
        boolean enabled,
        String geminiApiKey,
        String geminiApiUrl,
        String model,
        String fallbackModel,
        String models,
        int dailyRequestBudget,
        Duration minInterval,
        Duration requestTimeout,
        Duration retryBaseDelay,
        int batchSize) {

    public AiProperties {
        geminiApiUrl = StringUtils.hasText(geminiApiUrl) ? geminiApiUrl.replaceAll("/+$", "")
                : "https://generativelanguage.googleapis.com/v1beta";
        model = StringUtils.hasText(model) ? model.trim() : "gemini-flash-lite-latest";
        fallbackModel = StringUtils.hasText(fallbackModel) ? fallbackModel.trim() : null;
        dailyRequestBudget = dailyRequestBudget <= 0 ? 1000 : dailyRequestBudget;
        minInterval = minInterval == null ? Duration.ofSeconds(4) : minInterval;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(90) : requestTimeout;
        retryBaseDelay = retryBaseDelay == null ? Duration.ofSeconds(5) : retryBaseDelay;
        batchSize = batchSize <= 0 ? 20 : Math.min(batchSize, 50);
    }

    /** One model the client may use, with the free tier's own limits for it. */
    public record ModelSpec(String name, int requestsPerDay, int requestsPerMinute) {}

    /**
     * The models to use, in order of preference for bulk work: "name:requestsPerDay:requestsPerMinute,...". Each is used
     * up to its own daily limit, so several free models together go further than one. Falls back to model and
     * fallback-model when the list is empty.
     */
    public java.util.List<ModelSpec> pool() {
        java.util.List<ModelSpec> out = new java.util.ArrayList<>();
        if (StringUtils.hasText(models)) {
            for (String part : models.split(",")) {
                String[] f = part.trim().split(":");
                if (f.length == 0 || f[0].isBlank()) {
                    continue;
                }
                int rpd = f.length > 1 ? parse(f[1], 20) : 20;
                int rpm = f.length > 2 ? parse(f[2], 5) : 5;
                if (rpd > 0 && rpm > 0 && out.stream().noneMatch(m -> m.name().equals(f[0].trim()))) {
                    out.add(new ModelSpec(f[0].trim(), rpd, rpm));
                }
            }
        }
        if (out.isEmpty()) {
            out.add(new ModelSpec(model, dailyRequestBudget, 14));
            if (fallbackModel != null && !fallbackModel.equals(model)) {
                out.add(new ModelSpec(fallbackModel, 18, 5));
            }
        }
        return java.util.List.copyOf(out);
    }

    private static int parse(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Usable only when switched on and a key is configured. */
    public boolean active() {
        return enabled && StringUtils.hasText(geminiApiKey);
    }
}
