package io.destinationintel.place;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Destination search over names and aliases using exact, prefix, word-prefix and trigram
 * matching. Only real indexed places are returned; an empty result is reported as such.
 */
@Service
public class SearchService {

    public record SearchHit(
            UUID id,
            String slug,
            String name,
            String kind,
            String description,
            String matchedAlias,
            String matchType,
            double score,
            String stateName,
            String districtName,
            String parentCityName,
            String parentCitySlug,
            String coverageStatus,
            Double latitude,
            Double longitude) {

        /** A guide exists (possibly limited) when coverage is beyond "indexed". */
        @JsonProperty("hasGuide")
        public boolean hasGuide() {
            return coverageStatus != null && !coverageStatus.equals("indexed");
        }

        /** Where the result should link: neighbourhoods open their city's guide. */
        @JsonProperty("guideSlug")
        public String guideSlug() {
            return parentCitySlug != null && "neighbourhood".equals(kind) ? parentCitySlug : slug;
        }
    }

    public record SearchResult(String query, List<SearchHit> results) {}

    private final JdbcClient jdbc;

    public SearchService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public SearchResult search(String rawQuery, int limit) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.length() < 2) {
            return new SearchResult(query, List.of());
        }
        List<SearchHit> hits = jdbc.sql("""
                        WITH q AS (SELECT idi_normalize(:q) AS nq),
                        candidates AS (
                            SELECT a.place_id, a.alias, a.normalized,
                                   CASE
                                       WHEN a.normalized = q.nq THEN 1.0
                                       WHEN a.normalized LIKE q.nq || '%' THEN 0.9
                                       WHEN a.normalized LIKE '% ' || q.nq || '%' THEN 0.75
                                       ELSE 0
                                   END AS prefix_score,
                                   greatest(similarity(a.normalized, q.nq), word_similarity(q.nq, a.normalized) * 0.9) AS sim
                            FROM place_alias a, q
                            WHERE q.nq <> ''
                              AND (a.normalized % q.nq OR a.normalized LIKE q.nq || '%'
                                   OR a.normalized LIKE '% ' || q.nq || '%' OR q.nq <% a.normalized)
                        ),
                        best AS (
                            SELECT DISTINCT ON (place_id) place_id, alias, normalized, prefix_score, sim,
                                   greatest(prefix_score, sim) AS score
                            FROM candidates
                            ORDER BY place_id, greatest(prefix_score, sim) DESC, length(alias)
                        )
                        SELECT p.id, p.slug, p.name, p.kind, p.description,
                               CASE WHEN idi_normalize(p.name) = b.normalized THEN NULL ELSE b.alias END AS matched_alias,
                               CASE WHEN b.prefix_score = 1.0 THEN 'exact'
                                    WHEN b.prefix_score >= 0.75 THEN 'prefix'
                                    ELSE 'fuzzy' END AS match_type,
                               b.score,
                               st.name AS state_name,
                               di.name AS district_name,
                               city.name AS parent_city_name,
                               city.slug AS parent_city_slug,
                               dp.coverage_status,
                               p.latitude, p.longitude
                        FROM best b
                        JOIN place p ON p.id = b.place_id
                        LEFT JOIN destination_profile dp ON dp.place_id = p.id
                        LEFT JOIN LATERAL (
                            SELECT pp.name FROM place_relation r JOIN place pp ON pp.id = r.parent_id
                            WHERE r.child_id = p.id AND r.relation = 'in_state' ORDER BY pp.name LIMIT 1) st ON true
                        LEFT JOIN LATERAL (
                            SELECT pp.name FROM place_relation r JOIN place pp ON pp.id = r.parent_id
                            WHERE r.child_id = p.id AND r.relation = 'in_district' ORDER BY pp.name LIMIT 1) di ON true
                        LEFT JOIN LATERAL (
                            SELECT pp.name, pp.slug FROM place_relation r JOIN place pp ON pp.id = r.parent_id
                            WHERE r.child_id = p.id AND r.relation = 'part_of_city' LIMIT 1) city ON true
                        WHERE p.lifecycle_status = 'active' AND p.is_destination AND b.score >= 0.3
                        ORDER BY b.score DESC,
                                 CASE dp.coverage_status WHEN 'well_covered' THEN 4 WHEN 'usable' THEN 3
                                      WHEN 'needs_refresh' THEN 3 WHEN 'building' THEN 2 ELSE 1 END DESC,
                                 coalesce(p.sitelink_count, 0) DESC,
                                 coalesce(p.population, 0) DESC,
                                 p.name
                        LIMIT :limit
                        """)
                .param("q", query)
                .param("limit", Math.max(1, Math.min(limit, 25)))
                .query(SearchHit.class)
                .list();
        return new SearchResult(query, hits);
    }
}
