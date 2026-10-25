-- S307: release an approved stocktake whose live inventory drifted before apply.
-- The original snapshot and approval fields remain immutable; only recovery audit
-- metadata and the lifecycle status are added.
ALTER TABLE factory_stocktakes
    ADD COLUMN IF NOT EXISTS recount_requested_by BIGINT,
    ADD COLUMN IF NOT EXISTS recount_requested_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS recount_reason TEXT,
    ADD COLUMN IF NOT EXISTS recount_source_version BIGINT,
    ADD COLUMN IF NOT EXISTS recount_stocktake_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS recount_source_stocktake_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_stocktake_recount_source
    ON factory_stocktakes(recount_source_stocktake_id);

CREATE INDEX IF NOT EXISTS idx_stocktake_recount_child
    ON factory_stocktakes(recount_stocktake_id);

COMMENT ON COLUMN factory_stocktakes.recount_requested_by IS
    'S307 drift recovery actor; original approval actor is preserved';
COMMENT ON COLUMN factory_stocktakes.recount_requested_at IS
    'S307 drift recovery time; original approval time is preserved';
COMMENT ON COLUMN factory_stocktakes.recount_reason IS
    'S307 operator reason for the new count cycle';
COMMENT ON COLUMN factory_stocktakes.recount_source_version IS
    'Version supplied for the one-time recovery request';
COMMENT ON COLUMN factory_stocktakes.recount_stocktake_id IS
    'Fresh stocktake created after this approved task drifted';
COMMENT ON COLUMN factory_stocktakes.recount_source_stocktake_id IS
    'Approved stocktake that caused this fresh recount task';
