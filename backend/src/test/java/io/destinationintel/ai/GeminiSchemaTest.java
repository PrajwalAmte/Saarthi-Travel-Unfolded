package io.destinationintel.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class GeminiSchemaTest {

    @Test
    @SuppressWarnings("unchecked")
    void standardJsonSchemaIsTranslatedToGeminisDialect() {
        Map<String, Object> g = GeminiClient.toGeminiSchema(ListingReviewHandler.SCHEMA);
        assertThat(g.get("type")).isEqualTo("ARRAY");
        Map<String, Object> item = (Map<String, Object>) g.get("items");
        assertThat(item.get("type")).isEqualTo("OBJECT");
        Map<String, Object> props = (Map<String, Object>) item.get("properties");
        assertThat(((Map<String, Object>) props.get("confidence")).get("type")).isEqualTo("NUMBER");
        Map<String, Object> tags = (Map<String, Object>) props.get("tags");
        assertThat(tags.get("type")).isEqualTo("ARRAY");
        assertThat(((Map<String, Object>) tags.get("items")).get("type")).isEqualTo("OBJECT");
        assertThat(((Map<String, Object>) props.get("inDestination")).get("enum")).as("enums are kept").isNotNull();
    }

    @Test
    void theSchemasCallersWriteAreProviderNeutral() {
        assertThat(ListingReviewHandler.SCHEMA.get("type")).isEqualTo("array");
        assertThat(OverviewWriter.SCHEMA.get("type")).isEqualTo("array");
        assertThat(GeminiClient.toGeminiSchema(Map.of("type", "object", "additionalProperties", false)))
                .as("keywords outside Gemini's subset are dropped").doesNotContainKey("additionalProperties");
    }
}
