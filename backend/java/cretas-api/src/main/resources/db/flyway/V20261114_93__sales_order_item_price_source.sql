-- 客户价目表最小闭环: 订单行落「价格来源」, 以便从订单回查这个价是专属价/统一价/最近成交/手填。
--   price_source: CUSTOMER(客户专属价) / GLOBAL(统一价) / LAST_DEAL(最近成交) / MANUAL(手填), 历史行为 NULL(未记录)。
--   price_ref: 来源说明快照 (价目表名称 或 来源订单号), 不随价目表后续改名/删除变化。
-- 只加可空列, 不回填, 可重复执行。
ALTER TABLE sales_order_items ADD COLUMN IF NOT EXISTS price_source VARCHAR(16) NULL;
ALTER TABLE sales_order_items ADD COLUMN IF NOT EXISTS price_ref VARCHAR(200) NULL;
