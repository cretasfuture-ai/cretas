-- 写入确认「结果不明」状态 (INTENT-DISTILL-20261111, Fable 审阅 §8.3 / 建议 5)。
--
-- 工具执行中抛异常 / 超时, 或进程中途退出后由对账判定的凭据记 OUTCOME_UNKNOWN, 不再记 FAILED:
-- 写入可能已提交, 「失败」会诱导用户重新预览再写一遍。只放宽状态取值, 不加列、不动数据;
-- ck_ipt_executing_claim (EXECUTING 必须带认领信息) 原样保留。可重复执行。

ALTER TABLE intent_preview_tokens DROP CONSTRAINT IF EXISTS ck_ipt_atomic_status;

ALTER TABLE intent_preview_tokens
    ADD CONSTRAINT ck_ipt_atomic_status
    CHECK (status IN ('PENDING', 'EXECUTING', 'CONFIRMED', 'FAILED', 'CANCELLED', 'EXPIRED', 'OUTCOME_UNKNOWN'));

-- 对账按 (status, claimed_at) 扫停在执行中的凭据; 只索引 EXECUTING 行, 平时几乎为空。
CREATE INDEX IF NOT EXISTS idx_ipt_executing_claimed_at
    ON intent_preview_tokens (claimed_at)
    WHERE status = 'EXECUTING';
