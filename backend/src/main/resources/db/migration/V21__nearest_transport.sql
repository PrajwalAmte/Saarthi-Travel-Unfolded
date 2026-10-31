-- Nearest airport with scheduled flights (OurAirports, public domain) and nearest railway station (OpenStreetMap)
-- for each guided destination, stored as computed "getting there" facts with their sources.
INSERT INTO source (id, name, category, access_method, base_url, licence_name, licence_url, attribution_text,
                    share_alike, usage_notes, rate_limit_policy, refresh_interval_days, enabled)
VALUES ('ourairports', 'OurAirports', 'open_data',
        'Release-based: the airports.csv export is downloaded at most monthly; Indian airports marked as having '
            || 'scheduled service are kept',
        'https://ourairports.com/data/', 'Public domain', 'https://ourairports.com/data/',
        'Airport data from OurAirports (public domain).',
        false,
        'The scheduled-service flag is kept by volunteers and can lag when routes start or stop; check with the airline.',
        'One file download per month.',
        30, true)
ON CONFLICT (id) DO NOTHING;

CREATE TABLE airport (
    ident        text PRIMARY KEY,
    name         text             NOT NULL,
    iata_code    text,
    municipality text,
    kind         text             NOT NULL,
    scheduled    boolean          NOT NULL,
    latitude     double precision NOT NULL,
    longitude    double precision NOT NULL,
    geog         geography(Point, 4326) GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography) STORED,
    loaded_at    timestamptz      NOT NULL DEFAULT now()
);

CREATE INDEX airport_geog_idx ON airport USING gist (geog) WHERE scheduled;

ALTER TABLE ingestion_job DROP CONSTRAINT ingestion_job_job_type_check;
ALTER TABLE ingestion_job ADD CONSTRAINT ingestion_job_job_type_check CHECK (job_type IN ('wikidata_index', 'wikivoyage_guide',
    'climate', 'place_image', 'resolve_request', 'coverage_refresh', 'osm_extract', 'overture_places', 'heritage_monuments',
    'quality_evaluation', 'match_resolution', 'ai_review', 'road_routes', 'nearest_transport'));
