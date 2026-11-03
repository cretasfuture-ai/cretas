-- V20261103_190__shortage_case_outcome_checks.sql
--
-- S4 缺料处理: 执行后核对结果。应用局部重排 / 生成采购草稿之后, 按当时的数据核对目标是否达成
-- (缺口是否关闭、受影响订单的交期是否保住), 结论写回处理实例; 没达成或判断不了就如实记, 不默认成功。
--   verdict: ACHIEVED      缺口已关闭且未开工的排程都在交期内完工
--            PENDING_SUPPLY 缺口未关闭, 但每种缺料都有在走采购单且最晚到货早于首个受影响排程, 排程在交期内
--            NOT_ACHIEVED  有排程晚于交期, 或缺口没有任何在走的补货
--            UNKNOWN       交期 / 到货日缺失等, 判断不了
-- 存量为空。

CREATE TABLE shortage_case_outcome_checks (
    id                  BIGSERIAL     PRIMARY KEY,
    case_id             VARCHAR(36)   NOT NULL REFERENCES shortage_cases (id),
    factory_id          VARCHAR(50)   NOT NULL,
    action_id           BIGINT        REFERENCES shortage_case_actions (id),
    trigger             VARCHAR(32)   NOT NULL CHECK (trigger IN ('RESCHEDULE_APPLIED', 'PURCHASE_DRAFT', 'MANUAL_RECHECK')),
    verdict             VARCHAR(16)   NOT NULL CHECK (verdict IN ('ACHIEVED', 'PENDING_SUPPLY', 'NOT_ACHIEVED', 'UNKNOWN')),
    gap_closed          BOOLEAN       NOT NULL,
    open_gap_lines      INT           NOT NULL,
    due_date            DATE,
    latest_planned_end  TIMESTAMP,
    late_schedule_count INT           NOT NULL,
    supply_ready_date   DATE,
    detail              TEXT          NOT NULL,
    actor_type          VARCHAR(32)   NOT NULL CHECK (actor_type IN ('FACTORY_USER', 'PLATFORM_ADMIN', 'SYSTEM')),
    actor_id            BIGINT,
    created_at          TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP     NOT NULL DEFAULT NOW(),
    deleted_at          TIMESTAMP
);

CREATE INDEX idx_shortage_outcome_checks_case ON shortage_case_outcome_checks (factory_id, case_id, id);
