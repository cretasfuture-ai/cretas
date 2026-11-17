# map-D：仓储 / 库存 15 个路由 → 后端接口逐页对照

只读核对。基线：worktree admiring-chandrasekhar-9000f3。路径前缀：`J/` = `backend/java/cretas-api/src/main/java/com/cretas/aims/`，`W/` = `web-admin/src/`，`P/` = `prototypes/unified-v1/src/`。所有 Java 接口基础路径为 `/api/mobile/{factoryId}/`（下文省略前缀）。行号取注解所在行。

## 先读：两个工作区、两种数据源，不能混成一个结论

路由登记在 `P/frame/routes.ts`，由 `P/supply/SupplyRecords.vue`（kind 判定在第 43 行）分流：

- **六膳门（lsm）工作区**：库存、出入库单据、仓库、批次页全部读「黑湖快照」`/api/inventory-source`，补充数据写 `/__lsm/supply/*`（本机 JSON 文件，`P/supply/supply-lsm.cjs`）。黑湖没有的页（调拨、无单申请、盘点、字典、报损、源头个体、均价趋势）走 `LsmGapPage.vue`。这些端点都是原型假后端。
- **味美（ydwm）工作区**：`P/supply/ydwm.ts` 直连真实 Java 路径 `/api/mobile/FOOD_0000_001/...`，所以味美视图本身就是接口清单的证据。
- 正式后端里**没有任何接口提供「黑湖快照 / 黑湖出入库单」读取**。`backend/python/blacklake/` 是独立本地 BI 应用（挂在 `/api/smartbi/blacklake`，见 `smartbi_app.py:61`，不在 `backend/python/main.py`），只有分析/报表接口，没有库存与单据列表。Java 里没有 blacklake 代码。
- 因此状态里把「Cretas 原生数据（有 Java 接口）」和「黑湖镜像读取（无接口）」分开写。六膳门正式端到底用黑湖镜像还是 LSMDEMO 式的 Cretas 原生库存，需 Steve 裁决（记忆里：Cretas 接管称重+入库批次，开单/财务留黑湖）。

状态词：有 / 部分 / 缺 / 原型专用。规则实现除特别注明外**规则未核**（只核了接口存在与返回字段）。

---

## 1. /warehouse/materials

- 页面名：六膳门「入库记录」（lsm，DocsPage dir=in）；味美「收货任务」（YdwmList page=receiving）。web-admin 同路由 `W/views/warehouse/materials/list.vue`。
- 原型来源：`P/supply/DocsPage.vue`、`P/supply/YdwmList.vue:44-47`、`P/supply/SupplyRecords.vue:43`。

需要的数据与动作：
1. （味美）收货任务列表：采购单号、供应商、物料行、订购/已收/待收（分单位）、超收规则文案、状态中文。
2. （味美）收货/上传凭证/确认入库（原型明确回原页面办理，本页只读）。
3. （六膳门）黑湖入库单列表（按场景分类：采购/生产/退料/调拨/其他；单据与明细行两种视图；筛选仓库/日期/单号）。
4. （六膳门）明细行补金额、补批次号（Cretas 本地补充）。
5. 导出 CSV。

后端对应：
1. `GET warehouse/receiving/tasks`：`J/controller/warehouse/WarehouseReceivingController.java:65`；返回 `PurchaseReceivingTaskResponse`，含 `overReceiveLimitLabel`、`remainingReceivableQuantity`、`statusLabel`（`J/dto/inventory/PurchaseReceivingTaskResponse.java`、`PurchaseServiceImpl`）。字段齐。
2. 创建收货单 `POST warehouse/receiving/receipts` :177（必须关联已审批采购单，否则 400）；确认 `POST .../receipts/{id}/confirm` :217；改数量 `PUT .../receipts/{id}/items` :230；作废 :244；少收关闭 `POST .../tasks/{taskId}/close-short` :257；客户来料收货 `.../tasks/{taskId}/receipts` :131。web-admin 另用 `material-batches` 列表 `MaterialBatchController.java:349` 看入库批次。
3. 黑湖入库单列表：无接口。
4. 补金额/补批次：无接口（Java 批次金额、批次号在收货确认时生成，不支持事后补写；`PUT material-batches/{id}` :235 明确拒改数量/单价）。
5. 批次导出 `GET material-batches/export` :1364。

状态：**部分**（味美收货任务=有；六膳门黑湖入库单读取=缺；明细行补金额/批次=原型专用）。
缺口：黑湖入库单镜像读取；若六膳门走 Cretas 原生则入库单据按场景（采购/生产/退料/调拨）的统一列表视图（现只有 `material-batches`、`receiving/receipts/by-order/{orderId}` :206 按单查）无「入库单据」聚合接口。

---

## 2. /warehouse/unordered-inbound-applications

- 页面名：六膳门「无订单入库申请」（LsmGapPage page=unordered）。lsmOnly；味美走编译页。web-admin 同路由有真页面。
- 原型来源：`P/supply/LsmGapPage.vue:41`（CONF.unordered）。原型明说「黑湖没有申请这一层」，只展示已审批的普通入库/其它入库单作对照。

需要的数据与动作：
1. 黑湖「普通入库 / 其它入库」已审批单据只读对照（单号、类型、业务时间、状态、行数、品项、关联单号）。
2. 导出。（申请与审批在黑湖办理，原型不提供写入口。）

后端对应：
1. 黑湖单据对照：无接口。
2. 但正式端**已有完整的无订单入库申请**：`operations/customer-material-arrivals` GET :38、POST 创建 :47、`/{id}/cancel` :59、`/approve` :70、`/reject` :84（`J/controller/operations/CustomerMaterialArrivalNoticeController.java`）；实体字段 reason(CUSTOMER_MATERIAL/GIFT/OTHER)、customerId、expectedArrivalAt、status、reviewRemark、receiptCount（`J/entity/.../CustomerMaterialArrivalNotice.java`）；按申请收货 `POST warehouse/receiving/arrival-notices/{noticeId}/receipts` `WarehouseReceivingController.java:145`。web-admin 已接：`W/api/customerMaterialArrival.ts:42-83`。

状态：**部分**（原型要的「黑湖对照单据」=缺；原型没做的「申请/审批/收货」Java 已完整有）。
缺口：黑湖普通/其它入库单读取（同第 1 节）。无其它。

---

## 3. /warehouse/shipments

- 页面名：六膳门「出库记录」（DocsPage dir=out，默认场景销售出库）。web-admin `W/views/warehouse/shipments/list.vue`。
- 原型来源：`P/supply/DocsPage.vue`、`SupplyRecords.vue:43`。

需要的数据与动作：
1. 黑湖出库单列表（场景：销售/领料/调拨/其他；按单据/按明细行；关联销售单号跳转）。
2. 明细行补金额/批次（本地补充）。
3. 导出。

后端对应：
1. Cretas 原生出库：销售发货 `GET warehouse/deliveries/pending` `J/controller/warehouse/WarehouseDeliveryController.java:52`、历史 `/history` :70、确认出库 `POST /{deliveryId}/confirm` :101；发货单列表 `GET shipments` `J/controller/ShipmentController.java:56`（及 /date-range :119、/stats :167、/{id} :221）；销售发货记录 `sales/deliveries`（SalesController，`SalesController.java:939` 邻近）。web-admin 用批次分配 `sales-deliveries/items/{}/batch-allocations(/recommend-fifo)`、`sales/deliveries/{}`。领料出库见 `material-requisitions`（FactoryMaterialRequisitionController）。
2. 无接口（同第 1 节）。
3. 无统一导出（ShipmentController 无 export）。

状态：**部分**（Cretas 原生发货/出库=有；跨场景统一「出库单据」聚合视图、黑湖镜像、导出=缺）。
缺口：出库单据（销售/领料/调拨出库）统一查询接口与导出；黑湖出库单镜像。

---

## 4. /transfer/list

- 页面名：「调拨单」（lsm：LsmGapPage 黑湖调拨出/入库单只读；味美：YdwmList page=transfers 只读，写操作回原页面）。web-admin `W/views/transfer/list.vue`。
- 原型来源：`P/supply/YdwmList.vue:50`、`LsmGapPage.vue:35`。

需要的数据与动作：
1. 调拨单列表：编号、类型、调出/调入（仓库名）、调拨日期、金额、状态（草稿/在途/已签收/已完成/已取消）；关键词搜索。
2. 动作（原页面）：新建、提交申请、调出、签收、取消。
3. 导出 CSV。
4. （lsm）黑湖调拨出/入库单对照。

后端对应：
1. `GET transfers?page&size&status` `J/controller/inventory/TransferController.java:52`（1 起页，支持逗号分隔多状态，`PageResponse<InternalTransfer>`）；字段 transferNumber/transferType/sourceWarehouseId/targetWarehouseId/transferDate/totalAmount/status/items（`J/entity/inventory/InternalTransfer.java`）。**字段差异**：只有 `sourceWarehouseId/targetWarehouseId`，没有 `...WarehouseName`（原型 pick 会回退显示 ID）；状态枚举为 DRAFT/REQUESTED/APPROVED/REJECTED/SHIPPED/RECEIVED/CONFIRMED/CANCELLED/REVERSED（:77 提示串），原型 `S_TR` 里 IN_TRANSIT/COMPLETED 不存在、缺 REQUESTED/APPROVED/REJECTED/CONFIRMED/REVERSED。
2. 新建 POST :39、request :94、ship :107、receive :120、confirm :173、cancel :211、改数量 :186、选源批次 :247、可选批次 :235；仓内分段发运/收货 :261/:279、交付状态 :270；差异处理 `transfer-diffs` GET :41、`/decide` :63（`TransferDiffController.java`）；统计 :224。
3. 无导出。
4. 黑湖单据：无接口。

状态：**部分**（Cretas 原生=有；调出在途不计入调入仓库存等规则未核；导出缺、仓库名缺、黑湖对照缺）。
缺口：列表补仓库名字段（小）；调拨导出（小）；原型状态标签映射需按真实枚举重做（小，前端侧）。

---

## 5. /warehouse/inventory-query

- 页面名：库存查询（lsm：StockPage 黑湖余额 + 未返回余额 + 进销存 + 补充记录；味美：YdwmInventory 按物料汇总/批次明细，原料+成品）。web-admin 为 5 个 tab 的 hub（`W/views/warehouse/inventory-query/index.vue`）。
- 原型来源：`P/supply/StockPage.vue`、`P/supply/YdwmInventory.vue`。

需要的数据与动作：
1. 原料批次：物料、批次、仓库、在库、**可用**、单位、入库日、质量状态。
2. 成品批次：产品、批次、仓库、生产数、已发数、可用、单位、入库日。
3. 仓库列表（筛选）。
4. 汇总视图（同物料同仓同单位合计）、零/负库存标记、读取时点。
5. （lsm）黑湖返回余额 + 「未返回余额」组合 + 现场补充（补一个在库数、可撤销留痕）。
6. 导出 CSV。
7. 负库存核对方向（黑湖单据推导）。

后端对应：
1. `GET material-batches?page&size` `J/controller/MaterialBatchController.java:349`（`MaterialBatchDTO`：currentQuantity、reservedQuantity、warehouseId/Name、receiptDate、expireDate、status、unitPrice）。**字段差异**：没有 `availableQuantity`、`qualityStatus`、`remainingQuantity`（原型读它们会显示「未返回」）；可用量口径=`currentQuantity − unsettledConsumption`，且 `unsettledConsumption` 只有 `GET inventory/by-warehouse` 填充（`J/dto/material/MaterialBatchDTO.java:115`，`WarehouseInventoryController.java:95`）。
2. `GET sales/finished-goods?page&size` `J/controller/inventory/SalesController.java:954`（`FinishedGoodsBatch`：producedQuantity、shippedQuantity、reservedQuantity、`getAvailableQuantity()`、warehouseId、productionDate/expireDate、status）。只有 warehouseId，没有 warehouseName。另有 `GET finished-goods/inventory` `FinishedGoodsInventoryController.java:38`、半成品 `semi-finished/inventory`（SemiFinishedInventoryController :26）。
3. `GET factory/warehouses` `J/controller/factory/FactoryWarehouseController.java:42`；`GET inventory/warehouses` `WarehouseInventoryController.java:224`。
4. 汇总：`GET material-batches/stock-summary` `MaterialBatchController.java:1056`（按物料汇总，不分仓、不含客户自有料，缺单价整组总价为空，PR #1756）；分仓 `GET inventory/by-warehouse` :95（仓库归属校验、跨厂可见网络）。**不含成品汇总**（成品由 web-admin 前端汇总 `W/views/warehouse/inventory-total/FinishedGoodsSummary.vue`）。
5. 黑湖余额与「未返回余额补充」：无接口（原型专用）。正式端对应能力是期初建账 `POST material-batches/opening` `MaterialBatchController.java:175` 与盘点（第 9 节），不是「补一个数」。
6. 批次导出 `GET material-batches/export` :1364；台账导出见第 6 节。成品导出无。
7. 负库存：Java 以「负库存拦截」为准（审计表标「正式更严」），不产生负库存，故该功能不需要。

状态：**部分**（原料/成品/仓库/汇总=有；「可用」字段、成品分仓汇总、成品导出=缺；黑湖余额/未返回补充=原型专用+缺镜像）。
缺口：见缺口汇总 G1、G2、G3。

---

## 6. /lsm/stock-summary

- 页面名：进销存汇总（lsm：StockPage summary，产品×仓库×单位，期初/入/出/期末+金额；味美：YdwmStock，另含「库龄与呆滞」「补货建议」两个页签）。web-admin 无此路由（并入库存总查询）。
- 原型来源：`P/supply/StockPage.vue:55-93`、`P/supply/YdwmStock.vue`。

需要的数据与动作：
1. 期间（开始/结束）内按 产品×仓库×单位 的期初、入库（分场景）、出库（分场景）、期末。
2. 估值金额：单价（来源）、入/出/期末金额、合计（仅已估值部分）；缺价为空不补 0；单位不同不乘。
3. 成品也进汇总（味美）。
4. 库龄与呆滞：按批次入库日/保质期分档（正常/临期/已过期/长期未动>90 天/未录保质期）。
5. 补货建议：库存线+在途+在制−待发−待领−未清需求，逐项列出。
6. 补期初（本地补充）、导出 CSV。

后端对应：
1. `GET inventory/ledger?startDate&endDate&materialTypeId` `J/controller/inventory/InventoryLedgerController.java:77`；行字段 openingQty/inboundQty/outboundProductionQty/outboundSalesQty/transferInQty/transferOutQty/adjustQty/stocktakeProfit/LossQty/closingQty（`InventoryLedgerLineDTO`）；期初来自月结快照滚算（`InventoryLedgerServiceImpl.java:109-123,350`）。**只按物料类型，没有仓库维度、没有成品**；按大类 `/by-kind` :143；导出 `/export` :110、`/by-kind/export` :170。
2. 金额：同一 DTO 的 openingAmount/inboundAmount/outboundAmount/closingAmount/movingAvgUnitPrice（带价格权限遮蔽）；另 `GET material-batches/inventory/valuation` `MaterialBatchController.java:1011`。成品估值：原型读 `/__account/product-cost`（原型专用）；正式对应成本模块，规则未核。
3. 成品进销存：无接口。
4. 库龄/呆滞：只有临期 `GET material-batches/expiring` :526、`/expired` :542、`/inventory/expiry-warnings` :1249（30/60/90 天三级，只统计未来 90 天内到期）；批次列表带 receiptDate/expireDate 可前端算 90 天未动；呆滞只作为 SmartBI 聚合指标 `InventoryHealthAnalysisServiceImpl.java:587`，无批次级「长期未动」列表接口。
5. 补货：`GET restock-board` `J/controller/RestockBoardController.java:33`（订单需求 vs 库存/在制/缺口，按交货日；含 /horizon :43、/by-warehouse :54）；物料库存线 `RawMaterialTypeDTO.minStock/maxStock`、低库存 `material-batches/low-stock` :1099、`raw-material-types/low-stock` :276。原型公式（库存线+在途−待发−待领−未清需求）与 RestockBoard 口径是否一致：规则未核。
6. 补期初：期初建账 `POST material-batches/opening` :175 + 月结快照；导出台账见 1。

状态：**部分**（原料进销存+金额+导出=有；仓库维度、成品、期间内分场景明细、批次级库龄呆滞=缺；补货=部分/规则未核；黑湖版进销存=缺镜像）。
缺口：G4、G5、G6。

---

## 7. /warehouse/inventory-adjustments

- 页面名：库存调整单（lsm：只读说明，调整在黑湖办理；味美 YdwmAdjust：单条成品批次小幅调整）。web-admin 无独立路由（原料调整在 `W/views/warehouse/inventory/index.vue`）。
- 原型来源：`P/supply/YdwmAdjust.vue`、`SupplyRecords.vue:70`。

需要的数据与动作：
1. 成品批次列表（选批次，看生产数/已发数）。
2. 提交调整：调整量（可正可负）、原因（计量误差/破损/过期报废/找到遗漏/期初更正/其他，其他必填说明）、referenceType、预览→确认→回读。
3. 规则：不得低于已发数；>50% 建议走盘点；原料批次只能走盘点。
4. 已完成调整记录列表（本次会话内）。

后端对应：
1. `GET sales/finished-goods` `SalesController.java:954`。
2. `POST sales/finished-goods/{batchId}/adjust` `SalesController.java:273`，请求 `AdjustFinishedGoodsRequest(adjustmentQuantity, reason, referenceType∈OPENING_CORRECTION/STOCKTAKE/SCRAP/OTHER)`，调整后可用量为负返回 422，每次写 `finished_goods_adjustment_log`。原型的 `OTHER/SCRAP/OPENING_CORRECTION` 取值全部在枚举内。
3. 「不低于已发数」由「可用量不得为负」覆盖（等价性规则未核）；「>50% 走盘点」是原型 UI 规则，后端无对应校验。原料批次：原型说「味美没有单条调整接口」与事实不符——Java 有 `POST material-batches/{batchId}/adjust` `MaterialBatchController.java:629`（入参为调整后绝对数量 `quantity`+必填 `reason`，拒收 adjustmentType）。
4. 调整记录查询：无独立列表接口（成品调整日志表有，未见读取接口），原型本身只显示本会话已提交记录。

状态：**有**（成品调整动作与字段齐；原料调整也有）。六膳门「黑湖办理」只读说明=原型专用。
缺口：调整记录历史查询接口（G7，小，非原型必需）。

---

## 8. /lsm/inbound-batches

- 页面名：入库批次（六膳门专用，Cretas 主来源；三个页签：建批次、批次库存、对账）。
- 原型来源：`P/supply/BatchPage.vue`，假后端 `P/supply/supply-batch.cjs`（`/__lsm/supply/batches|batch|batch-revoke|batch-no|batch-reconcile`）。web-admin 无此路由。

需要的数据与动作：
1. 待建批次列表：黑湖入库单（成品入库/采购入库）里还没建批次的行。
2. 建批次：产品、数量、批次号（按产品+日期建议）、生产日期、保质期天数→自动算到期日、存放条件、关联黑湖单号+行、关联工单；数量可取自称重良品（最后一道工序净重）；已过期必须写原因。
3. 产品默认值（保质期/存放条件）保存。
4. 批次库存：按到期日排序、剩余天数、临期（≤保质期 20%，至少 3 天）/过期标色；先到期先出建议；检验待处置（HOLD/BLOCKED）批次不进先出建议。
5. 撤销批次（必填原因），对应黑湖行回到待建。
6. 对账：Cretas 批次数量 ↔ 黑湖入库单行（一致/差额/只在 Cretas/只在黑湖，单位不同单列）。

后端对应：
1. 无接口（黑湖单据）。
2. **正式端无「手工建批次」**：`POST material-batches` `MaterialBatchController.java:150` 是已停用墓碑，直接返回 409（`directInventoryWriteDisabled()`，注释写明新库存只能由仓储来源任务/盘点/受控调整/期初建账写入）。可用的建库路径：收货确认 `warehouse/receiving/receipts/{id}/confirm`（必须关联采购单，`WarehouseReceivingController.java:177,217`）、期初建账 `POST material-batches/opening` :175（`OpeningInventoryItem` 有 batchNumber/productionDate/expiryDate/quantity，但无关联黑湖行、无称重来源）、成品由生产入库（`warehouse/transit-ledgers` `ProductionWarehouseReceiptMobileController.java:97,117`）。数据模型已有承载位：`MaterialBatch.sourceDocType/sourceDocId/sourceLineNo`（`CreateMaterialBatchRequest.java:89-102`）、`expiredOnCreateReason`（`MaterialBatchDTO.java:193`）、`productionDate/expireDate/storageLocation`，**可复用但缺入口**。
3. 保质期默认：`raw-material-types` 有 `shelfLifeDays/storageType`（`RawMaterialTypeDTO.java:79-83`）；产品侧默认值规则未核。
4. 批次库存：`GET material-batches` :349、先到期先出 `GET material-batches/fefo/{materialTypeId}` :500、`/expiring` :526、`/expired` :542；剩余天数 `remainingDays` 字段 `MaterialBatchDTO.java:241`；检验待处置：批次状态 `PUT material-batches/{id}/status` :800（INSPECTING 等），与原型 HOLD/BLOCKED 的映射规则未核。成品批次走 `sales/finished-goods`。
5. 撤销：`DELETE material-batches/{batchId}` :260（仅管理员/仓储主管，无原因字段）；更规范的是调整/作废：成品 `POST sales/finished-goods/{id}/void` `SalesController.java:294`（需已发=0 且预留=0）。「撤销原因必填且留痕、对应来源行回到待建」无对应。
6. 对账：无接口（Cretas↔黑湖对账只在原型 `/lsm/reconcile`、`batch-reconcile`）。

状态：**部分**（批次库存/先到期先出/临期过期=有；手工建批次（含黑湖行关联、称重取数、过期原因）、撤销留原因、对账、黑湖待建清单=缺）。这一页是融合里最大缺口，且与 Java「禁止无来源建批次」的红线冲突，需 Steve 裁决建批次入口走哪条合规路径。
缺口：G8、G9。

---

## 9. /warehouse/stocktake-query

- 页面名：盘点任务（lsm：黑湖无盘点数据，缺口页；味美：YdwmList page=stocktakes 只读；web-admin 为「盘点与期初库存/半成品盘点」hub `W/views/warehouse/stocktake-query/index.vue`）。
- 原型来源：`P/supply/YdwmList.vue:53`、`LsmGapPage.vue:39`。

需要的数据与动作：
1. 盘点单列表：单号、仓库、基准时间、发起时间、复核人、状态（已发起/盘点中/待复核/已批准/已生效/已驳回/已取消）。
2. 动作（回原页面）：发起、录入、复核（他人复核后才调整库存）。
3. 搜索。

后端对应：
1. `GET stocktakes?status&page&size` `J/controller/StocktakeController.java:149`。**两处适配问题**：该接口 `page` 为 0 起（`PageRequest.of(page,size)`），原型发 `page=1` 会跳过第一页；`StocktakeDTO` 字段为 stocktakeNo、warehouseId（无仓库名）、inventoryCutoffAt、initiatedAt、approvedByDisplay，原型读 `stocktakeNumber/planNumber/warehouseName/baselineAt/approvedByName` 会全部落到「未记录」；状态枚举为 INITIATED/COUNTING/PENDING_APPROVAL/APPROVED/APPLIED/REJECTED/RECOUNT_REQUIRED/CANCELLED（`FactoryStocktake.java:45`），原型的 SUBMITTED/APPROVED_APPLIED 不存在。
2. 发起 POST :63（月底约束）、录入 `PUT /{id}/items` :200、提交 :213、批准 :237、驳回 :248、应用 :259、差异预览 :272、取消 :89、漂移后重盘 :75、详情 :161；批量导入 `stocktakes/bulk-import/template|preview|confirm` `StocktakeBulkImportController.java:48,64,84`；半成品盘点 `semi-finished-stocktakes`（SemiFinishedStocktakeController :48）。
3. 搜索：后端无关键词参数（前端过滤）。

状态：**有**（动作全有；列表字段名与页码需适配，规则未核）。六膳门「黑湖没有盘点」=黑湖镜像缺。
缺口：G10（适配，小）。

---

## 10. /warehouse/storage-locations

- 页面名：仓库档案与布局（lsm：WarehousePage，黑湖仓库档案+Cretas 本地区域/库位/底图/拖拽摆放）。web-admin `W/views/warehouse/storage-locations/index.vue`。
- 原型来源：`P/supply/WarehousePage.vue`，`/__lsm/supply/layout`。

需要的数据与动作：
1. 仓库列表：编码、名称、状态、有余额/零/负库存计数、本地布局区域数/库位数/底图有无。
2. 编辑布局：新增/删除区域（名称唯一、前缀）、新增/删除库位（编码唯一、归属区域；区域下有库位不可删）、上传底图（≤20MB，压缩 1600px）、拖拽/缩放区域、乐观锁保存+回读。
3. 从仓库跳到库存查询。

后端对应：
1. `GET factory/warehouses` `FactoryWarehouseController.java:42`；增/改/删 :55/:84/:107；`apply-template` :132。仓库内库存计数：`GET inventory/by-warehouse` `WarehouseInventoryController.java:95`。
2. 布局：`GET warehouse/layout` `J/controller/WarehouseLayoutController.java:57`、`PUT warehouse/layout` :65（仅 factory_super_admin/warehouse_manager，`validateLayout`）、识图 `POST /recognize` :84、成品批次库位 `PUT /finished-goods/{batchId}/location` :123。**口径差异**：Java 布局存为**整厂一份**（zones/locations/floors，存于 `FactoryFeatureConfig`），原型是**每仓库一份**布局+底图；有无底图字段、乐观锁 expectedVersion、「区域下有库位不可删」「库位唯一」由 `validateLayout` 校验的范围：规则未核。物料批次库位 `PUT material-batches/{id}` 的 `storageLocation` :235。
3. 跳转：纯前端。

状态：**部分**（仓库档案与布局保存=有；按仓库分层布局、底图存储、版本号乐观锁=缺/差异）。
缺口：G11。

---

## 11. /warehouse/material-types

- 页面名：物料/原料类型字典（lsm：来源类型计数，黑湖无字典；味美：YdwmList page=materials 只读）。web-admin `W/views/warehouse/material-types/list.vue`。
- 原型来源：`P/supply/YdwmList.vue:52`、`LsmGapPage.vue:42`。

需要的数据与动作：
1. 列表：编码、名称、单位、分类、用途（辅料/包材/原料/主料）、储存（冷冻/冷藏/常温）、参考价、启停；同名同编码标疑似重复。
2. 动作（回原页面）：新建、编辑、停用、分类树、别名/合并。
3. （lsm）黑湖产品「来源类型」分布计数。

后端对应：
1. `GET raw-material-types?page&size&keyword&classificationId&materialKind` `J/controller/RawMaterialTypeController.java:166`（1 起页）；`/active` :195；字段 code/name/category/mainCategory/unit/storageType/materialReferencePrice/shelfLifeDays/isActive/minStock/maxStock/aliases（`RawMaterialTypeDTO`）。**字段差异**：原型「用途」读 `materialCategory`，DTO 为 `mainCategory`（原料/辅料/包材）；`storageType` 注释为 fresh/frozen/dry，原型映射 FROZEN/CHILLED/DRY，需按真值对齐。
2. 新建 :67、改 :103、删 :140、停用批量 `PUT /batch/status` :289、别名 :403/:412/:421、合并检查 :430、合并 :438、编码预览 :355、建议 :375、单位建议 :304、查重 :317。
3. 黑湖来源类型计数：无接口（黑湖镜像）。

状态：**有**（Cretas 原生）；六膳门「黑湖来源类型计数」=原型专用。
缺口：仅字段对齐（`mainCategory`、storageType 取值），小。

---

## 12. /warehouse/material-segments

- 页面名：物料分类字典（lsm：黑湖无大类/中类字段，纯缺口页；味美无）。web-admin `W/views/warehouse/material-segments/index.vue`。
- 原型来源：`P/supply/LsmGapPage.vue:43`（gap 文案：黑湖没有物料分类字段，不顶替）。

需要的数据与动作：
1. 无（原型只写缺口说明）。正式页需要三层分类树的查看/增删改/恢复。

后端对应：
1. `GET material-segments` `J/controller/MaterialCodeSegmentController.java:39`、树 `/tree` :48、新增 :55、改 :64、删 :74、已删除 :85、恢复 `/{id}/restore` :94；`MaterialCodeSegmentDTO(level, segmentLabel, parentId, sortOrder, isActive, children)`。web-admin 已接 `W/views/warehouse/material-segments/index.vue:106`。

状态：**有**（原型本身无数据需求；正式能力完整）。
缺口：无。

---

## 13. /warehouse/wastage-reports

- 页面名：报损管理（lsm：Cretas 补充入口，黑湖无数据；记事实，不扣库存）。web-admin `W/views/warehouse/wastage-reports/index.vue`。
- 原型来源：`P/supply/LsmGapPage.vue:47`，字段定义 `P/supply/supply-gap.cjs` KINDS.wastage，`/__lsm/supply/gap-record|gap-revoke`。

需要的数据与动作：
1. 列表：报损日期、物料或产品、批次号、数量、单位、仓库、原因、来源；搜索。
2. 新增：日期（不得晚于今天）、物料/产品、批次号（选填）、数量>0、单位、仓库（选填）、原因。
3. 撤销（必填原因，留痕）。
4. 导出。原型明确：报损后的质量处置、财务报废审批「不在本页」。

后端对应：
1. `GET wastage-reports?trackType&status&page&size`（0 起页）`J/controller/WastageReportController.java:63`；DTO：reportNo、trackType、warehouseId、materialBatchId、rawMaterialTypeId、wastageQty、wastageReason、reasonDetail、photoUrls、status、submittedBy/At、approverRole、approvedBy/At、rejectReason、appliedAt、notes、createdAt。**没有单位、物料/批次名称、报损日期字段**（需用批次关联取名，日期取 createdAt）。
2. 新增 `POST wastage-reports` :50：`CreateWastageReportRequest` 必填 trackType、**materialBatchId（必须关联批次）**、wastageQty、wastageReason（枚举）、**photoUrls（至少 1 张照片，强制）**。原型「批次号选填、无照片」与 Java 不一致，Java 更严。
3. 撤销：无。Java 流程是 提交 `/{id}/submit` :99 → 审批 `/approve` :111（原子扣减库存+审计）/驳回 `/reject` :125；待审批 `/pending` :85；详情 :76。
4. 导出：无。

状态：**部分**（报损创建/审批/扣库存=有，且比原型多出审批与扣库存；原型「无批次/无照片/本地撤销/不扣库存」与正式口径冲突，导出缺）。
缺口：G12（口径裁决：六膳门是否也强制批次+照片、是否扣库存；导出，小）。

---

## 14. /warehouse/source-individuals

- 页面名：源头个体档案（lsm：Cretas 补充入口，黑湖无活体/胴体来源数据）。web-admin `W/views/source-individuals/index.vue`。
- 原型来源：`P/supply/LsmGapPage.vue:48`，字段 `supply-gap.cjs` KINDS.source。

需要的数据与动作：
1. 列表：个体编号、类型（活体/胴体）、品种、来源（养殖场/供应商）、进场日期、关联入库批次、来源；搜索。
2. 新增（编号唯一；进场日期不晚于今天）。
3. 撤销（必填原因）。
4. 导出。

后端对应：
1. `GET source-individuals?page&size&keyword` `J/controller/SourceIndividualController.java:58`（1 起页；keyword 命中编号/耳标/物种/品种/来源场）；`/by-tag/{tagNumber}` :77；`/{id}` :87；`/{id}/detail` :102（溯源详情）。`SourceIndividualDTO`：individualCode、tagNumber、species、breed、sex、birthDate、originFarm、dna 字段、父母个体、extAttributes、notes、version。批次侧 `MaterialBatch.sourceIndividualId` 提供批次→个体关联（`MaterialBatchDTO.java:209`）。
2. 新增 POST :113；修改 :126（version）；删除 :139。编号唯一性规则未核。
3. 字段差异：**无「类型（活体/胴体）」字段、无「进场日期」字段**（可放 extAttributes，需约定）；「关联入库批次」是批次侧反向引用，不在个体上。
4. 撤销（带原因）：无，只有删除 :139。导出：无。

状态：**部分**（档案 CRUD/按耳标反查/溯源详情=有；类型、进场日期字段、撤销留原因、导出=缺）。
缺口：G13。

---

## 15. /warehouse/material-price-trend

- 页面名：物料均价趋势（lsm：由黑湖采购订单明细按「产品+月份」加权平均，只取单价>0 且数量>0 的行；不混算库存成本均价）。web-admin `W/views/warehouse/MaterialPriceTrendView.vue`。
- 原型来源：`P/supply/LsmGapPage.vue:46`（trendRows 计算）、`P/cost/BomPriceAdvice.vue:42`。

需要的数据与动作：
1. 编码、产品、月份、均价、采购数量、单位、单据行数（按产品×月份）。
2. 选物料和时间看走势；空数据与单位限制如实显示。
3. 导出。

后端对应：
1. **无专门的采购均价趋势接口**。web-admin 前端自己用 `GET material-batches`（`W/views/warehouse/MaterialPriceTrendView.vue:111`）取批次，按 `unitPrice>0` 过滤后在浏览器里算（:141,152），另取 `raw-material-types/active` 做物料列表；字段 `unitPrice`、`movingAvgPrice`（`RawMaterialTypeDTO.java:116`）。Java 里只有**销售**价格趋势（`SalesPriceTrendDTO`、`SalesController`），不是采购价。
2. 口径差异：原型取**采购订单明细**加权，web-admin 取**入库批次**单价；采购单明细读取接口 `purchase/orders`（`J/controller/inventory/PurchaseController.java`）有，聚合规则未核。批次价受价格权限遮蔽（`@PriceSensitive`，`MaterialBatchDTO.java`）。
3. 导出：无。

状态：**部分**（可用现有接口由前端组装，无服务端聚合/权限一致的趋势接口，导出缺）。
缺口：G14。

---

# 缺口汇总

| # | 缺什么 | 建议落点 | 大小 |
|---|---|---|---|
| G1 | 原料批次列表缺 `availableQuantity`/`qualityStatus`/`remainingQuantity` 与统一「可用」口径（仅分仓接口带 unsettledConsumption）；成品只有 warehouseId 无仓库名 | `MaterialBatchController#getMaterialBatchList` DTO 填充，`SalesController#listFinishedGoods` 补仓库名 | 小 |
| G2 | 成品分仓/汇总库存接口（现靠 web-admin 前端汇总）；成品导出 | `FinishedGoodsInventoryController` 增汇总与导出 | 中 |
| G3 | 六膳门黑湖快照读取（余额、出入库单、仓库）：正式端无任何接口；需裁决是否做 | 新建 Blacklake 只读镜像 Controller（Java）或把 `backend/python/blacklake` 并入主服务 | 大（先裁决） |
| G4 | 进销存按「仓库」维度、含成品、期间内分场景入/出明细 | `InventoryLedgerService` 增 warehouse/成品维度（`InventoryLedgerController`） | 大 |
| G5 | 批次级库龄与呆滞（>90 天未动、未录保质期）列表 | `MaterialBatchController` 增 `inventory/aging`（或 `InventoryHealthAnalysisService` 暴露批次级） | 中 |
| G6 | 补货建议与原型公式（库存线+在途+在制−待发−待领−未清需求）对齐，规则未核 | `RestockBoardService` 增物料库存线口径 | 中 |
| G7 | 成品/原料库存调整记录查询（调整单历史列表） | `SalesController` 与 `MaterialBatchController` 增调整日志读取 | 小 |
| G8 | 入库批次建批次入口：按来源单据行建批次（含生产/到期日、称重来源、过期原因、关联黑湖行）；现 `POST material-batches` 为 409 墓碑，需 Steve 裁决合规路径 | `WarehouseReceivingController` 新增「无采购单来源任务」收货，或 `MaterialBatchController` 受控来源建账 | 大 |
| G9 | 批次撤销带必填原因并使来源行回到待建；Cretas↔黑湖入库批次对账 | `MaterialBatchController`（撤销）+ 新对账服务（依赖 G3） | 大 |
| G10 | 盘点列表适配：`page` 0 起、字段 stocktakeNo/inventoryCutoffAt/approvedByDisplay、仓库名、状态枚举；调拨列表补仓库名与状态枚举 | `StocktakeController`、`TransferController` DTO，或原型适配层 | 小 |
| G11 | 按仓库分层的布局与底图存储（Java 为整厂一份） | `WarehouseLayoutController` 增 per-warehouse 布局与版本号 | 中 |
| G12 | 报损口径：Java 强制批次+照片、审批后扣库存；原型六膳门为记事实不扣库存；另缺导出、单位/名称字段 | 裁决后改 `WastageReportController`/DTO | 小（先裁决） |
| G13 | 源头个体缺「类型（活体/胴体）」「进场日期」「撤销留原因」「导出」 | `SourceIndividualController` + `SourceIndividual` 实体/DTO | 小 |
| G14 | 采购均价趋势服务端聚合（按产品×月份加权、价格权限一致）与导出 | `PurchaseController` 或 `MaterialBatchController` 增 `price-trend` | 中 |
| G15 | 出库单据统一查询（销售/领料/调拨出库）与导出；入库单据按场景聚合 | `ShipmentController` / `InventoryLedgerController` 增单据流水接口 | 中 |

---

# 完成度与说明

- 路由 15/15 已核对。状态计数：有 4（inventory-adjustments、material-types、material-segments、stocktake-query）；部分 11（materials、unordered-inbound-applications、shipments、transfer/list、inventory-query、stock-summary、inbound-batches、storage-locations、wastage-reports、source-individuals、material-price-trend）；缺 0；原型专用 0（但每页的「黑湖镜像/本地补充」部分均标为原型专用或缺镜像，见各节）。
- 未核事项：各规则实现（库存扣减、调拨在途、先到期先出排序、盘点复核限制、月结快照期初滚算等）只确认了接口存在与返回字段，规则未核；原型对 `/api/mobile/FOOD_0000_001/` 的代理是否做页码转换未验证（G10 的 0 起页问题按代码推断）。
- 参考表 `prototypes/BACKEND-MERGE-MAP.md` 第二节「库存/仓库」把多项标「已有」，本次逐字段核对后补出的新发现：`POST material-batches` 为停用墓碑（入库批次页最大冲突）、盘点/调拨/物料字典的字段与状态枚举与原型不一致、成品进销存无服务端接口、报损与源头个体口径差异。
