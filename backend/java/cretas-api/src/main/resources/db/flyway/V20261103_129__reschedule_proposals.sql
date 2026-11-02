-- S4 L1 切片 3: 缺料实例上的局部重排 (S4-03 局部 / 04 / 05)。
--
-- 一行 = 一次「生成重排方案」: 记下当时每个受影响排程任务的基线 (id + updated_at + 产线 + 起止),
-- 以及最多 3 个候选 (同线顺延 / 换线 / 转人工)。确认时逐个重读任务与基线比对, 有一个对不上就整体 409,
-- 一行都不写; 对得上才改 line_schedules 的产线和起止, 并在执行台账记一条 RESCHEDULE_APPLIED。
CREATE TABLE IF NOT EXISTS reschedule_proposals (
    id                  VARCHAR(36)   PRIMARY KEY,
    factory_id          VARCHAR(64)   NOT NULL,
    case_id             VARCHAR(36)   NOT NULL REFERENCES shortage_cases(id),
    status              VARCHAR(16)   NOT NULL CHECK (status IN ('OPEN', 'APPLIED', 'STALE')),
    baseline            JSONB         NOT NULL,
    candidates          JSONB         NOT NULL,
    applied_candidate   VARCHAR(32),
    expires_at          TIMESTAMP     NOT NULL,
    created_by_type     VARCHAR(32)   NOT NULL CHECK (created_by_type IN ('FACTORY_USER', 'PLATFORM_ADMIN', 'SYSTEM')),
    created_by_id       BIGINT,
    applied_at          TIMESTAMP,
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at          TIMESTAMP,
    CHECK (status <> 'APPLIED' OR (applied_candidate IS NOT NULL AND applied_at IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_reschedule_proposals_case ON reschedule_proposals (factory_id, case_id, created_at DESC);

-- 执行台账多一种动作: 应用重排 (结果 = 方案 id)。列上内联 CHECK 的约束名由 PG 生成为 <表>_<列>_check。
ALTER TABLE shortage_case_actions DROP CONSTRAINT IF EXISTS shortage_case_actions_action_type_check;
ALTER TABLE shortage_case_actions ADD CONSTRAINT shortage_case_actions_action_type_check
    CHECK (action_type IN ('PURCHASE_DRAFT', 'DECISION', 'RESCHEDULE_APPLIED'));
ALTER TABLE shortage_case_actions ADD CONSTRAINT shortage_case_actions_reschedule_needs_proposal
    CHECK (action_type <> 'RESCHEDULE_APPLIED' OR result_ref IS NOT NULL);
