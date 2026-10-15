-- W6: retain actor namespace for module configuration writes and publication.
ALTER TABLE IF EXISTS config_change_log
    ALTER COLUMN operator_id DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS actor_type VARCHAR(32),
    ADD COLUMN IF NOT EXISTS actor_id BIGINT;

ALTER TABLE IF EXISTS factory_configurations
    ALTER COLUMN created_by DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS published_actor_type VARCHAR(32),
    ADD COLUMN IF NOT EXISTS published_actor_id BIGINT;

-- The apply command consumes a draft exactly once.  V06's DRAFT-only check
-- must therefore admit the terminal APPLIED state used by the idempotency gate.
ALTER TABLE IF EXISTS module_config_drafts
    DROP CONSTRAINT IF EXISTS ck_module_config_draft_status,
    ADD CONSTRAINT ck_module_config_draft_status
        CHECK (status IN ('DRAFT', 'APPLIED'));
