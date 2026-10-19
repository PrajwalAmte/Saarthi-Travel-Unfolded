package io.destinationintel.guide;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * When a fact is replaced (by a source update or an accepted correction), pending "Still
 * true?" answers about the old value no longer apply: they are withdrawn and their review
 * items closed, rather than being counted for a value nobody confirmed.
 */
@Component
public class PendingVerifications {

    private final JdbcClient jdbc;

    public PendingVerifications(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int withdrawForReplacedFact(UUID factId) {
        return jdbc.sql("""
                        WITH withdrawn AS (
                            UPDATE verification SET status = 'withdrawn', decided_at = now(),
                                   decision_reason = 'The fact was replaced before this answer was reviewed'
                            WHERE fact_id = :f AND status = 'pending'
                            RETURNING id)
                        UPDATE moderation_item SET status = 'resolved', resolved_at = now(),
                               resolution = 'withdrawn: the fact was replaced'
                        WHERE item_type = 'verification' AND status = 'open' AND item_id IN (SELECT id FROM withdrawn)
                        """)
                .param("f", factId).update();
    }
}
