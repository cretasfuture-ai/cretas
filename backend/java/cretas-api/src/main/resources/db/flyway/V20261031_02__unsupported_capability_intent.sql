-- 让 AI 不再承诺系统做不到的能力：登记 UNSUPPORTED_CAPABILITY 意图 → unsupported_capability Tool
--
-- 背景（2026-08-26 普查）:
--   ① IntentKnowledgeBase 有两处把 "SPC图表" 映射到 QUALITY_STATS。
--      QUALITY_STATS 是有真实绑定的 (tool_name='quality_stats_query')，所以用户问 SPC
--      会拿到一份**看起来像回事**的质检合格率统计 —— 这是最坏的一种：不是拒答，是假答。
--      全仓实测: controlChart / control_chart / 统计过程控制 命中 0；Cpk 的命中全部是
--      pnpm-lock 里的 base64 噪声。SPC 的核心(控制图 + 过程能力指数)一样都没有。
--   ② CORRELATION_ANALYSIS 有 6 条短语，而这个意图码在整个仓库里只存在于
--      IntentKnowledgeBase 自己 —— 没有 Tool、没有 seed、没有 Python 实现。
--      它没有 DB 行，所以会 fall through 到 LLM 兜底
--      (IntentExecutionOrchestrator.java:1631-1638)，由模型自由发挥。
--
-- ⚠️ 这条 migration 是「接上没有」的那一步，不能省:
--    短语改指 UNSUPPORTED_CAPABILITY 之后，如果 ai_intent_configs 里没有这一行，
--    它会和 CORRELATION_ANALYSIS 一样 fall through —— 改了等于没改。

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category,
    sensitivity_level, quota_cost, keywords, required_roles, priority, description,
    tool_name, semantic_domain, semantic_action, semantic_object,
    business_type, is_active, created_at, updated_at
) VALUES (
    gen_random_uuid()::text, 'UNSUPPORTED_CAPABILITY', '能力不支持-明确拒答', 'SYSTEM',
    'LOW', 1,
    '["SPC","SPC图表","控制图","管制图","过程能力","Cpk","统计过程控制","相关性分析","关联分析","因果分析","影响因素","有没有关联"]',
    NULL, 80,
    -- 单 literal 不拼接: 本表其它列有 JSON cast, PG 里 `::` 优先级高于 `||`, 拼接会让 cast 只作用于
    -- 最后一个 fragment (见 V20260825_07 的 NOTE)。这里统一不用 `||`, 免得下次照抄出事。
    '用户要的分析系统确实做不到时，明确告知做不到/为什么/替代方案。区别于 OUT_OF_DOMAIN(问的事跟系统无关)：这里问的正是本系统的领域，只是这一项没有能力。',
    'unsupported_capability', 'SYSTEM', 'QUERY', 'CAPABILITY',
    'COMMON', TRUE, NOW(), NOW()
) ON CONFLICT (intent_code) DO UPDATE SET
    intent_name = EXCLUDED.intent_name,
    keywords = EXCLUDED.keywords,
    tool_name = EXCLUDED.tool_name,
    description = EXCLUDED.description,
    priority = EXCLUDED.priority,
    is_active = TRUE,
    updated_at = NOW();
