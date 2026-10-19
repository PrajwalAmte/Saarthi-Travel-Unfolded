package io.destinationintel.audit;

import io.destinationintel.common.Json;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Append-only audit log. Writes join the caller's transaction so an audit record exists
 * if and only if the audited change was committed.
 */
@Service
public class AuditService {

    public record AuditEntry(long id, UUID actorUserId, String actorType, String actorName, String action,
                             String entityType, String entityId, UUID destinationId, String details,
                             OffsetDateTime createdAt) {}

    private final JdbcClient jdbc;
    private final Json json;

    public AuditService(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID actorUserId, String action, String entityType, Object entityId, UUID destinationId,
                       Map<String, ?> details) {
        jdbc.sql("""
                        INSERT INTO audit_log (actor_user_id, actor_type, action, entity_type, entity_id, destination_id, details)
                        VALUES (:actor, :actorType, :action, :entityType, :entityId, :destinationId, CAST(:details AS jsonb))
                        """)
                .param("actor", actorUserId)
                .param("actorType", actorUserId == null ? "system" : "user")
                .param("action", action)
                .param("entityType", entityType)
                .param("entityId", String.valueOf(entityId))
                .param("destinationId", destinationId)
                .param("details", json.write(details == null ? Map.of() : details))
                .update();
    }

    public List<AuditEntry> forEntity(String entityType, String entityId, int limit) {
        return jdbc.sql("""
                        SELECT a.id, a.actor_user_id, a.actor_type,
                               CASE WHEN u.status = 'deleted' THEN 'Former contributor' ELSE u.display_name END AS actor_name,
                               a.action, a.entity_type, a.entity_id, a.destination_id, a.details::text AS details, a.created_at
                        FROM audit_log a LEFT JOIN app_user u ON u.id = a.actor_user_id
                        WHERE a.entity_type = :type AND a.entity_id = :id
                        ORDER BY a.created_at DESC, a.id DESC LIMIT :limit
                        """)
                .param("type", entityType).param("id", entityId).param("limit", limit)
                .query(AuditEntry.class).list();
    }

    public List<AuditEntry> recent(UUID destinationId, int limit, int offset) {
        return jdbc.sql("""
                        SELECT a.id, a.actor_user_id, a.actor_type,
                               CASE WHEN u.status = 'deleted' THEN 'Former contributor' ELSE u.display_name END AS actor_name,
                               a.action, a.entity_type, a.entity_id, a.destination_id, a.details::text AS details, a.created_at
                        FROM audit_log a LEFT JOIN app_user u ON u.id = a.actor_user_id
                        WHERE (CAST(:dest AS uuid) IS NULL OR a.destination_id = :dest)
                        ORDER BY a.created_at DESC, a.id DESC LIMIT :limit OFFSET :offset
                        """)
                .param("dest", destinationId).param("limit", limit).param("offset", offset)
                .query(AuditEntry.class).list();
    }
}
