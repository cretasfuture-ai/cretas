-- Opaque, tenant- and command-bound references for product_create reconciliation.
-- No bearer token, idempotency key, parameters, or downstream payload is stored.
SET LOCAL lock_timeout = '5s';

ALTER TABLE tool_execution_idempotency
    ADD COLUMN IF NOT EXISTS result_entity_type VARCHAR(64),
    ADD COLUMN IF NOT EXISTS result_entity_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS result_reference_stage VARCHAR(32);

ALTER TABLE tool_execution_idempotency
    DROP CONSTRAINT IF EXISTS ck_tei_result_reference_pair;

ALTER TABLE tool_execution_idempotency
    ADD CONSTRAINT ck_tei_result_reference_pair CHECK (
        num_nonnulls(result_entity_type, result_entity_id, result_reference_stage) = 0
        OR (num_nonnulls(result_entity_type, result_entity_id, result_reference_stage) = 3
            AND result_reference_stage IN ('PRODUCT_CREATED', 'COPY_COMMITTED', 'READBACK_VERIFIED'))
    );
