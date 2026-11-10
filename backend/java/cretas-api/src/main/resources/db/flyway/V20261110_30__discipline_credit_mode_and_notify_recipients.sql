-- 工厂纪律 A17 / B12 (Steve 2026-11-10): 信用额度检查开关 + 每类通知的接收角色, 都放在 factory_thresholds。
-- 只插全局默认 (factory_id='*'), 不给具体工厂插行 —— 没有 per-factory 行 = 跟随全局默认。
-- 分类沿用现有 CREDIT / PRODUCTION / INVENTORY, 没有新增枚举值。
-- 通知接收人的值是逗号分隔的角色码; 配的角色全都没有人时, 代码层一律改发厂长 (factory_super_admin)。
INSERT INTO factory_thresholds
  (factory_id, threshold_key, category, value_type, threshold_value, default_value, description)
VALUES
  ('*', 'credit.check.mode', 'CREDIT', 'STRING', 'OFF', 'OFF',
   '超信用额度检查: OFF=不检查(默认); WARN=只提示并留痕; BLOCK=拦截, 持信用特批权限者填原因可放行'),
  ('*', 'inventory.permission.material_type_maintain', 'INVENTORY', 'STRING', 'warehouse_manager', 'warehouse_manager',
   '原料类型维护(新建/改/删/别名/合并)的岗位, 逗号分隔; 厂长恒有权限'),
  ('*', 'inventory.permission.wastage_approve', 'INVENTORY', 'STRING', '', '',
   '报损审批(通过/驳回/看待审)的岗位, 逗号分隔; 空=只有厂长'),
  ('*', 'credit.permission.override', 'CREDIT', 'STRING', '', '',
   '信用特批(超信用额度被拦截时放行)的岗位, 逗号分隔; 空=只有厂长'),
  ('*', 'production.notify.schedule_created', 'PRODUCTION', 'STRING',
   'dispatcher,factory_super_admin', 'dispatcher,factory_super_admin',
   '新调度计划创建通知的接收角色(逗号分隔); 无人时发厂长'),
  ('*', 'production.notify.schedule_confirmed', 'PRODUCTION', 'STRING',
   'workshop_supervisor', 'workshop_supervisor',
   '调度计划确认通知的接收角色(逗号分隔); 无人时发厂长'),
  ('*', 'production.notify.schedule_delayed', 'PRODUCTION', 'STRING',
   'dispatcher,factory_super_admin,workshop_supervisor', 'dispatcher,factory_super_admin,workshop_supervisor',
   '调度计划延期预警的接收角色(逗号分隔); 无人时发厂长'),
  ('*', 'production.notify.urgent_insert', 'PRODUCTION', 'STRING',
   'dispatcher,factory_super_admin,workshop_supervisor', 'dispatcher,factory_super_admin,workshop_supervisor',
   '紧急插单通知的接收角色(逗号分隔); 无人时发厂长'),
  ('*', 'production.notify.efficiency_warning', 'PRODUCTION', 'STRING',
   'dispatcher,workshop_supervisor', 'dispatcher,workshop_supervisor',
   '生产进度严重落后预警的接收角色(逗号分隔); 无人时发厂长'),
  ('*', 'production.notify.ai_scheduling', 'PRODUCTION', 'STRING',
   'dispatcher,factory_super_admin', 'dispatcher,factory_super_admin',
   'AI 排产结果通知的接收角色(逗号分隔), 原来是全厂广播; 无人时发厂长'),
  ('*', 'inventory.notify.low_stock', 'INVENTORY', 'STRING',
   'warehouse_manager,procurement_manager', 'warehouse_manager,procurement_manager',
   '低库存报警的接收角色(逗号分隔); 无人时发厂长')
ON CONFLICT (factory_id, threshold_key) WHERE deleted_at IS NULL DO NOTHING;
