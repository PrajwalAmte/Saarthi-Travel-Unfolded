package io.destinationintel.ai;

import io.destinationintel.common.Json;
import io.destinationintel.guide.PriceParser;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Turns prices and opening hours that the AI found in a listing's own text into ordinary facts. Each one is the
 * verbatim quote, carries the same Wikivoyage evidence (revision, link, date) as the description it came from, and is
 * marked as extracted. They are hidden until AI improvements are published.
 */
@Component
public class AiFacts {

    public record Extracted(String kind, String quote) {}

    private final JdbcClient jdbc;
    private final Json json;

    public AiFacts(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public int write(UUID entryId, UUID descriptionFactId, List<Extracted> extracted, boolean published) {
        Map<String, Object> desc = jdbc.sql("""
                        SELECT destination_id, section, observed_at, observed_basis FROM fact WHERE id = :id""")
                .param("id", descriptionFactId).query().listOfRows().stream().findFirst().orElse(null);
        if (desc == null) {
            return 0;
        }
        Set<String> kept = new HashSet<>();
        int written = 0;
        for (Extracted e : extracted) {
            String key = "ai:" + entryId + ":" + e.kind();
            if (!kept.add(key)) {
                continue;
            }
            String valueJson = null;
            if (e.kind().equals("price")) {
                var p = PriceParser.parse(e.quote());
                if (p.isEmpty()) {
                    continue;
                }
                valueJson = json.write(Map.of("min", p.get().min(), "max", p.get().max(), "currency", "INR", "free", p.get().free()));
            }
            UUID id = jdbc.sql("""
                            INSERT INTO fact (destination_id, entry_id, section, kind, value_text, value_json, volatility, status, origin,
                                              ingest_key, observed_at, observed_basis, source_refreshed_at)
                            VALUES (:d, :e, :section, :kind, :text, CAST(:json AS jsonb), 'volatile', :status, 'ingested', :key,
                                    :observed, :basis, now())
                            ON CONFLICT (ingest_key) DO UPDATE SET value_text = EXCLUDED.value_text, value_json = EXCLUDED.value_json,
                                   status = EXCLUDED.status, observed_at = EXCLUDED.observed_at, updated_at = now()
                            RETURNING id""")
                    .param("d", desc.get("destination_id")).param("e", entryId).param("section", desc.get("section"))
                    .param("kind", e.kind()).param("text", e.quote()).param("json", valueJson)
                    .param("status", published ? "published" : "hidden").param("key", key)
                    .param("observed", desc.get("observed_at")).param("basis", desc.get("observed_basis"))
                    .query(UUID.class).single();
            jdbc.sql("DELETE FROM evidence WHERE fact_id = :f").param("f", id).update();
            jdbc.sql("""
                            INSERT INTO evidence (fact_id, source_id, url, permalink, title, excerpt, extraction_method, source_revision,
                                                  source_revision_at, observed_at, retrieved_at)
                            SELECT :f, source_id, url, permalink, title, :quote, 'ai_extracted_verbatim', source_revision,
                                   source_revision_at, observed_at, now()
                            FROM evidence WHERE fact_id = :desc""")
                    .param("f", id).param("quote", e.quote()).param("desc", descriptionFactId).update();
            written++;
        }
        // A price or time the AI no longer finds (the text changed) stops being shown.
        jdbc.sql("""
                        UPDATE fact SET status = 'superseded', updated_at = now()
                        WHERE entry_id = :e AND ingest_key LIKE 'ai:%' AND status IN ('published', 'hidden')
                          AND NOT (ingest_key = ANY (string_to_array(:kept, ',')))""")
                .param("e", entryId).param("kept", String.join(",", kept)).update();
        return written;
    }
}
