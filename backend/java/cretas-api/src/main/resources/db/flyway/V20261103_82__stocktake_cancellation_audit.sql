-- S302: audited cancellation for an unsubmitted stocktake by its maker.
-- The columns are nullable so legacy rows and rollback-compatible readers remain valid.
ALTER TABLE factory_stocktakes
    ADD COLUMN IF NOT EXISTS cancelled_by BIGINT,
    ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS cancel_reason TEXT;

COMMENT ON COLUMN factory_stocktakes.cancelled_by IS
    'S302 cancellation actor; only the active same-factory initiator may cancel';
COMMENT ON COLUMN factory_stocktakes.cancelled_at IS
    'S302 cancellation timestamp';
COMMENT ON COLUMN factory_stocktakes.cancel_reason IS
    'S302 operator-provided cancellation reason';
