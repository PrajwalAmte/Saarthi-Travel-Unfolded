-- Places and destinations ------------------------------------------------------
-- A place is any searchable geographic entity: a state, district, city, town,
-- region, park or neighbourhood. Geography is a graph (place_relation), not a tree.
CREATE TABLE place (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug             text        NOT NULL UNIQUE,
    name             text        NOT NULL,
    name_normalized  text GENERATED ALWAYS AS (idi_normalize(name)) STORED,
    kind             text        NOT NULL CHECK (kind IN ('country', 'state', 'union_territory', 'district', 'region',
                                                          'city', 'town', 'village', 'neighbourhood', 'park',
                                                          'island', 'attraction', 'other')),
    description      text,
    latitude         double precision CHECK (latitude BETWEEN -90 AND 90),
    longitude        double precision CHECK (longitude BETWEEN -180 AND 180),
    geog             geography(Point, 4326) GENERATED ALWAYS AS (
                         CASE
                             WHEN latitude IS NULL OR longitude IS NULL THEN NULL
                             ELSE ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography
                         END) STORED,
    population       bigint CHECK (population >= 0),
    wikidata_id      text UNIQUE CHECK (wikidata_id ~ '^Q[0-9]+$'),
    wikivoyage_title text UNIQUE,
    iso_code         text,
    sitelink_count   integer,
    is_destination   boolean     NOT NULL DEFAULT true,
    lifecycle_status text        NOT NULL DEFAULT 'active' CHECK (lifecycle_status IN ('active', 'merged', 'removed')),
    merged_into_id   uuid REFERENCES place (id),
    origin           text        NOT NULL DEFAULT 'ingested' CHECK (origin IN ('ingested', 'community')),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX place_name_trgm_idx ON place USING gin (name_normalized gin_trgm_ops);
CREATE INDEX place_geog_idx ON place USING gist (geog);
CREATE INDEX place_kind_idx ON place (kind) WHERE lifecycle_status = 'active';

-- Names, alternate spellings, historical names and names in Indian scripts.
CREATE TABLE place_alias (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    place_id   uuid        NOT NULL REFERENCES place (id) ON DELETE CASCADE,
    alias      text        NOT NULL,
    normalized text GENERATED ALWAYS AS (idi_normalize(alias)) STORED,
    language   text        NOT NULL DEFAULT 'und',
    alias_type text        NOT NULL CHECK (alias_type IN ('label', 'alternate', 'historical', 'local_script', 'source_title', 'community')),
    source_id  text REFERENCES source (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (place_id, alias, language)
);

CREATE INDEX place_alias_trgm_idx ON place_alias USING gin (normalized gin_trgm_ops);
CREATE INDEX place_alias_normalized_idx ON place_alias (normalized text_pattern_ops);
CREATE INDEX place_alias_place_idx ON place_alias (place_id);

-- Geographic relationships. A place may have several parents (a park spanning two
-- districts, a region within a state) and relation types are explicit.
CREATE TABLE place_relation (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    child_id   uuid        NOT NULL REFERENCES place (id) ON DELETE CASCADE,
    parent_id  uuid        NOT NULL REFERENCES place (id) ON DELETE CASCADE,
    relation   text        NOT NULL CHECK (relation IN ('in_state', 'in_district', 'administrative_parent', 'within_region', 'part_of_city')),
    source_id  text REFERENCES source (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (child_id <> parent_id),
    UNIQUE (child_id, parent_id, relation)
);

CREATE INDEX place_relation_parent_idx ON place_relation (parent_id, relation);

-- Freely licensed image for a destination, with mandatory credit.
CREATE TABLE place_image (
    place_id     uuid PRIMARY KEY REFERENCES place (id) ON DELETE CASCADE,
    source_id    text        NOT NULL REFERENCES source (id),
    file_title   text        NOT NULL,
    image_url    text        NOT NULL CHECK (image_url ~ '^https://'),
    width        integer,
    height       integer,
    page_url     text        NOT NULL CHECK (page_url ~ '^https://'),
    artist       text,
    licence_name text        NOT NULL,
    licence_url  text,
    fetched_at   timestamptz NOT NULL
);

-- Incremental sync state per place and source (revision tracking for idempotency).
CREATE TABLE place_source_sync (
    place_id       uuid        NOT NULL REFERENCES place (id) ON DELETE CASCADE,
    source_id      text        NOT NULL REFERENCES source (id),
    external_id    text        NOT NULL,
    last_revision  text,
    parser_version integer,
    last_synced_at timestamptz,
    last_status    text CHECK (last_status IN ('succeeded', 'unchanged', 'failed', 'not_found', 'skipped')),
    last_error     text,
    stats          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (place_id, source_id, external_id)
);
