# map-B：采购 / 销售 / 交付风险 / 缺料 页面 → 后端接口逐页对照

只读调研，未改仓库。日期 2026-11-17/18。

## 读法与口径（先读）

路径缩写：
- `JC/` = `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/`
- `JS/` = 同目录上一层的 `service/`、`entity/`、`dto/`
- `PY/` = `backend/python/`
- `UV/` = `prototypes/unified-v1/src/`
- `WA/` = `web-admin/src/`

**同一路由在原型里有两种渲染，数据来源完全不同，状态分开写：**
- 味美变体（ws=ydwm）：`UV/supply/Ydwm*.vue` 直接调用 `/api/mobile/{factoryId}/...`，路径就是 Java 的真实路径。所以只要路径存在、字段名对得上，就是「有」。
- 六膳门变体（默认，lsmOnly）：`UV/supply/LsmExtras.vue`、`LsmGapPage.vue`，数据来自 `/__lsm/supply/extras`，也就是黑湖导出文件 `extras-current.json`，只读。正式后端对应物是 `PY/blacklake/live_extras.py:74-88` 的采集计划（sale_order、purchase_order、purchase_requirement、receivable、receipt、customer_ledger、vendor_ledger、invoicing、outsource_*、customer、vendor、customer_price、vendor_price），落在 `blacklake.record` 表（`PY/blacklake/migrations/001_blacklake.sql:15`）。**检索结果：Java 里没有任何 blacklake 引用；Python 里 blacklake 包只暴露 `/live-monitor`（`live_api.py:13`）、`/period-reports`、`/analysis`、`/labor-supplements` 等分析接口，没有按资源读取列表和详情的接口。** 所以六膳门变体的「读黑湖单据列表」在正式后端是「缺」，下文每页统一记为「LSM 缺」，汇总成一条大缺口（G1）。
- 路由 `/sales/shipments` `/sales/returns` `/procurement/requisitions/pending-approval` `/procurement/ai-purchase` `/procurement/price-lists` `/sales/vehicles` `/sales/payment-requests` `/production/shortage-cases` 在 `UV/frame/routes.ts` 里是 `lsmOnly:true`，味美侧不渲染统一原型页，办理走原页面（web-admin 已有同名页，见各节）。

**状态判定**：有 = 每项数据和动作都有接口；部分 = 缺哪几项写明；缺 = 没有任何对应；原型专用 = 只在原型成立。下表「状态」默认指**味美/Java 原生**；六膳门变体另注。规则实现只读了关键方法，没逐条核的写「规则未核」。

**检索覆盖**：读了各 Controller 的映射行与关键 Service 方法（PurchaseRequisitionServiceImpl、ReturnOrderServiceImpl、VehicleServiceImpl、SupplierServiceImpl、CustomerServiceImpl、DeliveryRiskService/Evaluator），读了实体 SalesOrder、PurchaseOrder、ReturnOrder、Customer、Supplier、PriceList、Vehicle、PurchaseRequisition、SalesDeliveryRecord、ArApTransaction 的字段，并核对 web-admin 路由。Python 侧用关键词检索：采购/销售/请购/付款申请/交付风险/缺料在 `PY/` 里除 blacklake 采集外没有对应实现。

---

## 1. /procurement/requisitions/my　我的请购（味美标题「我的请购」，六膳门标题「采购需求」）

**原型来源**：味美 `UV/supply/YdwmList.vue` CONF.requisitions（`:CONF` 约 34 行，api `purchase-requisitions?page=1&size=500`）；六膳门 `UV/supply/LsmExtras.vue` CONF.requirement（黑湖 purchase_requirement）。

**需要的数据与动作**
1. 列表：单号 requisitionNumber、物料×数量（requestedItems：materialName、quantity、unit）、原因 reason、期望交货、创建时间、状态（草稿/待审批/已批准/已驳回/已转采购…）。
2. 状态筛选、搜索、CSV 导出（前端做）。
3. 详情：明细行。
4. 动作（原页面办理）：新建草稿、提交审批、审批、驳回、转采购订单、删除草稿。
5. 六膳门：采购需求（需求量、已下达、已收、缺料、计划到货、建议供应商），只读，审批在黑湖。

**后端对应**
1. `GET /api/mobile/{factoryId}/purchase-requisitions`，`JC/inventory/PurchaseRequisitionController.java:43-76`（list），`PurchaseRequisition` 实体字段 `requisitionNumber/requestedItems/reason/expectedDate/status/createdAt`（`JS/entity/inventory/PurchaseRequisition.java:76-132`）。
2. 状态筛选有（`status` 参数）；「只看我的」靠 `requesterId` 参数。
3. `GET .../purchase-requisitions/{id}`，`PurchaseRequisitionController.java:79`。
4. 新建 `POST ...` （`:43-60` 区）、提交 `POST /{id}/submit :88`、审批 `/approve :100`、驳回 `/reject :112`（必须写原因）、转 PO `/convert-to-po :126`（幂等，必须带 supplierId）、删除草稿 `DELETE /{id} :150`（仅 DRAFT、仅本人）。
5. 六膳门：LSM 缺（G1）。

**状态**：部分（LSM 缺）。

**缺口**
- 实测缺陷（真实 bug，非缺接口）：`PurchaseRequisitionServiceImpl.java:210-222` 的 `list` 是「status 优先，否则 requesterId」二选一。web-admin `WA/views/procurement/requisitions/my-list.vue:56-57` 同时传 `requesterId` 和 `status`，选了状态后「我的请购」会列出全厂的单。需要两个条件同时生效。
- 字段名对不上：原型读 `expectedDeliveryDate/requiredDate`（`YdwmList.vue` requisitions.due），Java 实体是 `expectedDate`，页面会显示「未填写」。
- 状态枚举对不上：Java `PurchaseRequisitionStatus` 是 DRAFT/PENDING_APPROVAL/APPROVED/CONVERTED_TO_PO/REJECTED；原型 `S_RQ` 写的是 CONVERTED，没有 CONVERTED_TO_PO，已转采购的单会显示英文原值；原型的 SUBMITTED/CANCELLED/WITHDRAWN 在 Java 不存在（没有撤回/取消）。
- 请购行单位缺失时 Java 默认「件」（`PurchaseRequisitionServiceImpl.java:~270` mapItemsToPoItems）：规则未核是否符合「不能算就不算」。
- 「按单位分开」等规则：规则未核。

---

## 2. /procurement/requisitions/pending-approval　待审批请购

**原型来源**：六膳门 `LsmGapPage.vue` CONF.pending（黑湖采购需求 approveStatus≠1，只读）；味美侧与上一页同一个 `YdwmList` requisitions（「本人与待审批请购同一列表」），无独立页。

**需要的数据与动作**
1. 待审批清单（单号、物料、数量、缺口、计划到货、审批状态）。
2. 审批 / 驳回（味美在原页面办理；六膳门在黑湖办理，原型不提供）。

**后端对应**
1. `GET /purchase-requisitions?status=PENDING_APPROVAL`，`PurchaseRequisitionController.java` list；web-admin 已用：`WA/views/procurement/requisitions/pending-approval.vue:60`。
2. `POST /{id}/approve :100`，`POST /{id}/reject :112`。

**状态**：有（味美/Java）；LSM 缺（G1）。

**缺口**：无独立缺口。注意审批权限 `procurement:read_write`，自批限制：规则未核。

---

## 3. /procurement/orders　采购订单

**原型来源**：味美 `UV/supply/YdwmPurchase.vue` + `YdwmDraft.vue`；六膳门 `LsmExtras.vue` CONF.purchase。

**需要的数据与动作**
1. 列表：单号、供应商名、物料摘要、下单日、**采购交货日期**、订购·已收·待收（按单位）、金额（无价格权限时整列为空）、状态四分组（未完成/执行中/已完成/已取消）。
2. 收货任务汇总（订购/已收/待收/超收规则），关联采购单。
3. 详情：明细行、这张单引起的收货记录。
4. 动作：新建草稿（带价目表参考价，记录单价来源 价目表/手改/手填 与偏离%）、提交、审批、取消、关闭、收货。
5. 六膳门：黑湖采购订单（含关联销售单页签）只读。

**后端对应**
1. `GET /api/mobile/{factoryId}/purchase/orders`，`JC/inventory/PurchaseController.java:120`。实体含 `orderNumber/supplierName/orderDate/expectedDeliveryDate/totalAmount/status/items`（`JS/entity/inventory/PurchaseOrder.java:79-247`），采购交货日期字段名对得上。关键字、日期范围过滤有。
2. `GET /warehouse/receiving/tasks`，`JC/warehouse/WarehouseReceivingController.java:65`，返回 `orderedQuantity/receivedQuantity/remainingReceivableQuantity/statusLabel/overReceiveLimitLabel`（`JS/dto/inventory/PurchaseReceivingTaskResponse.java:65-101`）。只含执行中单据，与原型「已收齐如实写明细未返回」一致。
3. `GET /purchase/orders/{id} :243`、`/orders/{id}/receives :727`、`/orders/{id}/cumulative-received :706`。
4. 新建 `POST /purchase/orders :99`；提交 `:376`、审批 `:487`、取消 `:497`、复制 `:512`、财务审核 `:528/:538/:550`、收货 `POST /purchase/receives :582`、`/receives/{id}/confirm :655`。
5. 六膳门：LSM 缺（G1）。

**状态**：部分（LSM 缺）。

**缺口**
- 新建草稿的「单价来源/参考价/偏离%」：原型 `YdwmDraft.vue` 提交 `priceSource/referencePrice/referencePriceListId/referencePriceListName/priceDeviationPct`、`status:'DRAFT'`、`notes`；Java `CreatePurchaseOrderRequest`（`JS/dto/inventory/CreatePurchaseOrderRequest.java:23-129`）无这些字段，备注字段叫 `remark` 不是 `notes`。检索 `priceSource|referencePrice|priceDeviation` 只在 AI 工具和别的 DTO，PO 明细实体没有。会被静默丢弃（Boot 默认忽略未知字段），保存后看不到来源。
- 状态枚举对不上：Java `PurchaseOrderStatus` = DRAFT/SUBMITTED/WORKFLOW_RUNNING/APPROVED/PENDING_FINANCE_REVIEW/FINANCE_APPROVED/FINANCE_REJECTED/PARTIAL_RECEIVED/COMPLETED/CANCELLED/CLOSED；原型 `YdwmPurchase.vue` STATUS 缺 WORKFLOW_RUNNING、PENDING_FINANCE_REVIEW、FINANCE_REJECTED，且用了 PARTIALLY_RECEIVED/RECEIVED/REJECTED（Java 没有），这些单会掉进「未完成」默认分组。
- 已收齐单据的收货明细：任务接口只返回执行中，已收货单与采购单的关联未返回（原型自己也写明）；`GET /purchase/orders/{id}/receives` 可补，但原型没接，规则未核。
- 列表接口分页上限未核（原型一次取 size=500）。

---

## 4. /procurement/ai-purchase　采购建议 / AI 受控采购

**原型来源**：六膳门 `LsmGapPage.vue` CONF.aipurchase（只列黑湖采购需求中缺口>0 的项，只读，不自动下单、不给建议数量）；味美无统一页，走原页面 `WA/views/procurement/ai-purchase/index.vue`。

**需要的数据与动作**
1. 有缺口的采购需求清单（需求量、缺口、计划到货）。
2. 无动作（原型明确不下单）。

**后端对应**
1. Java 没有「采购需求缺口」这个对象。最近的是：`GET /purchase/orders/suggestions/from-so/{salesOrderId}`（`JC/inventory/PurchaseController.java:785`，BOM 展开扣库存得净需求）与 `POST /purchase/orders/suggestions/from-so-multi :803`；缺料实例的 `GET /shortage-cases/{caseId}/procurement-proposals`（`JC/ShortageCaseActionController.java:85`）与 `POST .../procurement-drafts :93`。
2. web-admin 正式页是「AI 受控采购」：预览走 `POST /{factoryId}/ai-intents/execute`（`WA/api/controlledPurchase.ts:68`），授权/执行/撤销走 `JC/AiPurchaseTaskGrantController.java:32/36/47/73`，定时 `PurchaseGrantTimerBindingController`。

**状态**：部分。味美侧有更强的受控采购与建议接口，但与原型这页「按需求缺口只读列表」口径不同；LSM 缺（G1）。

**缺口**：若六膳门要保留这页，需要黑湖 purchase_requirement 的读取接口（并入 G1）。原型「不给建议数量」与 Java 建议接口（会给预填数量）是口径差异，需 Steve 裁决要不要保留。规则未核：from-so 建议与原型「缺口=需求量−已下达量」不是同一算法。

---

## 5. /procurement/suppliers　供应商

**原型来源**：味美 `YdwmList.vue` CONF.suppliers；六膳门 `LsmExtras.vue` CONF.vendors（含账本与四个页签）。

**需要的数据与动作**
1. 列表：编号、名称、联系人、电话、地址、启用/停用；「疑似重复」（同名同电话，前端算）。
2. 六膳门：账期、采购额、待收货额、待开票额、待付款、已付款（供应商账本）；详情页签：采购订单、待开票、价目表、委外任务。
3. 动作：新增、编辑、导入（批量校验）、导出。

**后端对应**
1. `GET /suppliers`，`JC/SupplierController.java:145`；实体 `code/supplierCode/name/contactPerson/phone/address/isActive`（`JS/entity/Supplier.java:50-138`），字段对得上。
2. 账本：`GET /suppliers/{id}/statistics :292` **是桩**：`JS/service/impl/SupplierServiceImpl.java:402-407` 里 `totalOrders=0、totalAmount=0` 带 `TODO`，不能给账本数。可用的是 `GET /suppliers/outstanding-balance :508` 与 `JC/finance/ArApController.java:224/303/324`（`/finance/transactions`、`/statement`、`/aging`，按 counterpartyType/counterpartyId）。待开票额：`PurchaseOrder.invoiceStatus`（实体 `:222`）可聚合，无现成接口。供应商价目：`JC/SupplierMaterialController.java:21`（`/suppliers/{id}/materials`）；该供应商的采购订单：`GET /purchase/orders?keyword`（无按 supplierId 过滤参数，规则未核）。
3. 新增 `POST :64`、编辑 `PUT :88`、导入 `POST /import/preview :424`、`/import/confirm :446`、`/import/error-report :459`、导出 `:340`。

**状态**：部分（LSM 缺）。

**缺口**
- 供应商账本汇总（采购额、待收货额、待开票额、待付款、已付款）缺：`statistics` 是桩，需要实现，或在 ArAp 上做汇总接口。
- 委外任务页签：Java 侧未检索到对应接口（`JC/` 无 outsource 控制器；web-admin 有 `WA/api/outsource.ts`，路径未核）。
- 导入行数上限：MERGE-MAP 第七节说供应商导入 1000 改 2000，已处理，本次未复核。

---

## 6. /procurement/price-lists　价格表

**原型来源**：味美 `YdwmList.vue` CONF.pricelists，`ydwm-price.ts`（匹配采购参考价）；六膳门 `LsmGapPage.vue` CONF.pricelists（黑湖供应商价目表 0 条，空态）。

**需要的数据与动作**
1. 列表：名称、类型、生效日、失效日（30 天内到期标注）、有效/停用。
2. 价格条目（按名称+单位精确匹配采购价，只认 PURCHASE_PRICE 且 isActive、日期在窗口内）。
3. 动作：新建价格表、维护条目、停用。

**后端对应**
1. `GET /price-lists`，`JC/inventory/PriceListController.java:96`；实体 `name/priceType/effectiveFrom/effectiveTo/isActive/items(itemName,unit,standardPrice)`（`JS/entity/inventory/PriceList.java:57-91`，`PriceListItem.java:51-58`）。`PURCHASE_PRICE` 枚举值存在（`PriceList.java:63` 注释）。
2. `GET /price-lists/effective :107`、`/{id} :115`、`/lookup :133`（按客户+产品查售价）。
3. 新建 `POST :56`（含条目）、删除 `DELETE :194`。

**状态**：部分（LSM 无数据，页面本身是空态）。

**缺口**
- 没有更新接口：控制器只有 创建/列表/生效/详情/lookup/删除，没有 PUT。「条目维护、停用」无接口（改只能删了重建）。
- `isActive` 来自实体 `PriceList.isActive`，但没有切换接口。
- 采购价与销售价共用同一实体按 `priceType` 区分，权限未按类型分（`PriceListController.java:50-55` 注释自己承认）。
- 采购价目表无供应商字段（`PriceList.customerId` 只服务销售），原型也按这个口径做名称+单位匹配，一致。

---

## 7. /sales/orders　销售订单

**原型来源**：味美 `UV/supply/YdwmSales.vue`（含齐套）；六膳门 `LsmExtras.vue` CONF.sales（含对照价目表偏离、N2 三态、已存视图）。

**需要的数据与动作**
1. 列表：单号、客户、产品摘要、**计划交货**（逾期天数）、订购·已发·待发（按单位）、金额、三态（未完成/进行中/已完成/已取消）与原状态。
2. 发货记录（母单+子发运）、齐套状态（BOM 展开+库存）。
3. 详情：明细、这张单引起的发货记录。
4. 动作：新建、提交审核、编辑、发货、取消；导出 CSV。
5. 六膳门：对照客户价目表的偏离（单价为 0/多条价目/无价目）、批量单号搜索、本周交货/已逾期视图。

**后端对应**
1. `GET /api/mobile/{factoryId}/sales/orders`，`JC/inventory/SalesController.java:149`（keyword、日期范围、分页）；实体 `orderNumber/customerName/orderDate/requiredDeliveryDate/status/totalAmount/items`（`JS/entity/inventory/SalesOrder.java:85-391`）。
2. `GET /sales/deliveries :871`（只有 page、size，无订单过滤）、`/deliveries/by-order/{orderId} :938`；明细 `SalesDeliveryItem.deliveredQuantity/unit`（`JS/entity/inventory/SalesDeliveryItem.java:57`）。齐套：`bom/recipes`、`sales/finished-goods :954`、`material-batches`（原型自己在前端用 `kitting.ts` 算）。
3. `GET /sales/orders/{id} :528`。
4. 新建 `POST :132`、编辑 `PUT /{id} :516`、确认 `:657`、提交审核 `:762`、财务审/驳 `:774/:791`、取消 `:681`、短交关闭 `:707`、复制 `:747`、创建发货单 `POST /deliveries :858`、发货 `/deliveries/{id}/ship :900`、签收 `:912/:922`。
5. 对照价目表：`GET /customers/{id}/price-list`（`JC/sales/CustomerPriceListController.java:35`）、`POST /sales/check-estimate-price :1125`、`/orders/{id}/lines/{lineId}/adjust-price :1151`。
6. 六膳门：LSM 缺（G1）。

**状态**：部分（LSM 缺）。

**缺口**
- 计划交货字段名对不上：原型 `DELIVERY_KEYS`（`UV/supply/ydwm.ts`）= plannedDeliveryDate/deliveryDate/expectedDeliveryDate/requiredDate，Java 是 `requiredDeliveryDate`，所以交期恒显示「未填写」，交付风险页同样受影响。原型适配即可，Java 不用动。
- 状态枚举对不上：Java `SalesOrderStatus` = DRAFT/CONFIRMED/PENDING_FINANCE_REVIEW/FINANCE_APPROVED/FINANCE_REJECTED/PROCESSING/PARTIAL_DELIVERED/COMPLETED/CANCELLED/SHORT_CLOSED；原型 `SALES3` 缺 PENDING_FINANCE_REVIEW、FINANCE_APPROVED、FINANCE_REJECTED、SHORT_CLOSED（落入「未识别」），并含 Java 没有的 PENDING_APPROVAL/SUBMITTED/APPROVED/REJECTED/SHIPPED/DELIVERED/CLOSED。
- 发货单列表无订单号/状态/日期过滤（`SalesController.java:871-880`），原型是前端取 500 条再按单号匹配，数据量大时会漏。
- 发货状态：Java `SalesDeliveryStatus` 11 种（含 PENDING_WAREHOUSE_CONFIRM、PICKED、PENDING_SPLIT…），原型 `SHIP_STATUS` 只列 6 种。
- 已存视图：原型用 `viewTabs`（按账号存 5 个）；后端有 `WA/api/savedViews.ts`，Java 端点本次未核。

---

## 8. /sales/shipments　发运记录

**原型来源**：六膳门 `LsmGapPage.vue` CONF.shipments（黑湖销售出库单，只读，来自 `readInventory` 的 orders）。味美无统一页，原页面 `WA/views/sales/shipments/list.vue`（FormalDeliveries + LegacyShipments）。

**需要的数据与动作**
1. 出库单号、类型、业务时间、状态、行数、品项、关联单号。
2. 搜索、导出 CSV；无写动作（开单在黑湖）。

**后端对应**
1. 原生发货单：`GET /sales/deliveries`，`JC/inventory/SalesController.java:871`（单号、客户、日期、状态、明细，字段见 `SalesDeliveryRecord.java:68-204`）。
2. 物流发运：`GET /shipments`，`JC/ShipmentController.java:56`（page 0 起、status 过滤）、`/customer/{id} :96`、`/date-range :119`、`/tracking/{no} :143`。
3. 动作：创建 `POST /sales/deliveries :858`、`/ship :900`、`/delivered :912`、`/signature :922`；`POST /shipments :247`、`PUT /{id}/status :275`。
4. 黑湖出库单：LSM 缺（G1）。

**状态**：部分（原生接口都有，但缺检索条件；LSM 缺）。

**缺口**：`/sales/deliveries` 无搜索/日期/状态过滤；`/shipments` 与 `/sales/deliveries` 是两套模型（web-admin 分 Formal/Legacy 两个页签），融合时需明确以哪套为准。

---

## 9. /sales/returns　销售退货

**原型来源**：味美 `YdwmList.vue` CONF.returns；六膳门 `LsmGapPage.vue` CONF.returns（黑湖销售退货入库单，没有退货原因、审批流）。

**需要的数据与动作**
1. 列表：退货单号、客户、原订单、退货日期、金额（无权限时隐藏）、状态。
2. 详情：状态、备注；动作：从销售订单「申请退货」、审批、财务审批、完成入库、退款（原页面办理）。

**后端对应**
1. `GET /return-orders`，`JC/inventory/ReturnOrderController.java:55`（`returnType`、`status`、分页）；实体 `returnNumber/returnType/status/counterpartyId/sourceOrderId/returnDate/totalAmount`（`JS/entity/inventory/ReturnOrder.java:74-99`）。
2. 详情 `:82`；动作：创建 `:43`、提交 `:94`、审批 `:103`、财务审批 `:119`、财务驳回 `:131`、驳回 `:143`、完成 `:154`；退款 `/return-orders/{id}/refund*`（`JC/inventory/ReturnRefundController.java`，web-admin `WA/api/returnOrder.ts`）。

**状态**：部分（LSM 缺）。

**缺口**
- 原型 `api` 没带 `returnType=SALES_RETURN`（`YdwmList.vue` returns.api = `return-orders?page=1&size=500`）；同一接口还返回采购退货，会混入销售退货页。web-admin 正式页带了该参数（`WA/views/sales/returns/list.vue:173`）。
- 列表不 enrich 客户名与原订单号：`counterpartyName/sourceOrderNumber` 是 `@Transient`，只在详情接口填充（`JS/service/inventory/impl/ReturnOrderServiceImpl.java:662-699`）；原型读 `customerName`、`salesOrderNumber/orderNumber`，会显示「未记录」。需要列表也带名称，或前端另查。
- 状态枚举：Java 有 SUBMITTED/APPROVED/FINANCE_APPROVED/PROCESSING 等，原型 `S_RT` 没有 SUBMITTED/FINANCE_APPROVED/PROCESSING（显示英文原值），且含 Java 没有的 PENDING/RECEIVED/CANCELLED。
- 规则：原型不做财务记账；Java 有退款与冲减，融合时不得覆盖（MERGE-MAP 第三节已记）。

---

## 10. /lsm/receivables　应收与收款

**原型来源**：仅六膳门 `LsmExtras.vue` CONF.finance（应收 + 收款页签 + 待开票页签）；lsmOnly。

**需要的数据与动作**
1. 应收单：单号、客户、日期、应收、已结算、待收（负数=多收或冲减）、到期日、逾期天数（overdueStatus=1 且待收>0 才逾期）。
2. 收款：收款单、客户、日期、方式、实收。
3. 待开票：出入库单、类型、业务时间、关联销售单、单价。
4. 只读，无写动作；应付/付款黑湖返回 0 条。

**后端对应**
1. 应收流水：`GET /finance/transactions`，`JC/finance/ArApController.java:224`（counterpartyType、counterpartyId、分页）；`ArApTransaction` 有 `amount/settledAmount/outstandingAmount/dueDate/paymentStatus/transactionDate`（`JS/entity/finance/ArApTransaction.java:106-162` 附近）。账龄 `/aging :324`、概览 `/overview :335`、对账单 `/statement :303`。
2. 收款：`GET /finance/payments`，`JC/finance/PaymentRecordController.java:115`、`/by-customer :157`、`/by-sales-order/{id} :144`。
3. 待开票：`GET /finance/invoices`，`JC/finance/InvoiceController.java:133`（发票申请，不是「待开票出入库单」）；`SalesOrder.invoiceStatus/invoicedAmount`；`/finance/invoice-todos/failed`（`SalesOrderInvoiceTodoController.java:32`）是开票待办失败列表。
4. 黑湖原始应收/收款：LSM 缺（G1）。

**状态**：部分。

**缺口**
- 没有「应收单」维度的列表（带到期日、逾期天数、待收）：现有的是流水账与账龄分桶，无按状态/逾期筛选；逾期判定口径（待收为负不算逾期）规则未核。
- 「待开票出入库单」清单没有对应：Java 的发票接口是申请/审批/开具链路，没有按出入库单列未开票项的接口（建议 G6）。
- 币种：Java 有 `currencyCode`，黑湖币种未返回，口径需对齐。

---

## 11. /sales/customers　客户

**原型来源**：味美 `YdwmList.vue` CONF.customers；六膳门 `LsmExtras.vue` CONF.customers（含账本与五个页签：销售订单、应收、收款、待开票、价目表）。

**需要的数据与动作**
1. 列表：编号、名称、联系人、电话、收货地址、启用/停用、疑似重复（前端）。
2. 六膳门：账期、合同额、待发货额、待开票额、待收款、已收款。
3. 详情页签：销售订单、应收、收款、待开票、价目表；沟通记录、报价（原页面）。
4. 动作：新增、编辑、导入导出、停用。

**后端对应**
1. `GET /customers`，`JC/CustomerController.java:276`；实体 `code/customerCode/name/contactPerson/phone/shippingAddress/isActive/creditPeriodDays`（`JS/entity/Customer.java:49-170`），字段对得上。
2. 账本：`GET /customers/{id}/statistics :601` **是桩**：`JS/service/impl/CustomerServiceImpl.java:389-392` 的 `totalOrders/totalSales/averageOrderValue` 恒为 0（`TODO`）；`GET /customers/{id}/purchase-history :621` 直接返回空列表（`CustomerServiceImpl.java:397-401`）。可用：`/customers/outstanding-balance :830`、`ArApController` 流水/账龄。
3. 页签：订单 `GET /sales/orders/by-customer :314`；应收/收款 `/finance/transactions?counterpartyId`、`/finance/payments/by-customer :157`；价目 `/customers/{id}/price-list`；沟通记录 `JC/CallRecordController.java:57`、`WechatRecordController.java:42`、`CustomerTrackingRecordController.java:30`；报价 `OperationalQuoteController`（规则未核）。
4. 新增 `POST :144`、编辑 `PUT :169`、导入 `POST /import :739`、`/import/json :786`、导出 `:671`、状态 `PUT /{id}/status :504`。

**状态**：部分（LSM 缺）。

**缺口**：客户账本汇总（合同额、待发货额、待开票额、待收款、已收款）缺，`statistics` 与 `purchase-history` 是未实现的桩；「待开票」同第 10 节。

---

## 12. /lsm/customer-prices　客户价目表

**原型来源**：仅六膳门 `LsmExtras.vue` CONF.prices（黑湖 customer_price）；lsmOnly。销售订单页还用它做参考价偏离。

**需要的数据与动作**
1. 跨客户列表：客户、产品、编码、规格、价格、最近成交价、平均成交价、更新时间；搜索、导出。
2. 只读，无写动作。

**后端对应**
1. 单客户：`GET /customers/{customerId}/price-list`，`JC/sales/CustomerPriceListController.java:35`，返回 `CustomerPriceItemView`（priceListName、itemName、unit、standardPrice、effectiveFrom/To、effective，`JS/dto/sales/CustomerPriceItemView.java:16-27`）。
2. 最近成交价：`GET /customer-price-history?customerId&productId`，`JC/sales/CustomerPriceHistoryController.java:60`（必须带 productId，按订单日倒序）；`GET /customers/{id}/price-memory`（`CustomerController.java:478`）。
3. 写入：`PUT /customers/{id}/price-list/items :44`、`DELETE .../items/{itemId} :57`（原型不要）。
4. 黑湖价目：LSM 缺（G1）。

**状态**：部分。

**缺口**
- 无跨客户汇总列表（必须逐客户请求）；
- 平均成交价缺：历史接口只给明细，没有平均值；
- 规格 productSpec 不在 `CustomerPriceItemView`；
- 原型「同一客户+产品多条价目时不取任何一条」：Java upsert 按产品唯一（`upsert(productTypeId, price)`），模型不同，多价目场景不存在，规则未核。

---

## 13. /sales/vehicles　车辆字典

**原型来源**：六膳门 `LsmGapPage.vue` CONF.vehicles + `UV/supply/supply-gap.cjs`（Cretas 补充记录，本机 JSON 文件）；黑湖无车辆数据。

**需要的数据与动作**
1. 列表：车牌号、车型、司机（人手填写）、载重（吨）、备注、来源。
2. 新增（车牌不能重复，大小写不敏感）、撤销（必须写原因，保留在记录里）。

**后端对应**
1. `GET /vehicles`，`JC/VehicleController.java:44`；`VehicleDTO` 有 `plateNumber/driver/phone/capacity/currentLoad/status/vehicleType/notes`（`JS/dto/VehicleDTO.java:34-73`）。
2. 新增 `POST :86`（车牌重复返回 409，`JS/service/impl/VehicleServiceImpl.java:73-79`）；改 `PUT :98`；状态 `PATCH :112`；删除（软删除）`DELETE :142`。web-admin 已用 `WA/views/sales/vehicles/list.vue` 与物流 `WA/api/logistics.ts:308`（`/logistics/vehicles`）。

**状态**：有（字段齐全；规则差异见缺口）。

**缺口（规则差异，非缺接口）**
- 载重单位：原型是「吨」，Java `capacity` 语义是 kg（web-admin `logistics.ts:257` 注释写明），融合必须换算，不能直接当吨存。
- 车牌唯一性：Java `findByFactoryIdAndPlateNumber` 区分大小写、不 trim；原型 `.toUpperCase()` 比较。
- 撤销必须写原因并保留：Java 是无原因的软删除；需要时加原因字段或审计。
- 原型把「司机」限定为手填文本、不关联人员档案；Java `driver/phone` 也是文本，一致。物流 profile 还有司机绑定（`logistics.ts:297`），原型没有。

---

## 14. /sales/payment-requests　销售付款申请

**原型来源**：六膳门 `LsmGapPage.vue` CONF.payreq：**空态占位**。「黑湖没有销售付款申请数据；财务模块在本原型中关闭，这里不提供申请入口。」味美侧由原页面打开。

**需要的数据与动作**
1. 无（空态说明页）。

**后端对应**（对应原页面 `WA/views/sales/payment-requests/list.vue`）
1. `GET /payment-requests`（全状态，status、keyword），`JC/inventory/PaymentRequestController.java:180`。
2. `POST /payment-requests/sales :77`（对客户退款/返利/销售费用，同一销售订单已有活跃申请返回 409）；提交 `PUT /{id}/submit :102`、财务审批 `:114`、驳回 `:127`、标记已付 `:140`。注意：该控制器注释写明遗留的业务页付款写入已改为 fail-closed，实际付款走 AP 待付项与 OA 工作流适配器（规则未核：本页「标记已付」是否仍可用）。

**状态**：有（后端有；原型页本身是空态，六膳门变体按「原型专用」处理，不应进后端，原因：黑湖无此数据且财务模块在原型关闭）。

**缺口**：无。

---

## 15. /production/delivery-risks　交付风险

**原型来源**：`UV/production/DeliveryRisk.vue` + `delivery-risk.ts`（判定）+ `kitting.ts` + `progress.ts`；味美读 `sales/orders`、`production-plans`、`bom/recipes`、`sales/finished-goods`、`material-batches`，六膳门读黑湖销售订单行库存+工单，计划进度写「不能判断」。生产与销售两处菜单同一页。

**需要的数据与动作**
1. 所有未完成订单（含低风险、不能判断）：单号、客户、交期与剩余天数、齐套（齐套/料齐待产/部分/缺料/不能判断）、计划进度（工单进度/时间进度）、风险（高/中/低/不能判断）、原因、订单行待交量。
2. 按风险筛选、搜索；跳转订单、缺料异常。
3. 只读。

**后端对应**
1. `GET /delivery-risks`，`JC/DeliveryRiskController.java:70`：只返回**已建实例**（有风险的订单），`CaseView` 字段 `orderNumber/customerName/dueDate/riskLevel/stage/ownerName`；详情 `GET /{caseId} :100` 带 `assessment`（signals、evidence、unknowns、impact、suggestions、estimatedDaysNeeded、daysLeft、materialGapCount、lateSchedules，`JS/service/shortage/DeliveryRiskService.java:78-83`）。
2. 全量评估：`POST /delivery-risks/scan :82` 返回每张未交订单的评估，含 OK 与 UNKNOWN（`ScanResult` `:85`，上限 500），但**会写入实例**（建/刷新处理实例）。
3. 办理动作（原型没有）：指派 `:117`、复核 `:127`、结束 `:136`、可指派人 `:90`。
4. 齐套明细：缺料分析 `ShortageAnalysisService`（`DeliveryRiskService.java:~226 material()`）；订单到计划的关联：`ProductionPlan.sourceOrderId`（`DeliveryRiskService.java:plans()`）。web-admin 已有页 `WA/views/production/delivery-risks/index.vue`、`WA/api/deliveryRisks.ts`。

**状态**：部分（LSM 缺）。

**缺口**
- 判定口径不同（规则级差异，需 Steve 裁决统一哪个）：原型是 高/中/低/不能判断 四档，阈值 逾期=高、缺料7天内高、部分齐套2天内高、计划落后2天内高 等（`UV/production/delivery-risk.ts`）；Java `DeliveryRiskEvaluator`（`JS/service/shortage/DeliveryRiskEvaluator.java:21`）只有 RISK/UNKNOWN/OK 三档，用信号（OVERDUE、NO_PLAN、LATE_SCHEDULE、CAPACITY_SHORT、MATERIAL_LATE）和日产能推算，没有「高/中」分级。
- 只读视图缺纯读接口：看全量评估需调用会写实例的 `POST /scan`，缺一个只读的「评估全部未交订单」接口。
- 交期字段：原型读不到 `requiredDeliveryDate`（见第 7 节），味美变体全部会被判「交期未填写→不能判断」。
- 计划进度：原型取计划 `plannedQuantity/actualQuantity`，Java 用成品批次产出汇总并做单位换算，数据口径不同，规则未核。

---

## 16. /production/shortage-cases　缺料异常

**原型来源**：`UV/production/ShortageCases.vue`，仅六膳门：黑湖采购需求 `lackAmount>0`；「不自己推算、不展开 BOM、不写影响哪张订单」。四处菜单（生产/质量/采购/销售）同一页。味美侧走原生缺料事件页（`WA/views/production/shortage-cases/index.vue`）。

**需要的数据与动作**
1. 采购需求：需求单号、物料、需求量、已下达采购、已收货、缺口、计划到货（已过日期标注）；搜索；「同时显示无缺口」开关。
2. 只读，跳转交付风险页。

**后端对应**（Java 的缺料是另一个概念：销售订单来源的缺料实例）
1. `GET /shortage-cases`，`JC/ShortageCaseController.java:73`（只含 SALES_ORDER 来源，`ShortageCaseRepository.findForFactory`）；`CaseView` 有 `salesOrderNumber/stage/gapLineCount/lastRefreshedAt`；详情 `GET /{caseId} :87` 返回 `LineView`（requiredQty、releasedQty、inspectingQty、onOrderQty、gapQty、unit）与留痕。
2. 动作：刷新 `POST /{caseId}/refresh :98`；方案/建议 `JC/ShortageCaseActionController.java:76/85`；生成采购草稿 `POST /procurement-drafts :93`；核对 `/outcome-checks :133/:141`；决定 `/decision :151`；改排程 `JC/ShortageRescheduleController.java:67/77/86`。
3. 黑湖采购需求缺口：LSM 缺（G1）。

**状态**：部分。味美/Java 的缺料异常能力比原型强，但口径是「按销售订单 BOM 展开」，与原型「按采购需求缺口」不是同一份数据。

**缺口**：原型页若要留给六膳门，需要 G1（读黑湖采购需求）。Java 缺料实例有「影响哪张订单」（salesOrderNumber），而原型明确这块黑湖没有，两边展示不能直接合并，需裁决页面以哪种口径为准。

---

## 缺口汇总

1. **G1　黑湖镜像读取接口（影响 LSM 变体的第 1–12、15、16 页）**：缺什么：按资源读取 `blacklake.record` 的列表与详情接口（sale_order、purchase_order、purchase_requirement、receivable、receipt、invoicing、customer、vendor、customer_price、vendor_price、customer_ledger、vendor_ledger、outsource_*），带 revision/checkedAt 与「0 条」「未返回」语义；采集已存在（`PY/blacklake/live_extras.py:74-88`）。建议落在：Python `blacklake` 包新增 read 路由并在 Java 网关代理，或 Java 增 `BlacklakeMirrorController`。大小：大。
2. **G2　请购「我的 + 状态」同时过滤**：`PurchaseRequisitionServiceImpl.java:210-222` status 与 requesterId 互斥，修成可组合。落在 `PurchaseRequisitionController/Service` 与 Repository 查询。大小：小。
3. **G3　采购草稿的单价来源与参考价留痕**：`CreatePurchaseOrderRequest` 与 PO 明细实体新增 priceSource、referencePrice、referencePriceListId/Name、priceDeviationPct，备注字段名对齐（notes→remark）。落在 `PurchaseController.createOrder` 与实体、迁移。大小：中。
4. **G4　供应商/客户账本汇总**：`SupplierServiceImpl.getSupplierStatistics`、`CustomerServiceImpl.getCustomerStatistics`、`getCustomerPurchaseHistory` 是桩，实现采购额/合同额/待发货额/待收货额/待付款/待收款/已收付款/待开票额。落在 `SupplierController/CustomerController` 与 ArAp 聚合。大小：中。
5. **G5　价格表更新与停用**：`PriceListController` 无 PUT；补更新、条目增删改、启停。落在 `PriceListController`。大小：小。
6. **G6　应收单维度与待开票清单**：应收按单据维度（到期日、待收、逾期天数、待收为负不算逾期）筛选接口；待开票出入库单清单接口。落在 `ArApController` 与 `InvoiceController`。大小：中。
7. **G7　跨客户客户价目表列表与平均成交价**：`CustomerPriceListController` 增跨客户查询，历史接口增平均价与规格。落在 `CustomerPriceListController/CustomerPriceHistoryController`。大小：小。
8. **G8　退货列表补名称与类型**：`ReturnOrderServiceImpl.getReturnOrders` 返回 counterpartyName、sourceOrderNumber；原型调用补 `returnType=SALES_RETURN`。落在 `ReturnOrderController` 与原型。大小：小。
9. **G9　发货单检索条件**：`SalesController.listDeliveries` 增加 keyword、orderNumber、状态、日期过滤。大小：小。
10. **G10　交付风险只读全量评估 + 口径统一**：新增只读评估接口（不写实例）；裁决 高/中/低 四档与 RISK/UNKNOWN/OK 三档的口径。落在 `DeliveryRiskController/Service/Evaluator`。大小：中（需业务裁决）。
11. **G11　原型字段与枚举适配（改原型，不改 Java）**：销售交期 `requiredDeliveryDate`；请购期望日 `expectedDate`；请购/采购/销售/退货/发货状态枚举补全（见第 1、3、7、9 节）。落在 `UV/supply/ydwm.ts`、`YdwmList.vue`、`YdwmPurchase.vue`、`YdwmSales.vue`、`DeliveryRisk.vue`。大小：小。
12. **G12　车辆口径对齐**：载重 吨↔kg、车牌大小写与 trim、撤销原因。落在 `VehicleServiceImpl` 或原型换算。大小：小。
13. **G13　采购建议/缺料口径裁决**：原型「按需求缺口只读、不给建议数量」与 Java「BOM 展开建议、缺料实例」不同；需 Steve 裁决保留哪种。落在 `PurchaseController` 建议接口与 `ShortageCaseController`。大小：中（裁决为主）。
14. **G14　委外任务页签（供应商详情）**：Java 控制器里未检索到对应接口（web-admin 有 `outsource.ts`，路径本次未核）。大小：待核。

**未核项**：供应商/客户导入规则、报价、沟通记录页签字段、`savedViews` 端点、采购/销售各状态机的权限与自批限制、`PurchaseController` 列表分页上限，均为「规则未核」。
