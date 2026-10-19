package io.destinationintel.place;

import io.destinationintel.common.Slugs;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class PlaceRepository {

    /** Attributes of a place as supplied by an ingestion source. */
    public record PlaceUpsert(
            String wikidataId,
            String name,
            String kind,
            String description,
            Double latitude,
            Double longitude,
            Long population,
            String wikivoyageTitle,
            String isoCode,
            Integer sitelinkCount,
            boolean isDestination,
            String slugQualifier) {}

    public record ParentRow(String relation, UUID id, String slug, String name, String kind) {}

    public record AliasRow(String alias, String language, String aliasType) {}

    private final JdbcClient jdbc;

    public PlaceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PlaceRow> findBySlug(String slug) {
        return jdbc.sql("SELECT " + PlaceRow.COLUMNS + " FROM place p WHERE p.slug = :slug AND p.lifecycle_status = 'active'")
                .param("slug", slug).query(PlaceRow.class).optional();
    }

    public Optional<PlaceRow> findById(UUID id) {
        return jdbc.sql("SELECT " + PlaceRow.COLUMNS + " FROM place p WHERE p.id = :id")
                .param("id", id).query(PlaceRow.class).optional();
    }

    public Optional<PlaceRow> findByWikidataId(String qid) {
        return jdbc.sql("SELECT " + PlaceRow.COLUMNS + " FROM place p WHERE p.wikidata_id = :qid")
                .param("qid", qid).query(PlaceRow.class).optional();
    }

    public Optional<PlaceRow> findByWikivoyageTitle(String title) {
        return jdbc.sql("SELECT " + PlaceRow.COLUMNS + " FROM place p WHERE p.wikivoyage_title = :t")
                .param("t", title).query(PlaceRow.class).optional();
    }

    public List<PlaceRow> findByWikivoyageTitlePrefix(String prefix, int limit) {
        return jdbc.sql("SELECT " + PlaceRow.COLUMNS + """
                         FROM place p WHERE p.wikivoyage_title LIKE :prefix ESCAPE '\\' AND p.lifecycle_status = 'active'
                        ORDER BY p.wikivoyage_title LIMIT :limit""")
                .param("prefix", prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
                .param("limit", limit)
                .query(PlaceRow.class).list();
    }

    /** Places whose Wikivoyage article is a district of a larger city article ("City/District"). */
    public List<PlaceRow> findWikivoyageDistrictArticles() {
        return jdbc.sql("SELECT " + PlaceRow.COLUMNS + " FROM place p WHERE p.wikivoyage_title LIKE '%/%'")
                .query(PlaceRow.class).list();
    }

    public long countDestinations() {
        return jdbc.sql("SELECT count(*) FROM place WHERE is_destination AND lifecycle_status = 'active'")
                .query(Long.class).single();
    }

    /**
     * Inserts or updates a place keyed by its Wikidata identifier. The slug is assigned
     * once at creation and never changes, so shared URLs stay stable.
     */
    public UUID upsertFromWikidata(PlaceUpsert u) {
        if (StringUtils.hasText(u.wikivoyageTitle())) {
            jdbc.sql("UPDATE place SET wikivoyage_title = NULL WHERE wikivoyage_title = :t AND wikidata_id IS DISTINCT FROM :qid")
                    .param("t", u.wikivoyageTitle()).param("qid", u.wikidataId()).update();
        }
        Optional<UUID> existing = jdbc.sql("SELECT id FROM place WHERE wikidata_id = :qid")
                .param("qid", u.wikidataId()).query(UUID.class).optional();
        if (existing.isPresent()) {
            jdbc.sql("""
                            UPDATE place SET name = :name, kind = :kind, description = :description,
                                   latitude = :lat, longitude = :lon,
                                   coordinates_source = CASE WHEN CAST(:lat AS double precision) IS NOT NULL THEN 'wikidata' END,
                                   population = :population,
                                   wikivoyage_title = coalesce(:wv, wikivoyage_title), iso_code = coalesce(:iso, iso_code),
                                   sitelink_count = coalesce(:sitelinks, sitelink_count),
                                   is_destination = :isDestination, updated_at = now()
                            WHERE id = :id
                            """)
                    .param("name", u.name()).param("kind", u.kind()).param("description", u.description())
                    .param("lat", u.latitude()).param("lon", u.longitude()).param("population", u.population())
                    .param("wv", u.wikivoyageTitle()).param("iso", u.isoCode()).param("sitelinks", u.sitelinkCount())
                    .param("isDestination", u.isDestination()).param("id", existing.get())
                    .update();
            return existing.get();
        }
        String slug = uniqueSlug(u.name(), u.slugQualifier(), u.wikidataId());
        return jdbc.sql("""
                        INSERT INTO place (slug, name, kind, description, latitude, longitude, coordinates_source, population,
                                           wikidata_id, wikivoyage_title, iso_code, sitelink_count, is_destination, origin)
                        VALUES (:slug, :name, :kind, :description, :lat, :lon,
                                CASE WHEN CAST(:lat AS double precision) IS NOT NULL THEN 'wikidata' END, :population, :qid, :wv,
                                :iso, :sitelinks, :isDestination, 'ingested')
                        RETURNING id
                        """)
                .param("slug", slug).param("name", u.name()).param("kind", u.kind())
                .param("description", u.description()).param("lat", u.latitude()).param("lon", u.longitude())
                .param("population", u.population()).param("qid", u.wikidataId()).param("wv", u.wikivoyageTitle())
                .param("iso", u.isoCode()).param("sitelinks", u.sitelinkCount())
                .param("isDestination", u.isDestination())
                .query(UUID.class).single();
    }

    /**
     * Takes a Wikidata-indexed place out of the index (soft removal) unless it already carries
     * guide content or facts. Returns the number of places removed (0 or 1).
     */
    public int softRemoveIfUnused(String qid) {
        return jdbc.sql("""
                        UPDATE place p SET lifecycle_status = 'removed', is_destination = false, updated_at = now()
                        WHERE p.wikidata_id = :q AND p.lifecycle_status = 'active'
                          AND NOT EXISTS (SELECT 1 FROM guide_entry e WHERE e.destination_id = p.id)
                          AND NOT EXISTS (SELECT 1 FROM fact f WHERE f.destination_id = p.id)
                        """).param("q", qid).update();
    }

    String uniqueSlug(String name, String qualifier, String qid) {
        String base = Slugs.slugify(name);
        if (base.isEmpty()) {
            base = qid == null ? "place" : qid.toLowerCase();
        }
        if (!slugExists(base)) {
            return base;
        }
        if (StringUtils.hasText(qualifier)) {
            String qualified = base + "-" + Slugs.slugify(qualifier);
            if (!slugExists(qualified)) {
                return qualified;
            }
        }
        if (qid != null) {
            String withQid = base + "-" + qid.toLowerCase();
            if (!slugExists(withQid)) {
                return withQid;
            }
        }
        for (int i = 2; ; i++) {
            String candidate = base + "-" + i;
            if (!slugExists(candidate)) {
                return candidate;
            }
        }
    }

    private boolean slugExists(String slug) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM place WHERE slug = :slug)").param("slug", slug)
                .query(Boolean.class).single();
    }

    /** Adds an alias; returns 1 if it was new. */
    public int addAlias(UUID placeId, String alias, String language, String type, String sourceId) {
        if (!StringUtils.hasText(alias) || alias.length() > 200) {
            return 0;
        }
        return jdbc.sql("""
                        INSERT INTO place_alias (place_id, alias, language, alias_type, source_id)
                        VALUES (:placeId, :alias, :language, :type, :source)
                        ON CONFLICT (place_id, alias, language) DO NOTHING
                        """)
                .param("placeId", placeId).param("alias", alias.trim()).param("language", language)
                .param("type", type).param("source", sourceId)
                .update();
    }

    public void addRelation(UUID childId, UUID parentId, String relation, String sourceId) {
        if (childId.equals(parentId)) {
            return;
        }
        jdbc.sql("""
                        INSERT INTO place_relation (child_id, parent_id, relation, source_id)
                        VALUES (:child, :parent, :relation, :source)
                        ON CONFLICT (child_id, parent_id, relation) DO NOTHING
                        """)
                .param("child", childId).param("parent", parentId).param("relation", relation).param("source", sourceId)
                .update();
    }

    public List<ParentRow> parents(UUID placeId) {
        return jdbc.sql("""
                        SELECT r.relation, p.id, p.slug, p.name, p.kind
                        FROM place_relation r JOIN place p ON p.id = r.parent_id
                        WHERE r.child_id = :id AND p.lifecycle_status = 'active'
                        ORDER BY CASE r.relation WHEN 'in_state' THEN 1 WHEN 'in_district' THEN 2
                                 WHEN 'part_of_city' THEN 3 ELSE 4 END, p.name
                        """)
                .param("id", placeId).query(ParentRow.class).list();
    }

    public List<PlaceRow> children(UUID parentId, String relation, int limit) {
        return jdbc.sql("SELECT " + PlaceRow.COLUMNS + """
                         FROM place_relation r JOIN place p ON p.id = r.child_id
                        WHERE r.parent_id = :id AND r.relation = :relation AND p.lifecycle_status = 'active'
                        ORDER BY p.name LIMIT :limit""")
                .param("id", parentId).param("relation", relation).param("limit", limit)
                .query(PlaceRow.class).list();
    }

    public List<AliasRow> aliases(UUID placeId) {
        return jdbc.sql("""
                        SELECT alias, language, alias_type FROM place_alias WHERE place_id = :id
                        ORDER BY CASE alias_type WHEN 'label' THEN 1 WHEN 'alternate' THEN 2 WHEN 'historical' THEN 3
                                 WHEN 'local_script' THEN 4 ELSE 5 END, language, alias
                        """)
                .param("id", placeId).query(AliasRow.class).list();
    }

    public String normalize(String text) {
        return jdbc.sql("SELECT idi_normalize(:t)").param("t", text).query(String.class).optional().orElse("");
    }

    public void deleteRelationsFrom(UUID childId, String relation) {
        jdbc.sql("DELETE FROM place_relation WHERE child_id = :id AND relation = :relation")
                .param("id", childId).param("relation", relation).update();
    }
}
