package io.destinationintel.ingestion.wikidata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** The parts of a Wikidata entity (from wbgetentities) that the index uses. */
public record EntityInfo(
        String id,
        String lastRevisionId,
        String englishLabel,
        String englishDescription,
        List<String> englishAliases,
        Map<String, String> localLabels,
        String wikivoyageTitle,
        int wikipediaCount,
        List<String> instanceOf,
        List<String> locatedIn,
        List<String> countries,
        Double latitude,
        Double longitude,
        Long population,
        String imageFile,
        boolean dissolved) {

    private static final Set<String> NON_WIKIPEDIA = Set.of("commonswiki", "specieswiki", "metawiki", "wikidatawiki",
            "mediawikiwiki", "sourceswiki", "wikimaniawiki", "outreachwiki", "incubatorwiki");

    public static EntityInfo parse(JsonNode e) {
        String id = e.path("id").asString();
        Map<String, String> local = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> l : e.path("labels").properties()) {
            if (!l.getKey().equals("en")) {
                local.put(l.getKey(), l.getValue().path("value").asString());
            }
        }
        List<String> aliases = new ArrayList<>();
        for (JsonNode a : e.path("aliases").path("en")) {
            aliases.add(a.path("value").asString());
        }
        int wikipedias = 0;
        String wv = null;
        for (Map.Entry<String, JsonNode> s : e.path("sitelinks").properties()) {
            String site = s.getKey();
            if (site.equals("enwikivoyage")) {
                wv = s.getValue().path("title").asString();
            }
            if (site.endsWith("wiki") && !NON_WIKIPEDIA.contains(site)) {
                wikipedias++;
            }
        }
        JsonNode claims = e.path("claims");
        List<String> p31 = itemValues(claims.path("P31"));
        List<String> p131 = currentItemValues(claims.path("P131"));
        List<String> p17 = itemValues(claims.path("P17"));
        Double lat = null;
        Double lon = null;
        JsonNode coord = preferred(claims.path("P625"));
        if (coord != null) {
            JsonNode v = coord.path("mainsnak").path("datavalue").path("value");
            if (v.has("latitude")) {
                lat = v.path("latitude").asDouble();
                lon = v.path("longitude").asDouble();
            }
        }
        Long population = null;
        JsonNode pop = preferred(claims.path("P1082"));
        if (pop != null) {
            String amount = pop.path("mainsnak").path("datavalue").path("value").path("amount").asString(null);
            if (amount != null) {
                try {
                    population = (long) Double.parseDouble(amount.replace("+", ""));
                } catch (NumberFormatException ignored) {
                    // leave unknown
                }
            }
        }
        String image = null;
        JsonNode img = preferred(claims.path("P18"));
        if (img != null) {
            image = img.path("mainsnak").path("datavalue").path("value").asString(null);
        }
        boolean dissolved = false;
        for (JsonNode st : claims.path("P576")) {
            if (!"deprecated".equals(st.path("rank").asString())) {
                dissolved = true;
            }
        }
        return new EntityInfo(id, e.path("lastrevid").asString(null),
                e.path("labels").path("en").path("value").asString(null),
                e.path("descriptions").path("en").path("value").asString(null),
                aliases, local, wv, wikipedias, p31, p131, p17, lat, lon, population, image, dissolved);
    }

    /**
     * Item values of statements that still hold: statements with an end time (P582) describe the
     * past (for example a district a town belonged to before a split) and are skipped, unless every
     * statement has ended. Preferred-rank statements come first.
     */
    static List<String> currentItemValues(JsonNode statements) {
        List<String> preferred = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (JsonNode st : statements) {
            String rank = st.path("rank").asString();
            if ("deprecated".equals(rank) || st.path("qualifiers").has("P582")) {
                continue;
            }
            String id = st.path("mainsnak").path("datavalue").path("value").path("id").asString(null);
            if (id != null) {
                ("preferred".equals(rank) ? preferred : current).add(id);
            }
        }
        preferred.addAll(current);
        return preferred.isEmpty() ? itemValues(statements) : preferred;
    }

    private static List<String> itemValues(JsonNode statements) {
        List<String> out = new ArrayList<>();
        for (JsonNode st : statements) {
            if ("deprecated".equals(st.path("rank").asString())) {
                continue;
            }
            String id = st.path("mainsnak").path("datavalue").path("value").path("id").asString(null);
            if (id != null) {
                out.add(id);
            }
        }
        return out;
    }

    /** The preferred-rank statement if any, otherwise the first non-deprecated one. */
    private static JsonNode preferred(JsonNode statements) {
        JsonNode first = null;
        for (JsonNode st : statements) {
            String rank = st.path("rank").asString();
            if ("preferred".equals(rank)) {
                return st;
            }
            if (first == null && !"deprecated".equals(rank)) {
                first = st;
            }
        }
        return first;
    }
}
