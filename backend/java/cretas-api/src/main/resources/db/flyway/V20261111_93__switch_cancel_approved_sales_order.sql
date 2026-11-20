-- V20261111_93: 销售单财务批准后取消 改为工厂开关 (Steve 2026-11-11 拍板做成开关, 默认关 = 原行为)
--
-- 配置放进现有的 factory_thresholds (工厂行 > 全局 '*' 行 > 代码兜底), ⛔ 不新建注册表; 只插全局默认行。
--
--   sales.order.cancel_after_approval        0   0 = 财务批准后只能驳回 / 退款 (原行为); 1 = 允许有权限的人带原因取消,
--                                                 连带取消自动生成的生产计划和未开工排程 (已开工 / 已发货 / 已有收款的拦住)
--   sales.permission.cancel_approved_order   ''  谁能取消已批准的销售单: 岗位码逗号分隔; 空 = 只有厂长
--
-- 「谁能取消」按权限码 sales_order:cancel_approved 判 (PermissionServiceImpl 可配置权限), 不按岗位名写死。

INSERT INTO factory_thresholds
  (factory_id, threshold_key, category, value_type, threshold_value, default_value,
   description, unit, min_value, max_value)
VALUES
  ('*', 'sales.order.cancel_after_approval', 'SALES', 'INTEGER', '0', '0',
   '销售订单财务批准后能否取消: 0 = 不能, 只能驳回/退款; 1 = 有「取消已批准销售单」权限的人可以带原因取消, 并一并取消它自动生成的生产计划和未开工的排程。已开工有报工、已完成、已有发货单或收款/应收的订单仍然不能这样取消。',
   NULL, '0', '1'),
  ('*', 'sales.permission.cancel_approved_order', 'SALES', 'STRING', '', '',
   '谁能取消财务已批准的销售订单 (仅在 sales.order.cancel_after_approval = 1 时用得上): 英文岗位码, 逗号分隔, 如 sales_manager,finance_manager; 留空 = 只有厂长。',
   NULL, NULL, NULL)
ON CONFLICT (factory_id, threshold_key) WHERE deleted_at IS NULL DO NOTHING;
