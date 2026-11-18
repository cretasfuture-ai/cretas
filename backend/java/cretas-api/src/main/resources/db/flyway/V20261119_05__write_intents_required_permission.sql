-- @write-intent-required-permission
-- 启用的写意图补 required_permission (2026-11-18 合入, 原 V20261117_81 上调到 V20261119_05: main 已有 V20261118_95, 在飞原型 PR 要占 _99; 2026-11-17, 数字班组「系统里没有登记谁有权确认，所以不交办」)。
--
-- 现象: 数字仓管「今天到的 500kg 牛肉前腱子入原料仓」→ RECEIVE_CONFIRM_CREATE → READ_MODE_WRITE_BLOCKED, 但意图没登记
-- 权限码, 数字班组不知道谁有权确认, 整件拒绝; 质检员的写操作整组交办不了, 建生产计划同样。
--
-- 口径 (每行的码, 不新造权限名):
--   1. 工具自己声明了 getRequiredPermissions (与 web 端同功能接口 @RequirePermission 同源, GatedDataToolAiPathPermissionTest
--      钉住) ⇒ 原样照抄, 逗号 = 任一即可 (与 IntentPermissionGate / ToolRbacEnforcer 同语义, 不收紧也不放宽);
--   2. 工具没声明 ⇒ 取 web 端做同一件事的接口的 @RequirePermission (PR 描述逐条列接口);
--      web 是 sales:edit 的 (商机推进 / 企微记录) 写 sales:read_write: PermissionServiceImpl.checkAction 对 edit 走 default
--      分支 = 要求 read_write, 两者同宽;
--   3. 已有迁移登记过的 (V20261101_50 等) 照旧写同一个值, 本迁移只补空, 不覆盖。
-- 没有 web 对应 / 只按平台身份或审批节点岗位放行的写意图不在这里登记, 名单在 WriteIntentRequiredPermissionContractTest.EXEMPT,
-- 交 organizer 裁决。
--
-- 只补空: 已经有值的行 (含 prod 上早先手工回填的) 不动; 已软删的行不动; 全局行和按厂覆盖行一起补。
-- 闸: WriteIntentRequiredPermissionContractTest 读本文件的 ('意图码', '权限码') 元组, 目录里的启用写意图缺一个就红。
--
-- #1816 终审:
--   H1 CLOCK_IN / CLOCK_OUT 不登记 (一线打卡默认开放 FrontlineAiWriteGrantService / V20261101_55, 补 hr:read_write 会让
--      操作员在 AI 里打不了卡), 走旧角色名单 + 一线开放名单, 列 EXEMPT;
--   M2 PRODUCTION_PLAN_CREATE 只留 production:read_write (与工具层一致, 不带 scheduling);
--   M3 先把命中的行 (id, 意图码, 厂, 旧值, 新值) 写进备份表, 再按备份表改。回滚 (只回本迁移改过、此后没人再动过的行):
--      UPDATE ai_intent_configs c SET required_permission = b.old_required_permission, updated_at = NOW()
--      FROM ai_intent_required_permission_bak_20261118 b
--      WHERE c.id = b.id AND c.required_permission IS NOT DISTINCT FROM b.new_required_permission;

CREATE TABLE IF NOT EXISTS ai_intent_required_permission_bak_20261118 (
    id                      VARCHAR(36)  NOT NULL PRIMARY KEY,
    intent_code             VARCHAR(50)  NOT NULL,
    factory_id              VARCHAR(50),
    old_required_permission VARCHAR(64),
    new_required_permission VARCHAR(64)  NOT NULL,
    backed_up_at            TIMESTAMP    NOT NULL DEFAULT NOW()
);
COMMENT ON TABLE ai_intent_required_permission_bak_20261118 IS
    'V20261119_05 补 required_permission 前的旧值 (回滚依据, 见迁移头注释); 只增不改';

INSERT INTO ai_intent_required_permission_bak_20261118 (id, intent_code, factory_id, old_required_permission, new_required_permission)
SELECT c.id, c.intent_code, c.factory_id, c.required_permission, v.perm
FROM ai_intent_configs c
JOIN (VALUES
    -- 工具声明照抄
    ('ABACA_WEIGHT_LOG', 'procurement:read_write'),
    ('COLD_CHAIN_ALERT_ACKNOWLEDGE', 'equipment:read_write,quality:read_write'),
    ('COLD_CHAIN_EQUIPMENT_REGISTER', 'equipment:read_write'),
    ('FINANCE_PAYMENT_RECORD', 'finance:read_write,sales:read_write'),
    ('FOOD_SAMPLE_CREATE', 'quality:read_write'),
    ('FOOD_SAMPLE_DISPOSE', 'quality:read_write'),
    ('FREE_GIFT_INBOUND', 'warehouse:read_write'),
    ('HR_LEAVE_SUBMIT', 'hr:read,hr:read_write'),
    ('INVENTORY_GAIN_INBOUND', 'warehouse:read_write'),
    ('PDA_SCAN_TASK_GENERATE', 'warehouse:read_write,inventory:write'),
    ('PRODUCT_CREATE_FLYWHEEL', 'production:read_write,system:read_write'),
    ('RD_REQUEST_CREATE', 'rd:read_write'),
    ('RD_SAMPLE_APPROVE', 'rd:read_write'),
    ('RD_SAMPLE_CREATE', 'rd:read_write'),
    ('RECEIVE_CONFIRM_CREATE', 'warehouse:read_write,inventory:write'),
    ('RECEIVE_WITH_LIMIT', 'warehouse:read_write,inventory:write'),
    ('RETURN_ORDER_APPROVE', 'sales:read_write,procurement:read_write'),
    ('SALES_DELIVERY_BATCH_ALLOCATE', 'sales:read_write,warehouse:read_write'),
    ('SALES_NEED_CONVERT', 'sales:read_write,system:read_write'),
    ('SHIPMENT_CONFIRM_CREATE', 'sales:read_write'),
    ('SHIPMENT_CREATE', 'sales:read_write'),
    ('SSOP_EXECUTE_COMPLETE', 'quality:read_write'),
    ('SSOP_PROCEDURE_CREATE', 'quality:read_write'),
    ('SUPPLIER_QUALIFICATION_CREATE', 'procurement:read_write'),
    -- web 端同功能接口
    ('ALERT_ACKNOWLEDGE', 'equipment:read_write'),
    ('ALERT_RESOLVE', 'equipment:read_write'),
    ('BATCH_UPDATE', 'warehouse:read_write,inventory:read_write'),
    ('CAMERA_SUBSCRIBE', 'system:read_write,equipment:read_write'),
    ('CAMERA_UNSUBSCRIBE', 'system:read_write,equipment:read_write'),
    ('FACTORY_MR_CLOSE', 'warehouse:read_write,production:read_write'),
    ('FACTORY_MR_GENERATE', 'warehouse:read_write,production:read_write'),
    ('FINANCE_INVOICE_APPROVE', 'finance:read_write'),
    ('HR_LEAVE_APPROVE', 'hr:read_write'),
    ('INVENTORY_FREEZE', 'warehouse:read_write,inventory:read_write'),
    ('MATERIAL_ADJUST_QUANTITY', 'warehouse:read_write,inventory:read_write'),
    ('MATERIAL_BATCH_CONSUME', 'warehouse:read_write,inventory:read_write'),
    ('MATERIAL_BATCH_CREATE', 'warehouse:read_write,inventory:read_write'),
    ('MATERIAL_BATCH_RELEASE', 'warehouse:read_write,inventory:read_write'),
    ('MATERIAL_BATCH_RESERVE', 'warehouse:read_write,inventory:read_write'),
    ('MATERIAL_BATCH_USE', 'warehouse:read_write,inventory:read_write'),
    ('MATERIAL_UPDATE', 'warehouse:read_write,inventory:read_write'),
    ('OPPORTUNITY_TRANSITION', 'sales:read_write'),
    ('PERIOD_CONFIRM_CLOSE', 'finance:read_write'),
    ('PERIOD_REQUEST_CLOSE', 'finance:read_write'),
    ('PLAN_UPDATE', 'production:read_write,scheduling:read_write'),
    ('PROCUREMENT_ORDER_CREATE', 'procurement:read_write'),
    ('PRODUCT_UPDATE', 'production:read_write,rd:read_write'),
    ('PRODUCTION_BATCH_CREATE', 'production:read_write'),
    ('PRODUCTION_PLAN_CREATE', 'production:read_write'),
    ('QUALITY_CHECK_CREATE', 'quality:read_write'),
    ('QUALITY_DEFECT_RECORD', 'quality:read_write'),
    ('QUALITY_DISPOSITION_EXECUTE', 'quality:read_write'),
    ('QUALITY_RETURN_CREATE', 'quality:read_write'),
    ('RELEASE_DECISION', 'quality:read_write'),
    ('REQUISITION_CREATE', 'procurement:read_write'),
    ('RETURN_ORDER_CREATE', 'sales:read_write,procurement:read_write'),
    ('REUSABLE_CONTAINER_LOSS', 'warehouse:read_write'),
    ('REUSABLE_CONTAINER_RETURN_IN', 'warehouse:read_write'),
    ('REUSABLE_CONTAINER_SHIP_OUT', 'warehouse:read_write'),
    ('SALES_NEED_CREATE', 'sales:read_write'),
    ('SUPPLIER_EVALUATE', 'procurement:read_write,finance:read_write'),
    ('TRANSFER_APPROVE', 'inventory:write'),
    ('TRANSFER_CREATE', 'inventory:write'),
    ('WECHAT_CREATE', 'sales:read_write'),
    ('WORK_PROCESS_TASK_ASSIGN', 'production:read_write'),
    ('WORK_PROCESS_TASK_COMPLETE', 'production:read_write'),
    ('WORK_PROCESS_TASK_SPAWN', 'production:read_write'),
    ('WORK_PROCESS_TASK_START', 'production:read_write'),
    -- 已有迁移登记过的 (V20261101_50), 同值重申, 只补空不覆盖
    ('CONVERSION_RATE_UPDATE', 'production:read_write'),
    ('FACTORY_NOTIFICATION_CONFIG', 'system:read_write'),
    ('PERIOD_REOPEN', 'finance:read_write'),
    ('RULE_CONFIG', 'system:read_write'),
    ('SCHEDULING_SET_AUTO', 'system:read_write'),
    ('SCHEDULING_SET_DISABLED', 'system:read_write'),
    ('SCHEDULING_SET_MANUAL', 'system:read_write'),
    ('WORK_PROCESS_CONFIG_UPDATE', 'production:read_write')
) AS v(code, perm) ON c.intent_code = v.code
WHERE c.deleted_at IS NULL
  AND (c.required_permission IS NULL OR btrim(c.required_permission) = '')
ON CONFLICT (id) DO NOTHING;

UPDATE ai_intent_configs c
SET required_permission = b.new_required_permission,
    updated_at = NOW()
FROM ai_intent_required_permission_bak_20261118 b
WHERE c.id = b.id
  AND c.deleted_at IS NULL
  AND (c.required_permission IS NULL OR btrim(c.required_permission) = '');
