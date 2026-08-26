-- 「没人填不良品」被写成了「零不良」—— 把这批编出来的良品数改回 NULL(不知道)。
--
-- ## 为什么这不是"可能不准", 而是【可证伪的假】
--
-- 生产实测(cretas_prod_db, 2026-08-26):
--   · 4 个批次的 quality_inspections 明确记录了不合格品
--     (例: 批次 10766 抽样 80 / 合格 75 / 不合格 5 / 不良率 6.25% / 等级 B / 检验员 张六膳)
--   · 而这 4 个批次的 good_quantity 【全部等于 actual_quantity】—— 批次侧声称零不良
--   ⇒ 同一批货, 两个系统给出互相矛盾的结论, 且下游(良品率/损耗/人效)读的是说谎的那一边。
--
-- ## 为什么可以整批清, 而不只清那 4 条
--
--   · 全库 61 个批次, good_quantity < actual_quantity 的行数是 【0】
--     ⇒ 从来没有任何人成功录过不良品 ⇒ 每一个非空的 good_quantity 都是代码填的,
--       没有一条代表"某个人说了这批零不良"。
--   · 写侧那三处兜底已于 2026-08-26 删除(PR #114 + 读侧 LaborEfficiency),
--     并由 GoodQuantityIsNotFabricatedContractTest 钉住不再回来。本迁移只清存量。
--
-- ## 为什么这不丢信息
--
-- 被清掉的原值【恒等于 actual_quantity】, 而那一列原样保留 ⇒ 任何时候都能还原。
-- 换来的是: NULL 会让下游诚实地说"不知道", 而不是把 100% 良品当真实读数往下传。
--
-- ## 保守性
--
-- ⛔ 只清 good_quantity = actual_quantity 的行。若将来真有人录了不良(good < actual),
--    本迁移【不碰】它。生产环境预期命中 8 行 / 1 个工厂(F006), 误伤面实测 0 行。
-- 幂等: 重复执行不再匹配任何行。

UPDATE production_batches
   SET good_quantity = NULL,
       updated_at = now()
 WHERE good_quantity IS NOT NULL
   AND actual_quantity IS NOT NULL
   AND good_quantity = actual_quantity;
