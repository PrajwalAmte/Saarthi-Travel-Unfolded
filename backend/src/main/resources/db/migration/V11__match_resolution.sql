-- Each decided match review records the rule (or reviewer) that decided it.
ALTER TABLE poi_match_review ADD COLUMN resolution text;
UPDATE poi_match_review SET resolution = 'reviewer' WHERE status <> 'open' AND resolved_by IS NOT NULL;

-- Job that resolves open match reviews with evidence rules.
ALTER TABLE ingestion_job DROP CONSTRAINT ingestion_job_job_type_check;
ALTER TABLE ingestion_job ADD CONSTRAINT ingestion_job_job_type_check CHECK (job_type IN ('wikidata_index', 'wikivoyage_guide',
    'climate', 'place_image', 'resolve_request', 'coverage_refresh', 'osm_extract', 'overture_places', 'heritage_monuments',
    'quality_evaluation', 'match_resolution'));
