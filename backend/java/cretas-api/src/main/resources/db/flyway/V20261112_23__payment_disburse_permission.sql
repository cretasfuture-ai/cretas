-- 工厂纪律 B13 (Steve 2026-11-10): 「我的待办」按权限聚合, 不再只给财务经理 / 出纳。
-- 付款待办按「付款执行」权限出数: 默认 = 改动前的名单 (出纳), 厂长恒有; 工厂可在阈值里改岗位名单。
-- 配置放进现有的 factory_thresholds, 只插全局默认行; 分类沿用 FINANCE。
INSERT INTO factory_thresholds
  (factory_id, threshold_key, category, value_type, threshold_value, default_value, description)
VALUES
  ('*', 'finance.permission.payment_disburse', 'FINANCE', 'STRING', 'cashier', 'cashier',
   '付款执行(出纳点已付款, 决定谁的待办里出付款待办)的岗位, 逗号分隔的英文岗位码; 厂长恒有权限; 留空=只有厂长')
ON CONFLICT (factory_id, threshold_key) WHERE deleted_at IS NULL DO NOTHING;
