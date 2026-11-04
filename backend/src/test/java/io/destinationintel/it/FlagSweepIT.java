package io.destinationintel.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.destinationintel.ai.FlagDecider;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Flags stored at exactly the threshold are decided, and self-contradicting ones leave the listing alone. */
class FlagSweepIT extends IntegrationTest {

    @Autowired FlagDecider decider;

    private UUID flag(UUID destination, String title, String[] issues, boolean fits, String inDestination, String suggested, double confidence) {
        UUID entry = jdbc.sql("SELECT id FROM guide_entry WHERE title = :t").param("t", title).query(UUID.class).single();
        return jdbc.sql("""
                        INSERT INTO ai_review (entry_id, destination_id, input_hash, prompt_version, model, fits_section, in_destination,
                                               issues, suggested_section, confidence, reason, flagged, status)
                        VALUES (:e, :d, 'h', 1, 'm', :fits, :inDest, :issues, :suggested, :confidence, 'Test reason.', true, 'open')
                        RETURNING id""")
                .param("e", entry).param("d", destination).param("fits", fits).param("inDest", inDestination).param("issues", issues)
                .param("suggested", suggested).param("confidence", confidence).query(UUID.class).single();
    }

    private String entryStatus(String title) {
        return jdbc.sql("SELECT status FROM guide_entry WHERE title = :t").param("t", title).query(String.class).single();
    }

    private String resolution(UUID flag) {
        return jdbc.sql("SELECT resolution FROM ai_review WHERE id = :f").param("f", flag).query(String.class).single();
    }

    @Test
    void ninetyPercentCountsAsNinetyAndContradictionsAreKept() {
        UUID testville = seedTestvillePlace();
        ingestGuide(testville);
        // A "real" column stores 0.9 as 0.89999998, which once kept these flags open for ever.
        UUID elsewhere = flag(testville, "Palace Heritage Hotel", new String[] {"outside_destination"}, true, "no", "stay", 0.9);
        UUID contradiction = flag(testville, "Backpacker Inn", new String[] {"wrong_section"}, false, "yes", "stay", 0.9);

        assertThat(decider.sweep()).isEqualTo(2);
        assertThat(resolution(elsewhere)).isEqualTo("hidden");
        assertThat(entryStatus("Palace Heritage Hotel")).as("hidden, and can be undone").isNotEqualTo("published");
        assertThat(resolution(contradiction)).isEqualTo("kept");
        assertThat(entryStatus("Backpacker Inn")).isEqualTo("published");
    }
}
