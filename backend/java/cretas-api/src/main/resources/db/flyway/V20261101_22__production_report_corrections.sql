ALTER TABLE production_reports
    ADD COLUMN IF NOT EXISTS data_corrected BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS last_correction_id VARCHAR(36);

CREATE TABLE IF NOT EXISTS production_report_corrections (
    id VARCHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    factory_id VARCHAR(50) NOT NULL,
    production_report_id BIGINT NOT NULL REFERENCES production_reports(id),
    work_process_task_id BIGINT,
    production_batch_id BIGINT,
    production_plan_id VARCHAR(191),
    product_type_id VARCHAR(100),
    process_category VARCHAR(200),
    status VARCHAR(32) NOT NULL,
    original_output_quantity NUMERIC(12,2), proposed_output_quantity NUMERIC(12,2),
    original_good_quantity NUMERIC(12,2), proposed_good_quantity NUMERIC(12,2),
    original_defect_quantity NUMERIC(12,2), proposed_defect_quantity NUMERIC(12,2),
    original_total_workers INTEGER, proposed_total_workers INTEGER,
    original_total_work_minutes INTEGER, proposed_total_work_minutes INTEGER,
    original_notes VARCHAR(500), proposed_notes VARCHAR(500),
    correction_reason VARCHAR(500) NOT NULL,
    submitted_by BIGINT NOT NULL, submitted_at TIMESTAMP NOT NULL,
    workflow_instance_id VARCHAR(36),
    decided_by BIGINT, decided_at TIMESTAMP, decision_notes VARCHAR(500), applied_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
    CONSTRAINT chk_prod_report_correction_status CHECK (status IN ('PENDING_APPROVAL','APPROVED_APPLIED','REJECTED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_prod_report_correction_pending
    ON production_report_corrections(factory_id, production_report_id)
    WHERE status = 'PENDING_APPROVAL';
CREATE INDEX IF NOT EXISTS idx_prod_report_correction_plan_status
    ON production_report_corrections(factory_id, production_plan_id, status);
CREATE INDEX IF NOT EXISTS idx_prod_report_correction_product_status
    ON production_report_corrections(factory_id, product_type_id, status);
