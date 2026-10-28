-- S6-02: a Skill run leaves a receipt, and a factory can save a successful run as a reusable Skill
-- with a parameter contract. Every step of a recorded run names the tool, the parameters actually
-- sent and the tool gateway's audit event, so a saved Skill can always be traced back to the run it
-- came from, and each rerun of it is a new receipt of its own. Rows are never deleted: a saved
-- Skill is switched off with enabled = false.
CREATE TABLE skill_run_records (
    id              VARCHAR(36)  PRIMARY KEY,
    factory_id      VARCHAR(50)  NOT NULL,
    user_id         BIGINT       NOT NULL,
    skill_name      VARCHAR(100) NOT NULL,
    -- Set when this run executed a saved Skill: the saved definition it ran.
    saved_skill_id  VARCHAR(36),
    status          VARCHAR(20)  NOT NULL
        CHECK (status IN ('SUCCEEDED', 'FAILED', 'DENIED', 'OUTCOME_UNKNOWN')),
    -- [{tool, descriptorVersion, params, gatewayStatus, auditEventId}]; auditEventId is null for a
    -- step that did not run through the tool gateway.
    steps           JSONB        NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_srr_factory_created ON skill_run_records (factory_id, created_at DESC);
CREATE INDEX idx_srr_saved_skill ON skill_run_records (saved_skill_id) WHERE saved_skill_id IS NOT NULL;

CREATE TABLE factory_saved_skills (
    id                  VARCHAR(36)  PRIMARY KEY,
    factory_id          VARCHAR(50)  NOT NULL,
    name                VARCHAR(100) NOT NULL,
    display_name        VARCHAR(200) NOT NULL,
    -- The successful run it was saved from, and that run's Skill.
    source_run_id       VARCHAR(36)  NOT NULL REFERENCES skill_run_records (id),
    source_skill_name   VARCHAR(100) NOT NULL,
    -- [{tool, params}]: the tools and captured parameters of the source run, in order. A rerun can
    -- change only the parameters the contract declares; it can never add a tool.
    steps               JSONB        NOT NULL,
    parameter_contract  JSONB        NOT NULL,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by          BIGINT       NOT NULL,
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_fss_factory_name UNIQUE (factory_id, name)
);
