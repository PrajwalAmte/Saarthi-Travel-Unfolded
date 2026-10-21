package io.destinationintel.request;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GuideEstimatorTest {

    @Test
    void shortWaitsAreGivenInMinutes() {
        assertThat(GuideEstimator.range(1, 2)).isEqualTo("1–2 minutes");
        assertThat(GuideEstimator.range(12, 25)).isEqualTo("12–25 minutes");
    }

    @Test
    void longWaitsAreGivenInHalfHours() {
        assertThat(GuideEstimator.range(60, 120)).isEqualTo("1–2 hours");
        assertThat(GuideEstimator.range(100, 200)).isEqualTo("1.5–3.5 hours");
        assertThat(GuideEstimator.range(2000, 4000)).isEqualTo("more than a day");
    }

    @Test
    void describeWidensTheRangeAndNeverPromisesZero() {
        GuideEstimator.Estimate e = GuideEstimator.describe(60, 0, true, new GuideEstimator.Typical(90, 10));
        assertThat(e.minMinutes()).isEqualTo(1);
        assertThat(e.maxMinutes()).isGreaterThan(e.minMinutes());
        assertThat(e.summary()).startsWith("About ");
        assertThat(e.basis()).contains("median of 10 imports");
    }

    @Test
    void withoutEnoughHistoryTheBasisSaysSo() {
        GuideEstimator.Estimate e = GuideEstimator.describe(600, 4, false, new GuideEstimator.Typical(90, 2));
        assertThat(e.basis()).contains("4 imports are ahead").contains("Too few guides");
        assertThat(e.summary()).contains("not running");
    }
}
