-- V20261103_180__report_cost_variance_intent.sql
--
-- 成本差异意图 (S6 复测 #1394, acc-s6, F006): 「成本差异」「成本差异分析」「实际成本和计划成本差异」都识别成
-- COST_QUERY, 状态 READ_MODE_WRITE_BLOCKED (「这是操作类请求」) —— 咨询页里每个成本差异问题都走不通。
-- 成因: COST_QUERY 绑的 report_cost_query 会生成并保存一份 AI 成本分析报告 (返回 reportId), 是真写, 被写闸拦是对的;
-- 而只读的 report_cost_variance (BOM 理论成本 vs 实际成本) 在工厂目录里没有任何意图绑定, 只在 cost-analysis Skill 里。
--
-- 1. 新意图 REPORT_COST_VARIANCE → report_cost_variance (FACTORY / REPORT / LOW, 工具声明 READ)。
--    required_roles / required_permission 都不设: 由工具自己的门禁
--    (finance:read / finance:read_write / procurement:price:view, 与 GET /reports/cost-variance 同一组) 决定。
-- 2. COST_QUERY 不动 (它是写)。
-- 3. 事后核对: 恰好 1 行有效意图绑定 report_cost_variance。
--
-- 预检: literal 里没有 || ; keywords 是 JSONB 数组; 全文没有美元符后紧跟左花括号 (Flyway 占位符)。

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    business_type, created_at, updated_at
)
VALUES (
    gen_random_uuid(),
    'REPORT_COST_VARIANCE',
    '成本差异分析',
    'REPORT',
    'report_cost_variance',
    'LOW',
    '["成本差异","成本差异分析","成本差异报表","实际成本和计划成本差异","实际成本与计划成本差异","计划成本和实际成本","成本偏差","理论成本和实际成本"]'::jsonb,
    '只读查询成本差异: 对比 BOM 理论成本与生产实际成本, 按产品给实际单位成本、差异与差异率 (缺独立标准成本时明说无法比较)。不生成新报告。触发: 成本差异 / 成本差异分析 / 实际成本和计划成本差异。',
    90,
    true,
    'FACTORY',
    NOW(),
    NOW()
)
ON CONFLICT (intent_code) DO UPDATE SET
    intent_name       = EXCLUDED.intent_name,
    intent_category   = EXCLUDED.intent_category,
    tool_name         = EXCLUDED.tool_name,
    sensitivity_level = EXCLUDED.sensitivity_level,
    keywords          = EXCLUDED.keywords,
    description       = EXCLUDED.description,
    priority          = EXCLUDED.priority,
    is_active         = true,
    business_type     = EXCLUDED.business_type,
    deleted_at        = NULL,
    updated_at        = NOW();

DO $$
DECLARE
    bound int;
BEGIN
    SELECT count(*) INTO bound FROM ai_intent_configs
     WHERE tool_name = 'report_cost_variance' AND is_active AND deleted_at IS NULL;
    IF bound <> 1 THEN
        RAISE EXCEPTION 'active intents bound to report_cost_variance = % (expected 1)', bound;
    END IF;
END $$;
