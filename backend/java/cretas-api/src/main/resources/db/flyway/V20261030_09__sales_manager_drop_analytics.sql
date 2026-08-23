-- 销售主管去掉「数据与分析」(2026-08-21 owner 实测拍板, 第二轮)
--
-- owner 原话:「数据与分析这一块给它隐藏吧, 他们用不上。销售的话...也没有权限,
--   不应该给他们看。数据分析应该是最高权限, 比如说管理员或者老板这样子阶级的。」
--
-- ⛔ **只动 sales_manager 一个角色。** analytics 当前有 13 个角色持有
--    (含 viewer:r / department_admin:rw / production_clerk:r 等), 按 owner 的说法
--    那些多半也该收 —— 但那等于替他重定 27 个角色的矩阵, 他已经明确说那是单独一轮。
--    ⇒ 这里只执行他点名的这一个, 其余登记不动。
--
-- ⚠️ 权威是这张表, Java 的 PERMISSION_MATRIX 只是镜像(同步改了)。

UPDATE platform_role_permissions SET permission_level = '-', updated_at = NOW()
  WHERE role_code = 'sales_manager' AND module_code = 'analytics' AND deleted_at IS NULL;
