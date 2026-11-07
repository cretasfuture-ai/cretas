CREATE TABLE IF NOT EXISTS insert_slot_previews (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    factory_id VARCHAR(50) NOT NULL,
    slot_id VARCHAR(36) NOT NULL,
    product_type_id VARCHAR(191) NOT NULL,
    preview_quantity DECIMAL(20, 6) NOT NULL,
    quantity_unit VARCHAR(20) NOT NULL,
    planned_unit VARCHAR(32) NOT NULL,
    production_line_id VARCHAR(36) NOT NULL,
    window_start TIMESTAMP(6) NOT NULL,
    window_end TIMESTAMP(6) NOT NULL,
    slot_updated_at TIMESTAMP(6) NULL,
    impact_level VARCHAR(20) NOT NULL,
    requires_approval BOOLEAN NOT NULL,
    affected_plan_count INT NOT NULL,
    candidate_number INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP(6) NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_insert_slot_preview_candidate
    ON insert_slot_previews (factory_id, slot_id, candidate_number);

CREATE INDEX IF NOT EXISTS idx_insert_slot_preview_factory_slot
    ON insert_slot_previews (factory_id, slot_id);

CREATE INDEX IF NOT EXISTS idx_insert_slot_preview_factory_created
    ON insert_slot_previews (factory_id, created_at);
