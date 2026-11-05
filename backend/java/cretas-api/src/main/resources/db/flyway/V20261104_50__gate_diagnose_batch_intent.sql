-- =====================================================
-- V20261104_50: 绑定「批次为什么领不了 / 出库为什么卡住」到只读诊断 Tool gate_diagnose_batch
-- 只读: Tool 读的是写路径拒绝时同一份闸诊断, 不执行任何解开动作
-- =====================================================
INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category,
    sensitivity_level, quota_cost, keywords, required_roles, priority, description,
    tool_name, semantic_domain, semantic_action, semantic_object,
    business_type, is_active, created_at, updated_at
) VALUES (
    gen_random_uuid()::text, 'GATE_DIAGNOSE_BATCH', '批次被拦原因诊断', 'MATERIAL',
    'LOW', 1,
    '["这批为什么领不了","为什么领不了","出库为什么卡住","为什么不能出库","为什么不能领料","为什么不能投料","批次被拦","领不了料","出不了库","投不了料"]',
    NULL, 70,
    '诊断某个批次为什么领不了料 / 出不了库 / 投不了料: 被哪道闸拦、涉及的批次与数量、怎么解开 (只读, 不执行解开动作)',
    'gate_diagnose_batch', 'MATERIAL', 'QUERY', 'BATCH',
    'COMMON', TRUE, NOW(), NOW()
) ON CONFLICT (intent_code) DO UPDATE SET
    intent_name = EXCLUDED.intent_name,
    keywords = EXCLUDED.keywords,
    tool_name = EXCLUDED.tool_name,
    description = EXCLUDED.description,
    updated_at = NOW();
