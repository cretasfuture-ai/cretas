-- Delegated grants for writes made by cron schedules / event trigger chains (S6).
-- A grant binds one factory, one source (scheduler config or chain step), one tool, one run-as
-- user and one exact parameter digest, with a run budget and an expiry. It is consumed at most
-- once per run key; a repeated run key replays the same gateway idempotency key instead of
-- consuming again. Revocation is a state, rows are never deleted.
CREATE TABLE autonomous_tool_grants (
    id                 VARCHAR(36)  PRIMARY KEY,
    factory_id         VARCHAR(50)  NOT NULL,
    source_type        VARCHAR(16)  NOT NULL CHECK (source_type IN ('SCHEDULER', 'TRIGGER')),
    source_ref         VARCHAR(100) NOT NULL,
    tool_name          VARCHAR(100) NOT NULL,
    run_as_user_id     BIGINT       NOT NULL,
    parameters_digest  VARCHAR(128) NOT NULL,
    max_runs           INTEGER      NOT NULL CHECK (max_runs > 0),
    used_runs          INTEGER      NOT NULL DEFAULT 0 CHECK (used_runs >= 0 AND used_runs <= max_runs),
    expires_at         TIMESTAMP    NOT NULL,
    granted_by         BIGINT       NOT NULL,
    granted_at         TIMESTAMP    NOT NULL,
    revoked_at         TIMESTAMP,
    revoked_by         BIGINT,
    version            BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX idx_atg_lookup ON autonomous_tool_grants (factory_id, source_type, source_ref, tool_name, run_as_user_id);

CREATE TABLE autonomous_tool_grant_runs (
    id               VARCHAR(36)  PRIMARY KEY,
    grant_id         VARCHAR(36)  NOT NULL REFERENCES autonomous_tool_grants (id),
    run_key          VARCHAR(150) NOT NULL,
    idempotency_key  VARCHAR(200) NOT NULL,
    consumed_at      TIMESTAMP    NOT NULL,
    CONSTRAINT uk_atgr_grant_run UNIQUE (grant_id, run_key)
);
