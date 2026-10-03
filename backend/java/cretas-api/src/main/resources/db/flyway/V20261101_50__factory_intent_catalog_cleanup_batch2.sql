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

-- ISAPI_CONFIG_FIELD_DETECTION: 设备配置, 现阶段不做
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '设备配置, 现阶段不做'))
WHERE intent_code = 'ISAPI_CONFIG_FIELD_DETECTION' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- ISAPI_CONFIG_LINE_DETECTION: 设备配置, 现阶段不做
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '设备配置, 现阶段不做'))
WHERE intent_code = 'ISAPI_CONFIG_LINE_DETECTION' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- ISAPI_QUERY_CAPABILITIES: 设备配置, 现阶段不做
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '设备配置, 现阶段不做'))
WHERE intent_code = 'ISAPI_QUERY_CAPABILITIES' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- SCALE_ADD_DEVICE: 设备配置, 现阶段不做
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '设备配置, 现阶段不做'))
WHERE intent_code = 'SCALE_ADD_DEVICE' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- SCALE_ADD_DEVICE_VISION: 设备配置, 现阶段不做
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '设备配置, 现阶段不做'))
WHERE intent_code = 'SCALE_ADD_DEVICE_VISION' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- SCALE_UPDATE_DEVICE: 设备配置, 现阶段不做
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '设备配置, 现阶段不做'))
WHERE intent_code = 'SCALE_UPDATE_DEVICE' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- COLD_CHAIN_EQUIPMENT_REGISTER: 设备配置 (COMMON), 现阶段不做
UPDATE ai_intent_configs SET business_type = 'RESTAURANT', updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'SCOPE_OUT:COMMON->RESTAURANT', 'reason', '设备配置 (COMMON), 现阶段不做'))
WHERE intent_code = 'COLD_CHAIN_EQUIPMENT_REGISTER' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- FACTORY_FEATURE_TOGGLE: 系统功能开关, 不在聊天里改
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '系统功能开关, 不在聊天里改'))
WHERE intent_code = 'FACTORY_FEATURE_TOGGLE' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- FORM_GENERATION: 表单字段生成 (开发配置), 不在聊天里做
UPDATE ai_intent_configs SET is_active = false, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'DEACTIVATE', 'reason', '表单字段生成 (开发配置), 不在聊天里做'))
WHERE intent_code = 'FORM_GENERATION' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- GOVERNANCE_PATTERN_DISCOVERY: Skill/Tool 治理 (COMMON), 不在工厂聊天里做
UPDATE ai_intent_configs SET business_type = 'RESTAURANT', updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'SCOPE_OUT:COMMON->RESTAURANT', 'reason', 'Skill/Tool 治理 (COMMON), 不在工厂聊天里做'))
WHERE intent_code = 'GOVERNANCE_PATTERN_DISCOVERY' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- GOVERNANCE_SKILL_COMPOSE: Skill/Tool 治理 (COMMON), 不在工厂聊天里做
UPDATE ai_intent_configs SET business_type = 'RESTAURANT', updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'SCOPE_OUT:COMMON->RESTAURANT', 'reason', 'Skill/Tool 治理 (COMMON), 不在工厂聊天里做'))
WHERE intent_code = 'GOVERNANCE_SKILL_COMPOSE' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- GOVERNANCE_SKILL_MANAGE: Skill/Tool 治理 (COMMON), 不在工厂聊天里做
UPDATE ai_intent_configs SET business_type = 'RESTAURANT', updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'SCOPE_OUT:COMMON->RESTAURANT', 'reason', 'Skill/Tool 治理 (COMMON), 不在工厂聊天里做'))
WHERE intent_code = 'GOVERNANCE_SKILL_MANAGE' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- WORK_PROCESS_CONFIG_UPDATE: 配置产品工序 —— 保留在聊天里, 但必须有权限 (production:read_write) + WriteGuard 二次确认
UPDATE ai_intent_configs SET sensitivity_level = 'HIGH',
    required_permission = COALESCE(NULLIF(required_permission, ''), 'production:read_write'),
    updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'HARDEN', 'reason', '配置产品工序'))
WHERE intent_code = 'WORK_PROCESS_CONFIG_UPDATE' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- RULE_CONFIG: 规则配置 —— 保留在聊天里, 但必须有权限 (system:read_write) + WriteGuard 二次确认
UPDATE ai_intent_configs SET sensitivity_level = 'HIGH',
    required_permission = COALESCE(NULLIF(required_permission, ''), 'system:read_write'),
    updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'HARDEN', 'reason', '规则配置'))
WHERE intent_code = 'RULE_CONFIG' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- CONVERSION_RATE_UPDATE: 转换率配置 —— 保留在聊天里, 但必须有权限 (production:read_write) + WriteGuard 二次确认
UPDATE ai_intent_configs SET sensitivity_level = 'HIGH',
    required_permission = COALESCE(NULLIF(required_permission, ''), 'production:read_write'),
    updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'HARDEN', 'reason', '转换率配置'))
WHERE intent_code = 'CONVERSION_RATE_UPDATE' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- SCHEDULING_SET_AUTO: 排产模式: 全自动 —— 保留在聊天里, 但必须有权限 (system:read_write) + WriteGuard 二次确认
UPDATE ai_intent_configs SET sensitivity_level = 'HIGH',
    required_permission = COALESCE(NULLIF(required_permission, ''), 'system:read_write'),
    updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'HARDEN', 'reason', '排产模式: 全自动'))
WHERE intent_code = 'SCHEDULING_SET_AUTO' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- SCHEDULING_SET_MANUAL: 排产模式: 人工确认 —— 保留在聊天里, 但必须有权限 (system:read_write) + WriteGuard 二次确认
UPDATE ai_intent_configs SET sensitivity_level = 'HIGH',
    required_permission = COALESCE(NULLIF(required_permission, ''), 'system:read_write'),
    updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'HARDEN', 'reason', '排产模式: 人工确认'))
WHERE intent_code = 'SCHEDULING_SET_MANUAL' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- SCHEDULING_SET_DISABLED: 排产模式: 禁用自动排产 —— 保留在聊天里, 但必须有权限 (system:read_write) + WriteGuard 二次确认
UPDATE ai_intent_configs SET sensitivity_level = 'HIGH',
    required_permission = COALESCE(NULLIF(required_permission, ''), 'system:read_write'),
    updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'HARDEN', 'reason', '排产模式: 禁用自动排产'))
WHERE intent_code = 'SCHEDULING_SET_DISABLED' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- FACTORY_NOTIFICATION_CONFIG: 通知设置 —— 保留在聊天里, 但必须有权限 (system:read_write) + WriteGuard 二次确认
UPDATE ai_intent_configs SET sensitivity_level = 'HIGH',
    required_permission = COALESCE(NULLIF(required_permission, ''), 'system:read_write'),
    updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'HARDEN', 'reason', '通知设置'))
WHERE intent_code = 'FACTORY_NOTIFICATION_CONFIG' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- PERIOD_REOPEN: 反结账 —— 保留在聊天里, 但必须有权限 (finance:read_write) + WriteGuard 二次确认
UPDATE ai_intent_configs SET sensitivity_level = 'HIGH',
    required_permission = COALESCE(NULLIF(required_permission, ''), 'finance:read_write'),
    updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'HARDEN', 'reason', '反结账'))
WHERE intent_code = 'PERIOD_REOPEN' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- MY_APPROVAL_WORKDESK → APPROVAL_PENDING_QUERY: 与待审批列表同一个 Tool, Tool 不区分意图
UPDATE ai_intent_configs t SET keywords = pg_temp.append_keywords(t.keywords, s.keywords),
    config_version = COALESCE(t.config_version, 0) + 1, updated_at = NOW()
FROM ai_intent_configs s WHERE s.intent_code = 'MY_APPROVAL_WORKDESK' AND s.deleted_at IS NULL
  AND t.intent_code = 'APPROVAL_PENDING_QUERY' AND t.deleted_at IS NULL;
UPDATE ai_intent_configs SET deleted_at = NOW(), updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'MERGE_INTO:APPROVAL_PENDING_QUERY', 'reason', '与待审批列表同一个 Tool, Tool 不区分意图'))
WHERE intent_code = 'MY_APPROVAL_WORKDESK' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- WAREHOUSE_KEEPER_TODAY_TASKS → MATERIAL_TODAY_RECEIVING_QUERY: 与今日待收货同一个 Tool, Tool 不区分意图
UPDATE ai_intent_configs t SET keywords = pg_temp.append_keywords(t.keywords, s.keywords),
    config_version = COALESCE(t.config_version, 0) + 1, updated_at = NOW()
FROM ai_intent_configs s WHERE s.intent_code = 'WAREHOUSE_KEEPER_TODAY_TASKS' AND s.deleted_at IS NULL
  AND t.intent_code = 'MATERIAL_TODAY_RECEIVING_QUERY' AND t.deleted_at IS NULL;
UPDATE ai_intent_configs SET deleted_at = NOW(), updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'MERGE_INTO:MATERIAL_TODAY_RECEIVING_QUERY', 'reason', '与今日待收货同一个 Tool, Tool 不区分意图'))
WHERE intent_code = 'WAREHOUSE_KEEPER_TODAY_TASKS' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- PURCHASER_WEEKLY_PLAN → STOCK_ALERT: 与低库存预警同一个 Tool, Tool 不区分意图
UPDATE ai_intent_configs t SET keywords = pg_temp.append_keywords(t.keywords, s.keywords),
    config_version = COALESCE(t.config_version, 0) + 1, updated_at = NOW()
FROM ai_intent_configs s WHERE s.intent_code = 'PURCHASER_WEEKLY_PLAN' AND s.deleted_at IS NULL
  AND t.intent_code = 'STOCK_ALERT' AND t.deleted_at IS NULL;
UPDATE ai_intent_configs SET deleted_at = NOW(), updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'MERGE_INTO:STOCK_ALERT', 'reason', '与低库存预警同一个 Tool, Tool 不区分意图'))
WHERE intent_code = 'PURCHASER_WEEKLY_PLAN' AND business_type = 'COMMON' AND deleted_at IS NULL;

-- SHIPMENT_BY_CUSTOMER → SHIPMENT_QUERY: 按客户查出货: 查询出货的 Tool 支持客户筛选 (原 Tool 强制要客户ID)
UPDATE ai_intent_configs t SET keywords = pg_temp.append_keywords(t.keywords, s.keywords),
    config_version = COALESCE(t.config_version, 0) + 1, updated_at = NOW()
FROM ai_intent_configs s WHERE s.intent_code = 'SHIPMENT_BY_CUSTOMER' AND s.deleted_at IS NULL
  AND t.intent_code = 'SHIPMENT_QUERY' AND t.deleted_at IS NULL;
UPDATE ai_intent_configs SET deleted_at = NOW(), updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'MERGE_INTO:SHIPMENT_QUERY', 'reason', '按客户查出货: 查询出货的 Tool 支持客户筛选 (原 Tool 强制要客户ID)'))
WHERE intent_code = 'SHIPMENT_BY_CUSTOMER' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- SHIPMENT_BY_DATE → SHIPMENT_QUERY: 按日期查出货: 查询出货的 Tool 支持日期范围
UPDATE ai_intent_configs t SET keywords = pg_temp.append_keywords(t.keywords, s.keywords),
    config_version = COALESCE(t.config_version, 0) + 1, updated_at = NOW()
FROM ai_intent_configs s WHERE s.intent_code = 'SHIPMENT_BY_DATE' AND s.deleted_at IS NULL
  AND t.intent_code = 'SHIPMENT_QUERY' AND t.deleted_at IS NULL;
UPDATE ai_intent_configs SET deleted_at = NOW(), updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'MERGE_INTO:SHIPMENT_QUERY', 'reason', '按日期查出货: 查询出货的 Tool 支持日期范围'))
WHERE intent_code = 'SHIPMENT_BY_DATE' AND business_type = 'FACTORY' AND deleted_at IS NULL;

-- ALERT_BY_LEVEL → ALERT_LIST: 按级别查告警: 告警列表 Tool 支持级别筛选
UPDATE ai_intent_configs t SET keywords = pg_temp.append_keywords(t.keywords, s.keywords),
    config_version = COALESCE(t.config_version, 0) + 1, updated_at = NOW()
FROM ai_intent_configs s WHERE s.intent_code = 'ALERT_BY_LEVEL' AND s.deleted_at IS NULL
  AND t.intent_code = 'ALERT_LIST' AND t.deleted_at IS NULL;
UPDATE ai_intent_configs SET deleted_at = NOW(), updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object('cleanup_20261003', jsonb_build_object('action', 'MERGE_INTO:ALERT_LIST', 'reason', '按级别查告警: 告警列表 Tool 支持级别筛选'))
WHERE intent_code = 'ALERT_BY_LEVEL' AND business_type = 'FACTORY' AND deleted_at IS NULL;
