package io.destinationintel.request;

import io.destinationintel.common.Json;
import io.destinationintel.ingestion.JobHandler;
import io.destinationintel.ingestion.JobRepository.JobRow;
import io.destinationintel.ingestion.wikidata.EntityInfo;
import io.destinationintel.ingestion.wikidata.PlaceKindClassifier;
import io.destinationintel.ingestion.wikidata.WikidataClient;
import io.destinationintel.ingestion.wikidata.WikidataIndexer;
import io.destinationintel.place.PlaceRepository;
import io.destinationintel.place.PlaceRow;
import io.destinationintel.request.RequestService.Candidate;
import io.destinationintel.request.RequestService.RequestRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Job "resolve_request": resolves a requested name to a real Indian place using the local
 * index and Wikidata search. Ambiguous names are put back to the requester as a choice
 * instead of guessing; names that match no Indian place are reported as not found.
 */
@Component
public class ResolveRequestHandler implements JobHandler {

    private final RequestService requests;
    private final PlaceRepository places;
    private final WikidataClient wikidata;
    private final WikidataIndexer indexer;
    private final Json json;

    public ResolveRequestHandler(RequestService requests, PlaceRepository places, WikidataClient wikidata,
                                 WikidataIndexer indexer, Json json) {
        this.requests = requests;
        this.places = places;
        this.wikidata = wikidata;
        this.indexer = indexer;
        this.json = json;
    }

    @Override
    public String type() {
        return "resolve_request";
    }

    @Override
    public Map<String, Object> run(JobRow job) {
        Map<String, Object> params = json.map(job.params());
        UUID requestId = UUID.fromString(String.valueOf(params.get("requestId")));
        RequestRow r = requests.find(requestId).orElseThrow(() -> new PermanentFailure("Request no longer exists"));
        Map<String, Object> stats = new LinkedHashMap<>();
        if (r.resolvedPlaceId() != null) {
            stats.put("result", "already resolved");
            return stats;
        }
        String chosen = params.get("wikidataId") instanceof String s && s.matches("^Q[0-9]+$") ? s : null;
        if (chosen == null) {
            Optional<PlaceRow> local = requests.exactLocalMatch(r.normalizedQuery(), r.stateHint());
            if (local.isPresent()) {
                requests.attachPlace(requestId, local.get());
                stats.put("result", "matched local index");
                return stats;
            }
            List<Candidate> candidates = candidates(r);
            stats.put("candidates", candidates.size());
            if (candidates.isEmpty()) {
                requests.markUnresolvable(requestId, "No place in India with this name was found in Wikidata. "
                        + "Check the spelling, or add the state to narrow it down.");
                stats.put("result", "unresolvable");
                return stats;
            }
            if (candidates.size() > 1) {
                requests.markNeedsReview(requestId, candidates,
                        "Several places in India match this name. Choose the one you mean.");
                stats.put("result", "needs choice");
                return stats;
            }
            chosen = candidates.get(0).wikidataId();
        }
        Optional<PlaceRow> existing = places.findByWikidataId(chosen);
        if (existing.isEmpty()) {
            List<UUID> ids = indexer.indexItems(List.of(chosen));
            if (ids.isEmpty()) {
                requests.markUnresolvable(requestId, "The chosen item is not a place in India that can be indexed.");
                stats.put("result", "not indexable");
                return stats;
            }
            existing = places.findById(ids.get(0));
        }
        requests.attachPlace(requestId, existing.orElseThrow());
        stats.put("result", "resolved");
        stats.put("wikidataId", chosen);
        return stats;
    }

    /** Wikidata search results that are places in India, narrowed by the state hint when given. */
    List<Candidate> candidates(RequestRow r) {
        List<WikidataClient.SearchCandidate> found = wikidata.search(r.queryText(), 12);
        if (found.isEmpty()) {
            return List.of();
        }
        List<String> ids = found.stream().map(WikidataClient.SearchCandidate::id).toList();
        JsonNode entities = wikidata.entities(ids, "labels|claims");
        List<String> typeIds = new ArrayList<>();
        Map<String, EntityInfo> infos = new LinkedHashMap<>();
        for (String id : ids) {
            JsonNode n = entities.path(id);
            if (n.isMissingNode()) {
                continue;
            }
            EntityInfo e = EntityInfo.parse(n);
            if (!e.countries().contains(WikidataIndexer.INDIA) || e.latitude() == null) {
                continue;
            }
            infos.put(id, e);
            e.instanceOf().stream().filter(t -> !typeIds.contains(t)).forEach(typeIds::add);
        }
        Map<String, String> typeLabels = new LinkedHashMap<>();
        for (int i = 0; i < typeIds.size(); i += 50) {
            JsonNode types = wikidata.entities(typeIds.subList(i, Math.min(typeIds.size(), i + 50)), "labels");
            for (String t : typeIds.subList(i, Math.min(typeIds.size(), i + 50))) {
                String label = types.path(t).path("labels").path("en").path("value").asString(null);
                if (label != null) {
                    typeLabels.put(t, label);
                }
            }
        }
        List<Candidate> out = new ArrayList<>();
        String wanted = places.normalize(r.queryText());
        for (WikidataClient.SearchCandidate sc : found) {
            EntityInfo e = infos.get(sc.id());
            if (e == null) {
                continue;
            }
            List<String> labels = e.instanceOf().stream().map(typeLabels::get).filter(java.util.Objects::nonNull).toList();
            Optional<String> kind = PlaceKindClassifier.classify(labels, e.wikivoyageTitle());
            if (kind.isEmpty() || kind.get().equals("other")) {
                continue;
            }
            boolean nameMatches = places.normalize(sc.label() == null ? "" : sc.label()).equals(wanted)
                    || e.englishAliases().stream().anyMatch(a -> places.normalize(a).equals(wanted));
            if (!nameMatches) {
                continue;
            }
            if (r.stateHint() != null) {
                String[] adm = indexer.administrativeParents(e);
                String stateName = adm[0] == null ? null : places.findByWikidataId(adm[0]).map(PlaceRow::name).orElse(null);
                if (stateName == null || !places.normalize(stateName).equals(places.normalize(r.stateHint()))) {
                    continue;
                }
            }
            out.add(new Candidate(sc.id(), sc.label(), sc.description()));
        }
        return out;
    }
}
