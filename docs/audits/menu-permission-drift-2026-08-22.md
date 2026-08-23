# 菜单/端点权限漂移清单

**生成**: `python scripts/check-menu-permission-drift.py` · 2026-08-22
**当前**: 70 个组合 → **28 处**独立漂移点, 覆盖 16 个角色

## 进度 —— 前三轮降的**全是我自己的误报**

| 轮次 | 处数 | 修了什么 |
|---|---|---|
| 初版 | 326 | — |
| 修尺子 ① | 190 | 端点级解析 / 精确匹配 / **剥 Java 注释**(把「已移除」读成「还在」) |
| 修 `/rd/*` | 110 | 唯一一次**真修复**: 6 处 module 从 `production` 改成 `rd` |
| 修尺子 ② | **70** | **按 `(HTTP 方法, 路径)` 建键** + 裸 `@GetMapping` 也认 |

### 🔴 第二次修尺子改掉的是什么

`/customers` 上 **POST 要 sales|finance**, **GET 根本不拦**
(`CustomerController` 头部白纸黑字: *reads stay open to any authenticated user*)。
第一版按**路径**建键、同一路径多方法**取并集** ⇒ GET 被标成要 sales
⇒ 报出「19 个角色打不开客户列表」, **全是假的**。

⚠️ 而当时那行注释写着「取并集偏向**漏报**而不是误报」—— **推理是反的**:
取并集是把要求**变严**, 只会误报。我照着一个反的推理放过了它。

▎**形态 A**: 我想知道「页面调的**那个方法**要什么」, 实际量的是「这个路径上**任意**方法要什么」。

同轮第三个缺陷: 正则要求 `@GetMapping(` **带括号**, 而本仓裸写 `@GetMapping` 很常见
⇒ 那些端点整批不可见(它们本可继承**类级**权限) —— 这一个是**漏报**。

⚠️ **覆盖面缺口**: 另有 27 处调用点认不出 HTTP 动词(配置对象里的 `url:`)被跳过。
⛔ 这不是零, 是没量到。
---

| # | 角色数 | 方法 | 端点 | 后端要 | 页面 | 哪些角色 |
|---|---|---|---|---|---|---|
| 1 | **9** | `GET` | `/processing/material-consumptions/batch/` | procurement | `production/BomAchievementView.vue` | group_leader, operator, production_clerk, quality_controller, quality_inspector, quality_manager, team_leader, workshop_supervisor, yield_operator |
| 2 | **9** | `GET` | `/processing/material-consumptions/batch/` | procurement | `production/batches/detail.vue` | group_leader, operator, production_clerk, quality_controller, quality_inspector, quality_manager, team_leader, workshop_supervisor, yield_operator |
| 3 | **4** | `GET` | `/finance/invoices/by-sales-order/` | finance | `sales/orders/detail.vue` | operations_coordinator, production_manager, sales_manager, warehouse_manager |
| 4 | **4** | `GET` | `/finance/payments/by-sales-order/` | finance | `sales/orders/detail.vue` | operations_coordinator, production_manager, sales_manager, warehouse_manager |
| 5 | **4** | `POST` | `/finance/invoices/request-from-order` | finance | `sales/orders/detail.vue` | operations_coordinator, production_manager, sales_manager, warehouse_manager |
| 6 | **4** | `POST` | `/finance/payments/record` | finance | `sales/orders/detail.vue` | operations_coordinator, production_manager, sales_manager, warehouse_manager |
| 7 | **4** | `POST` | `/finance/receivable` | finance | `sales/orders/list.vue` | operations_coordinator, production_manager, sales_manager, warehouse_manager |
| 8 | **4** | `POST` | `/finance/receivable/payment` | finance | `sales/orders/list.vue` | operations_coordinator, production_manager, sales_manager, warehouse_manager |
| 9 | **3** | `POST` | `/alerts/detect` | equipment | `analytics/AlertDashboard.vue` | finance_manager, operations_coordinator, production_clerk |
| 10 | **3** | `POST` | `/disposal-records` | warehouse | `quality/disposals/list.vue` | group_leader, quality_inspector, quality_manager |
| 11 | **3** | `PUT` | `/disposal-records/` | warehouse | `quality/disposals/list.vue` | group_leader, quality_inspector, quality_manager |
| 12 | **2** | `GET` | `/inventory/ledger` | inventory | `finance/inventory-ledger/index.vue` | cashier, finance_manager |
| 13 | **2** | `GET` | `/inventory/ledger/export` | inventory | `finance/inventory-ledger/index.vue` | cashier, finance_manager |
| 14 | **1** | `GET` | `/reports/dashboard/equipment` | finance/procurement | `analytics/index.vue` | production_clerk |
| 15 | **1** | `GET` | `/reports/dashboard/overview` | finance/procurement | `analytics/index.vue` | production_clerk |
| 16 | **1** | `GET` | `/reports/dashboard/production` | finance/procurement | `analytics/index.vue` | production_clerk |
| 17 | **1** | `GET` | `/reports/dashboard/quality` | finance/procurement | `analytics/index.vue` | production_clerk |
| 18 | **1** | `GET` | `/reports/dashboard/trends` | finance/procurement | `analytics/index.vue` | production_clerk |
| 19 | **1** | `GET` | `/reports/production-by-product` | finance/procurement | `analytics/production-report/index.vue` | production_clerk |
| 20 | **1** | `GET` | `/price-lists/lookup` | procurement | `sales/orders/list.vue` | sales_manager |
| 21 | **1** | `GET` | `/reports/sales/profit-detail/` | finance/procurement | `sales/orders/profit-detail.vue` | sales_manager |
| 22 | **1** | `DELETE` | `/material-packaging/by-material/` | production | `warehouse/material-types/list.vue` | warehouse_worker |
| 23 | **1** | `DELETE` | `/raw-material-types/` | production | `warehouse/material-types/list.vue` | warehouse_worker |
| 24 | **1** | `POST` | `/raw-material-types` | production | `warehouse/material-types/list.vue` | warehouse_worker |
| 25 | **1** | `PUT` | `/material-packaging/by-material/` | production | `warehouse/material-types/list.vue` | warehouse_worker |
| 26 | **1** | `PUT` | `/raw-material-types/` | production | `warehouse/material-types/list.vue` | warehouse_worker |
| 27 | **1** | `POST` | `/labels/material-batch/` | production | `warehouse/materials/list.vue` | warehouse_worker |
| 28 | **1** | `GET` | `/semi-finished/inventory` | production | `warehouse/semi-finished/list.vue` | warehouse_worker |
