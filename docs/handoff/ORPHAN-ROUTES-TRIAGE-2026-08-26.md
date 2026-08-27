# 走不到的路由 —— 逐条待裁定清单

**生成日期**: 2026-08-26 ｜ **共 91 条** ｜ 闸: `web-admin/src/router/__tests__/routeReachability.spec.ts`（棘轮 `ORPHAN_BUDGET = 91`）

## 这份清单是什么

web-admin 有 **91 条路由既不在侧边栏菜单里，也没有任何代码字面量跳转过去** ——
对真实用户等于不存在，只能手打 URL 才进得去。能定位到组件的 76 条合计 **36,895 行 Vue**。

先例是 **#87 仓储空间**、**#96 车间大屏**：都是做完、部署、RBAC 正确、菜单零登记。
#87 的后果是生产上 `factory_feature_config` 里 `WAREHOUSE_LAYOUT` **一行都没有**
—— 不是功能坏了，是没人找得到。

**闸已经拦住新增的**（变异对照：注入一条不可达路由 → 红 92>91；同一条登记进菜单 → 绿 91）。
**存量这 91 条是产品判断**，需要你逐条圈。

## ⚠️ 先看这个：91 条不是同一种东西

| 类型 | 条数 |
|---|---|
| 页面 | 75 |
| 函数重定向 | 4 |
| ? | 3 |
| 重定向 → `/system/product-processes` | 2 |
| 重定向 → `/rd/samples` | 2 |
| 重定向 → `/warehouse/unordered-inbound-applications` | 1 |
| 重定向 → `/ops/agent-ops/eval-sets` | 1 |
| 重定向 → `/production-analytics/production` | 1 |
| 重定向 → `/scheduling/logistics/workbench` | 1 |
| 重定向 → `/transfer/list` | 1 |

**「函数重定向」那一类多半不用你操心** —— 它们是**故意**不进菜单的：
源码注释写着「AI分析报告删除 — redirect 到经营分析 hub（component 保留以防深链，但 menu 隐藏）」，
也就是为了让老书签/深链还能跳到新页面而保留的存根。这类标 `保留` 我就登记进白名单。

**真正要你判的是「页面」那一类。**

## 怎么用

在最后一列「决定」填：

| 填 | 我会做什么 |
|---|---|
| `保留` | 去 `menuConfig.ts` 补菜单入口（或在相关页面加跳转）；重定向存根则登记进白名单 |
| `删除` | 路由 + 页面文件一起删掉 |
| `?` | 我去查后端端点是否存在 + 生产库有没有数据，再回来给结论 |

整份填完给我，我按标注执行并把棘轮预算调低。**不必一次填完** —— 填多少我执行多少。

## ⚠️ 为什么这一步不能由我替你做

git 历史 **2026-08-23 才重建**（"Initial company baseline"，全仓仅 115 个 commit），
所以「最后修改时间」这个信号是死的：91 条里绝大多数都显示成「导入以来没动过」，
**分不清「已废弃」和「稳定不需要改」**。我没有别的客观信号能替代你的产品记忆。

「它调了哪个后端接口」这一列是我能给的最强旁证 —— **有接口 ≈ 后端真做了**。

## 已知盲区

- 本闸看不见**动态拼接**的跳转（`router.push('/hr/' + sub)`）。若某条其实是这么进去的，标 `保留`，我登记白名单。
- 还有 **3 条我没能定位到组件声明**（表里标 `?`）—— 多为父级路由或声明写法特殊，需要时我单独去查。

## 按子系统分布

| 子系统 | 条数 |
|---|---|
| hr | 13 |
| system | 11 |
| analytics | 9 |
| warehouse | 5 |
| finance | 4 |
| sales | 4 |
| smart-bi | 4 |
| production | 3 |
| production-analytics | 3 |
| rd | 3 |
| requisitions | 3 |
| admin | 2 |
| crm | 2 |
| supplier-delivery | 2 |
| transfer | 2 |
| workflow | 2 |
| approval-rules | 1 |
| approval-workflow-editor | 1 |
| audit | 1 |
| cost-attribution | 1 |
| dashboard | 1 |
| data-completeness | 1 |
| exceptions | 1 |
| indicator-center | 1 |
| inquiry-quotes | 1 |
| inventory | 1 |
| invoices | 1 |
| operations | 1 |
| ops | 1 |
| payment-requests | 1 |
| price-anomaly | 1 |
| print-template-editor | 1 |
| returns | 1 |
| scheduling | 1 |
| supplier-reconciliation | 1 |

---

## 逐条清单

| # | 路由 | 页面标题 | 类型 | 组件 | 行数 | 它调的后端接口 | 决定 |
|---|---|---|---|---|---|---|---|
| 1 | `/admin/etl-status` | 餐饮 ETL 状态 | 页面 | `views/restaurant/admin/etl-status.vue` | 373 | `api/restaurant/etl-admin` | |
| 2 | `/admin/name-resolution` | 菜品名称匹配 | 页面 | `views/restaurant/admin/name-resolution.vue` | 386 | `api/restaurant/name-resolution` | |
| 3 | `/analytics/ai-reports` | — | **函数重定向**(故意隐藏菜单) | — | — | — | |
| 4 | `/analytics/dianping` | — | ?(未定位) | — | — | — | |
| 5 | `/analytics/dishes` | 菜品分析 | 页面 | `views/restaurant/analytics/dishes.vue` | 33 | — | |
| 6 | `/analytics/gross-margin` | — | ?(未定位) | — | — | — | |
| 7 | `/analytics/menu` | — | ?(未定位) | — | — | — | |
| 8 | `/analytics/platform` | 平台分析 | 页面 | `views/restaurant/analytics/platform.vue` | 568 | `api/smartbi/common` | |
| 9 | `/analytics/role-kpi` | 经营看板 | 页面 | `views/restaurant/analytics/role-kpi-dashboard.vue` | 264 | `/api/smartbi/gold/store-kpi-dashboard` | |
| 10 | `/analytics/stores` | 门店对比 | 页面 | `views/restaurant/analytics/store-comparison.vue` | 291 | `/api/smartbi/restaurant-ops/store-comparison`, `/api/smartbi/restaurant-ops/store-margin` | |
| 11 | `/analytics/targets` | 目标管理 | 页面 | `views/restaurant/analytics/target-hierarchy.vue` | 788 | `api/smartbi/restaurant-targets` | |
| 12 | `/approval-rules` | 采购审批规则 | 页面 | `views/procurement/approval-rules/list.vue` | 289 | `api/request`, `api/smartbi/common` | |
| 13 | `/approval-workflow-editor` | 审批工作流编辑器 (已迁移到 Canvas) | 页面 | `views/platform/approval-workflow-editor/index.vue` | 2021 | `api/approvalWorkflow` | |
| 14 | `/audit/cell` | 字段血统审计 | 页面 | `views/system/data-fabric/cell-audit.vue` | 333 | `/api/smartbi/provenance/audit` | |
| 15 | `/cost-attribution` | 成本归因 | 页面 | `views/restaurant/cost-attribution/index.vue` | 173 | `api/restaurant/costAttribution` | |
| 16 | `/crm/opportunity` | 商机列表 | 页面 | `views/crm/opportunity/SalesOpportunityList.vue` | 743 | `api/request`, `api/salesOpportunity` | |
| 17 | `/crm/opportunity/kanban` | 商机看板 | 页面 | `views/crm/opportunity/SalesOpportunityKanban.vue` | 580 | `api/salesOpportunity` | |
| 18 | `/dashboard/widgets` | 自定义看板 | 页面 | `views/dashboard/widget-demo.vue` | 60 | — | |
| 19 | `/data-completeness` | 数据完整度 | 页面 | `views/restaurant/data-completeness.vue` | 396 | `api/restaurant/completeness` | |
| 20 | `/exceptions` | 入库异常 | 页面 | `views/procurement/exceptions/list.vue` | 529 | `api/request` | |
| 21 | `/finance/inventory-ledger` | 进销存台账 | 页面 | `views/finance/inventory-ledger/index.vue` | 462 | `api/request` | |
| 22 | `/finance/voucher-export` | 凭证导出 | 页面 | `views/finance/voucher-export/index.vue` | 1049 | `api/request` | |
| 23 | `/finance/voucher-list` | 凭证列表 | 页面 | `views/finance/voucher/list.vue` | 497 | `api/voucher` | |
| 24 | `/finance/voucher-template` | 凭证模板 | 页面 | `views/finance/voucher-template/index.vue` | 419 | `api/voucher` | |
| 25 | `/hr/annual-settlement` | 年度汇算 | 页面 | `views/hr/salaries/annual-settlement.vue` | 650 | `api/annualTaxSettlement` | |
| 26 | `/hr/attendance-exceptions` | 考勤异常处理 | 页面 | `views/hr/attendance/exceptions.vue` | 443 | `api/request` | |
| 27 | `/hr/comptime-balance` | 调休账单 | 页面 | `views/hr/attendance/comptime-balance.vue` | 297 | `api/request` | |
| 28 | `/hr/config/city-overrides` | 城市差异化社保 | 页面 | `views/hr/config/city-overrides.vue` | 653 | `api/cityInsuranceOverride` | |
| 29 | `/hr/config/insurance-rates` | 社保公积金配置 | 页面 | `views/hr/config/insurance-rates.vue` | 662 | `api/insuranceConfig` | |
| 30 | `/hr/expense-requests` | 报销申请 | 页面 | `views/hr/expense-requests/list.vue` | 457 | `api/request` | |
| 31 | `/hr/leave-balances` | 假期余额 | 页面 | `views/hr/leave-balances/list.vue` | 154 | `api/request` | |
| 32 | `/hr/leave-requests` | 请假申请 | 页面 | `views/hr/leave-requests/list.vue` | 448 | `api/request` | |
| 33 | `/hr/overtime-requests` | 加班/调休申请 | 页面 | `views/hr/overtime-requests/list.vue` | 442 | `api/request` | |
| 34 | `/hr/salaries` | 工资管理 | 页面 | `views/hr/salaries/list.vue` | 807 | `api/salary`, `api/specialDeduction` | |
| 35 | `/hr/shift-calendar` | 排班日历 | 页面 | `views/hr/attendance/shift-calendar.vue` | 827 | `api/hrShiftAssignment`, `api/request` | |
| 36 | `/hr/special-deductions` | 专项扣除 | 页面 | `views/hr/special-deductions/list.vue` | 598 | `api/specialDeduction` | |
| 37 | `/hr/wage-policy` | 工资策略 | 页面 | `views/hr/wage-policy/index.vue` | 604 | `api/wagePolicy` | |
| 38 | `/indicator-center` | — | **函数重定向**(故意隐藏菜单) | — | — | — | |
| 39 | `/inquiry-quotes` | 核价单 | 页面 | `views/procurement/inquiry-quotes/list.vue` | 295 | `api/inquiryQuote` | |
| 40 | `/inventory` | 库存批次查询 | 页面 | `views/warehouse/inventory/index.vue` | 872 | `api/request` | |
| 41 | `/invoices` | 采购发票 | 页面 | `views/procurement/invoices/list.vue` | 729 | `api/request` | |
| 42 | `/operations/customer-material-arrivals` | 无订单入库申请 | 重定向 → `/warehouse/unordered-inbound-applications` | — | — | — | |
| 43 | `/ops/agent-ops` | Agent 运行与评测 | 重定向 → `/ops/agent-ops/eval-sets` | `views/platform/agent-ops/AgentOpsShell.vue` | 53 | — | |
| 44 | `/payment-requests` | 付款申请 | 页面 | `views/procurement/payment-requests/list.vue` | 173 | `api/request` | |
| 45 | `/price-anomaly` | 价格异常预警 | 页面 | `views/restaurant/price-anomaly/index.vue` | 570 | `api/smartbi/priceAnomaly` | |
| 46 | `/print-template-editor` | 打印模板设计器 | 页面 | `views/platform/print-template-editor/index.vue` | 279 | `api/printTemplateApi` | |
| 47 | `/production/bom/tree` | BOM多级展开 | 重定向 → `/system/product-processes` | — | — | — | |
| 48 | `/production/bom/versions` | BOM版本管理 | 重定向 → `/system/product-processes` | — | — | — | |
| 49 | `/production/labor-efficiency/dashboard` | 首页 | 页面 | `views/dashboard/index.vue` | 70 | — | |
| 50 | `/production-analytics` | 生产分析 | 重定向 → `/production-analytics/production` | — | — | — | |
| 51 | `/production-analytics/cost` | 成本分析 | 页面 | `views/production-analytics/FactoryCostAnalysis.vue` | 911 | `api/smartbi/factoryGold` | |
| 52 | `/production-analytics/three-price-comparison` | 三价对比看板 | 页面 | `views/production-analytics/ThreePriceComparison.vue` | 236 | `api/request` | |
| 53 | `/rd` | 研发管理 | 重定向 → `/rd/samples` | — | — | — | |
| 54 | `/rd/converted` | 已转样品库 | 页面 | `views/rd/converted/list.vue` | 145 | `api/request` | |
| 55 | `/rd/quotations/three-price` | — | 重定向 → `/rd/samples` | — | — | — | |
| 56 | `/requisitions` | 全部请购单 | 页面 | `views/procurement/requisitions/list.vue` | 330 | `api/purchaseRequisition` | |
| 57 | `/requisitions/my` | 我的请购 | 页面 | `views/procurement/requisitions/my-list.vue` | 343 | `api/purchaseRequisition` | |
| 58 | `/requisitions/pending-approval` | 待审批请购 | 页面 | `views/procurement/requisitions/pending-approval.vue` | 397 | `api/purchaseRequisition` | |
| 59 | `/returns` | 质检退回单 | 页面 | `views/quality/returns/list.vue` | 361 | `api/qualityReturn` | |
| 60 | `/sales/customer-tracking` | 客户跟踪记录 | 页面 | `views/sales/customer-tracking/list.vue` | 381 | `api/customerTracking`, `api/request` | |
| 61 | `/sales/finance-review` | 销售订单审批进度 | 页面 | `views/sales/finance-review/list.vue` | 156 | `api/salesFinanceReview` | |
| 62 | `/sales/needs` | 客户需求 | 页面 | `views/sales/needs/list.vue` | 313 | `api/salesNeed` | |
| 63 | `/sales/reports` | 销售报表 | 页面 | `views/sales/reports/dashboard.vue` | 128 | — | |
| 64 | `/scheduling/logistics-demo` | — | 重定向 → `/scheduling/logistics/workbench` | — | — | — | |
| 65 | `/smart-bi/gold-preview` | — | **函数重定向**(故意隐藏菜单) | — | — | — | |
| 66 | `/smart-bi/restaurant-v2` | 餐饮综合分析 | 页面 | `views/smart-bi/RestaurantV2Dashboard.vue` | 2447 | `/api/smartbi/restaurant-ops/gross-margin` | |
| 67 | `/smart-bi/upload-status` | 上传状态 | 页面 | `views/smart-bi/UploadStatusDashboard.vue` | 551 | `api/smartbi/upload-status` | |
| 68 | `/smart-bi/whatif` | — | **函数重定向**(故意隐藏菜单) | — | — | — | |
| 69 | `/supplier-delivery` | 供应商进货录入 | 页面 | `views/restaurant/supplier-delivery/SupplierDeliveryNoteList.vue` | 294 | `api/restaurant/supplierDeliveryNote` | |
| 70 | `/supplier-delivery/price-anomaly/pending` | 待我审批 | 页面 | `views/workflow/pending.vue` | 457 | `api/request` | |
| 71 | `/supplier-reconciliation` | 供应商月对账 | 页面 | `views/restaurant/supplier-reconciliation/list.vue` | 371 | `api/restaurant/supplierDeliveryNote`, `api/restaurant/supplierReconciliation` | |
| 72 | `/system/cost-variance` | 方差阈值配置 | 页面 | `views/system/cost-variance/index.vue` | 370 | `api/costVarianceConfig`, `api/request` | |
| 73 | `/system/data-fabric/capability-audit` | 能力驱动渲染审计 | 页面 | `views/system/data-fabric/capability-audit.vue` | 203 | — | |
| 74 | `/system/data-fabric/cell-audit` | 字段血统审计 | 页面 | `views/system/data-fabric/cell-audit.vue` | 333 | `/api/smartbi/provenance/audit` | |
| 75 | `/system/data-fabric/provenance-config` | Provenance 配置 | 页面 | `views/system/data-fabric/provenance-config.vue` | 410 | `/api/smartbi/factory-config/provenance` | |
| 76 | `/system/export-center` | 导出中心 | 页面 | `views/system/export-center/index.vue` | 453 | `api/dataCenter` | |
| 77 | `/system/import-center` | 导入中心 | 页面 | `views/system/import-center/index.vue` | 584 | `api/dataCenter` | |
| 78 | `/system/label-qc-platform-diagnostics` | 标签识别诊断 | 页面 | `views/platform/label-qc-diagnostics/index.vue` | 324 | `api/labelQc` | |
| 79 | `/system/label-qc-tray-crops` | 单盒标签精修 | 页面 | `views/platform/label-qc-crops/index.vue` | 321 | `api/labelQc` | |
| 80 | `/system/operation-log` | 操作日志 | 页面 | `views/system/operation-log/index.vue` | 281 | `api/dataCenter` | |
| 81 | `/system/smartbi-config/chart-templates` | 图表模板 | 页面 | `views/smartbi-config/ChartTemplateView.vue` | 684 | `api/smartbi-config` | |
| 82 | `/system/smartbi-config/data-sources` | 数据源配置 | 页面 | `views/smartbi-config/DataSourceConfigView.vue` | 526 | `api/smartbi-config` | |
| 83 | `/transfer` | 调拨管理 | 重定向 → `/transfer/list` | — | — | — | |
| 84 | `/transfer/new` | 新建调拨单 | 页面 | `views/transfer/list.vue` | 1045 | `api/request` | |
| 85 | `/warehouse/reusable-containers` | 周转耗材管理 | 页面 | `views/warehouse/reusable-containers/list.vue` | 221 | `api/request` | |
| 86 | `/warehouse/salted-deductions` | 盐化仓管理 | 页面 | `views/warehouse/salted-deductions/index.vue` | 670 | `api/request` | |
| 87 | `/warehouse/semi-finished` | 半成品重量库存 | 页面 | `views/warehouse/semi-finished/list.vue` | 262 | `api/request` | |
| 88 | `/warehouse/semi-finished-stocktakes` | 半成品盘点 | 页面 | `views/warehouse/semi-finished-stocktakes/index.vue` | 774 | `api/request` | |
| 89 | `/warehouse/transit-ledger` | 中转挂账对账 | 页面 | `views/warehouse/transit-ledger/list.vue` | 391 | `api/productionPlan` | |
| 90 | `/workflow/admin-running` | 工作流处理 | 页面 | `views/workflow/admin-running.vue` | 325 | `api/request` | |
| 91 | `/workflow/rules` | 流转规则设置 | 页面 | `views/workflow/rules.vue` | 560 | `api/request` | |


---

# 91 条走不到的路由 —— 带证据的分类（2026-08-27 补充）

配套闸：`web-admin/src/router/__tests__/routeReachability.spec.ts`（已从 91 收到 **88**）

## 结论先说

**大部分不是「没人用」，是「用的是 App」。** RN 侧实测：盘点 15 个屏、调拨 15 个、
领料 10 个、退货 11 个。这些 web 页面是**同一能力的第二份实现，而且是走不到的那份**。

⇒ 所以对多数条目，**该考虑的是删，不是补** —— 但删不可逆，需逐条圈。

生产数据佐证（这些能力确实在被使用，只是入口在别处）：
凭证导出记录 106、半成品盘点明细 4916、退货单 31、调拨单 12、领料申请 10；
而 `salary_items` / `payroll_records` 都是 **0** —— 薪资是唯一真的没人用过的。

## 唯一已执行的动作：补了会计凭证入口

| 证据 | 值 |
|---|---|
| `vouchers` | **18** |
| `voucher_entries` | **46** |
| `voucher_export_records` | **106** |
| `voucher_templates` / `subject_mappings` | 7 / 6 |
| RN 有没有凭证屏 | **没有**（中文命中全是「付款凭证号」这类词） |
| web 菜单登记 | **零** |

⇒ 凭证在生成、有人在导，**却没有任何界面能看**。已补 3 个入口，棘轮 91→88。

## 分类方法，以及它的盲区

判据是「这个页面调的后端端点，有没有被**用户走得到的**页面也调」。

⚠️ 第一版是错的：直接抽页面里的字符串，多数页面只抽到 `api/request`（一个几乎人人
import 的通用 helper），于是「被完全覆盖」这个读数**近乎恒真**。修法是往下走一跳，
解析它 import 的 `@/api/*` 模块里真正声明的端点。
阳性对照：159 个 api 模块 / 479 个可达组件 / 480 个端点。

⚠️ 仍有盲区：端点相同不等于能力相同（同一个列表接口可能服务不同视角）。
所以 B 类是「删除风险最低」，不是「一定可删」。

---

## A. 端点完全独有（23 条）—— 这些页面是某能力的**唯一**入口

删掉即失去该能力。

| 路由 | 独有端点数 | 组件 |
|---|---|---|
| `/admin/etl-status` | 3 | `views/restaurant/admin/etl-status.vue` |
| `/analytics/role-kpi` | 1 | `views/restaurant/analytics/role-kpi-dashboard.vue` |
| `/analytics/stores` | 2 | `views/restaurant/analytics/store-comparison.vue` |
| `/audit/cell` | 1 | `views/system/data-fabric/cell-audit.vue` |
| `/cost-attribution` | 1 | `views/restaurant/cost-attribution/index.vue` |
| `/data-completeness` | 1 | `views/restaurant/data-completeness.vue` |
| `/hr/annual-settlement` | 6 | `views/hr/salaries/annual-settlement.vue` |
| `/hr/config/city-overrides` | 6 | `views/hr/config/city-overrides.vue` |
| `/hr/config/insurance-rates` | 4 | `views/hr/config/insurance-rates.vue` |
| `/hr/salaries` | 13 | `views/hr/salaries/list.vue` |
| `/hr/shift-calendar` | 4 | `views/hr/attendance/shift-calendar.vue` |
| `/hr/special-deductions` | 4 | `views/hr/special-deductions/list.vue` |
| `/hr/wage-policy` | 1 | `views/hr/wage-policy/index.vue` |
| `/price-anomaly` | 4 | `views/restaurant/price-anomaly/index.vue` |
| `/print-template-editor` | 3 | `views/platform/print-template-editor/index.vue` |
| `/returns` | 4 | `views/quality/returns/list.vue` |
| `/smart-bi/upload-status` | 1 | `views/smart-bi/UploadStatusDashboard.vue` |
| `/system/cost-variance` | 2 | `views/system/cost-variance/index.vue` |
| `/system/data-fabric/cell-audit` | 1 | `views/system/data-fabric/cell-audit.vue` |
| `/system/data-fabric/provenance-config` | 1 | `views/system/data-fabric/provenance-config.vue` |
| `/system/export-center` | 14 | `views/system/export-center/index.vue` |
| `/system/import-center` | 14 | `views/system/import-center/index.vue` |
| `/system/operation-log` | 14 | `views/system/operation-log/index.vue` |

## B. 端点被可达页面完全覆盖（21 条）—— 能力另有入口，删除风险最低

| 路由 | 端点数 | 组件 |
|---|---|---|
| `/analytics/targets` | 1 | `views/restaurant/analytics/target-hierarchy.vue` |
| `/approval-workflow-editor` | 3 | `views/platform/approval-workflow-editor/index.vue` |
| `/crm/opportunity` | 1 | `views/crm/opportunity/SalesOpportunityList.vue` |
| `/crm/opportunity/kanban` | 1 | `views/crm/opportunity/SalesOpportunityKanban.vue` |
| `/finance/voucher-list` | 14 | `views/finance/voucher/list.vue` |
| `/finance/voucher-template` | 14 | `views/finance/voucher-template/index.vue` |
| `/inquiry-quotes` | 6 | `views/procurement/inquiry-quotes/list.vue` |
| `/production-analytics/cost` | 4 | `views/production-analytics/FactoryCostAnalysis.vue` |
| `/requisitions` | 6 | `views/procurement/requisitions/list.vue` |
| `/requisitions/my` | 6 | `views/procurement/requisitions/my-list.vue` |
| `/requisitions/pending-approval` | 6 | `views/procurement/requisitions/pending-approval.vue` |
| `/sales/customer-tracking` | 1 | `views/sales/customer-tracking/list.vue` |
| `/sales/finance-review` | 7 | `views/sales/finance-review/list.vue` |
| `/sales/needs` | 1 | `views/sales/needs/list.vue` |
| `/smart-bi/restaurant-v2` | 25 | `views/smart-bi/RestaurantV2Dashboard.vue` |
| `/supplier-delivery` | 1 | `views/restaurant/supplier-delivery/SupplierDeliveryNoteList.vue` |
| `/system/label-qc-platform-diagnostics` | 12 | `views/platform/label-qc-diagnostics/index.vue` |
| `/system/label-qc-tray-crops` | 12 | `views/platform/label-qc-crops/index.vue` |
| `/system/smartbi-config/chart-templates` | 11 | `views/smartbi-config/ChartTemplateView.vue` |
| `/system/smartbi-config/data-sources` | 11 | `views/smartbi-config/DataSourceConfigView.vue` |
| `/warehouse/transit-ledger` | 24 | `views/warehouse/transit-ledger/list.vue` |

## C. 部分覆盖（2 条）

| 路由 | 端点数 | 组件 |
|---|---|---|
| `/admin/name-resolution` | 2 | `views/restaurant/admin/name-resolution.vue` |
| `/supplier-reconciliation` | 2 | `views/restaurant/supplier-reconciliation/list.vue` |

## D. 抽不到端点（45 条）—— 父级路由 / 重定向 / 纯前端

| 路由 | — | 组件 |
|---|---|---|
| `/analytics/ai-reports` | 0 | — |
| `/analytics/dianping` | 0 | — |
| `/analytics/dishes` | 0 | `views/restaurant/analytics/dishes.vue` |
| `/analytics/gross-margin` | 0 | — |
| `/analytics/menu` | 0 | — |
| `/analytics/platform` | 0 | `views/restaurant/analytics/platform.vue` |
| `/approval-rules` | 0 | `views/procurement/approval-rules/list.vue` |
| `/dashboard/widgets` | 0 | `views/dashboard/widget-demo.vue` |
| `/exceptions` | 0 | `views/procurement/exceptions/list.vue` |
| `/finance/inventory-ledger` | 0 | `views/finance/inventory-ledger/index.vue` |
| `/finance/voucher-export` | 0 | `views/finance/voucher-export/index.vue` |
| `/hr/attendance-exceptions` | 0 | `views/hr/attendance/exceptions.vue` |
| `/hr/comptime-balance` | 0 | `views/hr/attendance/comptime-balance.vue` |
| `/hr/expense-requests` | 0 | `views/hr/expense-requests/list.vue` |
| `/hr/leave-balances` | 0 | `views/hr/leave-balances/list.vue` |
| `/hr/leave-requests` | 0 | `views/hr/leave-requests/list.vue` |
| `/hr/overtime-requests` | 0 | `views/hr/overtime-requests/list.vue` |
| `/indicator-center` | 0 | — |
| `/inventory` | 0 | `views/warehouse/inventory/index.vue` |
| `/invoices` | 0 | `views/procurement/invoices/list.vue` |
| `/operations/customer-material-arrivals` | 0 | — |
| `/ops/agent-ops` | 0 | `views/platform/agent-ops/AgentOpsShell.vue` |
| `/payment-requests` | 0 | `views/procurement/payment-requests/list.vue` |
| `/production/bom/tree` | 0 | — |
| `/production/bom/versions` | 0 | — |
| `/production/labor-efficiency/dashboard` | 0 | `views/dashboard/index.vue` |
| `/production-analytics` | 0 | — |
| `/production-analytics/three-price-comparison` | 0 | `views/production-analytics/ThreePriceComparison.vue` |
| `/rd` | 0 | — |
| `/rd/converted` | 0 | `views/rd/converted/list.vue` |
| `/rd/quotations/three-price` | 0 | — |
| `/sales/reports` | 0 | `views/sales/reports/dashboard.vue` |
| `/scheduling/logistics-demo` | 0 | — |
| `/smart-bi/gold-preview` | 0 | — |
| `/smart-bi/whatif` | 0 | — |
| `/supplier-delivery/price-anomaly/pending` | 0 | `views/workflow/pending.vue` |
| `/system/data-fabric/capability-audit` | 0 | `views/system/data-fabric/capability-audit.vue` |
| `/transfer` | 0 | — |
| `/transfer/new` | 0 | `views/transfer/list.vue` |
| `/warehouse/reusable-containers` | 0 | `views/warehouse/reusable-containers/list.vue` |
| `/warehouse/salted-deductions` | 0 | `views/warehouse/salted-deductions/index.vue` |
| `/warehouse/semi-finished` | 0 | `views/warehouse/semi-finished/list.vue` |
| `/warehouse/semi-finished-stocktakes` | 0 | `views/warehouse/semi-finished-stocktakes/index.vue` |
| `/workflow/admin-running` | 0 | `views/workflow/admin-running.vue` |
| `/workflow/rules` | 0 | `views/workflow/rules.vue` |

---

## 2026-08-27 追记：B 类**不能**批量删除

上一节把「端点 100% 被可达页面覆盖」的一批标成「删除风险最低」。
按这个结论去执行时，逐条查了生产数据，**结论是反的**：

| 路由 | 背后生产数据 |
|---|---|
| `/system/label-qc-platform-diagnostics` / `-tray-crops` | 托盘裁图 **11409** / 标注 **9583** / 照片 **5850** / 任务 **1495** |
| `/approval-workflow-editor` | 审批流 25 / 历史 104 / 实例 44 |
| `/sales/customer-tracking` | 客户价格历史 **260** |
| `/requisitions` | 领料申请 10 |
| `/inquiry-quotes` | 询价单 3 |

**label-qc 那一对最说明问题**：操作侧的「包装标签拍检」(`/quality/label-qc`) 一直在菜单里、走得到；
而**平台侧的两个诊断页走不到**。它们不是冗余，是**这个重度使用子系统的排障工具，
而排障的人打不开它**。⇒ 该补入口，不是该删。（已补，棘轮 88 → 86。）

### 判据为什么不够

两个判据都被否掉了：

1. **端点覆盖** —— 判不出「能力是否重复」。一个看板和一个列表页共用接口，
   不代表看板多余。`/smart-bi/restaurant-v2`（2446 行）就是这样被误判的。
2. **同区有可达兄弟页** —— 更差。它把餐饮看板判成可删，只因为菜单里有个
   不相干的 `/smart-bi/mapping-review`（同属 `/smart-bi/` 前缀而已）。

⇒ **静态分析判不出「这个页面还有没有用」。** 能给的最强旁证是「背后有没有生产数据」，
而那只能否定（有数据⇒别删），不能肯定（没数据也可能是刚上线的功能）。

**剩下的必须由懂产品的人看名字判断。** 19 个名字，5 分钟能扫完，比任何静态判据都准。
