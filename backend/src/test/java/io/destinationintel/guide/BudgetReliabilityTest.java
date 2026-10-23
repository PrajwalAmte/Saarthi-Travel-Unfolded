package io.destinationintel.guide;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BudgetReliabilityTest {

    @Test
    void fewPricesAreNeverPresentedAsAnEstimate() {
        assertThat(BudgetService.reliability(1, 0)).isEqualTo("too_few");
        assertThat(BudgetService.reliability(2, 2)).isEqualTo("too_few");
    }

    @Test
    void oldPricesAreFlaggedByHowManyAreOld() {
        assertThat(BudgetService.reliability(9, 9)).isEqualTo("all_stale");
        assertThat(BudgetService.reliability(41, 39)).isEqualTo("mostly_stale");
        assertThat(BudgetService.reliability(10, 5)).isEqualTo("mostly_stale");
        assertThat(BudgetService.reliability(10, 4)).isEqualTo("ok");
        assertThat(BudgetService.reliability(5, 0)).isEqualTo("ok");
    }
}
