-- V20261111_62: 客供料收货能不能超收 做成工厂开关 (A15, Steve 2026-11-11 拍板, 默认关 = 原行为)
--
-- 配置放进现有的 factory_thresholds (工厂行 > 全局 '*' 行 > 代码兜底), 只插全局默认行;
-- 工厂在「系统设置 → 业务阈值配置 → 库存」里看和改。
--
--   inventory.customer_supplied.allow_over_receive   0   0 = 客供料实收不能超过剩余待收, 多 1 克也拦 (原行为);
--                                                       1 = 与采购收货同一个超收上限 (工厂库存设置里的采购超收上限; 没配 = 不限制)。
--
-- 开关开时允许累计实收超过应收; 同批的 V20261111_61 已放开表约束的上界 (保留不能为负)。

INSERT INTO factory_thresholds
  (factory_id, threshold_key, category, value_type, threshold_value, default_value,
   description, unit, min_value, max_value)
VALUES
  ('*', 'inventory.customer_supplied.allow_over_receive', 'INVENTORY', 'INTEGER', '0', '0',
   '客供料收货能不能超收: 0 = 客户来料实收不能超过任务剩余待收, 多收一点都会被拦 (默认); 1 = 与采购收货用同一个超收上限 (工厂库存设置里的采购超收比例, 没配就是不限制), 在上限内多收可以入库。',
   NULL, '0', '1')
ON CONFLICT (factory_id, threshold_key) WHERE deleted_at IS NULL DO NOTHING;
