-- 工厂意图目录清理 (owner 2026-10-03 批准)。由评测仓 sim/gen_intent_cleanup.py 从 intent_cleanup_plan.py 生成,
-- 生成前已校验: 代码都在在用目录 / 删除停用合并只落在 FACTORY / COMMON 只移出工厂目录 / 合并目标可执行。
-- ⛔ 餐饮不动: COMMON 意图改为 RESTAURANT, 餐饮看到的集合逐条不变。
-- 删除一律软删 (deleted_at), 停用是 is_active=false —— 都可恢复。每行在 metadata.cleanup_20261003 留原因。

CREATE OR REPLACE FUNCTION pg_temp.append_keywords(old jsonb, add jsonb) RETURNS jsonb AS $$
    SELECT COALESCE(jsonb_agg(v ORDER BY first_pos), '[]'::jsonb)
    FROM (SELECT v, MIN(pos) AS first_pos
          FROM jsonb_array_elements_text(COALESCE(old, '[]'::jsonb) || COALESCE(add, '[]'::jsonb)) WITH ORDINALITY AS t(v, pos)
          GROUP BY v) s
$$ LANGUAGE sql IMMUTABLE;

-- BOM_RECIPE_ACTIVATE: 绑定的 Tool 在代码里不存在, 识别到只会报「Tool 未注册」
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '绑定的 Tool 在代码里不存在, 识别到只会报「Tool 未注册」'))
WHERE intent_code = 'BOM_RECIPE_ACTIVATE' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- BOM_RECIPE_CLONE_WITH_MODIFY: 绑定的 Tool 在代码里不存在, 识别到只会报「Tool 未注册」
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '绑定的 Tool 在代码里不存在, 识别到只会报「Tool 未注册」'))
WHERE intent_code = 'BOM_RECIPE_CLONE_WITH_MODIFY' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- BOM_RECIPE_COST_CALCULATE: 绑定的 Tool 在代码里不存在, 识别到只会报「Tool 未注册」
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '绑定的 Tool 在代码里不存在, 识别到只会报「Tool 未注册」'))
WHERE intent_code = 'BOM_RECIPE_COST_CALCULATE' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- BOM_RECIPE_CREATE_FROM_TEXT: 绑定的 Tool 在代码里不存在, 识别到只会报「Tool 未注册」
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '绑定的 Tool 在代码里不存在, 识别到只会报「Tool 未注册」'))
WHERE intent_code = 'BOM_RECIPE_CREATE_FROM_TEXT' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- BOM_RECIPE_CREATE_FROM_SAMPLE: 绑定的 Tool 在代码里不存在, 识别到只会报「Tool 未注册」
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '绑定的 Tool 在代码里不存在, 识别到只会报「Tool 未注册」'))
WHERE intent_code = 'BOM_RECIPE_CREATE_FROM_SAMPLE' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- PAYMENT_REQUEST_QUERY: 绑定的 Tool 在代码里不存在, 识别到只会报「Tool 未注册」
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '绑定的 Tool 在代码里不存在, 识别到只会报「Tool 未注册」'))
WHERE intent_code = 'PAYMENT_REQUEST_QUERY' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- PURCHASE_EXCEPTION_QUERY: 绑定的 Tool 在代码里不存在, 识别到只会报「Tool 未注册」
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '绑定的 Tool 在代码里不存在, 识别到只会报「Tool 未注册」'))
WHERE intent_code = 'PURCHASE_EXCEPTION_QUERY' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- DATA_BATCH_DELETE: 破坏性操作, 不允许由聊天直接触达
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '破坏性操作, 不允许由聊天直接触达'))
WHERE intent_code = 'DATA_BATCH_DELETE' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- INVENTORY_CLEAR: 破坏性操作, 不允许由聊天直接触达
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '破坏性操作, 不允许由聊天直接触达'))
WHERE intent_code = 'INVENTORY_CLEAR' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- USER_DELETE: 破坏性操作, 不允许由聊天直接触达
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '破坏性操作, 不允许由聊天直接触达'))
WHERE intent_code = 'USER_DELETE' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- EQUIPMENT_DELETE: 破坏性操作, 不允许由聊天直接触达
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '破坏性操作, 不允许由聊天直接触达'))
WHERE intent_code = 'EQUIPMENT_DELETE' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- CUSTOMER_DELETE: 破坏性操作, 不允许由聊天直接触达
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '破坏性操作, 不允许由聊天直接触达'))
WHERE intent_code = 'CUSTOMER_DELETE' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- ORDER_DELETE: 破坏性操作, 不允许由聊天直接触达
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '破坏性操作, 不允许由聊天直接触达'))
WHERE intent_code = 'ORDER_DELETE' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- SUPPLIER_DELETE: 破坏性操作, 不允许由聊天直接触达
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '破坏性操作, 不允许由聊天直接触达'))
WHERE intent_code = 'SUPPLIER_DELETE' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- MATERIAL_BATCH_DELETE: 破坏性操作, 不允许由聊天直接触达
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '破坏性操作, 不允许由聊天直接触达'))
WHERE intent_code = 'MATERIAL_BATCH_DELETE' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- SCALE_DELETE_DEVICE: 破坏性操作, 不允许由聊天直接触达
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '破坏性操作, 不允许由聊天直接触达'))
WHERE intent_code = 'SCALE_DELETE_DEVICE' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- CONFIG_RESET: 破坏性操作, 不允许由聊天直接触达
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '破坏性操作, 不允许由聊天直接触达'))
WHERE intent_code = 'CONFIG_RESET' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- USER_CREATE: 账号与权限管理, 不允许由聊天直接触达
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '账号与权限管理, 不允许由聊天直接触达'))
WHERE intent_code = 'USER_CREATE' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- USER_DISABLE: 账号与权限管理, 不允许由聊天直接触达
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '账号与权限管理, 不允许由聊天直接触达'))
WHERE intent_code = 'USER_DISABLE' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- USER_ROLE_ASSIGN: 账号与权限管理, 不允许由聊天直接触达
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '账号与权限管理, 不允许由聊天直接触达'))
WHERE intent_code = 'USER_ROLE_ASSIGN' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- RESTAURANT_OPS_RECIPE_COST: 餐饮意图被标成 COMMON, 工厂不该看到
UPDATE ai_intent_configs SET business_type = 'RESTAURANT', updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'SCOPE_OUT:COMMON->RESTAURANT', 'reason', '餐饮意图被标成 COMMON, 工厂不该看到'))
WHERE intent_code = 'RESTAURANT_OPS_RECIPE_COST' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- RESTAURANT_OPS_SALES_SUMMARY: 餐饮意图被标成 COMMON, 工厂不该看到
UPDATE ai_intent_configs SET business_type = 'RESTAURANT', updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'SCOPE_OUT:COMMON->RESTAURANT', 'reason', '餐饮意图被标成 COMMON, 工厂不该看到'))
WHERE intent_code = 'RESTAURANT_OPS_SALES_SUMMARY' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- RESTAURANT_OPS_TREND_ANALYSIS: 餐饮意图被标成 COMMON, 工厂不该看到
UPDATE ai_intent_configs SET business_type = 'RESTAURANT', updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'SCOPE_OUT:COMMON->RESTAURANT', 'reason', '餐饮意图被标成 COMMON, 工厂不该看到'))
WHERE intent_code = 'RESTAURANT_OPS_TREND_ANALYSIS' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- REVENUE_REPORT_GENERATE: 餐饮意图被标成 COMMON, 工厂不该看到
UPDATE ai_intent_configs SET business_type = 'RESTAURANT', updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'SCOPE_OUT:COMMON->RESTAURANT', 'reason', '餐饮意图被标成 COMMON, 工厂不该看到'))
WHERE intent_code = 'REVENUE_REPORT_GENERATE' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- INTENT_CREATE: 元意图 (通过聊天改 AI 意图配置), 无执行器
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '元意图 (通过聊天改 AI 意图配置), 无执行器'))
WHERE intent_code = 'INTENT_CREATE' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- INTENT_UPDATE: 元意图 (通过聊天改 AI 意图配置), 无执行器
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '元意图 (通过聊天改 AI 意图配置), 无执行器'))
WHERE intent_code = 'INTENT_UPDATE' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- INTENT_ANALYZE: 元意图 (通过聊天改 AI 意图配置), 无执行器
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '元意图 (通过聊天改 AI 意图配置), 无执行器'))
WHERE intent_code = 'INTENT_ANALYZE' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- WORKFLOW_RULE_TEST: 测试类意图, 工厂一线不该在聊天里看到
UPDATE ai_intent_configs SET business_type = 'RESTAURANT', updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'SCOPE_OUT:COMMON->RESTAURANT', 'reason', '测试类意图, 工厂一线不该在聊天里看到'))
WHERE intent_code = 'WORKFLOW_RULE_TEST' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- COLD_CHAIN_TEMPERATURE → COLD_CHAIN_TEMP_QUERY: 无执行器; 查温度已有可执行意图
UPDATE ai_intent_configs t SET keywords = pg_temp.append_keywords(t.keywords, s.keywords),
    config_version = COALESCE(t.config_version, 0) + 1, updated_at = NOW()
FROM ai_intent_configs s WHERE s.intent_code = 'COLD_CHAIN_TEMPERATURE' AND s.deleted_at IS NULL
  AND t.intent_code = 'COLD_CHAIN_TEMP_QUERY' AND t.deleted_at IS NULL;
UPDATE ai_intent_configs SET deleted_at = NOW(), updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'MERGE_INTO:COLD_CHAIN_TEMP_QUERY', 'reason', '无执行器; 查温度已有可执行意图'))
WHERE intent_code = 'COLD_CHAIN_TEMPERATURE' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- PRODUCTION_PLAN_CREATE_FULL → PRODUCTION_PLAN_CREATE: 无执行器; 建计划已有可执行意图
UPDATE ai_intent_configs t SET keywords = pg_temp.append_keywords(t.keywords, s.keywords),
    config_version = COALESCE(t.config_version, 0) + 1, updated_at = NOW()
FROM ai_intent_configs s WHERE s.intent_code = 'PRODUCTION_PLAN_CREATE_FULL' AND s.deleted_at IS NULL
  AND t.intent_code = 'PRODUCTION_PLAN_CREATE' AND t.deleted_at IS NULL;
UPDATE ai_intent_configs SET deleted_at = NOW(), updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'MERGE_INTO:PRODUCTION_PLAN_CREATE', 'reason', '无执行器; 建计划已有可执行意图'))
WHERE intent_code = 'PRODUCTION_PLAN_CREATE_FULL' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- ORDER_FILTER → ORDER_LIST: 无执行器; 订单查询已有可执行意图
UPDATE ai_intent_configs t SET keywords = pg_temp.append_keywords(t.keywords, s.keywords),
    config_version = COALESCE(t.config_version, 0) + 1, updated_at = NOW()
FROM ai_intent_configs s WHERE s.intent_code = 'ORDER_FILTER' AND s.deleted_at IS NULL
  AND t.intent_code = 'ORDER_LIST' AND t.deleted_at IS NULL;
UPDATE ai_intent_configs SET deleted_at = NOW(), updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'MERGE_INTO:ORDER_LIST', 'reason', '无执行器; 订单查询已有可执行意图'))
WHERE intent_code = 'ORDER_FILTER' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- COST_TREND_ANALYSIS → COST_QUERY: 无执行器; 成本查询已有可执行意图
UPDATE ai_intent_configs t SET keywords = pg_temp.append_keywords(t.keywords, s.keywords),
    config_version = COALESCE(t.config_version, 0) + 1, updated_at = NOW()
FROM ai_intent_configs s WHERE s.intent_code = 'COST_TREND_ANALYSIS' AND s.deleted_at IS NULL
  AND t.intent_code = 'COST_QUERY' AND t.deleted_at IS NULL;
UPDATE ai_intent_configs SET deleted_at = NOW(), updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'MERGE_INTO:COST_QUERY', 'reason', '无执行器; 成本查询已有可执行意图'))
WHERE intent_code = 'COST_TREND_ANALYSIS' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- WORK_ORDER_UPDATE: 无执行器 (修改工单未接工具)
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '无执行器 (修改工单未接工具)'))
WHERE intent_code = 'WORK_ORDER_UPDATE' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- APPROVAL_SUBMIT: 无执行器 (提交审批未接工具)
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '无执行器 (提交审批未接工具)'))
WHERE intent_code = 'APPROVAL_SUBMIT' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- BOM_CATEGORY_FILTER: 无执行器
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '无执行器'))
WHERE intent_code = 'BOM_CATEGORY_FILTER' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- PLAN_FROM_SALES_ORDER_ITEM: 无执行器
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '无执行器'))
WHERE intent_code = 'PLAN_FROM_SALES_ORDER_ITEM' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- WORK_REPORT_MODE_QUERY: 无执行器
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '无执行器'))
WHERE intent_code = 'WORK_REPORT_MODE_QUERY' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- QUERY_FINANCE_ROA: 无执行器; 财务比率没有接工具 (财务报表类另有可执行意图)
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '无执行器; 财务比率没有接工具 (财务报表类另有可执行意图)'))
WHERE intent_code = 'QUERY_FINANCE_ROA' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- QUERY_FINANCE_ROE: 无执行器; 财务比率没有接工具 (财务报表类另有可执行意图)
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '无执行器; 财务比率没有接工具 (财务报表类另有可执行意图)'))
WHERE intent_code = 'QUERY_FINANCE_ROE' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- QUERY_LIQUIDITY: 无执行器; 财务比率没有接工具 (财务报表类另有可执行意图)
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '无执行器; 财务比率没有接工具 (财务报表类另有可执行意图)'))
WHERE intent_code = 'QUERY_LIQUIDITY' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- QUERY_SOLVENCY: 无执行器; 财务比率没有接工具 (财务报表类另有可执行意图)
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '无执行器; 财务比率没有接工具 (财务报表类另有可执行意图)'))
WHERE intent_code = 'QUERY_SOLVENCY' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- QUERY_DUPONT_ANALYSIS: 无执行器; 财务比率没有接工具 (财务报表类另有可执行意图)
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '无执行器; 财务比率没有接工具 (财务报表类另有可执行意图)'))
WHERE intent_code = 'QUERY_DUPONT_ANALYSIS' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- COLD_CHAIN_MONITORING: 无执行器 (COMMON, 只移出工厂目录)
UPDATE ai_intent_configs SET business_type = 'RESTAURANT', updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'SCOPE_OUT:COMMON->RESTAURANT', 'reason', '无执行器 (COMMON, 只移出工厂目录)'))
WHERE intent_code = 'COLD_CHAIN_MONITORING' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- SSOP_DAILY_SANITATION: 无执行器 (COMMON, 只移出工厂目录)
UPDATE ai_intent_configs SET business_type = 'RESTAURANT', updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'SCOPE_OUT:COMMON->RESTAURANT', 'reason', '无执行器 (COMMON, 只移出工厂目录)'))
WHERE intent_code = 'SSOP_DAILY_SANITATION' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- WAREHOUSE_TODAY_RECEIVING_PENDING: 无执行器 (COMMON, 只移出工厂目录)
UPDATE ai_intent_configs SET business_type = 'RESTAURANT', updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'SCOPE_OUT:COMMON->RESTAURANT', 'reason', '无执行器 (COMMON, 只移出工厂目录)'))
WHERE intent_code = 'WAREHOUSE_TODAY_RECEIVING_PENDING' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- HACCP_CHECKPOINT: 无执行器 (COMMON, 只移出工厂目录)
UPDATE ai_intent_configs SET business_type = 'RESTAURANT', updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'SCOPE_OUT:COMMON->RESTAURANT', 'reason', '无执行器 (COMMON, 只移出工厂目录)'))
WHERE intent_code = 'HACCP_CHECKPOINT' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- ADDITIVE_COMPLIANCE: 无执行器 (COMMON, 只移出工厂目录)
UPDATE ai_intent_configs SET business_type = 'RESTAURANT', updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'SCOPE_OUT:COMMON->RESTAURANT', 'reason', '无执行器 (COMMON, 只移出工厂目录)'))
WHERE intent_code = 'ADDITIVE_COMPLIANCE' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- FOOD_SAMPLE_LIFECYCLE: 无执行器 (COMMON, 只移出工厂目录)
UPDATE ai_intent_configs SET business_type = 'RESTAURANT', updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'SCOPE_OUT:COMMON->RESTAURANT', 'reason', '无执行器 (COMMON, 只移出工厂目录)'))
WHERE intent_code = 'FOOD_SAMPLE_LIFECYCLE' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- SUPPLIER_QUALIFICATION_ALERT: 无执行器 (COMMON, 只移出工厂目录)
UPDATE ai_intent_configs SET business_type = 'RESTAURANT', updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'SCOPE_OUT:COMMON->RESTAURANT', 'reason', '无执行器 (COMMON, 只移出工厂目录)'))
WHERE intent_code = 'SUPPLIER_QUALIFICATION_ALERT' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- PRODUCT_PRODUCT_SALES_RANKING: 无执行器 (COMMON, 只移出工厂目录)
UPDATE ai_intent_configs SET business_type = 'RESTAURANT', updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'SCOPE_OUT:COMMON->RESTAURANT', 'reason', '无执行器 (COMMON, 只移出工厂目录)'))
WHERE intent_code = 'PRODUCT_PRODUCT_SALES_RANKING' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- FREE_GIFT_INBOUND: 无执行器 (COMMON, 只移出工厂目录)
UPDATE ai_intent_configs SET business_type = 'RESTAURANT', updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'SCOPE_OUT:COMMON->RESTAURANT', 'reason', '无执行器 (COMMON, 只移出工厂目录)'))
WHERE intent_code = 'FREE_GIFT_INBOUND' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- INVENTORY_GAIN_INBOUND: 无执行器 (COMMON, 只移出工厂目录)
UPDATE ai_intent_configs SET business_type = 'RESTAURANT', updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'SCOPE_OUT:COMMON->RESTAURANT', 'reason', '无执行器 (COMMON, 只移出工厂目录)'))
WHERE intent_code = 'INVENTORY_GAIN_INBOUND' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- SALES_DELIVERY_BATCH_ALLOCATE: 无执行器 (COMMON, 只移出工厂目录)
UPDATE ai_intent_configs SET business_type = 'RESTAURANT', updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'SCOPE_OUT:COMMON->RESTAURANT', 'reason', '无执行器 (COMMON, 只移出工厂目录)'))
WHERE intent_code = 'SALES_DELIVERY_BATCH_ALLOCATE' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- TAX_GROUP_INVOICE: 无执行器 (COMMON, 只移出工厂目录)
UPDATE ai_intent_configs SET business_type = 'RESTAURANT', updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'SCOPE_OUT:COMMON->RESTAURANT', 'reason', '无执行器 (COMMON, 只移出工厂目录)'))
WHERE intent_code = 'TAX_GROUP_INVOICE' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- TRACE_CODE_FORMAT: 无执行器 (COMMON, 只移出工厂目录)
UPDATE ai_intent_configs SET business_type = 'RESTAURANT', updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'SCOPE_OUT:COMMON->RESTAURANT', 'reason', '无执行器 (COMMON, 只移出工厂目录)'))
WHERE intent_code = 'TRACE_CODE_FORMAT' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- CAMERA_STREAMS: 无执行器 (COMMON, 只移出工厂目录)
UPDATE ai_intent_configs SET business_type = 'RESTAURANT', updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'SCOPE_OUT:COMMON->RESTAURANT', 'reason', '无执行器 (COMMON, 只移出工厂目录)'))
WHERE intent_code = 'CAMERA_STREAMS' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- CAMERA_SUBSCRIBE: 无执行器 (COMMON, 只移出工厂目录)
UPDATE ai_intent_configs SET business_type = 'RESTAURANT', updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'SCOPE_OUT:COMMON->RESTAURANT', 'reason', '无执行器 (COMMON, 只移出工厂目录)'))
WHERE intent_code = 'CAMERA_SUBSCRIBE' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- CAMERA_UNSUBSCRIBE: 无执行器 (COMMON, 只移出工厂目录)
UPDATE ai_intent_configs SET business_type = 'RESTAURANT', updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'SCOPE_OUT:COMMON->RESTAURANT', 'reason', '无执行器 (COMMON, 只移出工厂目录)'))
WHERE intent_code = 'CAMERA_UNSUBSCRIBE' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- SYSTEM_FEEDBACK: 无执行器 (COMMON, 只移出工厂目录)
UPDATE ai_intent_configs SET business_type = 'RESTAURANT', updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'SCOPE_OUT:COMMON->RESTAURANT', 'reason', '无执行器 (COMMON, 只移出工厂目录)'))
WHERE intent_code = 'SYSTEM_FEEDBACK' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- SYSTEM_PASSWORD_RESET: 无执行器 (COMMON, 只移出工厂目录)
UPDATE ai_intent_configs SET business_type = 'RESTAURANT', updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'SCOPE_OUT:COMMON->RESTAURANT', 'reason', '无执行器 (COMMON, 只移出工厂目录)'))
WHERE intent_code = 'SYSTEM_PASSWORD_RESET' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- SYSTEM_PERMISSION_QUERY: 无执行器 (COMMON, 只移出工厂目录)
UPDATE ai_intent_configs SET business_type = 'RESTAURANT', updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'SCOPE_OUT:COMMON->RESTAURANT', 'reason', '无执行器 (COMMON, 只移出工厂目录)'))
WHERE intent_code = 'SYSTEM_PERMISSION_QUERY' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- SYSTEM_PROFILE_EDIT: 无执行器 (COMMON, 只移出工厂目录)
UPDATE ai_intent_configs SET business_type = 'RESTAURANT', updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'SCOPE_OUT:COMMON->RESTAURANT', 'reason', '无执行器 (COMMON, 只移出工厂目录)'))
WHERE intent_code = 'SYSTEM_PROFILE_EDIT' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- SYSTEM_SETTINGS: 无执行器 (COMMON, 只移出工厂目录)
UPDATE ai_intent_configs SET business_type = 'RESTAURANT', updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'SCOPE_OUT:COMMON->RESTAURANT', 'reason', '无执行器 (COMMON, 只移出工厂目录)'))
WHERE intent_code = 'SYSTEM_SETTINGS' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- SYSTEM_SWITCH_FACTORY: 无执行器 (COMMON, 只移出工厂目录)
UPDATE ai_intent_configs SET business_type = 'RESTAURANT', updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'SCOPE_OUT:COMMON->RESTAURANT', 'reason', '无执行器 (COMMON, 只移出工厂目录)'))
WHERE intent_code = 'SYSTEM_SWITCH_FACTORY' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- QUALITY_CHECK_EXECUTE → QUALITY_CHECK_CREATE: 与新建质检绑定同一个 Tool
UPDATE ai_intent_configs t SET keywords = pg_temp.append_keywords(t.keywords, s.keywords),
    config_version = COALESCE(t.config_version, 0) + 1, updated_at = NOW()
FROM ai_intent_configs s WHERE s.intent_code = 'QUALITY_CHECK_EXECUTE' AND s.deleted_at IS NULL
  AND t.intent_code = 'QUALITY_CHECK_CREATE' AND t.deleted_at IS NULL;
UPDATE ai_intent_configs SET deleted_at = NOW(), updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'MERGE_INTO:QUALITY_CHECK_CREATE', 'reason', '与新建质检绑定同一个 Tool'))
WHERE intent_code = 'QUALITY_CHECK_EXECUTE' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- EFFICIENCY_COMPARE_ALIAS: 「别名」重复 (COMMON, 只移出工厂目录)
UPDATE ai_intent_configs SET business_type = 'RESTAURANT', updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'SCOPE_OUT:COMMON->RESTAURANT', 'reason', '「别名」重复 (COMMON, 只移出工厂目录)'))
WHERE intent_code = 'EFFICIENCY_COMPARE_ALIAS' AND business_type = 'COMMON' AND deleted_at IS NULL;
