-- 财务主管收窄到「只看得见财务自己」(2026-08-22, owner 拍板)
--
-- 【owner 原话】
-- 「理论上是全部要砍掉的，但是……销售的话需要看到销售单的详情，采购的话需要看的是采购单的详情。
--   ……要独立出来……在导航栏里面其实只能看到财务自己本身的内容，但是我们又要让财务看到
--   这些订单详情的内容，但是又不能让他直接跨模块去看。」
--
-- ▎这句话点出的是一个**结构性区分**:
--   「菜单里看得见」「路由进得去」「接口读得到」现在共用同一个开关, 要拆开。
--
-- 【砍】analytics / production / rd / sales / procurement
--   剩 dashboard / finance / report (+ restaurant / restaurantFinance 不动)
--
-- 【⚠️ 实测澄清: 砍这两个不会让财务失去看订单的能力, 因为他本来就没有】
--   全量搜过 views/finance/**: **一处跨模块跳转都没有**(没有 router.push('/sales/orders/..'),
--   没有 router-link)。订单号在财务页里是**纯文本 / 下拉选项**。
--   ⇒ 财务此前有 sales/procurement 模块, 效果只是**侧边栏铺开了那两整组**,
--     并没有带来「看订单详情」的能力。
--   ⇒ 那个能力要**新建** —— 见同批的财务页内只读抽屉(⛔ 不新增路由, 物理上进不了导航)。
--
-- 【⛔ 砍之前逐个核过财务的本职端点够不够得着】
--   PUT /payment-requests/finance-approve  -> 要 finance  ✅
--   PUT /payment-requests/mark-paid        -> 要 finance  ✅
--   GET /payment-requests, /approved       -> 含 finance  ✅
--   GET /sales/orders                      -> 含 finance  ✅ (抽屉靠它)
--   GET /purchase/orders                   -> 不拦        ✅ (抽屉靠它)
--   GET /inventory/ledger(+export)         -> 含 finance  ✅
--   🔴 **够不着的 3 个**: POST /payment-requests、POST /payment-requests/sales、
--      PUT /payment-requests/submit —— 那是**发起**动作,
--      页面在 views/procurement/ 和 views/sales/ 下, 由采购/销售自己发起,
--      ⛔ 本来就不是财务的活。⇒ 不构成损失, 显式登记在此。
--
-- 【预演】漂移扫描器: 砍完 finance_manager 新增漂移 **0 处**。
-- ⛔ 不动 restaurant / restaurantFinance —— owner 明确划的禁区。

UPDATE platform_role_permissions SET permission_level = '-', updated_at = NOW()
  WHERE role_code = 'finance_manager' AND module_code = 'analytics' AND deleted_at IS NULL;
UPDATE platform_role_permissions SET permission_level = '-', updated_at = NOW()
  WHERE role_code = 'finance_manager' AND module_code = 'production' AND deleted_at IS NULL;
UPDATE platform_role_permissions SET permission_level = '-', updated_at = NOW()
  WHERE role_code = 'finance_manager' AND module_code = 'rd' AND deleted_at IS NULL;
UPDATE platform_role_permissions SET permission_level = '-', updated_at = NOW()
  WHERE role_code = 'finance_manager' AND module_code = 'sales' AND deleted_at IS NULL;
UPDATE platform_role_permissions SET permission_level = '-', updated_at = NOW()
  WHERE role_code = 'finance_manager' AND module_code = 'procurement' AND deleted_at IS NULL;
