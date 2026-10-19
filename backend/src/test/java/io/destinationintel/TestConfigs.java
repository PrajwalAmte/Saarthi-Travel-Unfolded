package io.destinationintel;

import io.destinationintel.config.AppProperties;
import java.time.Duration;
import java.util.List;

/** Configuration objects for plain unit tests (no Spring context). */
public final class TestConfigs {

    private TestConfigs() {}

    public static AppProperties.Ranking ranking() {
        return new AppProperties.Ranking(new AppProperties.Ranking.Weights(3.0, 1.5, 1.5, 2.0, 1.0, 0.75), 3, 25, 15);
    }

    public static AppProperties.Freshness freshness() {
        return new AppProperties.Freshness(1095, 365, 120);
    }

    public static AppProperties.Coverage coverage() {
        return new AppProperties.Coverage(List.of("eat", "stay", "explore", "getting_around"), 3, 3, 8, 0.3, 0.25, 0.6);
    }

    public static Duration second() {
        return Duration.ofSeconds(1);
    }
}
