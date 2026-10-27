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
        dailyRequestBudget = dailyRequestBudget <= 0 ? 200 : dailyRequestBudget;
        minInterval = minInterval == null ? Duration.ofSeconds(4) : minInterval;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(90) : requestTimeout;
        retryBaseDelay = retryBaseDelay == null ? Duration.ofSeconds(5) : retryBaseDelay;
        batchSize = batchSize <= 0 ? 12 : Math.min(batchSize, 50);
    }

    /** Usable only when switched on and a key is configured. */
    public boolean active() {
        return enabled && StringUtils.hasText(geminiApiKey);
    }
}
