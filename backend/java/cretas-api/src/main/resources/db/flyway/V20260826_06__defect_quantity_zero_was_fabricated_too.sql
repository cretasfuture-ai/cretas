-- good_quantity 的孪生兄弟: defect_quantity 同样在说「零不良」, 而同样没人填过。
--
-- ## 为什么补这一条
--
-- V20260826_05 把编出来的 good_quantity(= actual, 声称 100% 良品) 清成了 NULL,
-- 但【漏了旁边这一列】: 同样这 8 个已完工批次, defect_quantity 全部是 0。
-- 清掉一个说谎的数、留下另一个说同样的谎, 等于没修 —— 而且 defect_quantity 更要紧:
-- good_quantity 当时零消费方, 而 defect_quantity 【真的有人读】——
--   ProcessingBatchCompleteTool / ProcessingBatchDetailTool / ProcessingCapacityTodayTool
--   (AI 工具与产能报表都在累加它)。
--
-- ## 证据(生产实测 cretas_prod_db 2026-08-26)
--
--   · 全库 61 个批次, defect_quantity > 0 的行数 = 【0】 ⇒ 从来没有人录进过一个不良品
--   · 8 个 COMPLETED 批次的 defect_quantity 全部 = 0
--   · 其中 4 个批次的 quality_inspections 明确记录了不合格品
--     (批次 10766: 抽样 80 / 不合格 5; 10806 三次复检各判 FAIL; 10814 CONDITIONAL 3 不合格)
--     ⇒ 这 4 条的「0 个不良品」是【可证伪的假】。
--
-- ## 为什么 0 不是合法读数
--
-- ⚠️ 这一列在代码里是【累加器】(ProcessingServiceImpl 用 currentDefect.add(teamDefect)),
--    所以 0 的字面含义是「累加了 0 次上报」, 而不是「确认这批零不良」。
--    批次已 COMPLETED ⇒ 不会再有上报 ⇒ 此刻这个 0 只会被下游当成「零不良」读。
--    「没人报过」和「确认为零」必须分得开, 而 NULL 才是前者。
--
-- ## 安全性
--
--   · 读侧全部 null-safe: getDefectQuantity() != null ? … : ZERO / addNullSafe(...)
--     ⇒ 清成 NULL 不会让任何调用点炸, 只会让它诚实地说「不知道」。
--   · ⛔ 只清 = 0 的行。将来真有人报了不良(> 0), 本迁移不碰。幂等。
--   · 预期命中 8 行 / 1 个工厂(F006); 误伤面实测 0 行。

UPDATE production_batches
   SET defect_quantity = NULL,
       updated_at = now()
 WHERE defect_quantity = 0
   AND status = 'COMPLETED';
