-- V20261115_01__report_batch_yield_rank_intent.sql
--
-- 批次出成率排行意图 (六膳门 AI 问答 BI 演示, 2026-11-15): 「哪个批次出成率最低」「各批次出成率排序」以前落到
-- INDICATOR_QUERY (viewer 被拒) / REPORT_DASHBOARD_OVERVIEW (答成仪表盘总览), 没有能给批次出成率排行的工具。
-- 新增只读工具 report_batch_yield_rank (良品 ÷ 实际产出现算, 缺数量的批次不进排行并说明) 并绑定新意图
-- REPORT_BATCH_YIELD_RANK (FACTORY / REPORT / LOW)。只新增绑定, 不改既有意图的分类与澄清策略。
-- 事后核对: 恰好 1 行有效意图绑定 report_batch_yield_rank。

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    business_type, created_at, updated_at
)
VALUES (
    gen_random_uuid(),
    'REPORT_BATCH_YIELD_RANK',
    '批次出成率排行',
    'REPORT',
    'report_batch_yield_rank',
    'LOW',
    '["批次出成率","出成率最低","出成率最高","出成率排序","出成率排行","哪个批次出成率","各批次出成率","出成率差的批次"]'::jsonb,
    '只读查询批次出成率排行: 期间内已完成批次按出成率(良品数量÷实际产出数量)从低到高或从高到低排序并出柱图; 缺良品或实际数量的批次不进排行并说明个数。',
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
     WHERE tool_name = 'report_batch_yield_rank' AND is_active AND deleted_at IS NULL;
    IF bound <> 1 THEN
        RAISE EXCEPTION 'active intents bound to report_batch_yield_rank = % (expected 1)', bound;
    END IF;
END $$;
