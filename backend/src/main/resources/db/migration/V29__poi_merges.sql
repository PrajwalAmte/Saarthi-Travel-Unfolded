-- Two map places can turn out to be one, usually when a new record matches both. Merging moves the records
-- and guide entries to the place kept. Each merge keeps what it moved, so it can be undone exactly.
CREATE TABLE poi_merge (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    kept_poi_id           uuid        NOT NULL REFERENCES poi (id) ON DELETE CASCADE,
    merged_poi_id         uuid        NOT NULL REFERENCES poi (id) ON DELETE CASCADE,
    rule                  text        NOT NULL,
    distance_m            double precision NOT NULL,
    merged_seed_record_id uuid,
    moved_records         uuid[]      NOT NULL,
    moved_entries         uuid[]      NOT NULL,
    created_at            timestamptz NOT NULL DEFAULT now(),
    undone_at             timestamptz,
    undone_by             uuid REFERENCES app_user (id) ON DELETE SET NULL
);

CREATE INDEX poi_merge_recent_idx ON poi_merge (created_at DESC);
CREATE INDEX poi_merge_kept_idx ON poi_merge (kept_poi_id);
CREATE INDEX poi_merge_merged_idx ON poi_merge (merged_poi_id);

-- A rule being tried out: what it would have decided, recorded without changing the outcome.
ALTER TABLE poi_match_review ADD COLUMN shadow_rule text;

ALTER TABLE ingestion_job DROP CONSTRAINT ingestion_job_job_type_check;
ALTER TABLE ingestion_job ADD CONSTRAINT ingestion_job_job_type_check CHECK (job_type IN ('wikidata_index', 'wikivoyage_guide',
    'climate', 'place_image', 'resolve_request', 'coverage_refresh', 'osm_extract', 'overture_places', 'heritage_monuments',
    'quality_evaluation', 'match_resolution', 'ai_review', 'road_routes', 'nearest_transport', 'air_quality', 'fact_check',
    'wikivoyage_redirects', 'poi_duplicate_merge'));
