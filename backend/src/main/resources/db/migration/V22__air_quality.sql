-- Smog season by month from the Open-Meteo air-quality API (Copernicus CAMS global model, about 45 km grid).
-- The model understates absolute PM2.5 in India, so only a clearly polluted season is stated, never a number.
-- Results are kept per model grid cell, so neighbouring destinations share one download.
INSERT INTO source (id, name, category, access_method, base_url, licence_name, licence_url, attribution_text,
                    share_alike, usage_notes, rate_limit_policy, refresh_interval_days, enabled)
VALUES ('open-meteo-air', 'Open-Meteo Air Quality API (Copernicus CAMS)', 'climate_data',
        'API: two years of hourly modelled PM2.5 per 0.4° grid cell, reduced to monthly means; only the monthly means are stored',
        'https://open-meteo.com/en/docs/air-quality-api', 'CC BY 4.0', 'https://open-meteo.com/en/license',
        'Air quality estimates by Open-Meteo.com (CC BY 4.0) from the Copernicus Atmosphere Monitoring Service.',
        false,
        'Modelled, not measured. Good at showing the polluted season; actual readings in polluted months are often higher.',
        'Shared with the weather API''s free non-commercial allowance; a few cells per planner run.',
        365, true)
ON CONFLICT (id) DO NOTHING;

CREATE TABLE air_quality_cell (
    cell_key    text PRIMARY KEY,
    period      text        NOT NULL,
    months      jsonb       NOT NULL,
    request_url text        NOT NULL,
    fetched_at  timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE ingestion_job DROP CONSTRAINT ingestion_job_job_type_check;
ALTER TABLE ingestion_job ADD CONSTRAINT ingestion_job_job_type_check CHECK (job_type IN ('wikidata_index', 'wikivoyage_guide',
    'climate', 'place_image', 'resolve_request', 'coverage_refresh', 'osm_extract', 'overture_places', 'heritage_monuments',
    'quality_evaluation', 'match_resolution', 'ai_review', 'road_routes', 'nearest_transport', 'air_quality'));
