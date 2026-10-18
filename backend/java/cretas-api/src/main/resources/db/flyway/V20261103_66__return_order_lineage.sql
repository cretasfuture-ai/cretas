-- P5: stable return-line to original FG and returned-stock linkage.
-- Nullable columns keep legacy rows readable; only newly completed returns claim
-- VERIFIED through the persisted IDs.  Historical multi-line rows remain UNKNOWN.
ALTER TABLE return_order_items
    ADD COLUMN IF NOT EXISTS source_finished_goods_batch_id VARCHAR(191);

ALTER TABLE finished_goods_batches
    ADD COLUMN IF NOT EXISTS return_order_item_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_roi_source_finished_batch
    ON return_order_items (source_finished_goods_batch_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_fgb_factory_return_order_item
    ON finished_goods_batches (factory_id, return_order_item_id)
    WHERE return_order_item_id IS NOT NULL;
