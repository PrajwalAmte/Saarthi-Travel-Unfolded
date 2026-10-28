package io.destinationintel.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AiPropertiesTest {

    private static AiProperties props(String models, String model, String fallback) {
        return new AiProperties(true, "k", null, model, fallback, models, 1000, null, null, null, 20);
    }

    @Test
    void readsEachModelsOwnLimits() {
        var pool = props("gemini-flash-lite-latest:480:14, gemini-3.8-flash:18:4,gemini-3-flash-preview", null, null).pool();
        assertThat(pool).extracting(AiProperties.ModelSpec::name)
                .containsExactly("gemini-flash-lite-latest", "gemini-3.8-flash", "gemini-3-flash-preview");
        assertThat(pool.get(0).requestsPerDay()).isEqualTo(480);
        assertThat(pool.get(1).requestsPerMinute()).isEqualTo(4);
        assertThat(pool.get(2).requestsPerDay()).as("unspecified limits are cautious").isEqualTo(20);
    }

    @Test
    void ignoresDuplicatesAndBadEntries() {
        var pool = props("a:10:2,a:99:9,:5:5,b:0:3,c:x:y", null, null).pool();
        assertThat(pool).extracting(AiProperties.ModelSpec::name).containsExactly("a", "c");
        assertThat(pool.get(0).requestsPerDay()).isEqualTo(10);
    }

    @Test
    void fallsBackToTheSingleModelSettings() {
        assertThat(props("", "gemini-flash-lite-latest", null).pool()).extracting(AiProperties.ModelSpec::name)
                .containsExactly("gemini-flash-lite-latest");
    }
}
