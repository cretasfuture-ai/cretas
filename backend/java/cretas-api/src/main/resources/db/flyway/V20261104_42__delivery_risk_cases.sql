-- V20261104_42__delivery_risk_cases.sql
--
-- 交付风险异常: 复用 shortage_cases 这组处理实例 (阶段 / 负责人 / 留痕 / 执行台账 / 结果核对), 不另造一套。
--   source_type = DELIVERY_RISK, source_id = 销售订单 id。
--   delivery_risk_case_details  一条实例一行: 最近一次评估的结论、依据、影响和处理建议。
--   action_type HANDLING_TASK   指派给负责人的处理任务 (只记录, 不改排程、不下采购)。
--   trigger RISK_RECHECK        关闭前按当前数据复核风险是否解除。
-- 存量为空: 只有用户在页面上点「扫描」才建实例, 没有定时任务。

ALTER TABLE shortage_cases DROP CONSTRAINT IF EXISTS shortage_cases_source_type_check;
ALTER TABLE shortage_cases ADD CONSTRAINT shortage_cases_source_type_check
    CHECK (source_type IN ('SALES_ORDER', 'COST_VARIANCE', 'MATERIAL_BALANCE', 'DELIVERY_RISK'));

ALTER TABLE shortage_case_actions DROP CONSTRAINT IF EXISTS shortage_case_actions_action_type_check;
ALTER TABLE shortage_case_actions ADD CONSTRAINT shortage_case_actions_action_type_check
    CHECK (action_type IN ('PURCHASE_DRAFT', 'DECISION', 'RESCHEDULE_APPLIED', 'HANDLING_TASK'));
ALTER TABLE shortage_case_actions ADD CONSTRAINT shortage_case_actions_task_needs_text
    CHECK (action_type <> 'HANDLING_TASK' OR (reason IS NOT NULL AND length(trim(reason)) > 0));

ALTER TABLE shortage_case_outcome_checks DROP CONSTRAINT IF EXISTS shortage_case_outcome_checks_trigger_check;
ALTER TABLE shortage_case_outcome_checks ADD CONSTRAINT shortage_case_outcome_checks_trigger_check
    CHECK (trigger IN ('RESCHEDULE_APPLIED', 'PURCHASE_DRAFT', 'MANUAL_RECHECK', 'RISK_RECHECK'));

CREATE TABLE delivery_risk_case_details (
    case_id         VARCHAR(36)  PRIMARY KEY REFERENCES shortage_cases (id),
    factory_id      VARCHAR(50)  NOT NULL,
    due_date        DATE,
    risk_level      VARCHAR(16)  NOT NULL CHECK (risk_level IN ('RISK', 'UNKNOWN', 'OK')),
    assessment_json TEXT         NOT NULL,
    assessed_at     TIMESTAMP    NOT NULL
);

CREATE INDEX idx_delivery_risk_details_factory ON delivery_risk_case_details (factory_id, risk_level);
