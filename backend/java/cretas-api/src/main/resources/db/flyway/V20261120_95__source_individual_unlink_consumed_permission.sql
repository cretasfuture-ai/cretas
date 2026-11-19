-- #1968 M-2 (organizer 裁定口径 a, 2026-11-19): 解除「已有消耗」原料批次的来源个体挂载, 按权限而不是写死岗位。
-- 权限码 source_individual:unlink_consumed (PermissionServiceImpl 可配置权限); 配置放进现有的 factory_thresholds
-- (工厂行 > 全局 '*' 行 > 代码兜底), 只插全局默认行; 分类沿用 INVENTORY。
-- 默认 = 质量经理 (厂长恒有)。未消耗的批次仍按原端点权限 (warehouse/inventory 写) 解除, 不受本行影响。
INSERT INTO factory_thresholds
  (factory_id, threshold_key, category, value_type, threshold_value, default_value, description)
VALUES
  ('*', 'inventory.permission.source_individual_unlink_consumed', 'INVENTORY', 'STRING',
   'quality_manager', 'quality_manager',
   '解除已有消耗(已领用/已耗尽/生产占用/已报废)原料批次的来源个体挂载的岗位, 逗号分隔的英文岗位码; 厂长恒有权限; 留空=只有厂长')
ON CONFLICT (factory_id, threshold_key) WHERE deleted_at IS NULL DO NOTHING;
