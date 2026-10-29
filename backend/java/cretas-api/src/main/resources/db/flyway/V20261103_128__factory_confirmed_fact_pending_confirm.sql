-- 对话建厂: AI 从自由文本提取的值不是用户确认过的事实。
-- 新增状态 PENDING_CONFIRM = 「上一版/自由文本已填写, 等用户一键确认」; 只有结构化决策或用户明确确认才变 CONFIRMED。
-- 每工厂每事项最多一条待确认, 与「最多一条 CONFIRMED / CONFLICT_PENDING」同一套部分唯一索引保护。

ALTER TABLE factory_confirmed_fact DROP CONSTRAINT IF EXISTS chk_factory_confirmed_fact_status;
ALTER TABLE factory_confirmed_fact ADD CONSTRAINT chk_factory_confirmed_fact_status
    CHECK (status IN ('CONFIRMED', 'CONFLICT_PENDING', 'PENDING_CONFIRM', 'SUPERSEDED', 'REJECTED'));

CREATE UNIQUE INDEX IF NOT EXISTS uq_fcf_one_pending_confirm_per_field
    ON factory_confirmed_fact (factory_id, field_key) WHERE status = 'PENDING_CONFIRM';
