-- Optional "email me when the guide is ready" for destination requests.
-- Opt-in only. The address exists only while a notification is pending: it is erased the moment the
-- email is sent (or given up on), so no list of reader emails accumulates. 'outcome' records what happened.
CREATE TABLE request_notification (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id      uuid        NOT NULL REFERENCES destination_request (id) ON DELETE CASCADE,
    email           text CHECK (email IS NULL OR (length(email) <= 254 AND email = lower(email))),
    outcome         text CHECK (outcome IN ('sent_published', 'sent_not_found', 'failed', 'expired')),
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error      text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    resolved_at     timestamptz,
    -- a pending notification has an address and no outcome; a finished one has an outcome and no address
    CONSTRAINT request_notification_state CHECK ((email IS NULL) = (outcome IS NOT NULL))
);

CREATE UNIQUE INDEX request_notification_once_idx ON request_notification (request_id, email) WHERE email IS NOT NULL;
CREATE INDEX request_notification_pending_idx ON request_notification (created_at) WHERE email IS NOT NULL;

-- The importer records that it is alive each time it polls, so the site can say honestly when it is not running.
CREATE TABLE importer_heartbeat (
    id      smallint PRIMARY KEY CHECK (id = 1),
    seen_at timestamptz NOT NULL
);
