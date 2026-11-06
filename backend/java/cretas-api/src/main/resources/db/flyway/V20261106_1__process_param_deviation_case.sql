-- Process-parameter deviation cases.  The writer is feature-gated off by default;
-- this migration only adds compatible storage and does not seed any case rows.
SET LOCAL lock_timeout = '5s';

ALTER TABLE shortage_cases DROP CONSTRAINT IF EXISTS shortage_cases_source_type_check;
ALTER TABLE shortage_cases ADD CONSTRAINT shortage_cases_source_type_check
    CHECK (source_type IN ('SALES_ORDER', 'COST_VARIANCE', 'MATERIAL_BALANCE',
                           'DELIVERY_RISK', 'PROCESS_PARAM_DEVIATION'));

CREATE TABLE process_param_deviation_case_details (
    case_id                 VARCHAR(36) PRIMARY KEY REFERENCES shortage_cases (id),
    factory_id              VARCHAR(50) NOT NULL,
    source_key              VARCHAR(255) NOT NULL,
    source_kind             VARCHAR(32) NOT NULL CHECK (source_kind IN ('YIELD_REPORT', 'PROCESS_SHEET_ROW')),
    source_report_id        BIGINT,
    production_batch_id     BIGINT,
    production_plan_id     VARCHAR(191),
    work_process_task_id    BIGINT,
    process_entry_step_key  VARCHAR(255),
    process_name            VARCHAR(200),
    batch_number            VARCHAR(100),
    product_type_id         VARCHAR(100),
    param_key               VARCHAR(191) NOT NULL,
    param_label             VARCHAR(200),
    unit                    VARCHAR(32),
    measured_value          NUMERIC(20,6),
    standard_value          NUMERIC(20,6),
    range_min               NUMERIC(20,6),
    range_max               NUMERIC(20,6),
    status                  VARCHAR(16) NOT NULL CHECK (status IN ('OK', 'LOW', 'HIGH')),
    fact_fingerprint        VARCHAR(64) NOT NULL,
    observed_at             TIMESTAMP NOT NULL,
    resolution_code         VARCHAR(32) CHECK (resolution_code IN
                             ('REASONABLE', 'CORRECTED', 'STANDARD_UPDATE', 'DATA_ERROR')),
    resolution_note         TEXT,
    resolved_by             BIGINT,
    resolved_at             TIMESTAMP,
    version                 BIGINT NOT NULL DEFAULT 0,
    created_at              TIMESTAMP NOT NULL,
    updated_at              TIMESTAMP NOT NULL,
    deleted_at              TIMESTAMP
);

CREATE UNIQUE INDEX uk_process_param_case_source
    ON process_param_deviation_case_details (factory_id, source_key)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_process_param_case_factory
    ON process_param_deviation_case_details (factory_id, observed_at DESC)
    WHERE deleted_at IS NULL;
