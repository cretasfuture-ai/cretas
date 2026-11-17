# 页面 → 后端接口逐页对照 · A 组（26 个路由）

只读核对，未改仓库。日期 2026-11-18。仓库根：`C:\Users\Steve\my-prototype-logistics\.claude\worktrees\admiring-chandrasekhar-9000f3`。

## 读法与证据范围

- Java 路径均在 `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/` 下（下文只写文件名:行）；Python 路径在 `backend/python/` 下。路径前缀 `/api/mobile/{factoryId}` 简写为 `M`。
- 路由与行号来自对 Controller 注解的静态索引（`scratchpad/routes.txt`），并抽读了关键 Controller 方法体：`WorkflowInstanceController`、`IssueDispositionController`、`SavedViewController` / `ViewEntityCatalog`、`AIController` 报告三接口、`AIEnterpriseService` 经营报告段、`OperatingReportSnapshot`、`mapping_review.py`、`completeness_calculator.py`、`SmartBiQueryTemplateController`。其余项只看到路由存在，写「字段规则未核」。
- 重要事实：`/smart-bi/*`、`/analytics/*` 这些路由在 unified-v1 里没有原生页面（`frame/routes.ts` 里只有 `/smart-bi/analysis-hub` 且仅六膳门走财务视图），它们是 `prototypes/component-library/legacy-src/views/{smart-bi,analytics}` 的编译页，即 web-admin 页面的旧拷贝（与 `web-admin/src/views/...` 有差异，ExcelUpload / MappingReviewQueue / DataCompletenessView 完全相同）。这几页以 web-admin 同名页面的真实调用作为证据，前端已在调真接口。
- `/crew/*`、`/g/*`、`/workflow/*`、`/collab/tasks`、`/unified/reports`、`/analytics/ai-reports` 是 unified-v1 原生页，数据来自原型本机 `.cjs` 服务（`/__unified/*`、`/__lsm/*`、`private/*.json`）和黑湖快照，这些本身不进后端。
- Python `smartbi_compat/api/*` 在 `main.py:1203-1235` 已 `include_router`，Java 侧 `/smart-bi/analysis/*` 实际由它提供（Java 无这些路由）。

状态统计（26 个路由，`/crew/reports` 不在清单内）：有 13（workflow 四页、smart-bi/dashboard、analysis-hub、supply-chain、alert-dashboard、smart-bi/analysis、upload、mapping-review、query-templates、vision-training），部分 9（/crew/settings、/crew/issues、/g/today、/g/todo、/g/settings、ai-reports、analytics/overview、data-completeness、/unified/reports），缺 4（/crew、/crew/ledger、/crew/onboard、/collab/tasks），原型专用 0（各页里的原型专用数据源已在各节写明）。缺口 10 条（G1–G10）。

---

## 1. /crew ｜班组首页 ｜`unified-v1/src/crew/CrewHome.vue`、`CrewCards.vue`、`crew-store.ts`、`f-api.cjs`

数据与动作：
1. 五位数字员工（文员/仓管/计划员/质检/会计）各一张卡：今天办成 / 等确认 / 已交办 / 发现问题 的计数。
2. 本厂启用了几位数字员工。
3. 当前登录人与权限（决定卡片上「能查/能办」）。
4. 动作：跳转到各子页，无写入。

后端对应：
1. 无。「办成/交办」来自 `f-api.cjs` 本机 JSON 的 `records`，没有对应表。近似物：`AiValueSummaryController.java` 的 `GET M/ai/value-summary`（AI 调用次数、token、预警计数，是全厂口径，不分「数字员工」，不含「办成件数」）。
2. 无。
3. 有：`CurrentPermissionsController.java:26` `GET M/permissions/current`；`UserController.java:235` `GET M/users/current`。

状态：**缺**（仅第 3 项有）。
缺口：数字员工实体、启用配置、办成/交办记录与按日计数（见缺口 G1）。

## 2. /crew/ledger ｜工作记录与额度 ｜`CrewLedger.vue`、`crew-store.ts`、`f-api.cjs`

数据与动作：
1. 工作记录列表（时间、员工、事项、结果 已交办/已办成/没办成/结果不明/演示、额度、依据单号、确认人、说明）。
2. 每位员工的结果分布与额度合计；今日额度。
3. 「已交办」回读协同任务状态后转已办成/没办成（`records/sync`）。
4. 动作：按员工筛选；导出 CSV（前端本地生成）。

后端对应：
1–3. 无。`AIQuotaConfigController.java:42` `GET M/ai-quota-configs` 是 AI 提问次数的配额配置（按 questionType），不是「每件办成扣 1 个额度」的口径；`AIEnterpriseService.getQuotaInfo` 是周配额，也不是。
4. CSV 导出是页面本地行为，不需要后端。

状态：**缺**。
缺口：G1（工作记录表 + 额度口径 + 与任务回读）。额度口径本身是产品裁决项，规则未核。

## 3. /crew/onboard ｜班组入职 ｜`CrewOnboard.vue`、`crew-facts.ts`（`onboardParse`、`readiness`、`yieldSeries`）

数据与动作：
1. 产品列表及其是否有工艺路线（来自黑湖快照）。
2. 口述工艺 → 整理成工序步骤，对照「这款产品黑湖真实报过哪些工序」，列出没对上/漏提的。
3. 出成规则：历史出成率区间（`yieldSeries`），人填标准与容差。
4. 每位数字员工的上岗条件检查（缺哪些资料）。
5. 动作：生成预览进「等我确认」；确认后写入班组配置与入职记录。

后端对应：
1. 近似：`ProductTypeController`（产品）、`ProductWorkProcessController`（产品工序）；字段未核。
2. 近似但不等价：`PlatformAssemblyInterviewController.java:978` `POST /api/platform/factory-assembly/factories/{factoryId}/ai-initialize/converse`（对话建厂，平台账号、工厂级，不是单个产品的口述工序整理）。
3. 近似：`GET M/production/batches/{id}/yield`、`YieldReportController` 有出成率；按「产品 + 两个工序」取历史区间的接口未见。
4. 近似：`ProductConfigurationReadinessController.java:25` `GET M/product-configuration-readiness/{productTypeId}`（SKU 的工艺/BOM 分阶段完整性）。这是「产品配置」完整度，不是「数字员工上岗条件」。
5. 无（班组配置、入职记录不存在）。

状态：**缺**（有 1、3、4 的近似物，但没有页面要的口述整理、上岗条件、入职记录）。
缺口：G1（数字员工配置/入职记录）；G2（产品级口述工艺整理，可复用 factory_assembly 的对话能力，中）。

## 4. /crew/settings ｜数字班组配置 ｜`CrewSettings.vue`、`crew-config.ts`

数据与动作：
1. 本厂启用哪几位数字员工；调整记录（谁、何时、前后对比、原因）。
2. 每位员工管的权限模块；当前身份在这些模块的 rw/r/无。
3. 一线岗位 → 数字员工入口映射（原型代码常量，页面自己写明「不能改」）。
4. 动作：保存启用（必须写原因，要求至少启用一位，回读）。

后端对应：
1. 无。
2. 有：`CurrentPermissionsController.java:26` `GET M/permissions/current`；模块清单 `PermissionRegistryController.java`（`/system/permission-registry/*`）。数字员工与模块的映射 `crew-config.ts` 是常量，无后端。
3. 无。近似：`AiFrontlineGrantController.java:26/31/40` `GET|PUT|DELETE M/ai-frontline-grants[/{roleCode}/{intentCode}]`（一线角色×AI 意图授权，是「AI 能办什么」的真配置，粒度比「启用哪位员工」细）。
4. 无。

状态：**部分**（仅第 2 项；启用开关、调整记录、岗位映射无）。
缺口：G1。

## 5. /crew/issues ｜发现的问题（原主动汇报并入）｜`CrewIssues.vue`、`crew-reports.ts`、`f-api.cjs`、`/__unified/e/api/rules`、`/__unified/a/api/weighin-reconcile`

数据与动作：
1. 五类来源：数字员工汇报（前端按快照现算）/ 规则预警（E 线协同任务规则提醒）/ 对账差异（称重↔黑湖、入库批次↔黑湖）/ 数据缺口 / 数据疑点（出成率≥100% 等）。
2. 动作：汇报转待办（进「等我确认」）；关闭（结论 已处理/误报/接受偏差 + 一句话）；恢复。

后端对应：
1. 部分有（web-admin 已有同形页 `web-admin/src/views/issues/IssuesPage.vue`，`issuesData.ts` 调用如下）：
   - 缺料 `GET M/shortage-cases` → `ShortageCaseController.java:73`（`issuesData.ts:175`）
   - 交付风险 `GET M/delivery-risks` → `DeliveryRiskController.java:70`（`issuesData.ts:187`）
   - 工艺参数偏离 `GET M/quality/process-param-deviation/cases` → `ProcessParamDeviationCaseController.java`（`issuesData.ts:204`）
   - 成本损耗异常 `GET M/cost-deviation-cases`、`/daily-runs` → `CostDeviationCaseController.java:167/152`（`issuesData.ts:216/242`）
   - 冷链超温 `GET M/cold-chain/alerts` → `ColdChainExposureController.java:42`（`issuesData.ts:230`）
   - 数据疑点 `GET M/production/data-suspects` → `ProductionDataSuspectController.java:33`（`issuesData.ts:257`；规则：出成率≥100%、起止相同、单位明显不符，已读方法注解）
   - 已处置列表 `GET M/issues/dispositions` → `IssueDispositionController.java:59`（`issuesData.ts:305`）
2. 有：关闭 `POST M/issues/dispositions/close`（`:80`，结论枚举 HANDLED/FALSE_ALARM/ACCEPTED_DEVIATION，与原型三结论一致，`entity/issue/IssueDisposition.java:33`）；转待办 `POST .../convert`（`:94`）；恢复 `POST .../reopen`（`:107`）。

原型有、后端没有的来源：「数字员工汇报」（现算，无后端）；「规则预警」来自协同任务规则，后端无协同任务（见 §13）；「称重↔黑湖」「入库批次↔黑湖」对账是 Cretas 与黑湖两边比对，黑湖侧只在原型/六膳门快照里，属原型专用，不进正式后端。

状态：**部分**（关闭/转待办/恢复与 7 类问题源都有；缺数字员工汇报与协同任务规则预警两类来源）。
缺口：G1（汇报）；G3（规则预警来源，依赖协同任务）。

## 6. /g/today ｜今天 ｜`frame-g/TodayPage.vue`、`today-data.ts`、`pages.ts`

数据与动作：
1. 今天要办的事（按岗位）：待称重任务、待建批次入库单、临期/过期批次、等我确认、对账差异、规则提醒、交付风险（高/中）、协同任务。
2. 数字员工卡片（同 §1）；发现的问题四类计数。
3. 「我在盯的」指标（临期批次数、对账差异数、数字班组今日办成数）。
4. 动作：问数字班组（原型里只弹说明，没有真执行）；点条目跳转。

后端对应（web-admin 已有同形页 `web-admin/src/views/today/TodayPage.vue`，`todayData.ts` 调用）：
- 待审批 `GET M/workflow/instances/pending|my-created|…`（`todayData.ts:152`）→ `WorkflowInstanceController.java:167/660/725/748`
- 报废待审 `GET M/disposal-records?status=PENDING`（`:160`）→ `DisposalController.java`（类级 `:39`）
- 临期/过期批次 `GET M/material-batches/expiring?days=7`、`/expired`（`:187/188`）→ `MaterialBatchController.java`（类级 `:112`；方法行未取）
- 交付风险 `GET M/delivery-risks?openOnly=true`（`:216`）→ `DeliveryRiskController.java:70`
- 收货任务 `GET M/warehouse/receiving/tasks`（`:244`）→ `warehouse/WarehouseReceivingController.java`（类级 `:49`）
- 任务角标 `GET M/task-badges/sales-orders|unordered-inbound`（`:261`）→ `TaskBadgeController.java:39/46`
- 指派给我 `GET M/issues/dispositions/assigned-to-me`（`:274`）→ `IssueDispositionController.java:66`
- 发现的问题计数 见 §5。

缺：待称重任务/待建批次是「黑湖执行中且无 Cretas 称重」「黑湖入库单未关联批次」，来自黑湖快照对比，属原型专用；后端对应的真实称重待办在 `WorkProcessTaskController`、`WeighingController`（路由存在，字段未核）；协同任务（`web-admin/src/views/today/TodayPage.vue:45` 明确写「协同任务不在迁移范围（M7）」）；数字班组办成数；「问数字班组」交给哪位员工的分派无接口（近似：`AIController` 的对话入口，未核）。

状态：**部分**。
缺口：G1、G3；待称重在正式后端的口径需另核（规则未核）。

## 7. /g/todo ｜待办 ｜`frame-g/TodoPage.vue`、`TodoBlock.vue`、`today-data.ts`

数据与动作：六个页签：等我确认 / 业务待办 / 指派给我 / 待我审批 / 我发起的 / 抄送与已处理。

后端对应：
1. 等我确认：无（数字班组收件箱，见 G1）。近似思路：`PreviewTokenServiceImpl` 的预览-确认流程是另一套（采购受控任务），不是通用收件箱。
2. 业务待办：同 §6 各来源（待称重、待建批次、临期、对账、规则、风险）。
3. 指派给我：有，`IssueDispositionController.java:66`。协同任务也列在这里，后端无（G3）。
4. 待我审批：有，`WorkflowInstanceController.java:167` `GET M/workflow/instances/pending`；原型文案还含「待审批请购、财务调整/报废审批」，后端对应 `PurchaseRequisitionController`、`DisposalController`（`web-admin todayData.ts:160` 已调报废待审），请购待审批路径未核。
5. 我发起的：有，`:660` my-created。
6. 抄送与已处理：有，`:748` copied、`:725` acted。

状态：**部分**（等我确认、协同任务缺）。
缺口：G1、G3。

## 8. /g/settings ｜设置 ｜`frame-g/pages.ts`（`/g/settings`，`SkeletonPage.vue`，「骨架页」）

数据与动作：5 个页签：主数据（条数与缺口、跳转各主数据页）/ 账号与权限（账号、岗位、角色、可用数字员工、功能模块开关）/ 规则与阈值（业务阈值、审批链、毛利红线、排产设置、编码规则、工艺保密、凭证模板）/ 数字班组配置（跳 `/crew/settings`）/ 数据与日志（文件导入、映射复核、数据检查、查询模板、导入导出中心、数据备份；日志列：操作人、对象、时间）。页面本身是骨架，多数是跳转。

后端对应：
- 账号 `UserController.java:266` `GET M/users`；角色 `RoleController.java:90` `GET M/roles`；模块开关 `FeatureConfigController`、`UserModuleAccessController`、`FactoryRoleModuleOverrideController`（路由存在，字段未核）。
- 阈值 `CanvasThresholdsController.java:99/227/280/369`；审批链 `ApprovalChainController`、`ApprovalPermissionController`、`PurchaseApprovalRuleController`；毛利红线 `GrossMarginConfigController`；排产 `LineCapacityConfigController`；工艺保密 `processsecret`（`ProcessSecretPayloadScrubber` 等，Controller 未核）；单位 `UnitContractController`。
- 主数据完整度（条数 + 缺口）：近似 Python `completeness_calculator.py:360` `POST /api/client-requirement/completeness/compute`（见 §22），按模块完整度，不是「物料/客户/供应商条数 + 缺哪几项」，字段未核。
- 数据与日志：上传见 §21，映射复核 §23，数据检查 §22，查询模板 §24；「导入/导出中心」`SmartBIUploadManageController`、`StocktakeBulkImportController` 部分对应；备份 `web-admin/src/api/tenantBackup.ts` 在调，Java Controller 名未核；操作日志 web-admin 有 `api/auditLog.ts`，Controller 未核。
- 数字班组配置：缺（§4）；「可用数字员工」列缺。

状态：**部分**（跳转目标多数有接口；数字班组相关列、主数据缺口汇总缺；多项为规则未核）。
缺口：G1；G4（主数据完整度汇总，小）。

## 9–12. /workflow/pending、/workflow/my-created、/workflow/acted、/workflow/copied ｜审批中心四视图 ｜`home/OaCenter.vue`

数据与动作（四页同一组件，按路径切换）：
1. 列表列：单据号、类型、当前节点、发起/申请人、负责岗位、时间。
2. 数量页签（待我处理/我发起的/已处理/抄送我的 各 N）。
3. 筛选：业务类型、负责岗位（或发起人）、关键词；待我处理页「最早到期」。
4. 动作：原型只读，点「去协同任务办理 / 去报工审批页」跳走；不做审批。
5. 原型数据源是「黑湖报工审批 + Cretas 协同任务」，非真实工作流。

后端对应（正式后端是完整工作流引擎，比原型强，原型数据源本身不进后端）：
- 待我处理 `GET M/workflow/instances/pending`（`workflow/WorkflowInstanceController.java:167`，按用户角色查，支持 `moduleCode`、`page`、`size`）
- 我发起的 `GET .../my-created`（`:660`，按发起人）
- 已处理 `GET .../acted`（`:725`，执行过审批动作的实例）
- 抄送我的 `GET .../copied`（`:748`，通知节点按用户/角色匹配）
- 返回体 `dto/workflow/WorkflowInstancePendingDTO.java`：instanceId、moduleCode、moduleLabel、businessCode、businessSummary、businessAmount、counterpartyName、currentNodeLabel、initiatedAt、initiatedByUsername、status、completedAt、approverRoles，覆盖原型六列。
- 动作（原型没有）：审批/驳回 `POST .../{instanceId}/actions`（`:311`）。
- web-admin 已有 `web-admin/src/views/workflow/{pending,my-created,acted,copied}.vue`。

状态：四页均 **有**。
备注：筛选里只有 `/pending` 支持 `moduleCode`；关键词、岗位、发起人筛选后端无参数（前端本地筛即可，小）；「最早到期」无对应字段（工作流实例无截止日，近似：无）。黑湖报工审批、协同任务两类行在正式后端不存在，属原型专用。

## 13. /collab/tasks ｜协同任务 ｜`collab/CollabTasks.vue`、`e-api.cjs`、`e-store.ts`、`task-types.cjs`

数据与动作：
1. 任务列表（编号、标题、类型、状态 TODO/DOING/DONE/CANCELLED、负责岗位、创建人、截止、优先级、关联单据、超期天数、响应/处理时长）。
2. 视图：全部/我负责的/我发起的/今天到期/已逾期，已存视图（按账号+工作区）。
3. 任务类型（颜色、标题规则、默认负责人、专属字段）；规则提醒（首检未做、任务超期、检验不合格未建任务）与执行日志。
4. 动作：新建、指派、改状态（合法状态流转）、评论、AI 草稿（`tasks/draft`）。

后端对应：无任何对应。后端搜索 `协同任务` / `collab` 无 Controller；web-admin `menuConfig.ts:751` 明确写「协同任务 … 不做（M7）」。近似物（均不等价）：`IssueDispositionController` 的「转成我的待办」（只对问题单，无状态机/评论）；`ScheduledTaskController`（cron 系统任务，不是人工任务）；`InternalNotificationController`（通知）。

状态：**缺**。
缺口：G3（协同任务：任务+评论+状态流转+类型+规则提醒）。

## 14. /analytics/ai-reports ｜AI 经营报告 ｜`home/AiReports.vue`、`d-ai.cjs`

数据与动作：
1. 周报/月报：事实表指标（工单、报工、良品/不良率、成本、库存、销售等），每个数字带来源说明与「依据 N 条」，可点回记录；本期对上期。
2. 模型解读：点击后才请求，不可用时只显示事实表。
3. 动作：切换周/月与前后期；留档（保存快照）；点依据回到记录。

后端对应：
- 列表 `GET M/ai/reports?reportType=`（`AIController.java:358`）→ `AIEnterpriseService.getReportList`；详情 `GET M/ai/reports/{reportId}`（`:387`）；手动生成 `POST M/ai/reports/generate`（`:406`，`daily|weekly|monthly` 且工厂类型为 FACTORY 时走 `generateOperatingReport`，`service/AIEnterpriseService.java:763`，快照落库）。
- 快照结构 `service/reports/OperatingReportSnapshot.java`：sections/metrics（id、label、value、unit、missingReason、coverage、sourcePath）、sources（endpoint、fetchedAt、basis）、insights（受限枚举，模型输出要过结构与指标引用校验，失败有 `MODEL_OUTPUT_INVALID`）。语义与原型「数字带来源、缺失说明、模型只解读」一致。
- web-admin 有 `web-admin/src/views/analytics/ai-reports/index.vue` 与 `api/aiReports.ts:27/29/31`。

缺：原型的「依据 N 条、点开回到逐条记录」后端只有 `sourcePath` 与 `coverage`，没有逐条记录引用（规则未核）；上期对比字段未见；原型指标集（工单计划开始/实际结束、报工不良率）与后端 Collector 的指标集是否一致规则未核（`OperatingReportCollector.java` 未读）。

状态：**部分**。
缺口：G5（逐数字明细记录下钻、上期对比，中）。

## 15. /smart-bi/dashboard ｜经营驾驶舱 ｜legacy-src `views/smart-bi/Dashboard.vue`（web-admin 同名页）

数据与动作：
1. 高管看板（KPI、趋势、洞察）按日期区间。
2. 金表读取（日期范围、KPI 汇总、趋势包）。
3. 动作：改日期区间、经营解读、分享（`/smart-bi/share`）。

后端对应：
1. `GET M/smart-bi/dashboard/executive`、`/executive/custom`、`/executive/insights`、`/executive/insights/custom`：Python `smartbi_compat/api/dashboard_composite.py:451/471`、Java `SmartBIDashboardController.java:156/189/211/319`（Java 与 Python 各有一份）。`web-admin Dashboard.vue:934` 调 `/executive/custom`。
2. `GET /api/smartbi/gold/kpi-summary`、`/gold/trend-bundle`、`/gold/data-range`：`smartbi/api/gold_reads.py`（类前缀 `/gold`，`main.py:958` 挂 `/api/smartbi`）；`web-admin/src/api/smartbi/gold.ts:394/403`、`dataRange.ts:26`。
3. 分享 `/smart-bi/share`：路径存在于页面，后端 Controller 未找到（规则未核）。

状态：**有**（分享一项未核）。

## 16. /analytics/overview ｜分析概览 ｜legacy-src `views/analytics/index.vue`

数据与动作：生产今日、质量、概览、设备四块；趋势；餐饮租户的 restaurant-ops 汇总。

后端对应：`GET M/reports/dashboard/production|quality|overview|equipment|trends`（`ReportController.java:128/149/107/166/198`；页 `index.vue:208-211,268`）。`GET /api/smartbi/restaurant-ops/summary?days=30`（`index.vue:82`）：路由索引与 grep 都没找到该路径（`gold_reads.py` 仅注释提到 restaurant-ops 数据），仅餐饮租户使用。

状态：**部分**（制造业部分有；餐饮汇总接口未找到）。
缺口：G6（核实/补 `restaurant-ops/summary`，小；若不做餐饮租户可不处理）。

## 17. /smart-bi/analysis-hub ｜经营分析 ｜legacy-src `views/smart-bi/BusinessAnalysisHub.vue`

数据与动作：页签 = 财务看板 / 图表推荐 / 销售分析 / 趋势 / 指标中心 / 工厂 KPI；图表洞察、分析对话。（unified 外壳里六膳门走 `home/FinanceLsm.vue`，读黑湖财务口径，原型专用。）

后端对应：
- 财务看板：Python `POST /api/smartbi/financial-dashboard/{generate,batch,analyze}`、`GET .../templates`（`smartbi/api/financial_dashboard.py`，`web-admin api/smartbi/financial-dashboard.ts:136/147/158/169`）
- 图表推荐：`/api/chart/{build,batch,recommend,smart-recommend}`（`smartbi/api/chart.py`；表见 `BACKEND-MERGE-MAP.md` §七）
- 销售分析：`GET M/smart-bi/analysis/sales`（`smartbi_compat/api/analysis_sales.py:1727`）；`/api/smartbi/{factory_id}/sales-preset/*`（`sales_preset.py:94-523`）
- 趋势、指标中心、工厂 KPI：`GET M/reports/kpi-metrics`（`ReportController.java:566`）、`/api/smartbi/factory/{yield-trend,cost-structure,production-fused}`（`factory_production_gold.py:303/409`）、`/api/smartbi/factory-overview/sales`（`factory_overview.py`）
- 分析对话：`POST /api/chat/general-analysis`（`smartbi/api/chat.py:1186`）

状态：**有**（各接口存在，字段与口径规则未核）。

## 18. /analytics/supply-chain ｜供应链概览 ｜legacy-src `views/analytics/SupplyChainOverview.vue`

数据与动作：采购单、原料批次、加工批次、销售订单四类汇总概览（只读）。

后端对应：`GET M/purchase/orders`（`inventory/PurchaseController.java:120`）、`GET M/material-batches`（`MaterialBatchController.java:349`）、`GET M/processing/batches`（`ProcessingController.java:220`）、`GET M/sales/orders`（`inventory/SalesController.java:149`），页内调用 `SupplyChainOverview.vue:132-142`。另有 `SupplyImpactController.java:31/41/52`（disruption/quality/price）。汇总口径在前端算（页只拿列表，分页上限与口径规则未核）。

状态：**有**。

## 19. /analytics/alert-dashboard ｜预警看板 ｜legacy-src `views/analytics/AlertDashboard.vue`

数据与动作：汇总卡片；告警列表（状态/级别筛选、分页）；动作：手动检测、确认、解决（填处理说明）。

后端对应：`GET M/alerts/summary`（`AlertController.java:78`）、`GET M/alerts`（`:41`）、`POST M/alerts/detect`（`:161`，需 `equipment:read_write`）、`PUT M/alerts/{id}/acknowledge`（`:108`，userId 为 query 参数）、`PUT .../resolve`（`:133`）。web-admin `AlertDashboard.vue:139/164/185/207/242`。

状态：**有**。

## 20. /smart-bi/analysis ｜智能数据分析 ｜legacy-src `views/smart-bi/SmartBIAnalysis.vue`

数据与动作：上传历史与表数据；智能 KPI、钻取、跨表分析、同比、统计分析、重命名无意义列、SSE 综合分析、工厂生产融合时序；NL→SQL 问答。

后端对应：`/api/smartbi/analysis-cache/{uploadId}`（`analysis_cache.py`）、`/api/smartbi/cross-sheet-analysis`（`cross_sheet.py`）、`/api/smartbi/yoy-comparison`（`yoy.py`）、`/api/smartbi/nl-to-sql`（`nl_to_sql.py`）、`/api/smartbi/synthesis/comprehensive-stream`（`synthesis.py`）、`/api/smartbi/factory/production-fused`、`M/smart-bi/drill-down`（`smartbi_compat/api/analysis_drilldown.py:723`；Java `SmartBIAnalysisController.java:122`）、`M/smart-bi/uploads/{id}/data`（`SmartBIUploadController.java:619`）。`SmartBIAnalysis.vue` 4462 行，只核对了接口存在，逐接口返回字段规则未核。

状态：**有**。

## 21. /smart-bi/upload ｜Excel 上传 ｜legacy-src `views/smart-bi/ExcelUpload.vue`（与 web-admin 同）

数据与动作：上传（含堆叠表区域识别、异步自动解析与进度）、模板下载、上传历史管理（改名、删除）、字段回填。

后端对应：Python `POST /api/excel/detect-regions`（`smartbi/api/excel.py:370`）、`POST /api/smartbi/excel/auto-parse-async`（`excel_async.py:122`）、`GET .../auto-parse-status/{upload_id}`（`:249`）；Java `SmartBIUploadController.java:140/215/286/330/358`（upload、upload-and-analyze、confirm、sheets、upload-batch）、`GET .../uploads`（`:518`）、`.../uploads/{id}/fields`（`:593`）、`backfill/*`（`:707/729`）、`SmartBIUploadManageController.java:40` DELETE、`SmartBIUploadTemplateController.java:26` 模板下载；上传状态 `GET /api/smartbi/{factory_id}/uploads/list`（`upload_status.py:137`）。改名接口按 `BACKEND-MERGE-MAP.md` §七 PR #1749 已补，本次未单独定位行号。

状态：**有**。

## 22. /smart-bi/data-completeness ｜数据完整度 ｜legacy-src `DataCompletenessView.vue`（与 web-admin 同）

数据与动作：
1. 各模块填充率、字段级完整度、下钻（缺失数由前端按完整度×总数估算）。
2. 校验规则增删改启停。
3. 趋势。

后端对应：
1. `POST /api/client-requirement/completeness/compute`（`client_requirement/completeness_calculator.py:360`，页 `DataCompletenessView.vue:288`；`main.py:1170` 条件挂载，不可用时页面提示服务不可用）。
2. 无：校验规则只存浏览器 localStorage（`DataCompletenessView.vue:350-368`），后端没有规则表，也不参与计算。
3. `GET /api/client-requirement/completeness/trend`（`completeness_calculator.py:376`），页面未见调用。
下钻只显示估算的缺失数，没有返回缺失记录明细的接口。

状态：**部分**。
缺口：G7（校验规则落库并参与计算，中；缺失记录明细下钻，中）。

## 23. /smart-bi/mapping-review ｜字段映射复核 ｜legacy-src `MappingReviewQueue.vue`（与 web-admin 同）

数据与动作：待复核列（列名、识别标准字段、置信度、方法）；标准字段下拉；确认映射并固化（pin）。

后端对应：Python `smartbi_compat/api/mapping_review.py`：`GET M/{factory_id}/smart-bi/mapping-review/pending`（`:63`，读 `smart_bi_mapping_review_queue`，仅 PENDING）、`.../canonical-fields`（`:40`）、`POST .../{upload_id}/confirm`（`:90`，先校验目标必须是受控标准字段）。页面 `MappingReviewQueue.vue:154/185/254`，走 `/smartbi-api` 前缀直连 Python。

状态：**有**。

## 24. /smart-bi/query-templates ｜查询模板管理 ｜legacy-src `QueryTemplateManager.vue`

数据与动作：模板列表/新增/改/删；一键执行（调对话分析）。（注意：unified 外壳里 `/smart-bi/query-templates?view=custom-reports` 被借用为「自定义报表」，见 §26；这里按旧路由的查询模板页核对。）

后端对应：`SmartBiQueryTemplateController.java:44/52/70/86`（GET/POST/PUT/DELETE `M/smart-bi/query-templates`，软删，按 factoryId 隔离）；Python 另有 `smartbi_compat/api/analysis.py:129`、`query_templates_write.py:237/253/270`；执行 `POST /api/chat/general-analysis`（`chat.py:1186`）。页 `QueryTemplateManager.vue:340/367/386/405`。

状态：**有**。

## 25. /unified/reports ｜自定义报表 ｜`home/CustomReports.vue`（外壳别名 `/smart-bi/query-templates?view=custom-reports`）

数据与动作：
1. 选数据源（生产工单、报工、工单成本、味美生产计划）→ 选字段、筛选、分组、聚合（count/sum/avg，同单位才相加，缺失不计）、图表（表/柱/线）。
2. 保存模板；周期（每天/每周/每月）；到期未生成提醒；生成快照（Run）并留档。
3. 动作：新建/编辑/删除模板、预览、生成快照、查看历史快照。

后端对应：
- 模板保存/读取/执行：`SavedViewController.java:41-77` `M/saved-views`（options/list/create/get/update/delete/execute）。但 `service/view/ViewEntityCatalog.java:51/64` 只登记了 `customers`、`suppliers` 两个实体，列出字段、操作符（eq/ne/contains/gt…）和字段级权限；没有生产工单、报工、成本数据源，也没有分组、聚合、图表、周期、快照。
- 参数化报表 `POST M/reports/custom`（`ReportController.java:463`）→ `ProductionReportService.getCustomReport`（内部规则未核）。
- 周期快照：`ScheduledTaskController.java:106-256` 是 cron 系统任务，不存报表快照。
- 数据源「黑湖工单/报工」和味美计划投影是原型专用。

状态：**部分**（模板 CRUD 骨架有，数据源范围、聚合/图表、周期快照缺）。
缺口：G8。

## 26. /quality/vision-training ｜视觉质检训练 ｜`quality/QualityLoop.vue`（仅「只写缺口」说明页，不造数据）

数据与动作：unified 外壳里该页只写缺口；真实页在 web-admin `web-admin/src/views/quality/vision-training/index.vue`（路由 `router/index.ts:942`）。真实页需要：训练模板、配方、预览、草稿项目、训练任务、数据准备（拍照/标注/复核/提交/导出）。

后端对应：Java `vision/FactoryVisionTrainingController.java`（`M/vision-training/` templates:29、recipes:33、preview:37、drafts POST:41 GET:45、drafts/{projectId}:49、plan:53、jobs GET:57 POST:61、preparation:67、preparation/{operation}:72）；平台侧 `PlatformVisionTrainingController.java:27/32/37`；Python `vision_training/preparation_router.py:124-149`（photos/image/review/standard/submit/export，`main.py:1152` 挂载）；另有 `VisionVerifyController.java:55/68`（视觉验证）。

状态：**有**（原型页只写缺口，正式后端与 web-admin 都有）。

---

## 缺口汇总

| 编号 | 缺什么 | 建议落在哪 | 大小 |
|---|---|---|---|
| G1 | 数字班组：员工启用配置 + 调整记录、「等我确认」收件箱（预览→确认→回读）、工作记录与额度（已交办/办成/没办成/不明）、入职记录。须先由 Steve 裁定额度口径 | 新建 `DigitalCrew` Controller + Service（Java），表 4 张；确认执行复用现有业务接口 | 大 |
| G2 | 产品级口述工艺整理（口述→工序步骤，对照该产品历史报工工序） | Python `factory_assembly` 新增产品级接口 + Java 代理；或复用 `ProductWorkProcessController` 数据 | 中 |
| G3 | 协同任务：任务、评论、状态流转、任务类型、规则提醒与执行日志、已存视图。web-admin 已声明 M7 不做，须先确认是否要 | 新建 `CollabTaskController`；视图可复用 `SavedViewController`；或把需求并入工作流/`IssueDisposition` | 大 |
| G4 | 主数据完整度汇总（物料/客户/供应商条数与缺口） | `completeness_calculator.py` 加实体类型，或 Java 新增汇总接口 | 小 |
| G5 | 经营报告每个数字的逐条依据记录下钻、与上期对比 | `OperatingReportCollector` / `OperatingReportSnapshot` 增加明细引用与 prev 字段 | 中 |
| G6 | `/api/smartbi/restaurant-ops/summary` 未找到（仅餐饮租户用） | Python `gold_reads.py` 核实或补接口；制造业不受影响 | 小 |
| G7 | 数据完整度校验规则落库并参与计算；缺失记录明细下钻 | `completeness_calculator.py` + 新表；Java 代理可选 | 中 |
| G8 | 自定义报表：生产工单/报工/成本数据源、分组聚合与图表、周期与历史快照 | 扩 `ViewEntityCatalog` 实体与聚合，新增报表快照表与周期触发（可借 `ScheduledTask` 调度，不是其现有功能） | 大 |
| G9 | 审批中心筛选：`/my-created`、`/acted`、`/copied` 缺 `moduleCode`；关键词、岗位、发起人筛选无参数；无「最早到期」字段 | `WorkflowInstanceController` 加可选参数（或前端本地筛） | 小 |
| G10 | `/smart-bi/share` 分享接口未找到 | 若页面需要，补 Controller；`web-admin/src/api/shareLink.ts` 已存在，应先核是否对应 | 小（规则未核） |

## 未核事项（明确写出，不当作「有」）

- 各 Python/Java 分析接口的返回字段与口径（§17、§20 只确认路由存在）。
- `MaterialBatchController` 的 `expiring`/`expired`、`DisposalController` 列表、`WarehouseReceivingController` `tasks`：确认控制器类存在，方法行号未取。
- 请购待审批入审批中心的路径（§7）。
- 待称重任务在正式后端的口径（§6）。
- `OperatingReportCollector` 指标集与原型指标集是否一致（§14）。
