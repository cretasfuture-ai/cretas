-- 成本偏差案例 (cost_deviation_case_details) 里存了内部占位产品名 (__RAW_WORKFLOW_OWNER__<原料id>) 的存量回填。
-- ⛔ 本脚本不随发布自动执行, 由 organizer 安排; 先跑第 1 段只读统计, 再跑第 2 段。
-- ⛔ 不碰 LIUSHANMEN: 两段都排除该工厂。
-- 回填口径: 多产出计划的 production_plans.product_type_id 指向原料锚点, 锚点 id = 原料 id,
--   真实名 = raw_material_types.name (与 ScheduleProductNames.displayName 同一规则)。
--   查不到原料名的行置 NULL (界面显示「产品已删除或不存在」), 不写占位也不猜。

-- ── 1. 只读: 各工厂受影响行数 + 能回填几行 ──────────────────────────────
SELECT d.factory_id,
       COUNT(*)                                             AS affected_rows,
       COUNT(r.name)                                        AS resolvable_rows,
       COUNT(*) - COUNT(r.name)                             AS will_become_null
FROM cost_deviation_case_details d
LEFT JOIN production_plans p ON p.id = d.plan_id AND p.factory_id = d.factory_id
LEFT JOIN raw_material_types r ON r.id = p.product_type_id AND r.factory_id = d.factory_id
WHERE d.product_name LIKE '\_\_%'
  AND d.factory_id <> 'LIUSHANMEN'
GROUP BY d.factory_id
ORDER BY d.factory_id;

-- ── 2. 写入 (organizer 执行; 放事务里, 先核对第 1 段数字再 COMMIT) ──────────
-- BEGIN;
-- UPDATE cost_deviation_case_details d
--    SET product_name = (SELECT r.name
--                          FROM production_plans p
--                          JOIN raw_material_types r ON r.id = p.product_type_id AND r.factory_id = p.factory_id
--                         WHERE p.id = d.plan_id AND p.factory_id = d.factory_id)
--  WHERE d.product_name LIKE '\_\_%'
--    AND d.factory_id <> 'LIUSHANMEN';
-- -- 期望: 更新行数 == 第 1 段 affected_rows 之和; 之后同条件再 SELECT COUNT(*) 里残留的 '__%' 只应是 will_become_null 的行
-- COMMIT;
