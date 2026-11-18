# map-C：生产链 15 条路由 页面 → 后端接口 逐页对照

日期 2026-11-18，只读核对，未改仓库任何文件。仓库根：`C:\Users\Steve\my-prototype-logistics\.claude\worktrees\admiring-chandrasekhar-9000f3`。

## 读法与边界（先读）

- Java 控制器目录简写 `C/` = `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/`；所有 Java 路径前缀 `/api/mobile/{factoryId}`，下文省略前缀。web-admin 指仓库根 `web-admin/src`（注意：不是 frontend/web-admin）。
- 原型有两个工作区，同一路由渲染两种页：**六膳门(LSM)** 只读黑湖快照 + Cretas 补充（`unified-v1/src/production/a-api.cjs` 假后端）；**味美(ydwm)** 直接调 Java 风格 REST（`ydwm-a.ts` 的 `yget`）。下文分开列。
- 「黑湖快照」(project/task/output/bom/routing/costs 等)在正式后端 **没有数据通道**：Java 里无黑湖导入类；`backend/python/blacklake/` 是采集+SmartBI 分析包，路由只有 live-monitor、period-reports、smartbi 分析、labor-supplements 等，没有生产页接口。MIGRATION-PLAN 第 0 节 Q6 也写了此前置缺口。所以凡是「LSM 页读黑湖」的数据，下表一律按「正式端对应的原生对象(生产计划/批次/报工)」对照，并在缺口里单列「黑湖数据怎么进」。
- 已读源码：原型各 .vue/.cjs 全文或关键段；Java 控制器映射、`YieldReportRequest`、`WeighingEntryRules`、`DataSuspectService`、`BomPriceAdjustmentController/Entity/Service 头`、`OutsourceDtos`、`ProductionPlanController` 映射、`ProductionPlanDTO` 字段、`ProductionProgressDashboardService` 返回字段、`FactoryMaterialRequisitionController`。web-admin 读了路由表、`WeighPage.vue`、`weighForm.ts`、`api/weighing.ts`、各 hub 页的子页面清单。
- **规则未核**：下列只核了「接口在、字段在」，没有逐条比对规则实现：计划 nextAction 口径、领料状态机、出成率与偏离判定、调价建议生成条件、委外收货建议。凡标「规则未核」处不要当等价。
- 行号：控制器映射行号取自 grep（例 `ProductionPlanController.java:340`）。未给行号的是只读了方法名/目录的项。

---

## 1. /production/plans 生产工单

- 页面名：生产工单（LSM「计划与执行记录」；味美「生产计划」精简视图）
- 原型来源：`unified-v1/src/production/ProductionList.vue`（kind=orders，LSM）；`YdwmProduction.vue`（page=plans，ws=ydwm）；`production/progress.ts`、`mapping.ts`、`supplements.ts`、`FactoryCanvas.vue`（打开画布）
- 需要的数据与动作：
  1. 工单列表：计划号、产品、状态(新建/执行中/已结束/已取消)、计划量、良品(实际量)、单位、计划起止日期、版本(锁定生产版本、用料版本)
  2. 筛选/检索：关键字、状态、产品、单位、批量单号搜索、相对日期范围、四态计数（点计数即切状态）、已存视图、分页
  3. 工期判定：工单进度 vs 时间进度、已超期/落后（前端 `progress.ts` 自算）
  4. 「下一步」文案、锁定版本、报工模式(逐道/免工序)、备注
  5. 闭环状态：领(领料单)、产(已报量)、入(小结/入库状态)
  6. 来源需求：来源类型、销售订单号、客户、交期
  7. 路线对应（工单产品有无当前路线）、详情的上下游：产品→工单→工序任务→逐笔报工→BOM→领退料
  8. 动作：打开画布(跳工序配置)、详情；（味美原页面另有：新建、录入、小结、档案——原型只读）
  9. LSM 补充：Excel 批量补充 BOM 投料工序/产品路线/工序标准（模板、预检、确认、撤销）
- 后端对应：
  1. `GET /production-plans`（`C/ProductionPlanController.java:367`，`PageRequest` 带 page/size/sortBy/keyword/status；service 已应用 keyword 与 status，含 UNFINISHED/ACTIVE，`ProductionPlanServiceImpl.getProductionPlanList` 约 :3235）。字段 `ProductionPlanDTO` 含 planNumber、productTypeName、plannedQuantity、actualQuantity、plannedUnit、plannedDate、expectedCompletionDate、status、selectedWorkflowVersion、selectedBomVersion。
  2. 关键字、状态：同上；日期范围 `GET /production-plans/date-range`(:396)；今日 `/today`(:413)。**无**产品/单位/批量单号/路线对应筛选，无四态计数接口（前端可用 `/status/{status}` :381 四次取，或自算）。已存视图在 web-admin `savedViews` 侧，未核。
  3. 工期：前端用 plannedDate/expectedCompletionDate 自算即可；服务端另有 `GET /production-plans/delivery-warnings`(:435，OVERDUE/URGENT/WARN)。
  4. DTO 已含 `nextAction`、`selectedWorkflowVersion`、`selectedBomVersion`、`skipProcessReporting`、`notes`（规则未核）。
  5. 领：`GET /material-requisitions/by-plan/{planId}`（`C/factory/FactoryMaterialRequisitionController.java:103`）；产：`actualQuantity`；入：`GET /production-plans/{planId}/settlement`(:764)、`/production-summary`(:854)。闭环三态没有单接口，需前端合成。
  6. DTO 有 `customerOrderNumber`、`sourceOrderId(s)`、`sourceCustomerName`；**交期**没有直接字段，要经 `GET /production-plans/{planId}/source-order-ids`(:1482) 再查销售订单，或 `GET /production-plans/by-sales-order/{id}`(:1422)。
  7. 路线对应：无单接口；`GET /product-process-workflows/{productTypeId}`、`/product-work-processes` 可判。上下游：`GET /production-plans/{planId}/document-trace`（`C/ProductionDocumentTraceController.java:44`）。
  8. 画布：web-admin 前端路由跳转，无后端动作。新建 `POST /production-plans`(:124)、`/draft`(:214)、`/{planId}/commit`(:257)、`/batch-from-so`(:152)、`/batch`(:1191)；开工 `/start`(:452)、小结 `/interim-settle`(:636)、结单 `/settle`(:593)、取消 `/cancel`(:907)、复制 `/copy`(:957)、导出 `/export`(:1235)、导入模板 `/import-template`(:1264)、导入 `/import`(:1279)。web-admin 在 `views/production/plans/` 调用 `api/productionPlan`。
  9. LSM 补充 Excel（BOM 投料工序/路线/工序标准）：Java 无同形接口。近似：`GET /bom/workflow-export`(`C/BomController.java:192`，只导出)；SKU 导入 `/product-types/import/*`；工艺与配方 Excel **导入**未找到。
- 状态：**部分**
- 缺口：
  - 列表缺 产品/单位/批量单号/路线对应 筛选与四态计数（小，加查询参数）
  - 闭环三态(领/产/入)与交期无聚合接口（小～中，建议在 `ProductionPlanController` 加列表扩展字段或 `plans/loop-summary`）
  - LSM 黑湖工单数据通道缺（大，见缺口汇总 G1）
  - LSM「补充 Excel 导入」无对应（中，规则未核，见 G12）

---

## 2. /production/material-flow 生产领退料

- 页面名：领料 / 生产领退料
- 原型来源：LSM `supply/FlowPage.vue`（view=supply）；味美 `YdwmProduction.vue`（page=flow，YDWM_ROUTES 分流）
- 需要的数据与动作：
  1. 领料单列表：单号、状态(待备料/拣货中/已拣货/在途/已签收/已关单/已取消)、生产计划号、生成时的用料版本(与计划当前版本核对)、物料行数、需料日期、申请人
  2. 详情行：物料、类别、需求、已发、实耗、退回、单位（各取各字段，不互相代替；缺则显示「未报工」）
  3. 检索：单号/计划号/产品、状态、深链 `?no=`
  4. 退料记录列表
  5. LSM：按产品汇总的 已发/退回/净领用（同产品同单位才合计）、明细行（业务时间、仓库、关联工单）、日期范围、导出 CSV
  6. 动作（味美原页面办理，原型不做）：生成领料、拣货、调拨、签收、关单、退料
- 后端对应：
  1. `GET /material-requisitions`（`C/factory/FactoryMaterialRequisitionController.java:82`，参数仅 status/page/size）；实体含 requisitionNo、productionPlanId、sourceRecipeVersion(规则未核)。
  2. `GET /material-requisitions/{id}`(:96)；`FactoryMaterialRequisitionItem` 有 requiredQty、pickedQty、issuedQty、consumedQty、wastageQty、returnedQty、unit、materialCategory、batchNumbers。字段齐。
  3. **缺**：列表无关键字/计划号/日期筛选，只有 status 与分页；单号深链需前端取全量或用 `/by-plan/{planId}`(:103)。
  4. `GET /production-material-returns`（`C/factory/ProductionMaterialReturnController.java:43`，参数 requisitionId/requisitionNo/page/size；已解析物料名、批次号、需求单号）。
  5. 按产品汇总、净领用、CSV 导出：**无**。
  6. 生成 `POST /material-requisitions/generate`(:39)；`PUT /{id}/start-picking`(:113)、`/confirm-picking`(:123)、`/transfer`(:136)、`/receive`(:145)、`/close`(:154，带 items 即退料并写 `ProductionMaterialReturn`)、`/cancel`(:171)。退料没有独立 POST：靠关单带 items，及报工撤回时 `returnToSourceIfRequisitionClosed`（service 约 :1926）。web-admin：`views/production/material-flow/index.vue`（领料单 + 退料记录两个 tab）。
- 状态：**部分**
- 缺口：领料单列表缺关键字/计划号/日期筛选（小）；缺按物料/产品汇总已发-退回-净领用与导出（中）；无独立「退料」动作入口（只有关单退料、报工撤回退料，规则未核，需确认是否满足 LSM 业务）；LSM 黑湖领退料单来源（G1）。

---

## 3. /production/batches 生产批次

- 页面名：生产批次（LSM 叫「六膳门按工单展示」，内容与 plans 同一列表；味美为批次页）
- 原型来源：`ProductionList.vue`（LSM，同 plans）；味美走原生页（原型内无源码，`public/native` 未收）
- 需要的数据与动作：
  1. LSM：同第 1 节（工单当批次）
  2. 味美：批次列表、详情、开工/暂停/恢复/完工/取消、时间线、工序任务、WIP、出成率
- 后端对应：
  1. 同第 1 节。
  2. `GET /processing/batches`(`C/ProcessingController.java:220`)、`/batches/{id}`(:171)、`/batches/lookup`(:186)、`/batches/{id}/timeline`(:257)、`POST /processing/batches`(:75)、`/start`(:91)、`/pause`(:108)、`/resume`(:123)、`/complete`(:137)、`/cancel`(:157)；批次级 `GET /production/batches/{id}/yield`、`/wip`（`C/YieldReportController.java`，前缀 `production/batches/{batchId}`），`/production-batches/{id}/workflow-runtime`（`C/ProductProcessWorkflowRuntimeController.java:27`）。web-admin：`views/production/batches/list.vue`、`detail.vue`（路由 router/index.ts:343、349）。
- 状态：**部分**（正式端批次对象齐全；规则未核；LSM「工单当批次」的对象命名要 T2 决定，MIGRATION-PLAN 已写「按工厂显示对象名」）
- 缺口：列表筛选参数未逐项核对（规则未核）；黑湖工单数据通道（G1）。

---

## 4. /production/approval 生产报工（LSM 只读）/ 报工撤回审批（味美）

- 页面名：LSM「生产报工 · 只读 / 审批在黑湖办理」；味美「报工撤回审批」；web-admin 为「报工审批 + 撤回审批」hub
- 原型来源：LSM `ProductionList.vue`(kind=reports，含 `DefectCapture.vue`、`RecordDetail.vue`)；味美 `YdwmProduction.vue`(page=reversal)；`a-api.cjs` 的不良项采集、`shift-hours`
- 需要的数据与动作：
  1. LSM 报工列表：报工ID、来源工单、工序、任务ID、良品、单位、审批状态、人工成本（工价规则）、起止时间；批量报工ID搜索、报工结束时间范围
  2. LSM 动作：报工详情；不良品项采集（按 E 线字典分摊，不超过黑湖不良数，可撤销留痕）；按班次规则算有效工时/加班
  3. 味美撤回审批列表：类型(批次撤回/小结撤回)、对象、范围、状态、原因、回退流水数、申请人、审批人、申请/审批时间
  4. 动作：批准、驳回（原型在原页面办理）
- 后端对应：
  1. 报工列表：`GET /process-work-reporting/reports`（`C/ProcessWorkReportingController.java:85`）、`/reports/{id}`(:104)、`/pending-approval`(:39)、`/summary`(:135)、`/by-task/{taskId}`(:143)。字段、日期与批量筛选**规则未核**。
  2. 审批：`PUT /process-work-reporting/{id}/approve`(:51)、`/reject`(:62)、`/batch-approve`(:75)。不良项采集：Java 只有单个 `defectQuantity`（`YieldReportRequest`）；`QualityDefectController` 是质检不良记录，**没有**「按字典项分摊到某条报工」；报工核对修正 `POST /production-report-corrections/reports/{reportId}`（`C/ProductionReportCorrectionController.java:45`）是另一回事。班次规则/加班：`ProductionAnalyticsController` 有 `efficiency/hours`(:132)，是否含班次拆分**规则未核**。人工成本：`ProductionReport.laborSegments` 存在，工价规则未核。
  3. `GET /reversals`（`C/ReportReversalController.java:81`）+ `GET /production-plans/interim-settle-reversal-requests`(`ProductionPlanController.java:724`)，原型即同时合并这两类。
  4. 批次撤回批准/驳回 `PUT /reversals/{logId}/approve`(:95)、`/reject`(:115)；小结撤回 `POST /production-plans/interim-settle-reversal-requests/{id}/approve`(:684)、`/reject`(:703)；发起 `POST /processing/batches/{id}/reversal`(:61)、`/production-plans/{planId}/interim-settle/reverse`(:655)。web-admin：`views/production/approval-hub/index.vue`（报工审批 + 撤回审批）。
- 状态：**部分**
- 缺口：不良项按字典分摊到报工（中，G5）；LSM 黑湖报工通道（G1）；人名解析 `users` 在原型另调，正式端 DTO 是否直接带人名规则未核。

---

## 5. /production/weigh 称重报工（重点）

- 页面名：称重报工
- 原型来源：`unified-v1/src/production/WeighIn.vue`（全文已读）+ `a-api.cjs` 第 7 节 weighins 系列（saveWeighin/revokeWeighin/taskSummary/reconcileView/weighingSummary/suspectsOf）；`YieldStandard.vue` 的标准出成率
- 需要的数据与动作（逐项）：
  1. 选任务：默认只显示执行中任务，可搜工单/产品/工序；「今天」计划的工单排前
  2. 逐笔称重录入：投入重量、良品毛重、皮重、不良、副产品多行（要选产品目录里的产品）、起止时间（北京时间，默认=上一笔结束）
  3. 计算：良品净重 = 毛重 − 皮重；工序出成率 = 良品净重 ÷ 投入；单位 kg 两位小数
  4. 校验：投入>0；皮重≤毛重；皮重两位小数；良品+不良+副产品 ≤ 投入(+0.005)；结束晚于开始；开始/结束不得晚于现在 5 分钟；不良项须在字典里且适用于本工序；副产品须在产品目录
  5. 本任务逐笔列表 + 汇总：笔数、投入合计、良品合计、不良、副产、工序出成率、起止
  6. 数据疑点：出成率≥100%、开始=结束、重量≥5000kg 或 投入<0.1kg，只标不改，附「找谁核」
  7. 对比标准出成率：状态 低于标准/高于标准/在范围内/未设标准，偏离百分点
  8. 撤销一笔称重（原因必填，留痕，不计入合计）
  9. 整单出成率 = 路线最后一道良品 ÷ 第一道投入，缺哪道写缺；与黑湖报工并排对账（单位非 kg 不比）
  10. 给入库带出良品净重（`weighins/inbound`）；给成本取汇总（`weighing-summary`）
  11. 同一任务可多笔，每笔只累加，不把任务置完工
- 后端对应：
  1. **部分**：`GET /processing/batches-with-clerk-wip`(`C/ProcessingController.java:197`，web-admin `WeighPage.vue:58` 用它选批次)；`GET /production/batches/{id}/work-process-tasks`（web-admin `api/processProduction.getBatchWorkProcessTasks`）。「按今天计划排序/执行中任务总览」无专门接口；MIGRATION-PLAN 2.2 也写「今日待称重任务聚合要新增」。
  2. `POST /production/batches/{batchId}/reports`（`C/YieldReportController.java` 的 submitReport，`YieldReportRequest`：inputQuantity、outputQuantity、**tareWeight**、defectQuantity、laborSegments[startTime/endTime HH:mm]、businessDate、clientRequestId、markComplete、byproducts[name,quantity,unit]、wasteQuantity）。皮重落 `ProductionReport.tareWeight`（`entity/ProductionReport.java:271`）。毛重不入库，`outputQuantity` 存净重（与原型口径一致）。web-admin `WeighPage.vue` 实际只发：投入、净重、皮重、不良(单个 kg 数)、起止时间、日期；**不发副产品**。
  3. 皮重与净重由前端算、服务端 `WeighingEntryRules.assertTare` 兜底（负数、>2 位小数、非质量单位、净重为负均 400）。工序出成率：`GET /production/batches/{id}/yield`。
  4. 时间≤现在+5 分钟：`WeighingEntryRules.assertNotLaterThanNow`（`service/yield/WeighingEntryRules.java`，含夜班跨零点回放 12 小时，比原型更细）。**未见**：产出(良品+不良+副产)≤投入 的服务端校验、结束晚于开始（跨零点视为次日，不报错）、不良项字典校验、副产品须在目录——这些在 `YieldReportServiceImpl.submitReport` 里是否有只读到片段，**规则未核**（该文件有 超收预检 `GET /yield/limits`，`YieldReportController`，那是另一口径）。
  5. `GET /production/work-process-tasks/{taskId}/weighings`（`C/ProductionDataSuspectController.java:43`）返回每笔 reportId、input/output、tareWeight、defectQuantity、laborSegments、suspects、whoToCheck。字段齐；**汇总(合计/出成率)** 由 web-admin 前端算（`WeighPage.vue` totals）。
  6. `GET /production/data-suspects?days=`（同控制器 :33）+ `DataSuspectService`（`DataSuspectRules`，阈值可配 `ThresholdKeys.PRODUCTION_DATA_SUSPECT_*`，默认 5000/0.1kg）。判据含出成率≥100%、起止相同、单位不符。**有**。
  7. 标准：`WorkProcess.standardYieldMin/Max`、`ProductWorkProcess.standardYieldRate`（entity 字段）；超标判定 `YieldReportServiceImpl` 约 :843（BELOW_MIN/ABOVE_MAX）并走参数偏离案件。形态与原型不同：原型是「标准率 + 允许偏离百分点」，Java 是 min/max 区间；称重页**未展示**对比标准。**部分**。
  8. 撤销单笔称重：**无**。只有整批 `POST /processing/batches/{id}/reversal`(`ReportReversalController.java:61`，需审批，整单)和核对修正(`ProductionReportCorrectionController`，需权限码 `production_report_correction:read_write`，是改值不是撤销)。
  9. 整单出成率：`GET /production/batches/{id}/yield` 返回 `BatchYieldDTO`（整批+单工序）；是否「末道良品÷首道投入」**规则未核**。与黑湖对账：**无**（`backend/python/blacklake/stock_reconciliation.py` 是库存对账，不是称重对账，未核）。
  10. 入库带出净重：`POST /production-plans/{planId}/warehouse-receipt`(`ProductionPlanController.java:888`) 与 `/settlement-prefill`(:813)；是否带最后一道净重**规则未核**。成本取称重汇总：`GET /production/batches/{batchNumber}/yield-summary`(`C/ProductionBatchCostController.java:61`)。
  11. `markComplete:false` 即多笔累加，**有**（`YieldReportRequest.markComplete` 注释；web-admin 固定传 false）。幂等 `clientRequestId`，**有**。
- 状态：**部分**
- 缺口（称重重点）：
  - 副产品多行且来自产品目录：Java 有 byproducts 但是自由文本 name，页面不发；目录约束缺（中，G4）
  - 不良按字典项分摊：缺（中，G5，依赖 E 线不良项字典，目前 Java 只有 `QualityDefect`/`DefectType` 枚举）
  - 撤销单笔称重（原因必填、留痕、重算合计）：缺（中，G6，要新增报工级撤销，不能复用整批撤回）
  - 与黑湖报工并排对账、整单出成率口径：缺/规则未核（大，G1/G7）
  - 标准出成率「率+允许偏离」与区间模型的映射，称重页展示偏离状态：缺（小，G8）
  - 待称重任务总览（按今天排序、执行中且尚无称重）：缺（小，G9）
  - 服务端「产出≤投入」「结束晚于开始」校验：规则未核（若无则补，小）

---

## 6. /production/analysis-query 生产分析

- 页面名：生产分析
- 原型来源：`production/ProductionAnalysis.vue`（LSM）；味美走原生分析页
- 需要的数据与动作：
  1. 工单状态筛选；共 N 张
  2. 工期状态计数（已超期/落后/正常…）
  3. 按产品+单位汇总：工单数、计划量、良品、良品÷计划（计划缺失不算）
  4. 近 14 天报工笔数柱图
  5. 全由代码算、不调模型
- 后端对应（`C/ProductionAnalyticsController.java`，前缀 `production-analytics`）：
  1. `/dashboard`(:60)；`/daily-trend`(:70)；`/by-product`(:80)；`/by-process`(:90)；`/efficiency/*`(:102-142)；`/budget-vs-actual`(:152)。入参、返回字段**未逐项读**，规则未核。
  3. 按产品汇总：`/by-product` 与 `GET /reports/production-by-product`（web-admin 在 `views/production/analysis` 用）。
  4. 日趋势：`/daily-trend`，是否为「报工笔数」口径规则未核。
  2. 工期状态计数：无专门接口，前端可由 `production-plans` + 日期算。
  web-admin：`views/production/analysis/index.vue`（hub：车间实时生产报表、生产数据分析、BOM达成率、工序投入产出对比、人效双口径、成品出厂核算）。
- 状态：**部分**（接口族在、字段口径规则未核）
- 缺口：工期状态计数口径（小）；黑湖通道（G1）；其余需逐接口核字段（规则未核）。

---

## 7. /production/kanban 状态看板

- 页面名：状态看板（正式端叫生产看板）
- 原型来源：`production/StatusKanban.vue`
- 需要的数据与动作：
  1. 4 列：新建/执行中/已结束/已取消，每列工单卡片（计划号、产品、计划量、良品、工单进度、工期标签）；每列显示前 30 张
  2. 点卡片跳工单详情；卡片只读，不可拖动
- 后端对应：
  1. `GET /production-plans/status/{status}`（`ProductionPlanController.java:381`）按状态取；或 `GET /production-plans?status=`；`/production-plans/pending-execution`(:1176)。字段见第 1 节。注意 Java 状态枚举(PENDING/PLANNED/IN_PROGRESS/PAUSED/STOPPED/COMPLETED/CANCELLED)与原型 4 态要映射（原型 ydwm-a.ts 已有 PLAN_STATUS 表）。
  2. web-admin 看板 hub：`views/production/kanban/index.vue`（未完成计划队列、备货看板 `GET /restock-board/horizon`、交货预警 `/production-plans/delivery-warnings`），**不是**原型的 4 列状态泳道。
- 状态：**部分**（数据有；4 列泳道视图正式端没有现成页，前端工作）
- 缺口：4 列泳道视图（前端，小，数据接口已够）。

---

## 8. /dashboard/production-progress 车间大屏

- 页面名：车间大屏
- 原型来源：`production/ShopFloor.vue`
- 需要的数据与动作：
  1. KPI：执行中工单数、已超期/落后数、当日报工笔数、未开始工单数
  2. 执行中工单进度表：计划量、良品、工单进度、时间进度、工期
  3. 最近 8 条报工：结束时间、来源工单、工序、良品
- 后端对应：
  1–2. `GET /dashboard/production-progress?date=`（`C/ProductionProgressDashboardController.java:43` → `service/production/ProductionProgressDashboardService`）。返回 `summary`（totalPlans、totalWorkOrders、completed/inProgress/pendingWorkOrders、overallProgressPct、总计划/总已报量）和 `plans[]`（planNumber、productName、customerName、plannedQuantity、reportedQuantity、progressPct、status、processes[工序级 planned/reported/pct/status]）。**缺**：时间进度、已超期/落后判定、当日报工笔数、最近报工列表。
  web-admin：`views/dashboard/production-progress.vue`（router/index.ts:194），调用 `/dashboard/production-progress`（有 `dashboard` 子路径封装，细节未核）。
- 状态：**部分**
- 缺口：时间进度/超期落后（小，可前端用 plannedDate 自算）；最近报工与当日笔数（小，可从 `process-work-reporting/reports` 取，但未核时间筛选）。

---

## 9. /production/wip 在制品与效率

- 页面名：在制品与效率
- 原型来源：`production/WipMatrix.vue`（在制 tab、效率 tab）、`efficiency.ts`、`a-api.cjs` 第 2、3、5 节（standards、efficiency、shift-rules、shift-hours）
- 需要的数据与动作：
  1. 工单×工序矩阵：格子=已批准良品/任务计划量，状态(完成/进行中/未开始)，「停在」哪道，堵点工序 Top3；工序列按路线顺序合并；只看执行中、可搜
  2. 效率：按工序的标准工时、有效工时(黑湖)、有效工时(按班次规则)、实际效率、达标率；单位不一致/无标准/无有效工时如实标注；只到工序不排个人
  3. 动作：补充/修改/撤销 标准工时（秒/单位，原因必填，留痕）；配置/撤销 班次规则（班次、休息）
- 后端对应：
  1. 矩阵：批次级 `GET /production/batches/{id}/wip`(`YieldReportController`)、`GET /wip/available`(`C/ReportReversalController.java:149`)、`GET /processing/batches-with-clerk-wip`(`ProcessingController.java:197`)、`GET /production/batches/{id}/work-process-tasks`。**无**「工单×工序」跨批次矩阵接口；MIGRATION-PLAN 写「`/warehouse/wip-batches` 待核是否同义」（未核）。
  2. 效率：`GET /production-analytics/efficiency/dashboard|workers|trend|hours|cross`(`ProductionAnalyticsController.java:102-142`)、web-admin `labor-efficiency/compare`。效率口径是「标准工时÷有效工时」吗、是否有按工序标准秒/单位：只确认 Java **没有**「标准秒/单位」字段（grep entity 未命中），**规则未核**，倾向缺。
  3. 标准工时与班次规则的维护：**无**。
- 状态：**部分**
- 缺口：工单×工序在制矩阵（中，G10）；标准工时（秒/单位）与班次规则的存储与维护接口（中，G11；MIGRATION-PLAN 未覆盖）；效率口径对齐（规则未核）。

---

## 10. /production/process-hub 工序配置

- 页面名：工序配置（工艺目录 · 画布 · 节点用料 · BOM tab）
- 原型来源：LSM `production/FactoryCanvas.vue`、`LocalWorkflow.vue`、`supplements.ts`、`ProjectExtras.vue`（工单的委外与操作记录）；味美原生工艺画布
- 需要的数据与动作：
  1. 工单执行画布：路线工序节点、投入/产出口、可报工、节点挂标准用料(BOM)，待指定工序的 BOM，来源标注（黑湖/Cretas 补充）
  2. 本地工艺草稿编辑：节点/边增删、用料量、撤销、导入来源参考、保存草稿、发布生产版本、版本对比、激活生产版本、与实际对照
  3. 工序目录列表、标准用料总表
  4. 工单的委外任务与操作记录（只读）
- 后端对应：
  1. `GET /product-process-workflows/{productTypeId}`(`C/ProductProcessWorkflowController.java:78`)、`/producing`(:110)；BOM 树 `GET /bom/tree/{productTypeId}`(`C/BomController.java:74`)；`GET /production-batches/{id}/workflow-runtime`。
  2. 草稿 `PUT /product-process-workflows/{productTypeId}/draft`(:156)、发布 `/publish`(:168)、`/publish-and-activate`(:194)、版本 `/versions`(:135)、`/versions/{v}`(:144)、激活 `PUT /{workflowId}/activation`(:215)、`DELETE /activation`(:228)、BOM 同步预检 `/bom-sync-preflight`(:183)、`POST /resolve-by-outputs`(:91)。
  3. `GET /work-processes`、`/product-work-processes`（`C/WorkProcessController.java`、`C/ProductWorkProcessController.java`）；用料 `GET /bom/recipes`、`/bom/recipes/by-product/{id}/current`(`BomRecipeController.java:77,127`)。web-admin：`views/production/process-hub/index.vue`（工序管理 + 产品-工序配置 两 tab，router :589）。
  4. 工单的委外：`GET /outsource-orders/process-links?productionPlanId=`(`C/OutsourceOrderController.java`)；操作记录：无同形接口（黑湖操作日志），`ClerkProcessEntryController` 的 `/process-entry` 是录入不是日志，未核有无审计日志查询。
  补充 Excel 路线/BOM 投料工序/标准：无导入（见第 1 节）。
- 状态：**部分**
- 缺口：Excel 导入补充（中，G12）；黑湖操作日志只读（大/不建议，属黑湖通道 G1）；画布对「黑湖来源只读」的展示没有意义，正式端用原生数据即可（原型专用部分，不进后端）。

---

## 11. /production/yield-standard 标准出成率

- 页面名：标准出成率
- 原型来源：`production/YieldStandard.vue`（LSM，读写 a-api yield-standards）
- 需要的数据与动作：
  1. 按工序的标准出成率（0~150%）、允许偏离(个百分点)、设置人/时间/原因，已撤销/被取代历史
  2. 动作：新增/修改（原因必填，版本号并发保护）、撤销（留痕）
  3. 实际出成率按工单对比（来自称重）
- 后端对应：
  1. 存储：`WorkProcess.standardYieldMin/Max`(`entity/WorkProcess.java:66-71`)、`ProductWorkProcess.standardYieldRate`(`entity/ProductWorkProcess.java:114`)、成品级 `bom_recipes.overall_yield_rate`。**没有**「标准率 + 容差」与历史留痕的单模型；也无历史/撤销。
  2. 维护：`PUT /work-processes/{id}`(`WorkProcessController.java:76`)、`PUT /product-work-processes/{id}`；成品级 `POST /bom/yield-estimate/recalculate-apply`(`BomController.java:233`)、预览 `/recalculate-preview`(:165)、建议 `/yield-suggestions`(:246)。web-admin `views/production/YieldStandardView.vue`（成品配方级，只读建议列 + 人工保存，权限见其注释）。**口径不同**：正式页是「成品整单」标准，原型是「工序级」标准；两者哪个为准未裁决。
  3. 实际：`GET /production/yield/by-process`(`C/YieldAnalysisController.java:27`)、`/production/orders/{orderId}/yield-summary`(`C/OrderYieldController.java:35`)。
- 状态：**部分**
- 缺口：工序级标准的「率+容差+历史+撤销」（中，G8，需先裁决与 min/max、成品级三者的关系）；是否允许 ≥100%（原型允许到 150%，Java 范围未核）。

---

## 12. /system/products 产品 / SKU 管理

- 页面名：产品（产品、工艺与BOM）
- 原型来源：`ProductionList.vue`（kind=products，LSM）；味美原生页
- 需要的数据与动作：
  1. 列表：产品编码、名称、单位、关联工单数、BOM 条目数；搜索、单位筛选、分页
  2. 详情（上下游：产品→工单→…→BOM）；深链 `?product=`
  3. 动作：（LSM 只读）；味美原生有新建/编辑/导入等
- 后端对应：
  1. `GET /product-types`(`C/ProductTypeController.java:264`)、`/options`(:293)、`/active`(:307)、`/search`(:347)、`/{id}`(:225)；`/bom/products-with-bom`(`BomController.java:132`)。关联工单数/BOM 条目数无聚合字段（BOM：`products-with-bom`）。
  3. 新建 `POST /product-types`(:151)、`PUT /{id}`(:170)、`DELETE`(:212)、导入模板/预览/确认(:68,80,92)、`/assemble-sku`(:106)、`PUT /{id}/daily-capacity`(:186)。web-admin：`views/system/products/index.vue`（router :1998）。
- 状态：**有**（关联工单数等聚合字段规则未核，属展示项，小）
- 缺口：仅「关联工单数」类聚合（小，可选）。

---

## 13. /production/bom/ecns 工程变更通知

- 页面名：工程变更通知
- 原型来源：LSM `production/EcnGap.vue`（占位页：说明黑湖无此数据，**不造数据**）；味美走编译页原生
- 需要的数据与动作：
  1. LSM：无数据，只写缺口并导向 BOM 页签/工序配置
  2. 味美：ECN 列表、详情、申请、提交审批、批准/驳回、通知受影响岗位、影响范围计算、到期生效
- 后端对应（`C/EcnController.java`，前缀 `bom/ecns`）：创建 `POST`(:56)、详情 `GET /{ecnId}`(:66)、`/submit`(:75)、`/approve`(:84)、`/reject`(:94)、`/notify`(:105)、`/calculate-impact`(:115)、`/activate-due`(:125)、列表 `GET`(:137)。web-admin：`views/production/bom/EcnList.vue`（`api/ecn`，router :455）。
- 状态：**有**（Java 全流程在；LSM 占位页是**原型专用**，不应进后端；生效版本与 BOM/工艺版本的衔接规则未核）
- 缺口：无（LSM 若要用，也是用同一套原生 ECN，不是黑湖数据）。

---

## 14. /production/bom/price-adjustments BOM 调价建议（重点）

- 页面名：BOM调价建议
- 原型来源：`unified-v1/src/cost/BomPriceAdvice.vue`、`cost/bom-price-lsm.cjs`（compute，全文前 150 行已读；服务端路径 `/__lsm/product-cost/bom-price`）；味美侧只给精简视图链接
- 需要的数据与动作：
  1. 原料价变动识别：每个料(编码+单位)的采购价日序列(采购订单 + 工单领料价)，最新 vs 此前加权均价，变动≥1% 为「变」，单点/不变/极端值(≥3 倍)标「先核价」
  2. 影响产品：每产品每单位用量×价差=成本影响；上游半成品成本传导；同单位用量>3 视为口径未对齐不计入
  3. 调价建议：按客户价目表算「保毛利额/保毛利率」建议价，标来源、不出建议的原因
  4. What-if：选料、输入新价，逐产品算影响与售价
  5. 缺口清单（缺什么写什么）、来源版本、采集时间
  6. **只作建议：没有批准、保存、改价按钮**，不改价目表、不改配方行单价
- 后端对应：
  - Java 同名功能是**另一件事**：`C/BomPriceAdjustmentController.java`（前缀 `bom/price-adjustments`）管的是「采购价变动 → 配方行单价」。`GET`(:46)按状态分页；`GET /{id}`(:57)；`POST /check`(:65，手工触发)；`POST /{id}/approve`(:80)、`/reject`(:90)。实体 `BomPriceAdjustmentProposal`：recipeId/recipeItemId/productTypeId/productName/materialTypeId/materialName、currentUnitPrice、proposedUnitPrice、deltaAmount、deltaPercent、affectedProductCount、status(PENDING/APPROVED/REJECTED)、sourceType(PURCHASE_RECEIVE/MANUAL_CHECK)；生成在收货时 `generateFromReceive`，**批准会把行价写进 BOM 配方**（`BomPriceAdjustmentServiceImpl` 头注释：D-S5-10/11，建议价=物料档案移动均价，价变则 409 STALE）。权限 `approval_bom_price_adjustment:approve`。web-admin：`views/production/bom/PriceAdjustmentList.vue`（`api/bomPriceAdjustment`，router :463）。
  - 逐项对应原型需求：
    1. 价变动识别：Java 只在**收货**触发或手工 check，且只看物料档案价；无价格序列、无「≥1% 才算变」「极端值先核价」。→ 缺
    2. 产品成本影响：只有 `affectedProductCount`（受影响配方数），**无**逐产品成本影响额/百分比、无半成品上游传导、无用量口径异常拦截。→ 缺（相近只读：`GET /bom/cost-summary/{productTypeId}`(`BomController.java:108`)、`/cost/three-price-comparison`(`ThreePriceComparisonController.java:43`)，不是调价）
    3. 售价/客户价目表建议（保毛利额、保毛利率）：**无**。相关数据在 `C/sales/CustomerPriceListController`(`/customers/{id}/price-list`)、`CustomerPriceHistoryController`、`inventory/PriceListController`，但没有「按成本变动算建议价」的计算（grep keepMargin/建议价关键词在 Java 与 Python 均无命中）。→ 缺
    4. What-if：无。→ 缺
    5. 缺口清单/来源版本：无。→ 缺
    6. 「只作建议」：与 Java **语义冲突**——Java 有批准/驳回且批准写库；原型明确无写动作。两者要拆成两页/两条语义，或裁决二选一（需 Steve 拍板，见缺口 G3）。
- 状态：**部分**（只有「采购价→BOM 行价提案+审批」这一半；原型的「成本影响+售价建议+what-if」整块缺）
- 缺口：见缺口汇总 G2、G3（大）。规则未核：Java 提案生成的边界条件（只对 DRAFT/ACTIVE 配方版本生成，归档版本不回写）未与原型规则对照。

---

## 15. /lsm/outsource 委外收发（重点）

- 页面名：委外收发（黑湖只读）
- 原型来源：`unified-v1/src/supply/LsmExtras.vue`（outsource 配置，约 :83-86）、`SupplyRecords.vue`、`supply-lsm.cjs`、`supply-ai.cjs`；工单内委外 `a-api.cjs` projectExtras（第 4 节）
- 需要的数据与动作：
  1. 委外任务列表：任务编码、委外方、产品、计划量、已发料、实收、退回、单位（计划/已发/实收/退回分列，不互相代替）
  2. 检索：任务号/产品/委外方、批量单号、日期范围、已存视图、分页、导出 CSV
  3. 详情 tab：发料/退料记录、收货/退货记录（动作、数量、单位、业务时间）
  4. 工单详情里带出本工单的委外任务及收发；整单委外无工单号的单独列「未关联」
  5. 解读（AI 对象说明，系统事实，不调模型）
  6. 全部只读；写操作在黑湖
- 后端对应（`C/OutsourceOrderController.java`，前缀 `outsource-orders`，权限 production 读/写）：
  1. `GET /outsource-orders`（参数 supplierId、status[]、keyword、page、size）→ `OrderSummary`：orderNo、supplierName、status、expectedReturnDate、lineCount、plannedQuantity、receivedGoodQuantity、outsourceBalance、feeAmount。**缺**列表层的「已发料」「退回」「单位」「产品」（在 detail 的 lines/balances 里：`LineView.returnedGoodsQuantity`、`BalanceView.issuedQuantity/currentQuantity/unit`）。
  2. 检索：keyword/supplier/status 有；**无**日期范围、批量单号；已存视图/CSV 导出无。
  3. `GET /outsource-orders/{orderId}` → `OrderDetail.movements[]`：movementType、bizDate、批次、quantity、defectQuantity、unit、unitCost、amount、reason；与原型 发料/退料/收货/退货 动作一致，规则未核（movementType 取值未读）。
  4. `GET /outsource-orders/process-links?productionPlanId=`：工单下的委外工序（orderNo、lineStatus、plannedQuantity、receivedGoodQuantity、feeAmount）。「整单委外无工单」= `outsourceType=WHOLE` 的行。
  5. 解读：无后端，前端。
  6. 正式端是**原生闭环（有写）**：`POST`创建(:...)、`/{id}/release`、`/cancel`、`/close`、`/lines/{lineId}/issue`、`/material-return`、`/receive`、`/goods-return`、`/loss`、`GET /lines/{lineId}/receive-suggestion`；设计见 `docs/specs/outsource-closed-loop-design.md`（未读）。web-admin：`views/production/outsource/index.vue`（`api/outsource`，router :386，有原型分组侧栏入口）。
- 状态：**部分**（原生闭环齐，且超出原型只读范围；但原型的黑湖委外数据无通道，列表字段与检索与原型有差）
- 缺口：列表补 已发料/退回/产品/单位 聚合字段（小）；日期范围与批量单号检索、CSV 导出（小）；黑湖委外任务/收发记录的读取通道（G1，大）；注意口径冲突——原型「黑湖只读、Cretas 不写」与 Java「原生委外闭环」是两种产品定位，需 Steve 裁决 LSM 委外是镜像黑湖还是迁到原生（G13，规则/产品问题）。

---

# 缺口汇总

| 编号 | 缺什么 | 建议落在哪 | 估计大小 |
|---|---|---|---|
| G1 | 黑湖只读数据（工单/任务/报工/领退料/采购订单/客户价目/委外/操作日志）进正式后端的通道与表；Java 0 处 blacklake，Python 黑湖包路由没挂进主进程；所有 LSM 只读页依赖它 | 新增 Python `blacklake` 读取路由 + Java 读代理；或 Java 新读模型。先要 Steve 定 MIGRATION-PLAN Q6 | 大 |
| G2 | BOM 调价「原料价变动→产品成本影响→售价建议」：价格序列、变动/极端值判定、逐产品影响额、上游传导、保毛利售价建议、what-if、缺口清单 | `BomPriceAdjustmentService` 旁新增只读 `GET /bom/price-impact`（或放 Python smartbi/成本包），读 采购价、领料价、`CustomerPriceList` | 大 |
| G3 | 「只作建议」与 Java 批准写库语义冲突，需裁决：原型建议页与现有提案审批页是否拆成两个入口 | 产品裁决；落地时 `BomPriceAdjustmentController` 保留审批，新增只读建议端点 | 小（裁决）+ 随 G2 |
| G4 | 称重副产品多行且限产品目录 | `YieldReportRequest.Byproduct` 加 `productTypeId` 并在 `YieldReportServiceImpl` 校验；web-admin `WeighPage` 补表单 | 中 |
| G5 | 称重不良按字典项分摊（defectId+kg，适用工序，≤黑湖/总不良） | 新增报工不良明细表+校验，对接 E 线不良项字典（现仅 `QualityDefect`/`DefectType` 枚举） | 中 |
| G6 | 撤销单笔称重（原因必填、留痕、重算合计、不回写） | `ProductionReportCorrectionController` 旁新增报工级撤销，或复用冲销模型；不能用整批 `reversal` | 中 |
| G7 | 整单出成率口径（末道良品÷首道投入）与「与黑湖并排对账」；`getYield` 是否同口径未核 | `YieldReportServiceImpl.getYield` 核对；对账放 G1 之后 | 中～大 |
| G8 | 工序级标准出成率「率+允许偏离+历史+撤销」，及称重页偏离状态展示；与 `WorkProcess` min/max、成品级 `overall_yield_rate` 三者关系待裁决 | `WorkProcessController`/新标准表；称重页读标准 | 中 |
| G9 | 待称重任务总览（执行中且无称重、按今日计划排序） | `ProductionDataSuspectController` 同族新增 `GET /production/weigh/pending-tasks` | 小 |
| G10 | 工单×工序在制矩阵（含「停在哪道」「堵点」） | `ProductionPlanController` 或 `ProductionAnalyticsController` 新增聚合；先核 `/warehouse/wip-batches` | 中 |
| G11 | 工序标准工时（秒/单位）与班次规则（班次、休息、加班）的存储、维护、效率达标率 | `WorkProcess` 扩展或新表 + `ProductionAnalyticsController` efficiency 口径对齐 | 中 |
| G12 | 补充 Excel 批量导入（BOM 投料工序/产品路线/工序标准，预检逐行报错、整批撤销） | `BomController`/`ProductProcessWorkflowController` 新增 import；现仅有 `workflow-export` | 中 |
| G13 | LSM 委外：镜像黑湖还是迁原生闭环的产品裁决；列表补 已发/退回/产品/单位、日期范围、批量单号、CSV 导出 | `OutsourceOrderController.list` 扩参数与 `OrderSummary` | 小（接口）+ 产品裁决 |
| G14 | 生产计划列表缺 产品/单位/批量单号/路线对应筛选、四态计数、闭环三态(领/产/入)与交期聚合 | `ProductionPlanController.list`（`PageRequest` 扩字段）+ 返回扩展 | 小～中 |
| G15 | 领料单列表缺 关键字/计划号/日期；缺按物料汇总已发/退回/净领用与导出；无独立退料动作 | `FactoryMaterialRequisitionController.list` 扩参数 + 汇总端点 | 小～中 |
| G16 | 车间大屏缺 时间进度/超期落后、当日报工笔数、最近报工列表 | `ProductionProgressDashboardService` 扩字段 | 小 |
| G17 | 状态看板 4 列泳道视图（数据已有） | web-admin 前端 | 小 |
| G18 | 报工服务端校验核实：产出(良品+不良+副产)≤投入、结束晚于开始、不良项/副产品目录校验，规则未核 | `YieldReportServiceImpl.submitReport` 读码核对 | 小 |

## 15 条路由状态统计

- 有：2（/system/products、/production/bom/ecns）
- 部分：13（plans、material-flow、batches、approval、weigh、analysis-query、kanban、production-progress、wip、process-hub、yield-standard、price-adjustments、outsource）
- 缺：0（但 price-adjustments 的「成本影响+售价建议」整块、wip 的标准工时/班次规则 接近缺）
- 原型专用：0 条整路由；子项原型专用：ECN 的 LSM 占位页、画布对「黑湖来源只读」的展示、`/__local/` 本地草稿保存区、`x-prototype-*` 头、`acceptanceSession`
