-- 「成本 0」不是「确认为零」, 是「没人填价」—— 把它改回 NULL, 让它继续阻断。
--
-- ## 为什么这是数据修复而不是代码修复
--
-- 现行代码【已经是对的】: CostRollupUtil.calcItemCost 在 unitPrice 为 null 时返回 null
-- 而不是 0(源码注释原文: "returns null, NOT 0 — to avoid misleadingly showing a ¥0 cost"),
-- 且 CostRollupUtilTest 已有一条专门断言钉住它。
-- 这 8 行是【旧代码写下的存量】, 不是当前路径产生的。
--
-- 实测证据(生产库 cretas_prod_db, 2026-08-26):
--   · item_cost = 0 的行共 8 条, 全部是 PACKAGING
--   · 这 8 条的 unit_price 【全部为 NULL】(8/8) —— 单价都不知道, 成本不可能是「确认的零」
--   · 全表 unit_price = 0 的行数是 【0】(阴性对照): 本系统从不合法地记录零单价
--   · 物料是真空袋 / 外箱 / 标签 —— 现实中不可能零成本
--   · 近 10 天写入 19 行, 其中 item_cost = 0 的是 【0】 条 (阳性对照: 活的写入路径不再产 0)
--   · 8 条的 updated_at 最新停在 2026-08-15, 而全表最新是 2026-08-25
--
-- ## 为什么必须改
--
-- BomRecipeServiceImpl.recomputeMaterialCost 的语义是
-- 「itemCost 为 null -> markFamilyCostIncomplete -> 整个 family 标准成本被清空」。
-- 也就是说 NULL 会【诚实阻断】, 而 0 会被当成真实读数【静默计入 COGS】, 把毛利算虚高。
-- 这正是本仓反复记的那个形态: 兜底的默认值把「我不知道」翻译成「是 0」。
--
-- ## 保守性
--
-- ⛔ 只改「零成本 且 单价未知」的行。若将来真有「确认为零」的物料(赠品/自产),
--    它会带着一个真实的 unit_price = 0, 本迁移【不碰】它。
-- 影响面: 生产环境预期 8 行。幂等 —— 重复执行不再匹配任何行。

UPDATE bom_recipe_items
   SET item_cost = NULL,
       updated_at = now()
 WHERE item_cost = 0
   AND unit_price IS NULL
   AND deleted_at IS NULL;
