package io.destinationintel.ingestion;

import io.destinationintel.common.Json;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent writes of ingested guide content, keyed by stable ingest keys.
 *
 * <ul>
 *   <li>Unchanged values only refresh {@code source_refreshed_at} and evidence retrieval
 *       metadata; verification timestamps are never touched by ingestion.</li>
 *   <li>A changed value supersedes the old fact with a new one, so community verifications
 *       stay attached to the value they confirmed and the change is kept in history.</li>
 *   <li>Facts that moderation retracted or a community correction superseded are never
 *       overwritten by a source refresh.</li>
 * </ul>
 */
@Component
public class GuideWriter {

    public enum Outcome { CREATED, UPDATED, UNCHANGED, KEPT_COMMUNITY_VERSION }

    public record EntryUpsert(
            UUID destinationId,
            String section,
            String subsection,
            String area,
            String title,
            String altTitle,
            Double latitude,
            Double longitude,
            String wikidataId,
            Integer sitelinkCount,
            Map<String, Object> attributes,
            String ingestKey) {}

    public record FactUpsert(
            UUID destinationId,
            UUID entryId,
            String section,
            String kind,
            String heading,
            String valueText,
            Map<String, Object> valueJson,
            String volatility,
            LocalDate observedAt,
            String observedBasis,
            String ingestKey) {}

    public record EvidenceUpsert(
            String sourceId,
            String url,
            String permalink,
            String title,
            String excerpt,
            String extractionMethod,
            String sourceRevision,
            OffsetDateTime sourceRevisionAt,
            LocalDate observedAt) {}

    private record ExistingFact(UUID id, String valueText, String valueJson, String status, LocalDate observedAt) {}

    private final JdbcClient jdbc;
    private final Json json;
    private final io.destinationintel.guide.PendingVerifications pending;

    public GuideWriter(JdbcClient jdbc, Json json, io.destinationintel.guide.PendingVerifications pending) {
        this.jdbc = jdbc;
        this.json = json;
        this.pending = pending;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID upsertEntry(EntryUpsert u, OffsetDateTime seenAt) {
        return jdbc.sql("""
                        INSERT INTO guide_entry (destination_id, section, subsection, area, title, alt_title, latitude, longitude,
                                                 wikidata_id, sitelink_count, attributes, status, origin, ingest_key,
                                                 last_seen_in_source_at)
                        VALUES (:dest, :section, :subsection, :area, :title, :alt, :lat, :lon, :qid, :sitelinks,
                                CAST(:attrs AS jsonb), 'published', 'ingested', :key, :seen)
                        ON CONFLICT (ingest_key) DO UPDATE SET
                            section = EXCLUDED.section,
                            subsection = EXCLUDED.subsection,
                            area = EXCLUDED.area,
                            title = EXCLUDED.title,
                            alt_title = EXCLUDED.alt_title,
                            latitude = EXCLUDED.latitude,
                            longitude = EXCLUDED.longitude,
                            wikidata_id = EXCLUDED.wikidata_id,
                            sitelink_count = EXCLUDED.sitelink_count,
                            attributes = EXCLUDED.attributes || jsonb_strip_nulls(jsonb_build_object(
                                'temporarilyClosed', guide_entry.attributes -> 'temporarilyClosed')),
                            last_seen_in_source_at = EXCLUDED.last_seen_in_source_at,
                            status = CASE WHEN guide_entry.status = 'hidden' AND guide_entry.status_reason = 'removed_from_source'
                                          THEN 'published' ELSE guide_entry.status END,
                            status_reason = CASE WHEN guide_entry.status = 'hidden' AND guide_entry.status_reason = 'removed_from_source'
                                                 THEN NULL ELSE guide_entry.status_reason END,
                            updated_at = now()
                        RETURNING id
                        """)
                .param("dest", u.destinationId()).param("section", u.section()).param("subsection", u.subsection())
                .param("area", u.area()).param("title", u.title()).param("alt", u.altTitle())
                .param("lat", u.latitude()).param("lon", u.longitude()).param("qid", u.wikidataId())
                .param("sitelinks", u.sitelinkCount()).param("attrs", json.write(u.attributes() == null ? Map.of() : u.attributes()))
                .param("key", u.ingestKey()).param("seen", seenAt)
                .query(UUID.class).single();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome upsertFact(FactUpsert f, EvidenceUpsert ev, OffsetDateTime fetchedAt) {
        Optional<ExistingFact> existing = jdbc.sql("""
                        SELECT id, value_text, value_json::text AS value_json, status, observed_at
                        FROM fact WHERE ingest_key = :key FOR UPDATE
                        """)
                .param("key", f.ingestKey()).query(ExistingFact.class).optional();
        String newJson = f.valueJson() == null ? null : json.write(f.valueJson());

        if (existing.isEmpty()) {
            UUID id = insertFact(f, newJson, fetchedAt, null);
            observation(id, f.valueText(), newJson, f.observedAt(), "initial", ev.sourceId());
            upsertEvidence(id, ev, fetchedAt);
            return Outcome.CREATED;
        }

        ExistingFact old = existing.get();
        boolean sameValue = old.valueText().equals(f.valueText()) && jsonEquals(old.valueJson(), newJson);

        if (old.status().equals("superseded") || old.status().equals("retracted") || old.status().equals("rejected")) {
            // A community correction or moderation decision takes precedence; keep history only.
            if (!sameValue) {
                observation(old.id(), f.valueText(), newJson, f.observedAt(), "source_update", ev.sourceId());
            }
            upsertEvidence(old.id(), ev, fetchedAt);
            return Outcome.KEPT_COMMUNITY_VERSION;
        }

        if (sameValue) {
            jdbc.sql("""
                            UPDATE fact SET source_refreshed_at = :fetched,
                                   observed_at = CASE WHEN CAST(:observed AS date) IS NULL THEN observed_at
                                                      WHEN observed_at IS NULL OR CAST(:observed AS date) > observed_at
                                                      THEN CAST(:observed AS date) ELSE observed_at END,
                                   observed_basis = CASE WHEN CAST(:observed AS date) IS NULL THEN observed_basis ELSE :basis END,
                                   heading = :heading, section = :section,
                                   status = CASE WHEN status = 'hidden' THEN 'published' ELSE status END,
                                   updated_at = now()
                            WHERE id = :id
                            """)
                    .param("fetched", fetchedAt).param("observed", f.observedAt()).param("basis", f.observedBasis())
                    .param("heading", f.heading()).param("section", f.section()).param("id", old.id())
                    .update();
            upsertEvidence(old.id(), ev, fetchedAt);
            return Outcome.UNCHANGED;
        }

        // Value changed in the source: supersede, keeping verifications attached to the old value.
        jdbc.sql("UPDATE fact SET status = 'superseded', ingest_key = NULL, updated_at = now() WHERE id = :id")
                .param("id", old.id()).update();
        pending.withdrawForReplacedFact(old.id());
        UUID id = insertFact(f, newJson, fetchedAt, old.id());
        observation(id, f.valueText(), newJson, f.observedAt(), "source_update", ev.sourceId());
        upsertEvidence(id, ev, fetchedAt);
        return Outcome.UPDATED;
    }

    private UUID insertFact(FactUpsert f, String valueJson, OffsetDateTime fetchedAt, UUID supersedes) {
        return jdbc.sql("""
                        INSERT INTO fact (destination_id, entry_id, section, kind, heading, value_text, value_json, volatility,
                                          status, origin, ingest_key, observed_at, observed_basis, source_refreshed_at,
                                          supersedes_fact_id)
                        VALUES (:dest, :entry, :section, :kind, :heading, :text, CAST(:json AS jsonb), :volatility,
                                'published', 'ingested', :key, :observed, :basis, :fetched, :supersedes)
                        RETURNING id
                        """)
                .param("dest", f.destinationId()).param("entry", f.entryId()).param("section", f.section())
                .param("kind", f.kind()).param("heading", f.heading()).param("text", f.valueText())
                .param("json", valueJson).param("volatility", f.volatility()).param("key", f.ingestKey())
                .param("observed", f.observedAt()).param("basis", f.observedBasis()).param("fetched", fetchedAt)
                .param("supersedes", supersedes)
                .query(UUID.class).single();
    }

    private void observation(UUID factId, String text, String valueJson, LocalDate observedAt, String change, String sourceId) {
        jdbc.sql("""
                        INSERT INTO fact_observation (fact_id, value_text, value_json, observed_at, change_type, source_id)
                        VALUES (:fact, :text, CAST(:json AS jsonb), :observed, :change, :source)
                        """)
                .param("fact", factId).param("text", text).param("json", valueJson).param("observed", observedAt)
                .param("change", change).param("source", sourceId).update();
    }

    private void upsertEvidence(UUID factId, EvidenceUpsert ev, OffsetDateTime fetchedAt) {
        jdbc.sql("""
                        INSERT INTO evidence (fact_id, source_id, url, permalink, title, excerpt, extraction_method,
                                              source_revision, source_revision_at, observed_at, retrieved_at)
                        VALUES (:fact, :source, :url, :permalink, :title, :excerpt, :method, :revision, :revisionAt,
                                :observed, :retrieved)
                        ON CONFLICT (fact_id, source_id, (coalesce(url, ''))) DO UPDATE SET
                            permalink = EXCLUDED.permalink,
                            title = EXCLUDED.title,
                            excerpt = EXCLUDED.excerpt,
                            extraction_method = EXCLUDED.extraction_method,
                            source_revision = EXCLUDED.source_revision,
                            source_revision_at = EXCLUDED.source_revision_at,
                            observed_at = coalesce(EXCLUDED.observed_at, evidence.observed_at),
                            retrieved_at = EXCLUDED.retrieved_at,
                            updated_at = now()
                        """)
                .param("fact", factId).param("source", ev.sourceId()).param("url", ev.url())
                .param("permalink", ev.permalink()).param("title", ev.title()).param("excerpt", ev.excerpt())
                .param("method", ev.extractionMethod()).param("revision", ev.sourceRevision())
                .param("revisionAt", ev.sourceRevisionAt()).param("observed", ev.observedAt())
                .param("retrieved", fetchedAt)
                .update();
    }

    /**
     * Hides content from an article that was not present in the latest run. Entries that
     * contributors have verified are kept but flagged for review instead.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int hideMissing(String ingestKeyPrefix, OffsetDateTime runStartedAt) {
        String pattern = ingestKeyPrefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        int entries = jdbc.sql("""
                        UPDATE guide_entry e SET status = 'hidden', status_reason = 'removed_from_source', updated_at = now()
                        WHERE e.ingest_key LIKE :p ESCAPE '\\' AND e.status = 'published'
                          AND (e.last_seen_in_source_at IS NULL OR e.last_seen_in_source_at < :run)
                          AND NOT EXISTS (SELECT 1 FROM fact f JOIN verification v ON v.fact_id = f.id
                                          WHERE f.entry_id = e.id AND v.status = 'accepted' AND v.verdict = 'confirm')
                        """)
                .param("p", pattern).param("run", runStartedAt).update();
        int facts = jdbc.sql("""
                        UPDATE fact f SET status = 'hidden', updated_at = now()
                        WHERE f.ingest_key LIKE :p ESCAPE '\\' AND f.status = 'published'
                          AND (f.source_refreshed_at IS NULL OR f.source_refreshed_at < :run)
                          AND NOT EXISTS (SELECT 1 FROM verification v WHERE v.fact_id = f.id
                                          AND v.status = 'accepted' AND v.verdict = 'confirm')
                        """)
                .param("p", pattern).param("run", runStartedAt).update();
        return entries + facts;
    }

    /**
     * Merges high-confidence duplicate entries in one destination: same section and name, and
     * either within 150 m of each other or with the same normalised address. The entry with more
     * published facts (then the older one) is kept; the other is marked merged, not deleted.
     * Same-name entries elsewhere (branches of a chain) are left alone.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int mergeDuplicateEntries(UUID destinationId) {
        record Pair(UUID keep, UUID drop) {}
        // A duplicate whose kept twin has since left the source comes back on its own.
        jdbc.sql("""
                        UPDATE guide_entry e SET status = 'published', merged_into_id = NULL, status_reason = NULL, updated_at = now()
                        WHERE e.destination_id = :d AND e.status = 'merged' AND e.origin = 'ingested'
                          AND e.status_reason LIKE 'Duplicate listing%'
                          AND EXISTS (SELECT 1 FROM guide_entry k WHERE k.id = e.merged_into_id AND k.status <> 'published')
                        """).param("d", destinationId).update();
        List<Pair> pairs = jdbc.sql("""
                        WITH scored AS (
                            SELECT e.id, e.section, e.title_normalized, e.geog, e.created_at,
                                   (SELECT count(*) FROM fact f WHERE f.entry_id = e.id AND f.status = 'published') AS facts,
                                   (SELECT idi_normalize(f.value_text) FROM fact f WHERE f.entry_id = e.id AND f.kind = 'address'
                                    AND f.status = 'published' LIMIT 1) AS address
                            FROM guide_entry e WHERE e.destination_id = :d AND e.status = 'published' AND e.section <> 'neighbourhood')
                        SELECT DISTINCT ON (b.id) a.id AS keep, b.id AS drop
                        FROM scored a JOIN scored b ON b.id <> a.id AND b.section = a.section AND b.title_normalized = a.title_normalized
                             AND (a.facts > b.facts OR (a.facts = b.facts AND (a.created_at, a.id) < (b.created_at, b.id)))
                        WHERE (a.geog IS NOT NULL AND b.geog IS NOT NULL AND ST_DWithin(a.geog, b.geog, 150))
                           OR (a.address IS NOT NULL AND a.address = b.address)
                        ORDER BY b.id, a.facts DESC, a.created_at
                        """).param("d", destinationId).query(Pair.class).list();
        java.util.Set<UUID> dropped = new java.util.HashSet<>();
        int merged = 0;
        for (Pair p : pairs) {
            if (dropped.contains(p.keep()) || !dropped.add(p.drop())) {
                continue;
            }
            merged += jdbc.sql("""
                            UPDATE guide_entry SET status = 'merged', merged_into_id = :keep,
                                   status_reason = 'Duplicate listing of the same place (same name and location or address)',
                                   updated_at = now()
                            WHERE id = :drop AND status = 'published'
                            """).param("keep", p.keep()).param("drop", p.drop()).update();
        }
        return merged;
    }

    private boolean jsonEquals(String a, String b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return Objects.equals(json.tree(a), json.tree(b));
    }
}
