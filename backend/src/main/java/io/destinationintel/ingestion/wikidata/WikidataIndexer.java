package io.destinationintel.ingestion.wikidata;

import io.destinationintel.config.AppProperties;
import io.destinationintel.ingestion.JobRepository;
import io.destinationintel.place.PlaceRepository;
import io.destinationintel.place.PlaceRepository.PlaceUpsert;
import io.destinationintel.place.PlaceRow;
import io.destinationintel.source.SourceRepository;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Builds the nationwide destination index from Wikidata (CC0): states and union
 * territories, districts, every Indian place with an English Wikivoyage article, and large
 * settlements. Names, aliases in Indian languages and administrative relations are stored.
 * All writes are upserts keyed by Wikidata id, so re-running is idempotent.
 */
@Service
public class WikidataIndexer {

    private static final Logger log = LoggerFactory.getLogger(WikidataIndexer.class);

    public static final String STATE = "Q12443800";
    public static final String UNION_TERRITORY = "Q467745";
    public static final String DISTRICT = "Q1149652";
    public static final String INDIA = "Q668";

    static final String STATES_QUERY = """
            SELECT ?item (SAMPLE(?c) AS ?coord) (MAX(?p) AS ?pop) (SAMPLE(?iso) AS ?isoCode) (SAMPLE(?type) AS ?t) WHERE {
              VALUES ?type { wd:Q12443800 wd:Q467745 }
              ?item wdt:P31 ?type .
              FILTER NOT EXISTS { ?item wdt:P576 ?dissolved }
              OPTIONAL { ?item wdt:P625 ?c FILTER(isLiteral(?c)) }
              OPTIONAL { ?item wdt:P1082 ?p }
              OPTIONAL { ?item wdt:P300 ?iso }
            } GROUP BY ?item""";

    static final String DISTRICTS_QUERY = """
            SELECT ?item (SAMPLE(?c) AS ?coord) (MAX(?p) AS ?pop) (SAMPLE(?state) AS ?st) WHERE {
              ?item wdt:P31 wd:Q1149652 .
              FILTER NOT EXISTS { ?item wdt:P576 ?dissolved }
              OPTIONAL { ?item wdt:P625 ?c FILTER(isLiteral(?c)) }
              OPTIONAL { ?item wdt:P1082 ?p }
              OPTIONAL { ?item p:P131 ?stS . ?stS ps:P131 ?hopS . FILTER NOT EXISTS { ?stS pq:P582 [] } ?hopS wdt:P131* ?state . ?state wdt:P31 ?stType . VALUES ?stType { wd:Q12443800 wd:Q467745 }
                          FILTER NOT EXISTS { ?state wdt:P576 ?stateDissolved } }
            } GROUP BY ?item""";

    static final String WIKIVOYAGE_QUERY = """
            SELECT ?item ?wv (SAMPLE(?c) AS ?coord) (MAX(?p) AS ?pop) (GROUP_CONCAT(DISTINCT ?tl; separator="|") AS ?types)
                   (SAMPLE(?state) AS ?st) (SAMPLE(?district) AS ?di) WHERE {
              ?wv schema:about ?item ; schema:isPartOf <https://en.wikivoyage.org/> .
              ?item wdt:P17 wd:Q668 .
              OPTIONAL { ?item wdt:P625 ?c FILTER(isLiteral(?c)) }
              OPTIONAL { ?item wdt:P1082 ?p }
              OPTIONAL { ?item wdt:P31 ?t . ?t rdfs:label ?tl FILTER(lang(?tl) = "en") }
              OPTIONAL { ?item p:P131 ?stS . ?stS ps:P131 ?hopS . FILTER NOT EXISTS { ?stS pq:P582 [] } ?hopS wdt:P131* ?state . ?state wdt:P31 ?stType . VALUES ?stType { wd:Q12443800 wd:Q467745 }
                          FILTER NOT EXISTS { ?state wdt:P576 ?stateDissolved } }
              OPTIONAL { ?item p:P131 ?stD . ?stD ps:P131 ?hopD . FILTER NOT EXISTS { ?stD pq:P582 [] } ?hopD wdt:P131* ?district . ?district wdt:P31 wd:Q1149652
                          FILTER NOT EXISTS { ?district wdt:P576 ?districtDissolved } }
            } GROUP BY ?item ?wv""";

    static final String LARGE_SETTLEMENTS_QUERY = """
            SELECT ?item (SAMPLE(?c) AS ?coord) (MAX(?p) AS ?pop) (GROUP_CONCAT(DISTINCT ?tl; separator="|") AS ?types)
                   (SAMPLE(?state) AS ?st) (SAMPLE(?district) AS ?di) WHERE {
              VALUES ?t { wd:Q515 wd:Q1549591 wd:Q3957 wd:Q200250 wd:Q174844 wd:Q1637706 wd:Q15284 wd:Q486972 }
              ?item wdt:P31 ?t ; wdt:P17 wd:Q668 ; wdt:P1082 ?p ; wdt:P625 ?c . FILTER(isLiteral(?c))
              FILTER(?p >= %d)
              ?t rdfs:label ?tl FILTER(lang(?tl) = "en")
              OPTIONAL { ?item p:P131 ?stS . ?stS ps:P131 ?hopS . FILTER NOT EXISTS { ?stS pq:P582 [] } ?hopS wdt:P131* ?state . ?state wdt:P31 ?stType . VALUES ?stType { wd:Q12443800 wd:Q467745 }
                          FILTER NOT EXISTS { ?state wdt:P576 ?stateDissolved } }
              OPTIONAL { ?item p:P131 ?stD . ?stD ps:P131 ?hopD . FILTER NOT EXISTS { ?stD pq:P582 [] } ?hopD wdt:P131* ?district . ?district wdt:P31 wd:Q1149652
                          FILTER NOT EXISTS { ?district wdt:P576 ?districtDissolved } }
              FILTER NOT EXISTS { ?item wdt:P31 wd:Q1149652 }
            } GROUP BY ?item""";

    /** One candidate place assembled from SPARQL before entity details are fetched. */
    record Candidate(String qid, String wikivoyageTitle, Double latitude, Double longitude, Long population,
                     List<String> typeLabels, String fixedKind, String stateQid, String districtQid, String isoCode) {}

    private final WikidataClient client;
    private final PlaceRepository places;
    private final SourceRepository sources;
    private final JobRepository jobs;
    private final TransactionTemplate tx;
    private final JdbcClient jdbc;
    private final long minPopulation;
    private final IndexConsistency consistency;

    public WikidataIndexer(WikidataClient client, PlaceRepository places, SourceRepository sources, JobRepository jobs,
                           TransactionTemplate tx, JdbcClient jdbc, AppProperties props, IndexConsistency consistency) {
        this.consistency = consistency;
        this.client = client;
        this.places = places;
        this.sources = sources;
        this.jobs = jobs;
        this.tx = tx;
        this.jdbc = jdbc;
        this.minPopulation = props.ingestion().minPopulationForIndex();
    }

    /** Per-run counters beyond the standard sync-run fields. */
    public static final class Counts {
        final java.util.concurrent.atomic.AtomicLong aliasesAdded = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong relationsSet = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong removed = new java.util.concurrent.atomic.AtomicLong();

        public Map<String, Object> toMap() {
            return Map.of("aliasesAdded", aliasesAdded.get(), "relationsSet", relationsSet.get(),
                    "placesRemovedAfterValidation", removed.get());
        }
    }

    public Map<String, Object> indexAll(io.destinationintel.ingestion.run.SyncRunRecorder.Run run, UUID jobId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        Counts counts = new Counts();
        Map<String, String> stateNames = new HashMap<>();

        List<Candidate> states = new ArrayList<>();
        for (Map<String, String> r : client.select(STATES_QUERY)) {
            run.fetched.incrementAndGet();
            String qid = WikidataClient.qid(r.get("item"));
            if (qid == null) {
                run.reject("invalid_entity_id");
                continue;
            }
            double[] pt = WikidataClient.point(r.get("coord"));
            String kind = r.getOrDefault("t", "").endsWith(UNION_TERRITORY) ? "union_territory" : "state";
            states.add(new Candidate(qid, null, pt == null ? null : pt[0], pt == null ? null : pt[1],
                    parseLong(r.get("pop")), List.of(), kind, null, null, r.get("isoCode")));
        }
        Set<String> seenAll = new HashSet<>();
        states.forEach(c -> seenAll.add(c.qid()));
        stats.put("states", upsertAll(states, stateNames, jobId, run, counts));
        states.forEach(c -> stateNames.putIfAbsent(c.qid(), nameOf(c.qid())));

        List<Candidate> districts = new ArrayList<>();
        for (Map<String, String> r : client.select(DISTRICTS_QUERY)) {
            run.fetched.incrementAndGet();
            String qid = WikidataClient.qid(r.get("item"));
            if (qid == null) {
                run.reject("invalid_entity_id");
                continue;
            }
            double[] pt = WikidataClient.point(r.get("coord"));
            districts.add(new Candidate(qid, null, pt == null ? null : pt[0], pt == null ? null : pt[1],
                    parseLong(r.get("pop")), List.of(), "district", WikidataClient.qid(r.get("st")), null, null));
        }
        districts.forEach(c -> seenAll.add(c.qid()));
        stats.put("districts", upsertAll(districts, stateNames, jobId, run, counts));

        List<Candidate> wv = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, String> r : client.select(WIKIVOYAGE_QUERY)) {
            run.fetched.incrementAndGet();
            String qid = WikidataClient.qid(r.get("item"));
            if (qid == null) {
                run.reject("invalid_entity_id");
                continue;
            }
            if (!seen.add(qid)) {
                continue;
            }
            String title = wikivoyageTitle(r.get("wv"));
            double[] pt = WikidataClient.point(r.get("coord"));
            List<String> types = List.of(r.getOrDefault("types", "").split("\\|"));
            wv.add(new Candidate(qid, title, pt == null ? null : pt[0], pt == null ? null : pt[1],
                    parseLong(r.get("pop")), types, null, WikidataClient.qid(r.get("st")),
                    WikidataClient.qid(r.get("di")), null));
        }
        stats.put("wikivoyageDestinations", upsertAll(wv, stateNames, jobId, run, counts));

        List<Candidate> large = new ArrayList<>();
        for (Map<String, String> r : client.select(LARGE_SETTLEMENTS_QUERY.formatted(minPopulation))) {
            run.fetched.incrementAndGet();
            String qid = WikidataClient.qid(r.get("item"));
            if (qid == null) {
                run.reject("invalid_entity_id");
                continue;
            }
            if (seen.contains(qid)) {
                continue;
            }
            seen.add(qid);
            double[] pt = WikidataClient.point(r.get("coord"));
            large.add(new Candidate(qid, null, pt == null ? null : pt[0], pt == null ? null : pt[1],
                    parseLong(r.get("pop")), List.of(r.getOrDefault("types", "").split("\\|")), null,
                    WikidataClient.qid(r.get("st")), WikidataClient.qid(r.get("di")), null));
        }
        stats.put("largeSettlements", upsertAll(large, stateNames, jobId, run, counts));
        stats.put("cityDistrictLinks", tx.execute(s -> linkWikivoyageDistricts()));
        seenAll.addAll(seen);
        stats.put("consistency", consistency.run(seenAll, run, jobId));
        stats.putAll(counts.toMap());
        return stats;
    }

    /** Fetches entity details for candidates in batches of 50 and upserts them. */
    int upsertAll(List<Candidate> candidates, Map<String, String> stateNames, UUID jobId, io.destinationintel.ingestion.run.SyncRunRecorder.Run run, Counts counts) {
        int written = 0;
        for (int i = 0; i < candidates.size(); i += 50) {
            List<Candidate> batch = candidates.subList(i, Math.min(candidates.size(), i + 50));
            JsonNode entities = client.entities(batch.stream().map(Candidate::qid).toList(),
                    "labels|aliases|descriptions|sitelinks");
            Integer n = tx.execute(status -> {
                int count = 0;
                for (Candidate c : batch) {
                    run.parsed.incrementAndGet();
                    JsonNode node = entities.path(c.qid());
                    if (node.isMissingNode() || node.has("missing")) {
                        run.reject("entity_missing");
                        continue;
                    }
                    EntityInfo info = EntityInfo.parse(node);
                    if (upsert(c, info, stateNames, run, counts).isPresent()) {
                        count++;
                    }
                }
                return count;
            });
            written += n == null ? 0 : n;
            if (jobId != null) {
                jobs.heartbeat(jobId);
            }
        }
        log.info("Upserted {} of {} Wikidata candidates", written, candidates.size());
        return written;
    }

    Optional<UUID> upsert(Candidate c, EntityInfo info, Map<String, String> stateNames, io.destinationintel.ingestion.run.SyncRunRecorder.Run run, Counts counts) {
        String kind = c.fixedKind() != null ? c.fixedKind()
                : PlaceKindClassifier.classify(c.typeLabels(), c.wikivoyageTitle()).orElse(null);
        if (kind == null) {
            reject(run, counts, c.qid(), "not_a_destination");
            return Optional.empty();
        }
        Double lat = c.latitude() != null ? c.latitude() : info.latitude();
        Double lon = c.longitude() != null ? c.longitude() : info.longitude();
        if (lat != null && lon != null && !io.destinationintel.poi.PoiNormalizer.insideIndia(lat, lon)) {
            reject(run, counts, c.qid(), "outside_india");
            return Optional.empty();
        }
        boolean existed = places.findByWikidataId(c.qid()).isPresent();
        String wvTitle = c.wikivoyageTitle() != null ? c.wikivoyageTitle() : info.wikivoyageTitle();
        String name = info.englishLabel();
        if (name == null || name.isBlank()) {
            name = wvTitle != null ? wvTitle.replace('_', ' ') : info.localLabels().values().stream().findFirst().orElse(c.qid());
        }
        String stateName = c.stateQid() == null ? null : stateNames.computeIfAbsent(c.stateQid(), this::nameOf);
        UUID id = places.upsertFromWikidata(new PlaceUpsert(c.qid(), name, kind, info.englishDescription(), lat, lon,
                c.population() != null ? c.population() : info.population(),
                wvTitle, c.isoCode(), info.wikipediaCount(), true, stateName));
        jdbc.sql("UPDATE place SET lifecycle_status = 'active', is_destination = true WHERE id = :id AND lifecycle_status = 'removed'")
                .param("id", id).update();
        if (run != null) {
            (existed ? run.updated : run.inserted).incrementAndGet();
        }
        int aliases = places.addAlias(id, name, "en", "label", "wikidata");
        for (String alias : info.englishAliases()) {
            aliases += places.addAlias(id, alias, "en", "alternate", "wikidata");
        }
        for (Map.Entry<String, String> l : info.localLabels().entrySet()) {
            aliases += places.addAlias(id, l.getValue(), l.getKey(), isLatin(l.getValue()) ? "alternate" : "local_script", "wikidata");
        }
        if (wvTitle != null && !wvTitle.equalsIgnoreCase(name)) {
            aliases += places.addAlias(id, wvTitle.replace('_', ' '), "en", "source_title", "wikivoyage-en");
        }
        if (counts != null) {
            counts.aliasesAdded.addAndGet(aliases);
        }
        // Current Wikidata relations replace earlier ones, so corrections upstream propagate.
        if (c.stateQid() != null && !c.stateQid().equals(c.qid())) {
            places.findByWikidataId(c.stateQid()).ifPresent(st -> {
                places.deleteRelationsFrom(id, "in_state");
                places.addRelation(id, st.id(), "in_state", "wikidata");
                if (counts != null) {
                    counts.relationsSet.incrementAndGet();
                }
            });
        }
        if (c.districtQid() != null && !c.districtQid().equals(c.qid())) {
            places.findByWikidataId(c.districtQid()).ifPresent(d -> {
                places.deleteRelationsFrom(id, "in_district");
                places.addRelation(id, d.id(), "in_district", "wikidata");
                if (counts != null) {
                    counts.relationsSet.incrementAndGet();
                }
            });
        }
        jdbc.sql("""
                        INSERT INTO place_source_sync (place_id, source_id, external_id, last_revision, last_synced_at, last_status)
                        VALUES (:id, 'wikidata', :qid, :rev, now(), 'succeeded')
                        ON CONFLICT (place_id, source_id, external_id) DO UPDATE SET
                            last_revision = EXCLUDED.last_revision, last_synced_at = now(), last_status = 'succeeded',
                            last_error = NULL
                        """)
                .param("id", id).param("qid", c.qid()).param("rev", info.lastRevisionId()).update();
        return Optional.of(id);
    }

    /**
     * Rejects a candidate and, if a place from an earlier run exists for it, takes it out of the
     * index (soft removal) unless it already carries guide content.
     */
    private void reject(io.destinationintel.ingestion.run.SyncRunRecorder.Run run, Counts counts, String qid, String reason) {
        if (run != null) {
            run.reject(reason);
        }
        int removed = places.softRemoveIfUnused(qid);
        if (removed > 0) {
            if (counts != null) {
                counts.removed.incrementAndGet();
            }
            if (run != null) {
                run.deleted.incrementAndGet();
            }
        }
    }

    /** Links Wikivoyage district articles ("Bengaluru/North") to their city. */
    int linkWikivoyageDistricts() {
        List<PlaceRow> districts = places.findWikivoyageDistrictArticles();
        int linked = 0;
        for (PlaceRow d : districts) {
            String parentTitle = d.wikivoyageTitle().substring(0, d.wikivoyageTitle().lastIndexOf('/'));
            Optional<PlaceRow> parent = places.findByWikivoyageTitle(parentTitle);
            if (parent.isPresent()) {
                places.addRelation(d.id(), parent.get().id(), "part_of_city", "wikivoyage-en");
                linked++;
            }
        }
        return linked;
    }

    /**
     * Indexes specific items (used when a reader requests a destination that is not yet in the
     * index). Uses only the Action API: the item, its types and its administrative parents.
     */
    public List<UUID> indexItems(Collection<String> qids) {
        List<UUID> out = new ArrayList<>();
        for (String qid : qids) {
            JsonNode entities = client.entities(List.of(qid), "labels|aliases|descriptions|sitelinks|claims");
            JsonNode node = entities.path(qid);
            if (node.isMissingNode() || node.has("missing")) {
                continue;
            }
            EntityInfo info = EntityInfo.parse(node);
            if (!info.countries().contains(INDIA)) {
                continue;
            }
            List<String> typeLabels = labels(info.instanceOf());
            String[] adm = administrativeParents(info);
            Map<String, String> stateNames = new HashMap<>();
            if (adm[0] != null) {
                ensureIndexed(adm[0], "state");
            }
            if (adm[1] != null) {
                ensureIndexed(adm[1], "district");
            }
            String fixed = info.instanceOf().contains(STATE) ? "state"
                    : info.instanceOf().contains(UNION_TERRITORY) ? "union_territory"
                    : info.instanceOf().contains(DISTRICT) ? "district" : null;
            Candidate c = new Candidate(qid, info.wikivoyageTitle(), info.latitude(), info.longitude(),
                    info.population(), typeLabels, fixed, adm[0], adm[1], null);
            Optional<UUID> id = tx.execute(s -> upsert(c, info, stateNames, null, null));
            id.ifPresent(out::add);
        }
        return out;
    }

    private void ensureIndexed(String qid, String expectedKind) {
        if (places.findByWikidataId(qid).isPresent()) {
            return;
        }
        JsonNode node = client.entities(List.of(qid), "labels|aliases|descriptions|sitelinks|claims").path(qid);
        if (node.isMissingNode()) {
            return;
        }
        EntityInfo info = EntityInfo.parse(node);
        if (info.dissolved()) {
            return;
        }
        String kind = expectedKind.equals("state") && info.instanceOf().contains(UNION_TERRITORY) ? "union_territory" : expectedKind;
        Candidate c = new Candidate(qid, info.wikivoyageTitle(), info.latitude(), info.longitude(), info.population(),
                List.of(), kind, null, null, null);
        tx.execute(s -> upsert(c, info, new HashMap<>(), null, null));
    }

    /**
     * Walks P131 upwards (breadth-first, bounded) to find the state/UT and district. Statements
     * with an end date are skipped (see {@link EntityInfo}), so a town whose district was split
     * resolves to its current district.
     */
    public String[] administrativeParents(EntityInfo start) {
        String state = null;
        String district = null;
        Deque<String> queue = new ArrayDeque<>(start.locatedIn());
        Set<String> visited = new HashSet<>();
        int fetched = 0;
        while (!queue.isEmpty() && fetched < 12 && (state == null || district == null)) {
            List<String> batch = new ArrayList<>();
            while (!queue.isEmpty() && batch.size() < 20) {
                String q = queue.poll();
                if (visited.add(q)) {
                    batch.add(q);
                }
            }
            if (batch.isEmpty()) {
                break;
            }
            JsonNode entities = client.entities(batch, "claims");
            fetched++;
            for (String q : batch) {
                EntityInfo e = EntityInfo.parse(entities.path(q));
                if (e.dissolved()) {
                    // Historical entities (e.g. "Mysore State") are not current administrative parents.
                    queue.addAll(e.locatedIn());
                    continue;
                }
                if (state == null && (e.instanceOf().contains(STATE) || e.instanceOf().contains(UNION_TERRITORY))) {
                    state = q;
                }
                if (district == null && e.instanceOf().contains(DISTRICT)) {
                    district = q;
                }
                queue.addAll(e.locatedIn());
            }
        }
        return new String[] {state, district};
    }

    private List<String> labels(List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        JsonNode entities = client.entities(ids.subList(0, Math.min(50, ids.size())), "labels");
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            String label = entities.path(id).path("labels").path("en").path("value").asString(null);
            if (label != null) {
                out.add(label);
            }
        }
        return out;
    }

    private String nameOf(String qid) {
        return places.findByWikidataId(qid).map(PlaceRow::name).orElse(null);
    }

    static String wikivoyageTitle(String url) {
        if (url == null) {
            return null;
        }
        String prefix = "https://en.wikivoyage.org/wiki/";
        String t = url.startsWith(prefix) ? url.substring(prefix.length()) : url;
        return java.net.URLDecoder.decode(t, java.nio.charset.StandardCharsets.UTF_8).replace('_', ' ');
    }

    static boolean isLatin(String s) {
        return s.codePoints().filter(Character::isLetter)
                .allMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN);
    }

    private static Long parseLong(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return (long) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public SourceRepository sources() {
        return sources;
    }
}
