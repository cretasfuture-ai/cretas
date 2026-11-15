-- V20261115_11__ai_bi_segment2_intents.sql
--
-- 六膳门 AI 问答 BI 第 2 段 (2026-11-15): 三个只读工具各绑一个新意图 (FACTORY / REPORT / LOW), 只新增绑定, 不改既有意图:
--   REPORT_BOM_COST_RANK -> report_bom_cost_rank; REPORT_SALES_MARGIN_SUMMARY -> report_sales_margin_summary;
--   REPORT_LABOR_COST_SHARE -> report_labor_cost_share。事后核对: 每个工具恰好 1 行有效意图绑定。

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    business_type, created_at, updated_at
)
VALUES (
    gen_random_uuid(),
    'REPORT_BOM_COST_RANK',
    'BOM成本排行',
    'REPORT',
    'report_bom_cost_rank',
    'LOW',
    '["BOM成本","BOM成本最高","BOM成本排行","哪个产品BOM成本高","BOM成本最高的产品","配方成本最高"]'::jsonb,
    '只读查询BOM成本排行: 按产品现行BOM成本(每1个成品)排序并出柱图, 可按产品名筛选(自动解析产品ID); 当前BOM口径仅归集包材, 缺单价会标注。',
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

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    business_type, created_at, updated_at
)
VALUES (
    gen_random_uuid(),
    'REPORT_SALES_MARGIN_SUMMARY',
    '销售额与毛利汇总',
    'REPORT',
    'report_sales_margin_summary',
    'LOW',
    '["本月销售额","本月毛利","销售额和毛利","销售额与毛利","本月卖了多少","这个月赚了多少"]'::jsonb,
    '只读查询销售额与毛利汇总(默认本月): 折后未税销售额、毛利、毛利率、成本覆盖率并出柱图; 毛利只算已回填成本的明细, 口径同SKU毛利率。',
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

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    business_type, created_at, updated_at
)
VALUES (
    gen_random_uuid(),
    'REPORT_LABOR_COST_SHARE',
    '人工成本占比',
    'REPORT',
    'report_labor_cost_share',
    'LOW',
    '["人工成本占比","人工占比","人工成本占总成本","人工成本比例","哪个产品人工占比高"]'::jsonb,
    '只读查询人工成本占比: 期间内已完成批次人工成本占总成本的比例, 按产品拆分并出柱图; 与成本分析页同口径, 只给金额汇总不含人员。',
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
    t text;
    bound int;
BEGIN
    FOREACH t IN ARRAY ARRAY['report_bom_cost_rank','report_sales_margin_summary','report_labor_cost_share'] LOOP
        SELECT count(*) INTO bound FROM ai_intent_configs
         WHERE tool_name = t AND is_active AND deleted_at IS NULL;
        IF bound <> 1 THEN
            RAISE EXCEPTION 'active intents bound to % = % (expected 1)', t, bound;
        END IF;
    END LOOP;
END $$;
