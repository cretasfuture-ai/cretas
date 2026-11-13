-- 成本偏差案例 (cost_deviation_case_details) 里存了内部占位产品名 (__RAW_WORKFLOW_OWNER__<原料id>) 的存量回填。
-- ⛔ 本脚本不随发布自动执行, 由 organizer 安排; 一次只处理一个工厂, 先跑第 1 段只读统计, 再跑第 2 段。
-- 用法 (psql): psql -v ON_ERROR_STOP=1 -v fid=F006 -f backfill-cost-deviation-product-name.sql
--   第 2 段默认注释, 核对第 1 段数字后取消注释再跑。
-- ⛔ LIUSHANMEN 明确拒绝 (见下)。
-- 回填口径与生产代码 ScheduleProductNames.displayName 完全一致:
--   占位名只精确匹配 '__RAW_WORKFLOW_OWNER__' || 计划的 product_type_id (锚点 id = 原料 id);
--   取原料名 raw_material_types.name; 查不到原料名就去掉前缀显示原料 id (即 product_type_id), 不写 NULL。
--   其它 '__' 开头的值不碰。

SELECT (:'fid' = 'LIUSHANMEN') AS refuse \gset
\if :refuse
\echo '拒绝: LIUSHANMEN 不在本脚本范围'
\quit
\endif

-- ── 1. 只读: 本厂受影响行数 + 其中能取到原料名的行数 ────────────────────────
SELECT d.factory_id,
       COUNT(*)      AS affected_rows,
       COUNT(r.name) AS use_raw_material_name,
       COUNT(*) - COUNT(r.name) AS use_raw_id_fallback
FROM cost_deviation_case_details d
JOIN production_plans p ON p.id = d.plan_id AND p.factory_id = d.factory_id
LEFT JOIN raw_material_types r ON r.id = p.product_type_id AND r.factory_id = d.factory_id
WHERE d.factory_id = :'fid'
  AND d.product_name = '__RAW_WORKFLOW_OWNER__' || p.product_type_id
GROUP BY d.factory_id;

-- 只读: 带 '__' 前缀但本脚本不会改的行 (应人工看一眼, 不在回填范围)
SELECT COUNT(*) AS untouched_other_placeholder_rows
FROM cost_deviation_case_details d
LEFT JOIN production_plans p ON p.id = d.plan_id AND p.factory_id = d.factory_id
WHERE d.factory_id = :'fid'
  AND d.product_name LIKE '\_\_%'
  AND (p.id IS NULL OR d.product_name <> '__RAW_WORKFLOW_OWNER__' || p.product_type_id);

-- ── 2. 写入 (organizer 执行) ────────────────────────────────────────────────
-- BEGIN;
-- UPDATE cost_deviation_case_details d
--    SET product_name = COALESCE(NULLIF(btrim(r.name), ''), p.product_type_id)
--   FROM production_plans p
--   LEFT JOIN raw_material_types r ON r.id = p.product_type_id AND r.factory_id = p.factory_id
--  WHERE p.id = d.plan_id AND p.factory_id = d.factory_id
--    AND d.factory_id = :'fid'
--    AND d.product_name = '__RAW_WORKFLOW_OWNER__' || p.product_type_id;
-- -- 期望: 更新行数 == 第 1 段 affected_rows
-- -- 复核: 同条件 (上面第 1 段第一条 SELECT) 再跑, 应 0 行 —— 执行后残留应为 0
-- COMMIT;
