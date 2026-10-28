-- What happened to each AI flag, who decided it, and enough to undo it. Confident flags are decided automatically.
ALTER TABLE ai_review ADD COLUMN resolution text CHECK (resolution IN ('moved', 'hidden', 'kept', 'noted'));
ALTER TABLE ai_review ADD COLUMN decided_automatically boolean NOT NULL DEFAULT false;
ALTER TABLE ai_review ADD COLUMN previous_section text;
CREATE INDEX ai_review_decided_idx ON ai_review (resolved_at DESC) WHERE status IN ('accepted', 'dismissed');
