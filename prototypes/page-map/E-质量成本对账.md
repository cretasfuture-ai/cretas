# map-E：质量闭环 + 成本 + 对账 + 原型服务 → 正式后端对照（只读核对）

核对日期 2026-11-18。Java 路径省略前缀 `backend/java/cretas-api/src/main/java/com/cretas/aims/`，下称 `J/`；Python 前缀 `backend/python/`，下称 `P/`。Java 基础路径均为 `/api/mobile/{factoryId}`。
原型来源：`prototypes/unified-v1/src/...`（下称 `U/`）；E 线后端 `U/collab/e-api.cjs`、`U/collab/e-rules.cjs`、`U/quality/*.cjs`；成本后端 `unified-v1/lib/cost-lsm.cjs`；味美工具 `ydwm-v1/tools/*.cjs`。
状态只用：有 / 部分 / 缺 / 原型专用。规则读到代码的写"已核"，没读到的写"规则未核"。

总体结论（先看这条）：
- 质量闭环 8 页，Java 在业务名词上基本都有对应控制器，但**数据模型不同**：原型是「分阶段（来料/生产/入库/发货/其他）检验 + 带版本检验标准 + 草稿/提交/作废」，Java 是「按生产批次的检验 + 按产品绑定的检验项」。
- 原型质量记录全部挂 C 入库批次（黑湖入库批次，含采购入库），Java 召回/废弃/退回按生产批次、成品批次、原料批次各自挂，没有统一的「入库批次号」入口。
- 成本口径差异大：原型单位成本 =（材料+人工）÷ 良品产出，不含制造费用；Java 单盒成本 = 总成本 ÷ 箱数，含辅料、包材、设备、其他、半成品、副产回收。两边不能直接换算。
- 黑湖读取与对账：Java 里**没有任何黑湖代码**；Python `blacklake/` 是独立测试应用（`smartbi_app.py` 单独起，未并入 `P/main.py`）。

---

## 1. /quality/inspections 质量检验

名称｜原型来源｜`U/quality/QualityInspections.vue`、`U/collab/e-api.cjs`（saveInspection 等）、`U/quality/qc-rules.cjs`、`U/quality/lake-inspections.cjs`、`U/quality/first-check.cjs`、`U/quality/batches.cjs`

需要的数据与动作：
1. 读：检验单列表，按阶段（来料/生产/入库/发货/其他）、状态、关键词、批量单号、日期筛选。
2. 读：检验单详情：标准版本、逐项实测值、单项判定、总判定（PASS/FAIL/INCOMPLETE）、操作日志。
3. 写：新建草稿、保存草稿、提交结果（必检项未填完拒绝；不合格只能选 让步接收/拒收/返工/复检，让步接收必填原因）。
4. 写：作废（必填原因）。
5. 写：来料/入库检验挂「入库批次」，不合格自动让批次 HOLD（待处置），批次处置结论（放行/让步放行/退货/报废）后解除或 BLOCKED。
6. 读：黑湖检验单（只读，阶段由来源单据推断）。
7. 读：首检状态（标准上设「用作首检+适用工序」，对照黑湖工序任务与首笔称重，只提醒不阻断）。
8. 写：不合格后建质量异常任务（规则触发的提醒 INSPECTION_FAIL）。

后端对应：
1. `GET /processing/quality/inspections`（分页、result、keyword、batchNos、dateFrom/dateTo）— `J/controller/ProcessingController.java:459`；批量单号核对 `:497`。**无阶段筛选**。
2. `GET /processing/quality/inspections/{inspectionId}` — `ProcessingController.java:513`；逐项快照在 `customFields.items`（`J/service/impl/ProcessingServiceImpl.java:1185` 起的 submitDynamicInspection 写入）。无操作日志接口（规则未核）。
3. 提交：`POST /processing/batches/{batchId}/quality-inspections`（`ProcessingController.java:447`）+ 表单 `GET /processing/batches/{batchId}/quality-form`（`:440`）。判定已核：NUMERIC/RANGE 按上下限，BOOLEAN、TEXT 按标准值；必检未填 400；不合格需原因（`ProcessingServiceImpl.java:1185-1300`）。结论 PASS/FAIL/CONDITIONAL（原型没有 CONDITIONAL，Java 的"条件合格"来自工厂开关 failScope）。**没有草稿态、没有选项型（option）检验项**（valueType 只认 NUMERIC/RANGE/TEXT/BOOLEAN）。处置：`POST /processing/quality/inspections/{id}/evaluate-disposition`（`ProcessingController.java:559`）、`execute-disposition`（`:642`）、特批 `request-special-approval`（`:699`）。
4. 无作废接口（未找到）。
5. 批次扣留：`J/controller/QcReleaseController.java`（`/qc-release/pending` :36、`/decisions` :43、`/records` :56）；`J/controller/QualityDispositionController.java`（`/held-goods-batches` :201、`/batches/{batchId}/release-held-goods` :217）。挂的是生产批次/成品批次，不是黑湖式「入库批次」；来料检验挂原料批次 `QualityInspection.materialBatchId`（`J/entity/QualityInspection.java:53`）但**没有对应的提交接口**（提交接口只接生产批次，规则已核）。
6. 无（Java 无黑湖代码）。
7. 无首检概念；`FIRST_CHECK_PENDING` 提醒规则也无（`J/controller/` 下未找到）。
8. 无「规则触发提醒」；成本/缺料异常有处理实例（`CostDeviationCaseController`），质量异常任务未找到。

状态：**部分**
缺口：A 阶段字段与阶段筛选；B 草稿/作废；C 选项型检验项；D 来料/入库检验提交接口（挂原料批次）；E 统一「入库批次」HOLD/BLOCKED 视图；F 黑湖检验单只读；G 首检；H 不合格自动提醒。

---

## 2. /quality/standards 检验标准

名称｜原型来源｜`U/quality/QualityStandards.vue`、`U/collab/e-api.cjs`（saveStandard、cleanItems）

需要的数据与动作：
1. 读：按阶段的标准列表，每条含版本历史、适用对象（本阶段全部/指定对象）、启用状态。
2. 写：新建标准（≥1 检验项，≤40 项；项类型 数值/选项/判定；数值项上下限至少一个、必填单位、标准值须在上下限内；选项项 ≥2 选项且至少 1 个合格选项且不能全合格）。
3. 写：修改已有标准必须写原因，版本号 +1，留前后对比日志；阶段新建后不可改。
4. 写：启用/停用。
5. 写：生产阶段标准可设「用作首检」+ 适用工序。

后端对应：
1. `GET /quality-check-items`（`J/controller/QualityCheckItemController.java:103`）、按产品读绑定 `GET /quality-check-items/bindings/product/{productTypeId}`（`:287`）。检验项属性已核（`J/entity/config/QualityCheckItem.java`）：valueType、standardValue、min/max、unit、tolerance、抽样策略、severity、isRequired、`version`。**没有阶段**；分类 `QualityCheckCategory` 是 感官/物理/化学/微生物/包装，不是阶段。
2. `POST /quality-check-items`（`:50`）、绑定 `POST /quality-check-items/bindings`（`:248`）、批量绑定（`:300`）、从模板复制（`:232`）。数值项校验（上下限、必填单位、标准值在区间内）是否在服务层，**规则未核**（只读到控制器）。选项型无。
3. `PUT /quality-check-items/{itemId}`（`:65`）；实体有 `version` 字段（`QualityCheckItem.java:234`），提交时校验 itemVersion 一致（`ProcessingServiceImpl.java` submitDynamicInspection 的 409 QUALITY_STANDARD_STALE，已核）。「修改必填原因」「前后对比日志」未找到。
4. `POST /quality-check-items/batch/enable`、`batch/disable`（`:206`、`:219`）。
5. 无。

状态：**部分**
缺口：A 阶段维度与「本阶段全部对象/指定对象」适用范围；B 选项型检验项；C 修改原因+版本日志（版本号有，原因/对比无）；D 首检标记；E 数值项自洽校验（规则未核）。

---

## 3. /quality/defects 不良分析

名称｜原型来源｜`U/quality/QualityDefects.vue`、`U/collab/e-api.cjs`（saveDefect、defectStats）

需要的数据与动作：
1. 读：工序不良分布：按「工序+单位」分组，良品数、不良数、报工次数、不良率=不良÷（良品+不良）；**不同单位分开汇总不相加**；分母为 0 不算率（显示无法计算，不显示 0%）；按日期区间。数据源是黑湖报工。
2. 读：按不良项分布（读 A 线补录 `/__unified/a/api/defect-captures`）。
3. 写：不良项字典维护（编码唯一、名称、分类、适用工序，修改必填原因，启用/停用必填原因）。

后端对应：
1. 报工里有 `defect_quantity`/`good_quantity`（`J/entity/ProductionReport.java:84`）。`GET /production-analytics/by-process`（`J/controller/ProductionAnalyticsController.java:90` → `ProductionReportRepository.getProcessBreakdown`，`J/repository/ProductionReportRepository.java:163-175`）：按 `process_category` 汇总 output/good/defect/report_count，**不按单位分组、不算率、跨单位直接求和**（已核 SQL）。另有 `GET /production/yield/by-process`（`J/controller/YieldAnalysisController.java:27`）：按工序出成率，单位混杂时返回 null（`unitComparable`），但它是出成率不是不良率。
2. 不良项明细：`GET /quality-defects`（`J/controller/QualityDefectController.java:61`）、`/summary`（`:90`）。`defectType` 是固定枚举 `J/entity/enums/DefectType.java`（外观/重量/成分/微生物/包装/异物/感官/保质期/其他 9 类），按检验单登记（`POST /quality-defects` :42），不是报工分项。
3. 无字典维护（枚举固定，无编码/适用工序/启停）。

状态：**部分**
缺口：A 工序不良率接口（按工序+单位分组、不跨单位求和、零分母返回 null）；B 报工分项补录不良项；C 不良项字典（编码/分类/适用工序/启停/改因）。

---

## 4. /quality/ccp CCP 监控记录

名称｜原型来源｜`U/quality/q-records.cjs`（type=ccp）、`U/quality/QualityLoop.vue`

需要的数据与动作：
1. 读：CCP 记录列表（工序、监控点、上下限、实测值、单位、监控时间、监控人、判定 PASS/DEVIATION）。
2. 写：自由录入一条（工序必须在黑湖工序目录；上下限至少填一个且下限≤上限；实测值必填；监控时间不得晚于现在）。判定由代码按限值算。
3. 写：关闭（必填处理结果）、作废（必填原因）；记录不可改。
4. 偏离只**提示**「建议扣留本批及下游」，**不自动扣留**。

后端对应：
1. `GET /ccp/records?batchNumber=`（`J/controller/CcpMonitoringController.java:67`）**必须给批号**；`GET /ccp/deviations/open`（`:77`，最多 500 条）；导出 CSV `:90`。没有跨批次全量列表/分页/日期筛选。视图含 result 三态 OK/DEVIATION_OPEN/DEVIATION_HANDLED（`J/service/foodsafety/ccp/CcpMonitoringService.java:94`）。
2. `POST /ccp/records`（`:55`）：必须按**预先配置的 CCP 点**（`GET /ccp/step-checkpoints`，`:46`）按工序录入，`workProcessId` 必填，不能自由输监控点/限值（`CcpMonitoringService.java:139、170-215` 已核）。限值判定 `isOutOfLimit` 闭区间（`:152`，已核，与原型口径一致）。
3. `POST /ccp/records/{recordId}/handle`（`:105`）填纠偏措施。**无作废**；`voidDeviationsOfReport` 只随报工撤销（`CcpMonitoringService.java:347`）。
4. **口径相反**：Java 超限「自动生成偏离并扣待检」（`CcpMonitoringController.java:53` 注释 + `RecordResult.heldOutputs`），原型明确「不自动扣留」。需 Steve 裁决以谁为准（见缺口汇总）。

状态：**部分**
缺口：A 跨批次 CCP 记录列表；B 自由录入监控点（或明确只用预配置 CCP）；C 独立作废；D 自动扣留口径裁决。

---

## 5. /quality/returns 质检退回单

名称｜原型来源｜`U/quality/q-records.cjs`（type=return）

需要的数据与动作：
1. 读：退回单列表。
2. 写：新建（退回对象=工单/批次/物料文本；来源检验单**选填**；数量>0；单位；退回给=工序或供应商；原因）。
3. 写：处理完写结果关闭；作废必填原因。

后端对应：
1. `GET /quality-return-orders`（`J/controller/QualityReturnOrderController.java:62`，按 status/targetType/inspectionId/日期分页）。
2. `POST /quality-return-orders`（`:40`）：**qualityInspectionId 必填且必须存在**（`J/service/impl/QualityReturnOrderServiceImpl.java:77-80` 已核）；`targetType` 只有 SUPPLIER、SUBCONTRACT（`J/entity/enums/QualityReturnTargetType.java`），**不能退回工序**；targetId 必填；数量>0 已核（`:73`）。
3. 状态机 DRAFT → `PUT /{id}/confirm`（`:109`）→ `PUT /{id}/ship`（`:120`，物流单号必填）；撤销只能撤草稿 `DELETE /{id}`（`:133`）。无「关闭写处理结果」，无已确认后的作废。

状态：**部分**
缺口：A 来源检验单改选填；B 退回工序；C 「关闭=处理结果」与确认后作废；D 退回对象文本（工单/批次/物料）。

---

## 6. /quality/disposals 废弃处理

名称｜原型来源｜`U/quality/q-records.cjs`（type=disposal）

需要的数据与动作：
1. 读：废弃记录列表。
2. 写：新建（对象、可挂入库批次并校验存在与**单位一致**、来源检验单、数量>0、方式 报废/销毁/退供应商/回炉返工、原因）。
3. 关闭/作废（同上）。

后端对应：
1. `GET /disposal-records`（`J/controller/DisposalController.java:91`，分页、类型、状态、keyword、finishedGoodsBatchId）。
2. `POST /disposal-records`（`:158`）：请求含 disposalType、disposalQuantity、disposalReason、disposalMethod、qualityInspectionId、productionBatchId、materialBatchId、finishedGoodsBatchId（`J/dto/disposal/CreateDisposalRecordRequest.java:49-81`）。可挂批次候选 `GET /disposal-records/eligible-material-batches`（`J/controller/DisposalMaterialBatchOptionsController.java:25`）、`/eligible-finished-goods`（`DisposalController.java:80`）。方式映射：SCRAP/DESTROY/RETURN 对应 报废/销毁/退供应商；**回炉返工不是废弃类型**（返工走 `ReworkRecord`，`DisposalRecord.reworkRecordId`）。「单位与批次一致」校验未找到（规则未核）。
3. 审批流 `PUT /{id}/approve`（`:238`）、`/reject`（`:281`）；`DELETE /{id}`（`:319`，已审批不可删）。无「关闭写结果」「作废写原因」。

状态：**部分**
缺口：A 回炉返工与废弃的合并展示（或前端分流到返工接口）；B 单位一致校验（规则未核）；C 关闭/作废语义（Java 是审批+删除）。

---

## 7. /quality/recalls 批次召回

名称｜原型来源｜`U/quality/q-records.cjs`（type=recall）、`U/quality/batches.cjs`（recallHolds）

需要的数据与动作：
1. 读：召回记录列表。
2. 写：选**C 入库批次**发起（采购入库或生产入库均可），等级 一/二/三级，原因，范围；同批次已有进行中召回则拒绝。
3. 进行中的召回让批次进入「待处置」HOLD，C 的先到期先出建议跳过。
4. 关闭（处理结果）、作废（原因）。

后端对应：
1. `GET /quality-recalls?batchId=`（`J/controller/FactoryRecallController.java:33`）。
2. `POST /quality-recalls`（`:49`）：请求只有 `batchId`（Long，生产批次）+ `reason`（`J/dto/quality/FactoryRecallDTO.java:26-30`）；服务依赖生产批次/成品批次/发货单（`J/service/quality/FactoryRecallService.java:80-98`）。**无等级、无范围、不支持采购入库批次**。预览 `GET /preview`（`:41`）。重复召回拦截规则未核。
3. 扣留：服务注释写「links hold the batch」（`FactoryRecallService.java:63`），与「先到期先出」联动规则未核。
4. 关闭 `POST /{eventId}/close`（`:98`，有 blockers 校验：发货单未确认/未退回则不可关）；**无作废**。

Java 比原型多：客户确认 `/customer-ack`（`:75`）、召回退货单 `/returns`（`:86`）、后续核对 `/follow-up`（`:67`）。

状态：**部分**
缺口：A 召回等级、范围字段；B 采购入库批次（原料）召回；C 作废；D 与先到期先出的联动（规则未核）。

---

## 8. /quality/param-deviation 工艺参数偏离

名称｜原型来源｜`U/quality/QualityLoop.vue`（GAPS 只写缺口，原型无数据）

原型声明需要：工序的工艺设定值 + 实测值，偏离后扣留本批及下游；黑湖没有这类参数，Cretas 也没有采集入口。

需要的数据与动作：
1. 读：按工序+参数汇总的偏离次数/率。
2. 读：偏离明细（偏低/偏高）。
3. （原型期望）偏离处置、扣留本批及下游。

后端对应：
1. `GET /quality/param-deviation/summary`（`J/controller/ProcessParamDeviationController.java:41`）：按工序+参数汇总，报工次数/偏离次数/偏离率，支持日期、工序、产品、批号。
2. `GET /quality/param-deviation/details`（`:57`，分页，status=LOW/HIGH）。
3. 处置实例：`GET/POST /quality/process-param-deviation/cases`（`J/controller/ProcessParamDeviationCaseController.java:27`；assign :67、start-verify :76、resolve :85）。扣留联动规则未核。
采集入口：参数值来自报工里填写的工艺参数（`summary` 注释「填了该参数的报工次数」），不是设备采集。

状态：**有**（原型自己写了「没有」，实际后端已有，原型页应改为接这三组接口；扣留本批及下游规则未核）
缺口：原型侧需把「只写缺口」改为真实接入；设备自动采集仍无。

---

## 9. /quality/cold-chain 冷链超温批次

名称｜原型来源｜`U/quality/QualityLoop.vue`（GAPS 只写缺口）

需要的数据与动作：
1. 读：冷库/冷藏车逐时温度 + 批次出入库时间 → 判定哪些批次超温。
2. 动作：转质检/扣留。

后端对应：
1. `GET /cold-chain/alerts`（`J/controller/ColdChainExposureController.java:42`）、`/alerts/{alertId}/affected-batches`（`:50`，原料/在制半成品/成品）。逐时读数表 `ColdChainTempReading` 由 `J/scheduler/ColdChainMonitoringScheduler.java` 写入（仅确认写入方是定时任务；读数来源设备/接口细节规则未核）。设备↔库位历史 `/equipment`（`:69`）、`/equipment/{id}/locations`（`:76`、`:83`）。
2. `POST /cold-chain/alerts/{alertId}/holds`（`:57`）转质检（沿用质检放行待检状态）。

状态：**有**（原型侧只写缺口，后端已具备；温度采集来源规则未核）
缺口：原型改为接入；批次对应用的是 Cretas 自身批次，不是黑湖入库批次。

---

## 10. /quality/label-qc 包装标签拍检

名称｜原型来源｜`U/quality/q-records.cjs`（type=label）

需要的数据与动作：
1. 写：按七项标签内容（品名/配料表/保质期/生产日期/批号/净含量/储存条件）逐项记 合格/不合格/不适用；一项不合格即不合格；七项全不适用拒绝；可挂入库批次。
2. 读：记录列表。

后端对应：
1–2. `J/controller/LabelQcController.java`：任务制拍检（`POST /label-qc/tasks` :76，`/tasks/{id}/photos` :88，`/submit` :101，`PUT /review` :176，列表 `GET /tasks` :125）。请求只含产品、批号、生产日期、幂等键（`J/dto/labelqc/LabelQcDtos.java:23-35`），审核对象是**照片上的缺陷标注框**，不是七项内容清单。平台侧复核 `J/controller/LabelQcPlatformController.java`。

状态：**部分**（拍检流程有，七项人工清单与判定无）
缺口：七项标签内容清单录入与判定；挂入库批次。

---

## 11. /lsm/cost 成本分析（六膳门）

名称｜原型来源｜`U/cost/LsmCost.vue`、`ProductCostWorkspace.vue`、`CostBatchDetail.vue`、`ProductCostReview.vue`、`BomPriceAdvice.vue`、`cost-review.ts`、`bom-price-lsm.cjs`；后端适配 `unified-v1/lib/cost-lsm.cjs`

需要的数据与动作（口径已从代码读出）：
1. 读：产品 → 工单 → 工序 → 成本依据层级；缺口分类 11 类（未完工、无工序对应、无正数良品…）。
2. 口径-单位成本：`unitCost = directCost ÷ finishedGood`，要求 unitCostAvailable、良品>0、直接成本已知，否则 null；`directCost = 材料费 + 人工费`，**不含制造费用**、不含委外/其他（`cost-review.ts` unitCost；`cost-lsm.cjs:55-66`）。
3. 口径-产品级：同产品同单位可核算工单 `Σ(材料+人工) ÷ Σ良品` 加权（`cost-costOverview.cjs` groupEligibleCostProjects）。
4. 口径-分摊：工单人工按工序有效人时分摊到分，最后一道有人时的工序吃尾差；人时缺或为负则全部 null（`cost-laborCostAnalysis.cjs` allocateLaborByPersonHours）。
5. 口径-黑湖对照与采用值：黑湖 project_cost 的材料（来源 12 实际领退料）、人工（costType 2）、委外、其他逐项与 Cretas 算值对比，差额原因分类（MATCH/SOURCE_ZERO/BL_ZERO/NEGATIVE/…）；采用值规则：黑湖非零优先 → 黑湖 0 或无则用 Cretas → 黑湖为负则阻断（`cost-lsm.cjs:99-165`）。
6. 口径-差异：同产品 ≥3 张可比单：最高比最低高 ≥20% 报「单位成本差异」；材料占比 <10%、人工占比 >65%、人工为 0 提示（`cost-review.ts` signals）；两张单对照拆成材料差+人工差。
7. 口径-毛利率：成本页**没有毛利率**（成本分析只到直接成本；未在 cost/ 下找到毛利计算）。
8. 工价规则：计件=良品×单价（单位须一致，不换算）、计时=有效工时（优先黑湖 effectiveTime，否则起止推算并标注）×时薪；指定产品优先于通用，生效日不晚于报工日（`cost-lsm.cjs:25-52`）。
9. 副产品分摊：不分摊 / 按重量 / 按比例（`byproduct-rule`）。
10. 进销存估值：原料最近一次黑湖正价领料单价 → Cretas 补充单价 → 未估值；成品取已识别直接成本单价；不同单位不换算。
11. 动作：补充人工费/人时/人员名单（Excel 模板导出、导入预检/确认、撤销、跟进状态 OPEN/MISSING/CHECKED）、币种补充、批量分析。
12. 动作：BOM 调价建议（原料价变动→成本影响，只作建议）。
13. 读：班次有效工时与效率（来自 A 线）。

后端对应：
1. `GET /production/orders/{orderId}/cost-breakdown`（`J/controller/OrderCostBreakdownController.java:38`）、`GET /production/batches/{batchNumber}/cost-breakdown`（`J/controller/ProductionBatchCostController.java:76`）、`GET /production/cost-summary?startDate&endDate`（`J/controller/OrderCostSummaryController.java:41`）。字段：rawMaterialCost、laborCost、seasoning、packaging、equipment、other、semiFeed、totalCost、perBoxCost、byproductCredit、netPerBoxCost、costComplete、missingCostItemCount、priceMasked（`J/dto/yield/OrderCostBreakdownDTO.java:56-138`）。无「产品→工单→工序」三层汇总接口（只有订单/批次/区间汇总）。
2. **口径不同（已核）**：`perBox = total ÷ boxCount`（`J/service/yield/OrderCostBreakdownService.java:640`），分母是箱数（由产出换算），不是良品产出单位；总成本含辅料、包材、设备、其他、半成品；缺项时 costComplete=false，派生字段置 null（`:591-637`），这一点与「不能算就不算」一致。设备/其他未采集时给 UNCOLLECTED 状态而非 0（`:710-731`）。
3. 产品级加权：无；`cost-summary` 是逐单一行（`OrderCostSummaryController.java:41`）。
4. **无「工单人工按有效人时分摊到工序」**：Java 的人工金额是工序报工自带的 `laborCost`，只在同一工序内按「人数×分钟」摊到时段（`OrderCostBreakdownService.java:2040-2085`，已核）。
5. 黑湖对照与采用值：**无**（Java 无黑湖代码）。Python `P/blacklake/smartbi_api.py:117` `GET .../complete-cost-analysis`、`:175` `.../cost-traceability`、`:75/:93` `labor-supplements`，由 `P/blacklake/smartbi_app.py` 以 `/api/smartbi/blacklake` 挂载；**未并入 `P/main.py`**（独立测试应用，`businessWritesEnabled:false`）。逐项差额原因分类与「采用值」规则是否同口径：规则未核。
6. 成本差异信号：Java 有 `cost-deviation-cases`（`J/controller/CostDeviationCaseController.java:59`：阈值 `/thresholds` :120/:128、负责人 `/owners` :136/:143、扫描 `/scan` :159、列表 :167、详情 :190、指派 :234、核查 :244、结论 :252），口径是**计划成本差异% / 物料平衡偏离%超阈值**（依赖 `PlanCostVarianceService`、`MaterialBalanceService`），不是原型的「同产品单位成本离散 ≥20%/占比阈值」。信号清单不等价（逐条规则未核）。
7. 毛利率：成本页本身不需要；财务毛利在 `J/controller/finance/FinanceReportController.java`（规则未核，不在本页范围）。
8. 工价规则：`J/controller/WageController.java` 计件规则 CRUD（`/wage/piece-rate-rules` :67/:87/:108/:130/:171），模式枚举 `J/entity/enums/WageMode.java`；`/wage/analysis/labor-cost`（`:680`）。计件/计时、产品优先于通用、生效日、单位不一致不换算：规则未核。
9. 副产品：Java 为「副产回收额冲减成本」`byproductCredit`（`OrderCostBreakdownService.java:643-652`），不是按重量/比例分摊主产品成本；口径不同。
10. 估值：库存估值在 `J/controller/MaterialBatchController.java`（库存汇总，PR #1756 已改缺价为空），原料取批次入库价，与原型「最近一次黑湖领料价 → 补充价」不同；成品估值取成本分析单价：Java 无。
11. 补充人工/人时/名单：**无**对应补充存储与 Excel 导入。Python 有 `labor-supplements`（见 5，独立应用）。跟进状态：成本差异案例有指派/核查/结论（`CostDeviationCaseController.java:234-252`），语义近似但对象不同。币种补充：`J/controller/FactoryCostSettingsController.java:22`（`GET/PUT /config/cost-settings`）具体字段规则未核。
12. BOM 调价建议：`J/controller/BomController.java`、`ThreePriceComparisonController.java` 有价格相关，是否有「原料价变动→成本影响」：规则未核（未逐方法读）。
13. 班次有效工时/效率：`/wage/efficiency/*`（`WageController.java:197-337`）、`/production-analytics/efficiency/dashboard`（`ProductionAnalyticsController.java:101`）有效率；班次规则化有效工时规则未核。

状态：**部分**
缺口：见汇总 A5–A12。

---

## 12. /lsm/reconcile 对账（Cretas ↔ 黑湖）

名称｜原型来源｜`U/home/ReconcileHub.vue`；A 线 `/__unified/a/api/weighin-reconcile`；C 线 `/__lsm/supply/batch-reconcile`；黑湖读取 `liushanmen-v1/tools/read_source.py`、`read_extras.py`、`read_inventory.py`

业务定位（已按记忆裁定）：Cretas 接管称重报工与入库批次（主来源），开单/财务留黑湖，**不回写黑湖**，只做差异对账。黑湖每天 08:00 采集一次。

需要的数据与动作：
1. 读：称重报工 ↔ 黑湖报工：按工序任务匹配，字段（良品、投入等），四类结果：有差额 / 只在 Cretas / 只在黑湖 / 一致；单位不同的单列 mismatch，不进四类。
2. 读：入库批次 ↔ 黑湖入库单行：同上。
3. 每行写明「找谁核对」（车间/仓库），带黑湖数据时点 asOf，可导出。
4. 不改任何一边，不回写黑湖。
5. 前提：Cretas 侧「逐笔称重报工」「入库批次（含到期）」存在且为主来源。

后端对应：
1. 无对账接口。Cretas 侧称重数据只看到 `GET /production/work-process-tasks/{taskId}/weighings`（`J/controller/ProductionDataSuspectController.java:43`，疑点复核用），报工在 `production_reports`。没有「称重报工 vs 黑湖」比较。
2. 无。入库批次主体在 `J/controller/MaterialBatchController.java`（`/material-batches`，到期预警 `/expiring` :526、`/expired` :542、导出 :1364），无「与黑湖入库单对照」接口。
3. 无。
4. 满足（Java 根本不接黑湖）。
5. 黑湖读取：Python 独立应用有——`P/blacklake/client.py`（只读连接器）、`full_pull.py`、`sync.py`；对账相关 `P/blacklake/stock_reconciliation.py`（**仓库负库存与流水核对**：按已审批单据、单位一致、方向与符号一致、业务时间不晚于余额采集，才计入；证据性质，不是 Cretas↔黑湖差异）。该类与原型的「四类差异」不是同一件事。以上均未挂进 Java/`main.py`。

状态：**缺**（黑湖↔Cretas 差异对账接口两边都没有；Python 只有黑湖单边的库存流水核对）
缺口：见汇总 B1–B3。

---

## 13. 服务：product-cost-workspace（ydwm-v1/tools/product-cost-workspace.cjs，味美）

原型来源：`ydwm-v1/tools/product-cost-workspace.cjs`、`cost-costOverview.cjs`、`cost-laborCostAnalysis.cjs`、`cost-filters.cjs`、`product-cost-jobs.cjs`、`cost-report-lineage.cjs`；六膳门版 `unified-v1/lib/cost-lsm.cjs` 是同一结构的适配。

需要的数据与动作：
1. 读：按批次建成本表：材料费=批次 materialCost、人工费=批次 laborCost（缺则取补充）、良品=actualQuantity、单位；工序取自已提交报工（有效人时优先于实际人时）；缺项清单（工序对应/材料费/人工费/良品/单位/有效人时/名单）。
2. 读：就绪问题：良品为 0、负数/冲销、人时合计 0 不可分摊。
3. 写（补充）：人工费、每工序有效人时（零需单独确认）、人员代码（≤200）、依据必填；源哈希变化则 409；已有人工费不可改写。
4. 写：跟进状态（OPEN/MISSING/CHECKED+说明）；Excel 模板/导入预检/确认（摘要校验）；批量分析 job。
5. 全部写入只落本地 `private/cost-workspaces/*.json`，不动生产/薪资/库存。

后端对应：
1. 批次成本 `GET /production/batches/{batchNumber}/cost-breakdown`（`ProductionBatchCostController.java:76`）；缺项语义 Java 用 `missingCostItems`/`costComplete`（`OrderCostBreakdownService.java:591-637`），与原型「缺哪项就 null」同向。字段名/口径差异见第 11 节。
2. 负数/冲销、人时为 0 等「口径待核」提示：未找到等价（规则未核）。
3. 人工补充：**无**；工资来源是工价规则与薪资（`WageController.java`）。
4. 跟进/导入：**无**（成本异常案例见第 11 节第 11 项）。
5. **原型专用**：本机 JSON 存储、`sourceHash` 乐观锁文件、`x-prototype-user-action` 头、`FOOD_0000_001` 硬编码，不进后端（`BACKEND-MERGE-MAP.md` 第六节已列）。

状态：**部分**
缺口：补充存储与审计（正式后端需新表）；就绪问题清单；批次→工序有效人时分摊。

---

## 14. 服务：ledger-facts（ydwm-v1/tools/ledger-facts.cjs）

原型来源：`ydwm-v1/tools/ledger-facts.cjs`（79 行）

需要的数据与动作：
1. 读：领料台账行 `{领料单、状态、需求数量、已发数量、实际耗用、退回数量}`，由代码用**任意精度十进制**（BigInt，缺失/非法/负数分别标 missing/invalid）算：待发=需求−已发（≤0 记 0）、未取消需求合计、状态分类（PENDING/PICKING/TRANSFERRED/ISSUED/IN_USE 为活动）。
2. 生成核对建议代码（CHECK_USAGE、REVIEW_CANCELLED、REVIEW_RETURNS、COMPLETE_FIELDS、CHECK_INVALID、CHECK_PENDING、CHECK_STATUS），**模型只能选这些代码**，由代码渲染文字；校验模型输出，不合法则丢弃（`verify`/`render`/`proof`，摘要 sha256）。
3. 取消单不计入需求与待发；退回量单列不改实耗。

后端对应：
1. Java 领料：`J/controller/MaterialConsumptionController.java`（领用/消耗），领料单台账的「待发/实耗/退回」汇总口径：规则未核。无等价的「十进制精确+缺失三态」纯函数。
2. Python `P/smartbi/agent/factbook.py`、`synthesis_engine.py` 疑似「代码出事实、模型只综合」的同类设计，仅关键词命中，**规则未核**，不能确认等价。无「模型只选核对代码」的白名单。
3. 无。

状态：**缺**
缺口：台账事实纯函数与核对代码白名单（见汇总 C1）。

---

## 15. 服务：report-insights（ydwm-v1/tools/report-insights.cjs）

原型来源：`ydwm-v1/tools/report-insights.cjs`

需要的数据与动作：
1. 读/写/删：上传表分析缓存（`GET/POST/DELETE /smartbi-api/api/smartbi/analysis-cache/{id}`），读时校验 `tableHash`，台账类/业务类表走 ledger/business 的 `render`+`verify` 重新生成文字，不信缓存里的模型原文。
2. 写：`POST /insight/generate`（及 stream）、`GET dashboard/executive/insights(/custom,/stream)`；本地模型（macmini qwen3:8b）解读。
3. 限制：POST 缓存只保留服务端自己产出的文本，工厂范围必须一致，≤1MB。

后端对应：
1. Python `P/smartbi/api/analysis_cache.py`：GET :81、POST :135、DELETE :197（路径与原型同名；是否校验 tableHash/重渲染：规则未核）。
2. `POST /general-analysis`（`P/smartbi/api/chat.py:1186`，流式 `:1635`）；经营洞察 `GET /api/smartbi/insights/custom` 由 Java `J/client/AgentInsightsClient.java:86` 转发，流式 `:143`。`/insight/generate`：`P/main.py:915` 挂了 `insight.router`（路径前缀 `/api/insight`）。
3. 工厂范围校验、大小限制：规则未核。macmini 本地推理桥与 `online-bridge.cjs`/`online-policy.cjs`：**原型专用**（本机转发与计划策略），不进后端。

状态：**部分**
缺口：缓存读取时的重算校验与「只收服务端产出」（规则未核）。

---

## 16. 服务：list-analysis-service（ydwm-v1/tools/list-analysis-service.cjs）

原型来源：`ydwm-v1/tools/list-analysis-service.cjs`、`business-facts.cjs`、`engine/analyst.cjs`

需要的数据与动作：
1. 读：每个列表页（计划、采购单、销售单、退货、调拨、库存、发货）→ 对应原始资源（`/production-plans`、`/purchase/orders`、`/sales/orders`、`/return-orders`、`/transfers`、`/material-batches`、`/sales/deliveries`），代码算业务事实，只在「有可核对明细」时才解读，否则 422。
2. 写：`POST /__account/analyze`、`GET /__account/analysis-history`：问题≤20000 字；历史带 businessReview；非本地来源时走线上 `/smartbi-api/api/chat/general-analysis`，把事实/明细交给模型，模型返回的核对 ID 经 `verify` 后再 `render`。
3. 历史会话里的分析需校验后才回显，旧口径丢弃。

后端对应：
1. 无「页面→资源→业务事实」的统一服务。各资源列表接口存在（如 `/material-batches` `MaterialBatchController.java:112`）。
2. `POST /general-analysis`（`P/smartbi/api/chat.py:1186`、`P/chat/api/routes.py:446`，Java 转发 `J/client/PythonSmartBIClient.java:628`）。事实白名单校验：规则未核。
3. 对话历史：Java 有会话存储，校验回显：规则未核。
`/__account/*` 路由、`prototypeLocalOnly`、`macmini` 推理标识：**原型专用**。

状态：**部分**
缺口：页面级「原始明细→事实→模型选码」服务（见汇总 C2）。

---

# 缺口汇总

状态统计：共 16 项（12 路由 + 4 服务）。有 2（param-deviation、cold-chain）；部分 12（inspections、standards、defects、ccp、returns、disposals、recalls、label-qc、lsm/cost、product-cost-workspace、report-insights、list-analysis-service）；缺 2（lsm/reconcile、ledger-facts）；原型专用 0（部分项内含原型专用片段，已在各节注明）。

## A. 质量闭环
- A1 检验阶段（来料/生产/入库/发货/其他）：缺什么=阶段字段、阶段筛选；落在 `ProcessingController` 质检接口 + `QualityInspection` 增 stage；**中**
- A2 草稿/作废/按入库批次挂接：缺什么=检验单草稿态、作废、来料/入库检验挂原料或入库批次的提交接口；落在 `ProcessingController`/`QualityInspectionService`；**中**
- A3 选项型检验项 + 修改原因/版本日志 + 适用范围（全部对象/指定对象）+ 首检标记：落在 `QualityCheckItemController`；**中**
- A4 检验结果自动提醒（不合格未建任务）与首检未做提醒：落在新的规则服务，可复用 `CostDeviationCaseController` 式处理实例；**中**
- A5（质量页读黑湖检验单）：Java 无黑湖，归 D1；见下。
- A6 工序不良率接口：缺什么=按工序+单位分组、不同单位不相加、零分母 null；建议改 `ProductionAnalyticsController.by-process` 或在 `YieldAnalysisController` 加不良率；**小**
- A7 不良项字典（编码/分类/适用工序/启停/改因）与报工分项补录：落在新 `QualityDefectItemController`（或扩 `QualityDefectController`）；**中**
- A8 CCP 跨批次列表与独立作废、自由录入监控点：落在 `CcpMonitoringController`；**小**
- A9 CCP 偏离是否自动扣留：**口径冲突**（Java 自动扣，原型不自动），需 Steve 裁决后单边改；**小**（裁决后）
- A10 退回单：来源检验单改选填、退回工序、关闭写处理结果、确认后作废：落在 `QualityReturnOrderController/Service`；**小**
- A11 废弃：回炉返工并入同一列表、批次单位一致校验、关闭/作废语义：落在 `DisposalController` 或前端分流；**小**
- A12 召回：等级、范围、采购入库批次（原料）召回、作废、与先到期先出联动：落在 `FactoryRecallController/Service`；**中**
- A13 包装标签七项清单录入与判定：落在 `LabelQcController` 新增清单型任务或新 Controller；**中**
- A14 param-deviation、cold-chain 原型页：后端已有，原型改接入即可（无后端工作）；**小**（前端）

## B. 对账
- B1 称重报工 ↔ 黑湖报工差异对账接口（四类+单位不同单列+asOf+找谁）：落在新 `WeighingReconcileController`，数据源为 Cretas 称重报工 + Python 黑湖读取；**大**
- B2 入库批次 ↔ 黑湖入库单行差异对账接口：落在 `MaterialBatchController` 旁新增 `InboundBatchReconcileController`；**大**
- B3 黑湖读取并入正式服务：`P/blacklake` 现为独立测试应用（`smartbi_app.py` 单独起），需决定并入 `P/main.py` 或由 Java 定时采集（每日 08:00）；**大**

## C. 成本与分析服务
- C1 领料台账事实纯函数（十进制精确、缺失/非法/负数三态、待发/取消/退回口径）与核对代码白名单：落在 Python `smartbi/agent`（`factbook.py` 邻近）或 Java 领料服务；**中**
- C2 页面级「原始明细→事实→模型只选核对码」：落在 Python `smartbi/api/chat.py` 的 general-analysis；**中**
- C3 成本口径裁决：原型「（材料+人工）÷良品单位、不含制造费用」vs Java「总成本÷箱数、含辅料包材设备其他」；需 Steve 定统一口径后再对齐，否则两套数字并存；**中**
- C4 工单人工按有效人时分摊到工序（含尾差归最后一道有人时的工序）：落在 `OrderCostBreakdownService`；**中**
- C5 产品→工单→工序汇总与同产品加权单位成本（Σ成本÷Σ良品）接口：落在 `OrderCostSummaryController` 邻近；**中**
- C6 成本信号（同产品单位成本离散 ≥20%且样本≥3、材料占比<10%、人工占比>65%、人工为 0）：落在 `CostDeviationScanService`；**中**
- C7 黑湖成本对照与采用值（逐项差额原因、黑湖非零优先、负数阻断）：依赖 B3，落在 Python `blacklake` 并入后的成本服务；**大**
- C8 补充存储（人工费、每工序人时、名单、依据、源哈希乐观锁、撤销）与 Excel 导入/导出、跟进状态：落在新 `CostSupplementController` + 新表；**大**
- C9 工价规则口径核对（计件单位一致不换算、产品优先、生效日、时长取值优先级）：`WageController` 规则未核，需逐条读后补；**小**
- C10 副产品分摊（不分摊/按重量/按比例）与 Java「副产回收冲减」口径对齐；**中**
- C11 库存/成品估值口径（最近一次领料价→补充价→未估值）：落在 `MaterialBatchController` 库存汇总；**中**
- C12 BOM 调价建议（原料价变动→成本影响，只作建议）：`BomController`/`ThreePriceComparisonController` 规则未核，需读后确认；**小**
- C13 report-insights 缓存读取重算校验与「只收服务端产出」：落在 `P/smartbi/api/analysis_cache.py`（规则未核）；**小**

## D. 其它
- D1 黑湖检验单只读视图（质量检验页第 6 项）：随 B3 一起；**中**

本次未核对规则：检验标准数值项自洽校验、检验操作日志、召回与先到期先出联动、冷链读数来源、param-deviation 扣留联动、工价规则细节、BOM 调价建议、analysis-cache 重算校验。
