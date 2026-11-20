-- V20261111_94: 插单是否一律走审批 做成工厂开关 (1551-11, Steve 2026-11-11 拍板, 默认关 = 原行为)
--
-- 配置放进现有的 factory_thresholds (工厂行 > 全局 '*' 行 > 代码兜底), 只插全局默认行;
-- 工厂在「系统设置 → 业务阈值配置 → 生产」里看和改。
--
--   production.urgent_insert.always_approval   0   0 = 只有预览判定需要审批 (影响高/严重) 的插单走强制插单审批, 其余直接排入 (原行为);
--                                                   1 = 本厂有启用的强制插单审批画布时, 所有插单都先进审批, 批准后才排入。

INSERT INTO factory_thresholds
  (factory_id, threshold_key, category, value_type, threshold_value, default_value,
   description, unit, min_value, max_value)
VALUES
  ('*', 'production.urgent_insert.always_approval', 'PRODUCTION', 'INTEGER', '0', '0',
   '插单是否一律走审批: 0 = 只有影响高/严重、预览提示需要审批的插单才走审批, 其余确认后直接排入; 1 = 本厂启用了强制插单审批流程时, 所有插单(包括影响小的)都要先审批, 批准后才排入。没有启用审批流程时设成 1 也不生效; 审批流程里如果配了「影响小就直接通过」的条件分支, 那类插单仍会直接排入。',
   NULL, '0', '1')
ON CONFLICT (factory_id, threshold_key) WHERE deleted_at IS NULL DO NOTHING;
