package io.destinationintel.ingestion.wikivoyage;

import io.destinationintel.audit.AuditService;
import io.destinationintel.common.Json;
import io.destinationintel.config.AppProperties;
import io.destinationintel.guide.CoverageService;
import io.destinationintel.ingestion.GuideWriter;
import io.destinationintel.ingestion.GuideWriter.Outcome;
import io.destinationintel.ingestion.JobHandler;
import io.destinationintel.ingestion.JobRepository;
import io.destinationintel.ingestion.JobRepository.JobRow;
import io.destinationintel.ingestion.wikidata.EntityInfo;
import io.destinationintel.ingestion.wikidata.WikidataClient;
import io.destinationintel.ingestion.wikivoyage.WikivoyageArticleParser.ParsedArticle;
import io.destinationintel.ingestion.wikivoyage.WikivoyageArticleParser.Region;
import io.destinationintel.ingestion.wikivoyage.WikivoyageClient.Revision;
import io.destinationintel.ingestion.wikivoyage.WikivoyageMapper.ArticleContext;
import io.destinationintel.ingestion.wikivoyage.WikivoyageMapper.MappedArticle;
import io.destinationintel.ingestion.wikivoyage.WikivoyageMapper.MappedEntry;
import io.destinationintel.ingestion.wikivoyage.WikivoyageMapper.MappedFact;
import io.destinationintel.place.PlaceRepository;
import io.destinationintel.place.PlaceRow;
import io.destinationintel.source.SourceRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Job "wikivoyage_guide": ingests the Wikivoyage article linked to a destination (and, for
 * large cities, its district articles) into typed, source-tagged facts. Unchanged revisions
 * only refresh source timestamps. Destinations that have graduated to community
 * maintenance are skipped unless the job is forced by an administrator.
 */
@Component
public class WikivoyageGuideHandler implements JobHandler {

    /**
     * Priority of the photo and climate jobs a guide queues. Above the guide imports (20 when queued in bulk),
     * so a destination's photo and weather arrive right after its guide, not after every guide in the queue.
     */
    static final int FOLLOW_UP_PRIORITY = 30;


    private final WikivoyageClient client;
    private final WikidataClient wikidata;
    private final PlaceRepository places;
    private final GuideWriter writer;
    private final SourceRepository sources;
    private final CoverageService coverage;
    private final JobRepository jobs;
    private final TransactionTemplate tx;
    private final JdbcClient jdbc;
    private final Json json;
    private final AuditService audit;
    private final AppProperties props;
    private final io.destinationintel.ingestion.run.SyncRunRecorder recorder;
    private final io.destinationintel.quality.QualityService quality;
    private final io.destinationintel.ai.AiProperties ai;
    /** AI review runs after the cheap follow-ups and the bulk imports, but before routine refreshes. */
    static final int AI_REVIEW_PRIORITY = 4;

    public WikivoyageGuideHandler(WikivoyageClient client, WikidataClient wikidata, PlaceRepository places,
                                  GuideWriter writer, SourceRepository sources, CoverageService coverage,
                                  JobRepository jobs, TransactionTemplate tx, JdbcClient jdbc, Json json,
                                  AuditService audit, AppProperties props,
                                  io.destinationintel.ingestion.run.SyncRunRecorder recorder,
                                  io.destinationintel.quality.QualityService quality,
                                  io.destinationintel.ai.AiProperties ai) {
        this.client = client;
        this.wikidata = wikidata;
        this.places = places;
        this.writer = writer;
        this.sources = sources;
        this.coverage = coverage;
        this.jobs = jobs;
        this.tx = tx;
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.props = props;
        this.recorder = recorder;
        this.quality = quality;
        this.ai = ai;
    }

    @Override
    public String type() {
        return "wikivoyage_guide";
    }

    @Override
    public Map<String, Object> run(JobRow job) {
        if (!sources.isEnabled(WikivoyageMapper.SOURCE_ID)) {
            throw new PermanentFailure("The Wikivoyage source is disabled in the source registry");
        }
        Map<String, Object> params = json.map(job.params());
        PlaceRow place;
        if (job.targetPlaceId() != null) {
            place = places.findById(job.targetPlaceId())
                    .orElseThrow(() -> new PermanentFailure("Target destination no longer exists"));
        } else if (params.get("wikidataId") instanceof String qid && qid.matches("^Q[0-9]+$")) {
            // Retryable: the place may simply not be indexed yet when this job is first attempted.
            place = places.findByWikidataId(qid)
                    .orElseThrow(() -> new IllegalStateException(qid + " is not in the index yet"));
        } else {
            throw new PermanentFailure("Job has no target destination");
        }
        if (place.wikivoyageTitle() == null) {
            throw new PermanentFailure("No English Wikivoyage article is linked to " + place.name() + " in Wikidata");
        }
        boolean force = Boolean.TRUE.equals(params.get("force"));
        Map<String, Object> stats = new LinkedHashMap<>();
        if (!force && coverage.profile(place.id()).map(p -> p.graduatedAt() != null).orElse(false)) {
            stats.put("skipped", "Destination has graduated to community maintenance; collected sources are no longer consumed");
            return stats;
        }

        io.destinationintel.ingestion.run.SyncRunRecorder.Run run =
                recorder.start(WikivoyageMapper.SOURCE_ID, job.id(), "place:" + place.slug());
        try {
            Optional<Revision> main = client.latest(place.wikivoyageTitle());
            if (main.isEmpty()) {
                recordSync(place.id(), place.wikivoyageTitle(), null, "not_found", "Article not found", Map.of());
                throw new PermanentFailure("Wikivoyage article '" + place.wikivoyageTitle() + "' was not found");
            }
            Revision rev = main.get();
            ParsedArticle article = WikivoyageArticleParser.parse(rev.content());
            boolean neighbourhoodsApply = Set.of("city", "town", "neighbourhood", "village", "island").contains(place.kind());

            List<Region> districtRegions = new ArrayList<>();
            if (neighbourhoodsApply) {
                for (Region r : article.regions()) {
                    if (r.linkTarget() != null && r.linkTarget().startsWith(rev.title() + "/")) {
                        districtRegions.add(r);
                    }
                }
            }
            List<String> specialityNames = new ArrayList<>(article.foodTerms());
            article.specialities().stream()
                    .filter(s -> !s.topHeading().equalsIgnoreCase("buy"))
                    .map(WikivoyageArticleParser.Speciality::name)
                    .filter(n -> !specialityNames.contains(n))
                    .forEach(specialityNames::add);

            run.datasetVersion("revision:" + rev.revisionId());
            Map<String, Object> mainStats = ingestArticle(place, rev, article, null, true, neighbourhoodsApply,
                    specialityNames, force, job.id(), run);
            stats.put("article", rev.title());
            stats.put("revision", rev.revisionId());
            stats.put("articleStatus", article.articleStatus());
            stats.put("main", mainStats);

            List<Map<String, Object>> districtStats = new ArrayList<>();
            int max = props.ingestion().maxDistrictArticles();
            for (Region r : districtRegions.subList(0, Math.min(max, districtRegions.size()))) {
                Optional<Revision> d = client.latest(r.linkTarget());
                if (d.isEmpty()) {
                    districtStats.add(Map.of("article", r.linkTarget(), "status", "not_found"));
                    continue;
                }
                ParsedArticle da = WikivoyageArticleParser.parse(d.get().content());
                Map<String, Object> ds = ingestArticle(place, d.get(), da, r.label(), false, false, specialityNames,
                        force, job.id(), run);
                ds.put("article", d.get().title());
                districtStats.add(ds);
                jobs.heartbeat(job.id());
            }
            if (!districtStats.isEmpty()) {
                stats.put("districts", districtStats);
            }

            Integer merged = tx.execute(s -> writer.mergeDuplicateEntries(place.id()));
            run.matchedExisting.addAndGet(merged == null ? 0 : merged);
            stats.put("duplicatesMerged", merged);
            CoverageService.CoverageResult cov = coverage.refresh(place.id());
            stats.put("coverage", cov.status());
            recorder.stage(run, "quality");
            io.destinationintel.quality.QualityService.ReportView report = quality.evaluate(WikivoyageMapper.SOURCE_ID, run.id, run.scope);
            boolean qualityFailed = "failed".equals(report.status());
            stats.put("quality", Map.of("status", report.status(), "reportId", report.id().toString()));
            run.published.set(jdbc.sql("""
                            SELECT count(*) FROM fact WHERE destination_id = :d AND status = 'published' AND ingest_key LIKE 'wv:%'
                            """).param("d", place.id()).query(Long.class).single());
            if (!qualityFailed) {
                recorder.markPublished(WikivoyageMapper.SOURCE_ID);
            }
            // When every article is on the revision we already hold, fetching nothing is the expected result, not a warning.
            stats.put("syncStatus", recorder.finish(run, qualityFailed, run.unchanged.get() == 0));
            stats.put("run", run.summary());

            // Make sure supporting data exists for a destination that now has guide content.
            jobs.enqueue("climate", "open-meteo", place.id(), Map.of(), "climate:" + place.id(), FOLLOW_UP_PRIORITY - 1, "system");
            jobs.enqueue("place_image", "wikimedia-commons", place.id(), Map.of(), "place_image:" + place.id(), FOLLOW_UP_PRIORITY, "system");
            if (ai.active()) {
                // Listings whose text is unchanged are skipped by the review, so this costs nothing on a no-op refresh.
                jobs.enqueue("ai_review", null, place.id(), Map.of(), "ai_review:" + place.id(), AI_REVIEW_PRIORITY, "system");
            }
            return stats;
        } catch (RuntimeException e) {
            recorder.fail(run, e.getMessage());
            throw e;
        }
    }

    private Map<String, Object> ingestArticle(PlaceRow place, Revision rev, ParsedArticle article, String area,
                                              boolean includeProse, boolean includeRegions,
                                              List<String> specialityNames, boolean force, UUID jobId,
                                              io.destinationintel.ingestion.run.SyncRunRecorder.Run run) {
        Map<String, Object> stats = new LinkedHashMap<>();
        Optional<SyncRow> sync = sync(place.id(), rev.title());
        boolean unchanged = !force && sync.isPresent()
                && String.valueOf(rev.revisionId()).equals(sync.get().lastRevision())
                && Integer.valueOf(WikivoyageArticleParser.VERSION).equals(sync.get().parserVersion())
                && Set.of("succeeded", "unchanged").contains(sync.get().lastStatus());
        OffsetDateTime runAt = OffsetDateTime.now(ZoneOffset.UTC);
        String prefix = "wv:" + rev.pageId() + ":";

        if (unchanged) {
            int refreshed = tx.execute(s -> {
                String pattern = prefix.replace("_", "\\_") + "%";
                int n = jdbc.sql("UPDATE fact SET source_refreshed_at = :now WHERE ingest_key LIKE :p AND status = 'published'")
                        .param("now", runAt).param("p", pattern).update();
                jdbc.sql("UPDATE guide_entry SET last_seen_in_source_at = :now WHERE ingest_key LIKE :p AND status = 'published'")
                        .param("now", runAt).param("p", pattern).update();
                jdbc.sql("""
                                UPDATE evidence SET retrieved_at = :now FROM fact f
                                WHERE evidence.fact_id = f.id AND f.ingest_key LIKE :p AND evidence.source_id = 'wikivoyage-en'
                                """).param("now", runAt).param("p", pattern).update();
                return n;
            });
            stats.put("status", "unchanged");
            stats.put("factsRefreshed", refreshed);
            run.unchanged.addAndGet(refreshed);
            recordSync(place.id(), rev.title(), String.valueOf(rev.revisionId()), "unchanged", null, stats);
            return stats;
        }

        Map<String, Integer> sitelinks = sitelinks(article);
        ArticleContext ctx = new ArticleContext(place.id(), rev.title(), rev.pageId(), rev.revisionId(), rev.timestamp(),
                area, includeProse, includeRegions, specialityNames, sitelinks,
                props.ranking().majorAttractionMinSitelinks());
        MappedArticle mapped = WikivoyageMapper.map(article, ctx);
        long items = mapped.entries().size() + mapped.destinationFacts().size() + mapped.skippedListings();
        run.fetched.addAndGet(items);
        run.parsed.addAndGet(items);
        for (int i = 0; i < mapped.skippedListings(); i++) {
            run.reject("listing_without_usable_information");
        }
        int[] geoFlagged = {0};

        Map<Outcome, Integer> outcomes = new EnumMap<>(Outcome.class);
        int[] entriesWritten = {0};
        int hidden = tx.execute(s -> {
            for (MappedEntry me : mapped.entries()) {
                GuideWriter.EntryUpsert entry = me.entry();
                String flag = geoFlag(place, entry.latitude(), entry.longitude());
                if (flag != null) {
                    geoFlagged[0]++;
                    run.warn("Coordinates of \"" + entry.title() + "\" are implausibly far from " + place.name()
                            + "; they were removed and the entry flagged.");
                    Map<String, Object> attrs = new LinkedHashMap<>(entry.attributes());
                    attrs.put("sourceCoordinates", Map.of("latitude", entry.latitude(), "longitude", entry.longitude(), "flag", flag));
                    entry = new GuideWriter.EntryUpsert(entry.destinationId(), entry.section(), entry.subsection(), entry.area(),
                            entry.title(), entry.altTitle(), null, null, entry.wikidataId(), entry.sitelinkCount(), attrs,
                            entry.ingestKey());
                }
                UUID entryId = writer.upsertEntry(entry, runAt);
                jdbc.sql("UPDATE guide_entry SET geo_flag = :f WHERE id = :id AND geo_flag IS DISTINCT FROM :f")
                        .param("f", flag).param("id", entryId).update();
                entriesWritten[0]++;
                for (MappedFact mf : me.factsFor(entryId)) {
                    outcomes.merge(writer.upsertFact(mf.fact(), mf.evidence(), runAt), 1, Integer::sum);
                }
            }
            for (MappedFact mf : mapped.destinationFacts()) {
                outcomes.merge(writer.upsertFact(mf.fact(), mf.evidence(), runAt), 1, Integer::sum);
            }
            int h = writer.hideMissing(prefix, runAt);
            audit.record(null, "ingestion.article_ingested", "place", place.id(), place.id(), Map.of(
                    "source", WikivoyageMapper.SOURCE_ID, "article", rev.title(), "revision", rev.revisionId(),
                    "entries", entriesWritten[0], "hidden", h));
            return h;
        });
        jobs.heartbeat(jobId);

        stats.put("status", "processed");
        stats.put("entries", entriesWritten[0]);
        stats.put("skippedListings", mapped.skippedListings());
        Map<String, Integer> o = new LinkedHashMap<>();
        outcomes.forEach((k, v) -> o.put(k.name().toLowerCase(), v));
        stats.put("facts", o);
        stats.put("hiddenNoLongerInSource", hidden);
        stats.put("coordinatesFlagged", geoFlagged[0]);
        run.inserted.addAndGet(outcomes.getOrDefault(Outcome.CREATED, 0));
        run.updated.addAndGet(outcomes.getOrDefault(Outcome.UPDATED, 0));
        run.unchanged.addAndGet(outcomes.getOrDefault(Outcome.UNCHANGED, 0) + outcomes.getOrDefault(Outcome.KEPT_COMMUNITY_VERSION, 0));
        run.deleted.addAndGet(hidden);
        recordSync(place.id(), rev.title(), String.valueOf(rev.revisionId()), "succeeded", null, stats);
        return stats;
    }

    /**
     * Flags listing coordinates that cannot belong to the destination: more than 80 km from a city
     * or town, or 600 km from a state, region or park. Returns null when plausible or absent.
     */
    static String geoFlag(PlaceRow place, Double lat, Double lon) {
        return io.destinationintel.common.GeoPlausibility.flag(place.kind(), place.latitude(), place.longitude(), lat, lon);
    }

    /** Wikipedia-language counts for listings that carry a Wikidata id (evidence for "major attraction"). */
    private Map<String, Integer> sitelinks(ParsedArticle article) {
        Set<String> qids = new LinkedHashSet<>();
        for (WikivoyageArticleParser.Listing l : article.listings()) {
            String q = l.field("wikidata");
            if (q != null && q.trim().matches("^Q[0-9]+$") && (l.type().equals("see") || l.type().equals("do"))) {
                qids.add(q.trim());
            }
        }
        Map<String, Integer> out = new HashMap<>();
        List<String> list = new ArrayList<>(qids);
        for (int i = 0; i < list.size(); i += 50) {
            JsonNode entities = wikidata.entities(list.subList(i, Math.min(list.size(), i + 50)), "sitelinks");
            for (String q : list.subList(i, Math.min(list.size(), i + 50))) {
                JsonNode n = entities.path(q);
                if (!n.isMissingNode() && !n.has("missing")) {
                    out.put(q, EntityInfo.parse(n).wikipediaCount());
                }
            }
        }
        return out;
    }

    record SyncRow(String lastRevision, Integer parserVersion, String lastStatus) {}

    private Optional<SyncRow> sync(UUID placeId, String title) {
        return jdbc.sql("""
                        SELECT last_revision, parser_version, last_status FROM place_source_sync
                        WHERE place_id = :p AND source_id = 'wikivoyage-en' AND external_id = :t
                        """)
                .param("p", placeId).param("t", title).query(SyncRow.class).optional();
    }

    private void recordSync(UUID placeId, String title, String revision, String status, String error, Map<String, Object> stats) {
        jdbc.sql("""
                        INSERT INTO place_source_sync (place_id, source_id, external_id, last_revision, parser_version,
                                                       last_synced_at, last_status, last_error, stats)
                        VALUES (:p, 'wikivoyage-en', :t, :rev, :ver, now(), :status, :error, CAST(:stats AS jsonb))
                        ON CONFLICT (place_id, source_id, external_id) DO UPDATE SET
                            last_revision = coalesce(EXCLUDED.last_revision, place_source_sync.last_revision),
                            parser_version = EXCLUDED.parser_version, last_synced_at = now(),
                            last_status = EXCLUDED.last_status, last_error = EXCLUDED.last_error, stats = EXCLUDED.stats
                        """)
                .param("p", placeId).param("t", title).param("rev", revision)
                .param("ver", WikivoyageArticleParser.VERSION).param("status", status).param("error", error)
                .param("stats", json.write(stats)).update();
    }
}
