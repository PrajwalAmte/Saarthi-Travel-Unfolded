package io.destinationintel.quality;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The outcome of evaluating one source (or one run of it). */
public record QualityResult(
        Map<String, Object> metrics,
        List<QualityCheck> checks,
        Map<String, Double> scoreComponents,
        List<Map<String, Object>> samples,
        List<String> limitations) {

    public String status() {
        boolean criticalFailed = checks.stream().anyMatch(c -> !c.passed() && QualityCheck.CRITICAL.equals(c.severity()));
        if (criticalFailed) {
            return "failed";
        }
        boolean warningFailed = checks.stream().anyMatch(c -> !c.passed() && QualityCheck.WARNING.equals(c.severity()));
        return warningFailed ? "warning" : "passed";
    }

    /**
     * Mean of the component percentages. It summarises the components shown next to it and is
     * not a measure of truth; components are never adjusted to reach a threshold.
     */
    public Double score() {
        if (scoreComponents.isEmpty()) {
            return null;
        }
        double mean = scoreComponents.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return Math.round(mean * 10.0) / 10.0;
    }

    public static Map<String, Object> newMetrics() {
        return new LinkedHashMap<>();
    }
}
