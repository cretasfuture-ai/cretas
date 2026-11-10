-- V20261110_47__factory_allow_self_approval.sql
--
-- 工厂级「允许本人审批」开关 (纪律排查 B1–B3 / B11 / B15, Owner 2026-11-10 裁定选 A)。
--
-- 背景: 报工撤回、盘点差异、不合格品处置/特批、改价、退款都要求「审批人 ≠ 提交人」, 没有任何例外。
--   一个人的工厂永远办不成这些事 (F006 现场为了过四眼, 两个账号来回点)。
--
-- 行为:
--   • FALSE (默认, 所有工厂) → 四眼照旧, ZERO 行为变化。
--   • TRUE  (逐工厂在「工厂配置」里打开) → 提交人可以审批自己的单, 但必须填原因;
--     审批记录上 self_approved = TRUE 并保存原因 (留痕)。
--
-- 留痕列加在各自的审批记录上 (不新建表):
--   report_reversal_logs / factory_stocktakes / decision_audit_logs /
--   sales_price_adjustment_records / payment_requests / ar_ap_transactions
--   payment_requests 另有 self_paid* —— 退款「申请人 ≠ 付款登记人」是第二道四眼, 单独留痕。
--
-- 多租户安全: DEFAULT FALSE → 未显式配置的工厂 (现有 + 未来) 默认关闭; 本迁移不 seed 任何工厂为 TRUE。
-- 幂等: ADD COLUMN IF NOT EXISTS。

ALTER TABLE factory_settings
    ADD COLUMN IF NOT EXISTS allow_self_approval BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN factory_settings.allow_self_approval IS
    '工厂级"允许本人审批"开关 (默认 false = 四眼, 审批人不能是提交人). true = 提交人可审批自己的报工撤回/盘点差异/质检处置/改价/退款, 但必须填原因, 审批记录标 self_approved.';

ALTER TABLE report_reversal_logs
    ADD COLUMN IF NOT EXISTS self_approved BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS self_approval_reason TEXT;

ALTER TABLE factory_stocktakes
    ADD COLUMN IF NOT EXISTS self_approved BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS self_approval_reason TEXT;

ALTER TABLE decision_audit_logs
    ADD COLUMN IF NOT EXISTS self_approved BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS self_approval_reason TEXT;

ALTER TABLE sales_price_adjustment_records
    ADD COLUMN IF NOT EXISTS self_approved BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS self_approval_reason TEXT;

ALTER TABLE payment_requests
    ADD COLUMN IF NOT EXISTS self_approved BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS self_approval_reason TEXT,
    ADD COLUMN IF NOT EXISTS self_paid BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS self_paid_reason TEXT;

ALTER TABLE ar_ap_transactions
    ADD COLUMN IF NOT EXISTS self_approved BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS self_approval_reason TEXT;

COMMENT ON COLUMN report_reversal_logs.self_approved IS '本人审批 (提交人=审批人, 工厂开了 allow_self_approval)';
COMMENT ON COLUMN factory_stocktakes.self_approved IS '本人审批 (发起/录入/提交人=审批人且有盘盈亏, 工厂开了 allow_self_approval)';
COMMENT ON COLUMN decision_audit_logs.self_approved IS '本人审批 (申请人=审批人, 工厂开了 allow_self_approval)';
COMMENT ON COLUMN sales_price_adjustment_records.self_approved IS '本人审批 (改价人=审批人, 工厂开了 allow_self_approval)';
COMMENT ON COLUMN payment_requests.self_approved IS '本人审批 (退款申请人=审批人, 工厂开了 allow_self_approval)';
COMMENT ON COLUMN payment_requests.self_paid IS '本人登记付款 (退款申请人=付款登记人, 工厂开了 allow_self_approval)';
COMMENT ON COLUMN ar_ap_transactions.self_approved IS '本人审批 (退款撤销申请人=审批人, 工厂开了 allow_self_approval)';
