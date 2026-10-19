package io.destinationintel.quality;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Evaluators for the climate and image sources. */
@Configuration
public class SupportingSourcesQualityEvaluators {

    @Bean
    SourceQualityEvaluator openMeteoQuality(JdbcClient jdbc) {
        return new SourceQualityEvaluator() {
            @Override
            public String sourceId() {
                return "open-meteo";
            }

            @Override
            public QualityResult evaluate(UUID runId) {
                Map<String, Object> m = QualityResult.newMetrics();
                List<QualityCheck> checks = new ArrayList<>();
                long facts = jdbc.sql("SELECT count(*) FROM fact WHERE kind = 'climate' AND status = 'published'").query(Long.class).single();
                long complete = jdbc.sql("""
                        SELECT count(*) FROM fact WHERE kind = 'climate' AND status = 'published'
                          AND jsonb_array_length(value_json -> 'months') = 12""").query(Long.class).single();
                long implausible = jdbc.sql("""
                        SELECT count(*) FROM fact f, jsonb_array_elements(f.value_json -> 'months') mo
                        WHERE f.kind = 'climate' AND f.status = 'published'
                          AND ((mo ->> 'meanMaxC')::numeric NOT BETWEEN -30 AND 55 OR (mo ->> 'meanMinC')::numeric NOT BETWEEN -45 AND 40
                               OR (mo ->> 'precipitationMm')::numeric NOT BETWEEN 0 AND 4000
                               OR (mo ->> 'meanMinC')::numeric > (mo ->> 'meanMaxC')::numeric)""").query(Long.class).single();
                long guides = jdbc.sql("SELECT count(DISTINCT place_id) FROM destination_profile WHERE coverage_status <> 'indexed'")
                        .query(Long.class).single();
                m.put("climateFacts", facts);
                m.put("guidesWithClimatePct", Pct.of(facts, guides));
                checks.add(QualityCheck.atLeast("complete_years", "completeness", QualityCheck.CRITICAL,
                        "Climate facts with all 12 months (%)", Pct.of(complete, facts), 100, null));
                checks.add(QualityCheck.atMost("implausible_values", "accuracy", QualityCheck.CRITICAL,
                        "Monthly values outside physical ranges or with min above max", implausible, 0, null));
                checks.add(QualityCheck.info("guides_with_climate", "coverage", "Guides with climate averages (%)",
                        Pct.of(facts, guides), null));
                Map<String, Double> c = new LinkedHashMap<>();
                c.put("completeness", Pct.of(complete, facts));
                c.put("coverage", Math.min(100, Pct.of(facts, guides)));
                return new QualityResult(m, checks, c, List.of(), List.of(
                        "ERA5 reanalysis is on a ~25 km grid; local microclimates (hill stations, coasts) can differ."));
            }
        };
    }

    @Bean
    SourceQualityEvaluator commonsQuality(JdbcClient jdbc) {
        return new SourceQualityEvaluator() {
            @Override
            public String sourceId() {
                return "wikimedia-commons";
            }

            @Override
            public QualityResult evaluate(UUID runId) {
                Map<String, Object> m = QualityResult.newMetrics();
                List<QualityCheck> checks = new ArrayList<>();
                long images = jdbc.sql("SELECT count(*) FROM place_image").query(Long.class).single();
                long licensed = jdbc.sql("SELECT count(*) FROM place_image WHERE licence_name IS NOT NULL AND licence_name <> ''")
                        .query(Long.class).single();
                long credited = jdbc.sql("SELECT count(*) FROM place_image WHERE artist IS NOT NULL").query(Long.class).single();
                long guides = jdbc.sql("SELECT count(*) FROM destination_profile WHERE coverage_status <> 'indexed'").query(Long.class).single();
                m.put("images", images);
                m.put("guidesWithImagePct", Pct.of(images, guides));
                checks.add(QualityCheck.atLeast("licence", "provenance", QualityCheck.CRITICAL, "Images with a stated licence (%)",
                        Pct.of(licensed, images), 100, null));
                checks.add(QualityCheck.atLeast("author_credit", "provenance", QualityCheck.WARNING, "Images with an author credit (%)",
                        Pct.of(credited, images), 90, null));
                Map<String, Double> c = new LinkedHashMap<>();
                c.put("licensed", Pct.of(licensed, images));
                c.put("credited", Pct.of(credited, images));
                return new QualityResult(m, checks, c, List.of(), List.of(
                        "Images are only those Wikidata links to the destination (P18); quality of the photograph is not assessed."));
            }
        };
    }
}
