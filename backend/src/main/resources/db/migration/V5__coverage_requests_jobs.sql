-- Destination coverage profile (derived from stored data by explicit quality rules) ---
CREATE TABLE destination_profile (
    place_id        uuid PRIMARY KEY REFERENCES place (id) ON DELETE CASCADE,
    coverage_status text        NOT NULL CHECK (coverage_status IN ('indexed', 'building', 'usable', 'well_covered', 'needs_refresh')),
    section_stats   jsonb       NOT NULL DEFAULT '{}'::jsonb,
    quality_checks  jsonb       NOT NULL DEFAULT '[]'::jsonb,
    version         integer     NOT NULL DEFAULT 1,
    computed_at     timestamptz NOT NULL,
    graduated_at    timestamptz
);

CREATE INDEX destination_profile_status_idx ON destination_profile (coverage_status);

-- Destination requests from readers (no account required) ------------------------------
CREATE TABLE destination_request (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tracking_code      text        NOT NULL UNIQUE,
    query_text         text        NOT NULL CHECK (length(query_text) BETWEEN 2 AND 120),
    normalized_query   text        NOT NULL,
    state_hint         text,
    resolved_place_id  uuid REFERENCES place (id) ON DELETE SET NULL,
    status             text        NOT NULL CHECK (status IN ('received', 'queued', 'processing', 'indexed_no_guide',
                                                               'building', 'published', 'needs_review',
                                                               'unresolvable', 'merged')),
    status_detail      text,
    merged_into_id     uuid REFERENCES destination_request (id),
    demand_count       integer     NOT NULL DEFAULT 1 CHECK (demand_count >= 1),
    candidates         jsonb       NOT NULL DEFAULT '[]'::jsonb,
    last_job_id        uuid,
    first_requested_at timestamptz NOT NULL DEFAULT now(),
    last_requested_at  timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX destination_request_normalized_idx ON destination_request (normalized_query, coalesce(state_hint, ''));
CREATE INDEX destination_request_place_idx ON destination_request (resolved_place_id);
CREATE INDEX destination_request_status_idx ON destination_request (status, demand_count DESC);

-- Ingestion job queue (Postgres-backed, claimed with FOR UPDATE SKIP LOCKED) -----------
CREATE TABLE ingestion_job (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    job_type        text        NOT NULL CHECK (job_type IN ('wikidata_index', 'wikivoyage_guide', 'climate',
                                                              'place_image', 'resolve_request', 'coverage_refresh')),
    source_id       text REFERENCES source (id),
    target_place_id uuid REFERENCES place (id) ON DELETE CASCADE,
    params          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    dedup_key       text        NOT NULL,
    status          text        NOT NULL DEFAULT 'queued' CHECK (status IN ('queued', 'running', 'succeeded', 'failed', 'cancelled')),
    priority        integer     NOT NULL DEFAULT 0,
    attempts        integer     NOT NULL DEFAULT 0,
    max_attempts    integer     NOT NULL DEFAULT 3,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    started_at      timestamptz,
    finished_at     timestamptz,
    heartbeat_at    timestamptz,
    last_error      text,
    stats           jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_by      text        NOT NULL CHECK (created_by IN ('scheduler', 'bootstrap', 'request', 'admin', 'system')),
    created_at      timestamptz NOT NULL DEFAULT now()
);

-- Only one queued or running job per dedup key (re-enqueueing is idempotent).
CREATE UNIQUE INDEX ingestion_job_active_dedup_idx ON ingestion_job (dedup_key) WHERE status IN ('queued', 'running');
CREATE INDEX ingestion_job_due_idx ON ingestion_job (status, priority DESC, next_attempt_at) WHERE status = 'queued';
CREATE INDEX ingestion_job_created_idx ON ingestion_job (created_at DESC);

ALTER TABLE destination_request
    ADD CONSTRAINT destination_request_job_fk FOREIGN KEY (last_job_id) REFERENCES ingestion_job (id) ON DELETE SET NULL;

-- Spring Session (JDBC) -----------------------------------------------------------------
CREATE TABLE spring_session (
    primary_id            char(36) NOT NULL,
    session_id            char(36) NOT NULL,
    creation_time         bigint   NOT NULL,
    last_access_time      bigint   NOT NULL,
    max_inactive_interval int      NOT NULL,
    expiry_time           bigint   NOT NULL,
    principal_name        varchar(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id char(36)     NOT NULL,
    attribute_name     varchar(200) NOT NULL,
    attribute_bytes    bytea        NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id) REFERENCES spring_session (primary_id) ON DELETE CASCADE
);
