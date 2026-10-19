package io.destinationintel.guide;

import io.destinationintel.guide.GuideRows.EntryRow;
import io.destinationintel.guide.GuideRows.EvidenceRow;
import io.destinationintel.guide.GuideRows.FactRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * Loads all published guide content of one destination in three indexed queries. The
 * result is cached per destination; ranking for a given set of preferences then happens in
 * memory. The cache is evicted whenever moderation or ingestion changes the destination.
 */
@Component
public class GuideBaseLoader {

    public record GuideBase(
            UUID destinationId,
            List<EntryRow> entries,
            Map<UUID, List<FactRow>> factsByEntry,
            List<FactRow> destinationFacts,
            Map<UUID, List<EvidenceRow>> evidenceByFact) {

        public List<FactRow> factsFor(UUID entryId) {
            return factsByEntry.getOrDefault(entryId, List.of());
        }

        public List<EvidenceRow> evidenceFor(UUID factId) {
            return evidenceByFact.getOrDefault(factId, List.of());
        }
    }

    public static final String CACHE = "guide-base";

    private final GuideRepository repo;
    private final CacheManager cacheManager;

    public GuideBaseLoader(GuideRepository repo, CacheManager cacheManager) {
        this.repo = repo;
        this.cacheManager = cacheManager;
    }

    @Cacheable(cacheNames = CACHE, key = "#destinationId")
    public GuideBase load(UUID destinationId) {
        List<EntryRow> entries = repo.publishedEntries(destinationId);
        Map<UUID, List<FactRow>> byEntry = new LinkedHashMap<>();
        List<FactRow> destinationFacts = new ArrayList<>();
        for (FactRow f : repo.publishedFacts(destinationId)) {
            if (f.entryId() == null) {
                destinationFacts.add(f);
            } else {
                byEntry.computeIfAbsent(f.entryId(), k -> new ArrayList<>()).add(f);
            }
        }
        Map<UUID, List<EvidenceRow>> evidence = new LinkedHashMap<>();
        for (EvidenceRow e : repo.evidenceForDestination(destinationId)) {
            evidence.computeIfAbsent(e.factId(), k -> new ArrayList<>()).add(e);
        }
        return new GuideBase(destinationId, List.copyOf(entries), Map.copyOf(byEntry), List.copyOf(destinationFacts),
                Map.copyOf(evidence));
    }

    public void evict(UUID destinationId) {
        var cache = cacheManager.getCache(CACHE);
        if (cache != null) {
            cache.evict(destinationId);
        }
        var detail = cacheManager.getCache("destination-detail");
        if (detail != null) {
            detail.clear();
        }
    }
}
