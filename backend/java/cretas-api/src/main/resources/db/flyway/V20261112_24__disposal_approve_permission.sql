-- 工厂纪律 B8 (Steve 2026-11-10): 报废审批按权限, 不按岗位名写死。
-- 配置放进现有的 factory_thresholds (工厂行 > 全局 '*' 行), 只插全局默认行; 分类沿用 INVENTORY。
-- 默认 = 改动前写死的名单: 财务经理 (厂长恒有), 上线后谁能批和以前一样。
INSERT INTO factory_thresholds
  (factory_id, threshold_key, category, value_type, threshold_value, default_value, description)
VALUES
  ('*', 'inventory.permission.disposal_approve', 'INVENTORY', 'STRING', 'finance_manager', 'finance_manager',
   '报废审批(通过/驳回)的岗位, 逗号分隔的英文岗位码; 厂长恒有权限; 留空=只有厂长')
ON CONFLICT (factory_id, threshold_key) WHERE deleted_at IS NULL DO NOTHING;
