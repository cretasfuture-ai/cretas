-- 缺口 03 回料流转 (Steve 2026-11-03 裁定): 按产品设回料比例上限。
-- 回料占比 = 本道工序回料投入 / 主料投入 (%)。超过上限必须填原因才能正式提交, 留痕。
-- NULL = 没设, 不拦 (⛔ 不加默认值、不回填)。
ALTER TABLE product_types ADD COLUMN IF NOT EXISTS max_rework_ratio_percent NUMERIC(6, 2) NULL;
ALTER TABLE product_types DROP CONSTRAINT IF EXISTS ck_product_types_max_rework_ratio;
ALTER TABLE product_types ADD CONSTRAINT ck_product_types_max_rework_ratio CHECK (
    max_rework_ratio_percent IS NULL
    OR (max_rework_ratio_percent > 0 AND max_rework_ratio_percent <= 100)
);
