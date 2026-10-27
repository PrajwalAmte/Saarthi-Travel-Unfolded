-- AI improvements to guide content, stored beside the source data and never in place of it:
--   * a short readable summary of a listing's description (kept only if it passes a faithfulness check),
--   * tags (interests, local food, accessibility, price level), each with the exact words that justify it,
--   * prices and opening hours found in description text, kept only as verbatim quotes.
-- Deleting these rows returns every guide to exactly what its sources say.
CREATE TABLE ai_enrichment (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    entry_id          uuid        NOT NULL REFERENCES guide_entry (id) ON DELETE CASCADE,
    destination_id    uuid        NOT NULL REFERENCES place (id) ON DELETE CASCADE,
    input_hash        text        NOT NULL,
    prompt_version    integer     NOT NULL,
    model             text        NOT NULL,
    summary           text CHECK (summary IS NULL OR length(summary) <= 400),
    summary_status    text        NOT NULL CHECK (summary_status IN ('accepted', 'rejected', 'not_needed')),
    summary_rejection text,
    tags              jsonb       NOT NULL DEFAULT '[]'::jsonb,
    extracted         jsonb       NOT NULL DEFAULT '[]'::jsonb,
    created_at        timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ai_enrichment_version_idx ON ai_enrichment (entry_id, input_hash, prompt_version);
CREATE INDEX ai_enrichment_destination_idx ON ai_enrichment (destination_id, created_at DESC);

-- A destination's "In short": a few sentences written only from facts already in the guide, each citing them.
CREATE TABLE ai_overview (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    destination_id uuid        NOT NULL REFERENCES place (id) ON DELETE CASCADE,
    input_hash     text        NOT NULL,
    prompt_version integer     NOT NULL,
    model          text        NOT NULL,
    items          jsonb       NOT NULL,
    rejected       jsonb       NOT NULL DEFAULT '[]'::jsonb,
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ai_overview_version_idx ON ai_overview (destination_id, input_hash, prompt_version);

-- Operator settings shared by every instance that uses this database (unlike environment variables).
CREATE TABLE app_setting (
    key        text PRIMARY KEY,
    value      text        NOT NULL,
    updated_by uuid REFERENCES app_user (id),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Whether readers see AI improvements. Off until an admin has checked samples and switches it on.
INSERT INTO app_setting (key, value) VALUES ('ai.publish', 'false');
