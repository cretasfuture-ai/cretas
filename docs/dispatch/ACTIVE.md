# Dispatch 台账 — ACTIVE
**最后更新**：2026-08-23

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-08-21-active-history.md](archive/2026-08-21-active-history.md)、[2026-08-17-active-history.md](archive/2026-08-17-active-history.md)、[2026-08-16-active-history.md](archive/2026-08-16-active-history.md)、[2026-08-15-active-history.md](archive/2026-08-15-active-history.md)、[2026-08-14-active-history.md](archive/2026-08-14-active-history.md)、[2026-08-13-active-history.md](archive/2026-08-13-active-history.md)、[2026-08-12-active-history.md](archive/2026-08-12-active-history.md)、[2026-08-11-active-history.md](archive/2026-08-11-active-history.md)、[2026-08-10-active-history.md](archive/2026-08-10-active-history.md)、[2026-08-09-active-history.md](archive/2026-08-09-active-history.md)、[2026-08-07-active-history.md](archive/2026-08-07-active-history.md)、[2026-08-04-active-history.md](archive/2026-08-04-active-history.md)、[2026-08-03-active-history.md](archive/2026-08-03-active-history.md)、[2026-08-02-active-history.md](archive/2026-08-02-active-history.md)、[2026-08-01-active-history.md](archive/2026-08-01-active-history.md)、[2026-07-31-active-history.md](archive/2026-07-31-active-history.md)、[2026-07-30-active-history.md](archive/2026-07-30-active-history.md)、[2026-07-29-active-history.md](archive/2026-07-29-active-history.md)、[2026-07-28-active-history.md](archive/2026-07-28-active-history.md)、[2026-07-27-active-history.md](archive/2026-07-27-active-history.md) 与 [2026-07-26-active-history.md](archive/2026-07-26-active-history.md)，此前历史见 [2026-07-25-active-history.md](archive/2026-07-25-active-history.md)。

## 在飞任务

- `ML-LIUSHANMEN-WORK-AREA-ROI-20260811` — `blocked` — Owner: `/root` — 已有代码进入公司迁移树，但正式 audit 仍因 `trayal_0e5a259f_030` unjudgeable fail closed；计划任务 `Cretas-Liushanmen-VisionLab` 今日 LastResult=1，仍指向旧 detached worktree，须先迁到公司受控代码源并核销失败。保留视觉 scope；LocateAnything hardening 与其串行，模型、人工真值、原图和生产不改。
- `FIX-F006-WAREHOUSE-RN-HISTORY-PAGINATION-20260823` — `in-progress` — Owner: `/root/warehouse_history_fix` — Base SHA: `45e51500173f47686ccb72de6ed25141afba32f4` — Scope lock: RN `warehouseDeliveryApiClient`、`WHDeliveryConfirmListScreen`、`WHPurchaseReceiveListScreen`、`WHInboundListScreen`、`warehouseList.logic`及相关测试；Java `WarehouseDeliveryController` 及现有 warehouse delivery Service/Repository 必需的只读历史查询/真实 JPA 与 Controller 测试。不得修改销售发货写状态机、财务、权限矩阵、ACTIVE、数据库/迁移/生产。验收：真实受权历史 API；终态覆盖旧 pending；待确认数不混历史；入库/发货超 50 条可分页找到；采购两路部分失败不跳页不丢数；行为测试 + JPA 启动门禁；`NOT_DEPLOYED`。

- `FIX-F006-WORKFLOW-IDENTITY-DEACTIVATION-RACE-20260823` — `in-progress` — Owner: `/root/workflow_fix` — Base SHA: `2af2c7bce6` + 已验证 dirty 历史引用修复 — Scope lock: Workflow `saveDraft` 请求 id/definitionVersion/lockVersion 身份门禁、Web 历史预览恢复身份与 409 错误文案、deactivate 在 count 前获取与 `V20261028_53` 一致的 product advisory xact lock、对应 Mockito/Web/真实 PostgreSQL 两事务测试；不得修改无关 Workflow/BOM/计划业务、ACTIVE、生产数据或远程。验收：旧客户端不能覆盖新 DRAFT；历史图恢复到当前草稿 envelope 或显式冲突；start-first 则 deactivate 409，deactivate-first 语义与 spec 一致；后端原因清晰可见；目标测试、`NOT_DEPLOYED`。

- `FEATURE-F006-WAREHOUSE-DELIVERY-HISTORY-20260823` — `review` — Owner: `/root` — Base SHA: `e356afc69af2215839625b435becadf2e256b57d`; warehouse commit `45e5150017` — Scope lock: RN `services/api/warehouseDeliveryApiClient.ts`、`screens/warehouse/outbound/WHDeliveryConfirmListScreen.tsx`、`screens/warehouse/warehouseList.logic.ts` 及对应目标测试；只接入约定的 `GET /api/mobile/{factoryId}/warehouse/deliveries/history?page=1&size=...` 只读接口，禁止修改 Java、其他 RN 页面、权限、ACTIVE 或生产。回执：10 suites / 73 tests、Babel 语法与 `git diff --check` 通过；改动文件无新增 TypeScript 错误，基线仍有 `apiClient.ts` 5 个既有错误；`NOT_DEPLOYED`。待协调者复核并与 Java history endpoint 一并验收。

- `FIX-F006-FINANCE-OUTBOX-RISK-20260823` — `in-progress` — Owner: `/root/finance_outbox_fix` — Base SHA: `5afa1d31c078a6723c7adac47835ae6ae3846f1b` — Scope lock: `SalesOrder` 自动开票内部状态、`SalesOrderRepository`、`SalesOrderInvoiceTodoProcessor`/调度器、`InvoiceServiceImpl` 自动开票幂等分支、`InvoiceRecord`唯一约束、Flyway `V20261030_20`及对应 JPA/Service 测试；不得修改采购发票、REST/AI 墓碑、Web、E2E、ACTIVE、数据库或远端。验收：部分已开票不误标 DONE；足额已开票才幂等完成；死信可见、可授权重试并有告警证据；重启/租约恢复；Entity 与 PostgreSQL 唯一语义一致；目标测试与真实 JPA Context；`NOT_DEPLOYED`。

- `FIX-F006-FINANCE-PURCHASE-TOMBSTONE-EVIDENCE-20260823` — `in-progress` — Owner: `/root/finance_boundary_fix` — Base SHA: `5afa1d31c078a6723c7adac47835ae6ae3846f1b` — Scope lock: PurchaseInvoice Repository/Service/Controller 锁与并发测试；Invoice REST/AI 手工申请墓碑、descriptor/Registry 可达性测试、死组件 `TaxGroupInvoiceDialog.vue`；F006 自动开票 mutation 证据记录及相关 E2E 清单；不得修改 outbox Entity/Repository/migration/processor、销售发货、Web 菜单路由、ACTIVE、数据库或远端。验收：采购发票同租户悲观锁 + 真实 JPA 门禁/并发测试；旧手工能力 REST/Registry/descriptor/E2E 契约一致且无死 POST；生产写账本记录自动 InvoiceRecord 的 ID/租户/预期实际 mutation/保留或清理；目标测试；`NOT_DEPLOYED`。

- `REVIEW-F006-WORKFLOW-AUTO-FORK-RISK-20260823` — `in-progress` — Owner: `/root/workflow_risk_review` — Base SHA: `e356afc69af2215839625b435becadf2e256b57d`; feature commits `c07ed0e4b8f7e319c979f19f60bc23b1399937bc` + `2af2c7bce6` — Read-only scope: Workflow 自动版本分叉、历史 identity、ProductionPlan/ProductionBatch 引用查询、停用门禁、编辑器提示及目标测试；禁止修改任何文件、ACTIVE、数据库、远端或生产。验收：重点复核事务/并发、版本唯一性、运行态枚举覆盖、JPA 查询门禁、旧 revision/计划/批次不变与缺失测试，返回结构化回执。

- `REVIEW-F006-FINANCE-OUTBOX-RISK-20260823` — `in-progress` — Owner: `/root/finance_risk_review` — Base SHA: `e356afc69af2215839625b435becadf2e256b57d` — Read-only scope: 当前 worktree 的财务/自动开票持久待办/Invoice Entity/SalesOrder Entity/SalesOrderRepository/Flyway V20261030_20/REST-AI 墓碑/采购待收票/Web 菜单与 E2E 精确 diff；禁止修改任何文件、ACTIVE、数据库、生产或远端。验收：按严重度复核事务、幂等、并发、迁移可部署性、历史数据边界、租户/权限两闸、JPA 启动测试与缺失测试，返回结构化回执。

- `FEATURE-F006-FINANCE-MANUAL-INVOICE-TOMBSTONE-20260823` — `in-progress` — Owner: `/root/finance_boundary_fix` — Base SHA: `d34de3c4d0` — Scope lock: `InvoiceController` 两个旧手工申请端点、`InvoiceRequestTool`/`InvoiceRequestFromOrderTool`、`SalesCreateInvoiceTool`、对应 tool descriptors/Registry 可达性/tests、`scripts/e2e/f006-role-chain/f006-role-chain.mjs` 自动待办观察段、`tests/v1-e2e/web/g1-invoice.spec.ts`/`j5-sales-full.spec.ts` 旧手工入口回归；禁止修改其他 AI Tool、扩大权限、生产写或恢复手工开票。验收：旧 REST 明确 410；全部 AI 手工开票/创建应收入口 disabled 且 Registry 不可达、写风险/权限元数据一致；E2E 只观察系统待办后继续既有受控 F006 流程；目标测试与共享脚本语法检查、`NOT_DEPLOYED`。

- `TRACE-CRETAS-ORIGIN-RBAC-RECONCILE-20260823` — `review` — Owner: `/root/sales_delivery_trace` — Base SHA: local main `e356afc69af2215839625b435becadf2e256b57d`, origin/main `3ea7444364d12d345d3a66cfca19e06d05820474` — Read-only scope: `cretas-mainmerge`、RBAC handoff、local main 独有已部署 RBAC commits 与 origin 新历史/tree；禁止修改文件、分支、索引、ACTIVE、生产或远端。验收：列出必须保留的离线 RBAC commit/文件/语义，origin 已覆盖/缺失/冲突，安全 bridge 策略和验证命令；不得用整树回退覆盖 origin 新版本。

- `FEATURE-F006-SALES-FULLPAGE-DELIVERY-20260823` — `queued` — Owner: `/root/sales_executor` — Base SHA: 待财务最终 commit — Scope lock: Web `router/index.ts`、`views/sales/orders/list.vue`、`detail.vue`、新增全屏建单页/产品与仓库选择组件、现有 `pinyinSearch.ts` 只读复用及对应测试；Java `SalesServiceImpl` 发货单创建/母子状态、`SalesController`/`SalesDeliveryShipmentController` 必需契约与目标测试；`docs/specs/f006-sales-fullpage-order-and-delivery-2026-08-23.md`。与财务 `SalesServiceImpl` 先串行，禁止修改发票监听器、权限矩阵、Entity/Repository/迁移或生产数据。产品裁决：销售明细只选可销售成品/半成品，客供原料仅走 `suppliedMaterials`；仓库按生产相关（RAW/WORKSHOP/WIP/LINESIDE）、物流（LOGISTICS）、成品（FINISHED）三组展示，排除 OTHER/RETURNS/SCRAP/研发；保留 processingMode/materialSupplyMode。验收：全屏创建、中文/编码/全拼/首字母检索、列表单品直显多品悬停完整、移除锁/备/缺、一次性自动生成全量子发运单并进入仓库待发、分批继续显式建子单、母单不可直发、状态闭环、目标 Vitest/Java/typecheck/build、`NOT_DEPLOYED`。

- `REVIEW-F006-WAREHOUSE-RN-SYMMETRY-20260823` — `review` — Owner: `/root/finance_reviewer` — Base SHA: `e356afc69af2215839625b435becadf2e256b57d` — Read-only scope: commit `6d37b5ad55166b4cdcbf7b60bdb8cf437d98331c` 的 RN 仓管导航、首页、入库、发货、采购状态与测试；禁止修改任何文件/ACTIVE/数据库/生产。验收：按严重度核对真实 API、三类待办与状态映射、低技术 UX、入/发货对称、单位/错误态、删除代码是否造成功能回退，附精确位置。

- `FEATURE-F006-FINANCE-INVOICE-TODO-OUTBOX-20260823` — `review` — Owner: `/root` — Base SHA: `e356afc69af2215839625b435becadf2e256b57d`; commit `5afa1d31c0` — Scope lock: `SalesOrder` 自动开票待办持久状态字段、`SalesOrderRepository` 重试候选查询、`SalesOrderInvoiceTodoProcessor`/监听器/调度器、`InvoiceRecord` 工厂内发票号唯一约束、Flyway `V20261030_20`、对应 Service/Listener/Scheduler/真实 JPA Context 测试；禁止历史订单回填、F006 数据写入或扩大手工开票入口。回执：8 个目标测试及真实 JPA gate 通过、Flyway 版本唯一、`git diff --check` 通过，`NOT_DEPLOYED`；风险终审仍要求修复最后一次 claim 崩溃后永久 PROCESSING 的恢复语义。

- `UX-F006-WAREHOUSE-RN-SYMMETRY-20260823` — `review` — Owner: `/root/warehouse_rn` — Base SHA: `e356afc69af2215839625b435becadf2e256b57d` — Scope lock: `frontend/CretasFoodTrace/src/navigation/WarehouseManagerNavigator.tsx`、`WarehouseManagerTabNavigator.tsx`、`screens/warehouse/home/**`、`screens/warehouse/inbound/**`、`screens/warehouse/outbound/**`、`utils/purchaseOrderStatus.ts`、这些范围的既有/新增目标测试、`docs/specs/f006-warehouse-rn-home-and-receiving-ux-2026-08-23.md`；不得修改 Java、Web、权限、ACTIVE/归档或生产数据。验收：四导航与极简首页、入库真实三待办追踪、指定筛选/排序、扫码大按钮、单位明确、发货对称、采购完成显示“已收货”，目标 Jest/TypeScript、`git diff --check`，`NOT_DEPLOYED`。

- `TRACE-F006-SALES-DELIVERY-STATE-20260823` — `review` — Owner: `/root/sales_delivery_trace` — Base SHA: `e356afc69af2215839625b435becadf2e256b57d` — Read-only scope: 销售订单创建/列表、产品检索、仓库类型 resolver、母子发货单、仓储发货状态机/API/测试；禁止修改任何文件、ACTIVE、数据库或生产。验收：返回准确文件/状态/API/仓库枚举映射、现有测试、与财务 `SalesServiceImpl` 完成态发布点的接口边界及建议互斥写 scope。

- `TRACE-F006-WORKFLOW-AUTO-FORK-20260823` — `review` — Owner: `/root/workflow_trace` — Base SHA: `e356afc69af2215839625b435becadf2e256b57d` — Read-only scope: Workflow 编辑/保存/版本/引用/停用状态机、Web 编辑器与目标测试；特别核对 ACTIVE 中 output-kind、viewport、inline category、cell actions、workprocess governance 的既有锁；禁止修改任何文件、ACTIVE、数据库或生产。验收：返回无冲突实现切面、必须串行/吸收的锁、不可变历史与自动分叉契约、JPA/迁移需求和建议验收命令。

- `FEATURE-F006-WORKFLOW-AUTO-FORK-ACTIVE-GATE-20260823` — `in-progress` — Owner: `/root/workflow_executor` — Base SHA: `e356afc69af2215839625b435becadf2e256b57d`; committed baseline `2af2c7bce6` — Scope lock: Java `ProductProcessWorkflowServiceImpl`、`ProductProcessWorkflowActivationServiceImpl`、Workflow DTO、仅为活动生产引用门禁必需的既有 ProductionPlan/ProductionBatch Repository 方法及对应 Workflow Service/真实 JPA Context/真实 PostgreSQL 并发测试；Web Admin `ProductProcessWorkflowEditor.vue`、Workflow API 类型与对应目标测试；新增且唯一的 Flyway `V20261030_21`，只用于将停用与计划/批次进入运行态线性化。本任务在这些精确文件内串行吸收既有编辑器锁，禁止扩大到 WorkProcess 治理、BOM 替代、投入模式派生、计划快照改写或生产数据。验收：产品级统一锁顺序；软删除历史引用仍不可变；停用与运行态转换共享同一 advisory lock 并有两种竞争顺序证据；自动分叉响应元数据和历史恢复当前 envelope 行为如实；目标 Vitest、Java Service、真实 JPA Context、可用时真实 PG 并发测试、Web typecheck、`git diff --check`，`NOT_DEPLOYED`。

- `REVIEW-F006-FINANCE-AUTO-TODO-20260823` — `in-progress` — Owner: `/root/finance_reviewer` — Base SHA: `e356afc69af2215839625b435becadf2e256b57d` — Read-only scope: 当前财务草稿的全部 dirty diff、现有 Invoice/PurchaseInvoice/Sales 完成监听器与测试、菜单/路由四承载点；禁止修改任何文件、ACTIVE、数据库或生产。验收：按严重度报告确定缺陷、重复开票/事务边界/RBAC/兼容风险、精确文件位置和最小修复建议；权限漂移新增必须为 0。

- `UX-F006-FINANCE-ARAP-AUTO-INVOICE-20260823` — `in-progress` — Owner: `/root` — Base SHA: `e356afc69af2215839625b435becadf2e256b57d` — Scope lock: `web-admin/src/components/layout/menuConfig.ts`、`web-admin/src/router/guards.ts`、`web-admin/src/router/index.ts`、`web-admin/src/views/finance/invoices/list.vue`、`web-admin/src/views/procurement/invoices/list.vue`、销售订单 Web 的手工开票入口与对应目标测试；Java 销售订单完成事件/监听器、`SalesServiceImpl` 完成态发布点、`InvoiceController` 手工申请墓碑、`InvoiceRequestTool`/`InvoiceRequestFromOrderTool` 兼容墓碑、对应 tool descriptor 与测试、`PurchaseInvoiceController/Service/Impl` 的待收票工作台读取及对应测试；`tests/v1-e2e/web/g1-invoice.spec.ts`、`j5-sales-full.spec.ts`、`scripts/e2e/f006-role-chain/f006-role-chain.mjs` 中精确自动开票验收段；本行与当日归档。目标：财务主管可见应收应付、销售开票、采购收票、收款管理；销售审批只在个人 OA，侧栏不再出现“财务待审销售单”；销售全部发货完成后按销售单/客户默认票种、税率分组幂等生成待审核开票记录；完成/关闭且尚未收到供应商发票的采购单自动进入待收票工作台，不伪造已收到发票；UI、REST 与 AI 全部移除/拒绝手工申请开票。不得回填历史发票、修改 F006 财务数据、放宽餐饮权限或覆盖 `E2E-F006-RBAC-ROLE-CHAIN-20260823` 已锁定的 AP 核销页面。验收：权限漂移新增 0、Web 目标测试/typecheck/build、Java listener/service/controller/tool/发货完成/事务边界目标测试、现有 Repository 真实 JPA gate（若查询签名变化则必须运行）、E2E 断言发货完成后唯一 `REQUESTED` 且无手工入口、本地 main 离线合入/发布后核对进程启动时间晚于制品 mtime，并以 `f006_finance_mgr` 退出重登复核导航和只读列表。

- `E2E-F006-RBAC-ROLE-CHAIN-20260823` — `in-progress` — Owner: `/root` — Base SHA: `7c4512fcbb73b48584c043cfe65c64033254721f` — Scope lock: `scripts/e2e/production-readonly/cli-runner.mjs`、`config/routes.js`、`config/readonly-post-whitelist.js`、`core/console-recorder.js`、`core/mutation-guard.js`、`core/sanitizer.js`、`core/run-suite.js`、`core/ui-login.js`、`scenarios/material-consumption-price-mask-readonly.js`、`scenarios/role-menu-census-readonly.js`、对应 unit tests 与生成的 `mcp-entry.js`；独立写入型 harness `scripts/e2e/f006-role-chain/**`（绝不并入 production-readonly 白名单，逐次记录预期 mutation、F006 登录证明、实体 ID 与回读）；`web-admin/src/views/workdesk/QualityChiefWorkdesk.vue` 及目标测试（默认待放行查询必须显式固定到只读 `QUALITY_CHIEF_WORKDESK`，不允许页面挂载时无意图码路由）；`web-admin/src/components/layout/AppSidebar.vue` 与 `AppSidebar.reactivity.spec.ts`（财务简化菜单必须经过与普通菜单相同的业态隐藏和权限过滤，修复 F006 可见但 403）；`backend/java/cretas-api/.../controller/inventory/SalesController.java` 与 `SalesControllerFinanceReviewListPermissionTest.java`（待财审销售单只读列表接受 `finance:read`，仍保留独立 `FINANCE_REVIEW_VIEW_PERMISSION` 二次闸，不扩大销售写权限）；`web-admin/src/views/finance/ar-ap/index.vue`、`web-admin/src/views/procurement/payment-requests/list.vue` 及其目标测试（删除指向后端 410 墓碑的采购新建/提交/审批/付款假入口；按现行财务红线仅允许财务从收货生成的 AP_INVOICE 未清项发起幂等付款核销，旧付款申请只作历史查询）；`docs/qa/F006-RBAC-ROLE-CHAIN-E2E-2026-08-23.md`、本行与当日归档。先以 `f006_quality_insp` 和真成本批次 `PB-PLAN-1787036063746-8719B078-74084` 验价格掩码，再执行 F006 Web、Expo App 与跨端采购→收货生成未清应付→财务付款核销→销售→生产→质检→发货→开票收款全链；原交接中的“付款先于收货”已被运行时 410 和 `PayableSettlementController` 证明是已退役旧链，不得绕过。只读阶段业务写 0；写阶段仅允许带本轮业务键且已证明属于 F006 的记录。产品缺陷另登记精确不重叠 scope 后修复，GitHub 停用期间走本地 main 离线合入/发布并核对进程启动时间晚于制品 mtime。验收：流程文档、价格掩码 4 条正向数据且所有金额键/列缺失、角色退出重登、深度矩阵、跨端回读、缺陷同因扫描、最终线上回归与本轮数据清理。

- `LLM-PROVIDER-CLIFF-20260815` — `blocked` — Owner: `/root` — DeepSeek 接线、七槽、悬崖模拟、每日健康与复审 cron 已进入迁移树并在线运行；剩余仅 2026-09-13 aistore 续期换 key、2026-09-04/11-15 运营复审等外部动作。旧 `llm_router.py` 代码锁已释放；不得把供应商控制台动作扩大为代码改动。

- `UX-RN-RESTAURANT-PROCUREMENT-DELIVERY-20260802` — `blocked` — Owner: `/root` — 实现提交已进入迁移树，代码与 Expo Web 验收可核销；当前餐饮 RN OTA runtime 与真实设备验收无法确认。实现文件 scope 已释放；如继续，仅新建 exact-commit OTA/设备验收项。

- `SEC-CREDENTIAL-ROTATION-20260719` — `blocked` — Owner: `/root` — tracked tree、服务器副本、数据库/JWT/internal/BaoTa/OSS RAM 替换已完成；仍需各供应商控制台吊销旧 Aliyun 主账号 AK、旧 Model Studio/Zhipu/DeepSeek key 与微信长期凭证并验证失效。旧 broad 代码锁已释放；不得在本任务做历史重写。

- `ENH-F006-MATERIAL-TAXONOMY-SUGGESTION-001` — `blocked` — Owner: `/root` — 候选 DTO、置信度/reason、同厂历史匹配和 Web 展示已进入迁移树；分类建议目标测试与 Excel preview 复用未完整证明，当前无执行者。陈旧 scope 已释放；恢复时按最新 Base 重新登记。

- `ARCH-F006-UNIFIED-MATERIAL-RECEIPT-SOURCE-001` — `blocked` — Owner: `/root` — CUSTOMER_OWNED 与销售 lineage 已有，但 PURCHASE/CUSTOMER_SUPPLIED/TRANSFER/退回/退料/期初/调整的统一任务与来源矩阵未完整实现。当前无执行者，旧高风险 scope 已释放；恢复时需重新登记 Entity/Flyway/JPA 门禁。

- `BUG-F006-PURCHASE-AP-PAYMENT-BOUNDARY-001` — `blocked` — Owner: `/root` — V94、allocation 核心、财务 AP 未清项付款与旧采购付款只读边界已进入迁移树；四维状态、付款条款里程碑/计划/三单匹配未完整实现。CORE 子任务已归档；父任务旧广域锁释放，恢复时按最新 Base 重锁。

- `ARCH-F006-DEMAND-DRIVEN-MATERIAL-RECEIPT-001` — `blocked` — Owner: `/root` — 净需求、客户来料、ownership/idempotency 与旧直接收料 fail closed 已有；严格同批 identity/单位/日期/QC/cost 匹配及独立库存 transaction 验收不足。需求仍有效且跨模块，保留 scope，`NOT_DEPLOYED`。

- `ENH-F006-CUSTOMER-CREATE-PROGRESSIVE-DISCLOSURE-001` — `blocked` — Owner: `/root` — Web 渐进表单已存在，但当前后端仅 name 必填，与 ACTIVE 原五项强制及销售单补全门禁冲突。须先由 Owner 裁定产品真值；保留 scope，生产客户零修改。

- `ENH-F006-SUPPLIER-IMPORT-001` — `blocked` — Owner: `/root` — 供应商必填资料、Excel 预览/幂等确认、状态生命周期、采购门禁与详情信息架构尚无本轮完整 tree/test 核销证据；当前无专用 PR。需求和 scope 均保留，恢复时先核对公司 main 的现有实现，严格 `NOT_DEPLOYED`。

- `BUG-F006-UNIT-DIMENSION-SCOPE-001` — `blocked` — Owner: `/root` — UnitContractService、原料/采购/入库/BOM 单位门禁及“件/个/只”字面单位的完整验收尚未本轮核销；保留 scope 与真实 JPA/Service 门禁，生产业务写入为 0。

- `BUG-F006-R2-SKU-UNIT-001` — `blocked` — Owner: `/root/supplier_frontend` — SKU displayUnit、净含量单位、同维度 canonical 化与历史回填尚无本轮完整 tree/test 核销证据；保留 Web scope，Java 契约仍须协调者另行审计，严格 `NOT_DEPLOYED`。

- `FEATURE-F006-R2-WORKPROCESS-DEDUP-001` — `blocked` — Owner: `/root/workprocess_governance` — canonical/mergedInto、未来候选排除、既有 Workflow/SKU/计划/报工引用不迁移，以及排序/单位从主数据移除的完整 JPA/Web 验收尚未本轮核销；保留 scope，生产重复组零治理。

- `FEATURE-F006-R2-BOM-SUBSTITUTE-001` — `queued` — Owner: `/root` — Java CORE 已归档，但父任务的 Web、计划、领料、报工、成本和追溯快照尚未证明完成；继续由统一 Workflow/BOM 任务串行吸收，保留父任务 scope，严格 `NOT_DEPLOYED`。

- `F006-R2-WORKFLOW-FIRST-BOM-GATE` — `queued` — Owner: `/root` — Workflow 草稿结构、BOM 完整性、激活/发布和生产计划准入的端到端门禁尚未证明完成；与统一 Workflow/BOM 状态机串行，保留 scope、ACTIVE-only 运行契约与历史快照零改写。

## Scope 锁地图

- `ML-LIUSHANMEN-WORK-AREA-ROI-20260811`：仅 `tools/vision-lab/work_area*.py`、`tools/vision-lab/mine_tray_queue.py`、`tools/vision-lab/tray_workflow.py`、`tools/vision-lab/evaluate_tray_candidate.py`、对应 `tools/vision-lab/tests/`、`docs/ops/liushanmen-vision-lab.md`、`docs/dispatch/ACTIVE.md` 与当日归档；不得改写人工真值、保护集、原图、现有模型或生产。

- `ARCH-F006-DEMAND-DRIVEN-MATERIAL-RECEIPT-001`：销售需求、BOM净需求、采购建议、客户来料、仓储来源任务及对应 Java/Web/测试/dispatch；涉及 Entity/Repository/Flyway 时必须重登记最新 Base 并通过真实 JPA/迁移门禁。不得开启普通手工收料写入口或修改生产数据。

- `ENH-F006-CUSTOMER-CREATE-PROGRESSIVE-DISCLOSURE-001`：客户 Web 共享表单与 Java Customer DTO/Service/Repository/API/import、销售使用门禁、目标测试/dispatch；先裁定联系人/电话/地址是否强制，再实施。不得修改生产客户数据。

- `ENH-F006-SUPPLIER-IMPORT-001`：供应商 Java Entity/DTO/Controller/Service/Repository、采购门禁、共享 Excel 导入与目标测试；Web 供应商列表/详情/新增编辑/导入及 API/types/tests；必须真实 JPA Context（如触及查询），生产业务写入为 0。

- `BUG-F006-UNIT-DIMENSION-SCOPE-001`：Java 单位 Entity/DTO/Repository/UnitContractService、原料/采购/入库/BOM 场景校验及真实 JPA/Service 测试；Web UnitSelect/API/types/display helper 与目标测试；不得改变历史异常记录或生产数据。

- `BUG-F006-R2-SKU-UNIT-001`：仅 Web SKU 新建/编辑基础规格、displayUnit/净含量单位 helper 与目标测试；Java 净含量契约须另行登记不重叠 scope。

- `FEATURE-F006-R2-WORKPROCESS-DEDUP-001`：Java WorkProcess/Workflow/SKU 关联、DTO/Repository/Service/Controller、Flyway/JPA tests；Web 工序治理及排序/单位字段移除与目标测试；不得迁移既有业务引用或治理生产重复组。

- `FEATURE-F006-R2-BOM-SUBSTITUTE-001`：BOM 替代关系的 Web、版本/计划/领料/报工/成本/追溯快照与目标测试；Java CORE 已完成，父任务不得重复修改 CORE Flyway/Entity，必须与统一 Workflow/BOM 任务串行。

- `F006-R2-WORKFLOW-FIRST-BOM-GATE`：Workflow 草稿结构、BOM 完整性 DTO/Service/API、激活/发布/生产计划准入、Web 阶段引导与目标测试；不得改写历史快照或生产数据。

## 阻塞项

- VisionLab：正式 ROI audit 仍有 1 个 unjudgeable；旧计划任务 LastResult=1 且代码源尚未迁到公司受控 worktree。
- LLM 运营：2026-09-13 aistore 续期换 key，以及 2026-09-04/2026-11-15 复审需要 Owner/供应商控制台动作。
- 餐饮 RN：实现已入 main，但当前 OTA runtime 与真实设备验收未确认。
- 凭证：旧 Aliyun 主账号 AK、旧模型供应商 key、Mall 微信长期凭证仍需各控制台吊销/重置并验证失效。
- F006 产品/架构：taxonomy suggestion、统一收料来源、AP 付款完整边界仍是无执行者 backlog；需求驱动收料与客户渐进创建保留 scope，后者须先裁定必填规则。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认并发最多 2 个代码执行代理和 2 个只读/测试代理；不得超过当前运行时槽位，且无独立价值的工作保持 DIRECT。
