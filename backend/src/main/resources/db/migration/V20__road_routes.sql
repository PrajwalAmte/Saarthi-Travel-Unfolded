-- Road distance and drive time from a focus city (weekend-trips origin) to each guided place around it, computed
-- by OSRM over OpenStreetMap roads. Stored per pair so pages never call the router while a reader waits.
INSERT INTO source (id, name, category, access_method, base_url, licence_name, licence_url, attribution_text,
                    share_alike, usage_notes, rate_limit_policy, refresh_interval_days, enabled)
VALUES ('osrm', 'OSRM routing over OpenStreetMap', 'open_data',
        'API: the OSRM table service is asked once per batch of up to 90 places for car distance and duration from a '
            || 'weekend-trips origin; only the two numbers per place are stored',
        'https://project-osrm.org/', 'ODbL 1.0 (road data)', 'https://www.openstreetmap.org/copyright',
        'Routes computed by OSRM from OpenStreetMap road data, © OpenStreetMap contributors (ODbL).',
        false,
        'Durations assume a car at typical road speeds with no traffic, stops or breaks; real journeys take longer. '
            || 'Places whose coordinates are far from any mapped road get no drive time.',
        'One request per second at most, a handful of requests per origin, recomputed every 90 days.',
        90, true)
ON CONFLICT (id) DO NOTHING;

CREATE TABLE road_route (
    origin_id         uuid        NOT NULL REFERENCES place (id) ON DELETE CASCADE,
    destination_id    uuid        NOT NULL REFERENCES place (id) ON DELETE CASCADE,
    -- Null when the router found no road route (an island, or a point far from any road).
    distance_m        integer     CHECK (distance_m >= 0),
    duration_s        integer     CHECK (duration_s >= 0),
    -- How far the router had to move each end to reach a road; large values mean the time is only approximate.
    snap_m            integer     NOT NULL DEFAULT 0,
    provider          text        NOT NULL,
    computed_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (origin_id, destination_id)
);

ALTER TABLE ingestion_job DROP CONSTRAINT ingestion_job_job_type_check;
ALTER TABLE ingestion_job ADD CONSTRAINT ingestion_job_job_type_check CHECK (job_type IN ('wikidata_index', 'wikivoyage_guide',
    'climate', 'place_image', 'resolve_request', 'coverage_refresh', 'osm_extract', 'overture_places', 'heritage_monuments',
    'quality_evaluation', 'match_resolution', 'ai_review', 'road_routes'));
