-- Natural-language search: how a model read each distinct question (filters only, never answers), cached so the
-- same question costs one model request a month and a day's model use for search can be capped.
CREATE TABLE search_intent (
    query_norm text PRIMARY KEY,
    intent     jsonb       NOT NULL,
    model      text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX search_intent_created_idx ON search_intent (created_at);
