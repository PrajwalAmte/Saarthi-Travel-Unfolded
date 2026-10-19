package io.destinationintel.quality;

import io.destinationintel.ingestion.run.SyncRunRecorder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Source-specific acceptance thresholds. OSM is the national base layer, so it must cover most
 * states with few residual duplicates. Overture is a complement read only around indexed
 * destinations, so nearly all of it must be within reach of one, duplicates against OSM are not
 * tolerated, and more records may wait for match review. Rejection rates count only records that
 * failed validation; out-of-scope features (unnamed, unmapped category, outside India, closed)
 * are reported separately.
 */
@Configuration
public class QualityEvaluators {

    @Bean
    SourceQualityEvaluator openStreetMapQuality(JdbcClient jdbc, SyncRunRecorder recorder) {
        return new PoiQualityEvaluator("openstreetmap",
                new PoiQualityEvaluator.Thresholds(5, 30, 1.0, 99.0, 25, 35, 5, 100), jdbc, recorder);
    }

    /**
     * ASI monuments via Wikidata: many are rural, so fewer are near an indexed destination, and
     * Wikidata lacks coordinates for a large share of them (reported, not hidden).
     */
    @Bean
    SourceQualityEvaluator heritageQuality(JdbcClient jdbc, SyncRunRecorder recorder) {
        return new PoiQualityEvaluator("asi-heritage",
                new PoiQualityEvaluator.Thresholds(5, 20, 1.0, 99.0, 40, 30, 15, 50), jdbc, recorder);
    }

    @Bean
    SourceQualityEvaluator overtureQuality(JdbcClient jdbc, SyncRunRecorder recorder) {
        return new PoiQualityEvaluator("overture-places",
                new PoiQualityEvaluator.Thresholds(10, 25, 1.0, 99.0, 25, 90, 15, 100), jdbc, recorder);
    }
}
