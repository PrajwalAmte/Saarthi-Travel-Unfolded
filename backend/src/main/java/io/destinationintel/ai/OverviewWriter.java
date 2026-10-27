package io.destinationintel.ai;

import io.destinationintel.common.Json;
import io.destinationintel.place.PlaceRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * A destination's "In short": up to four sentences (what it is best for, when to go, getting around, good to know)
 * written only from facts the guide already holds. Each sentence must cite the facts it uses, and any number or name
 * not found in those facts gets the sentence rejected.
 */
@Component
public class OverviewWriter {

    static final int PROMPT_VERSION = 1;
    static final List<String> TOPICS = List.of("best_for", "when_to_go", "getting_around", "good_to_know");
    private static final int MAX_FACTS = 40;

    static final String SYSTEM = """
            You write the "In short" box of an open travel guide for one destination in India, using only the numbered
            facts given. Never add anything that is not in them: no names, numbers, prices, dates or claims of your own.
            Write at most one sentence (at most 35 words) for each topic that the facts support, and skip a topic when they
            do not: best_for (what kind of trip or traveller it suits), when_to_go (season or months, from the facts),
            getting_around (the main ways to get around), good_to_know (one practical tip or caution).
            For each sentence list the ids of every fact it uses. Plain, neutral language; no superlatives the facts do not use.""";

    static final Map<String, Object> SCHEMA = Map.of(
            "type", "ARRAY",
            "items", Map.of("type", "OBJECT", "properties", Map.of(
                    "topic", Map.of("type", "STRING", "enum", TOPICS),
                    "text", Map.of("type", "STRING"),
                    "factIds", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))),
                    "required", List.of("topic", "text", "factIds")));

    record SourceFact(UUID id, String kind, String section, String text) {}

    private final GeminiClient gemini;
    private final JdbcClient jdbc;
    private final Json json;

    public OverviewWriter(GeminiClient gemini, JdbcClient jdbc, Json json) {
        this.gemini = gemini;
        this.jdbc = jdbc;
        this.json = json;
    }

    public Map<String, Object> write(PlaceRow place, String state) {
        List<SourceFact> facts = jdbc.sql("""
                        SELECT id, kind, section, value_text AS text FROM fact
                        WHERE destination_id = :d AND entry_id IS NULL AND status = 'published'
                          AND kind IN ('summary', 'seasonality', 'climate', 'transport', 'general_tip', 'safety', 'health',
                                       'customs', 'connectivity', 'orientation', 'speciality')
                        ORDER BY CASE kind WHEN 'summary' THEN 0 WHEN 'seasonality' THEN 1 WHEN 'climate' THEN 2
                                 WHEN 'transport' THEN 3 WHEN 'orientation' THEN 4 ELSE 5 END, created_at
                        LIMIT :n""")
                .param("d", place.id()).param("n", MAX_FACTS).query(SourceFact.class).list();
        Map<String, Object> stats = new LinkedHashMap<>();
        if (facts.size() < 2) {
            stats.put("status", "too few facts");
            return stats;
        }
        String hash = hash(facts);
        boolean done = jdbc.sql("SELECT EXISTS (SELECT 1 FROM ai_overview WHERE destination_id = :d AND input_hash = :h AND prompt_version = :v)")
                .param("d", place.id()).param("h", hash).param("v", PROMPT_VERSION).query(Boolean.class).single();
        if (done) {
            stats.put("status", "unchanged");
            return stats;
        }
        List<Map<String, Object>> numbered = new ArrayList<>();
        for (int i = 0; i < facts.size(); i++) {
            numbered.add(Map.of("id", "f" + (i + 1), "kind", facts.get(i).kind(),
                    "text", ListingReviewHandler.shorten(facts.get(i).text(), 500)));
        }
        GeminiClient.Answer answer = gemini.generateJson(SYSTEM, "Destination: " + place.name()
                + (state == null ? "" : " (" + state + ")") + ", India.\nFacts (JSON):\n" + json.write(numbered), SCHEMA);
        List<Map<String, Object>> accepted = new ArrayList<>();
        List<Map<String, Object>> rejected = new ArrayList<>();
        validate(answer.json(), facts, place.name(), state, accepted, rejected);
        jdbc.sql("""
                        INSERT INTO ai_overview (destination_id, input_hash, prompt_version, model, items, rejected)
                        VALUES (:d, :h, :v, :m, CAST(:items AS jsonb), CAST(:rejected AS jsonb))
                        ON CONFLICT (destination_id, input_hash, prompt_version) DO NOTHING""")
                .param("d", place.id()).param("h", hash).param("v", PROMPT_VERSION).param("m", answer.model())
                .param("items", json.write(accepted)).param("rejected", json.write(rejected)).update();
        stats.put("status", "written");
        stats.put("sentences", accepted.size());
        stats.put("rejected", rejected.size());
        return stats;
    }

    /** Keeps a sentence only if its topic is known, it cites real facts, and the guard finds nothing unsupported. */
    void validate(String answerJson, List<SourceFact> facts, String destination, String state,
                  List<Map<String, Object>> accepted, List<Map<String, Object>> rejected) {
        JsonNode root;
        try {
            root = json.tree(answerJson);
        } catch (RuntimeException e) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode n : root) {
            String topic = n.path("topic").asString("");
            String text = n.path("text").asString("").strip();
            if (!TOPICS.contains(topic) || !seen.add(topic)) {
                continue;
            }
            List<UUID> ids = new ArrayList<>();
            List<String> cited = new ArrayList<>();
            for (JsonNode f : n.path("factIds")) {
                String ref = f.asString("");
                if (ref.matches("f\\d+")) {
                    int i = Integer.parseInt(ref.substring(1)) - 1;
                    if (i >= 0 && i < facts.size() && !ids.contains(facts.get(i).id())) {
                        ids.add(facts.get(i).id());
                        cited.add(facts.get(i).text());
                    }
                }
            }
            var problem = text.length() > 240 ? java.util.Optional.of("longer than 240 characters")
                    : Faithfulness.citedSentenceProblem(text, cited, destination, state == null ? "" : state, "India");
            if (problem.isPresent()) {
                rejected.add(Map.of("topic", topic, "text", text, "reason", problem.get()));
            } else {
                accepted.add(Map.of("topic", topic, "text", text, "factIds", ids.stream().map(UUID::toString).toList()));
            }
        }
        accepted.sort(java.util.Comparator.comparingInt(m -> TOPICS.indexOf((String) m.get("topic"))));
    }

    private static String hash(List<SourceFact> facts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (SourceFact f : facts) {
                md.update((f.id() + "\u001f" + f.text() + "\u001e").getBytes(StandardCharsets.UTF_8));
            }
            md.update(String.valueOf(PROMPT_VERSION).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest(), 0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
