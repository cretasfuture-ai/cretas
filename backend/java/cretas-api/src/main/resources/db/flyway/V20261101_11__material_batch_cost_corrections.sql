CREATE TABLE IF NOT EXISTS material_batch_cost_corrections (
    id                VARCHAR(191) PRIMARY KEY,
    factory_id        VARCHAR(50)  NOT NULL,
    material_batch_id VARCHAR(191) NOT NULL,
    old_unit_price    NUMERIC(18,6),
    new_unit_price    NUMERIC(18,6) NOT NULL,
    reason            VARCHAR(255) NOT NULL,
    corrected_by      BIGINT NOT NULL,
    corrected_by_role VARCHAR(50),
    corrected_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_mbcc_batch FOREIGN KEY (material_batch_id) REFERENCES material_batches(id),
    CONSTRAINT fk_mbcc_user FOREIGN KEY (corrected_by) REFERENCES users(id),
    CONSTRAINT chk_mbcc_positive_price CHECK (new_unit_price > 0),
    CONSTRAINT chk_mbcc_changed CHECK (old_unit_price IS DISTINCT FROM new_unit_price)
);

CREATE INDEX IF NOT EXISTS idx_mbcc_batch
    ON material_batch_cost_corrections(material_batch_id, corrected_at DESC);
CREATE INDEX IF NOT EXISTS idx_mbcc_factory
    ON material_batch_cost_corrections(factory_id, corrected_at DESC);
