package io.destinationintel.ingestion.wikidata;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EntityInfoTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static String statement(String qid, String rank, String qualifier) {
        return """
                {"rank":"%s","mainsnak":{"datavalue":{"value":{"id":"%s"}}}%s}""".formatted(rank, qid,
                qualifier == null ? "" : ",\"qualifiers\":{\"" + qualifier + "\":[{}]}");
    }

    @Test
    void locatedInSkipsStatementsThatHaveEnded() {
        // Udupi: in Udupi district since 1997, in Dakshina Kannada until 1997 (both normal rank).
        var claims = JSON.readTree("[" + statement("Q1483337", "normal", "P580") + "," + statement("Q950571", "normal", "P582") + "]");
        assertThat(EntityInfo.currentItemValues(claims)).containsExactly("Q1483337");
    }

    @Test
    void preferredStatementsComeFirstAndEndedOnesAreAFallback() {
        var claims = JSON.readTree("[" + statement("Q2", "normal", null) + "," + statement("Q1", "preferred", null) + ","
                + statement("Q3", "deprecated", null) + "]");
        assertThat(EntityInfo.currentItemValues(claims)).containsExactly("Q1", "Q2");
        var allEnded = JSON.readTree("[" + statement("Q9", "normal", "P582") + "]");
        assertThat(EntityInfo.currentItemValues(allEnded)).containsExactly("Q9");
    }

    @Test
    void headquartersIsThePreferredCurrentStatement() {
        var claims = JSON.readTree("[" + statement("Q1", "normal", "P582") + "," + statement("Q2", "normal", null) + ","
                + statement("Q3", "preferred", null) + "]");
        assertThat(IndexConsistency.preferredItem(claims)).isEqualTo("Q3");
        var onlyEnded = JSON.readTree("[" + statement("Q1", "normal", "P582") + "]");
        assertThat(IndexConsistency.preferredItem(onlyEnded)).isNull();
    }
}
