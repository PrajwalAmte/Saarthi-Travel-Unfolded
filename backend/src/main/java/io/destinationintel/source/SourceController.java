package io.destinationintel.source;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.destinationintel.common.Json;
import io.destinationintel.ingestion.run.SyncRunRecorder;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sources")
@Tag(name = "Sources", description = "Public source registry: licences, attribution and sync health")
public class SourceController {

    /** What a source currently contributes, by kind of record. */
    public record Contribution(String kind, String label, long count) {}

    /** The latest sync run, split by stage outcome so a failed parse is not confused with a failed gate. */
    public record LatestRun(UUID id, String scope, String status, String stage, String datasetVersion,
                            OffsetDateTime startedAt, OffsetDateTime finishedAt, long fetched, long parsed, long rejected,
                            long inserted, long updated, long unchanged, long deleted, long published, List<String> warnings) {}

    public record PublicSource(
            String id,
            String name,
            String category,
            String accessMethod,
            String baseUrl,
            String licenceName,
            String licenceUrl,
            String attribution,
            boolean shareAlike,
            String usageNotes,
            String rateLimitPolicy,
            int refreshIntervalDays,
            boolean enabled,
            OffsetDateTime lastFetchAt,
            OffsetDateTime lastSuccessAt,
            boolean lastAttemptFailed,
            OffsetDateTime lastErrorAt,
            OffsetDateTime lastPublicationAt,
            OffsetDateTime nextSyncDueAt,
            String datasetVersion,
            String qualityStatus,
            OffsetDateTime qualityEvaluatedAt,
            Double qualityScore,
            UUID qualityReportId,
            LatestRun latestRun,
            List<Contribution> contributions,
            long factsBacked) {}

    private final SourceRepository sources;
    private final JdbcClient jdbc;
    private final SyncRunRecorder runs;
    private final Json json;

    public SourceController(SourceRepository sources, JdbcClient jdbc, SyncRunRecorder runs, Json json) {
        this.sources = sources;
        this.jdbc = jdbc;
        this.runs = runs;
        this.json = json;
    }

    private Map<String, Long> countBySource(String sql) {
        Map<String, Long> out = new HashMap<>();
        jdbc.sql(sql).query((rs, i) -> Map.entry(rs.getString(1), rs.getLong(2))).list().forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    @GetMapping
    @Operation(summary = "List every data source with its licence, attribution, sync health, quality status and contributions")
    public List<PublicSource> list() {
        Map<String, Long> facts = countBySource("""
                SELECT e.source_id, count(DISTINCT e.fact_id) FROM evidence e JOIN fact f ON f.id = e.fact_id
                WHERE f.status = 'published' GROUP BY 1""");
        Map<String, Long> aliases = countBySource("SELECT source_id, count(*) FROM place_alias GROUP BY 1");
        Map<String, Long> relations = countBySource("SELECT source_id, count(*) FROM place_relation GROUP BY 1");
        Map<String, Long> images = countBySource("SELECT source_id, count(*) FROM place_image GROUP BY 1");
        Map<String, Long> poiRecords = countBySource("SELECT source_id, count(*) FROM poi_source_record WHERE deleted_at IS NULL GROUP BY 1");
        Map<String, Long> identities = countBySource("SELECT source_id, count(*) FROM place_source_identity GROUP BY 1");
        long destinations = jdbc.sql("""
                SELECT count(*) FROM place WHERE lifecycle_status = 'active' AND is_destination AND wikidata_id IS NOT NULL
                """).query(Long.class).single();
        record Quality(String sourceId, UUID id, Double score) {}
        Map<String, Quality> quality = new HashMap<>();
        jdbc.sql("SELECT DISTINCT ON (source_id) source_id, id, score FROM quality_report ORDER BY source_id, generated_at DESC")
                .query((rs, i) -> new Quality(rs.getString(1), rs.getObject(2, UUID.class), rs.getObject(3) == null ? null : rs.getDouble(3)))
                .list().forEach(q -> quality.put(q.sourceId(), q));
        record Health(String id, OffsetDateTime lastPublicationAt, String datasetVersion, String qualityStatus,
                      OffsetDateTime qualityEvaluatedAt) {}
        Map<String, Health> health = new HashMap<>();
        jdbc.sql("SELECT id, last_publication_at, dataset_version, quality_status, quality_evaluated_at FROM source")
                .query(Health.class).list().forEach(h -> health.put(h.id(), h));

        return sources.all().stream().map(s -> {
            List<Contribution> c = new ArrayList<>();
            if (s.id().equals("wikidata")) {
                c.add(new Contribution("destinations", "Destinations indexed", destinations));
            }
            add(c, "facts", "Published facts with evidence", facts.get(s.id()));
            add(c, "poi_records", "Mapped places (active source records)", poiRecords.get(s.id()));
            add(c, "aliases", "Place names and aliases", aliases.get(s.id()));
            add(c, "relations", "Administrative relations", relations.get(s.id()));
            add(c, "images", "Images", images.get(s.id()));
            add(c, "place_identities", "Places matched to this source's ids", identities.get(s.id()));
            Health h = health.get(s.id());
            Quality q = quality.get(s.id());
            LatestRun latest = runs.latest(s.id()).map(r -> new LatestRun(r.id(), r.scope(), r.status(), r.stage(),
                    r.datasetVersion(), r.startedAt(), r.finishedAt(), r.fetched(), r.parsed(), r.rejected(), r.inserted(),
                    r.updated(), r.unchanged(), r.deleted(), r.published(), warnings(r.warnings()))).orElse(null);
            return new PublicSource(
                    s.id(), s.name(), s.category(), s.accessMethod(), s.baseUrl(), s.licenceName(), s.licenceUrl(),
                    s.attributionText(), s.shareAlike(), s.usageNotes(), s.rateLimitPolicy(), s.refreshIntervalDays(),
                    s.enabled(), s.lastFetchAt(), s.lastSuccessAt(),
                    s.lastErrorAt() != null && (s.lastSuccessAt() == null || s.lastErrorAt().isAfter(s.lastSuccessAt())),
                    s.lastErrorAt(),
                    h == null ? null : h.lastPublicationAt(),
                    s.lastSuccessAt() == null ? null : s.lastSuccessAt().plusDays(s.refreshIntervalDays()),
                    h == null ? null : h.datasetVersion(),
                    h == null ? "not_evaluated" : h.qualityStatus(),
                    h == null ? null : h.qualityEvaluatedAt(),
                    q == null ? null : q.score(),
                    q == null ? null : q.id(),
                    latest,
                    c,
                    facts.getOrDefault(s.id(), 0L));
        }).toList();
    }

    private static void add(List<Contribution> c, String kind, String label, Long n) {
        if (n != null && n > 0) {
            c.add(new Contribution(kind, label, n));
        }
    }

    private List<String> warnings(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return json.list(raw).stream().map(String::valueOf).limit(10).toList();
    }
}
