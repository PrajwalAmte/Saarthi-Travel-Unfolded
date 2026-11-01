-- Wikivoyage redirect titles as search names: "Statue of Unity" and "Ekta Nagar" lead to Kevadia, "Banglore" to
-- Bengaluru. Kept as their own alias type so they can be refreshed (and told apart) without touching other names.
ALTER TABLE place_alias DROP CONSTRAINT place_alias_alias_type_check;
ALTER TABLE place_alias ADD CONSTRAINT place_alias_alias_type_check
    CHECK (alias_type IN ('label', 'alternate', 'historical', 'local_script', 'source_title', 'community', 'redirect'));

ALTER TABLE ingestion_job DROP CONSTRAINT ingestion_job_job_type_check;
ALTER TABLE ingestion_job ADD CONSTRAINT ingestion_job_job_type_check CHECK (job_type IN ('wikidata_index', 'wikivoyage_guide',
    'climate', 'place_image', 'resolve_request', 'coverage_refresh', 'osm_extract', 'overture_places', 'heritage_monuments',
    'quality_evaluation', 'match_resolution', 'ai_review', 'road_routes', 'nearest_transport', 'air_quality', 'fact_check',
    'wikivoyage_redirects'));
