-- Contributors ---------------------------------------------------------------------
-- Readers never have an account. A row exists only for signed-in contributors.
CREATE TABLE app_user (
    id                        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    display_name              text        NOT NULL CHECK (length(display_name) BETWEEN 1 AND 60),
    email                     text CHECK (email IS NULL OR email = lower(email)),
    email_verified_at         timestamptz,
    tier                      text        NOT NULL DEFAULT 'contributor' CHECK (tier IN ('contributor', 'trusted', 'steward')),
    is_admin                  boolean     NOT NULL DEFAULT false,
    attribution               text        NOT NULL DEFAULT 'display_name' CHECK (attribution IN ('display_name', 'anonymous')),
    contributor_licence       text,
    contributor_licence_at    timestamptz,
    status                    text        NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'suspended', 'deleted')),
    last_login_at             timestamptz,
    created_at                timestamptz NOT NULL DEFAULT now(),
    updated_at                timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX app_user_email_idx ON app_user (email) WHERE email IS NOT NULL;

CREATE TABLE user_identity (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    provider   text        NOT NULL CHECK (provider IN ('email', 'google', 'github')),
    subject    text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (provider, subject)
);

-- Single-use, short-lived email sign-in tokens. Only a SHA-256 hash is stored.
CREATE TABLE login_token (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email        text        NOT NULL,
    token_hash   text        NOT NULL UNIQUE,
    display_name text,
    expires_at   timestamptz NOT NULL,
    consumed_at  timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX login_token_email_idx ON login_token (email, created_at DESC);

CREATE TABLE steward_assignment (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    place_id    uuid        NOT NULL REFERENCES place (id) ON DELETE CASCADE,
    assigned_by uuid REFERENCES app_user (id) ON DELETE SET NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, place_id)
);

CREATE TABLE trust_event (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    event_type    text        NOT NULL CHECK (event_type IN ('promoted', 'demoted', 'steward_assigned', 'steward_removed',
                                                             'suspended', 'reinstated', 'admin_granted')),
    from_tier     text,
    to_tier       text,
    reason        text        NOT NULL,
    actor_user_id uuid REFERENCES app_user (id) ON DELETE SET NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- "Still true?" verification records -------------------------------------------------
CREATE TABLE verification (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    fact_id         uuid        NOT NULL REFERENCES fact (id) ON DELETE CASCADE,
    user_id         uuid REFERENCES app_user (id) ON DELETE SET NULL,
    verdict         text        NOT NULL CHECK (verdict IN ('confirm', 'deny')),
    note            text CHECK (note IS NULL OR length(note) <= 500),
    evidence_url    text CHECK (evidence_url IS NULL OR evidence_url ~ '^https?://'),
    status          text        NOT NULL CHECK (status IN ('pending', 'accepted', 'rejected', 'withdrawn')),
    decision_reason text,
    decided_by      uuid REFERENCES app_user (id) ON DELETE SET NULL,
    decided_at      timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX verification_fact_idx ON verification (fact_id, status);
CREATE INDEX verification_user_idx ON verification (user_id, created_at DESC);

-- Contributions (corrections, reports, proposals) ------------------------------------
CREATE TABLE contribution (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              uuid REFERENCES app_user (id) ON DELETE SET NULL,
    type                 text        NOT NULL CHECK (type IN ('correction', 'closure_report', 'duplicate_report',
                                                              'incorrect_report', 'new_place', 'new_tip')),
    destination_id       uuid        NOT NULL REFERENCES place (id) ON DELETE CASCADE,
    target_fact_id       uuid REFERENCES fact (id) ON DELETE SET NULL,
    target_entry_id      uuid REFERENCES guide_entry (id) ON DELETE SET NULL,
    payload              jsonb       NOT NULL,
    note                 text CHECK (note IS NULL OR length(note) <= 1000),
    evidence_url         text CHECK (evidence_url IS NULL OR evidence_url ~ '^https?://'),
    conflict_of_interest boolean     NOT NULL DEFAULT false,
    coi_details          text,
    high_impact          boolean     NOT NULL DEFAULT false,
    status               text        NOT NULL CHECK (status IN ('pending', 'accepted', 'rejected', 'withdrawn', 'reverted')),
    decision_reason      text,
    decided_by           uuid REFERENCES app_user (id) ON DELETE SET NULL,
    decided_at           timestamptz,
    result_entry_id      uuid REFERENCES guide_entry (id) ON DELETE SET NULL,
    result_fact_id       uuid REFERENCES fact (id) ON DELETE SET NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX contribution_user_idx ON contribution (user_id, created_at DESC);
CREATE INDEX contribution_destination_idx ON contribution (destination_id, status);
CREATE INDEX contribution_target_fact_idx ON contribution (target_fact_id) WHERE status = 'pending';

ALTER TABLE fact
    ADD CONSTRAINT fact_contribution_fk FOREIGN KEY (contribution_id) REFERENCES contribution (id) ON DELETE SET NULL;
ALTER TABLE evidence
    ADD CONSTRAINT evidence_contributor_fk FOREIGN KEY (contributor_user_id) REFERENCES app_user (id) ON DELETE SET NULL;
ALTER TABLE fact_observation
    ADD CONSTRAINT fact_observation_contribution_fk FOREIGN KEY (contribution_id) REFERENCES contribution (id) ON DELETE SET NULL;

-- Abuse flags --------------------------------------------------------------------------
CREATE TABLE abuse_flag (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_user_id uuid REFERENCES app_user (id) ON DELETE SET NULL,
    target_type      text        NOT NULL CHECK (target_type IN ('contribution', 'verification', 'entry', 'fact', 'user')),
    target_id        uuid        NOT NULL,
    reason           text        NOT NULL CHECK (reason IN ('spam', 'self_promotion', 'offensive', 'misleading', 'other')),
    details          text CHECK (details IS NULL OR length(details) <= 1000),
    status           text        NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'actioned', 'dismissed')),
    resolved_by      uuid REFERENCES app_user (id) ON DELETE SET NULL,
    resolved_at      timestamptz,
    resolution       text,
    created_at       timestamptz NOT NULL DEFAULT now()
);

-- Moderation queue -----------------------------------------------------------------------
CREATE TABLE moderation_item (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    item_type      text        NOT NULL CHECK (item_type IN ('contribution', 'verification', 'abuse_flag')),
    item_id        uuid        NOT NULL,
    destination_id uuid REFERENCES place (id) ON DELETE CASCADE,
    reasons        jsonb       NOT NULL DEFAULT '[]'::jsonb,
    requires_role  text        NOT NULL CHECK (requires_role IN ('trusted', 'steward')),
    priority       integer     NOT NULL DEFAULT 0,
    status         text        NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'resolved')),
    resolution     text,
    resolved_by    uuid REFERENCES app_user (id) ON DELETE SET NULL,
    resolved_at    timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (item_type, item_id)
);

CREATE INDEX moderation_item_open_idx ON moderation_item (status, priority DESC, created_at);

-- Audit log: append-only record of every state-changing action. -------------------------
CREATE TABLE audit_log (
    id             bigserial PRIMARY KEY,
    actor_user_id  uuid REFERENCES app_user (id) ON DELETE SET NULL,
    actor_type     text        NOT NULL CHECK (actor_type IN ('user', 'system')),
    action         text        NOT NULL,
    entity_type    text        NOT NULL,
    entity_id      text        NOT NULL,
    destination_id uuid,
    details        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX audit_log_entity_idx ON audit_log (entity_type, entity_id, created_at DESC);
CREATE INDEX audit_log_actor_idx ON audit_log (actor_user_id, created_at DESC);
