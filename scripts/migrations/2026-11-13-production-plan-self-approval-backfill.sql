-- 红线 4 (2026-11-13): 强制插单「本人审批」存量留痕回填 —— 从审批意见前缀「【本人审批】」回填结构化字段。
--
-- 前提: 已执行 flyway V20261113_47 (production_plans.self_approved / self_approval_reason)。
--   decision_audit_logs 的两列由 V20261110_47 提供。
--
-- ⛔ 本脚本不随发布自动执行, 也不要在生产直接跑: 由 organizer 决定何时、对哪个库手工执行。
--   默认以 ROLLBACK 结尾 (= dry-run, 只看计数); 确认计数后把最后一行改成 COMMIT 再跑。
--
-- 口径 (与 UrgentInsertServiceImpl.applyWorkflowAction 写法一致):
--   • 只认以「【本人审批】」开头的审批意见 —— 这个前缀只由强制插单画布审批的本人批准写入
--     (#1591 起; 之前没有任何出口写过它), 所以不另按工厂/日期过滤。
--   • 原因 = 前缀之后的文字 (trim)。原因为空的不回填 (不能编原因), 计数单列出来人工看。
--   • 只改 self_approved = FALSE 的行 → 可重复执行 (幂等)。
--
-- 用法: psql -v ON_ERROR_STOP=1 -h <host> -d <db> -f 本文件

BEGIN;

-- ---- 回填前计数 (候选 / 前缀后为空 / 已回填) ----
SELECT 'production_plans' AS tbl,
       COUNT(*) FILTER (WHERE approval_comment LIKE '【本人审批】%' AND NOT self_approved
                          AND btrim(substr(approval_comment, 7)) <> '')          AS candidates,
       COUNT(*) FILTER (WHERE approval_comment LIKE '【本人审批】%'
                          AND btrim(substr(approval_comment, 7)) = '')           AS empty_reason_skipped,
       COUNT(*) FILTER (WHERE self_approved)                                     AS already_flagged
FROM production_plans;

SELECT 'decision_audit_logs' AS tbl,
       COUNT(*) FILTER (WHERE approval_comment LIKE '【本人审批】%' AND NOT self_approved
                          AND btrim(substr(approval_comment, 7)) <> '')          AS candidates,
       COUNT(*) FILTER (WHERE approval_comment LIKE '【本人审批】%'
                          AND btrim(substr(approval_comment, 7)) = '')           AS empty_reason_skipped,
       COUNT(*) FILTER (WHERE self_approved)                                     AS already_flagged
FROM decision_audit_logs
WHERE entity_type = 'ProductionPlan';

-- ---- 回填 ----
-- 「【本人审批】」是 6 个字符, substr(..., 7) 取其后。
UPDATE production_plans
SET self_approved = TRUE,
    self_approval_reason = btrim(substr(approval_comment, 7))
WHERE approval_comment LIKE '【本人审批】%'
  AND NOT self_approved
  AND btrim(substr(approval_comment, 7)) <> '';

UPDATE decision_audit_logs
SET self_approved = TRUE,
    self_approval_reason = btrim(substr(approval_comment, 7))
WHERE entity_type = 'ProductionPlan'
  AND approval_comment LIKE '【本人审批】%'
  AND NOT self_approved
  AND btrim(substr(approval_comment, 7)) <> '';

-- ---- 回填后计数: candidates 应为 0, already_flagged 应 = 回填前 candidates + already_flagged ----
SELECT 'production_plans' AS tbl,
       COUNT(*) FILTER (WHERE approval_comment LIKE '【本人审批】%' AND NOT self_approved
                          AND btrim(substr(approval_comment, 7)) <> '')          AS candidates,
       COUNT(*) FILTER (WHERE self_approved)                                     AS already_flagged
FROM production_plans;

SELECT 'decision_audit_logs' AS tbl,
       COUNT(*) FILTER (WHERE approval_comment LIKE '【本人审批】%' AND NOT self_approved
                          AND btrim(substr(approval_comment, 7)) <> '')          AS candidates,
       COUNT(*) FILTER (WHERE self_approved)                                     AS already_flagged
FROM decision_audit_logs
WHERE entity_type = 'ProductionPlan';

ROLLBACK;  -- 确认计数后改成 COMMIT
