-- AI reviews paused by the old single-budget rule were set to wake at midnight UTC. With per-model limits, spare
-- models can take them now, so let them run right away; they pause again by themselves when every model is spent.
-- Their notes are marked as waiting, so the jobs list does not show them as errors.
UPDATE ingestion_job
SET next_attempt_at = now(),
    last_error = 'Waiting: ' || last_error
WHERE job_type = 'ai_review' AND status = 'queued'
  AND last_error IS NOT NULL AND last_error NOT LIKE 'Waiting:%';
