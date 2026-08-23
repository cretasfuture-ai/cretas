-- V20261030_07: 生产经理(production_manager)从调度(dispatcher)拆出独立权限对象 +
--               新增生产文员(production_clerk)角色。
--
-- 背景 (owner 实测, 2026-08-21): production_manager 长期是 dispatcher 的别名
-- (PermissionServiceImpl.PERMISSION_MATRIX.put(production_manager, dispatcherPerms)),
-- 而 dispatcher 的设计是"生产读写 + 全模块只读"(含 hr/finance/system)。
-- 这份 L1 迁移 (V20260422_01__platform_role_permissions.sql) 里 production_manager 的
-- 种子行同样是 dispatcher 的逐字复制, 是 f006_production_mgr 侧边栏看起来跟 admin
-- 差不多的**根因**(L1 优先于 PermissionServiceImpl 里的硬编码 fallback)。
--
-- 本迁移: (a) 收紧 production_manager 的 L1 行 —— 砍掉 hr/finance/system,
-- sales/analytics/rd 从 rw 降级 r; (b) 为新角色 production_clerk 插入 L1 行。
-- ⛔ 不动 dispatcher 的任何行 —— 那是另一个在用的角色, 改它会打回 Apr 18 2026 bug #45。
-- ⛔ 不动任何 restaurant_* 角色的行。

-- ---- production_manager: 收紧 (UPDATE, 行已由 V20260422_01 插入) ----
UPDATE platform_role_permissions SET permission_level = 'r', updated_at = NOW()
  WHERE role_code = 'production_manager' AND module_code = 'sales' AND deleted_at IS NULL;
UPDATE platform_role_permissions SET permission_level = '-', updated_at = NOW()
  WHERE role_code = 'production_manager' AND module_code = 'hr' AND deleted_at IS NULL;
UPDATE platform_role_permissions SET permission_level = '-', updated_at = NOW()
  WHERE role_code = 'production_manager' AND module_code = 'finance' AND deleted_at IS NULL;
UPDATE platform_role_permissions SET permission_level = '-', updated_at = NOW()
  WHERE role_code = 'production_manager' AND module_code = 'system' AND deleted_at IS NULL;
UPDATE platform_role_permissions SET permission_level = 'r', updated_at = NOW()
  WHERE role_code = 'production_manager' AND module_code = 'analytics' AND deleted_at IS NULL;
UPDATE platform_role_permissions SET permission_level = 'r', updated_at = NOW()
  WHERE role_code = 'production_manager' AND module_code = 'rd' AND deleted_at IS NULL;
-- dashboard/production/scheduling (rw) 与 warehouse/quality/procurement/equipment/inventory/report (r)
-- 已经是 owner 要的值, 不需要改。restaurant 行(-) 不动。

-- ---- production_clerk: 新增 (INSERT, 角色首次出现) ----
INSERT INTO platform_role_permissions (role_code, module_code, permission_level) VALUES ('production_clerk', 'dashboard', 'r') ON CONFLICT (role_code, module_code) DO NOTHING;
INSERT INTO platform_role_permissions (role_code, module_code, permission_level) VALUES ('production_clerk', 'production', 'rw') ON CONFLICT (role_code, module_code) DO NOTHING;
INSERT INTO platform_role_permissions (role_code, module_code, permission_level) VALUES ('production_clerk', 'warehouse', 'r') ON CONFLICT (role_code, module_code) DO NOTHING;
INSERT INTO platform_role_permissions (role_code, module_code, permission_level) VALUES ('production_clerk', 'quality', '-') ON CONFLICT (role_code, module_code) DO NOTHING;
INSERT INTO platform_role_permissions (role_code, module_code, permission_level) VALUES ('production_clerk', 'procurement', '-') ON CONFLICT (role_code, module_code) DO NOTHING;
INSERT INTO platform_role_permissions (role_code, module_code, permission_level) VALUES ('production_clerk', 'sales', '-') ON CONFLICT (role_code, module_code) DO NOTHING;
INSERT INTO platform_role_permissions (role_code, module_code, permission_level) VALUES ('production_clerk', 'hr', '-') ON CONFLICT (role_code, module_code) DO NOTHING;
INSERT INTO platform_role_permissions (role_code, module_code, permission_level) VALUES ('production_clerk', 'equipment', '-') ON CONFLICT (role_code, module_code) DO NOTHING;
INSERT INTO platform_role_permissions (role_code, module_code, permission_level) VALUES ('production_clerk', 'finance', '-') ON CONFLICT (role_code, module_code) DO NOTHING;
INSERT INTO platform_role_permissions (role_code, module_code, permission_level) VALUES ('production_clerk', 'system', '-') ON CONFLICT (role_code, module_code) DO NOTHING;
INSERT INTO platform_role_permissions (role_code, module_code, permission_level) VALUES ('production_clerk', 'analytics', 'r') ON CONFLICT (role_code, module_code) DO NOTHING;
INSERT INTO platform_role_permissions (role_code, module_code, permission_level) VALUES ('production_clerk', 'scheduling', '-') ON CONFLICT (role_code, module_code) DO NOTHING;
INSERT INTO platform_role_permissions (role_code, module_code, permission_level) VALUES ('production_clerk', 'work_report', 'rw') ON CONFLICT (role_code, module_code) DO NOTHING;
INSERT INTO platform_role_permissions (role_code, module_code, permission_level) VALUES ('production_clerk', 'inventory', 'r') ON CONFLICT (role_code, module_code) DO NOTHING;
INSERT INTO platform_role_permissions (role_code, module_code, permission_level) VALUES ('production_clerk', 'report', 'r') ON CONFLICT (role_code, module_code) DO NOTHING;
INSERT INTO platform_role_permissions (role_code, module_code, permission_level) VALUES ('production_clerk', 'rd', '-') ON CONFLICT (role_code, module_code) DO NOTHING;
INSERT INTO platform_role_permissions (role_code, module_code, permission_level) VALUES ('production_clerk', 'restaurant', '-') ON CONFLICT (role_code, module_code) DO NOTHING;
INSERT INTO platform_role_permissions (role_code, module_code, permission_level) VALUES ('production_clerk', 'operations', '-') ON CONFLICT (role_code, module_code) DO NOTHING;
