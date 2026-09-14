-- =====================================================================
-- batch_lineage_edges 幂等键唯一索引 (2026-09-14)
--
-- 问题: LineageEdgeRecorder 的幂等是「先查后插」(check-then-insert)，
--       而 V20260821_02 建表时这张表上**只有普通索引，没有任何 UNIQUE**。
--       两个并发请求拿同一个 refKey 几乎同时进来，都查到「不存在」，都插入
--       ⇒ 同一件事实被写成两条边。
--
-- 后果不是资损，是**展示型缺陷**：闭包表自己有 uq_closure + ON CONFLICT DO NOTHING，
--       所以「能追溯到谁」不会错；但 LineageGraphAssembler.applyEdgeFacts 会把同一
--       相邻节点上的多条边的 quantity_used **求和** ⇒ 溯源页上的「投入量」被双计，
--       而这个数字会被用户直接看到。
--
-- 现实触发面: recordFinishedToShipment 的 refKey 是
--       delivery:<deliveryRecordId>:<batchId> —— 同一张发货单被并发确认两次
--       (前端重复点击 / APP 网络重试) 是真的会发生的。
--
-- ---------------------------------------------------------------------
-- 为什么是 partial unique index 而不是普通 UNIQUE 约束
-- ---------------------------------------------------------------------
-- 1. 约束键里含**表达式** (meta->>'refKey')，PostgreSQL 的 UNIQUE **约束**不支持表达式，
--    也不支持 WHERE 谓词 —— 只能用 CREATE UNIQUE INDEX。
-- 2. `WHERE meta->>'refKey' IS NOT NULL`: 既有的 67 条 WIP_CONSUME 边是
--    WipInventoryServiceImpl 写的，meta 里**没有 refKey**。不加这个谓词会把它们
--    全部纳入唯一性判定，而它们本来就允许同一对批次之间有多条 —— 会直接建索引失败。
--    ⚠️ 这不是理论担心: prod 实测 67 条边里 48 条是同批次自环 (source_id = target_id)。
-- 3. ⛔ 不加 CONCURRENTLY: Flyway 的迁移跑在事务里，CREATE INDEX CONCURRENTLY
--    不能在事务块内执行。当前表量级 (prod 67 行，回填后预计几百行) 锁表时间可忽略。
--
-- ---------------------------------------------------------------------
-- 与应用层的配合 (⚠️ 少一边就没兜住)
-- ---------------------------------------------------------------------
-- LineageEdgeWriter.writeInNewTransaction 单独 catch DataIntegrityViolationException
-- 并返回 ALREADY_EXISTS —— 因为「另一个线程抢先写了同一个 refKey」不是失败，
-- 是两条线程想写的本来就是同一件事实。⛔ 不要把它归进 FAILED，
-- 否则回填的「失败」计数会被并发去重污染，读数当场失真。
-- =====================================================================

-- ⚠️ 键里**带上 edge_type**，与应用层 findMatchingEdges 的过滤列逐字一致
--    (factory_id, edge_type, source_type, source_id, target_type, target_id + refKey)。
--    若索引比应用判据**更严**(少一列)，一条应用认为是新边的记录会被索引拒掉，
--    而 writer 把 DataIntegrityViolationException 读成 ALREADY_EXISTS ⇒ **静默丢边**。
--    两侧必须同口径 —— 这是「同一个判据两份实现」时唯一能避免漂移的办法。
CREATE UNIQUE INDEX IF NOT EXISTS uq_ble_refkey
    ON batch_lineage_edges (
        factory_id,
        edge_type,
        source_type,
        source_id,
        target_type,
        target_id,
        (meta ->> 'refKey')
    )
    WHERE meta ->> 'refKey' IS NOT NULL;

COMMENT ON INDEX uq_ble_refkey IS
    '幂等键唯一索引: 同一对批次之间同一个业务 refKey 只许有一条边。'
    '无 refKey 的历史 WIP_CONSUME 边由 WHERE 谓词排除在外。';
