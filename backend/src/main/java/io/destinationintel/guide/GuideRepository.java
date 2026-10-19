package io.destinationintel.guide;

import io.destinationintel.guide.GuideRows.EntryRow;
import io.destinationintel.guide.GuideRows.EvidenceRow;
import io.destinationintel.guide.GuideRows.FactRow;
import io.destinationintel.guide.GuideRows.ObservationRow;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read queries for guide content. Verification counts are always aggregated from records. */
@Repository
public class GuideRepository {

    private static final String FACT_SELECT = """
            SELECT f.id, f.destination_id, f.entry_id, f.section, f.kind, f.heading, f.value_text,
                   f.value_json::text AS value_json, f.volatility, f.status, f.origin, f.observed_at, f.observed_basis,
                   f.source_refreshed_at, f.last_verified_at, f.contribution_id, f.created_at,
                   coalesce(v.confirms, 0) AS accepted_confirmations,
                   coalesce(v.denials, 0) AS accepted_denials,
                   v.last_confirm AS last_confirmation_at,
                   v.last_deny AS last_denial_at,
                   coalesce(pc.pending, 0) AS pending_corrections,
                   coalesce(src.conflict_of_interest, false) AS owner_submitted
            FROM fact f
            LEFT JOIN (
                SELECT fact_id,
                       count(*) FILTER (WHERE verdict = 'confirm') AS confirms,
                       count(*) FILTER (WHERE verdict = 'deny') AS denials,
                       max(created_at) FILTER (WHERE verdict = 'confirm') AS last_confirm,
                       max(created_at) FILTER (WHERE verdict = 'deny') AS last_deny
                FROM verification WHERE status = 'accepted' GROUP BY fact_id) v ON v.fact_id = f.id
            LEFT JOIN (
                SELECT target_fact_id, count(*) AS pending FROM contribution
                WHERE status = 'pending' AND target_fact_id IS NOT NULL GROUP BY target_fact_id) pc ON pc.target_fact_id = f.id
            LEFT JOIN contribution src ON src.id = f.contribution_id
            """;

    private static final String EVIDENCE_SELECT = """
            SELECT e.id, e.fact_id, e.source_id, s.name AS source_name, s.category AS source_category,
                   s.licence_name, s.licence_url, e.url, e.permalink, e.title, e.excerpt, e.extraction_method,
                   e.source_revision, e.source_revision_at, e.observed_at, e.retrieved_at,
                   CASE WHEN u.id IS NULL THEN NULL
                        WHEN u.status = 'deleted' OR u.attribution = 'anonymous' THEN 'Anonymous contributor'
                        ELSE u.display_name END AS contributor_name
            FROM evidence e
            JOIN source s ON s.id = e.source_id
            LEFT JOIN app_user u ON u.id = e.contributor_user_id
            """;

    private final JdbcClient jdbc;

    public GuideRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<EntryRow> publishedEntries(UUID destinationId) {
        return jdbc.sql("""
                        SELECT e.id, e.destination_id, e.section, e.subsection, e.area, e.title, e.alt_title,
                               e.latitude, e.longitude, e.wikidata_id, e.sitelink_count, e.attributes::text AS attributes,
                               e.status, e.status_reason, e.origin, e.last_seen_in_source_at, e.created_at,
                               CASE WHEN e.geog IS NULL OR p.geog IS NULL THEN NULL
                                    ELSE round((ST_Distance(e.geog, p.geog) / 1000.0)::numeric, 2)::double precision END AS distance_km
                        FROM guide_entry e JOIN place p ON p.id = e.destination_id
                        WHERE e.destination_id = :d AND e.status = 'published'
                        ORDER BY e.section, e.title, e.id
                        """)
                .param("d", destinationId).query(EntryRow.class).list();
    }

    public Optional<EntryRow> entry(UUID entryId) {
        return jdbc.sql("""
                        SELECT e.id, e.destination_id, e.section, e.subsection, e.area, e.title, e.alt_title,
                               e.latitude, e.longitude, e.wikidata_id, e.sitelink_count, e.attributes::text AS attributes,
                               e.status, e.status_reason, e.origin, e.last_seen_in_source_at, e.created_at,
                               NULL::double precision AS distance_km
                        FROM guide_entry e WHERE e.id = :id
                        """)
                .param("id", entryId).query(EntryRow.class).optional();
    }

    public List<FactRow> publishedFacts(UUID destinationId) {
        return jdbc.sql(FACT_SELECT + " WHERE f.destination_id = :d AND f.status = 'published' ORDER BY f.created_at, f.id")
                .param("d", destinationId).query(FactRow.class).list();
    }

    public Optional<FactRow> fact(UUID factId) {
        return jdbc.sql(FACT_SELECT + " WHERE f.id = :id").param("id", factId).query(FactRow.class).optional();
    }

    public List<FactRow> factsForEntry(UUID entryId) {
        return jdbc.sql(FACT_SELECT + " WHERE f.entry_id = :id AND f.status = 'published' ORDER BY f.kind")
                .param("id", entryId).query(FactRow.class).list();
    }

    public List<EvidenceRow> evidenceForDestination(UUID destinationId) {
        return jdbc.sql(EVIDENCE_SELECT + """
                         JOIN fact f ON f.id = e.fact_id
                        WHERE f.destination_id = :d AND f.status = 'published'
                        ORDER BY e.retrieved_at DESC""")
                .param("d", destinationId).query(EvidenceRow.class).list();
    }

    public List<EvidenceRow> evidenceForFact(UUID factId) {
        return jdbc.sql(EVIDENCE_SELECT + " WHERE e.fact_id = :id ORDER BY e.retrieved_at DESC")
                .param("id", factId).query(EvidenceRow.class).list();
    }

    public List<ObservationRow> observations(UUID factId, int limit) {
        return jdbc.sql("""
                        SELECT o.id, o.fact_id, o.value_text, o.value_json::text AS value_json, o.observed_at,
                               o.change_type, o.source_id, o.recorded_at
                        FROM fact_observation o
                        WHERE o.fact_id = :id
                           OR o.fact_id IN (WITH RECURSIVE chain AS (
                                                SELECT supersedes_fact_id AS id, 1 AS depth FROM fact WHERE id = :id
                                                UNION ALL
                                                SELECT f.supersedes_fact_id, c.depth + 1 FROM fact f JOIN chain c ON f.id = c.id
                                                WHERE c.depth < 20)
                                            SELECT id FROM chain WHERE id IS NOT NULL)
                        ORDER BY o.recorded_at DESC LIMIT :limit
                        """)
                .param("id", factId).param("limit", limit).query(ObservationRow.class).list();
    }

    public record VerificationRow(UUID id, String verdict, String status, String note, String evidenceUrl,
                                  String contributorName, java.time.OffsetDateTime createdAt) {}

    public List<VerificationRow> verifications(UUID factId, int limit) {
        return jdbc.sql("""
                        SELECT v.id, v.verdict, v.status, v.note, v.evidence_url,
                               CASE WHEN u.id IS NULL OR u.status = 'deleted' OR u.attribution = 'anonymous'
                                    THEN 'Anonymous contributor' ELSE u.display_name END AS contributor_name,
                               v.created_at
                        FROM verification v LEFT JOIN app_user u ON u.id = v.user_id
                        WHERE v.fact_id = :id AND v.status IN ('accepted', 'pending')
                        ORDER BY v.created_at DESC LIMIT :limit
                        """)
                .param("id", factId).param("limit", limit).query(VerificationRow.class).list();
    }

    public record ClosedEntryRow(UUID id, String section, String title, String statusReason,
                                 java.time.OffsetDateTime updatedAt) {}

    public List<ClosedEntryRow> recentlyClosed(UUID destinationId, int limit) {
        return jdbc.sql("""
                        SELECT id, section, title, status_reason, updated_at FROM guide_entry
                        WHERE destination_id = :d AND status = 'closed' ORDER BY updated_at DESC LIMIT :limit
                        """)
                .param("d", destinationId).param("limit", limit).query(ClosedEntryRow.class).list();
    }
}
