package io.destinationintel.moderation;

import io.destinationintel.common.Json;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes to the moderation queue, always inside the transaction that created the item. */
@Component
public class ModerationQueue {

    private final JdbcClient jdbc;
    private final Json json;

    public ModerationQueue(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID open(String itemType, UUID itemId, UUID destinationId, List<String> reasons, String requiresRole, int priority) {
        return jdbc.sql("""
                        INSERT INTO moderation_item (item_type, item_id, destination_id, reasons, requires_role, priority)
                        VALUES (:type, :item, :dest, CAST(:reasons AS jsonb), :role, :priority)
                        ON CONFLICT (item_type, item_id) DO UPDATE SET status = 'open', resolved_at = NULL, resolution = NULL
                        RETURNING id
                        """)
                .param("type", itemType).param("item", itemId).param("dest", destinationId)
                .param("reasons", json.write(reasons)).param("role", requiresRole).param("priority", priority)
                .query(UUID.class).single();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void resolve(String itemType, UUID itemId, String resolution, UUID resolvedBy) {
        jdbc.sql("""
                        UPDATE moderation_item SET status = 'resolved', resolution = :r, resolved_by = :by, resolved_at = now()
                        WHERE item_type = :type AND item_id = :item AND status = 'open'
                        """)
                .param("r", resolution).param("by", resolvedBy).param("type", itemType).param("item", itemId).update();
    }
}
