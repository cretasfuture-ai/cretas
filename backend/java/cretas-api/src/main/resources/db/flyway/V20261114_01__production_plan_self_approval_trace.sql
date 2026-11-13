-- V20261114_01__production_plan_self_approval_trace.sql
--
-- 纪律排查红线 4 (2026-11-13): 强制插单「本人审批」留痕结构化。
--
-- 背景: 强制插单画布审批 (#1591) 开了工厂「允许本人审批」后, 提交人可以批自己的插单, 但留痕只是
--   在审批意见前面拼了一个字符串前缀「【本人审批】」, 没有结构化字段 —— 查「哪些计划是本人批的」只能 LIKE。
--   其它本人审批出口 (撤回/盘点/质检/改价/退款/应收应付) 都在各自的审批记录上用
--   self_approved + self_approval_reason 两列 (V20261110_47), 这里补齐同名同口径的两列。
--
-- 行为:
--   • 新列 DEFAULT FALSE / NULL → 现有计划全部视为「不是本人审批」, 与改动前的读法一致。
--   • 强制插单审批时 (UrgentInsertServiceImpl.applyWorkflowAction) 本人批准 ⇒ self_approved = TRUE + 原因;
--     同一条审批写的 decision_audit_logs 行 (V20261110_47 已有这两列) 也一起写。
--   • 审批意见里的「【本人审批】」前缀保留为给人看的文字 (OA 审批历史只有意见文本), 不再作为判据。
--
-- 存量回填: 只写脚本 scripts/migrations/2026-11-13-production-plan-self-approval-backfill.sql, 本迁移不回填,
--   ⛔ 不在生产自动执行 (由 organizer 决定何时、对哪个库手工跑)。
--
-- 幂等: ADD COLUMN IF NOT EXISTS。

ALTER TABLE production_plans
    ADD COLUMN IF NOT EXISTS self_approved BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS self_approval_reason TEXT;

COMMENT ON COLUMN production_plans.self_approved IS
    '本人审批 (强制插单提交人=审批人, 工厂开了 allow_self_approval)';
COMMENT ON COLUMN production_plans.self_approval_reason IS
    '本人审批原因 (开关开时必填)';

-- ar_ap_transactions 的两列在 V20261110_47 只为退款撤销加的; 红线 4 起应收/应付手工调整的审批/驳回也写它们。
COMMENT ON COLUMN ar_ap_transactions.self_approved IS
    '本人审批 (退款撤销申请人=审批人, 或手工调整提交人=审批/驳回人; 工厂开了 allow_self_approval)';
