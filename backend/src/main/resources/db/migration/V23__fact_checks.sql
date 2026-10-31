-- Automatic checks of stale prices and opening hours against each place's own website (the "checker" agent).
-- Every check is kept, with the exact quote it relied on; applied changes can be undone.
ALTER TABLE source DROP CONSTRAINT source_category_check;
ALTER TABLE source ADD CONSTRAINT source_category_check
    CHECK (category IN ('open_data', 'open_guide', 'climate_data', 'media', 'community', 'official_site'));

INSERT INTO source (id, name, category, access_method, base_url, licence_name, licence_url, attribution_text,
                    share_alike, usage_notes, rate_limit_policy, refresh_interval_days, enabled)
VALUES ('official-website', 'Official websites (checked automatically)', 'official_site',
        'Agent: a stale price or opening hours is looked up on the website already recorded for that place, following at '
            || 'most two links on the same site; robots.txt is respected and only a short quote is kept',
        NULL, 'Facts only; short quotes as evidence', NULL,
        'Checked against the place''s own website; the quote and page are linked.',
        false,
        'A model reads the page and must quote it word for word; the quote and the numbers are verified before anything '
            || 'changes, and every change can be undone.',
        'At most three pages per check, two seconds apart, and a handful of checks per destination each month.',
        30, true)
ON CONFLICT (id) DO NOTHING;

ALTER TABLE fact_observation DROP CONSTRAINT fact_observation_change_type_check;
ALTER TABLE fact_observation ADD CONSTRAINT fact_observation_change_type_check
    CHECK (change_type IN ('initial', 'source_update', 'community_correction', 'closure_report', 'reverted', 'automatic_check'));

CREATE TABLE fact_check (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    destination_id       uuid        NOT NULL REFERENCES place (id) ON DELETE CASCADE,
    entry_id             uuid        REFERENCES guide_entry (id) ON DELETE CASCADE,
    fact_id              uuid        NOT NULL REFERENCES fact (id) ON DELETE CASCADE,
    verdict              text        NOT NULL CHECK (verdict IN ('confirmed', 'changed', 'not_stated', 'closed', 'unreadable', 'rejected')),
    -- What happened: applied (fact re-dated or replaced), noted (left for a person), or skipped.
    outcome              text        NOT NULL CHECK (outcome IN ('applied', 'noted', 'skipped')),
    page_url             text,
    quote                text,
    new_value            text,
    reason               text,
    new_fact_id          uuid        REFERENCES fact (id) ON DELETE SET NULL,
    evidence_id          uuid,
    previous_observed_at date,
    previous_basis       text,
    model                text,
    pages_read           integer     NOT NULL DEFAULT 0,
    undone_at            timestamptz,
    undone_by            uuid,
    created_at           timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX fact_check_fact_idx ON fact_check (fact_id, created_at DESC);
CREATE INDEX fact_check_destination_idx ON fact_check (destination_id, created_at DESC);

ALTER TABLE ingestion_job DROP CONSTRAINT ingestion_job_job_type_check;
ALTER TABLE ingestion_job ADD CONSTRAINT ingestion_job_job_type_check CHECK (job_type IN ('wikidata_index', 'wikivoyage_guide',
    'climate', 'place_image', 'resolve_request', 'coverage_refresh', 'osm_extract', 'overture_places', 'heritage_monuments',
    'quality_evaluation', 'match_resolution', 'ai_review', 'road_routes', 'nearest_transport', 'air_quality', 'fact_check'));
