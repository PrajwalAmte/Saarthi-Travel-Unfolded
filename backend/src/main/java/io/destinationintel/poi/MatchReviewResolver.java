package io.destinationintel.poi;

import io.destinationintel.common.Json;
import io.destinationintel.poi.MatchEvidence.Decision;
import io.destinationintel.poi.MatchEvidence.Outcome;
import io.destinationintel.poi.MatchEvidence.Side;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves open match reviews with {@link MatchEvidence}. For each record, every open candidate is
 * decided; if any is the same place, the record joins the candidate with the strongest evidence and
 * its other candidates are closed. A record whose candidates are all distinct becomes a place of its
 * own. Every decision stores the rule that made it ({@code poi_match_review.resolution}).
 */
@Component
public class MatchReviewResolver {

    /** Strength of evidence for "same place", strongest first; used when several candidates qualify. */
    private static final List<String> SAME_STRENGTH = List.of("shared_phone", "shared_website", "same_name_same_spot",
            "same_name_nearby", "spelling_variant", "name_contains_other");

    public record Counts(int records, int merged, int distinct, int created, Map<String, Integer> byRule) {}

    private record Pair(UUID reviewId, UUID recordId, UUID candidateId, String recordName, String recordGroup,
                        String recordAttrs, String candidateName, String candidateGroup, String candidateAttrs,
                        double distanceM) {}

    private final JdbcClient jdbc;
    private final Json json;

    public MatchReviewResolver(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Resolves every open review, or those of one source's records when {@code sourceId} is given. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Counts resolveOpen(String sourceId) {
        List<Pair> pairs = jdbc.sql("""
                        SELECT m.id AS review_id, r.id AS record_id, p.id AS candidate_id, r.name AS record_name,
                               r.category_group AS record_group, r.attributes::text AS record_attrs,
                               p.name AS candidate_name, p.category_group AS candidate_group,
                               (SELECT jsonb_agg(o.attributes)::text FROM poi_source_record o
                                WHERE o.poi_id = p.id AND o.deleted_at IS NULL) AS candidate_attrs,
                               ST_Distance(r.geog, p.geog) AS distance_m
                        FROM poi_match_review m
                        JOIN poi_source_record r ON r.id = m.record_id AND r.deleted_at IS NULL
                        JOIN poi p ON p.id = m.candidate_poi_id AND p.status = 'active'
                        WHERE m.status = 'open' AND (CAST(:s AS text) IS NULL OR r.source_id = :s)
                        ORDER BY r.id, m.id
                        """).param("s", sourceId).query(Pair.class).list();
        Map<UUID, List<Pair>> byRecord = new LinkedHashMap<>();
        for (Pair p : pairs) {
            byRecord.computeIfAbsent(p.recordId(), k -> new ArrayList<>()).add(p);
        }
        Map<String, Integer> byRule = new TreeMap<>();
        int merged = 0;
        int distinct = 0;
        List<UUID> becomeNew = new ArrayList<>();
        for (Map.Entry<UUID, List<Pair>> e : byRecord.entrySet()) {
            Pair best = null;
            Decision bestDecision = null;
            Map<UUID, Decision> decisions = new LinkedHashMap<>();
            for (Pair p : e.getValue()) {
                Side record = side(p.recordName(), p.recordGroup(), List.of(json.map(p.recordAttrs())));
                Side candidate = side(p.candidateName(), p.candidateGroup(), candidateAttrs(p.candidateAttrs()));
                Decision d = MatchEvidence.decide(record, candidate, p.distanceM());
                decisions.put(p.reviewId(), d);
                if (d.outcome() == Outcome.SAME && (best == null || stronger(d, p, bestDecision, best))) {
                    best = p;
                    bestDecision = d;
                }
            }
            for (Pair p : e.getValue()) {
                Decision d = decisions.get(p.reviewId());
                boolean chosen = best != null && p.reviewId().equals(best.reviewId());
                String status = chosen ? "merged" : "distinct";
                String resolution = chosen || d.outcome() == Outcome.DISTINCT ? d.rule() : "another_candidate_matched_better";
                jdbc.sql("""
                                UPDATE poi_match_review SET status = :st, resolution = :res, resolved_at = now()
                                WHERE id = :id AND status = 'open'
                                """).param("st", status).param("res", resolution).param("id", p.reviewId()).update();
                byRule.merge((chosen ? "same:" : "distinct:") + resolution, 1, Integer::sum);
            }
            if (best != null) {
                jdbc.sql("""
                                UPDATE poi_source_record SET poi_id = :p, match_confidence = 'high', match_method = :m, match_score = NULL
                                WHERE id = :r AND poi_id IS NULL
                                """).param("p", best.candidateId()).param("m", "evidence:" + bestDecision.rule())
                        .param("r", e.getKey()).update();
                merged++;
            } else {
                becomeNew.add(e.getKey());
                distinct++;
            }
        }
        int created = createPlaces(becomeNew);
        return new Counts(byRecord.size(), merged, distinct, created, byRule);
    }

    private record EntryPair(UUID reviewId, UUID entryId, UUID poiId, String entryAddress, String recordAddress) {}

    private static final Set<String> ADDRESS_NOISE = Set.of("road", "rd", "street", "st", "opp", "opposite", "near", "nr",
            "main", "cross", "ft", "foot", "feet", "lane", "behind", "next", "to", "the", "of", "and", "no", "bengaluru",
            "bangalore", "india", "junction", "jn", "circle", "nagar", "layout", "stage", "phase", "block", "sector");

    /**
     * Resolves guide entries that share a name with several mapped places and have no coordinates
     * (typically chains with branches). An entry is linked only when the words of its address appear in
     * exactly one candidate's address; otherwise it stays unlinked, since choosing a branch would be a guess.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String, Integer> resolveEntryLinks() {
        List<EntryPair> pairs = jdbc.sql("""
                        SELECT m.id AS review_id, e.id AS entry_id, r.poi_id,
                               (SELECT f.value_text FROM fact f WHERE f.entry_id = e.id AND f.kind = 'address'
                                  AND f.status = 'published' LIMIT 1) AS entry_address,
                               r.attributes ->> 'address' AS record_address
                        FROM poi_match_review m JOIN guide_entry e ON e.id = m.candidate_entry_id
                        JOIN poi_source_record r ON r.id = m.record_id
                        WHERE m.status = 'open' ORDER BY e.id, m.id
                        """).query(EntryPair.class).list();
        Map<UUID, List<EntryPair>> byEntry = new LinkedHashMap<>();
        pairs.forEach(p -> byEntry.computeIfAbsent(p.entryId(), k -> new ArrayList<>()).add(p));
        int linked = 0;
        int left = 0;
        for (List<EntryPair> group : byEntry.values()) {
            List<String> words = addressWords(group.get(0).entryAddress());
            List<EntryPair> matching = words.isEmpty() || String.join("", words).length() < 6 ? List.of()
                    : group.stream().filter(p -> p.recordAddress() != null && p.poiId() != null
                            && addressWords(p.recordAddress()).containsAll(words)).toList();
            EntryPair chosen = matching.stream().map(EntryPair::poiId).distinct().count() == 1 ? matching.get(0) : null;
            for (EntryPair p : group) {
                boolean isChosen = chosen != null && p.poiId() != null && p.poiId().equals(chosen.poiId());
                jdbc.sql("""
                                UPDATE poi_match_review SET status = :st, resolution = :res, resolved_at = now()
                                WHERE id = :id AND status = 'open'
                                """).param("st", isChosen ? "merged" : "distinct")
                        .param("res", isChosen ? "shared_address" : chosen != null ? "another_branch_matched_the_address"
                                : "several_branches_no_location").param("id", p.reviewId()).update();
            }
            if (chosen != null) {
                linked += jdbc.sql("UPDATE guide_entry SET poi_id = :p, updated_at = now() WHERE id = :e AND poi_id IS NULL")
                        .param("p", chosen.poiId()).param("e", chosen.entryId()).update();
            } else {
                left++;
            }
        }
        return Map.of("entries", byEntry.size(), "linkedByAddress", linked, "leftUnlinked", left);
    }

    static List<String> addressWords(String address) {
        List<String> out = new ArrayList<>();
        if (address == null) {
            return out;
        }
        for (String t : address.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (t.length() > 2 && !ADDRESS_NOISE.contains(t) && !t.matches("\\d+")) {
                out.add(t);
            }
        }
        return out;
    }

    private static boolean stronger(Decision d, Pair p, Decision bestDecision, Pair best) {
        int a = SAME_STRENGTH.indexOf(d.rule());
        int b = SAME_STRENGTH.indexOf(bestDecision.rule());
        return a < b || (a == b && p.distanceM() < best.distanceM());
    }

    /** Records whose candidates were all different places become places of their own. */
    private int createPlaces(List<UUID> recordIds) {
        int created = 0;
        for (int i = 0; i < recordIds.size(); i += 500) {
            List<UUID> batch = recordIds.subList(i, Math.min(recordIds.size(), i + 500));
            created += jdbc.sql("""
                            WITH fresh AS (
                                INSERT INTO poi (name, category, category_group, latitude, longitude, wikidata_id, primary_source_id, seed_record_id)
                                SELECT r.name, r.category, r.category_group, r.latitude, r.longitude, r.attributes ->> 'wikidata',
                                       r.source_id, r.id
                                FROM poi_source_record r
                                WHERE r.id IN (:ids) AND r.poi_id IS NULL AND r.deleted_at IS NULL
                                RETURNING id, seed_record_id)
                            UPDATE poi_source_record r SET poi_id = f.id, match_confidence = 'new', match_method = 'created_after_review'
                            FROM fresh f WHERE r.id = f.seed_record_id
                            """).param("ids", batch).update();
        }
        return created;
    }

    private List<Map<String, Object>> candidateAttrs(String jsonArray) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (jsonArray == null) {
            return out;
        }
        for (Object o : json.list(jsonArray)) {
            if (o instanceof Map<?, ?> m) {
                Map<String, Object> copy = new LinkedHashMap<>();
                m.forEach((k, v) -> copy.put(String.valueOf(k), v));
                out.add(copy);
            }
        }
        return out;
    }

    private static Side side(String name, String group, List<Map<String, Object>> attributeSets) {
        Set<String> phones = new HashSet<>();
        Set<String> websites = new HashSet<>();
        Set<String> brands = new HashSet<>();
        for (Map<String, Object> a : attributeSets) {
            if (a.get("phone") != null) {
                phones.add(String.valueOf(a.get("phone")));
            }
            if (a.get("website") != null) {
                websites.add(String.valueOf(a.get("website")));
            }
            if (a.get("brand") != null) {
                brands.add(String.valueOf(a.get("brand")));
            }
        }
        return new Side(name, group, phones, websites, brands);
    }
}
