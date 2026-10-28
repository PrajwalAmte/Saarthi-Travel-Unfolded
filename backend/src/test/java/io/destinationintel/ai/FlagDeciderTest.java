package io.destinationintel.ai;

import static io.destinationintel.ai.FlagDecider.Decision.ASK;
import static io.destinationintel.ai.FlagDecider.Decision.HIDE;
import static io.destinationintel.ai.FlagDecider.Decision.KEEP;
import static io.destinationintel.ai.FlagDecider.Decision.MOVE;
import static io.destinationintel.ai.FlagDecider.Decision.NOTE;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FlagDeciderTest {

    @Test
    void confidentWrongSectionIsMoved() {
        // "Little Italy" under stay: an Italian restaurant.
        assertThat(FlagDecider.decide("stay", false, "yes", List.of("wrong_section"), "eat", 1.0, null)).isEqualTo(MOVE);
        assertThat(FlagDecider.decide("drink", false, "unclear", List.of("wrong_section"), "eat", 0.8, null)).isEqualTo(MOVE);
    }

    @Test
    void doubtfulOnesWaitForAPerson() {
        assertThat(FlagDecider.decide("stay", false, "yes", List.of("wrong_section"), "eat", 0.75, null)).isEqualTo(ASK);
        assertThat(FlagDecider.decide("buy", false, "yes", List.of("wrong_section"), "none", 0.85, null))
                .as("hiding needs more certainty than moving").isEqualTo(ASK);
        assertThat(FlagDecider.decide("eat", false, "yes", List.of("wrong_section"), "eat", 0.95, null))
                .as("no better section named").isEqualTo(ASK);
    }

    @Test
    void notAPlaceToListClosedOrElsewhereIsHidden() {
        assertThat(FlagDecider.decide("buy", false, "yes", List.of("wrong_section"), "none", 1.0, null)).as("an ATM").isEqualTo(HIDE);
        assertThat(FlagDecider.decide("buy", true, "yes", List.of("closed"), "buy", 0.95, null)).isEqualTo(HIDE);
        assertThat(FlagDecider.decide("stay", true, "no", List.of("outside_destination"), "stay", 0.95, 120_000.0)).isEqualTo(HIDE);
    }

    @Test
    void itsOwnMapLocationOverridesTextThatSaysElsewhere() {
        // Banga Bhawan: in Delhi, booking office in Kolkata.
        assertThat(FlagDecider.decide("stay", true, "no", List.of("outside_destination"), "stay", 0.95, 2_000.0)).isEqualTo(KEEP);
    }

    @Test
    void textProblemsAreNotedNotActedOn() {
        assertThat(FlagDecider.decide("see", true, "yes", List.of("broken_text"), "see", 0.9, null)).isEqualTo(NOTE);
        assertThat(FlagDecider.decide("eat", true, "yes", List.of("about_another_business"), "eat", 0.8, null)).isEqualTo(NOTE);
    }
}
