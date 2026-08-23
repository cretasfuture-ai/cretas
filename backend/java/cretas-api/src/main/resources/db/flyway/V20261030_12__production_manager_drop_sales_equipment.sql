-- 生产经理再砍 sales / equipment (2026-08-22 第二轮, owner 看侧边栏后逐项定)
--
-- owner 原话:「质量管理留着, 销售管理跟仓储管理应该都不是他做的呀。设备管理也不是他负责的。」
-- 随后追问「仓储」时 owner 选择: **保留, 保持只读** ——
--   生产经理要能自己查原料够不够、成品入没入库, 但改不了也批不了(现状已是 r, 本迁移不动它)。
--
-- 【砍掉】
--   sales      —— 销售订单不是他做的。⚠️ **代价已知**: 他不能再自己看「按哪张订单排产」,
--                 要从生产计划那侧的关联信息看。owner 接受。
--   equipment  —— 设备不归他管。
--
-- 【保留 —— ⛔ 登记, 不是漏了】
--   quality (r)                 owner 明确「质量管理留着」
--   warehouse (r) inventory (r) owner 明确选择「保留, 保持只读」
--   rd (r)                      上一轮 owner 选择保留(试产查配方)
--   production/scheduling/work_report/dashboard (rw)  本职
--
-- ⛔ 不动 dispatcher / restaurant_* 的任何行。
-- ⚠️ 权威是这张表, 不是 Java 的 PERMISSION_MATRIX(镜像/fallback)。
--
-- 【⚠️ 部署前已用漂移扫描器预演过】
-- 砍权限的反面是「页面还在、权限没了」= 另一种「看得见点不开」。
-- 把砍完后的权限集合喂进扫描器: production_manager 新增漂移 **0 处**。
-- ⛔ 不预演就部署 = 拿 owner 的下一次点击当测试。

UPDATE platform_role_permissions SET permission_level = '-', updated_at = NOW()
  WHERE role_code = 'production_manager' AND module_code = 'sales' AND deleted_at IS NULL;

UPDATE platform_role_permissions SET permission_level = '-', updated_at = NOW()
  WHERE role_code = 'production_manager' AND module_code = 'equipment' AND deleted_at IS NULL;
