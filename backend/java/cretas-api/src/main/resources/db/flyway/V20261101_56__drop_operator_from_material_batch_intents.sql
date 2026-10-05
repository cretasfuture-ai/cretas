-- 2026-10-06 owner 裁定: 「原料查询 / 领用原料」两个意图的名单里去掉操作员。
--
-- 原因: 意图名单列了 operator, 但它在 App 里没有仓库权限, 工具层本来就拒
--   MATERIAL_BATCH_QUERY → MaterialBatchQueryTool 要 warehouse/inventory 读
--   MATERIAL_BATCH_USE   → MaterialBatchUseTool 名单不含 operator
-- 意图说能、工具说不能 ⇒ 用户看到的是「识别成功了却执行被拒」。名单收成与工具一致。
-- yield_operator 是 V20261101_55 照 operator 补进来的, 一并去掉 (与 operator 同权的原则不变)。
-- 只改平台级行; 不动其它角色。

UPDATE ai_intent_configs
   SET required_roles = required_roles - 'operator' - 'yield_operator',
       updated_at = NOW()
 WHERE factory_id IS NULL
   AND deleted_at IS NULL
   AND jsonb_typeof(required_roles) = 'array'
   AND intent_code IN ('MATERIAL_BATCH_QUERY', 'MATERIAL_BATCH_USE')
   AND (required_roles ? 'operator' OR required_roles ? 'yield_operator');
