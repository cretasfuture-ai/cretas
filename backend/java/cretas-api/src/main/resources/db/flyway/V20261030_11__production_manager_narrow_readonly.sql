-- 生产经理再收一轮只读模块 (2026-08-22, owner 看侧边栏后拍板)
--
-- 【为什么还有这一轮】
-- V20261030_07 只砍了 owner 当时点名的 hr/finance/system, 并把 sales/analytics/rd
-- 从 rw 降到 r。那份迁移里我写了一行注释:
--   「warehouse/quality/procurement/equipment/inventory/report (r) 已经是 owner 要的值」
-- 🔴 **owner 从没说过那句话**。那 6 个是 dispatcher 的种子值, 我只是没动它们,
--    却把「我没改」写成了「owner 要的」。
-- ⇒ owner 2026-08-22 打开 f006_production_mgr 的首页, 看到 11 个顶级组,
--   第一句就是「这个权限不对吧」—— 看的正是那几个。
-- ▎把「我没改」记成「已确认」, 下一轮就会有人拿它当依据。
--
-- 【本轮砍掉的三个 (owner 逐项选的)】
--   analytics  —— owner 已定「数据分析应该是最高权限, 管理员或者老板」
--   report     —— 与 analytics 同族; ⚠️ **两个一起砍**「数据与分析」这一组才会真消失,
--                 只砍一个的话另一个照样把菜单撑出来
--   procurement—— 采购单是采购的事; owner 接受「生产经理不知道原料何时到货, 去问采购」
--
-- 【刻意保留 (⛔ 登记, 不是漏了)】
--   rd (r)          —— owner 选择保留: 试产时要能自己查配方/样品
--   warehouse (r) / inventory (r) —— 要知道原料够不够、成品入库
--   quality (r)     —— 质检结果影响生产
--   equipment (r)   —— 设备状态影响排产
--   sales (r)       —— 按订单排产
--   production / scheduling / work_report / dashboard (rw) —— 本职, 不动
--
-- ⛔ 不动 dispatcher 的任何行 —— 那是另一个在用的角色(改它会打回 Apr 18 2026 bug #45)。
-- ⛔ 不动任何 restaurant_* 角色的行。
-- ⚠️ 权威是**这张表**, 不是 Java 的 PERMISSION_MATRIX(后者是镜像/fallback)。

UPDATE platform_role_permissions SET permission_level = '-', updated_at = NOW()
  WHERE role_code = 'production_manager' AND module_code = 'analytics' AND deleted_at IS NULL;

UPDATE platform_role_permissions SET permission_level = '-', updated_at = NOW()
  WHERE role_code = 'production_manager' AND module_code = 'report' AND deleted_at IS NULL;

UPDATE platform_role_permissions SET permission_level = '-', updated_at = NOW()
  WHERE role_code = 'production_manager' AND module_code = 'procurement' AND deleted_at IS NULL;
