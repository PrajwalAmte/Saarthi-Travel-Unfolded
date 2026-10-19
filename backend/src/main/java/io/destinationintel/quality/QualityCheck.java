package io.destinationintel.quality;

/**
 * One automated check. {@code severity} decides the gate: a failed critical check fails the
 * source; a failed warning check marks it as accepted with warnings; info checks only report.
 */
public record QualityCheck(
        String key,
        String dimension,
        String severity,
        String description,
        String comparator,
        Double threshold,
        Double actual,
        boolean passed,
        String detail) {

    public static final String CRITICAL = "critical";
    public static final String WARNING = "warning";
    public static final String INFO = "info";

    /** Passes when {@code actual >= threshold}. */
    public static QualityCheck atLeast(String key, String dimension, String severity, String description,
                                       double actual, double threshold, String detail) {
        return new QualityCheck(key, dimension, severity, description, ">=", threshold, round(actual), actual >= threshold, detail);
    }

    /** Passes when {@code actual <= threshold}. */
    public static QualityCheck atMost(String key, String dimension, String severity, String description,
                                      double actual, double threshold, String detail) {
        return new QualityCheck(key, dimension, severity, description, "<=", threshold, round(actual), actual <= threshold, detail);
    }

    public static QualityCheck info(String key, String dimension, String description, double actual, String detail) {
        return new QualityCheck(key, dimension, INFO, description, null, null, round(actual), true, detail);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
