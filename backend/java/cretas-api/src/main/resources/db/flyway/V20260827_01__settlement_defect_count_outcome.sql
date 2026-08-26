-- 结单时必须回答「这批有没有不良品」—— 三态, 而不是一个可以留空也可以填 0 的数字。
--
-- ## 为什么需要三态
--
-- 2026-08-26 实测(生产): 72 张报工单里【没有一张】填过不良品数, 61 个批次里
-- defect_quantity > 0 的是【0 条】。正因为没人填, 旧代码才去兜底填「零不良」,
-- 于是 8 个已完工批次全部声称 100% 良品 —— 而其中 4 个的质检明确记了不合格。
-- (V20260826_05 / _06 已把那批编出来的数清成 NULL。)
--
-- 单一数字字段无法区分这两件事:
--   · 「已清点, 确认这批零不良」        -> 0 是真实读数
--   · 「没清点 / 没人填」                -> 0 是编的
-- 而这两者对良品率、损耗、人效的含义完全相反。⇒ 用一个显式的结论字段把它们分开。
--
-- ## 取值
--
--   COUNTED         已清点, 有不良品 -> defect_quantity 必须 > 0
--   NONE_CONFIRMED  已清点, 确认零不良 -> defect_quantity = 0 (这个 0 是真的)
--   NOT_COUNTED     没清点            -> defect_quantity 必须为 NULL
--   NULL(存量/旧客户端)                 等同 NOT_COUNTED, 不回填成 0
--
-- ⛔ 存量行一律留 NULL: 它们确实没有被问过这个问题, 补一个 NOT_COUNTED 会假装
--    「当时有人选了没清点」。不知道就是不知道。

ALTER TABLE production_settlements
    ADD COLUMN IF NOT EXISTS defect_count_outcome VARCHAR(24);

ALTER TABLE production_settlements
    ADD COLUMN IF NOT EXISTS defect_quantity NUMERIC(12,2);

COMMENT ON COLUMN production_settlements.defect_count_outcome IS
    '不良品清点结论三态: COUNTED(有N个) / NONE_CONFIRMED(已清点确认零) / NOT_COUNTED(没清点)。NULL=存量行, 等同 NOT_COUNTED。';
COMMENT ON COLUMN production_settlements.defect_quantity IS
    '不良品数量。仅 COUNTED 时 >0; NONE_CONFIRMED 时为 0; NOT_COUNTED/NULL 时必须为 NULL。';
