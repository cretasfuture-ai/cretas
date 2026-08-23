-- 生产文员去掉「数据与分析」(2026-08-22, 漂移普查第三批)
--
-- 【为什么】
-- 漂移扫描报出 production_clerk 在 analytics 菜单下点 /reports/dashboard/equipment
-- 和 /reports/production-by-product 必 403 —— 那两个端点要 finance|procurement,
-- 而生产文员两个都没有。
--
-- 修法有两条，选了收菜单这一条:
--   ⛔ 放宽 report 端点 = 把成本/采购口径的报表铺给一线文员
--   ✅ 收菜单 —— 与 owner 已下的判断一致:
--      「数据与分析这一块给它隐藏吧他们用不上……数据分析应该是最高权限,
--        就是比如说管理员或者老板这样子阶级的」(2026-08-21)
--      而生产文员按 owner 的定义是「审核报工数据 + 偶尔加个新产品」。
--
-- 【⛔ 只动 production_clerk 这一个角色】
-- 当前持有 analytics 的还有: department_admin/dispatcher/factory_super_admin/
-- finance_manager/platform_admin (rw) 和 operations_coordinator/production_manager/
-- viewer (r)。它们**没有出现在这批漂移里**(说明它们够得着那些端点),
-- ⛔ 不替 owner 扩大裁撤范围。
--
-- ⚠️ 权威是**这张表**, 不是 Java 的 PERMISSION_MATRIX(后者是镜像/fallback)。
--    只改 Java 的话 prod 一点都不会变 —— V20261030_07 就是因为这个才补的。
--
-- ⚠️ 工作台(dashboard)不受影响, 它是另一个 module_code。

UPDATE platform_role_permissions SET permission_level = '-', updated_at = NOW()
  WHERE role_code = 'production_clerk' AND module_code = 'analytics' AND deleted_at IS NULL;
