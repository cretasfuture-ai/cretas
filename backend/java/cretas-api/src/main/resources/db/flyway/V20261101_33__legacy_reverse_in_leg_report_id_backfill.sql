-- 回填 #391 之前写下的「遗留 IN 腿补偿」流水的 report_id
-- 任务 BUG-SFI-REVERSE-CLASSIFIER-LOCKOUT-20260906 (2026-09-06)
--
-- ============================================================================
-- 背景 (一句话): e774ebf298 (#391) 之前, 整单撤回给 IN 腿写 REVERSE 行时
-- **不写 report_id** (当时的想法是「血缘由 sourceRef=reversal-log:<id> 承担」)。
-- 这些行于是长成「REVERSE + REVERSAL + report_id 为 NULL + reversal-log: 前缀」——
-- 与「外部领用还回」那一类**逐字段同形**, 唯一的区别是**符号**(还回是正量, IN 腿补偿是负量)。
-- ReportReversalServiceImpl#isExternalConsumptionReverse 原来不看符号 ⇒ 误判 ⇒
-- validateFullWipLedgersBeforeMutation 抛 PARTIAL_COMPENSATION「外部领用还回流水数量非法」
-- ⇒ **这条 SFI 此后任何一次整单撤回都 409**。分类器那一维已在同一个 PR 里补上。
--
-- 本迁移做的是**数据侧**的收尾: 把能证明出确切 report_id 的那些行补齐, 让它们回到
-- 正常的「原始行 ↔ 补偿行」配对路径 (matchExistingCompensations 的键第一维就是 reportId),
-- 而不是长期依赖运行时兜底。两者不互相替代:
--   分类器兜底 = 证明不出 report_id 时也不把账锁死;
--   本迁移     = 证明得出来的就补上, 让配对是真的配对。
--
-- ============================================================================
-- 判据 (⛔ 不硬编码任何 txn id / report id)
-- ----------------------------------------------------------------------------
-- 只更新同时满足全部条件的行:
--   (1) txn_type='REVERSE' AND source_type='REVERSAL' AND report_id IS NULL
--       AND quantity < 0 AND source_ref LIKE 'reversal-log:%'
--       —— 负量才是 IN 腿补偿; 正量是「还回领用」, 归 isExternalConsumptionReverse, ⛔ 不碰。
--   (2) **恰好一条** report_reversal_logs 在 reverted_txn_ids 里认领了这条流水
--       (同工厂)。认领清单是 executeReversal 自己写的权威事实, 不是前缀这种代理判据。
--   (3) 该日志的批次下, 同一 SFI 上**恰好一条** IN 流水满足:
--       数量 = 本行数量的相反数, 且它的报工**已被软删** (deleted_at IS NOT NULL)。
--       —— 软删是那次撤回留下的痕迹; 撤回后重做的 IN 腿报工是活的, 因此被这一条排除。
-- 任何一条「恰好一条」不成立 ⇒ 整行跳过 (fail-closed), 由运行时兜底承担, ⛔ 不猜。
--
-- 幂等: WHERE 里带 report_id IS NULL, 重跑时命中 0 行。
-- 零命中安全: 没有匹配行时 UPDATE 0, 不报错 (新装库 / 已经修过的库都是这条路径)。
--
-- ============================================================================
-- prod 实测 (2026-09-06, 只读 SSH; 用与下面**逐字相同**的谓词跑 SELECT 版本)
-- ----------------------------------------------------------------------------
--   txn 141  LIUSHANMEN  sfi 370  -1.250000  reversal-log:18  -> report 23919
--   txn 142  LIUSHANMEN  sfi 371  -1.000000  reversal-log:18  -> report 23921
--   txn 143  LIUSHANMEN  sfi 372  -1.000000  reversal-log:18  -> report 23923
--   共 3 行, 全部在 LIUSHANMEN; F006 一行都不命中。
--
-- 阴性对照 (这些行必须**一条都不动**, 已逐条实测落在 SKIP 分支):
--   txn 145/146 (LIUSHANMEN)  正量 REVERSE 且**已有** report_id 23920/23922  -> 已有 report_id
--   txn 158/159/160 (F006)    reversal-log:21, #391 之后写的, 已有 report_id  -> 已有 report_id
--   txn 84/86/88  (F006 sfi 343)  'manual-cleanup:' 前缀的负量 REVERSE        -> 前缀不符
--   txn 90        (F006 sfi 344)  'interim-settle-reverse-in:' 前缀           -> 前缀不符
--   txn 165       (LIUSHANMEN sfi 378) 'interim-settle-reverse-in:' 前缀      -> 前缀不符
--   txn 136/144   (LIUSHANMEN sfi 370) IN 流水                                -> 不是 REVERSE
-- ⚠️ F006 343/344 与 LIUSHANMEN 378 是**故意不修**的 (硬约束 8 的「登记不改」):
--   343 的三行是带外手工 SQL 造的 (source_ref 里写着 'manual-cleanup: … — claude'),
--   没有任何撤回日志认领它们, 也没有对应的 IN 流水可配对 —— 它们同时还撞
--   WIP_LEDGER_INCOMPLETE (produced 300 > ΣIN 0), 那是另一个缺陷。
--   344/378 是 'interim-settle-reverse-in:' 前缀 (小结撤销写的), 从来就不在
--   isExternalConsumptionReverse 的白名单里, 它们的问题是**另一条**路径, 单独立项。
--   ⛔ 不在这条迁移里顺手放宽白名单 —— 那是形态 E。
--
-- ============================================================================
-- 🔴 为什么 H2 看不见这条迁移, 因而这个 PR 里没有「真跑一遍」的 JUnit
-- ----------------------------------------------------------------------------
-- (a) src/test/resources/application-test.properties 是
--     spring.flyway.enabled=false + spring.jpa.hibernate.ddl-auto=create ——
--     H2 测试库的表由 Hibernate 按 entity 建, **db/flyway 下的文件一条都不执行**。
-- (b) 就算强行执行, 谓词里的 `reverted_txn_ids @> to_jsonb(...)` 是 PostgreSQL 的
--     jsonb 包含运算符, H2 没有 jsonb 类型也没有 @>; 而 reverted_txn_ids 在 H2 上
--     被 Hibernate 建成 varchar/clob。
-- (c) 本仓 pom.xml 没有 testcontainers, 且 CI 当前路由在自建 Mac mini runner 上,
--     那台机器**没有 Docker** (.claude/rules/ci-runner-routing.md 第 6 关) ——
--     在 CI 里起 Postgres 只会把闸变成「起不来」。
-- ⇒ 真正的四态验证在一次性 postgres:17-alpine 容器里做过 (见
--    LegacyReverseInLegBackfillMigrationContractTest 的 javadoc 记录), 本仓里那个
--    ContractTest 是**源码文本闸**, 只保证这份被验证过的 SQL 没被人改回去。

UPDATE semi_finished_inventory_transactions t
SET report_id = d.derived_report_id
FROM (
    SELECT c.id AS txn_id,
           MIN(i.report_id) AS derived_report_id
    FROM semi_finished_inventory_transactions c
    -- (2) 恰好一条撤回日志认领它
    JOIN LATERAL (
        SELECT l.id, l.batch_id, COUNT(*) OVER () AS n_logs
        FROM report_reversal_logs l
        WHERE l.factory_id = c.factory_id
          AND l.reverted_txn_ids @> to_jsonb(c.id)
    ) h ON h.n_logs = 1
    -- (3) 该批次下同一 SFI 上, 数量相反且报工已软删的 IN 流水
    JOIN semi_finished_inventory_transactions i
      ON i.semi_finished_id = c.semi_finished_id
     AND i.txn_type = 'IN'
     AND i.report_id IS NOT NULL
     AND i.quantity = -c.quantity
    JOIN production_reports r
      ON r.id = i.report_id
     AND r.factory_id = c.factory_id
     AND r.batch_id = h.batch_id
     AND r.deleted_at IS NOT NULL
    -- (1) 只有负量 + reversal-log: 前缀 + 无 report_id 的 REVERSE 行才是候选
    WHERE c.txn_type = 'REVERSE'
      AND c.source_type = 'REVERSAL'
      AND c.report_id IS NULL
      AND c.quantity < 0
      AND c.source_ref LIKE 'reversal-log:%'
    GROUP BY c.id
    HAVING COUNT(*) = 1          -- (3) 的「恰好一条」: 多于一条候选 IN 腿 ⇒ 证明不出来 ⇒ 跳过
) d
WHERE t.id = d.txn_id
  AND t.report_id IS NULL;       -- 幂等: 重跑命中 0 行
