-- AI review of ingested listings. The model is a reviewer, never an author: it reads a listing and records a
-- verdict with its reason. It cannot change guide text or facts. Doubtful listings become open flags that an
-- admin accepts (the listing is hidden) or dismisses. One verdict per listing per text version and prompt version.
CREATE TABLE ai_review (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    entry_id          uuid        NOT NULL REFERENCES guide_entry (id) ON DELETE CASCADE,
    destination_id    uuid        NOT NULL REFERENCES place (id) ON DELETE CASCADE,
    input_hash        text        NOT NULL,
    prompt_version    integer     NOT NULL,
    model             text        NOT NULL,
    fits_section      boolean     NOT NULL,
    in_destination    text        NOT NULL CHECK (in_destination IN ('yes', 'no', 'unclear')),
    issues            text[]      NOT NULL DEFAULT '{}',
    suggested_section text CHECK (suggested_section IN ('eat', 'drink', 'stay', 'see', 'do', 'buy', 'none')),
    confidence        real        NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    reason            text        NOT NULL CHECK (length(reason) <= 500),
    flagged           boolean     NOT NULL,
    status            text        NOT NULL CHECK (status IN ('clear', 'open', 'accepted', 'dismissed', 'superseded')),
    resolved_by       uuid REFERENCES app_user (id),
    resolved_at       timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ai_review_version_idx ON ai_review (entry_id, input_hash, prompt_version);
CREATE INDEX ai_review_open_idx ON ai_review (destination_id) WHERE status = 'open';

-- Requests per day, so a free model's daily quota is never exceeded.
CREATE TABLE ai_usage (
    day      date PRIMARY KEY,
    requests integer NOT NULL DEFAULT 0,
    tokens   bigint  NOT NULL DEFAULT 0
);

ALTER TABLE ingestion_job DROP CONSTRAINT ingestion_job_job_type_check;
ALTER TABLE ingestion_job ADD CONSTRAINT ingestion_job_job_type_check CHECK (job_type IN ('wikidata_index', 'wikivoyage_guide',
    'climate', 'place_image', 'resolve_request', 'coverage_refresh', 'osm_extract', 'overture_places', 'heritage_monuments',
    'quality_evaluation', 'match_resolution', 'ai_review'));
