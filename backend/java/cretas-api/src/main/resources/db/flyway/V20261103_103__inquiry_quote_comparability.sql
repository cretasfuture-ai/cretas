-- ============================================================
-- L3 报价可比 (S5-01 / S5-02 / S5-04)
-- ============================================================
-- 1. 报价自带比较依据: 币种 / 计价单位 / 规格 / 起订量 / 价格生效日。
--    旧行这些列一律留 NULL = 「依据未知」, 比价时标为不可比, ⛔ 不回填猜测值。
-- 2. 税率不再默认 0: 未知税率 ≠ 0%。
--    旧服务层把「没填税率」写成 0, 所以存量的 0 无法区分「真 0%」与「没填」。
--    这里把存量 0 改成 NULL (未知), 比价时标为不可比, 采购员按实情重填即可。
--    这是失败即关闭的方向: 宁可让一条真 0% 报价重填一次, 也不让一条没填的报价被当成最低价。
-- 3. 核价单可挂在一张采购草稿上 (source_purchase_order_id):
--    选定报价时回填这张草稿, 不另建采购单。
-- Pre-assigned Flyway slot V20261103_103 (lane L3).
-- ============================================================

ALTER TABLE inquiry_quote_supplier_prices
    ADD COLUMN IF NOT EXISTS currency             VARCHAR(8),
    ADD COLUMN IF NOT EXISTS quoted_unit          VARCHAR(20),
    ADD COLUMN IF NOT EXISTS specification        VARCHAR(200),
    ADD COLUMN IF NOT EXISTS min_order_quantity   NUMERIC(18, 6),
    ADD COLUMN IF NOT EXISTS price_effective_date DATE;

ALTER TABLE inquiry_quote_supplier_prices
    ALTER COLUMN tax_rate DROP DEFAULT;

UPDATE inquiry_quote_supplier_prices
   SET tax_rate = NULL
 WHERE tax_rate = 0;

ALTER TABLE inquiry_quote_supplier_prices
    DROP CONSTRAINT IF EXISTS ck_iqsp_tax_rate_range;
ALTER TABLE inquiry_quote_supplier_prices
    ADD CONSTRAINT ck_iqsp_tax_rate_range
    CHECK (tax_rate IS NULL OR (tax_rate >= 0 AND tax_rate <= 100));

ALTER TABLE inquiry_quote_supplier_prices
    DROP CONSTRAINT IF EXISTS ck_iqsp_min_order_quantity_positive;
ALTER TABLE inquiry_quote_supplier_prices
    ADD CONSTRAINT ck_iqsp_min_order_quantity_positive
    CHECK (min_order_quantity IS NULL OR min_order_quantity > 0);

ALTER TABLE inquiry_quotes
    ADD COLUMN IF NOT EXISTS source_purchase_order_id VARCHAR(191);

-- 同一草稿的同一物料行同时只允许一张进行中的核价单。
-- 服务层先查后插挡不住两个并发创建, 由这个部分唯一索引兜底; 撞上时服务层转成 409。
-- (已转采购 / 已取消 / 已软删的核价单不占位, 可以对同一行再发起比价。)
CREATE UNIQUE INDEX IF NOT EXISTS uk_iq_open_source_draft_line
    ON inquiry_quotes(factory_id, source_purchase_order_id, material_type_id)
    WHERE source_purchase_order_id IS NOT NULL
      AND deleted_at IS NULL
      AND status IN ('DRAFT', 'INQUIRING', 'QUOTED', 'SELECTED');
