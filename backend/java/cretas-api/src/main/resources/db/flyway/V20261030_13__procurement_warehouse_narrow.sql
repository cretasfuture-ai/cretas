-- 采购主管 / 仓储主管 收窄菜单 (2026-08-22, owner 看侧边栏后逐个点名)
--
-- 【owner 原话】
--   采购: 「采购才能看到财务管理啊。还能看到生产管理，还能看到仓储管理啊，这不对啊。」
--   仓储: 「仓储这边也是能看到这些管理。」(指 采购/销售/生产/智能调度)
--
-- 【采购主管: 砍 finance / production / warehouse / inventory】
--   ⚠️ 「仓储管理」这一组由 **inventory 和 warehouse 两个模块**共同撑起
--      (menuConfig 该组内两种 module 都有) ⇒ 只砍一个组还在, 必须两个都砍。
--   剩下: dashboard / procurement / report
--
--   🔴 **砍之前先修了一处端点声明**(见 PriceListController):
--      「价格表管理」页挂在采购管理组下、管的就是采购价目表, 而它的写方法只认
--      sales|finance ⇒ 采购主管**能用纯粹因为他碰巧有 finance**。
--      直接砍 finance 会当场造出 2 处「看得见点不开」(创建/删除价目表)。
--      ⇒ 先给那两个写方法加 procurement:read_write, 再砍。
--      ▎砍权限的反面是「页面还在、权限没了」—— 那是另一种漂移, 不是收窄的胜利。
--
-- 【仓储主管: 砍 procurement / sales / production / scheduling】
--   剩下: dashboard / inventory / report / warehouse
--   ⚠️ 预演结果: 新漂移 **0 处** —— 入库/出库/调拨/盘点都不依赖被砍的那四个。
--
-- 【⛔ 两个角色都保留 report】owner 没点名, ⛔ 不替他决定。
-- ⛔ 不动 dispatcher / restaurant_* 的任何行。
-- ⚠️ 权威是这张表, 不是 Java 的 PERMISSION_MATRIX(镜像/fallback)。
--
-- 【部署前预演读数】
--   procurement_manager: 修完端点后新漂移 0 处 (修之前是 2 处)
--   warehouse_manager  : 新漂移 0 处

-- ---- 采购主管 ----
UPDATE platform_role_permissions SET permission_level = '-', updated_at = NOW()
  WHERE role_code = 'procurement_manager' AND module_code = 'finance' AND deleted_at IS NULL;
UPDATE platform_role_permissions SET permission_level = '-', updated_at = NOW()
  WHERE role_code = 'procurement_manager' AND module_code = 'production' AND deleted_at IS NULL;
UPDATE platform_role_permissions SET permission_level = '-', updated_at = NOW()
  WHERE role_code = 'procurement_manager' AND module_code = 'warehouse' AND deleted_at IS NULL;
UPDATE platform_role_permissions SET permission_level = '-', updated_at = NOW()
  WHERE role_code = 'procurement_manager' AND module_code = 'inventory' AND deleted_at IS NULL;

-- ---- 仓储主管 ----
UPDATE platform_role_permissions SET permission_level = '-', updated_at = NOW()
  WHERE role_code = 'warehouse_manager' AND module_code = 'procurement' AND deleted_at IS NULL;
UPDATE platform_role_permissions SET permission_level = '-', updated_at = NOW()
  WHERE role_code = 'warehouse_manager' AND module_code = 'sales' AND deleted_at IS NULL;
UPDATE platform_role_permissions SET permission_level = '-', updated_at = NOW()
  WHERE role_code = 'warehouse_manager' AND module_code = 'production' AND deleted_at IS NULL;
UPDATE platform_role_permissions SET permission_level = '-', updated_at = NOW()
  WHERE role_code = 'warehouse_manager' AND module_code = 'scheduling' AND deleted_at IS NULL;
