-- A settlement is an immutable audit row.  Re-opening a plan must retain its
-- soft-deleted settlement while allowing one replacement active settlement.
-- The idempotency key remains a table-wide unique key (including tombstones),
-- so replay protection is not weakened by this change.
ALTER TABLE production_settlements
    DROP CONSTRAINT IF EXISTS uq_prod_settlement_plan;

CREATE UNIQUE INDEX IF NOT EXISTS uq_prod_settlement_plan_active
    ON production_settlements(factory_id, production_plan_id)
    WHERE deleted_at IS NULL;
