package io.destinationintel.place;

import io.destinationintel.common.ApiException;
import io.destinationintel.common.Json;
import io.destinationintel.guide.CoverageService;
import io.destinationintel.guide.GuideDtos.FactView;
import io.destinationintel.guide.GuideRepository;
import io.destinationintel.guide.GuideService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import io.destinationintel.common.PageResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class DestinationService {

    public record ParentRef(String relation, String slug, String name, String kind) {}

    public record AliasView(String alias, String language, String type) {}

    public record ImageView(String url, Integer width, Integer height, String pageUrl, String artist,
                            String licenceName, String licenceUrl, String sourceName) {}

    public record CoverageCheck(String rule, boolean passed, String detail) {}

    public record CoverageView(String status, String label, String explanation, List<CoverageCheck> checks,
                               Map<String, Object> stats, OffsetDateTime computedAt, OffsetDateTime graduatedAt) {}

    public record SourceRef(String id, String name, String category, String licenceName, String licenceUrl,
                            String attribution, String baseUrl) {}

    public record ChildDestination(String slug, String name, String kind, String coverageStatus) {}

    public record ClosedEntry(UUID id, String section, String title, String reason, OffsetDateTime reportedAt) {}

    public record DestinationDetail(
            UUID id,
            String slug,
            String name,
            String kind,
            String description,
            Double latitude,
            Double longitude,
            Long population,
            String wikidataId,
            String wikidataUrl,
            String wikivoyageUrl,
            List<ParentRef> parents,
            List<AliasView> aliases,
            ImageView image,
            CoverageView coverage,
            FactView summary,
            Map<String, Integer> sectionCounts,
            List<SourceRef> sourcesUsed,
            OffsetDateTime lastSourceSyncAt,
            boolean guideAvailable,
            List<ChildDestination> childDestinations,
            List<ClosedEntry> recentlyClosed,
            List<io.destinationintel.ai.AiContent.InShortItem> inShort,
            int guidedPlaces) {}

    /** States and union territories are hubs: their guide is the set of guides for the places in them. */
    public static final java.util.Set<String> HUB_KINDS = java.util.Set.of("state", "union_territory");

    public record ListItem(String slug, String name, String kind, String description, String stateName,
                           String coverageStatus, String imageUrl, String imageCredit, Long entryCount) {}

    public record EntryRef(UUID id, String title, String section) {}

    private static final String LIST_WHERE = """
            WHERE p.is_destination AND p.lifecycle_status = 'active'
              AND (CAST(:kind AS text) IS NULL OR p.kind = :kind)
              AND (NOT :withGuide OR (dp.coverage_status IS NOT NULL AND dp.coverage_status <> 'indexed'))
            """;

    private final PlaceRepository places;
    private final CoverageService coverage;
    private final GuideService guide;
    private final GuideRepository guideRepo;
    private final JdbcClient jdbc;
    private final Json json;
    private final io.destinationintel.ai.AiContent ai;

    public DestinationService(PlaceRepository places, CoverageService coverage, GuideService guide,
                              GuideRepository guideRepo, JdbcClient jdbc, Json json, io.destinationintel.ai.AiContent ai) {
        this.places = places;
        this.coverage = coverage;
        this.guide = guide;
        this.guideRepo = guideRepo;
        this.jdbc = jdbc;
        this.json = json;
        this.ai = ai;
    }

    public PageResponse<ListItem> list(String kind, boolean withGuide, int page, int size) {
        long total = jdbc.sql("SELECT count(*) FROM place p LEFT JOIN destination_profile dp ON dp.place_id = p.id " + LIST_WHERE)
                .param("kind", kind).param("withGuide", withGuide).query(Long.class).single();
        List<ListItem> items = jdbc.sql("""
                        SELECT p.slug, p.name, p.kind, p.description, st.name AS state_name, dp.coverage_status,
                               img.image_url,
                               CASE WHEN img.image_url IS NULL THEN NULL
                                    ELSE concat_ws(', ', nullif(img.artist, ''), img.licence_name) END AS image_credit,
                               (SELECT count(DISTINCT (e.section, e.title_normalized)) FROM guide_entry e WHERE e.destination_id = p.id AND e.status = 'published'
                                  AND e.section <> 'neighbourhood') AS entry_count
                        FROM place p
                        LEFT JOIN destination_profile dp ON dp.place_id = p.id
                        LEFT JOIN place_image img ON img.place_id = p.id
                        LEFT JOIN LATERAL (SELECT s.name FROM place_relation r JOIN place s ON s.id = r.parent_id
                                           WHERE r.child_id = p.id AND r.relation = 'in_state' LIMIT 1) st ON true
                        """ + LIST_WHERE + """
                        ORDER BY CASE dp.coverage_status WHEN 'well_covered' THEN 0 WHEN 'usable' THEN 1
                                 WHEN 'needs_refresh' THEN 2 WHEN 'building' THEN 3 ELSE 4 END,
                                 coalesce(p.sitelink_count, 0) DESC, p.name
                        LIMIT :limit OFFSET :offset
                        """)
                .param("kind", kind).param("withGuide", withGuide).param("limit", size).param("offset", page * size)
                .query(ListItem.class).list();
        return PageResponse.of(items, page, size, total);
    }

    public List<EntryRef> findEntries(UUID destinationId, String q) {
        return jdbc.sql("""
                        SELECT id, title, section FROM guide_entry
                        WHERE destination_id = :d AND status = 'published'
                          AND (title_normalized LIKE '%' || idi_normalize(:q) || '%'
                               OR similarity(title_normalized, idi_normalize(:q)) > 0.3)
                        ORDER BY similarity(title_normalized, idi_normalize(:q)) DESC, title LIMIT 10
                        """)
                .param("d", destinationId).param("q", q).query(EntryRef.class).list();
    }

    public PlaceRow require(String slug) {
        return places.findBySlug(slug).orElseThrow(() -> ApiException.notFound("Destination '" + slug + "'"));
    }

    public DestinationDetail detail(String slug) {
        PlaceRow p = require(slug);
        List<ParentRef> parents = places.parents(p.id()).stream()
                .map(r -> new ParentRef(r.relation(), r.slug(), r.name(), r.kind())).toList();
        Set<String> seen = new LinkedHashSet<>();
        seen.add(p.name().toLowerCase());
        List<AliasView> aliases = new ArrayList<>();
        for (PlaceRepository.AliasRow a : places.aliases(p.id())) {
            if (aliases.size() >= 24) {
                break;
            }
            if (seen.add(a.alias().toLowerCase())) {
                aliases.add(new AliasView(a.alias(), a.language(), a.aliasType()));
            }
        }
        CoverageView cov = coverage.profile(p.id()).map(this::coverageView).orElse(indexedCoverage());
        boolean guideAvailable = !cov.status().equals("indexed");
        int guidedPlaces = guidedPlacesIn(p.id());
        boolean hub = HUB_KINDS.contains(p.kind()) && guidedPlaces > 0;
        FactView summary = guideAvailable || hub ? guide.summary(p.id()) : null;
        Map<String, Integer> counts = guideAvailable ? guide.sectionCounts(p.id()) : Map.of();
        List<SourceRef> sources = guideAvailable ? sourceRefs(guide.sourcesUsed(p.id())) : sourceRefs(Set.of("wikidata"));
        OffsetDateTime lastSync = jdbc.sql("SELECT max(last_synced_at) FROM place_source_sync WHERE place_id = :id")
                .param("id", p.id()).query(OffsetDateTime.class).optional().orElse(null);
        List<ChildDestination> children = List.of();
        if (Set.of("state", "union_territory", "district", "region").contains(p.kind())) {
            children = jdbc.sql("""
                            SELECT c.slug, c.name, c.kind, dp.coverage_status
                            FROM place_relation r
                            JOIN place c ON c.id = r.child_id
                            LEFT JOIN destination_profile dp ON dp.place_id = c.id
                            WHERE r.parent_id = :id AND r.relation IN ('in_state', 'in_district', 'within_region')
                              AND c.is_destination AND c.lifecycle_status = 'active'
                              AND c.kind NOT IN ('district', 'neighbourhood')
                            ORDER BY CASE WHEN dp.coverage_status IS NOT NULL AND dp.coverage_status <> 'indexed' THEN 0 ELSE 1 END,
                                     coalesce(c.sitelink_count, 0) DESC, c.name
                            LIMIT 30
                            """)
                    .param("id", p.id()).query(ChildDestination.class).list();
        }
        List<ClosedEntry> closed = guideRepo.recentlyClosed(p.id(), 10).stream()
                .map(c -> new ClosedEntry(c.id(), c.section(), c.title(), c.statusReason(), c.updatedAt())).toList();
        return new DestinationDetail(
                p.id(), p.slug(), p.name(), p.kind(), p.description(), p.latitude(), p.longitude(), p.population(),
                p.wikidataId(),
                p.wikidataId() == null ? null : "https://www.wikidata.org/wiki/" + p.wikidataId(),
                p.wikivoyageTitle() == null ? null
                        : "https://en.wikivoyage.org/wiki/" + URLEncoder.encode(p.wikivoyageTitle().replace(' ', '_'),
                        StandardCharsets.UTF_8).replace("%2F", "/"),
                parents, aliases, image(p.id()), cov, summary, counts, sources, lastSync, guideAvailable, children, closed,
                guideAvailable ? ai.inShort(p.id()) : List.of(), guidedPlaces);
    }

    /** How many places within this one (a state's towns and cities) have a guide. */
    public int guidedPlacesIn(UUID placeId) {
        return jdbc.sql("""
                        SELECT count(*) FROM place_relation r
                        JOIN destination_profile dp ON dp.place_id = r.child_id AND dp.coverage_status <> 'indexed'
                        WHERE r.parent_id = :p AND r.relation = 'in_state'""")
                .param("p", placeId).query(Long.class).single().intValue();
    }

    private ImageView image(UUID placeId) {
        return jdbc.sql("""
                        SELECT i.image_url AS url, i.width, i.height, i.page_url, i.artist, i.licence_name, i.licence_url,
                               s.name AS source_name
                        FROM place_image i JOIN source s ON s.id = i.source_id WHERE i.place_id = :id
                        """)
                .param("id", placeId).query(ImageView.class).optional().orElse(null);
    }

    private List<SourceRef> sourceRefs(Set<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT id, name, category, licence_name, licence_url, attribution_text AS attribution, base_url
                        FROM source WHERE id IN (:ids) ORDER BY name
                        """)
                .param("ids", List.copyOf(ids)).query(SourceRef.class).list();
    }

    @SuppressWarnings("unchecked")
    CoverageView coverageView(CoverageService.ProfileRow row) {
        List<CoverageCheck> checks = new ArrayList<>();
        for (Object o : json.list(row.qualityChecks())) {
            if (o instanceof Map<?, ?> m) {
                checks.add(new CoverageCheck(String.valueOf(m.get("rule")), Boolean.TRUE.equals(m.get("passed")),
                        String.valueOf(m.get("detail"))));
            }
        }
        return new CoverageView(row.coverageStatus(), label(row.coverageStatus()), explanation(row.coverageStatus()),
                checks, json.map(row.sectionStats()), row.computedAt(), row.graduatedAt());
    }

    static CoverageView indexedCoverage() {
        return new CoverageView("indexed", label("indexed"), explanation("indexed"), List.of(), Map.of(), null, null);
    }

    public static String label(String status) {
        return switch (status) {
            case "indexed" -> "Indexed";
            case "building" -> "Limited coverage";
            case "usable" -> "Usable";
            case "well_covered" -> "Well covered";
            case "needs_refresh" -> "Good coverage";
            default -> status;
        };
    }

    static String explanation(String status) {
        return switch (status) {
            case "indexed" -> "This place is in the index, but no guide content has been published for it yet.";
            case "building" -> "This guide is still being built. What is shown is sourced, but some core sections are thin or missing.";
            case "usable" -> "Enough sourced information across the core sections to be useful. Check freshness labels on individual facts.";
            case "well_covered" -> "Every core section is deep and a meaningful share of it has been verified by contributors.";
            case "needs_refresh" -> "Good coverage from open travel sources. Prices and opening hours change, so each one shows when it was last recorded; people who know the place can confirm them.";
            default -> "";
        };
    }
}
