package io.destinationintel.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QualityChecksTest {

    @Test
    void anyFailedCriticalCheckFailsTheReportAndWarningsOnlyWarn() {
        QualityCheck okCritical = QualityCheck.atLeast("a", "completeness", QualityCheck.CRITICAL, "a", 99, 97, null);
        QualityCheck badWarning = QualityCheck.atMost("b", "duplicates", QualityCheck.WARNING, "b", 3, 1, null);
        QualityCheck badCritical = QualityCheck.atMost("c", "geographic", QualityCheck.CRITICAL, "c", 1, 0, null);
        QualityCheck info = QualityCheck.info("d", "schema", "d", 12345, null);

        assertThat(new QualityResult(Map.of(), List.of(okCritical, info), Map.of(), List.of(), List.of()).status()).isEqualTo("passed");
        assertThat(new QualityResult(Map.of(), List.of(okCritical, badWarning), Map.of(), List.of(), List.of()).status()).isEqualTo("warning");
        assertThat(new QualityResult(Map.of(), List.of(okCritical, badWarning, badCritical), Map.of(), List.of(), List.of()).status())
                .isEqualTo("failed");
    }

    @Test
    void scoreIsTheMeanOfItsPublishedComponents() {
        QualityResult r = new QualityResult(Map.of(), List.of(), Map.of("validity", 99.0, "uniqueness", 97.0), List.of(), List.of());
        assertThat(r.score()).isEqualTo(98.0);
        assertThat(new QualityResult(Map.of(), List.of(), Map.of(), List.of(), List.of()).score()).isNull();
    }

    @Test
    void separatesOutOfScopeSkipsFromValidationFailures() {
        Map<String, Long> reasons = PoiQualityEvaluator.rejectionReasons(
                "{\"missing_name\": 43681, \"generic_name\": 841, \"outside_india\": 1, \"unmapped_category\": 7}");
        long outOfScope = PoiQualityEvaluator.OUT_OF_SCOPE.stream().mapToLong(k -> reasons.getOrDefault(k, 0L)).sum();
        assertThat(outOfScope).isEqualTo(43688);
        assertThat(reasons.get("generic_name")).isEqualTo(841);
        assertThat(PoiQualityEvaluator.rejectionReasons(null)).isEmpty();
    }
}
