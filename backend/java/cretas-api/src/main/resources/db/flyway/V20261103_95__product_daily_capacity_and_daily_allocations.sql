-- APS v2 (Steve 2026-10-28 裁定): 按产品「一天能做多少」按天排, 不再按每道工序预计工时排。
--
-- 1) product_types 加人工日产能: 一个产品一天能做多少 (数值 + 单位; 单位与该产品计划的计划单位一致,
--    如 袋/天、kg/天)。联产计划 (一张图出多个成品, product_type_id 是原料归属锚点) 的日产能
--    存在锚点那一行上, 语义是「这个计划一天能处理多少 (计划单位, 通常是 kg 原料)」。
--    空 = 没填; 排程时回落系统从成品入库历史算出的经验日产能, 两样都没有时排程页当场让人填。
--    ⛔ 不加默认值、不回填: 没填就是没填。
-- 2) line_schedules.daily_allocations: v2 每条排程 (一个生产计划) 的按天分配
--    {unit, capacityPerDay, capacitySource, ..., days: [{date, quantity, shareOfDay}]}。
--    v1 的 task_timeline 保留 (存量 v1 排程仍按工序时间线展示), v2 不再写它。

ALTER TABLE product_types ADD COLUMN IF NOT EXISTS daily_capacity NUMERIC(12, 2) NULL;
ALTER TABLE product_types ADD COLUMN IF NOT EXISTS daily_capacity_unit VARCHAR(32) NULL;
ALTER TABLE product_types ADD COLUMN IF NOT EXISTS daily_capacity_updated_at TIMESTAMP NULL;
ALTER TABLE product_types ADD COLUMN IF NOT EXISTS daily_capacity_updated_by BIGINT NULL;

-- 数值与单位成对出现; 数值必须为正 (0 不是「产能」, 是没填)。
ALTER TABLE product_types DROP CONSTRAINT IF EXISTS ck_product_types_daily_capacity;
ALTER TABLE product_types ADD CONSTRAINT ck_product_types_daily_capacity CHECK (
    (daily_capacity IS NULL AND daily_capacity_unit IS NULL)
    OR (daily_capacity IS NOT NULL AND daily_capacity > 0
        AND daily_capacity_unit IS NOT NULL AND btrim(daily_capacity_unit) <> '')
);

ALTER TABLE line_schedules ADD COLUMN IF NOT EXISTS daily_allocations JSONB NULL;
