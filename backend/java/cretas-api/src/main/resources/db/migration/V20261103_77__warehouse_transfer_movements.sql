CREATE TABLE warehouse_transfer_movements (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(191) NOT NULL,
    transfer_id VARCHAR(191) NOT NULL,
    transfer_item_id BIGINT NOT NULL,
    kind VARCHAR(16) NOT NULL,
    operation_id VARCHAR(64) NOT NULL,
    request_key VARCHAR(64) NOT NULL,
    source_batch_id VARCHAR(191),
    target_batch_id VARCHAR(191),
    quantity NUMERIC(18,6) NOT NULL,
    unit VARCHAR(20) NOT NULL,
    actor_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,
    CONSTRAINT uq_wtm_request_item UNIQUE (request_key, transfer_item_id),
    CONSTRAINT ck_wtm_kind CHECK (kind IN ('DISPATCH','RECEIPT')),
    CONSTRAINT ck_wtm_quantity CHECK (quantity > 0)
);
CREATE INDEX idx_wtm_transfer ON warehouse_transfer_movements(transfer_id);
CREATE INDEX idx_wtm_item ON warehouse_transfer_movements(transfer_item_id);
CREATE INDEX idx_wtm_request ON warehouse_transfer_movements(request_key);
