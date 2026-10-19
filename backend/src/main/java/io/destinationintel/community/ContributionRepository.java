package io.destinationintel.community;

import io.destinationintel.common.Json;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ContributionRepository {

    public record ContributionRow(
            UUID id,
            UUID userId,
            String type,
            UUID destinationId,
            UUID targetFactId,
            UUID targetEntryId,
            String payload,
            String note,
            String evidenceUrl,
            boolean conflictOfInterest,
            String coiDetails,
            boolean highImpact,
            String status,
            String decisionReason,
            UUID decidedBy,
            OffsetDateTime decidedAt,
            UUID resultEntryId,
            UUID resultFactId,
            OffsetDateTime createdAt) {}

    private static final String COLUMNS = """
            id, user_id, type, destination_id, target_fact_id, target_entry_id, payload::text AS payload, note,
            evidence_url, conflict_of_interest, coi_details, high_impact, status, decision_reason, decided_by,
            decided_at, result_entry_id, result_fact_id, created_at""";

    private final JdbcClient jdbc;
    private final Json json;

    public ContributionRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public UUID insert(UUID userId, String type, UUID destinationId, UUID targetFactId, UUID targetEntryId,
                       Map<String, Object> payload, String note, String evidenceUrl, boolean coi, String coiDetails,
                       boolean highImpact) {
        return jdbc.sql("""
                        INSERT INTO contribution (user_id, type, destination_id, target_fact_id, target_entry_id, payload,
                                                  note, evidence_url, conflict_of_interest, coi_details, high_impact, status)
                        VALUES (:u, :type, :d, :f, :e, CAST(:payload AS jsonb), :note, :url, :coi, :coiDetails, :high, 'pending')
                        RETURNING id
                        """)
                .param("u", userId).param("type", type).param("d", destinationId).param("f", targetFactId)
                .param("e", targetEntryId).param("payload", json.write(payload)).param("note", note)
                .param("url", evidenceUrl).param("coi", coi).param("coiDetails", coiDetails).param("high", highImpact)
                .query(UUID.class).single();
    }

    public Optional<ContributionRow> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM contribution WHERE id = :id").param("id", id)
                .query(ContributionRow.class).optional();
    }

    public Optional<ContributionRow> findForUpdate(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM contribution WHERE id = :id FOR UPDATE").param("id", id)
                .query(ContributionRow.class).optional();
    }

    public void decide(UUID id, String status, String reason, UUID decidedBy, UUID resultEntryId, UUID resultFactId) {
        jdbc.sql("""
                        UPDATE contribution SET status = :status, decision_reason = :reason, decided_by = :by,
                               decided_at = now(), result_entry_id = coalesce(:entry, result_entry_id),
                               result_fact_id = coalesce(:fact, result_fact_id), updated_at = now()
                        WHERE id = :id
                        """)
                .param("status", status).param("reason", reason).param("by", decidedBy).param("entry", resultEntryId)
                .param("fact", resultFactId).param("id", id).update();
    }

    public int countSince(UUID userId, OffsetDateTime since) {
        return jdbc.sql("SELECT count(*) FROM contribution WHERE user_id = :u AND created_at > :since")
                .param("u", userId).param("since", since).query(Integer.class).single();
    }

    public boolean hasPendingDuplicate(UUID userId, String type, UUID factId, UUID entryId, UUID destinationId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM contribution WHERE user_id = :u AND type = :t AND status = 'pending'
                          AND destination_id = :d
                          AND coalesce(target_fact_id, '00000000-0000-0000-0000-000000000000') = coalesce(CAST(:f AS uuid), '00000000-0000-0000-0000-000000000000')
                          AND coalesce(target_entry_id, '00000000-0000-0000-0000-000000000000') = coalesce(CAST(:e AS uuid), '00000000-0000-0000-0000-000000000000'))
                        """)
                .param("u", userId).param("t", type).param("d", destinationId).param("f", factId).param("e", entryId)
                .query(Boolean.class).single();
    }

    public record HistoryRow(UUID id, String type, String status, UUID destinationId, String destinationSlug,
                             String destinationName, String targetTitle, String payload, String decisionReason,
                             OffsetDateTime createdAt, OffsetDateTime decidedAt) {}

    public List<HistoryRow> history(UUID userId, int limit, int offset) {
        return jdbc.sql("""
                        SELECT c.id, c.type, c.status, c.destination_id, p.slug AS destination_slug, p.name AS destination_name,
                               coalesce(e.title, fe.title) AS target_title, c.payload::text AS payload, c.decision_reason,
                               c.created_at, c.decided_at
                        FROM contribution c
                        JOIN place p ON p.id = c.destination_id
                        LEFT JOIN guide_entry e ON e.id = c.target_entry_id
                        LEFT JOIN fact f ON f.id = c.target_fact_id
                        LEFT JOIN guide_entry fe ON fe.id = f.entry_id
                        WHERE c.user_id = :u
                        ORDER BY c.created_at DESC LIMIT :limit OFFSET :offset
                        """)
                .param("u", userId).param("limit", limit).param("offset", offset).query(HistoryRow.class).list();
    }

    public long countForUser(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM contribution WHERE user_id = :u").param("u", userId).query(Long.class).single();
    }

    public Map<String, Long> statusCounts(UUID userId) {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        jdbc.sql("SELECT status, count(*) AS n FROM contribution WHERE user_id = :u GROUP BY status ORDER BY status")
                .param("u", userId)
                .query((rs, i) -> Map.entry(rs.getString("status"), rs.getLong("n")))
                .list().forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }
}
