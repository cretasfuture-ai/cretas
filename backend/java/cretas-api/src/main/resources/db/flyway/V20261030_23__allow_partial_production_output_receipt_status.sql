-- Workflow output receipts are cumulative. A successful receipt that has not
-- yet reached the reported quantity is persisted as PARTIALLY_RECEIVED.
ALTER TABLE production_settlement_output_lines
    DROP CONSTRAINT IF EXISTS ck_pso_status;

ALTER TABLE production_settlement_output_lines
    ADD CONSTRAINT ck_pso_status
    CHECK (status IN ('REPORTED', 'PARTIALLY_RECEIVED', 'RECEIVED'));
