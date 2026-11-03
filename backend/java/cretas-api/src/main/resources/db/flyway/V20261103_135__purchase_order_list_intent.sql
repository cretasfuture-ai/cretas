-- V20261103_135__purchase_order_list_intent.sql
--
-- 采购订单查询意图 (S6 验收 2026-11-03, F006): 「查询采购订单」「查询采购单」识别成 ORDER_LIST,
-- 答的是「538 个销售订单」—— 采购的问题用销售的数据回答, 是错数据不是没命中。
-- 成因: 工厂目录里没有任何读意图绑定 purchase_order_list (只有 PURCHASE_ORDER_CREATE / _APPROVE 两个写意图),
-- 短语表和 ORDER_LIST 的关键词又把「采购订单 / 采购单」并进了 ORDER_LIST, 而 ORDER_LIST 早已只查销售订单。
--
-- 1. 新意图 PURCHASE_ORDER_LIST → purchase_order_list (FACTORY / PROCUREMENT / LOW)。
--    required_roles / required_permission 都不设: 读意图不比 App 页面更严, 由工具自己的门禁
--    (procurement:read / procurement:read_write, 与采购单列表接口同口径) 决定 —— 同 V20261103_131 的规则。
-- 2. ORDER_LIST 去掉一切含「采购」的关键词, 描述写明只查销售订单 (rerank 读描述)。
-- 3. 事后核对: 读数, 不是假设。
--
-- 预检: literal 里没有 || ; keywords 是 JSONB 数组; 全文没有美元符后紧跟左花括号 (Flyway 占位符)。

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    business_type, created_at, updated_at
)
VALUES (
    gen_random_uuid(),
    'PURCHASE_ORDER_LIST',
    '采购订单查询',
    'PROCUREMENT',
    'purchase_order_list',
    'LOW',
    '["采购订单","采购单","查询采购订单","查询采购单","查看采购订单","采购订单列表","采购单列表","采购单查询","采购订单查询","我们的采购单","向供应商下的单","供应商订单","未审批的采购","收货中的采购单","部分到货的采购单","已完成的采购单","已取消的采购单","purchase order"]'::jsonb,
    '查询采购订单列表 (我们向供应商下的采购单), 可按状态筛选: 草稿/已提交/审批中/已审批/待财务审核/财务已审核/财务驳回/部分到货/已完成/已取消/已关闭。不是销售订单 (客户下给我们的单请用订单列表)。触发: 查询采购订单 / 收货中的采购单 / 已取消的采购单。',
    95,
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

UPDATE ai_intent_configs
SET keywords = COALESCE((
        SELECT jsonb_agg(k ORDER BY pos)
          FROM jsonb_array_elements_text(keywords::jsonb) WITH ORDINALITY AS t(k, pos)
         WHERE k NOT LIKE '%采购%' AND lower(k) NOT LIKE '%purchase%'
    ), '[]'::jsonb),
    description = CASE
        WHEN description IS NULL THEN '查询销售订单列表 (客户下给我们的单)。采购订单不在这里, 请用采购订单查询。'
        WHEN description LIKE '%采购订单不在这里%' THEN description
        ELSE description || ' 只查销售订单 (客户下给我们的单); 采购订单不在这里, 请用采购订单查询。'
    END,
    updated_at = NOW()
WHERE intent_code = 'ORDER_LIST' AND deleted_at IS NULL;

DO $$
DECLARE
    po_rows int;
    leaked int;
BEGIN
    SELECT count(*) INTO po_rows FROM ai_intent_configs
     WHERE intent_code = 'PURCHASE_ORDER_LIST' AND tool_name = 'purchase_order_list'
       AND is_active AND deleted_at IS NULL AND business_type = 'FACTORY';
    IF po_rows <> 1 THEN
        RAISE EXCEPTION 'PURCHASE_ORDER_LIST rows = % (expected 1)', po_rows;
    END IF;
    SELECT count(*) INTO leaked FROM ai_intent_configs c,
           jsonb_array_elements_text(COALESCE(c.keywords::jsonb, '[]'::jsonb)) k
     WHERE c.intent_code = 'ORDER_LIST' AND c.deleted_at IS NULL AND k LIKE '%采购%';
    IF leaked <> 0 THEN
        RAISE EXCEPTION 'ORDER_LIST still has % 采购 keywords', leaked;
    END IF;
END $$;
