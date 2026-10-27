package io.destinationintel.ai;

import io.destinationintel.common.Json;
import io.destinationintel.ranking.EntryAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Reads the AI improvements readers may see. Returns nothing while publishing is switched off. */
@Component
public class AiContent {

    public record EntryEnrichment(String summary, List<Map<String, String>> tags) {}

    public record Source(UUID factId, String sourceName, String url) {}

    public record InShortItem(String topic, String text, List<Source> sources) {}

    private final JdbcClient jdbc;
    private final Json json;
    private final AiPublishing publishing;

    public AiContent(JdbcClient jdbc, Json json, AiPublishing publishing) {
        this.jdbc = jdbc;
        this.json = json;
        this.publishing = publishing;
    }

    public boolean published() {
        return publishing.published();
    }

    /** Latest improvements per listing of a destination, for the current prompt version. */
    @SuppressWarnings("unchecked")
    public Map<UUID, EntryEnrichment> forDestination(UUID destinationId) {
        if (!publishing.published()) {
            return Map.of();
        }
        Map<UUID, EntryEnrichment> out = new HashMap<>();
        jdbc.sql("""
                        SELECT DISTINCT ON (entry_id) entry_id, summary, summary_status, tags::text AS tags
                        FROM ai_enrichment WHERE destination_id = :d AND prompt_version = :v
                        ORDER BY entry_id, created_at DESC""")
                .param("d", destinationId).param("v", ListingReviewHandler.PROMPT_VERSION)
                .query((rs, i) -> {
                    List<Map<String, String>> tags = new ArrayList<>();
                    for (Object o : json.list(rs.getString("tags"))) {
                        if (o instanceof Map<?, ?> m) {
                            tags.add(Map.of("tag", String.valueOf(m.get("tag")), "quote", String.valueOf(m.get("quote"))));
                        }
                    }
                    String summary = "accepted".equals(rs.getString("summary_status")) ? rs.getString("summary") : null;
                    out.put(rs.getObject("entry_id", UUID.class), new EntryEnrichment(summary, tags));
                    return null;
                }).list();
        return out;
    }

    /** Adds AI tags the source-derived attributes do not already have; source-derived values always win. */
    public static EntryAttributes merge(EntryAttributes a, EntryEnrichment e) {
        if (e == null || e.tags().isEmpty()) {
            return a;
        }
        List<EntryAttributes.Tag> interests = new ArrayList<>(a.interests());
        List<EntryAttributes.Tag> food = new ArrayList<>(a.foodStyle());
        EntryAttributes.Attribute tier = a.priceTier();
        EntryAttributes.Attribute access = a.accessibility();
        for (Map<String, String> t : e.tags()) {
            String tag = t.get("tag");
            String basis = "The listing text says “" + t.get("quote") + "” (noted by AI)";
            switch (tag) {
                case "food", "culture", "nature", "adventure", "nightlife", "shopping", "family" -> {
                    if (interests.stream().noneMatch(x -> x.tag().equals(tag))) {
                        interests.add(new EntryAttributes.Tag(tag, basis));
                    }
                }
                case "regional_food" -> {
                    if (food.stream().noneMatch(x -> x.tag().equals("regional"))) {
                        food.add(new EntryAttributes.Tag("regional", basis));
                    }
                }
                case "wheelchair" -> {
                    if (access == null) {
                        access = new EntryAttributes.Attribute("wheelchair", basis);
                    }
                }
                case "budget", "moderate", "premium" -> {
                    if (tier == null) {
                        tier = new EntryAttributes.Attribute(tag, basis);
                    }
                }
                default -> {
                    // unknown tags are ignored
                }
            }
        }
        return new EntryAttributes(tier, interests, food, access, a.major(), a.temporarilyClosed());
    }

    /** The destination's "In short", with the source of every fact each sentence relies on. */
    @SuppressWarnings("unchecked")
    public List<InShortItem> inShort(UUID destinationId) {
        if (!publishing.published()) {
            return List.of();
        }
        String items = jdbc.sql("""
                        SELECT items::text FROM ai_overview WHERE destination_id = :d ORDER BY created_at DESC LIMIT 1""")
                .param("d", destinationId).query(String.class).optional().orElse(null);
        if (items == null) {
            return List.of();
        }
        List<InShortItem> out = new ArrayList<>();
        for (Object o : json.list(items)) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            List<Source> sources = new ArrayList<>();
            Map<String, Source> bySource = new LinkedHashMap<>();
            for (Object id : (List<Object>) m.get("factIds")) {
                UUID factId = UUID.fromString(String.valueOf(id));
                // A fact that has since been withdrawn takes its sentence with it.
                List<Source> found = jdbc.sql("""
                                SELECT f.id AS fact_id, s.name AS source_name, coalesce(e.permalink, e.url) AS url
                                FROM fact f JOIN evidence e ON e.fact_id = f.id JOIN source s ON s.id = e.source_id
                                WHERE f.id = :f AND f.status = 'published'""")
                        .param("f", factId).query(Source.class).list();
                if (found.isEmpty()) {
                    sources = null;
                    break;
                }
                for (Source s : found) {
                    bySource.putIfAbsent(s.sourceName(), s);
                }
            }
            if (sources == null) {
                continue;
            }
            sources.addAll(bySource.values());
            out.add(new InShortItem(String.valueOf(m.get("topic")), String.valueOf(m.get("text")), sources));
        }
        return out;
    }
}
