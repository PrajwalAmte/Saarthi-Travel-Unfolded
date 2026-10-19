-- Guide entries ------------------------------------------------------------------
-- A recommendation-shaped item inside a destination guide (a restaurant, a stay,
-- an attraction, a neighbourhood). Its individual claims live in "fact".
CREATE TABLE guide_entry (
    id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    destination_id         uuid        NOT NULL REFERENCES place (id) ON DELETE CASCADE,
    section                text        NOT NULL CHECK (section IN ('eat', 'drink', 'stay', 'see', 'do', 'buy', 'neighbourhood')),
    subsection             text,
    area                   text,
    title                  text        NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
    title_normalized       text GENERATED ALWAYS AS (idi_normalize(title)) STORED,
    alt_title              text,
    latitude               double precision CHECK (latitude BETWEEN -90 AND 90),
    longitude              double precision CHECK (longitude BETWEEN -180 AND 180),
    geog                   geography(Point, 4326) GENERATED ALWAYS AS (
                               CASE
                                   WHEN latitude IS NULL OR longitude IS NULL THEN NULL
                                   ELSE ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography
                               END) STORED,
    wikidata_id            text CHECK (wikidata_id ~ '^Q[0-9]+$'),
    sitelink_count         integer,
    attributes             jsonb       NOT NULL DEFAULT '{}'::jsonb,
    status                 text        NOT NULL DEFAULT 'published'
                                       CHECK (status IN ('published', 'pending', 'hidden', 'closed', 'merged', 'removed')),
    status_reason          text,
    merged_into_id         uuid REFERENCES guide_entry (id),
    origin                 text        NOT NULL CHECK (origin IN ('ingested', 'community')),
    ingest_key             text UNIQUE,
    last_seen_in_source_at timestamptz,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX guide_entry_destination_idx ON guide_entry (destination_id, section) WHERE status = 'published';
CREATE INDEX guide_entry_title_trgm_idx ON guide_entry USING gin (title_normalized gin_trgm_ops);
CREATE INDEX guide_entry_geog_idx ON guide_entry USING gist (geog);

-- Facts ----------------------------------------------------------------------------
-- An atomic, typed claim. Destination-level facts (summary, transport, tips) have no
-- entry; entry-level facts describe one guide entry (description, price, hours...).
CREATE TABLE fact (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    destination_id       uuid        NOT NULL REFERENCES place (id) ON DELETE CASCADE,
    entry_id             uuid REFERENCES guide_entry (id) ON DELETE CASCADE,
    section              text        NOT NULL CHECK (section IN ('overview', 'eat', 'drink', 'stay', 'see', 'do', 'buy',
                                                                 'neighbourhood', 'getting_around', 'getting_there',
                                                                 'tips', 'practical')),
    kind                 text        NOT NULL CHECK (kind IN ('summary', 'description', 'price', 'hours', 'address',
                                                              'phone', 'website', 'speciality', 'climate',
                                                              'seasonality', 'transport', 'safety', 'health',
                                                              'customs', 'connectivity', 'orientation',
                                                              'neighbourhood_character', 'accommodation_note',
                                                              'general_tip', 'what_to_order', 'accessibility')),
    heading              text,
    value_text           text        NOT NULL CHECK (length(value_text) BETWEEN 1 AND 4000),
    value_json           jsonb,
    volatility           text        NOT NULL CHECK (volatility IN ('stable', 'semi_stable', 'volatile')),
    status               text        NOT NULL DEFAULT 'published'
                                     CHECK (status IN ('published', 'pending', 'superseded', 'rejected', 'retracted', 'hidden')),
    origin               text        NOT NULL CHECK (origin IN ('ingested', 'community')),
    ingest_key           text UNIQUE,
    observed_at          date,
    observed_basis       text        NOT NULL CHECK (observed_basis IN ('source_listing_date', 'computed_period',
                                                                        'community_report', 'unknown')),
    source_refreshed_at  timestamptz,
    last_verified_at     timestamptz,
    supersedes_fact_id   uuid REFERENCES fact (id),
    contribution_id      uuid,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX fact_destination_idx ON fact (destination_id, section) WHERE status = 'published';
CREATE INDEX fact_entry_idx ON fact (entry_id) WHERE entry_id IS NOT NULL;

-- Evidence: where a fact came from. A source is a reference, not proof.
CREATE TABLE evidence (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    fact_id             uuid        NOT NULL REFERENCES fact (id) ON DELETE CASCADE,
    source_id           text        NOT NULL REFERENCES source (id),
    url                 text CHECK (url IS NULL OR url ~ '^https?://'),
    permalink           text CHECK (permalink IS NULL OR permalink ~ '^https?://'),
    title               text,
    excerpt             text,
    extraction_method   text        NOT NULL,
    source_revision     text,
    source_revision_at  timestamptz,
    observed_at         date,
    retrieved_at        timestamptz NOT NULL,
    contributor_user_id uuid,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CHECK (source_id = 'community' OR url IS NOT NULL)
);

CREATE UNIQUE INDEX evidence_fact_source_url_idx ON evidence (fact_id, source_id, coalesce(url, ''));
CREATE INDEX evidence_source_idx ON evidence (source_id);

-- History of meaningful value changes (prices, hours, closures, corrections).
CREATE TABLE fact_observation (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    fact_id         uuid        NOT NULL REFERENCES fact (id) ON DELETE CASCADE,
    value_text      text        NOT NULL,
    value_json      jsonb,
    observed_at     date,
    change_type     text        NOT NULL CHECK (change_type IN ('initial', 'source_update', 'community_correction',
                                                                'closure_report', 'reverted')),
    source_id       text REFERENCES source (id),
    contribution_id uuid,
    recorded_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX fact_observation_fact_idx ON fact_observation (fact_id, recorded_at DESC);
