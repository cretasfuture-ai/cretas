ALTER TABLE factory_stocktakes
    ADD COLUMN IF NOT EXISTS inventory_scope VARCHAR(30) NOT NULL DEFAULT 'RAW_MATERIAL';

ALTER TABLE factory_stocktake_items
    ALTER COLUMN material_batch_id DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS finished_goods_batch_id VARCHAR(191),
    ADD COLUMN IF NOT EXISTS product_type_id VARCHAR(191),
    ADD COLUMN IF NOT EXISTS quantity_unit VARCHAR(20);

CREATE INDEX IF NOT EXISTS idx_stocktake_inventory_scope
    ON factory_stocktakes(factory_id, warehouse_id, period_month, inventory_scope, status);
CREATE INDEX IF NOT EXISTS idx_stocktake_item_finished_goods
    ON factory_stocktake_items(finished_goods_batch_id);

ALTER TABLE factory_stocktakes
    DROP CONSTRAINT IF EXISTS chk_factory_stocktake_inventory_scope;
ALTER TABLE factory_stocktakes
    ADD CONSTRAINT chk_factory_stocktake_inventory_scope
    CHECK (inventory_scope IN ('RAW_MATERIAL', 'FINISHED_GOODS'));

ALTER TABLE factory_stocktake_items
    DROP CONSTRAINT IF EXISTS chk_factory_stocktake_item_identity;
ALTER TABLE factory_stocktake_items
    ADD CONSTRAINT chk_factory_stocktake_item_identity CHECK (
        (material_batch_id IS NOT NULL AND finished_goods_batch_id IS NULL)
        OR (material_batch_id IS NULL AND finished_goods_batch_id IS NOT NULL)
    );
