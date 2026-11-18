-- V20261118_41__scheduling_rush_order_analysis_intent.sql
--
-- 急单影响分析意图 (融资 BP 第 8 页「急单先把影响算清」, 2026-11-17): 数字计划员收到「周五要交 2 吨酱牛肉急单,
-- 原料够不够, 产线排不排得开?」时, 依次答 ① 原料够不够缺多少 ② 产线和人手 ③ 要挪哪几张原单各晚多久 ④ 方案草案。
-- 意图库以前没有急单 / 插单 / 齐套类意图, 这句被别的规则截走。
-- 新增只读工具 scheduling_rush_order_impact (复用插单原料评估 + 插单影响分析的只读 GET 口径; 不建计划、不占时段、
-- 不建预览) 并绑定新意图 SCHEDULING_RUSH_ORDER_ANALYSIS (FACTORY / ANALYSIS / LOW)。
-- required_roles / required_permission 都不设: 插单时段与影响在 App 排产页只按模块开关开放 (GET urgent-insert 无权限码),
-- 读意图不比 App 页面更严。只新增绑定, 不改既有意图。
-- 事后核对: 恰好 1 行有效意图绑定 scheduling_rush_order_impact。

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    business_type, created_at, updated_at
)
VALUES (
    gen_random_uuid(),
    'SCHEDULING_RUSH_ORDER_ANALYSIS',
    '急单影响分析',
    'ANALYSIS',
    'scheduling_rush_order_impact',
    'LOW',
    '["急单","插单","加急单","紧急订单","齐套","排不排得开","能不能插","插得进去","急单影响"]'::jsonb,
    '急单/插单影响分析 (只读): 给定产品、数量(按产品自带单位)和交期, 回答原料够不够缺多少、交期前产线和人手排不排得开、要挪哪几张原计划各晚多久, 并出一份待负责人确认的文字方案草案; 不建计划不占时段, 算不出来的项说缺什么。',
    90,
    true,
    'FACTORY',
    NOW(),
    NOW()
)
ON CONFLICT (intent_code) DO UPDATE SET
    intent_name         = EXCLUDED.intent_name,
    intent_category     = EXCLUDED.intent_category,
    tool_name           = EXCLUDED.tool_name,
    sensitivity_level   = EXCLUDED.sensitivity_level,
    keywords            = EXCLUDED.keywords,
    description         = EXCLUDED.description,
    priority            = EXCLUDED.priority,
    is_active           = true,
    business_type       = EXCLUDED.business_type,
    deleted_at          = NULL,
    updated_at          = NOW();

DO $$
DECLARE
    bound int;
BEGIN
    SELECT count(*) INTO bound FROM ai_intent_configs
     WHERE tool_name = 'scheduling_rush_order_impact' AND is_active AND deleted_at IS NULL;
    IF bound <> 1 THEN
        RAISE EXCEPTION 'active intents bound to scheduling_rush_order_impact = % (expected 1)', bound;
    END IF;
END $$;
