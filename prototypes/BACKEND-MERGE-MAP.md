# 原型假后端 → 正式后端：融合对照表

日期：2026-11-15。依据：`ydwm-v1/fusion-*.mjs`（及 `tools/`）、`component-library/server/` 对照 `backend/java/cretas-api` 与 `backend/python`。

## 先读这一段：这张表证明了什么、没证明什么

四个只读代理按业务域各查了一遍。**状态只按「Java 里有没有对应的 Controller / 路由」判定，没有逐条比对规则实现**，所以：

- 「已有」= 路由和类在，**不等于规则等价**。融合的主要工作量在规则对齐，不在补路由。
- Python 侧只做过关键词 grep，没有逐项核对；「没命中」不能当成「Python 里没有」。
- 各代理只读了部分文件全文，其余按路由行和关键词推断；下表「推断」处标明。
- `component-library/server/` 与 `ydwm-v1/` 同名文件行数不同的有：process-library、production、process-sheet、bom、smartbi、local-edits、data、workflow（`ydwm-v1` 行数更多或不同，哪边新未验证）。库存/仓库、采购/销售两组在组件库侧没有同名文件。

## 一、生产 / BOM / 工序 / 称重

| 原型模块 | 主要接口 | 正式后端对应 | 状态 | 融合要补 |
|---|---|---|---|---|
| fusion-bom | `/bom/recipes`、`by-product/{id}/seasoning`、`pinned-to-workflow/{rev}`、`packaging-specs` | `BomRecipeController`、`BomPinnedRecipeController`、`BomController`、`ProductTypeController` | 路由已有，规则未比对 | 辅料克/千克口径；`COOKING` 且 `countInSeasoning=false` 的排除 |
| fusion-production | `/production-plans`（draft、batch-from-so、costs） | `ProductionPlanController` | 路由已有，规则未比对 | 仅 DRAFT/PENDING/PLANNED 可删（409）；计划创建时绑定工艺与配方 |
| fusion-remaining-production | `status/{...}`、`wip/available`、`semi-finished/inventory`、`POST processing/batches`、`secondary-plan`、批次 `timeline/yield/wip/work-process-tasks` | `ProductionPlanController`、`ReportReversalController`、`ProcessingController`、`ProductionBatchCostController` | **部分有** | **缺**：`semi-finished/inventory`、`POST processing/batches`、批次级 `wip` 与 `work-process-tasks`；`yield` 路径与原型不一致 |
| fusion-process-library | `/work-processes`、`/product-work-processes` | `WorkProcessController`、`ProductWorkProcessController` | 已有 | merge（`mergedIntoId`）规则、`lockVersion` 乐观锁 |
| fusion-process-sheet | `process-sheet/*`、`settlement-prefill` | `ProcessSheetController` | 路由已有，规则未比对 | submit 的扣料与 `inputAllocations`；draft/submit 分离 |
| fusion-plan-excel | 计划导出、导入模板、导入 | `ProductionPlanController` | 路由已有，规则未比对 | 逐行校验口径与失败行号（序号+2） |
| fusion-plan-supplements | `material-advisory`、`document-trace`、`settlement` | `ProductionPlanController`、`ProductionDocumentTraceController` | 已有，规则未比对 | 缺料预估公式、`missingLinks` 文案 |
| fusion-material-flow | `/material-requisitions`、`/production-material-returns` | `FactoryMaterialRequisitionController`、`ProductionMaterialReturnController` | 控制器已有，规则未比对 | 状态机 PENDING→PICKING→TRANSFERRED→ISSUED→CLOSED；先进先出；损耗与退料口径 |

## 二、库存 / 仓库

| 原型模块 | 正式后端对应 | 状态 | 融合要补 |
|---|---|---|---|
| inventory-views | `WarehouseInventoryController`、`MaterialBatchController` | 已有 | 缺单价整组 `totalValue=null`；扣预留口径 |
| stock-allocation（内部函数） | 仅 `BomItemSubstituteServiceImpl`、`InterimSettleServiceImpl` | **部分有** | 排序（到期日→入库日→id）、跨计划隔离、替代料系数 |
| stocktake-import | `StocktakeBulkImportController` | 已有 | 预览 30 分钟有效、期初批次、重放幂等 |
| warehouse-layout | `WarehouseLayoutController` | 已有 | 位置有库存不可删；识图在原型里返回 501 |
| warehouse-transfer | `TransferController`、`TransferServiceImpl` | 已有，规则待核 | 客户自有/生产批次来源不动；任一行不足整单不动 |
| transfer-delivery | `TransferController` | 已有 | `requestKey` 幂等；分步记录后禁止一次性 confirm |
| receipt（内部函数） | `ProductionWarehouseReceiptMobileController`（推断） | **部分有** | 差异原因必填、哈希幂等、不猜仓库 |
| receipt-edits | `WarehouseReceivingController` | 已有 | 沿用采购单保存时的换算快照；超收拦截；仓库类型白名单 |
| reversals | `ProductionPlanController` | 已有（申请端未验证） | 24 小时窗口、下游已发货拦截、退料 |
| report-stock（内部函数） | `service/bom/*`、`InterimSettleServiceImpl` | **部分有** | 续锅比例、回料先进先出、跨计划半成品绑定 |

## 三、采购 / 销售 / 主数据

| 原型模块 | 正式后端对应 | 状态 | 融合要补 |
|---|---|---|---|
| controlled-purchase | `ControlledPurchaseTaskExecutionService`、`PreviewTokenServiceImpl` | 已有 | kg 与预算上限；`commandDigest`/过期/`expectedVersion`/actor 四项同时校验，不能降成只验 token |
| requisitions | `PurchaseRequisitionController` | 已有 | 转 PO 幂等、空价空税保留 |
| sales-purchase | `PurchaseController` | **部分有（推断）** | BOM 展开、库存口径（排除客户自有/过期/不合格）、预览指纹 409 |
| supplier-relations | `SupplierMaterialServiceImpl` 等 | 已有 | 含税折算（13%/9%/免税）；单位换算失败即拒绝 |
| quotes | `OperationalQuoteController` | 已有 | 毛利率口径、修订历史、单位一致 |
| return-orders | `ReturnOrderController` 等 | 已有（后端更全） | 原型不做财务记账（NOT_POSTED），不得覆盖真实记账 |
| trade-documents / documents | `PrintController`、Python `printing/` | 已有 / 部分有（推断） | 原型调用本机 Python 路径，不能照搬；多计划合并工单 |
| sales-delivery | `SalesDeliveryShipmentController` 等 | 已有（规则未读） | 逐条规则另核 |
| customer-records / manufacturers | 对应 Controller 在 | 已有 | `call-record` 路由未找到 |
| masterdata | `RawMaterialTypeController`、`ProductTypeController`、`SkuAssemblyService` | **部分有（推断）** | `suggest`/`suggest-unit`、料号预览、SKU 组装事务 |

## 四、报表 / 工作流 / 导入

| 原型模块 | 正式后端对应 | 状态 | 融合要补 |
|---|---|---|---|
| fusion-smartbi | Python `smartbi/api/*`、Java `SmartBIAnalysisController` | **部分有** | **缺**：`chart/build`、`chart/batch`、`chart/recommend` 未找到；台账类禁止混单位绘图 |
| fusion-report-contract（导出函数） | `YieldReportServiceImpl`、`ProcessSheetServiceImpl`、`WeighingEntryRules` | **部分有** | 幂等 hash、选择组 min/max、单位不可换算抛错 |
| fusion-local-approval | `SalesController`、`PurchaseController`、`StocktakeController`、`WorkflowInstanceController` | 已有 | 调拨 request、盘点 submit 校验未验证 |
| fusion-workflow | `ProductProcessWorkflowController` | 已有 | 乐观锁冲突 409 |
| fusion-workflow-excel | 未找到 | **缺失** | 工艺与配方 Excel 导出 |
| fusion-upload | `SmartBIUploadController` | 部分有 | `rename`、`fields` 是否具备；真删走后端 |
| fusion-data-templates | 未找到 | **缺失** | 模板下载 |
| fusion-account-imports | `SupplierController`（关键词） | 部分有 | 导入批量校验（1–2000 行）；SKU 导入未找到 |
| fusion-data / fusion-local-edits | 无 | 原型专用 | 不融合 |
| fusion-account-reads/support/additional-read/files、report-cutoff | — | 未验证 | 需逐路由查 |

## 五、最容易出错的规则（各组共同的）

1. **单位换算**：原型所有领料、调拨、关单、报工走统一 `convertQuantity`（只认 kg/g/pcs，无换算关系就报错）。Java 没有等价的统一入口，只在 `YieldReportServiceImpl` 有 5 处。收货换算必须沿用采购单保存时的快照，不能用当前档案重算。
2. **取整与精度**：需求量 `toFixed(6)`；库存比较阈值 1e-8；关单余量校验 1e-6；盘点两位小数、收货四位。`BigDecimal` 的 scale 和 `RoundingMode` 要显式对齐，边界值（刚好用完、损耗刚好等于余量）最容易判错。
3. **「不能算就不算」不能退化成 0**：缺单价 → null；单位不同的批次不计入库存；产出投入单位不同时 `yield` 返回空；缺税率 → null。
4. **先到期先出与计划隔离**：原型三处排序键不同（`expiryDate`/`expireDate`/`createdAt`），融合时统一口径，且不借用别的计划已签领的批次。
5. **整单原子与幂等**：库存不足时所有行都不动；收货、盘点用请求号或哈希保证重放不重复入库。
6. **冲销**：小结撤销只允许 24 小时内，下游已发货或已消耗就拒绝；退货累计量排除已驳回单据。

## 六、不要带进正式后端的东西（只在原型里成立）

- 本机文件与状态：`private/*`、`data/v3/factory-baseline.json`、`fs.writeFileSync` 写审计文件。
- 只认本机的门：host 白名单、Origin 校验、cookie ticket、`LOCAL_ACCESS_REQUIRED`。
- 会话与租户：`x-prototype-test-session`、`x-v5-business-session` 头；租户硬编码 `FOOD_0000_001`。
- 原型标记与降级：`prototypeLocalOnly`、`LOCAL_PRACTICE` 审批链、`/__local/` `/__account/` `/__prototype/` `/__v5/` 路由、快照 404、「未收录接口回快照数据」、`hiddenIds` 假删除。这些违反「禁止降级、不返回假数据」。

## 七、已知缺口（2026-11-15 按三条融合线回执订正）

本节原先把按路由「未找到」的项都写成「确认缺」，逐条 grep 与读码后多数其实已存在。订正如下。

1. 生产：原列的 `semi-finished/inventory`、`POST processing/batches`、批次级 `wip`、`work-process-tasks`、`yield` **全部已存在**。批次级三个接口的路径前缀是 `production/batches/{id}/`，RN 与 web-admin 已在用，原型侧适配路径即可。真正的差异是两处口径，Steve 已裁决并由 PR #1752 落地：建二次加工计划时就拦截目标工艺接不住半成品（409 `SECONDARY_SOURCE_NOT_ACCEPTED`，开工闸保留）；`POST processing/batches` 数量缺失或 ≤0 返回 400 `BATCH_QUANTITY_MUST_BE_POSITIVE`。
2. 报表与导入（PR #1749）：`chart/build`、`chart/batch`、`chart/recommend` 已在 Python `/api/chart/*`；工艺与配方 Excel 导出已有 `GET /bom/workflow-export`；SKU 导入已有 preview/confirm。真缺并已补的是：图表混单位闸、`GET /smart-bi/upload-template` 模板下载、上传改名与真删（物理删除，不带 `hiddenIds` 假删除）、`fields`/`data` 的工厂归属校验、供应商导入上限 1000 改 2000 与空文件、超限的明确 400。
3. 库存、采购、销售（PR #1756）：逐条核对表见 `docs/qa/prototype-rules-inventory-purchase-sales-audit-20261115.md`。已修两处：库存汇总任一在库批次缺单价时总价值与均价为空，不再给偏低的数；`SkuAssemblyService` 取客户按本厂过滤。核对表其余疑似缺陷为读码结论，未逐条复核，其中需 Steve 裁决的口径项仍未改。
4. Java 统一单位换算入口：只出了设计，未实现，见 `docs/qa/unit-conversion-gateway-design-20261115.md`，等 Steve 确认。

## 八、没覆盖到的

- 页面级对照已补：见 `PAGE-BACKEND-MAP.md`（85 个路由 + 4 个服务，明细在 `page-map/`），`product-cost-workspace`、`ledger-facts`、`report-insights`、`list-analysis-service` 和六膳门对账（缺）都在 E 组。Steve 已裁决的 11 项口径和可直接补的缺口也列在那里。
- 仍没覆盖：各页规则实现的逐条比对（那张表只确认接口与字段在不在）。
