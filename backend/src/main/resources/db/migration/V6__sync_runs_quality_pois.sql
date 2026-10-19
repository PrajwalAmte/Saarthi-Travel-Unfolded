-- Sync observability ------------------------------------------------------------------
-- A source registry row now records the outcome of each pipeline stage separately:
-- fetching, data-quality acceptance and publication are distinct facts.
ALTER TABLE source
    ADD COLUMN last_publication_at  timestamptz,
    ADD COLUMN quality_status       text NOT NULL DEFAULT 'not_evaluated'
        CHECK (quality_status IN ('not_evaluated', 'passed', 'warning', 'failed')),
    ADD COLUMN quality_evaluated_at timestamptz,
    ADD COLUMN dataset_version      text,
    ADD COLUMN publish_enabled      boolean NOT NULL DEFAULT true;

-- One row per execution of a source adapter, with real record-level counts.
CREATE TABLE source_sync_run (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id         text        NOT NULL REFERENCES source (id),
    job_id            uuid REFERENCES ingestion_job (id) ON DELETE SET NULL,
    scope             text        NOT NULL,
    dataset_version   text,
    status            text        NOT NULL DEFAULT 'running'
                      CHECK (status IN ('running', 'succeeded', 'succeeded_with_warnings', 'quality_failed', 'failed')),
    stage             text        NOT NULL DEFAULT 'fetch'
                      CHECK (stage IN ('fetch', 'validate', 'parse', 'normalize', 'resolve', 'persist', 'quality', 'publish', 'done')),
    started_at        timestamptz NOT NULL DEFAULT now(),
    finished_at       timestamptz,
    fetched           bigint      NOT NULL DEFAULT 0,
    parsed            bigint      NOT NULL DEFAULT 0,
    rejected          bigint      NOT NULL DEFAULT 0,
    inserted          bigint      NOT NULL DEFAULT 0,
    updated           bigint      NOT NULL DEFAULT 0,
    unchanged         bigint      NOT NULL DEFAULT 0,
    deleted           bigint      NOT NULL DEFAULT 0,
    published         bigint      NOT NULL DEFAULT 0,
    canonical_added   bigint      NOT NULL DEFAULT 0,
    matched_existing  bigint      NOT NULL DEFAULT 0,
    unresolved        bigint      NOT NULL DEFAULT 0,
    rejection_reasons jsonb       NOT NULL DEFAULT '{}'::jsonb,
    warnings          jsonb       NOT NULL DEFAULT '[]'::jsonb,
    errors            jsonb       NOT NULL DEFAULT '[]'::jsonb
);

CREATE INDEX source_sync_run_source_idx ON source_sync_run (source_id, started_at DESC);

-- Data-quality evaluation of a source (optionally of one run), with every check's
-- threshold and measured value so the outcome is explainable and reproducible.
CREATE TABLE quality_report (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id        text        NOT NULL REFERENCES source (id),
    run_id           uuid REFERENCES source_sync_run (id) ON DELETE SET NULL,
    scope            text        NOT NULL,
    generated_at     timestamptz NOT NULL DEFAULT now(),
    status           text        NOT NULL CHECK (status IN ('passed', 'warning', 'failed')),
    score            numeric(5, 1),
    score_components jsonb       NOT NULL DEFAULT '{}'::jsonb,
    metrics          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    checks           jsonb       NOT NULL DEFAULT '[]'::jsonb,
    samples          jsonb       NOT NULL DEFAULT '[]'::jsonb,
    limitations      jsonb       NOT NULL DEFAULT '[]'::jsonb,
    previous_id      uuid REFERENCES quality_report (id) ON DELETE SET NULL
);

CREATE INDEX quality_report_source_idx ON quality_report (source_id, generated_at DESC);

-- Points of interest -----------------------------------------------------------------------
-- Canonical mapped places (restaurants, stays, sights, stations). A POI is NOT a
-- recommendation: it becomes part of a guide only when linked to a guide entry or reviewed.
CREATE TABLE poi (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name              text        NOT NULL CHECK (length(name) BETWEEN 1 AND 300),
    name_normalized   text GENERATED ALWAYS AS (idi_normalize(name)) STORED,
    category          text        NOT NULL,
    category_group    text        NOT NULL CHECK (category_group IN ('eat', 'drink', 'stay', 'see', 'do', 'buy', 'transport')),
    latitude          double precision NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude         double precision NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    geog              geography(Point, 4326) GENERATED ALWAYS AS (
                          ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography) STORED,
    destination_id    uuid REFERENCES place (id) ON DELETE SET NULL,
    destination_km    double precision,
    district_id       uuid REFERENCES place (id) ON DELETE SET NULL,
    state_id          uuid REFERENCES place (id) ON DELETE SET NULL,
    wikidata_id       text CHECK (wikidata_id IS NULL OR wikidata_id ~ '^Q[0-9]+$'),
    status            text        NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'removed')),
    primary_source_id text        NOT NULL,
    seed_record_id    uuid,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX poi_seed_record_idx ON poi (seed_record_id) WHERE seed_record_id IS NOT NULL;
CREATE INDEX poi_geog_idx ON poi USING gist (geog);
CREATE INDEX poi_name_trgm_idx ON poi USING gin (name_normalized gin_trgm_ops);
CREATE INDEX poi_destination_idx ON poi (destination_id, category_group) WHERE status = 'active';
CREATE INDEX poi_wikidata_idx ON poi (wikidata_id) WHERE wikidata_id IS NOT NULL;

-- Every source's own record of a POI, with its original identifier and attributes. A POI
-- keeps one row per source, so independent provenance and conflicting values stay visible.
CREATE TABLE poi_source_record (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    poi_id            uuid REFERENCES poi (id) ON DELETE SET NULL,
    source_id         text        NOT NULL REFERENCES source (id),
    external_id       text        NOT NULL,
    source_version    text,
    source_timestamp  timestamptz,
    dataset_version   text,
    name              text,
    category_raw      text,
    category          text,
    category_group    text,
    latitude          double precision,
    longitude         double precision,
    geog              geography(Point, 4326) GENERATED ALWAYS AS (
                          CASE WHEN latitude IS NULL OR longitude IS NULL THEN NULL
                               ELSE ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography END) STORED,
    attributes        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    raw               jsonb       NOT NULL DEFAULT '{}'::jsonb,
    content_hash      text,
    changed_at        timestamptz NOT NULL DEFAULT now(),
    match_method      text,
    match_confidence  text CHECK (match_confidence IN ('source_identity', 'high', 'possible', 'new')),
    match_score       double precision,
    first_seen_at     timestamptz NOT NULL DEFAULT now(),
    last_seen_at      timestamptz NOT NULL DEFAULT now(),
    deleted_at        timestamptz,
    last_run_id       uuid REFERENCES source_sync_run (id) ON DELETE SET NULL,
    UNIQUE (source_id, external_id)
);

CREATE INDEX poi_source_record_poi_idx ON poi_source_record (poi_id);
CREATE INDEX poi_source_record_run_idx ON poi_source_record (source_id, last_seen_at);
CREATE INDEX poi_source_record_pending_idx ON poi_source_record (last_run_id) WHERE poi_id IS NULL;
CREATE INDEX poi_source_record_geog_idx ON poi_source_record USING gist (geog);

-- Weak matches are never merged automatically; they wait here for a reviewer.
CREATE TABLE poi_match_review (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    record_id          uuid        NOT NULL REFERENCES poi_source_record (id) ON DELETE CASCADE,
    candidate_poi_id   uuid REFERENCES poi (id) ON DELETE CASCADE,
    candidate_entry_id uuid REFERENCES guide_entry (id) ON DELETE CASCADE,
    score              double precision NOT NULL,
    reasons            jsonb       NOT NULL DEFAULT '[]'::jsonb,
    status             text        NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'merged', 'distinct')),
    created_at         timestamptz NOT NULL DEFAULT now(),
    resolved_at        timestamptz,
    resolved_by        uuid REFERENCES app_user (id) ON DELETE SET NULL,
    CHECK (candidate_poi_id IS NOT NULL OR candidate_entry_id IS NOT NULL)
);

CREATE UNIQUE INDEX poi_match_review_poi_idx ON poi_match_review (record_id, candidate_poi_id) WHERE candidate_poi_id IS NOT NULL;
CREATE UNIQUE INDEX poi_match_review_entry_idx ON poi_match_review (record_id, candidate_entry_id) WHERE candidate_entry_id IS NOT NULL;

-- Guide entries can be linked to the mapped POI they describe; entries whose source
-- coordinates contradict their destination are flagged instead of being plotted.
ALTER TABLE guide_entry
    ADD COLUMN poi_id   uuid REFERENCES poi (id) ON DELETE SET NULL,
    ADD COLUMN geo_flag text CHECK (geo_flag IN ('far_from_destination', 'outside_india'));

CREATE INDEX guide_entry_poi_idx ON guide_entry (poi_id) WHERE poi_id IS NOT NULL;

-- Places can carry identities from additional sources (e.g. the OSM node of a town).
CREATE TABLE place_source_identity (
    place_id    uuid        NOT NULL REFERENCES place (id) ON DELETE CASCADE,
    source_id   text        NOT NULL REFERENCES source (id),
    external_id text        NOT NULL,
    attributes  jsonb       NOT NULL DEFAULT '{}'::jsonb,
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (source_id, external_id)
);

CREATE INDEX place_source_identity_place_idx ON place_source_identity (place_id);

ALTER TABLE ingestion_job DROP CONSTRAINT ingestion_job_job_type_check;
ALTER TABLE ingestion_job ADD CONSTRAINT ingestion_job_job_type_check CHECK (job_type IN (
    'wikidata_index', 'wikivoyage_guide', 'climate', 'place_image', 'resolve_request', 'coverage_refresh',
    'osm_extract', 'overture_places', 'heritage_monuments', 'quality_evaluation'));

-- New sources -------------------------------------------------------------------------------
INSERT INTO source (id, name, category, access_method, base_url, licence_name, licence_url, attribution_text,
                    share_alike, usage_notes, rate_limit_policy, refresh_interval_days, enabled)
VALUES ('openstreetmap', 'OpenStreetMap (Geofabrik India extract)', 'open_data',
        'Release-based: the daily Geofabrik India extract (india-latest.osm.pbf) is downloaded and stream-parsed; '
            || 'named POIs (food, stays, sights, heritage, parks, markets, stations) and place nodes with Wikidata tags are kept',
        'https://download.geofabrik.de/asia/india.html', 'ODbL 1.0', 'https://www.openstreetmap.org/copyright',
        '© OpenStreetMap contributors, available under the Open Database Licence.',
        true,
        'Original OSM ids, versions, timestamps and tags are kept per record. Mapped POIs are not recommendations. '
            || 'Redistributing the POI database requires ODbL share-alike.',
        'One extract download per refresh; Geofabrik asks for no more than a few downloads per day.',
        7, true),
       ('overture-places', 'Overture Maps Places', 'open_data',
        'Release-based: the Overture places GeoParquet release is queried by bounding box around guide destinations '
            || '(DuckDB over the public S3 bucket); release version recorded per record',
        'https://docs.overturemaps.org/guides/places/', 'CDLA Permissive 2.0', 'https://cdla.dev/permissive-2-0/',
        'Places data from the Overture Maps Foundation (CDLA Permissive 2.0), compiled from contributors including Meta and Microsoft.',
        false,
        'Matched to existing POIs instead of duplicating them; each Overture record keeps its GERS id and upstream sources.',
        'One bounding-box query per destination per release.',
        30, true),
       ('asi-heritage', 'ASI monuments of national importance (via Wikidata)', 'open_data',
        'Wikidata items carrying an Archaeological Survey of India monument identifier (P1371); ASI itself publishes '
            || 'the register only as per-circle web pages and PDFs without a machine-readable API',
        'https://asi.nic.in/', 'CC0 1.0 (Wikidata statements)', 'https://creativecommons.org/publicdomain/zero/1.0/',
        'Heritage designations from Wikidata, citing Archaeological Survey of India monument numbers.',
        false,
        'Each fact links the Wikidata item and records the ASI monument number; no data is taken from ASI pages directly.',
        'Paged SPARQL queries, paced like other Wikidata access.',
        90, true);
