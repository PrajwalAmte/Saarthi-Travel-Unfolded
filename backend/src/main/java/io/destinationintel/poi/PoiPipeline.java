package io.destinationintel.poi;

import io.destinationintel.guide.GuideBaseLoader;
import io.destinationintel.ingestion.run.SyncRunRecorder;
import io.destinationintel.ingestion.run.SyncRunRecorder.Run;
import io.destinationintel.quality.QualityService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/**
 * The shared lifecycle every POI source goes through after its adapter has read and parsed
 * records: validate and normalise, persist in batches, resolve identities, handle records
 * missing from the release, assign geography, link guide entries, evaluate quality and only
 * then publish. Adapters contain no persistence or matching logic of their own.
 */
@Component
public class PoiPipeline {

    private static final Logger log = LoggerFactory.getLogger(PoiPipeline.class);

    /** How a finished import should treat records it did not see. */
    public record Scope(boolean complete, double[] bbox, double maxDeletionShare) {
        public static Scope fullRelease() {
            return new Scope(true, null, 0.2);
        }

        public static Scope boundingBox(double[] bbox) {
            return new Scope(true, bbox, 0.3);
        }
    }

    /** Receives raw records from an adapter and writes them in batches. */
    public final class Session implements Consumer<RawPoi> {
        private final Run run;
        private final OffsetDateTime seenAt;
        private final List<NormalizedPoi> buffer = new ArrayList<>();
        private final Map<String, Long> attributeWarnings = new LinkedHashMap<>();

        Session(Run run, OffsetDateTime seenAt) {
            this.run = run;
            this.seenAt = seenAt;
        }

        @Override
        public void accept(RawPoi raw) {
            run.parsed.incrementAndGet();
            PoiNormalizer.Result result = PoiNormalizer.normalize(raw);
            if (result instanceof PoiNormalizer.Rejected r) {
                run.reject(r.reason());
                return;
            }
            PoiNormalizer.Accepted a = (PoiNormalizer.Accepted) result;
            for (String w : a.attributeWarnings()) {
                attributeWarnings.merge(w, 1L, Long::sum);
            }
            buffer.add(a.poi());
            if (buffer.size() >= PoiStore.batchSize()) {
                flush();
            }
        }

        public void flush() {
            if (buffer.isEmpty()) {
                return;
            }
            PoiStore.UpsertCounts c = store.upsert(List.copyOf(buffer), run.id, seenAt);
            run.inserted.addAndGet(c.inserted());
            run.updated.addAndGet(c.changed());
            run.unchanged.addAndGet(c.unchanged());
            buffer.clear();
        }

        public Map<String, Long> attributeWarnings() {
            return attributeWarnings;
        }
    }

    private final PoiStore store;
    private final PoiEntryLinker linker;
    private final QualityService quality;
    private final SyncRunRecorder recorder;
    private final CacheManager caches;
    private final MatchReviewResolver resolver;

    public PoiPipeline(PoiStore store, PoiEntryLinker linker, QualityService quality, SyncRunRecorder recorder,
                       CacheManager caches, MatchReviewResolver resolver) {
        this.resolver = resolver;
        this.store = store;
        this.linker = linker;
        this.quality = quality;
        this.recorder = recorder;
        this.caches = caches;
    }

    public Session open(Run run, OffsetDateTime seenAt) {
        return new Session(run, seenAt);
    }

    /** Runs resolution, deletion handling, geography, linking, the quality gate and publication. */
    public Map<String, Object> finish(Run run, Session session, OffsetDateTime runStart, Scope scope, double radiusKm) {
        session.flush();
        Map<String, Object> stats = new LinkedHashMap<>();
        if (!session.attributeWarnings().isEmpty()) {
            stats.put("droppedAttributes", session.attributeWarnings());
            session.attributeWarnings().forEach((k, v) -> run.warn(v + " records had an " + k.replace('_', ' ') + " that was dropped"));
        }

        store.analyze();
        recorder.stage(run, "validate");
        PoiStore.QuarantineCounts q = store.quarantineStackedCoordinates(run.sourceId);
        stats.put("placeholderCoordinates", q);
        long unplaceable = store.unplaceableCount(run.sourceId);
        if (unplaceable > 0) {
            run.warn(unplaceable + " records share placeholder coordinates (5 or more different places on one exact point) "
                    + "and are kept but not mapped.");
        }
        recorder.stage(run, "resolve");
        PoiStore.ResolveCounts rc = store.resolve(run.sourceId, run.id, runStart);
        // Uncertain matches are decided from evidence; each decision records its rule.
        MatchReviewResolver.Counts reviewed = resolver.resolveOpen(run.sourceId);
        run.canonicalAdded.set(rc.created() + reviewed.created());
        run.matchedExisting.set(rc.wikidata() + rc.nameDistance() + rc.mergedDuplicates() + reviewed.merged());
        run.unresolved.set(store.openReviewCount(run.sourceId));
        stats.put("resolution", rc);
        stats.put("matchReviews", reviewed);

        recorder.stage(run, "persist");
        if (run.parsed.get() > 0) {
            PoiStore.DeletionResult del = store.markMissing(run.sourceId, runStart, scope.maxDeletionShare(), scope.bbox());
            if (del.skipped()) {
                run.warn("Deletion skipped: " + del.missing() + " of " + del.active()
                        + " existing records were not in this release, more than the safety limit allows.");
            }
            run.deleted.set(del.recordsDeleted());
            stats.put("deletion", del);
        }
        store.analyze();
        int assigned = store.assignGeography(runStart, radiusKm * 1000);
        stats.put("geographyAssigned", assigned);

        recorder.stage(run, "quality");
        QualityService.ReportView report = quality.evaluate(run.sourceId, run.id, run.scope);
        stats.put("quality", Map.of("status", report.status(), "score", report.score() == null ? "n/a" : report.score(),
                "reportId", report.id().toString()));
        boolean failed = "failed".equals(report.status());

        recorder.stage(run, "publish");
        if (!failed) {
            // Facts added to guide entries from this source (hours, phone, website) are published
            // only after the gate passes.
            PoiEntryLinker.LinkCounts links = linker.link(run.sourceId, OffsetDateTime.now());
            stats.put("guideEntryLinks", links);
            stats.put("guideEntryLinkReviews", resolver.resolveEntryLinks());
            run.published.set(store.publishedCount(run.sourceId));
            recorder.markPublished(run.sourceId);
        } else {
            int withdrawn = linker.withdraw(run.sourceId);
            stats.put("factsWithdrawn", withdrawn);
            run.warn("Quality gate failed: records from this source are not published until the issues are fixed"
                    + (withdrawn > 0 ? "; " + withdrawn + " facts it had added to guide entries were withdrawn." : "."));
        }
        evictGuides();
        String status = recorder.finish(run, failed, scope.complete());
        stats.put("status", status);
        log.info("POI import {} finished: {}", run.sourceId, run.summary());
        return stats;
    }

    private void evictGuides() {
        for (String name : new String[] {GuideBaseLoader.CACHE, "destination-detail"}) {
            var cache = caches.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        }
    }

    public UUID runId(Run run) {
        return run.id;
    }
}
