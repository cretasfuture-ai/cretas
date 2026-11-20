-- BOM 调价建议乐观锁 (PR #1578 审查 C, 2026-11-10)
-- 确认入库会原地改写 PENDING 建议的建议价; 与批准并发时, 后写的一方必须失败, 不能覆盖对方。
-- 已有行从 0 起; Hibernate @Version 每次 UPDATE 自增, 不一致 -> ObjectOptimisticLockingFailureException (409)。

ALTER TABLE bom_price_adjustment_proposals
ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN bom_price_adjustment_proposals.version IS 'Optimistic lock version (Hibernate @Version).';
