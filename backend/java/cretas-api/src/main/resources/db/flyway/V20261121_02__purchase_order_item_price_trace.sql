-- V20261121_02__purchase_order_item_price_trace.sql
-- 采购单明细留痕: 单价来源 (价目表/手改/手填)、参考价及其价目表、偏离百分比。
-- 原型缺口 G3: 以前这些字段被静默丢弃, 保存后看不到单价从哪来。
-- 全部可空: 存量行与不带参考价的下单路径保持 NULL (不猜、不补 0)。
ALTER TABLE purchase_order_items ADD COLUMN IF NOT EXISTS price_source VARCHAR(32);
ALTER TABLE purchase_order_items ADD COLUMN IF NOT EXISTS reference_price NUMERIC(15,4);
ALTER TABLE purchase_order_items ADD COLUMN IF NOT EXISTS reference_price_list_id VARCHAR(191);
ALTER TABLE purchase_order_items ADD COLUMN IF NOT EXISTS reference_price_list_name VARCHAR(200);
ALTER TABLE purchase_order_items ADD COLUMN IF NOT EXISTS price_deviation_pct NUMERIC(9,4);
