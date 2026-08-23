-- 销售主管收敛到「只管销售」(2026-08-21 owner 实测拍板)
--
-- owner 用 f006_sales_mgr 登录后, 首页「可访问模块」显示 4 个:
--   生产管理(只读) / 仓储管理(只读) / 销售管理(读写) / 财务管理(只读)
-- 原话:「对于销售来说, 他应该只有个人OA跟销售管理, 其他的应该没有任何信息」
--   (工作台保留)。
--
-- ⇒ 砍掉他点名的那三个只读: production / warehouse / finance
--
-- ⛔ **不动 restaurant / restaurantMarketing** —— 它们在这个角色上是 rw,
--    但那是餐饮体系, owner 明确划为禁区(「餐饮那边是另外一个体系」)。
-- ⛔ 也不动 analytics / report / rd / dashboard:
--    dashboard 是工作台(要保留); 另外三个 owner 没点名, ⛔ 不替他决定。
--
-- ⚠️ 判据: 权威是**这张表**, 不是 Java 的 PERMISSION_MATRIX ——
--    后者是本表的镜像/fallback(实测 operator 两边一致)。
--    只改 Java 的话 prod 一点都不会变(V20261030_07 就是因为这个才补的)。

UPDATE platform_role_permissions SET permission_level = '-', updated_at = NOW()
  WHERE role_code = 'sales_manager' AND module_code = 'production' AND deleted_at IS NULL;

UPDATE platform_role_permissions SET permission_level = '-', updated_at = NOW()
  WHERE role_code = 'sales_manager' AND module_code = 'warehouse' AND deleted_at IS NULL;

UPDATE platform_role_permissions SET permission_level = '-', updated_at = NOW()
  WHERE role_code = 'sales_manager' AND module_code = 'finance' AND deleted_at IS NULL;
