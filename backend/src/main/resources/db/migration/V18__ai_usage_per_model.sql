-- Each free model has its own daily request limit, so usage is counted per model. Days follow Google's quota day
-- (midnight Pacific time), not UTC, so the counts match what Google AI Studio shows.
ALTER TABLE ai_usage ADD COLUMN model text NOT NULL DEFAULT 'all';
ALTER TABLE ai_usage DROP CONSTRAINT ai_usage_pkey;
ALTER TABLE ai_usage ADD PRIMARY KEY (day, model);
