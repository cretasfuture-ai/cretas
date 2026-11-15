# 原型规则 vs 正式 Java 后端：库存 / 仓库 / 采购 / 销售 逐条核对表

日期：2026-11-15。基线：origin/main d6dc07cbaa。原型来源：分支 claude/cretas-nextui-prototypes（已合入 main 的 prototypes/）。

## 怎么读这份表

- 判定：一致 / 有偏差 / 正式更严（不得改弱）/ 缺失 / 无法判断（未读到）。
- 每节由只读代理读 Service 内部实现后给出，行号取自基线。**本会话亲自复核并修复的只有下面「已修复」两条**；其余条目是代理读码结论，修之前须再按行号复核。
- 「待 Steve 裁决」的口径不擅自改。退货财务记账、负库存拦截等正式更严处不改弱。
- 单位换算只出设计（见同目录 unit-conversion-gateway-design-20261115.md），不实现。

## 已修复（本批 PR，带回归测试，红线域须独立审查）

1. 库存汇总 totalValue：同一物料任一在库批次无单价，整组 totalValue 与 avgUnitPrice 为 null（原先 SUM 跳过空价，给出偏低的数）。`MaterialBatchRepository.findStockSummaryByFactory`；测试 `MaterialStockSummaryMissingPriceTest`（真实 JPA，旧查询下红、新查询下绿）。
2. SKU 组装取客户未按工厂过滤（他厂客户名会写进本厂 SKU 名称与编码）。`SkuAssemblyService.assemblesku` 改 `findByIdAndFactoryId`；测试 `SkuAssemblyServiceTest.assembleskuRejectsCustomerOfAnotherFactory`。

## 未修、留给后续（按各节「疑似缺陷」列表，需先裁决或另派）

各节末尾的疑似缺陷表即清单。需要 Steve 先拍板的口径汇总：生产投料是否按计划隔离已签领批次；stock-summary 是否显示客户自有料；同到期日第二排序键；删除仍有库存的仓位拦截与否；盘点重复提交 409 vs 返回原单；成品入库少收处理与幂等方式；采购收货换算只用单据快照 vs 当前档案；采购超收默认拦否；回料跨计划；同厂调拨目标批次是否拆源批；仓库类型白名单；9% 税率是否放行；请购驳回后能否重提；物料无税率转 PO 422 vs null；退货无货退款占容量、是否允许议价退货；发货后应收挂账失败回滚还是告警。

---


<!-- section A-inventory -->
# A. 库存域规则核对（原型 vs 正式 Java 后端）

只读核对，未改代码、未写生产。后端路径前缀省略为 `B/` = `backend/java/cretas-api/src/main/java/com/cretas/aims/`。行号为本 worktree（HEAD d6dc07cbaa）实读。
原型取自 `origin/claude/cretas-nextui-prototypes-2d5762:prototypes/ydwm-v1/`，原型源码每文件仅 8–23 行，故位置只写「函数/片段」。
判定词：一致 / 有偏差 / 正式更严 / 缺失 / 无法判断。

---

## 1. fusion-inventory-views.mjs（分仓库存 + 工厂级汇总）

| 原型规则 | 正式后端方法 | 判定 | 说明 |
|---|---|---|---|
| by-warehouse：仓库必须属于本工厂，否则 404（`warehouses.find(id)`） | `B/controller/WarehouseInventoryController.java:147-150` `findByIdAndFactoryIdAndDeletedAtIsNull(warehouseId, effectiveFactoryId)` | 正式更严 | 另有 targetFactoryId 跨厂可见网络校验（:106-143），原型无此概念。 |
| by-warehouse 批次排除 REVERSED/DELETED | `WarehouseInventoryController.java:158` `findByFactoryIdAndWarehouseId`；软删由实体 @Where 排除（注释 :156） | 一致 | 正式不按状态过滤其它状态（含 INSPECTING/EXPIRED 等都返回），原型同样只排除两种。 |
| 批次数量口径 `currentQuantity ?? remainingQuantity ?? receipt-used-reserved`（已扣预留） | `B/entity/MaterialBatch.java:366` `getCurrentQuantity()` = receipt − used − reserved；另在 `WarehouseInventoryController.java:176-190` 补 `unsettledConsumption`（未小结报工在途） | 一致（正式多一层） | 正式把「在途未小结消耗」作为独立字段返回，不并入 currentQuantity；原型无此字段。注意 :168-171 注释写「currentQuantity 恒 == receiptQuantity」与实体 :366 实现矛盾，属陈旧注释，见末尾缺陷 7。 |
| stock-summary：只统计 qty>0 且排除 REVERSED/USED_UP/DEPLETED/EXPIRED/SCRAPPED/DEFECTIVE | `B/repository/MaterialBatchRepository.java:682-698`（`findStockSummaryByFactory`）；入口 `B/controller/MaterialBatchController.java:1056`、`B/service/impl/MaterialBatchServiceImpl.java:2625` | 一致（状态集有微差） | 正式排除集 DEPLETED/USED_UP/EXPIRED/SCRAPPED/DEFECTIVE，未列 REVERSED（枚举里是否存在 REVERSED 未读到，不影响结论）；两边都不排除 INSPECTING。 |
| stock-summary 按 (物料,单位,ownership,customerId) 分组，客户自有单独成组 | 同上 `:700 AND (m.ownership IS NULL OR m.ownership = COMPANY_OWNED)`，GROUP BY 仅 (materialTypeId,name,code,category,quantityUnit) | 有偏差 | 正式直接排除客户自有料，汇总只代表公司自有；原型把客户自有显示成独立行。口径问题（见待裁决 B）。 |
| **缺单价整组 totalValue=null，不退化为 0**（`r.unitPrice==null` → `g.totalValue=null`，之后不再累加） | SQL `SUM((recv-used-reserved) * m.unitPrice)`（`MaterialBatchRepository.java:690-691`）；`MaterialBatchServiceImpl.java:2625-2640` 用 `value/qty` 回填 avgUnitPrice | **有偏差（正式有缺陷）** | SQL 的 SUM 会跳过 unitPrice 为 NULL 的行：同组里 3 批有价 + 1 批无价，totalValue = 仅有价三批之和（偏低），**不是 null**；仅「整组全无价」时才得 null。avgUnitPrice = 偏低的 totalValue / 含无价批的 totalQuantity，同样偏低。见缺陷 1。 |
| 价格单位 `priceUnit !== unit` 则 totalValue=null | `MaterialBatch.java` 无 priceUnit 字段（只有 unitPrice，:139） | 无法判断 / 缺失 | 正式批次没有价格单位概念，默认单价按 quantityUnit 计；原型这条在正式无对应。 |
| avgUnitPrice = totalValue/totalQuantity，totalValue=null→null | `MaterialBatchServiceImpl.java:2628-2636` 除零或 value null → null，scale 4 HALF_UP | 一致 | 仅当 SQL 返回 null 时才为 null（受缺陷 1 影响）。 |
| 租户隔离 | by-warehouse 全程带 factoryId（:147、:158、:176 的 `getUnsettledConsumptionByBatchIds(effectiveFactoryId,…)`）；stock-summary SQL `WHERE m.factoryId = :factoryId`（:692） | 一致 | |
| 权限 | stock-summary `@RequirePermission(warehouse/inventory 读)`（Controller :1055）；by-warehouse 注释说明靠 JwtAuthInterceptor 兜 path factoryId（:116-124） | 正式更严 | 原型无权限。 |

---

## 2. fusion-stock-allocation.mjs（生产投料分摊 FEFO / 替代料 / 预留）

对照正式：`B/service/processentry/impl/ProductionStockAllocationServiceImpl.java`（下称 PSA）、`B/repository/MaterialBatchRepository.java`、`B/service/processentry/ProductionInventoryOwnershipGuard.java`、`B/util/InventoryExpiry.java`、`B/service/bom/impl/BomItemSubstituteServiceImpl.java`、`B/service/yield/impl/InterimSettleServiceImpl.java`。

| 原型规则 | 正式后端方法 | 判定 | 说明 |
|---|---|---|---|
| 候选批次 = status AVAILABLE | `MaterialBatchRepository.java:500-507`（FOR UPDATE 版）/`:340-348`（非锁版）`m.status='AVAILABLE'` | 一致 | |
| 排除 qualityStatus=FAILED | 正式批次无 qualityStatus；用状态枚举：INSPECTING/DEFECTIVE/EXPIRED/SCRAPPED 不是 AVAILABLE，天然排除（`MaterialBatchStatus.java:63-65 blocksProduction`；`MaterialBatchStateGate.java` assertUsable/assertPostable） | 正式更严 | 扣留（hold）走 INSPECTING，也被排除。 |
| 必须在生产仓（WORKSHOP） | PSA `plan()` `warehouseResolver.resolveWorkshopId`（:64-70），查询带 `m.warehouseId=:warehouseId`；planExplicit 显式批次不在生产仓报 409（:334-341） | 一致 | |
| 排除过期（`expiryDate>=今天`，到期当天仍可用） | PSA `excludeExpired`（:808-815）+ `InventoryExpiry.isExpired`（`expireDate<today`，东八区 `InventoryExpiry.java:36-52`）；显式批次 :325-333 | 一致（正式更严） | 日期口径一致（当天可用）；正式固定东八区。原型用 `at.slice(0,10)`，`at` 若是 UTC 时间戳则 00:00-08:00 会差一天（原型侧隐患，不是后端缺陷）。 |
| 排除客户自有 | 查询带 `ownership IS NULL OR COMPANY_OWNED`（Repository :505）；非客供计划调 `assertMaterialBatchAllowed` 对 CUSTOMER_OWNED 直接 409（`ProductionInventoryOwnershipGuard.java:88-95`）；客供计划走客户专属 FEFO 分支（PSA :1015-1030, Repository 约 :482） | 正式更严 | 原型只是「排除」；正式还有客供料按同客户/同订单/同订单行的分支，原型无。 |
| 批次排序：到期日（空排最后）→入库日→id | `MaterialBatchRepository.java:507` `ORDER BY expireDate ASC NULLS LAST, createdAt ASC, id ASC`（生产分摊用）；`:347` 仓库级 FEFO 用 `receiptDate` | **有偏差** | 第二排序键：原型 receiptDate，正式生产分摊用 createdAt（生产仓里是调拨转入时间，Repository 注释 :489-494 有意如此）。同到期日的批次，原型按入库日先出，正式按「转入生产仓先后」先出，可能选出不同批次。口径待 Steve 裁决（见 C）。 |
| id 字符串比较做末位定序 | 正式 SQL 按 id（UUID 字符串）升序 | 一致 | |
| 扣预留：可用 = `remainingQuantity − 已提交未结算的报工分配（reserved map）` | PSA `batchAvailable`（:913-919）= stock − `sumPendingQuantityByMaterialBatchId`（`B/repository/ProductionInputAllocationRepository.java:21-25` 只算 SUBMITTED 且 interimSettledAt IS NULL）；planExplicit 同口径（:372-378）；stock 取 `getCurrentQuantity()`（已扣 `reservedQuantity` 列，`MaterialBatch.java:366`） | 一致（正式再多扣一层） | 原型 reserved 只来自工序表已提交未消耗行；正式额外还会被 `reservedQuantity` 列（领料/订单预留）扣减。同一批次在正式里可用量 ≤ 原型。 |
| **同轮内多条需求不重复吃同一批**（reserved map 累加） | PSA `plan()` `availableByBatch`（:73、:110-128）同一次 plan 内逐批扣减 | 一致 | |
| **跨计划隔离：不借用别的计划已签领的批次**（`belongsToPlan`，且计划专属优先于未归属，含显式指定批次） | 正式 `MaterialBatch` 实体没有 productionPlanId 字段（grep 实体仅 :31/:355 planBatchUsages 关联，无归属列）；`findStatusEligibleBatches*`（PSA :826-850、:992-1030）按 (工厂,物料,仓库) 取全部生产仓批次，不按计划过滤；占用靠 `ProductionInputAllocation.pending`（:372-378）串行避免超分配 | **缺失（口径待裁决）** | 正式里「某计划签领的料」只体现在领料单（`FactoryMaterialRequisition.productionPlanId` :57）；料进入生产仓后成为公共池，任何计划都能按 FEFO 吃到。唯一隔离是客供料的客户/订单维度。见待裁决 A。 |
| 显式指定批次（rawMaterialInputs）：必须是本计划可用批次 | `planExplicit`（PSA :255-400）：批次必须存在且属本厂（:290-297 `findByIdAndFactoryIdForUpdate`）、非产出批次（:314）、未过期（:325）、在生产仓（:334）、单位一致（:353-361）、物料一致（:364-371）、状态 AVAILABLE 且可用量足（:379） | 正式更严 | |
| 单位换算：仅 g↔kg，其余报「缺少明确换算关系」 | PSA 质量单位折 kg，其余按字面单位；包装单位只在展示口径换算，分配口径刻意严格（:887-904 注释） | 一致 | |
| 缺料：返回 shortage（遇到第一项缺料即返回） | `plan()` 收集全部缺料项后抛 `ProductionStockShortageException`（:135-161），并算成因 NOT_REQUISITIONED/TRULY_OUT_OF_STOCK（:178-198） | 正式更严 | |
| **替代料系数**：`requested = remaining × factor`；`equivalent = take / factor`；factor 必须 >0 | `B/entity/bom/BomItemSubstitute.java:121-123` 注释「substitute 数量 = 主项一个单位对应的替代量」（语义与原型一致）；录入校验 `BomItemSubstituteServiceImpl.java:216-254`（>0、单位不同必须显式填、自指/重复/循环/同族校验） | 一致（语义），**缺失（用法）** | 系数语义一致且录入更严。但**分摊/扣料路径不使用替代料**：PSA 全文无 substitute 引用；`getConversionFactor` 在库存扣减链路无读取点；替代料只在候选清单（`WorkflowClerkSheetServiceImpl.java:495-505`）、成本方差（`PlanCostVarianceCalculator.java:344+` 用价格单位换算而非系数）、结单白名单（`ProductionPlanServiceImpl.java:7826-7841`）出现。即原型的「主料不足自动用替代料顶」在正式里没有，需文员手选替代料批次。 |
| 分摊产物 `consumed:false, PENDING_SETTLEMENT`，小结时才扣 | PSA 分摊写 `ProductionInputAllocation(status=ALLOCATED)`（:644-657），`InterimSettleServiceImpl.java:206-282` 小结时才扣 `usedQuantity` | 一致 | |
| 负库存 | `InterimSettleServiceImpl.java:244-262` 扣后 `receipt−used−reserved<0` → 409 `BATCH_INSUFFICIENT`；锁序/悲观锁（:225-234） | 正式更严 | 原型无并发/负库存守卫概念。 |
| 租户隔离 | PSA 查询与锁定全带 factoryId（`findByIdAndFactoryIdForUpdate` :291；Guard `:48-52` 跨厂 403） | 一致 | |

---

## 3. fusion-stocktake-import.mjs（盘点批量导入）

对照正式：`B/controller/StocktakeBulkImportController.java`、`B/service/factory/impl/StocktakeBulkImportServiceImpl.java`（下称 SBI）、`B/service/factory/impl/FactoryStocktakeServiceImpl.java`（下称 FS）、`B/service/inventory/impl/OpeningInventoryServiceImpl.java`。

| 原型规则 | 正式后端方法 | 判定 | 说明 |
|---|---|---|---|
| 仓库必须属本工厂且有效 | SBI `requireWarehouse`（约 :365，`findByIdAndFactoryIdAndDeletedAtIsNull`） | 一致 | |
| 文件：xlsx/xls、≤50MB、1–5000 行 | Controller `validateFile`（:110-126）：xlsx/xls、**10MB**；行数上限**未读到**（`excelUtil.importFromExcel`，SBI readRows 仅校验非空） | 有偏差 | 大小上限 10MB vs 原型 50MB（正式更严）；5000 行上限在正式未读到，不能断言存在。 |
| 实盘数量非负且最多两位小数 | SBI :284-292 与 :350-355（import 预览）；`FS.validateInventoryScale`（FS :596-606）、`StocktakeItemUpdateDTO` `@DecimalMin("0")` | 一致 | |
| 单价非负 | SBI 仅校验「期初新建行」最多 2 位小数（:356-359）；**未读到非负校验**（`OpeningInventoryServiceImpl.java:261` 直接 `setUnitPrice`） | 有偏差（缺失） | 见缺陷 3。 |
| 批次号给了但仓内无此批次 → 报错 | SBI :318-331：非期初直接报错；**期初模式下，批次号非空但不存在 → 当作新物料走「从 0 盘盈」**（:334 之后） | 有偏差 | 原型任何模式下「填了批次号却不存在」都报错。正式期初模式会把拼错的批次号建成新批次（`generateUniqueBatchNumber` 防重码）。 |
| 非期初必须填批次号 | SBI :323-326 | 一致 | |
| 期初新建：物料名+单位需唯一匹配物料字典 | SBI `loadMaterialTypeIndex`/`resolveMaterialType`（约 :395-415）：按名称/编码精确（去空格小写），**不看单位**；同名多条时 name 后者覆盖，静默取一条 | 有偏差 | 原型要求名称+单位唯一匹配（`master.length!==1` 报错）；正式不校验单位唯一，同名不同单位物料会静默命中其中一个。 |
| 单位须与批次一致；表格仓库须与所选仓库一致 | SBI 既有批次分支用 `batch.getQuantityUnit()` 作单位，**未读到**对表格里单位列/仓库列与批次不一致的报错（`StocktakeImportRowDTO` 虽有字段，match() 未比较）；仓库名列同样未比较 | 缺失（未读到） | 表格单位被忽略（不是报错）。 |
| 同一批次/新物料重复出现拒绝（整单失败） | SBI :263-268 非空批次号重复→**该行**报错并继续；空批次号（新物料）不去重 | 有偏差 | 原型整单 422；正式逐行报错。两条空批次号同物料会建两个批次壳。 |
| 账面数量口径 `currentQuantity??remaining??receipt`（原型） | SBI :293 `scale4(batch.getPhysicalQuantity())`（= receipt − used，**不扣预留**）；模板 :81 同；FS 快照 :383-386 = physical − 未小结消耗 | 正式更严（口径不同） | 正式明确「盘点用货架实物，不扣预留」（FS :367-379 注释，防假盘盈）。原型若 currentQuantity 已扣预留，会在有预留的批次上产生假盘盈。 |
| 预览口径 = 确认/过账口径 | SBI 预览 systemQty = physical（:293）；真正写入的快照 systemQty = physical − 未小结消耗（FS :385）；drift 守卫同口径（FS :866） | **有偏差（正式有缺陷）** | 生产仓里有「已报工未小结」消耗的批次，预览显示的差异与 `updateItems` 重算后真实差异不一致。见缺陷 2。 |
| 预览 30 分钟有效、改文件/仓库需重新预览 | **无服务端预览缓存**：`preview` 只读不落库（Controller :64-80），`confirm` 重新解析上传的文件并重算匹配（SBI :113-130） | 有偏差（设计不同） | 正式靠「确认时用同一文件重算」保证一致，没有 30 分钟窗口，也没有 `预览后库存已变化` 的显式比对；库存变化由 `apply` 时 drift 守卫兜（FS :820-875，`STOCKTAKE_DRIFT`）。 |
| 重放幂等（内容哈希 + 月份 → 返回同一单据） | 无内容哈希；防重靠 `FS.initiateInternal` 同仓同月未完成盘点 409 `DUPLICATE_STOCKTAKE`（FS :285-304，状态 APPLIED/REJECTED/RECOUNT_REQUIRED/CANCELLED 之外都算占用） | 有偏差 | 重复点击确认：原型第二次返回第一次的盘点单；正式第二次得到 409 业务错误（且第一次已建的期初壳在同一事务里保留）。客户端需处理 409，不能当失败重试。 |
| 期初批次状态 PENDING_OPENING、数量 0，审批前不增可用库存 | `OpeningInventoryServiceImpl.java:259-262`：壳批次 receiptQuantity=0、状态 **AVAILABLE**；数量与价值由盘点 apply 的盘盈补入（SBI :148-170 注释）；`@Transactional` 整体回滚（SBI :113） | 有偏差（效果等价） | 状态值不同，但数量 0 ⇒ 可用量 0，所有查询都有 `remaining>0` 门槛，不会被分摊；没有读到「盘点被取消/驳回时清理空壳」的逻辑，见缺陷 4。 |
| 盘点月份必须 YYYY-MM | Controller `@RequestParam periodMonth`（:91）无格式校验；`FS.initiateInternal` 里 `periodMonth = YearMonth.from(now)`（:268），**忽略请求里的 periodMonth** | 有偏差 | 正式盘点月份来自服务器当前月，不用调用方传入值；原型按传入值。非月底非期初还会被 `monthEndThreshold` 拦（FS :274-279）。 |
| 盘点两位小数落库 | `updateItems` `setScale(4)`（FS :569）存 4 位，但入参已限 2 位；apply 落 `receipt` 为 2 位 HALF_UP（FS :911-913） | 一致 | |
| apply 后数量不为负 | FS :914-917 clamp 为 0（`realizedDelta` 用于凭证） | 正式更严 | 原型 apply 不在本模块。 |
| 租户隔离 | SBI/FS 全程 factoryId（`materialBatchRepo.findByFactoryIdAndWarehouseId`、`findAndValidateForUpdate`、`findByIdAndFactoryId`） | 一致 | |

---

## 4. fusion-warehouse-layout.mjs（仓位/楼层配置、移库）

对照正式：`B/controller/WarehouseLayoutController.java`、`B/service/warehouse/WarehouseLocationCatalog.java`。

| 原型规则 | 正式后端方法 | 判定 | 说明 |
|---|---|---|---|
| 区域前缀 2–8 位大写字母、不可重复；区域需名称 | `WarehouseLayoutController.java:164-186` `validateLayout`（`PREFIX_PATTERN ^[A-Z]{2,8}$` :39，前缀去重 :182）；**未读到「区域名称非空」「区域 id 非空且不重复」校验**（只在 id 非空时登记前缀映射 :180） | 有偏差 | 前缀规则一致；区域 id 重复/缺失、区域名称缺失，正式不拦。 |
| 位置编码必须有对应区域前缀、不可重复；位置 id 唯一 | :188-205：必须关联存在的 zoneId 且 code 以该区域前缀开头、code 去重；**位置 id 唯一性未读到** | 一致（主规则），位置 id 缺失校验 | |
| 货架：区域存在、层数 1–100 整数、画布矩形有效；位置绑定货架/层数合法 | 正式 `validateFloors`（:222-239）只校验体量（楼层 ≤50、区域块 ≤200、标注 ≤300、折线点 ≤1000）；**不校验货架区域/层数/矩形数值，不校验位置绑定 rackId/level**，且源码注释明确故意不 fail closed（:211-219） | 有偏差（正式更宽） | 前端/原型的几何完整性约束在正式 API 不成立；接口可保存非法货架。 |
| 标注 kind 白名单、points 为有限数 | :257-270 kind 白名单与 points 个数上限；**不校验 points 为有限数字** | 有偏差（更宽） | |
| 底图：PNG/JPEG/WebP/GIF data URL、≤8MB | 保存接口 `saveLayout` 未读到底图校验；`recognize` 才限制图片 ≤12M 字符（:90-93） | 缺失（未读到） | 底图随整份 layout 写入 JSONB（`:67-80`），没有大小/类型限制，只有请求体大小限制（未读）。 |
| **删除位置时若仍有库存则拒绝**（遍历原料与成品批次，`remaining>0` 且 code 在旧配置、不在新配置） | `saveLayout`（:65-80）→ `validateLayout` 无此检查；全后端 grep「仍存有库存」「仍有库存」无相关命中；`WarehouseLocationCatalog` 仅做存在性校验 | **缺失（正式有缺陷）** | 见缺陷 5：删除/改码一个仍有库存的位置，会让批次上的 `storageLocation` 变成悬空编码，之后该批次再次移库/转冻只能选新位置，库存按位置筛选为 0。 |
| 移库：目标位置必须是本厂已配置编码；批次必须属本厂 | 成品：`moveFinishedGoods`（Controller :123-144）`requireConfiguredLocation` + `findById…filter factoryId`；原料：`MaterialBatchMapper.java:192/312`、`MaterialBatchServiceImpl.java:2693/2783` 同一 Catalog | 一致 | 单一判据 `WarehouseLocationCatalog.requireConfiguredLocation`（:~70-95）；空串=清空允许；长度>100拒。 |
| 移库原型只在 body 仅含 storageLocation 时处理 | 正式 `moveFinishedGoods` 独立路由 | 无法判断 | 路由风格不同，不影响规则。 |
| 保存需有权限 | `@RequireRole({"factory_super_admin","warehouse_manager"})`（:65）；移成品库位 `@RequireRole` 含 warehouse_worker（:123） | 正式更严 | |
| 租户隔离 | getLayout/saveLayout 全按 path factoryId 取 `FactoryFeatureConfig`（:57-80）；成品批次 `filter(factoryId)`（:139） | 一致 | |
| recognize 本机返回 501 | 正式 `recognize`（:84-121）真实调 `pythonLLMClient.visionChatWarehouse` | 有偏差（原型 stub） | 正式依赖 Python 视觉服务，失败返回 503。 |

---

## 5. 辅助：convertQuantity（fusion-report-contract.mjs:3）

原型只认 kg↔g（`unitCode` 归一后），其余报「缺少明确换算关系」。正式 `PSA` 质量单位直折 kg（`massStockToKg` :873-900，`isMassStorageUnit`），包装单位只在展示口径通过 `unitContractService.convert` 查包装规格（:855-880），分配口径不换算（:887-896）。判定：一致（分配口径），正式在展示口径更宽。

---

## 疑似正式后端缺陷（按严重度）

### 缺陷 1：stock-summary 对部分无单价批次返回偏低的 totalValue / avgUnitPrice，而非 null
- 位置：`B/repository/MaterialBatchRepository.java:690-691`（`SUM((receipt-used-reserved) * m.unitPrice)`）；`B/service/impl/MaterialBatchServiceImpl.java:2628-2636`（回填 avgUnitPrice）。
- 触发：同一 (物料, 单位) 组内有 ≥1 个 unit_price 为 NULL 的在库批次（盘点期初新建、`OpeningInventoryServiceImpl.java:261` 注释「诚实-null」本就允许；被价格脱敏也会是 null——见 `PlanCostVarianceCalculator` 对无价的处理）。
- 为何是缺陷：SQL SUM 忽略 NULL 乘积，totalValue 只含有价批次，totalQuantity 却含无价批次，avgUnitPrice = 偏低的价值 / 全量，系统性低估库存金额，且前端看不出来。与同仓库其它位置「不知道就说不知道」的原则（盘点 apply 的 uncostedCount、差异报表）相反。
- 口径判断：非口径问题。原型「缺价整组 null」是对的，且正式只有整组全无价才得 null。
- 建议修法：JPQL 里加 `SUM(CASE WHEN m.unitPrice IS NULL THEN 1 ELSE 0 END)` 或在 DTO 增加 `unpricedBatchCount`；有无价批次时 totalValue/avgUnitPrice 置 null（或另给「已估值部分」字段并标注）。

### 缺陷 2：盘点批量导入预览的「账面数量/差异」与确认后真实快照口径不一致（生产仓批次）
- 位置：`B/service/factory/impl/StocktakeBulkImportServiceImpl.java:81`（模板）、`:293`（预览）取 `getPhysicalQuantity()`；`B/service/factory/impl/FactoryStocktakeServiceImpl.java:383-386`、`:866`（快照与 drift 守卫）取 `physical − 未小结消耗`。
- 触发：生产仓（WORKSHOP）批次存在「已报工、未小结」的 material_consumptions。仓管按模板里的账面数量填实盘，预览显示 MATCH，实际 `updateItems` 用快照 systemQty 重算为 SURPLUS；反之相反。
- 为何是缺陷：SBI 注释（:289-292）自称「与 initiate 快照口径一致」，实际漏了 FS 在 2026-07-04 加的未小结扣减（FS :367-379）；预览与过账不一致正是 #1201 修过的同类问题，只修了一半（measurement-and-wiring 形态 D：同一口径两份）。
- 口径判断：非口径问题，FS 快照那份是权威。
- 建议修法：SBI 的 `exportTemplate` 与 `match` 调同一个 `physicalShelf` + `loadUnsettledByBatch`（抽成 FS 的包级 helper，两处不再各写一份）。

### 缺陷 3：期初导入未校验单价非负
- 位置：`StocktakeBulkImportServiceImpl.java:356-359` 只判 >2 位小数；`OpeningInventoryServiceImpl.java:261` 直接写入。
- 触发：期初新建行 unitPrice = -5。批次单价为负，之后盘点 apply 过账 `value = delta × unitPrice`（FS :925 附近）会产生负金额凭证。
- 是缺陷：原型显式校验 `price<0` 拒绝。其它入口是否有拦截未读到。
- 建议修法：match() 期初新建分支加 `unitPrice < 0` 拒绝（与 actual 校验同处）。

### 缺陷 4：期初建壳后若盘点被驳回/取消，空壳批次的去向未读到
- 位置：`StocktakeBulkImportServiceImpl.java:148-170`（confirm 事务里建壳）、`FS.cancel`（FS :203-258）。
- 触发：期初导入已建壳并生成盘点单，之后盘点被取消或驳回不再重提。
- 说明：壳 receipt=0，不影响可用量；但会留下状态 AVAILABLE、数量 0 的批次，出现在 by-warehouse 列表和下一次盘点模板里。本次未读到 cancel 里是否清理壳（未读到，不下结论）。
- 建议：核对 `cancel` 是否按 sourceDocType=期初 + 数量 0 清理；没有就加，或把壳状态改为独立状态（原型的 PENDING_OPENING）。

### 缺陷 5：保存仓位配置时不检查「被删除/改码的位置是否仍有库存」
- 位置：`B/controller/WarehouseLayoutController.java:65-80`、`validateLayout` :164-205。
- 触发：管理员删除/改码仓位 `LC-01`，该位置上仍有原料或成品批次（`storageLocation='LC-01'`）。
- 为何是缺陷：批次上留下悬空位置编码，`WarehouseLocationCatalog.requireConfiguredLocation` 之后拒绝再选它（只能清空或改选其它），库存页的位置筛选（`ProductionPlanServiceImpl.java:8911`、`SupplyChainOrchestrator.java:850` 注释已提到匹配数 0 的问题）对这些批次失效。
- 口径判断：原型已有此规则，正式无任何等价检查，倾向缺陷；若 Steve 认为「位置只是标签，允许悬空」则属口径，后果见待裁决 D。
- 建议修法：`saveLayout` 里仿原型：旧配置有、新配置无的编码，查原料（`findByFactoryIdAndWarehouseId` 过滤 storageLocation）与成品批次剩余量 >0 则 409；一并校验区域 id/名称、位置 id 唯一。

### 缺陷 6：仓位配置只校验体量，不校验货架/标注几何与层数
- 位置：`WarehouseLayoutController.java:211-270`（含 :211-219 故意不校验的注释）。
- 说明：`levels`、rack 的 zoneId、`level ≤ rack.levels`、矩形有限数值、底图类型/大小，接口全不校验。注释的理由（区域被删仍可存）只覆盖「zoneShape 指向不存在区域」，不覆盖「rack.levels=-1」之类明显非法值。风险为数据质量，不是库存正确性。
- 建议：保留区域孤块容忍，其余数值型约束补上（与原型一致）。

### 缺陷 7（低）：Controller 注释与实体行为矛盾，易误导后续修改
- 位置：`WarehouseInventoryController.java:168-171` 称「currentQuantity 恒 == receiptQuantity」；实体 `MaterialBatch.java:366` 实际 receipt − used − reserved。仅注释问题，但此段注释是用来论证「unsettledConsumption 单独成字段」的依据，之后有人据此改口径会出错。

---

## 待 Steve 裁决（口径不明）

### A. 生产投料是否要按计划隔离已签领的批次
- 现状：正式料进入生产仓后是公共池，任何计划可按 FEFO 吃到任意生产仓批次；只在客供料上按客户/订单隔离（`ProductionInventoryOwnershipGuard.java:70-95`）。原型：批次若挂了 productionPlanId，只能被该计划吃，且计划专属优先于无归属批次，显式指定别计划的批次直接拒绝。
- 口径一（隔离，原型）：计划 A 领的 100kg 不会被计划 B 的自动分摊吃掉；缺点是需要给 material_batches 加归属列（迁移、领料落点改造、退料/转计划流程），生产仓存量里挂不上计划的老批次要当「无归属」处理。
- 口径二（公共池，正式现状）：简单，物料按先到期先出统一调度；缺点是 A 计划领料后 B 计划先报工就把 A 的料吃掉，A 再报工会被拦或要重新领料——你们之前「有货没领 / 真没货」的提示（PSA :200-240）就是在这个模型下给的。
- 建议：先问现场是否真有「A 领的料必须留给 A」的纪律要求；没有就保持正式现状，把原型这条改回公共池；有则单独立项，别在融合里偷偷加。

### B. stock-summary 是否显示客户自有料
- 口径一（原型）：客户自有料单独成行，工厂可见代加工料总量。口径二（正式）：汇总只含公司自有，客户料不进资产（`MaterialBatchRepository.java:700`）。
- 后果：采用口径一要避免与公司库存金额混加（原型已按 ownership 分组，没混）；采用口径二则代加工料需要另一个入口展示。账务上口径二更安全。
- 建议：保持正式口径，原型加一个独立「客户料」页签或保留分组但不并入合计。

### C. 同到期日批次的第二排序键：入库日 vs 转入生产仓时间
- 原型 receiptDate；正式生产分摊 createdAt（生产仓内为调拨转入顺序，`MaterialBatchRepository.java:489-507` 注释说明是刻意的）。仓库级 FEFO（调拨/领料）用 receiptDate（:347）。
- 后果：同一物料同到期日、不同入库日的批次，两种口径先用的批次不同；只影响同到期日的并列情形，不影响过期风险。
- 建议：以正式生产分摊口径为准（转入顺序），原型改 createdAt；若要统一两处 FEFO，需单独评估对既有调拨的影响。

### D. 删除仍有库存的仓位：拦截 vs 允许悬空
- 口径一（原型、倾向）：拦截，需先移库。口径二：允许，批次保留旧编码，库存只剩「未分配/悬空」状态。
- 后果：口径二下，所有按位置筛选/看板（含 AI 的仓储空间筛选）对这些批次返回缺失；且 `requireConfiguredLocation` 会让同批次后续的冻品转换、移库在不改位置时仍可通过（null=不改动），但改任何位置必须是新配置内的编码。
- 建议：采用口径一，在后端补（即缺陷 5 的修法）。

### E. 盘点导入「重复提交」应返回原单据还是 409
- 原型：同一内容 + 月份第二次返回第一次的盘点单（幂等）；正式：409 `DUPLICATE_STOCKTAKE`（同仓同月未完成盘点）。
- 后果：对客户端是否要「重试安全」有影响；409 更保守，不会误以为是新建成功。
- 建议：保持 409，把原型改为复现 409（或前端处理该码为「已存在，点击查看」）。

---

## 本次未读到 / 未验证（不下结论）
- 盘点导入行数上限（原型 5000）在正式是否存在：`ExcelUtil.importFromExcel` 未读。
- `ProductionInputAllocation` 的 pending 与 `MaterialConsumption` 未小结两本账是否会同时存在、是否有重叠扣减：分摊用前者（`ProductionInputAllocationRepository.java:21-25`），by-warehouse 视图与盘点用后者（`MaterialBatchServiceImpl.java:1634-1655`），两者的写入关系未追到底（形态 D 风险，建议另派核对）。
- `MaterialBatchStatus` 枚举里是否存在 REVERSED 值：未读到。
- 盘点取消/驳回对期初空壳的处理：见缺陷 4，未读到。
- `MaterialBatchMapper.toDTO` 里 currentQuantity 的映射：未读到。

---

<!-- section B-transfer-receipt -->
# B 切片：调拨 / 收货 / 冲销 / 报工库存侧 —— 原型规则 vs 正式 Java 后端

核对方式：只读。原型读 `origin/claude/cretas-nextui-prototypes-2d5762:prototypes/ydwm-v1/fusion-*.mjs`，Java 读本 worktree（HEAD d6dc07cbaa）。行号为本 worktree 的行号。
路径缩写：`B/` = `backend/java/cretas-api/src/main/java/com/cretas/aims/`。
- TSI = `B/service/inventory/impl/TransferServiceImpl.java`
- PSI = `B/service/inventory/impl/PurchaseServiceImpl.java`
- PPS = `B/service/impl/ProductionPlanServiceImpl.java`
- IRR = `B/service/yield/impl/InterimSettleReversalRequestServiceImpl.java`
- IRS = `B/service/yield/impl/InterimSettleReversalServiceImpl.java`
- FGF = `B/service/inventory/impl/FinishedGoodsFeedServiceImpl.java`

判定图例：一致 / 有偏差（两边行为不同，下文说明哪边该改）/ 正式更严 / 缺失（正式没有）/ 无法判断（未读到）。
未读到的东西一律写「未读到」，没有按路由名推断。

---

## 1. fusion-warehouse-transfer（同厂仓库调拨，原子确认路径）

Java 对应：`TransferController`（/transfers/*）→ `TSI.createTransfer`(191)、`confirmTransfer`(1305)、`deductSourceInventory`(1852)、`createTargetInventory`(2371)、`cancelTransfer`(1701)。

| 原型规则（文件+位置） | 正式后端方法（文件:行号） | 判定 | 说明 |
|---|---|---|---|
| 只支持同工厂 WAREHOUSE_TO_WAREHOUSE；源/目仓必须存在、启用、互不相同（warehouse-transfer 的 POST /transfers 段） | `TSI:305-327 validateCreateWarehouseRoute` 只校验「同厂、两仓非空、两仓不同」 | **有偏差** | Java 建单**不查仓库是否存在、是否启用、是否属本厂、仓型**。只有分步发运/收货路径调 `assertWarehouseDeliveryWarehouses`(1585-1597)。原子 confirm 路径(1305)没有等价校验，见缺陷 D1。 |
| 一张单内同一物料只能一行；数量必须 >0；单位须与物料档案一致（`seen.has(ref.id)`、`unitCode(i.unit)!==unitCode(ref.unit)`） | `TSI:427-450 assertNoDuplicateItemRows`；`TSI:530-544 ensureCreateQuantityAvailable`(>0)；单位归一 `TSI:617-626` | 一致（Java 更细） | Java 对质量/体积做单位归一，计数单位按字面比较，原型 `unitCode` 是另一套规则，单位口径本身不等价（见 §8 单位）。 |
| 建单时按源仓可用量快速失败（原型建单不检查库存，只在 confirm 检查） | `TSI:344-411 validateAndNormalizeCreateItems`（成品 371、原料 389） | 正式更严 | Java 建单即拦库存不足；原型建单放行、confirm 才拦。 |
| 任一行库存不足，整单一个字都不动（原型先算 `changes/created` 后统一 `beginBatch` 写入，不足直接 `fail`） | `TSI:1305` confirm 整体 `@Transactional`；原料先预检合计 `TSI:1909`，成品不足 `TSI:2013` 抛 409 | 一致（靠事务回滚） | 原料先整行预检再扣；成品是边扣边判，靠异常回滚整事务保证原子。多行时第 N 行失败会回滚前 N-1 行（`@Transactional` 且异常为 BusinessException，默认回滚）——**一致**，但依赖事务边界，非「先算后写」。 |
| 客户自有批次不动（`ownershipType/ownership==='CUSTOMER_OWNED'` 被滤掉） | `B/repository/MaterialBatchRepository.java:340-347`（FEFO 查询带 `ownership IS NULL OR COMPANY_OWNED`）；成品 `B/repository/inventory/FinishedGoodsBatchRepository.java:118-124` 同条件；解释文案 `TSI:493-528` | 一致 | |
| 生产批次来源（`sourceDocType==='PRODUCTION_BATCH'`）不动 | `MaterialBatchRepository:345`（`sourceDocType <> 'PRODUCTION_BATCH'`） | 一致（原料）；**成品侧未读到** | 成品查询 `FinishedGoodsBatchRepository:118-124` 没有 PRODUCTION_BATCH 条件（成品批次本来也没有这个来源类型，未读到成品是否会有该值）。 |
| 只扣 `AVAILABLE/ACTIVE/undefined` 状态批次 | 原料 `status = 'AVAILABLE'`；成品 `status = 'AVAILABLE'` | 一致 | |
| 按「到期日→入库日」先进先出；若用户预选批次则放队首（原型：`source-batch` 排序 `b.id===i.sourceBatchId` 优先，再 `expireDate\|\|createdAt`） | 原料 `ORDER BY expireDate ASC NULLS LAST, receiptDate ASC, id ASC`(MaterialBatchRepository:347)；成品 `expireDate ASC NULLS LAST, productionDate ASC`(FinishedGoodsBatchRepository:124)；预选 `TSI:reorder*Preselection` | 一致（排序键略不同） | 原型无到期日时用 createdAt，Java 无到期日排最后再用 receiptDate。**目标批次不带到期日**会放大差异，见缺陷 D2。 |
| 货架实物口径：原型只看批次账面 `currentQuantity/availableQuantity` | `TSI:1893-1920 physicalShelf = 账面 − 未小结报工消耗` | 正式更严 | Java 扣减前减去「已报工未小结」的消耗，原型没有这一层。融合时以 Java 为准。 |
| 成品同厂调拨：减源批 `producedQuantity`，不动 `shippedQuantity`（原型 `after.producedQuantity -= n`） | `TSI:1998` `batch.setProducedQuantity(... .subtract(deduct))` | 一致 | |
| 目标批次：每个源批各生成一个目标批次，`...original` 继承全部字段（含到期日），`sourceDocType=INTERNAL_TRANSFER`、`sourceBatchId` | `TSI:2371-2465 createTargetInventory`：每个**调拨行**只生成**一个**目标批次（数量=行数量），不设 `expireDate`，`sourceDocType=TRANSFER_IN`（`TSI:2395-2415`） | **有偏差** | ① Java 目标批次**丢到期日**（全文无 `setExpireDate`；仅 `ProcessTimeLimitService.java:1069` 读 TRANSFER_IN 批次，未见回填）；② 多个源批合成一个目标批次，批次级追溯靠 lineage edge（`TSI:1344 recordMaterialTransferEdges`）而非批次字段；③ `sourceDocType` 值不同（INTERNAL_TRANSFER vs TRANSFER_IN）。见缺陷 D2。 |
| 目标批次 ownership：原型继承源批 | Java 不设 ownership（`TSI:2395-2415` 无 `setOwnership`，null 被各查询当公司自有） | 一致（因源已限定公司自有） | |
| 取消：只许 DRAFT/REQUESTED/APPROVED/REJECTED，**原因必填**（原型 cancel 分支） | `TSI:1701-1720 cancelTransfer`：终态与 SHIPPED/RECEIVED 拒绝，其余可取消；原因由 `TransferController:211-220` 以 `@RequestParam(required=false)` 接收 | **有偏差** | Java 不要求原因。另 REJECTED 在 Java 是终态（`TransferStatus.java:41`），取消会被「终态不能取消」拦下，原型允许取消 REJECTED——无业务影响。 |
| 重复 confirm 幂等：已 CONFIRMED 直接返回成功（`if(before.status==='CONFIRMED')return ok(before)`） | `TSI:1312 assertStatus(... APPROVED ...)`，已确认再点抛 409 | **有偏差**（口径） | 原型幂等返回，Java 报错。属接口语义差异，不会双扣库存（Java 有调拨行行锁 `TSI:1567-1583` + 状态闸）。 |
| 并发：原型单进程无并发 | 调拨行 `FOR UPDATE`(`TSI:1573-1576`)；批次靠 `@Version` 乐观锁（`entity/MaterialBatch.java:85`、`entity/inventory/FinishedGoodsBatch.java:247`），原子 confirm 路径的 FEFO 查询**不加行锁**（`MaterialBatchRepository:348` 无 `@Lock`） | 一致（Java 有机制） | 两张不同调拨单并发扣同一批次时，后提交者得到乐观锁异常，是否被映射为 409 而不是 500 **未读到**（待核 GlobalExceptionHandler）。 |
| 租户隔离：原型硬编码 FOOD_0000_001 | 所有批次查询带 `factoryId`；调拨单 `findByIdAndEitherFactoryId`(`B/repository/inventory/InternalTransferRepository.java:94`)；行锁 SQL 带 factory 条件(`TSI:1573-1576`) | 一致 | `TSI:2238 validateBatchForItem` 用 `findById` 再比对 factory（不是直接带 factory 的查询），结果等价。 |

## 2. fusion-transfer-delivery（仓内分步交付：发运 / 收货）

Java 对应：`TSI.dispatchWarehouseTransfer`(1369)、`receiveWarehouseTransfer`(1464)、`toWarehouseDelivery`(1604)、实体 `entity/inventory/WarehouseTransferMovement.java`。

| 原型规则 | 正式后端方法 | 判定 | 说明 |
|---|---|---|---|
| 分步记录后禁止一次性 confirm（原型 warehouse-transfer 里 `staged` 标记 → 「请从仓内分步交付录入实际收货」） | `TSI:1312` confirm 要求状态 APPROVED；发运后状态为 SHIPPED(`TSI:1450`)；发运前还查 movement 历史(`TSI:1382`) | 一致（间接） | Java 靠状态机阻止，没有显式「staged」文案；提示变成「状态不对」。 |
| 发运要求 APPROVED；整单所有行不足则一行都不发 | `TSI:1376` APPROVED；`TSI:1388-1425` 先对所有行校验并按批次汇总需求后再写 | 一致（Java 先校验后写，更干净） | |
| 发运可不预选批次，按先到期批次自动分配（原型 `allocations`，多批） | `TSI:1389-1394`：**每行必须已选 `sourceBatchId`**，且只支持原料、公司物权、非包材 | **有偏差**（Java 更严） | 原型能 FEFO 自动拆批发运；Java 不能。原型也支持成品和包材吗：原型 `stockBase` 对 FINISHED_GOODS 走成品库存；Java 分步只支持 `supportsWarehouseDeliveryItem`(`TSI:1601`，原料非包材)。融合时需要决定：要么扩 Java 能力，要么原型限制到同口径。 |
| 批次锁：原型无 | `TSI:1400-1405` 按批次 id 升序 `findByIdAndFactoryIdForUpdate`（防死锁、串行化） | 正式更严 | |
| 发运幂等：已发运再发运返回已有结果（`if(saved?.staged)return ok(saved)`） | `TSI:1376` 状态必须 APPROVED，已 SHIPPED 再发抛 409；movement 历史非空也抛 409(`TSI:1382`) | 有偏差（口径，同 §1 重复 confirm） | 不会重复扣库存。 |
| 收货必须带 `requestKey`，长度 8-128，`itemQuantities` 对象 | `B/dto/inventory/WarehouseTransferReceiptRequest.java`：`UUID requestKey`（必须是 UUID），`Map<Long,BigDecimal>`；`TSI:1466-1468` 判空 | **有偏差** | Java 要求 UUID 格式，原型只要 8-128 字符串。融合时前端必须传 UUID。 |
| requestKey 幂等：同 key 同数量 → 返回原结果；同 key 不同数量 → 409（原型哈希 `digest` 比对） | `TSI:1476-1480`：只要 key 已存在就 409「requestKey已处理」；表上唯一约束 `uq_wtm_request_item (request_key, transfer_item_id)`（`WarehouseTransferMovement.java:17`） | **有偏差** | ① Java **没有「同 key 同数量返回原结果」**：网络重试会收到 409，调用方必须再去查交付状态；② Java 不比对数量哈希，同 key 不同数量一律 409（结果更严，但提示不区分）；③ 不会重复入库（有唯一约束 + 事务），库存安全。属接口语义差异。 |
| 本次收货每行 >0，不能超过待收量；最多四位小数（`Math.abs(v*10000-round(v*10000))>1e-6`） | `TSI:1486-1487` 非负且 `scale()<=4`；`TSI:1510` 超过待收量 409；`TSI:1514` 至少一行 >0 | 一致 | 细节：Java 的 `scale()>4` 是按 BigDecimal 书写位数判，`1.50000`（scale 5）会被拒；原型按数值判。前端应去尾零。 |
| 收货按发运分配（allocations）逐源批生成目标批次，收到多少建多少 | `TSI:1516-1544`：按行建一个目标批次，数量=本次 qty；以 `UUID` 作批次号后缀；记 RECEIPT movement 与 lineage edge | 一致（口径有别） | 同 D2：目标批次不带到期日。 |
| 收货前再核对「发运量=行数量、已收=行已收」，不一致拒绝（原型：「在途数量与收货记录不一致」） | `TSI:1499-1506` | 一致 | |
| 收满后转 CONFIRMED，发布确认事件（原型 `stage==='CONFIRMED'`） | `TSI:1545-1555`（发 `TransferConfirmedEvent`，事件失败不处理——此处没有 try/catch，与 confirmTransfer 的 try/catch(1355-1363) 不同） | 一致 / 小偏差 | 分步收货的事件发布**无异常保护**(`TSI:1552`)：监听器抛错会让整笔已入库的收货回滚——比原子路径严格；按风险属可接受，记入 D6。 |
| 取消：分步后不可取消（原型 cancel 只允许未移动库存） | `TSI:1701-1713` SHIPPED 拒绝取消 | 一致 | |
| 并发：原型无 | 调拨行 `FOR UPDATE`(`loadTransferLocked` 1567-1583)、批次行锁(发运)、`uq_wtm_request_item`、仓库 `FOR SHARE`(1591-1594) | 正式更严 | |

## 3. fusion-receipt（生产成品入库 `postReceipt`，仓库确认收货）

Java 对应：`ProductionWarehouseReceiptMobileController`(`/warehouse/transit-ledgers/{id}/confirm`，`B/controller/ProductionWarehouseReceiptMobileController.java:117`) → `PPS.confirmWarehouseReceipt`(5946)；工作流多产出路径 `PPS.confirmWorkflowOutputReceipt`(6172)。

| 原型规则（fusion-receipt.mjs） | 正式后端方法（文件:行号） | 判定 | 说明 |
|---|---|---|---|
| 必须先有生产核对（`settlement.settlementId`），否则报错 | `PPS:5968-5970` 取 settlement，缺则 `bridgeProductionSettlementForWarehouse` 补桥；工作流计划无末道产出行时 409(`PPS:6010-6015`) | 一致（Java 容错更多） | 非工作流计划没有结单会自动桥接，而非报错。 |
| 收货必须逐产出批次完整核对，不重复不遗漏 | 工作流路径：`PPS:6187-6240`（行数必须相等、重复报 409、缺行/多行 409） | 一致 | 非工作流路径只有单个 `receivedQuantity`。 |
| 收货数量 ≠ 报产数量时，**差异原因必填**（任何差异） | 非工作流：`PPS:6052-6063` 只有**超出称重容差**才要求原因（`receiptTolerance(unit)`，`PPS:8828`；kg 为 10kg，见 `PPS:6096` 文案）；容差内走 `POSTED_WITH_TOLERANCE` 不挂账。工作流：**没有差异原因概念**，允许分次收货，累计不得超过报产（`PPS:6225-6231`），剩余待后续收 | **有偏差**（口径不同） | 原型：一次性收货，任何数量差都要原因；Java：存在 10kg 称重容差、超容差才要原因+责任侧(`PPS:6064-6069`)、工作流可分次累计收货。两者对「少收」的建模完全不同。见 R1「待 Steve 裁决」。 |
| 多收：原型无单独规则（`receivedQuantity` 可 > 报产，只需原因） | `PPS:6031-6035 assertOverReceiptAllowed`（`PPS:9280`）：工厂开关，默认硬拦；工作流路径累计超报产 409(`PPS:6225`) | 正式更严 | |
| 仓库不猜：入参→小结仓→（仅单仓时才用）；存在多个仓不能自动猜 | `PPS:8924`、`PPS:9025`：`warehouseResolver.resolveFinishedGoodsId(factoryId)`，**固定走 WH-FG 成品仓，没有入参选仓**；移动端列表同走 `resolveFinishedGoodsName`(`ProductionWarehouseReceiptMobileController.java`) | **有偏差** | Java 不让仓管选仓，也不检查仓型白名单（原型要 WORKSHOP/FINISHED/LOGISTICS）。若工厂没有 WH-FG，`finishedGoodsWarehouseName` 退化为「未指定」文案（Controller 有 warn 日志），入库行为由 resolver 兜底**未读到**。见 D5（待裁决）。 |
| 幂等：用 `receiptHash`（仓+各行数量）；已收货时同哈希返回原结果，不同数量报错 | `PPS:5972-5991`：用 `Idempotency-Key`（Controller 强制必填、≤96 字符，`ProductionWarehouseReceiptMobileController.java:346-360`）。已收货：同 key 返回原结果；不同 key 抛 409 `PRODUCTION_RECEIPT_ALREADY_CONFIRMED`。工作流路径 key 逐行记录(`PPS:6179-6184`) | **有偏差**（机制不同，结果等价） | Java 靠 key 而非内容哈希：**同 key 换数量会静默返回旧结果**（无内容比对）；原型是内容哈希，同内容重发也算幂等。融合建议：前端每次提交新 key；服务端是否需要补内容哈希，见 R2。 |
| 数量取整：原型库存 `producedQuantity=receivedQuantity` 无显式取整（四位口径来自 BACKEND-MERGE-MAP） | 工作流路径：`PPS:6316-6328 requireInventoryQuantityScale`（`setScale(4, UNNECESSARY)`，超过四位 409）；非工作流路径 DTO 仅 `@DecimalMin(0.0001)`，**未读到四位取整** | 一致（工作流）/ 无法判断（非工作流） | |
| 产出单位须与小结一致 | `PPS:6037-6051`：与权威单位不等 409 `PRODUCTION_RECEIPT_UNIT_MISMATCH` | 正式更严 | |
| 租户隔离 | `PPS:5959` `findByIdAndFactoryId`；结单行锁 `findByFactoryIdAndProductionPlanIdForUpdate`(5968)；`ProductionCostConsistency.lockFactory`(5965-5967) 工厂级串行化 | 正式更严 | |
| 成品批次创建：成本 `unitCost=null, costIncomplete=true` | `PPS:6253 allocateWorkflowOutputCosts`；成本算不出时回 warning(`PPS:6340-6349`) | 一致（Java 更完整） | 不属本切片深挖范围。 |

## 4. fusion-receipt-edits（采购收货草稿：创建 / 改数 / 作废）

Java 对应：`WarehouseReceivingController`(`/warehouse/receiving/receipts*`，`B/controller/warehouse/WarehouseReceivingController.java:177-251`) → `PSI.createReceiveRecord`(2523)、`updateReceiveRecordItems`(3024)、`cancelReceive`(3099)、`confirmReceive`(2730)。

| 原型规则 | 正式后端方法 | 判定 | 说明 |
|---|---|---|---|
| 采购单必须 `FINANCE_APPROVED` 或 `PARTIAL_RECEIVED` 才可收货（原型每个动作都先查） | 创建：`PSI:2547-2553`（`FINANCE_APPROVED`/`PARTIAL_RECEIVED`，否则 409）；改数/作废：**不查采购单状态**(`PSI:3028-3045`、`3099-3110`)；确认：`PSI:2756-2779` 未查订单状态，只查超收与快照 | **有偏差** | 创建一致；改数、作废、确认三步 Java 不复查订单状态。对草稿作废不构成风险；对确认：订单在草稿期间被关闭（`closeReceivingTask`）后仍可确认——**未读到**是否有别处拦截。见 D4。 |
| 收货供应商须与采购单一致 | `PSI:2566-2570` | 一致 | |
| 同一采购单同时只允许一张活动草稿（原型：`activeReceiptCount`） | `PSI:2554-2565` `PURCHASE_RECEIPT_ACTIVE_EXISTS`（`isActiveReceipt`） | 一致 | |
| 收货行必须属于该采购单，且物料/单位/行 id 一致，行不重复 | 创建：`resolvePurchaseOrderItem`(`PSI:4357-4388`)；**未见同一采购单行重复的校验**(`createReceiveRecord` 逐行循环 2650+ 未去重) | **有偏差** | 原型 `seen` 去重；Java 创建/改数都没有对同一 `purchaseOrderItemId` 重复出现去重（`updateReceiveRecordItems` 用 Map 覆盖，后者覆盖前者；创建则会建两行）。超收校验 `validateOverReceiveCap` 逐行单独比较，同一 PO 行出现两次时合计可能越界（若开启超收上限）。见 D3。 |
| **沿用采购单保存时的换算快照**，拒绝与快照不符的单位/换算（原型：`packageToBaseFactorSnapshot`、`inventoryBaseUnitSnapshot`，不符 → 「必须沿用采购单保存时的快照」） | 创建 `PSI:3768-3799 resolveReceiptSelection`：同单位且未指定规格 → 用 **PO 行快照** `getPackageToBaseFactorSnapshot()`（快照缺失才回退到现档案 `conversionFactor`）；**不同单位/指定规格 → 用当前物料包装规格档案重算**，只要求基本单位一致。改数 `PSI:3069-3072`：沿用**收货行自己**已存的 `packageToBaseFactorSnapshot`。确认 `PSI:3611-3655 validatePersistedReceiptSelection` 再校基本单位 | **有偏差**（口径不同，且正式更灵活） | 原型严格：只许沿用采购单快照。Java：实际到货包装可换成别的规格（`resolveMaterialPackagingSelection` 读当前档案换算），快照保存在**收货行**上。→ 「用当前档案重算」在 Java 的非同单位分支是**有意允许**的。是否收紧，见 R3「待 Steve 裁决」。 |
| 超收拦截：累计收货 > 采购量即拦（原型 `Number(i.receivedQuantity)+Number(line.receivedQuantity)>Number(line.quantity)+1e-8`，**零容忍**） | `PSI:4293-4316 checkOverReceiveCap`；上限由 `OverReceiveLimit.resolve`(`PSI:129-135`) 决定：工厂配置 > 属性 `cretas.purchase.over-receive-rate`（`PSI:98-123` 注释：**默认 null = 不限制超收**） | **有偏差**（口径冲突） | **默认 Java 不拦超收**，原型零容忍。见 R4「待 Steve 裁决」。校验位置三处一致：创建(`PSI:2580`)、改数(`PSI:3043`)、确认前(`PSI:2778 validateOverReceiveCapForConfirm`)、累加时兜底(`PSI:4340`)。 |
| 仓库类型白名单：只允许 `RAW`/`SALTED`/`LOGISTICS`/`OUTSOURCE` 收采购（原型 `['RAW','SALTED','LOGISTICS','OUTSOURCE'].includes(warehouse.type)`） | `PSI:2584-2589` 与 `PSI:4147`：`WarehouseInventoryGuardService.assertCanReceive(...,"RAW")`（`B/service/factory/WarehouseInventoryGuardService.java:73-130`）：**黑名单式**——只拒 `WIP`、`FINISHED`；`RAW/SALTED` 通过；`TEMP/QC/LINESIDE/RETURNS/OUTSOURCE/SCRAP/TRANSFER`、`LOGISTICS/WORKSHOP` 以及**类型为 null** 的仓一律放行 | **有偏差**（Java 更松） | 原料可入 SCRAP/TRANSFER/QC/RETURNS 等仓。见 D5。 |
| 必须明确选仓，不猜 | `PSI:4137-4141`：`record.warehouseId` 为空 → `warehouseResolver.resolvePurchaseInboundWh(factoryId)`（默认采购入库仓）；创建时只在**传了仓**才校验仓型(`PSI:2586`) | **有偏差** | Java 允许不传仓，系统按配置兜底；原型必须显式选仓。见 D5。 |
| 收货数量 >0；四位小数；改数每行 >0 | `B/dto/inventory/CreateReceiveRecordRequest.java`：`@DecimalMin("0.01")`（**最小 0.01**）；`inventoryQuantitySnapshot = qty × factor`（`PSI:3072`、`PSI:2645`）**不取整**，批次 `receiptQuantity` 直接写入（`PSI:4064-4075`） | **有偏差** | Java 最小收货量 0.01，原型最小 >0（可 0.0001，四位小数）。Java 对换算后数量**不做四位取整**（`toBase` 为 `multiply`，`PSI:3708-3710`），小数位由 DB 列精度决定，**未读到列精度**。 |
| 改数只能改草稿，且只能改既有行，不能增删行 | `PSI:3028-3033`（仅 DRAFT）；`PSI:3057-3067` 不存在的行 400 | 一致 | |
| 改数后合计金额重算 | `PSI:3081 record.setTotalAmount(totalAmount)`：`totalAmount` 初值 0，只累加**请求里出现的行** | **缺陷（小）** | 若只提交部分行，未提交行的金额被丢出合计。见 D4b。 |
| 作废：只能作废草稿，留痕不删 | `PSI:3099-3111` 仅 DRAFT；只改状态 | 一致 | |
| 确认：附件凭证必传（原型无） | `PSI:2738-2748` | 正式更严 | |
| 并发 | 收货单 `FOR UPDATE`(`findByIdAndFactoryIdForUpdate`)；采购单头 `FOR UPDATE`(`PSI:2545`、`2756`)；`lockActiveMaterials` | 正式更严 | |
| 租户隔离 | 供应商、采购单、仓库均带 factoryId(`PSI:2535-2537`、`WarehouseInventoryGuardService:73-80`) | 一致 | |
| 批次质检：原型不涉及 | `PSI:4118-4126`：qcResult 非 PASS 自动 DEFECTIVE | 正式更严 | |

## 5. fusion-reversals（小结撤销）

Java 对应：申请端 `IRR.requestReverse`(83)，审批端 `IRR.approve`(160)，执行 `IRS.reverseInterimSettle`(≈86)；成品入库冲销 `FGF.reverseInterimCreate`(268)。唯一调用入口是 `IRR:172`（已 grep 确认没有别处调 `reverseInterimSettle`）。

| 原型规则（fusion-reversals.mjs） | 正式后端方法 | 判定 | 说明 |
|---|---|---|---|
| 只对 `sourceType==='SAFETY_STOCK'`（存货计划）开放 | `IRR:83-` 无计划类型限制 | **有偏差** | Java 不限制计划类型。原型这条是原型自身范围，不必移植。 |
| 撤销原因必填 | `IRR:87-93` | 一致 | |
| 24 小时窗口，从小结 `createdAt` 起 | `IRR:67`（24h）；申请端 `IRR:110`、**审批端再校一次** `IRR:~164-167`（锚点仍是小结 `postedAt`，不是申请时间）；`IRR:assertWithinWindow` | 一致（Java 更严） | 原型只在申请端校验，**审批端不复核**（审批拖过 24 小时仍可执行）。Java 两端都校。以 Java 为准。 |
| 同一小结已有待审批申请 → 返回已有申请（幂等） | `IRR:115-123` 抛 409 `INTERIM_REVERSE_REQUEST_PENDING` | 有偏差（口径） | 原型幂等返回；Java 报错。 |
| 审批：驳回原因必填；已处理 409；EXECUTED 再批准返回原结果 | `IRR:loadPending`：非 PENDING 一律 409；驳回原因**不要求**(`IRR reject`，`reason` 直接落库)；审批限角色 `finance_manager/factory_super_admin/platform_admin`(`IRR:assertApprovalRole`) | **有偏差** | ① Java 驳回不强制填原因；② Java 有角色闸，原型没有；③ EXECUTED 再批准 Java 报 409，原型返回原结果。 |
| 下游已发货或已消耗就拒绝：`shippedQuantity>0 或 availableQuantity!==producedQuantity` → 409 | `FGF:327 recoverable.compareTo(qty)<0` 拒绝（`FG_DOWNSTREAM_CONSUMED`，`FGF:327-399`）：可回收 = 主批 `produced−shipped−reserved` + 同厂调拨子批可回收；**在途调拨**按未回收计 | **一致，Java 更精细** | Java 允许把「同厂调拨出去的子批」一并冲回（原型一刀切拒绝）；对「已报废/盘亏/人工调整减量」给出专门提示。原型的规则比 Java 严（Java 允许冲同厂调拨子批）。融合以 Java 为准，原型对应用例要改。 |
| 退料：对 `line.consumed===false` 的原料投料，把数量加回来源批次 `remainingQuantity/currentQuantity/availableQuantity` | `IRS` ⑤：反查 `findByFactoryIdAndProductionBatchIdInAndInterimSettledAt`，`usedQuantity -= mc.quantity`（≥0 夹到 0，`IRS:226-232`）；状态从 USED_UP 恢复时过「不合格批次守卫」；落在已关单领料单生产仓批次上的回料直接退原料仓（⑤.5，C2） | 一致（Java 更完整） | 不是同一数据模型（原型加 remaining，Java 减 used），结果等价。 |
| 若来源批次不存在 → 409，不恢复库存 | `IRS:218-222` 404 | 一致 | |
| 冲销先做下游守卫，再做半成品/成品/原料，整体原子 | `IRS` 步骤 ①②③④⑤⑥⑦ 同一 `@Transactional`；任一守卫抛 → 整事务回滚、申请留 PENDING（`IRR:approve` 注释） | 一致 | |
| 并发：双撤 | `IRS:99 findByIdAndFactoryIdForUpdate` 悲观锁，败者重读为空 → 409 | 正式更严 | |
| 旧小结（无逐笔明细）不撤销 | `IRS:107-114 INTERIM_REVERSE_NO_DETAIL` | 正式更严 | 原型无此概念。 |
| 负库存 | `IRS:226-232` 已用量不为负；`FGF:327` 拒绝致负 | 一致 | |
| 租户隔离 | `IRR:83` 起全部 `findByIdAndFactoryId`；`IRS` 全部带 factoryId | 一致 | |

## 6. fusion-report-stock（只核对库存侧）

Java 对应：`B/service/recipe/RecipeCostCalculator.effectivePotRawKg`(56-68)、`B/service/processentry/impl/ProcessSheetServiceImpl.java:704-745`（调料自动扣料需求）、`B/service/processentry/impl/ReworkPolicyAutoDeduction.java`、`B/service/workflow/WorkflowReworkDeduction.java`、`B/service/bom/impl/BomSeasoningWorkspaceServiceImpl.java:706-713`。工序/半成品/领料状态机不在本线。

| 原型规则（bomTotals / autoReworkChanges） | 正式后端方法 | 判定 | 说明 |
|---|---|---|---|
| 续锅比例合法域：整数锅数 ≥1，比例 0..1，否则抛错 | `BomSeasoningWorkspaceServiceImpl:706-713`（比例 0..1，保存时校验，非 COOKING 段不许填比例 `BomRecipeServiceImpl:2429`）；锅数在 `ProcessSheetServiceImpl:710` 取 `potRawKgs.size()`，**未读到「锅数为整数≥1」的单独校验** | 一致（比例）/ 无法判断（锅数） | |
| 有 `potRawKgs`：有效原料 = 首锅 + 其余锅合计 × 比例；合计必须等于本次投入（±1e-5），锅数必须等于 `potCount` | `RecipeCostCalculator:56-68`：首锅 + (总 − 首锅) × 比例，**与原型公式一致**；但 `ProcessSheetServiceImpl:704-745` 对 `totalPotKg` 与 `inputKg` 是否相等**未读到校验**（冷却/熟制取 `totalPotKg`，注射取 `inputKg`） | 一致（公式）/ 缺失（合计校验，未读到） | |
| 无 `potRawKgs` 且锅数>1：按均摊 `weight*(1+(n-1)*ratio)/n` | Java 无此分支：`potRawKgs` 为空时当作单锅 `List.of(inputKg)`（`ProcessSheetServiceImpl:705-709`），注释明示「⛔ 不按总量/锅数均摊」（`RecipeCostCalculator:49-54`） | **有偏差（原型错）** | Java 明确拒绝均摊。原型的均摊分支在融合时应删掉，要求逐锅填原料。 |
| 比例作用范围：原型对**所有**调料绑定一视同仁；`countInSeasoning===false` 的任何段都排除 | `ProcessSheetServiceImpl:727-734`：续锅比例只作用于 **COOKING 段**；注射段用 `inputKg`；`COOKING` 且 `countInSeasoning` **不为 TRUE（含 null）**一律跳过（`ProcessSheetServiceImpl:723-726`）；`apply` 保存时默认 TRUE（`BomSeasoningWorkspaceServiceImpl:297-302`） | **有偏差** | 原型：注射段也受比例影响、`null` 算计入；Java：注射段不受比例影响，`null` 被当「不计入」。数据上 `null` 只会出现在历史数据，影响面小，待核数据。 |
| 调料用量 = 有效原料 kg × 每公斤克数 → 单位换算 | `ProcessSheetServiceImpl:735-745`：`× dosagePerKgG / 1000`，`divide(...,12,HALF_UP)`，`stripTrailingZeros` | 一致（精度口径：原型 `toFixed(6)`，Java 12 位 HALF_UP） | 取整口径不同：Java 保留 12 位；写入库存前是否二次取整未读到。 |
| 回料先进先出：只扣**同计划**批次，按 `createdAt` 升序 | `ReworkPolicyAutoDeduction:200-212`：候选 = `wipInventoryService.listWipByFactory(factoryId, productTypeId, processOrder, ownershipGuard.ofPlanId(planId))` 中 `isRework=true`、同回料 SKU、有可用量，按**生产日期→批次号**升序 | **有偏差** | ① Java 候选**不限同一计划**，只按归属（代工客户）+ 产品/工序过滤，原型严格限同一 `productionPlanId`；② 排序键 productionDate vs createdAt。见 R5「待 Steve 裁决」。 |
| 回料比例域 0..100，越界抛错 | `WorkflowReworkDeduction:150-160 ratioOf`：越界**视为没配**（不抛错、不夹逼），该条回料不扣 | **有偏差** | 原型报错，Java 静默跳过。见 D7。 |
| 回料分母 = 本次投入 kg（原型 `convertQuantity(input.inputQuantity,...)×ratio/100`） | `ReworkPolicyAutoDeduction:132-140`：分母 = 投入量 − 已手工投入回料；扣出后**把扣出量加回 `inputQuantity`**（`ReworkPolicyAutoDeduction:236-240`）。类注释：投 100kg + 8.5% ⇒ 回料 8.5kg，共 108.5kg | **有偏差** | 原型不扣减手工已投回料，也未见把回料加回投入量。原型对此不完整。 |
| 库存不足不拦报工，只记缺口 | `ReworkPolicyAutoDeduction:223-228` 缺口 warning「未拦报工」 | 一致 | |
| 实际扣减的原子性/并发 | 本类只**生成** `upstreamSources` 引用；真正扣库存在 `consumeReworkInputs`（严格扣减，**未读到**，属工序/半成品线） | 无法判断 | |
| 包材按产出量 × `standardQuantity`（`finished` 才扣；可选包材不扣；单位必须一致） | 本切片未读到 Java 对应段（`FactoryMaterialRequisitionServiceImpl:627` 注释仅提及计划阶段不含续锅放大） | 无法判断 | 不在本切片。 |

## 7. 租户隔离与并发汇总（所有模块）

- 调拨：批次查询、行锁、目标批次建立均带 factory（`TSI:1573-1576`；`MaterialBatchRepository:340-347`）。**例外**：建单/原子 confirm 路径不校验仓库归属（D1）。
- 采购收货：供应商/采购单/收货单/仓库均带 factory；单据行锁完整。
- 小结撤销：全部带 factory；双撤有悲观锁。
- 生产成品入库：有工厂级锁 + 结单行锁。
- 乐观锁冲突（调拨两单争同一批次）如何返回给前端：**未读到**全局异常处理，待核。

## 8. 单位与取整（本切片相关）

- 调拨单位：`TSI:617-626 canonicalTransferUnit` 只对质量/体积归一，计数/包装单位按字面（`TSI:547-576` 注释记录了 LIUSHANMEN 2026-07-30 事故、2026-08-03 撤回）。**原型的 `unitCode` 是另一套**（原型 `fusion-report-contract.mjs`，本切片未展开）。融合时调拨路径必须用 Java 的归一口径。
- 取整：分步收货 `scale()<=4`（`TSI:1486`）；工作流生产收货 `setScale(4, UNNECESSARY)`（`PPS:6322`）；采购收货**未读到四位取整**；盘点两位不在本切片。

---

## 疑似正式后端缺陷（本切片）

| 编号 | 位置 | 触发场景 | 性质 | 建议修法 |
|---|---|---|---|---|
| **D1** | `TSI:305-327 validateCreateWarehouseRoute` + `TSI:1305-1346 confirmTransfer` + `TSI:2371 createTargetInventory`（未见 `FactoryWarehouse` 校验：对 TransferServiceImpl 全文 grep `FactoryWarehouse|assertCanReceive|WarehouseInventoryGuard` 无命中） | 同厂调拨，调入仓 id 为不存在/他厂/已停用/类型为 FINISHED、WIP、SCRAP 的仓，走原子 confirm（非分步）路径 | **缺陷（疑似，需构造复现）**：目标批次直接写到任意仓库 id；分步路径有 `assertWarehouseDeliveryWarehouses`(1585-1597)，原子路径没有。是否有 DB 外键兜底**未读到** | 建单与 confirm 前都调用 `assertWarehouseDeliveryWarehouses` 同款校验（本厂、启用、非同仓），并按物料类别调用 `WarehouseInventoryGuardService.assertCanReceive`。 |
| **D2** | `TSI:2371-2465 createTargetInventory`（全文无 `setExpireDate`；成品分支只设 `productionDate=now`，`TSI:2454`） | 原料/成品从 A 仓调到 B 仓：目标批次**丢失到期日**；多个源批被合成一个目标批次 | **缺陷（高度疑似）**：到期日丢失 → 该批在 FEFO 中 `NULLS LAST` 排最后，过期预警/先到期先出对这批失效；追溯依赖 lineage edge，批次表上没有 source batch 字段。未读到回填逻辑（grep `TRANSFER_IN`：仅 `ProcessTimeLimitService:1069` 读取） | 目标批次按来源批次分别建立并拷贝 `expireDate`、`productionDate`、`supplierId`（或至少按「最早到期」继承）；`sourceBatchId` 写入批次字段。需 Steve 确认「合并为一个目标批次」是否是有意设计（见 R6）。 |
| **D3** | `PSI:2650-2700 createReceiveRecord` 逐行循环、`PSI:4200-4212 validateOverReceiveCap`、`PSI:3057-3067 updateReceiveRecordItems` | 同一采购单行在一张收货单里重复出现：创建会建多行；超收校验逐行比较，累计值被拆开；改数时后者覆盖前者 | **缺陷（疑似）**：未读到去重校验（原型有 `seen` 去重）。只有在开启超收上限时才可能放大，默认 null 不限制 | 创建/改数入口对 `purchaseOrderItemId` 去重，重复直接 400；超收校验按 PO 行合并本单合计量。 |
| **D4** | `PSI:3028-3045`（改数）、`PSI:2756-2779`（确认）未复查采购单状态 | 采购单在收货草稿存在期间被「少收关闭」(`closeReceivingTask`) 或状态变化后，草稿仍可改数、确认入库 | **缺陷（疑似）**：原型每步都校验订单仍处于 `FINANCE_APPROVED/PARTIAL_RECEIVED`。Java 在创建时校验，确认时只校验超收和快照。是否有别处（`closeReceivingTask` 自动作废草稿）兜底**未读到** | 确认与改数前加订单状态校验，状态不对 409。 |
| **D4b** | `PSI:3081` `updateReceiveRecordItems` 的合计金额只含请求里出现的行 | 提交部分行改数（接口允许只传部分行） | **缺陷（小）**：未提交行金额不进合计 | 合计金额按改后的全部行重算。 |
| **D5** | `WarehouseInventoryGuardService.java:73-130 assertCanReceive` + `PSI:4137-4141` + `PPS:8924/9025` | ① 采购原料可收入 SCRAP/TRANSFER/QC/RETURNS/TEMP/LINESIDE 仓及类型为 null 的仓；② 采购收货不传仓时系统兜底默认仓；③ 生产成品入库固定 WH-FG，没有选仓入口 | **口径问题**，待 Steve 裁决（见 R7） | 见 R7。 |
| **D6** | `TSI:1552` 分步收货满额时发布 `TransferConfirmedEvent` 未加 try/catch（原子路径 `TSI:1355-1363` 有） | 凭证监听器抛异常 | **缺陷（小，行为不一致）**：分步最后一次收货的整笔入库会随监听器异常回滚；原子路径吞掉异常。两条路径对「凭证失败是否回滚入库」口径相反 | 统一为同一种处理（建议与原子路径一致：吞掉并记日志，由财务补凭证）。口径需 Steve 拍板。 |
| **D7** | `WorkflowReworkDeduction:150-160 ratioOf` | 工艺配置里回料比例为负或 >100 | **口径问题 / 静默降级风险**：越界当作「没配」，该回料不扣，没有任何告警（原型直接抛错）。违反「不能算就报错」的项目原则倾向 | 越界时在 `ReworkAutoDeduction` 里返回 outcome=INVALID_RATIO 并给 warning，或在保存工艺时就拒绝。 |

### 待 Steve 裁决项

| 编号 | 问题 | 口径 A 的后果 | 口径 B 的后果 | 建议 |
|---|---|---|---|---|
| **R1** | 生产成品入库：少收怎么处理 | **A（原型）**：一次性收货，任何数量差异都必须填原因，一次收完。后果：简单、不会有「半收」状态；但现场分次到仓的实际无法表达 | **B（Java）**：10kg 称重容差内免原因；超容差必须原因+责任侧并进中转挂账；工作流计划允许分次累计收货。后果：贴近现场，但状态更多（PENDING_WAREHOUSE_RECEIPT、中转挂账） | 以 Java 为准（已上线）；原型的「任何差异都要原因」改成「超容差才要原因」。 |
| **R2** | 生产成品入库幂等：用 `Idempotency-Key` 还是内容哈希 | **A（Java 现状，key）**：同 key 换数量静默返回旧结果，前端每次提交必须新 key | **B（原型，哈希）**：同内容重发幂等，同 key 不同内容报错；需在 settlement 上存哈希 | 建议在 Java 现 key 机制上**补一个「同 key 内容不同则 409」**，成本低。 |
| **R3** | 采购收货换算：是否只许沿用采购单快照 | **A（原型）**：只许沿用采购单快照，实际到货用别的包装规格时被拦。后果：口径统一、可追溯；但「实际到货包装与下单不同」（如采购按箱、到货按袋）无法收 | **B（Java）**：同单位沿用 PO 快照；换单位/规格允许，用**当前档案**换算并存在收货行快照里，仅要求基本单位一致。后果：能收，但档案在草稿后被改过时按新档案算 | 建议保留 B，但**确认时按收货行快照入账**（Java 已这么做，`PSI:4064-4075` 用 `item.getInventoryQuantitySnapshot`），并在文档说明「快照在收货行，不在采购行」。 |
| **R4** | 采购超收：默认拦不拦 | **A（原型）**：零容忍，累计 > 采购量即拦。后果：仓管遇多收货必须退货或另下单 | **B（Java 默认）**：`overReceiveRate=null` 默认不限制，工厂可在库存设置里配上限（`PSI:98-123`）。后果：多收货直接入库，库存/应付按实收走 | 以 Java 为准（owner 2026-06 裁定过）；原型默认值改为「按工厂配置，未配不限」。 |
| **R5** | 回料自动扣料能否借用别的计划的批次 | **A（原型）**：只扣同一 `productionPlanId` 的回料。后果：隔离强，不会吃掉别的计划的回料；回料产生计划与使用计划不同时扣不到 | **B（Java）**：按产品+工序+归属（代工客户）选候选，不限同计划。后果：回料可跨计划使用，但可能占用别计划预期要用的回料 | 需 Steve 决定；影响库存可用口径。 |
| **R6** | 同厂调拨目标批次是否按源批次拆分 | **A（原型）**：每源批一个目标批次，继承到期日，批次表可直接追溯。后果：批次数变多 | **B（Java）**：一行一个目标批次，数量合并，依赖 lineage edge 追溯，到期日现状丢失（见 D2）。后果：批次少；追溯与到期预警受损 | 至少要修 D2 的到期日；是否拆批待决。 |
| **R7** | 仓库类型：白名单还是黑名单；不传仓是否兜底；生产成品是否可选仓 | **A（原型）**：采购入库仓白名单；必须选仓；生产收货必须选仓、多仓不猜。后果：严格，需要现场多一步选择 | **B（Java）**：黑名单（只拦 WIP/FINISHED）、不传仓走配置兜底、成品固定 WH-FG。后果：现场少操作，但原料可进 SCRAP/QC 等仓，多成品仓工厂无法选仓 | 建议：采购入库改白名单（加 SCRAP/TRANSFER/QC/RETURNS 拦截）；不传仓兜底保留但在响应里回显所入的仓；成品选仓待多成品仓客户出现再做。 |

## 本切片未读到 / 未覆盖

- `FinishedGoodsFeedServiceImpl.reverseInterimCreate` 之后的退库细节（子批冲销到 REVERSED 后同步冲销 InternalTransfer，`FGF:~430-500`）只读到入口守卫。
- `consumeReworkInputs`（严格扣减回料）、`WipInventoryService.reverseClerkOutput/restoreClerkSemi`、`FinishedGoodsFeedService.restoreForFeed`：属工序/半成品线，未读。
- 采购收货数量列的 DB 精度、`warehouseResolver.resolveFinishedGoodsId` 在无 WH-FG 时的行为、乐观锁异常到前端的映射：未读到。
- 盘点两位取整：不在本切片。
- 原型 `upstreamChanges`（跨计划半成品绑定）：不在本切片，未核对 Java。

---

<!-- section C-purchase -->
# C 切片：采购域规则等价核对（原型 fusion-* vs 正式 Java）

核对日期 2026-11-15，只读。原型取自 `origin/claude/cretas-nextui-prototypes-2d5762:prototypes/ydwm-v1/`，正式后端在 `backend/java/cretas-api/src/main/java/com/cretas/aims/`（下文路径省略此前缀，简写为 `…/`）。
判定词：一致 / 有偏差 / 正式更严 / 缺失 / 无法判断。凡写「未读到」就是没读到代码，不代表没有。
原型文件都是一行很长的压缩代码，「位置」写的是函数或分支名。

---

## 一、受控采购（fusion-controlled-purchase.mjs）

正式端入口：`service/intent/ControlledPurchaseTaskExecutionService.java`（下称 CPTES）、`service/intent/PurchaseTaskGrantService.java`（下称 PTGS）、`service/intent/PurchaseTaskMoneyContract.java`、`service/impl/PreviewTokenServiceImpl.java`、`controller/AiPurchaseTaskGrantController.java`、`service/execution/IntentExecutionOrchestrator.java`。

| 原型规则（文件+位置） | 正式后端方法（文件:行号） | 判定 | 说明 |
|---|---|---|---|
| 确认时 commandDigest 必须等于预览摘要（`handleControlledPurchase` 的 `confirm` 分支：`body.commandDigest!==g.digest`） | `IntentExecutionOrchestrator.java:1150-1181` 入口先用正则校验摘要格式、再 `claimToken(...,commandDigest)`；`PreviewTokenServiceImpl.java:268-293` 重算 `ToolCommandDigest` 与库内值、与客户端传入值做常量时间比较；`:325-340` 认领后再算一遍 | 正式更严 | 摘要由 factoryId+userId+toolName+descriptorVersion+executionMode+参数共同算出，原型只 hash 了 payload+cap。认领后二次复核也是正式独有 |
| expiresAt 必须与预览一致且未过期（`body.expiresAt!==g.expiresAt`；`view()` 超时即 EXPIRED） | `IntentExecutionOrchestrator.java:1150-1155` 只校验客户端传的 expiresAt 在未来；真正的过期判断在 `PreviewTokenServiceImpl.java:244-251`（库内 expiresAt）、`CPTES.java:113`、`:169`、`:288` | 有偏差（安全上不降级） | 客户端 expiresAt 不与库内值比对相等，只当「未过期」看；以服务端库内值为准，所以不能靠篡改它延寿。`authorizeForLater`（`CPTES.java:156-186`）根本不读 proof.expiresAt。口径差异，不是漏洞 |
| expectedVersion 必须等于授权当前版本（`body.expectedVersion!==g.version` 用于 timer/execute；revoke 带版本才比较） | 延迟执行：`CPTES.java:225`、`:243-245`、`:257-259` + `PTGS.java:125`（`grant.getVersion()==expectedVersion`）+ `AiPurchaseTaskGrantController.java` 的 `ExecuteLaterRequest` `@NotNull @Positive`；撤销：`PTGS.java:98` | 一致（延迟执行与撤销）；即时确认路径无客户端版本 | 即时确认走 `executeConfirmed`（`CPTES.java:104-148`）：版本由服务端读授权行自带，防并发靠「令牌 PENDING→EXECUTING 原子认领」（`PreviewTokenServiceImpl.java:277-293`）+ 悲观锁（`CPTES.java:108`）。撤销时 expectedVersion 为 null 会因 `Objects.equals` 失败而被拒（比原型更严，原型 null 则放行） |
| actor 必须与授权人一致（`g.actorId!==w.baseline.identity.userId`） | `CPTES.java:107`、`:402-407` `requireToken`（token.userId==actor.id、factory、tool、intentCode）、`:442-446` `requireGrantBinding`、`:281` 提交前再核 actor | 正式更严 | 另有：procurement:read_write 权限与「可看采购价」双权限（`:355-356`）、工具按厂开关（`:357`）、一线角色须有 AI 办理授权（`:358-362`）、提交前 `beforeCommit` 复核（`:134-146`、`:276-295`）。原型没有这些 |
| 四项同时校验，不能只验 token | 即时确认：digest（`PreviewTokenServiceImpl:268-293`）+ expiry（`CPTES:113`）+ actor（`:107,:402`）+ 版本由授权行绑定（`:125-128`）。延迟执行：digest 在 `:236-240`（定时）与 `:247`（重放），version（`:243,:257`），actor（`:224,:228`），expiry（`:252`） | 一致（实现形态不同） | 没有「只验 token」的路径：`requireToken`→`parameters(token)`（`:411-419`）每次都重算 parametersHash/commandDigest。唯一例外见疑似缺陷 C-1（`claimToken` 的 2 参重载允许不带摘要，但本车道调用点都传了摘要） |
| kg 与 CNY 唯一单位（`material.unit!=='kg'||c.unit!=='kg'||c.currency!=='CNY'`） | `CPTES.java:59-61`（预览）、`:422`、`:426`（执行时再核）、`PTGS.java:174` | 一致 | 正式端预览与执行各校验一遍，并校验行上 unit/quantityUnit/priceUnit 三个都是 kg |
| 物料主数据 unit 必须是 kg（`material.unit!=='kg'`） | 未读到 `CPTES` 校验 `RawMaterialType.unit`；`requireReferences`（`:393-400`）只验租户与启用 | 缺失 | 正式端只要求请求里写 kg，不核对该物料档案基本单位是否 kg。若物料档案是「箱」，会以 kg 建采购草稿；后续建单时 `PurchaseServiceImpl.applySupplierPurchaseContract`（`:4388`）会因包装/单位关系再拦，但那是另一道闸，不是这条车道自己的 |
| 税率 0–100 | `PurchaseTaskMoneyContract.java:88` 仅允许精确 0 或 13 | 正式更严（可能是业务缺口，见 C-2） | 9% 农产品税率被拒。原型放行 0–100 |
| 单价 ≥0（`price<0` 才拒） | `PurchaseTaskMoneyContract.java` `input()`：`signum()<=0` 拒 | 正式更严 | 单价 0 被拒 |
| 数量 >0；原型无精度限制 | 同上：必须能无损放进 NUMERIC(15,4)，超 4 位小数直接拒 | 正式更严 | |
| 含税上限：`gross>cap` 拒，`round(net)`，`tax=round(net*rate/100)`，`gross=net+tax` | `PurchaseTaskMoneyContract.java`：net=round(q×p)，gross=round(net×(1+rate))，tax=gross−net；预览 `CPTES.java:67-70`；执行时再核 `:427-429`；落库回读 `PTGS.java` `verifyPersistedDraft`（落库行与授权逐字段比较） | 一致（结果相同） | net 为 2 位小数时 `round(net+net×r)` 与 `net+round(net×r)` 数学上相同，13% 下两种写法同值。正式端多了落库后回读校验 |
| 预算上限必须有（`cap>0`） | `CPTES.java:67` `setScale(2, UNNECESSARY)` | 有偏差（健壮性） | 上限超过 2 位小数会抛 `ArithmeticException` 而不是 403 业务拒绝，见 C-3 |
| 预览不扣库存、只建 DRAFT 采购单、不提交审批 | `CPTES.java:93-100`（`readOnly` 预览）、`PTGS.java` `executeGrant` 调 `purchaseService.createPurchaseOrder`，`verifyPersistedDraft` 要求 `status==DRAFT` | 一致 | |
| 同一授权只能执行一次，重放返回原结果（`g.state==='DRAFT'&&g.purchaseOrderId` 直接返回） | `PTGS.java` `executeGrant`：已消费时按 `consumedRequestKey + consumedInputDigest` 返回原单，否则拒（`grant has already been consumed`）；`CPTES.java:299-312` `replay`；`consumeIfCurrent` CAS | 一致 | 正式端幂等键是 `preview-<id>` / `deferred:<id>` / `timer:<id>`（`CPTES.java:127-128,262`），不由客户端给 |
| 定时执行：授权后才可排程、执行时间须在有效期内、每笔一次 | `CPTES.java:195-210` `admitTimerBinding`、`:232-241` 定时绑定的 scope/input/command 摘要与过期时间必须未变、`:232-233` 已绑定定时的授权禁止手动消费 | 一致 | 正式端是持久化委托主体，原型是本机进程定时器 |
| 租户隔离 | `CPTES.java:394-398` 供应商、物料均 `findByIdAndFactoryId`；令牌/授权全部带 factory+user 查询（`PTGS.lockedGrant` 的 `findForUpdate(id,factory,user)`） | 一致 | |

---

## 二、请购单（fusion-requisitions.mjs）

正式端：`controller/inventory/PurchaseRequisitionController.java`、`service/inventory/impl/PurchaseRequisitionServiceImpl.java`（下称 PRSI）、`entity/enums/PurchaseRequisitionStatus.java`。

| 原型规则 | 正式后端方法（文件:行号） | 判定 | 说明 |
|---|---|---|---|
| 创建必须有申请原因与请购物料（`!data.reason?.trim()||!requestedItems.length`） | `PRSI.java:52-55` 只判行为空；`dto/inventory/CreatePurchaseRequisitionRequest.java` 的 `reason` 无 `@NotBlank` | 有偏差 | 原因可为空 |
| 每行物料须属本厂、数量>0 且有限、单位非空、建议供应商须属本厂 | `PRSI.java:50-70` 没有逐行校验；`mapItemsToPoItems`（`:256-291`）在转 PO 时才查 materialTypeId 非空、`quantity<=0` 的行**静默跳过** | 缺失（创建时）/有偏差（转 PO 时） | 垃圾数据（别的租户的物料 ID、负数量）能进请购单 JSONB；转 PO 时由 `PurchaseServiceImpl.applySupplierPurchaseContract`（`:4388-4392`）按租户拒（403），不会跨租户成单。但数量 ≤0 的行被悄悄丢掉，转出的 PO 行数少于请购行数，见 C-4 |
| 单位缺省不补（`!i.unit` 拒） | `PRSI.java:271-272` 单位为空时默认写「件」 | 有偏差 | 缺单位退化成「件」，是「不知道→猜一个」。后续换算会因件→kg 无关系而拒，但提示会让人困惑 |
| 提交：仅本人，来自 DRAFT 或 REJECTED | `PRSI.java:107-116`；`PurchaseRequisitionStatus.java:33-42`：DRAFT→PENDING；REJECTED 为终态 | 有偏差 | 正式端 REJECTED 不能再提交，要重建。原型允许改后重提。这是状态机口径不同，待 Steve 裁决（见 C-5） |
| 驳回必须填原因 | `PRSI.java:133-136` | 一致 | |
| 审批/驳回仅 PENDING_APPROVAL | `PRSI.java:118-144` + `transitionStatus`（`:236-244`） | 一致 | 两边都不禁止「请购人自批」；该项已登记在安全待办暂缓清单，不新列 |
| 删除仅本人 DRAFT | `PRSI.java:181-200` | 一致 | 正式端是软删除 |
| 转 PO 幂等：已转单返回已有 PO | `PRSI.java:155-158` 状态为 CONVERTED_TO_PO 且有 convertedPoId 时返回已有单；并发：`PurchaseRequisition` 有 `@Version`（`entity/inventory/PurchaseRequisition.java:130`），第二个事务保存请购单时乐观锁失败、整笔回滚（含其新建的 PO） | 一致（并发下是失败而非返回原单） | 串行重放等价；真并发时第二次请求拿到乐观锁错误（非 200 重放）。没有重复建 PO，所以幂等性成立 |
| 转 PO 的供应商必须属本厂 | `PRSI.java:149-152` 只判非空；`PurchaseServiceImpl.java:~396-399` `requireActiveSupplier`（`:4363` 附近）按 `findByIdAndFactoryId` 校验且要求启用 | 一致 | 租户闸在 PO 创建里 |
| 转 PO 时空价保持 null（原型 `unitPrice:null`，注释「未配置采购价保持空值，转单后必须核对」） | `PRSI.java:280` 抄请购行 `unitPrice`；`PurchaseServiceImpl.resolvePurchaseUnitPrice`（`:4489-4529`）在行价为空时按「规格报价→供应关系价→物料参考价」**自动补价**，全无才保持 null | 有偏差（正式端更主动） | 不会退化成 0（`positivePriceOrNull`，`:4568`）。但会自动采用供应关系价，其中「基准未知」的价格也会被采用，见 C-6 |
| 转 PO 时空税保持 null | `PurchaseServiceImpl.applyPurchaseTaxContract`（`:4572-4590`）：行税率空→免税物料取 0，有物料税率取之，否则**抛 422「物料未配置采购税率，不能静默按0%下单」** | 有偏差（正式端更严） | 转 PO 在物料没配税率时整单失败，并非保持 null 让人在草稿里补。显式是为了「不静默按 0%」，方向对，但对请购转单这条路是阻断，见 C-7（待 Steve 裁决） |
| 转 PO 后请购单记录 convertedPoId、状态 CONVERTED_TO_PO | `PRSI.java:159-174` | 一致 | |
| 60 秒内同买手同供应商同内容重复建 DRAFT 单 → 409 | `PurchaseServiceImpl.java:412` 起 | 正式独有 | 连续转两张物料+数量相同的请购单给同一供应商，60 秒内第二张会 409，见 C-8（口径问题） |
| 租户隔离 | `PRSI.java:226-234` `findById` 后比对 factoryId，不匹配返回 403（不是 404） | 有偏差（轻微） | 先按主键裸查再比租户，跨租户 ID 能探测到「存在」（403 vs 404）。列表查询均带 factoryId（`:210-221`） |

---

## 三、销售转采购（fusion-sales-purchase.mjs）

正式端：`controller/inventory/PurchaseController.java:785-815`（单张/多张建议）、`PurchaseServiceImpl.java:3265-3560`（建议计算）、`:372-470`（建单）。

| 原型规则 | 正式后端方法（文件:行号） | 判定 | 说明 |
|---|---|---|---|
| 1–50 张不同销售单，重复/越界拒 | `PurchaseServiceImpl.java:3328-3355`：`distinct` 去重（不报错）；`dto/inventory/MultiSoPurchaseSuggestionRequest` 只有 `@NotEmpty` | 有偏差 | 无 50 张上限；重复 ID 静默去重而非拒绝 |
| 销售单须属本厂，否则 403 | `PurchaseServiceImpl.java:3270-3274`、`:3359-3363` `findById` 后比对 factoryId，403 | 一致 | 先裸查再比租户，同请购单的 403/404 探测问题 |
| 已关闭/取消/已完成/已发货的销售单拒绝（409） | 未读到状态判断（`generatePurchaseSuggestionMulti` 全程无 `so.getStatus()`） | 缺失 | 已取消的销售单也能生成采购建议，见 C-9 |
| BOM 展开：当前 ACTIVE+current 配方，按产品行数量×单位产出展开，辅料取克/千克 | `PurchaseServiceImpl.java:3429-3475` `expandSoItemsInto`，取 `bomRecipeItemRepository.findCurrentByProduct`（`repository/bom/BomRecipeItemRepository.java:34-44`，要求 `isCurrent=true AND status=ACTIVE AND deletedAt IS NULL`）；用量 = `actualQuantity × 销售行数量`（`:3458-3459`），销售行数量先经 `resolveBomProductQuantity`（`:3483-3497`）归一到产品基本单位 | 有偏差 | (1) 正式端只展开 `bom_recipe_items`，**不读 `BomSeasoningItem` 调味辅料**（整个 `PurchaseServiceImpl` 没有 `Seasoning` 字样），原型会按 `dosagePerKgG×原料总kg` 加入辅料并排除 `countInSeasoning=false`；(2) 原型用 `outputQuantityPerUnit` 折算，正式用「每单位产品用量」，两种基数是否等价**无法判断**（需对同一配方跑数）。见 C-10 |
| 排除自供/副产物料（`master.selfSupplied||master.isByproduct`） | `:3446-3447` 排除类别为 BYPRODUCT 的配方行；`RawMaterialType` 有 `selfSupplied`（`entity/RawMaterialType.java:228`），但 `PurchaseServiceImpl` 未读到 `selfSupplied` 判断 | 有偏差 | 自供物料会被建议去采购，见 C-10 |
| 库存口径：只算 AVAILABLE、质检合格、非客户自有、未过期，且同单位 | `repository/MaterialBatchRepository.java:707-712` `sumAvailableQuantityByMaterialType`：`status='AVAILABLE'`、`ownership` 为空或 COMPANY_OWNED、扣 `reservedQuantity`、可用量>0 | 有偏差（一部分更严一部分更松） | 排除客户自有：一致；扣预留量：正式更严（原型不扣）；过期：靠 `autoCheckAndUpdateExpiredBatches` 每天 02:00 把过期批次翻成 EXPIRED（`service/impl/MaterialBatchServiceImpl.java:1779-1796`），查询本身不看 `expireDate`，所以「过期当天 02:00 前」仍计入；质检：质检中批次状态为 INSPECTING 被排除，但查询不看单独的质检结论字段；**单位不看**：直接对数量求和，批次单位与物料单位不同则混加，见 C-11；`status='AVAILABLE'` 把 IN_STOCK/FRESH/FROZEN 排除，而同文件 `:689-695` 的库存汇总用的是 `NOT IN(…)`，同一个「可用库存」在仓库里有两套口径，见 C-11 |
| 净需求 = max(需求−库存, 0)，多单合并后统一扣一次库存 | `:3380-3394`、`:3544-3548` | 一致 | |
| 预览指纹（销售单/BOM/物料/库存的 hash）；确认时指纹变化 → 409；预览 5 分钟过期；同一预览不能用于另一份内容 | 未读到。`generatePurchaseSuggestionMulti` 只读不落库；建单走普通 `createPurchaseOrder`（`:372`），请求体里没有预览 ID，也没有指纹比对 | 缺失 | 正式端没有「预览→确认」的绑定，销售单、BOM、库存在预览之后变了照样能建单，见 C-12 |
| 建单可带多来源销售单（`sourceSalesOrderIds`、`sourceBomReferences`） | `PurchaseServiceImpl.java:445` 只存单个 `salesOrderId`；全库搜 `sourceSalesOrderIds` 只在 FinishedGoodsBatch/MaterialBatch/CustomerPriceHistory 等与采购单无关的实体 | 缺失 | 合并多张销售单生成的采购单，来源关系不可追溯 |
| 采购行必须来自当前预览、不能重复/混入未选物料 | 未读到 | 缺失 | 同上，无预览绑定 |
| 销售单 ID 须属本厂（建单时） | `PurchaseServiceImpl.java:445`、`:1992` 直接 `setSalesOrderId`，未读到归属校验；`hydrateSalesOrderNumber`（`:561-570`）用 `salesOrderRepository.findById` 不带租户 | 缺失 | 见 C-13 |
| 缺单价/缺税率的行保持 null、不算金额 | `PurchaseOrderItem.getLineAmount()`（`entity/inventory/PurchaseOrderItem.java:161-167`）单价空返回 null；`PurchaseServiceImpl.java:~465-480` 建单时 `lineAmount==null` 不累加 | 一致 | 但税率为空在正式端建单时会被 `applyPurchaseTaxContract` 拒（见请购单一节） |
| 单位换算失败即拒绝 | `PurchaseServiceImpl.java:3575-3593` `conversionFactor`：换算失败抛 400「单位无法换算」；服务不可用抛 503 | 一致 | |
| 并发/幂等：预览有 `createdOrderId` 防重 | 仅有 60 秒内同买手同供应商同行内容的重复建 DRAFT 闸（`:412` 起） | 有偏差 | 防双击有，但不是预览维度的幂等；快速点两次「合并采购」不同内容会成两张单 |

---

## 四、供应商关系 / 采购规格 / 采购单行单位（fusion-supplier-relations.mjs）

正式端：`service/supplier/SupplierMaterialServiceImpl.java`、`SupplierMaterialPurchaseSpecServiceImpl.java`、`SupplierTaxInclusivePricing.java`、`entity/enums/TaxRate.java`、`PurchaseServiceImpl.applySupplierPurchaseContract`（`:4388`）。

| 原型规则 | 正式后端方法（文件:行号） | 判定 | 说明 |
|---|---|---|---|
| 含税折算：免税→未税=含税；TAX_13 → ÷1.13；TAX_9 → ÷1.09；缺税率 → null；取 4 位 | `SupplierTaxInclusivePricing.java:45-60`：免税返回含税；税率空返回 null；否则 `TaxRate.preTaxPrice`（`entity/enums/TaxRate.java:53-58`，scale 4、HALF_UP） | 一致 | 三档与缺税率保持 null 都对；null 时调用方「不动既有未税值」（`SupplierMaterialServiceImpl.java:171-175`；规格侧 `SupplierMaterialPurchaseSpecServiceImpl` 的 `apply` 同） |
| 含税价必须 >0 | 关系：`SupplierMaterialServiceImpl.java:164-169`；规格：`SupplierMaterialPurchaseSpecRequest` 的 `@Positive`（含税、未税均有） | 一致 | |
| 新建关系：同物料已有关系则 409；必填采购单位且币种 CNY | `SupplierMaterialServiceImpl.java:67-69`；币种固定 CNY（`:195`）；采购单位缺省取物料基本单位（`:196-198`） | 有偏差（轻微） | 原型要求显式填采购单位，正式端缺省取物料单位 |
| 新建关系必须有默认采购价 | `SupplierMaterialServiceImpl.java:147-158`：含税或未税任一给出即可，仅 create | 正式独有 | 原型未强制 |
| 修改关系：价格 null=不修改；单位变更须重新确认价 | `SupplierMaterialServiceImpl.java:188-233`；单位变更且未重新给价 → 400 `SUPPLIER_MATERIAL_PRICE_UNIT_RECONFIRM_REQUIRED` | 正式更严 | 注意第 225-233 行的判断只认 `request.getDefaultPurchasePrice()`，不认 `getTaxIncludedDefaultPurchasePrice()`（界面实际填含税）：只改单位并同时给含税价也会被拒。口径小缺陷，见 C-14 |
| 单位换算失败即拒绝 | 关系：采购单位与物料单位同维度才算兼容，否则要求配规格（`SupplierMaterialServiceImpl.java:219-224`、`:291-304`）；采购单行：`PurchaseServiceImpl.conversionFactor`（`:3575-3593`）失败 400；规格：库存基本单位必须等于物料单位（`SupplierMaterialPurchaseSpecServiceImpl` 的 `apply`） | 一致 | 正式端换算走统一的 `unitContractService`，不靠原型里那张写死的单位表（原型只认 kg/g/jin/t、l/ml、km/m/cm/mm，且「斤」按 500g） |
| 规格：名称、包装单位、正换算系数必填 | `SupplierMaterialPurchaseSpecRequest` 的 `@NotBlank`/`@DecimalMin` + `apply` 内校验 | 一致 | |
| 规格默认唯一 | `SupplierMaterialPurchaseSpecServiceImpl` 的 `enforceDefault` | 一致 | 原型停用后自动补一个默认，正式端未读到该补位 |
| 规格 quotedPrice 传 null | 规格侧 `spec.setQuotedPrice(request.getQuotedPrice())` 无条件赋值，null 即**清空**；关系侧 null 是「不修改」 | 有偏差（内部不一致） | 代码注释已声明这是 #457 登记的独立契约问题。同一概念两个语义，见 C-15 |
| 采购单行：数量单位须等于规格包装单位；计价单位须一致 | `PurchaseServiceImpl.java:4440-4466`、`:4424-4430` | 一致 | |
| 采购单行：库存折算快照（库存基本单位、换算系数、库存数量）随单保存 | `:4402-4412`、`:4466-4471` 写入 `item.set*Snapshot` | 一致 | 收货沿用快照这条在 `validatePersistedReceiptSelection`（`:4357` 附近）可见，属收货切片，本文不展开 |
| 计价单位≠数量单位时，单价换算（`priceFactor`） | `convertAuthoritativePrice`（`:4531-4553`）、`convertPricePerUnit` | 一致 | |
| 采购单价必须明确且 ≥0（`input.unitPrice==null` 拒） | `resolvePurchaseUnitPrice`（`:4489`）：显式价 ≤0 → 400，`null` 允许并按主数据补，补不到保持 null；提交审批时 `validateOrderLinesBeforeApproval`（`:1221-1240`）要求 >0，并在提交前先回解空价（`:1118-1160`） | 有偏差（正式更宽松、但提交闸更严） | 草稿允许空价，提交才拒。原型在保存草稿时就要求有价。不是退化成 0 |
| 「基准未知」的价格不参与自动带价 | `SupplierTaxInclusivePricing.hasUnknownBasis`（`:71`）有定义，**全仓只有定义没有调用**；`resolvePurchaseUnitPrice`（`:4489`）与 `resolveEffectivePrice`（`SupplierMaterialServiceImpl.java:306`）都直接用 `relation.getDefaultPurchasePrice()` | 缺失 | 注释写明「编排 2026-09-01 裁定：基准未知的价格不参与自动带价」，代码没接，原型有（`decorate` 里 `known` 判断，缺含税且非显式未税 → `MISSING`）。见 C-6 |
| 仅 DRAFT 采购单可改规格 | `updateDraftOrder`（`PurchaseServiceImpl.java:1925-1965`）`status!=DRAFT` → 409，且加行锁 | 一致 | |
| 草稿换供应商 | `PurchaseServiceImpl.java:1960-1975`：换供应商且不带明细时，把旧行的单价**写成 `BigDecimal.ZERO`** 并清规格 | 有偏差 | 违反「空价不退化为 0」，且该分支不重算 `totalAmount/taxAmount`（`:1995` 起只在带明细时重算），草稿头金额会与行不一致。提交时 `refreshMissingLinePricesFromMasterData`（`:1131`）把 ≤0 当空回解并重算头金额，所以不会带 0 价进审批。见 C-16 |
| 租户隔离 | 关系/规格全部 `findByIdAndFactoryId` + 供应商/物料归属校验（`SupplierMaterialServiceImpl.java:252-271`、规格服务 `ownedRelation/ownedSpec`） | 一致 | |
| 并发 | 关系与规格带 `version` 乐观锁比对；规格写入前对供应关系 `PESSIMISTIC_WRITE`、对物料加 `materialReferenceWriteGuard` 锁 | 正式更严 | |

---

## 五、报价单（fusion-quotes.mjs）

正式端：`controller/sales/OperationalQuoteController.java`、`service/sales/impl/OperationalQuoteServiceImpl.java`（下称 OQSI）、`entity/sales/OperationalQuote.java`、`repository/sales/OperationalQuoteRepository.java`。

| 原型规则 | 正式后端方法（文件:行号） | 判定 | 说明 |
|---|---|---|---|
| 创建：客户须属本厂、产品须属当前工厂、录价人姓名必填 | `OperationalQuoteController.java:55-80` 只判 customerId、productTypeId 非空；`OQSI.java:53-86` 不查客户/产品是否存在或属本厂；`quotedByName` 未要求必填 | 缺失 | 任意字符串都能存为 customerId/productTypeId，见 C-17 |
| 录价人 ID 须是本厂有效用户 | 未读到 | 缺失 | |
| 录价：只能在 PENDING_QUOTE | `OQSI.java:98-100` | 一致 | 抛 `IllegalStateException`；它被映射成什么 HTTP 状态码**未读到** |
| 录价：unitPrice 有限且 >0 | `OperationalQuoteController.java:99-107` 直接 `new BigDecimal(...)`，`OQSI.java:90-120` 无正数判断；`unitPrice` 为 null 也放行进入 PENDING_APPROVAL | 缺失 | 空价、0、负价的报价能提交审批，见 C-18 |
| 报价单位必须与所选产品单位一致（`data.unit!==old.unit`，`old.unit` 取自产品） | `OQSI.java:103` 直接 `quote.setUnit(unit)`；创建时也未取产品单位 | 缺失 | 单位一致性完全没有校验，见 C-18 |
| quoteType 只能 FIXED/NEGOTIABLE | 未读到白名单（`controller:validateSubmitPriceLengths` 只限长度） | 缺失 | |
| costPrice、minOrderQty 不得为负 | 未读到 | 缺失 | |
| validUntil 格式 YYYY-MM-DD | `controller:106` `LocalDate.parse` | 一致 | 格式错误抛的异常如何转 HTTP 状态未读到 |
| 毛利率 = (单价−成本)/单价，成本>0 才算，否则 null | `OQSI.java:108-112`：成本>0 才算 `(p−c)/p`，scale 6；**成本为空/0 时不清空** `marginRate` | 有偏差 | 公式一致。但修订后重新录价、成本留空时，上一轮的毛利率会留在单子上（`reviseQuote` 也不清 `marginRate`/`unitPrice`/`costPrice`）。数据库列 `margin_rate` 是 scale 4（`entity/sales/OperationalQuote.java:114`），代码算 6 位、落库时被截成 4 位。见 C-19 |
| 单价精度 | 实体 `unit_price` 为 `NUMERIC(15,2)`（`OperationalQuote.java:96`） | 有偏差（轻微） | 报价单价 0.125 元/kg 这类 3 位小数会被舍到 2 位，毛利率却按原值算，二者对不上 |
| 审批/驳回：仅 PENDING_APPROVAL；驳回必须有原因 | `OQSI.java:127-129`、`:148-150`；驳回原因无非空校验（`OperationalQuoteController.java:150-158`） | 有偏差 | 驳回原因可空 |
| 审批人身份 | `OperationalQuoteController.java:127-131`：`approverName` 取自请求体，`approverUserId` 取自登录态 | 有偏差 | 审批人姓名可被客户端随便写，与 `approverUserId` 可能不一致，见 C-20。原型取登录身份 |
| 修订：仅已通过/已驳回，必须填原因；修订次数+1；历史快照保存旧单价/单位/状态 | `OQSI.java:163-183`：状态判断一致；**原因不必填**；`revisionCount+1`；历史只以文本追加到 `remarks`（`[修订 vN] 原因`），**不保存旧单价/旧单位/旧状态** | 有偏差 | 修订后重新录价会覆盖 `unitPrice`，上一版价格丢失。未读到其他历史表（`OperationalQuote` 实体无历史字段）。见 C-21 |
| 修订时清空审批字段 | `OQSI.java:170-175` | 一致 | |
| 有效报价：APPROVED + 未过期 + 同客户同产品 | `OperationalQuoteRepository.java:46-52` | 一致 | 同一客户同产品有多条有效报价时按审批时间倒序全返，如何选一条由调用方决定 |
| 租户隔离 | `getQuote`：`findByIdAndFactoryIdAndDeletedAtIsNull`（`OQSI.java:187`）；列表同样带 factoryId | 一致 | |
| 并发 | 实体无 `@Version`，`OQSI` 无锁 | 缺失 | 两个人同时审批/驳回同一张 PENDING_APPROVAL 报价，两边都能通过状态判断，后写覆盖前写，见 C-22 |

---

## 疑似正式后端缺陷与口径问题

编号只在本文内使用。「缺陷」= 代码行为与已写明的意图或不变量冲突；「口径」= 两种做法都说得通，需 Steve 定。

**C-1 受控采购 claimToken 的两参重载允许不带摘要（低，防御性）**
- 位置：`service/impl/PreviewTokenServiceImpl.java:208-211`（2 参重载）与 `:219-241`（`proofCommandDigest==null` 时跳过 `:274-281` 摘要比对）。
- 触发：目前受控采购两个调用点都传摘要（`CPTES.java:162`、`IntentExecutionOrchestrator.java:1174`），本车道今天不会触发；别的调用方若走 2 参重载就等于只验 token。
- 判定：缺陷（潜在，未触发）。
- 建议：受控采购工具（`PurchaseTaskGrantService.CANONICAL_TOOL`）在认领时强制要求摘要非空，或删除 2 参重载。

**C-2 受控采购不支持 9% 税率（口径，待 Steve 裁决）**
- 位置：`service/intent/PurchaseTaskMoneyContract.java:88`，只放行精确 0 或 13。
- 触发：9% 农产品物料（物料档案 `TaxRate.TAX_9` 在系统里是合法税率）走 AI 受控采购一律被拒。
- 口径一：保持只放 0/13（更严，AI 只处理最简单的两档），后果是农产品物料的 AI 下单永远拒绝，用户转手工建单。口径二：放开 9%，后果是含税金额合约要再测（`gross=round(net×1.09)`）与落库回读 `verifyPersistedDraft` 的 taxRate 比较需要一并改。
- 建议：先问 Steve 是否让 AI 采购覆盖农产品。若放开，改 `PurchaseTaskMoneyContract` 的税率白名单与 `PurchaseTaskGrantService.validateMoneyScope`，并加 9% 用例。

**C-3 受控采购预算上限超过 2 位小数抛未处理异常（低，健壮性）**
- 位置：`CPTES.java:67` `decimal(input,"amountCap").setScale(2, RoundingMode.UNNECESSARY)`。
- 触发：`amountCap` 传 `100.123` → `ArithmeticException`，不是 403 业务拒绝，调用方看到 500。
- 判定：缺陷（体验与审计：拒绝没有走 `PurchaseGrantRefusalAuditService`）。
- 建议：捕获并转成 `denied("预算上限最多两位小数")`。

**C-4 请购转 PO 静默丢弃数量 ≤0 的行（中）**
- 位置：`PRSI.java:269-270`（`continue`）。
- 触发：请购行数量为 0 或负（创建时无校验，`PRSI.java:50-70`）。转出的 PO 比请购单少几行，请购单却标记 CONVERTED_TO_PO，请购需求被悄悄吞掉。
- 判定：缺陷。
- 建议：创建请购单时校验逐行物料属本厂、数量 >0、单位非空；转 PO 时遇到非法行直接 400，不再 `continue`。

**C-5 请购单被驳回后不能重新提交（口径，待 Steve 裁决）**
- 位置：`entity/enums/PurchaseRequisitionStatus.java:33-42`（REJECTED 终态）。
- 触发：驳回后请购人想改量重提。
- 口径一（正式现状）：REJECTED 终态，必须新建，历史清楚、避免「提交+驳回」反复造审批记录（`PRSI.java:185` 注释有同类考虑）。口径二（原型）：REJECTED→DRAFT/提交，请购人少填一遍，但同一张单的审批历史会混杂多轮。
- 建议：问 Steve。若采用原型口径，要同时加审批历史，避免丢轮次。

**C-6 「基准未知」的供应关系价被自动带入采购单（中，已书面裁决但没接线）**
- 位置：`SupplierTaxInclusivePricing.java:71` 的 `hasUnknownBasis` 全仓无调用；`PurchaseServiceImpl.java:4489-4529` 与 `SupplierMaterialServiceImpl.java:306-314` 直接用 `relation.getDefaultPurchasePrice()`。
- 触发：存量关系「未税有值、含税为空」（注释写 LIUSHANMEN 有 2 行）。请购转 PO、AI 建单、手工建单都会拿这个价。
- 判定：缺陷（文档说不该用，代码在用；与「无法解释的数不能给采购单定价」冲突）。
- 建议：在 `resolvePurchaseUnitPrice`、`resolveEffectivePrice` 里关系价与规格价前先判 `hasUnknownBasis`，是则当缺价处理，并补一条测试让「未税有值含税空」的关系不自动带价。

**C-7 请购转 PO 因物料没配税率而整单失败（口径，待 Steve 裁决）**
- 位置：`PurchaseServiceImpl.applyPurchaseTaxContract`（`:4572-4590`）→ 422 `PURCHASE_TAX_RATE_REQUIRED`。
- 触发：请购行不带税率（界面本就没有该字段），物料档案既非免税也没配税率。
- 口径一（正式现状）：拦下，逼人先去物料档案配税率，永不「静默按 0%」。后果：请购批准后转 PO 这一步被卡住，且提示在采购侧而非物料档案侧。口径二（原型）：税率保持 null，草稿里补，提交审批时再拦。后果：草稿金额含税合计算不出（正式端 `getLineAmountWithTax` 对空税率按 0 处理，`PurchaseOrderItem.java:176`），需要在提交闸补一道「税率必填」才不漏。
- 建议：问 Steve；倾向保持口径一，但把 422 的提示指向物料档案并显式写出物料名。

**C-8 60 秒重复建单闸会误拦请购转单（口径）**
- 位置：`PurchaseServiceImpl.java:412` 起，内容维度只取物料+数量。
- 触发：同一买手 60 秒内把两张「同物料同数量」的请购单转给同一供应商，第二张 409。
- 口径一：保持（防双击更重要）。口径二：转单带来源请购单 ID，作为内容维度之一。
- 建议：口径二更准，成本小。

**C-9 销售转采购不校验销售单状态（中）**
- 位置：`PurchaseServiceImpl.java:3265-3326`、`:3328-3425`（全程未读 `so.getStatus()`）。
- 触发：已取消、已关闭、已完成的销售单照样展开 BOM 出采购建议。
- 判定：缺陷（与原型 409 规则不一致，也会让人为取消的单子下采购）。
- 建议：在 `findById` 后按状态集合 `CANCELLED/CLOSED/SHORT_CLOSED/COMPLETED/DELIVERED` 拒绝（先确认正式端销售单状态枚举的真实取值）。

**C-10 采购建议漏掉调味辅料、未排除自供物料（中，口径需先对数）**
- 位置：`PurchaseServiceImpl.java:3429-3475`。
- 触发：配方有调味辅料（`bom_seasoning_items`）时，原型会按 `dosagePerKgG×原料总kg` 计入采购需求，正式端不计；`selfSupplied=true` 的物料仍被建议采购。
- 判定：辅料遗漏与自供未排除：缺陷（以现有 BOM 设计看，辅料是配方的一部分）；配方基数是否等价：无法判断。
- 建议：用同一个产品+订单量，在原型与正式端各跑一次，对比 `requiredQuantity`；差异来源确认后再改。

**C-11 采购建议的库存扣减口径（中）**
- 位置：`repository/MaterialBatchRepository.java:707-712`（只认 `status='AVAILABLE'`、不看单位、不看过期日期）；对比 `:689-695` 的库存汇总用 `NOT IN (DEPLETED,USED_UP,EXPIRED,SCRAPPED,DEFECTIVE)`。
- 触发：(a) 批次状态是 `IN_STOCK/FRESH/FROZEN/RESERVED` 时不计入「现有库存」，采购建议会多买；(b) 批次 `quantityUnit` 与物料单位不同时数量直接相加，单位混加；(c) 过期日已过但 02:00 定时任务未跑前仍计入。
- 判定：(a)(b) 缺陷；(c) 口径问题（用查询时刻还是用状态）。(a) 需先查线上这三类状态是否真有存量，**本次未查库**。
- 建议：库存可用口径抽成一份并让两处共用；单位不同则换算或排除；过期用 `expireDate >= 业务日` 兜底。

**C-12 合并采购没有预览-确认绑定（中，红线域）**
- 位置：`PurchaseController.java:803-815` 只读建议；建单 `PurchaseServiceImpl.java:372`。
- 触发：预览后销售单、BOM、库存变化，照样用旧预览内容建单；采购行可混入不在预览里的物料；快速重复提交不同内容会成多张。
- 判定：缺陷（相对原型 409 规则缺失）。是否需要补由 Steve 定：这是原型为 AI/批量场景加的闸，普通手工建单未必需要。
- 建议：若补，建议做成可选的 `previewId`+指纹，服务端存预览（带 5 分钟过期），建单时比对；采购单增加来源销售单集合字段。

**C-13 建单不校验销售单归属且按 ID 裸查（低-中，租户）**
- 位置：`PurchaseServiceImpl.java:445`、`:1992`（`setSalesOrderId`，无归属校验）；`:561-570`、`:575-590` `salesOrderRepository.findById` / `findAllById` 不带 factoryId。
- 触发：请求体 `salesOrderId` 填别的租户的销售单 ID（需知道 UUID），采购单落库，列表/详情上 `salesOrderNumber` 会把对方的销售单号读出来。
- 判定：缺陷（跨租户信息泄露，利用门槛高）。
- 建议：建单与更新时用带 factoryId 的查询校验；`hydrate*` 一律带 factoryId。

**C-14 修改采购单位时「重新确认价格」只认未税字段（低）**
- 位置：`SupplierMaterialServiceImpl.java:225-233`，条件含 `request.getDefaultPurchasePrice()==null`。
- 触发：界面填的是含税价（`taxIncludedDefaultPurchasePrice`），改单位同时给了含税新价，仍被判「未重新确认」被拒。
- 判定：缺陷。
- 建议：条件改为含税、未税任一给出即视为已确认。

**C-15 规格与关系对「价格传 null」语义相反（低，代码已登记）**
- 位置：`SupplierMaterialPurchaseSpecServiceImpl` 的 `apply`（`spec.setQuotedPrice(request.getQuotedPrice())`）对比 `SupplierMaterialServiceImpl.java:188-190`。
- 触发：更新规格时不带 quotedPrice，会把已有报价清空；更新关系不带价则保持。
- 判定：口径问题（注释称 #457 登记，另一条线）。待 Steve 裁决是否统一为「null=不修改」。

**C-16 草稿换供应商把行价写成 0 且不重算头金额（低）**
- 位置：`PurchaseServiceImpl.java:1960-1975`（`existingItem.setUnitPrice(BigDecimal.ZERO)`）。
- 触发：草稿采购单只改供应商、不带明细。
- 判定：缺陷（违反「空价不退化为 0」；草稿 `totalAmount/taxAmount` 与行不一致）。提交审批时有兜底（`:1131-1160`、`:1230`）。
- 建议：改为 `setUnitPrice(null)` 并清三个金额快照与头金额。

**C-17 报价单创建不校验客户与产品归属（中，租户）**
- 位置：`OperationalQuoteController.java:55-80`、`OQSI.java:53-86`。
- 触发：customerId/productTypeId 填任意值或别的租户的 ID，报价单落库；`quotedByUserId` 同样未验。
- 判定：缺陷。
- 建议：服务层用 `findByIdAndFactoryId` 校验客户、产品、录价人；顺带要求 `quotedByName` 非空。

**C-18 录价不校验价格与单位（高，账务相关）**
- 位置：`OQSI.java:90-120`、`OperationalQuoteController.java:99-107`。
- 触发：`unitPrice` 为空、0、负数都能进入待审批；报价单位可与产品单位不同（如产品 kg，报价写「箱」），审批通过后下游销售下单（`findActiveForOrder`）会拿一个单位不对的价。
- 判定：缺陷。
- 建议：录价时要求 `unitPrice>0`、`costPrice/minOrderQty≥0`、`quoteType∈{FIXED,NEGOTIABLE}`、`unit` 与产品档案单位一致（需先确认正式端报价单位的业务口径是否一定等于产品单位）。

**C-19 修订后重录价，毛利率可能是上一轮的（中，账务相关）**
- 位置：`OQSI.java:108-112`（成本空/0 时不写 `marginRate`）与 `:163-183`（`reviseQuote` 不清 `unitPrice/costPrice/marginRate`）；列精度 `OperationalQuote.java:96,114`。
- 触发：报价被修订，销售运营重录价但没填成本，页面仍显示上一轮毛利率；单价被存成 2 位小数而毛利率按未舍入的价算。
- 判定：缺陷。
- 建议：每次录价都显式写 `marginRate`（算不出写 null）；毛利率用已舍入后的单价算；`margin_rate` 的 scale 与计算 scale 对齐。

**C-20 审批人姓名取自请求体（低-中，审计）**
- 位置：`OperationalQuoteController.java:127-131`、`:143-158`。
- 触发：以 A 账号审批，body 写 `approverName:"B"`，记录里 `approverUserId=A`、`approverName=B`。
- 判定：缺陷（审计不可信）。
- 建议：姓名由服务端按 `userId` 取，忽略请求体。

**C-21 报价修订不保留历史价（中）**
- 位置：`OQSI.java:163-183`（只改 `revisionCount` 和追加 `remarks` 文本）。
- 触发：修订后重录价，旧单价/旧单位/旧状态丢失，只剩一句「[修订 v1] 原因」。
- 判定：缺陷（与原型 `history[]` 快照不一致；未读到其他历史表，若别处有审计表请指出）。
- 建议：修订时写一条快照（旧 unitPrice/unit/costPrice/marginRate/状态/时间/操作人），或建报价版本表。

**C-22 报价审批无并发控制（低-中）**
- 位置：`entity/sales/OperationalQuote.java` 无 `@Version`；`OQSI.java:126-159` 先读后判后写。
- 触发：两人同时对同一待审批报价点「通过」和「驳回」，两边都过状态判断，后写覆盖前写，审批人字段与最终状态可能互相矛盾。
- 判定：缺陷。
- 建议：给报价单加 `@Version` 或用 `findForUpdate`。

---

## 汇总

- 受控采购：核心四项校验正式端全部成立且更严，没有降成只验 token 的路径（只有 C-1 的两参重载是潜在口子）；仅 C-2（9% 税率）待裁决，C-3 为健壮性。
- 请购单：转 PO 幂等成立（靠状态判断 + 乐观锁）；缺创建时校验（C-4），驳回后不可重提（C-5）、缺税率整单失败（C-7）待裁决。
- 销售转采购：路由与基础展开在，**规则不等价**：无状态校验（C-9）、无调味辅料/自供排除（C-10）、库存口径三处问题（C-11）、无预览指纹 409（C-12）、多来源销售单不可追溯、建单销售单归属不校验（C-13）。
- 供应商关系：含税折算三档与缺税率 null 一致；单位换算失败拒绝一致；「基准未知价不自动带」没接线（C-6）。
- 报价：状态机与有效报价查询一致；价格/单位/归属/并发/历史多处缺失（C-17 至 C-22）。

未读到或未验证：`IllegalStateException` 到 HTTP 状态码的映射；线上库存批次状态分布；原型 `outputQuantityPerUnit` 与正式 `actualQuantity` 的数值等价性；PO 提交审批后其余状态流转（不在本切片）。

---

<!-- section D-return-delivery-masterdata -->
# D. 退货 / 销售发货 / 主数据：原型规则 vs 正式 Java 后端逐条核对

核对对象：origin/claude/cretas-nextui-prototypes-2d5762 上的 `prototypes/ydwm-v1/fusion-return-orders.mjs`、`fusion-sales-delivery.mjs`、`fusion-masterdata.mjs`。
正式后端读取基线：worktree HEAD `d6dc07cbaa`，Java 根 `backend/java/cretas-api/src/main/java/com/cretas/aims/`（下文路径省略此前缀，写作 `service/...`）。
全程只读，未改任何代码，未写任何数据。

判定词：一致 / 有偏差 / 正式更严 / 缺失 / 无法判断（后者必写"未读到"什么）。

## 0. 先给结论

1. 退货：「累计量排除已驳回单据」两边一致，但正式后端的累计量**只统计有货退货（withGoods=true）**，无货退款单完全不占也不受容量约束；原型对所有退货都校验。「退货单价必须等于来源价」正式后端**没有**（销售、采购两侧都没有）。
2. 退货记账：正式后端比原型严得多（财务审批门、挂起调整、凭证作废、期间开放检查），原型的 `NOT_POSTED` 只是占位，**不得建议把正式改弱**。
3. 发货：库存扣减、先到期先出、整单回滚、应收按实发挂账，正式后端都有且更全；但三处缺口：全部实发为 0 仍可确认出库；同一幂等键换内容时正式后端直接返回旧单（原型报 409）；应收挂账失败被吞掉。
4. 主数据：`SkuAssemblyService.assemblesku` 取客户时**没有按工厂过滤**（跨租户读取并把客户名写进 SKU 名称与编码），这是本切片最硬的一条缺陷。`RawMaterialTypeServiceImpl.deleteMaterialType` 不检查 BOM 与采购单引用，且检查异常时放行删除。

## 1. 退货单（fusion-return-orders.mjs → ReturnOrderService 体系）

正式后端入口：`controller/inventory/ReturnOrderController.java`（`/api/mobile/{factoryId}/return-orders`）→ `service/inventory/impl/ReturnOrderServiceImpl.java`；采购退货容量在 `service/inventory/impl/PurchaseReturnCapacityService.java`。

| 原型规则（行） | 正式后端方法（文件:行号） | 判定 | 说明 |
|---|---|---|---|
| 退货类型只能 PURCHASE_RETURN / SALES_RETURN（L14） | `ReturnOrderServiceImpl.createReturnOrder` `ReturnType.valueOf(request.getReturnType())`（ReturnOrderServiceImpl.java:180） | 一致 | 非法值抛 IllegalArgumentException（未读到全局异常映射，不确定返回 400 还是 500）。 |
| 来源订单必须存在且往来方与退货往来方一致（L15，否则 403） | 采购：`PurchaseReturnCapacityService.prepare`（PurchaseReturnCapacityService.java:101-113）按 `findByIdAndFactoryIdForUpdate` 取源采购单，状态不可退货 422，`supplierId != counterpartyId` 422。销售：**无**。 | 采购一致且更严；销售缺失 | 销售退货里没有任何一处比较 `SalesOrder.customerId` 与 `request.counterpartyId`（`getCustomerId` 在 ReturnOrderServiceImpl.java 全文无命中）。有货销售退货靠发货分配证据间接约束到订单，无货销售退货连 `sourceOrderId` 都不要求（ReturnOrderServiceImpl.java:192-201 只对 `physicalSalesReturn` 要求）。见缺陷 D-R3。 |
| 退货日期格式、原因必填、明细非空（L16） | DTO `dto/inventory/CreateReturnOrderRequest.java`：`returnDate @NotNull`、`items @NotEmpty`、`reason` 仅 `@Size(max=1000)`（:29-59） | 有偏差 | 原因必填在正式 DTO 不是必填（未读到 service 层补校验）。口径问题，非资金缺陷。 |
| 明细按物料/产品唯一匹配来源行，来源行不唯一或重复则拒绝（L17-18） | 采购：`prepare` 按 materialTypeId 分组，`matches.size()!=1` 422（PurchaseReturnCapacityService.java:131-155）。销售：`deriveOrValidateSalesReturnBatchEvidence` 订单行不唯一 409（ReturnOrderServiceImpl.java:358-365） | 一致 | 采购侧把同物料多行请求量合并后再校验（:141），原型是逐行判重复拒绝；效果等价偏宽松。 |
| 退货累计量**排除已驳回单据**（L18 `r.status!=='REJECTED'`） | 销售：`existingSalesReturnQuantity` 跳过 `REJECTED`（ReturnOrderServiceImpl.java:590）；采购：`CAPACITY_STATUSES` 白名单 DRAFT/SUBMITTED/APPROVED/FINANCE_APPROVED/COMPLETED（PurchaseReturnCapacityService.java:43-46） | 一致（但口径更窄） | 两处都排除 REJECTED。采购用白名单，枚举里还有 `PROCESSING`（entity/enums/ReturnOrderStatus.java:12）不在白名单内——若有流程会把单置为 PROCESSING，该单将不占容量（未读到谁写 PROCESSING，见缺陷 D-R5）。 |
| 累计量对所有退货（含无货）都计入（L18：只按 sourceOrderId+status 过滤） | 销售 `existingSalesReturnQuantity` `if (Boolean.FALSE.equals(order.getWithGoods())) continue;`（:593）；采购 `returnedByMaterial` 只算 `withGoods==TRUE`（:217）；采购校验 `if (!physicalReturn) continue;`（:156） | **有偏差** | 无货退款（withGoods=false）不占容量、不受容量约束。原型会拦。见缺陷 D-R1。 |
| 退货合计不能超过已收货/实发数量（L18，409） | 采购：`receivedBaseQuantity` 用收货量按采购快照/换算折基本单位，累计+本次>收货量则 409 `PURCHASE_RETURN_CAPACITY_EXCEEDED`（:149-166、:229-244）。销售：按**批次**核对实发分配合计，`requested+alreadyReturned > shipped` 则 409（ReturnOrderServiceImpl.java:478-491） | 正式更严 | 销售按实际发货批次与 shipment 核对，原型只按订单行 `deliveredQuantity`。采购在无法换算时返回 null 并报 422（:157-160），不当 0 也不当 1:1。 |
| 数量必须为正的有限数（L18） | 采购：`>0` 且 `exactTwoDecimals`（PurchaseReturnCapacityService.java:136-140）；销售 DTO `@DecimalMin("0.01")`（CreateReturnOrderRequest.java:83） | 一致/正式更严 | |
| **退货单价必须等于来源订单价**（L18：`Number(i.unitPrice)!==Number(line.unitPrice)` 则 422） | `createReturnOrder` 直接 `item.setUnitPrice(itemDTO.getUnitPrice())`（ReturnOrderServiceImpl.java:259），`lineAmount = qty × 请求单价`（:263-268）；采购 `prepare` 全程未读 `PurchaseOrderItem.unitPrice` | **缺失** | 正式后端的单价来自请求体。退款金额进入 AR/AP 调整（approve/complete 用 `getReceivableAmount()` / `getTotalAmount()`，:906-913、:1213-1229）。见缺陷 D-R2。 |
| 来源价缺失时单价、金额为 null，不凑 0（L18-19 `unitPrice ?? null`、`known` 判断） | 正式：`lineAmount` 无单价时为 `BigDecimal.ZERO`（ReturnOrderServiceImpl.java:263-268） | 有偏差 | 正式后端把"无单价"记成 0 金额；原型记 null。`ReturnOrder.totalAmount` 因此永远非 null。口径问题：待 Steve 裁决（见 D-R6）。 |
| 状态机 submit: DRAFT→SUBMITTED；approve: SUBMITTED→APPROVED；reject: SUBMITTED→REJECTED（L23），其它 409 | `submitReturnOrder` DRAFT→SUBMITTED（:857-866）；`approveReturnOrder` SUBMITTED→APPROVED（:870-925）；`rejectReturnOrder` SUBMITTED→REJECTED（:1116-1129），全部 409 | 一致 | 正式额外有 FINANCE_APPROVED、financeReject（APPROVED→REJECTED，:1077-1112）、COMPLETED。 |
| 有货退货的 approve 之后必须财务审批与完成，原型一律拒绝并说"未接入真实退款账务"（L22） | `financeApproveReturnOrder` APPROVED→FINANCE_APPROVED（:1060-1073，Controller 要求 `finance:read_write`，ReturnOrderController.java:121）；`completeReturnOrder` 前置必须 FINANCE_APPROVED（:1183-1189），完成时检查会计期间开放（:1198）、锁工厂（:1179） | **正式更严** | 不得建议改弱。原型 `NOT_POSTED` 只是占位，真实记账、期间、凭证作废、冲减审批在正式后端。 |
| 无货退款单 approve 时立即记账冲减（原型直接拒绝，L22 `approve&&withGoods===false`） | `approveReturnOrder` withGoods=false 分支调 `arApService.recordAdjustment`（:892-917）。`recordAdjustment` 为 PENDING 调整，余额待财务审批才变（见 :1240-1246 的 completionHint 注释） | 正式更严；但吞异常（缺陷 D-R4） | |
| 客户/供应商往来方写入来源订单的名称（L19 counterpartyName） | `getReturnOrderDetail` 用 `customerRepository.findById(counterpartyId)`（ReturnOrderServiceImpl.java:672-676）、`salesOrderRepository.findById(sourceOrderId)`（:684-687），列表 `enrichForDisplay` 用 `findAllById`（:798-828） | 有偏差（租户隔离） | 全部是**未按工厂过滤**的主键查询，且 counterpartyId/sourceOrderId 在无货退货里不校验归属，名称可被跨工厂 ID 带出。见缺陷 D-R3。 |
| 租户隔离：原型硬编码 FOOD_0000_001 | `getReturnOrderById` 比较 `order.getFactoryId()`，不匹配 403（:650-659）；写路径 `loadReturnOrderForWrite` 行锁并校验工厂（:1371-1378）；采购源单 `findByIdAndFactoryIdForUpdate`；销售订单行 `findBySalesOrderIdAndSalesOrderFactoryId`（:342-343） | 一致（写路径）/ 读路径有缺口 | 缺口见 D-R3。 |
| 退货完成后库存：原型不做 | 销售：完成时在成品仓建 DEFECTIVE 成品批次，沿用原批次单位、成本、所有权（:1266-1314）；采购：按采购入库仓 FEFO 扣减并写 `MaterialBatchAdjustment`（`deductMaterialForPurchaseReturn`，:1395-1464），库存不足整体 409，不部分扣减（:1410-1417） | 正式更严（原型无对应） | 完成链路失败一律 fail-loud 抛业务异常、整体事务回滚（:1233-1239、:1304-1311、:1353-1362）。 |

### 退货：疑似正式后端缺陷

**D-R1 无货退款不占退货容量、不受容量约束**
- 位置：`service/inventory/impl/ReturnOrderServiceImpl.java:593`（销售累计跳过 withGoods=false）、`:192-201`（无货销售退货不要求源订单与批次证据）；`service/inventory/impl/PurchaseReturnCapacityService.java:156`（采购 `if (!physicalReturn) continue;`）、`:217`（累计只算 withGoods=true）。
- 触发场景：已收 100 kg 的采购单，先提交无货退款 100 kg，再提交无货退款 100 kg、再提交有货退货 100 kg，三张都能建（采购无货分支在 :156 `continue`，不比较累计）；每张无货单审批时各生成一条应付冲减（:897-903）。
- 性质：**口径不明，待 Steve 裁决**。
  - 口径 A（无货退款=按数量的退款，和原型一致）：必须计入同一容量。后果：改后无货与有货共用余额，现存无货单要按 ReturnOrder 数据回算，可能让部分旧单据重新提交时被拒；风险低，账务更安全。
  - 口径 B（无货退款=金额类折让/赔付，与实物数量无关）：现状成立，但应改为按金额封顶（例如不超过来源订单已挂应收/应付），否则仍可无限冲减；后果：需要新的金额校验与产品文案，不能只加数量校验。
- 建议修法：先问 Steve 取口径；无论哪种口径都应加"上限"。A：把 `returnedByMaterial` 与 `existingSalesReturnQuantity` 的 withGoods 过滤去掉、`prepare` 去掉 `if (!physicalReturn) continue;`，并保留 REJECTED 排除。B：新增按来源单金额的累计上限。

**D-R2 退货单价/金额不校验来源价**
- 位置：`ReturnOrderServiceImpl.java:259`（单价取请求体）、`:263-268`（lineAmount）、`:1010-1046`（`salesReturnTaxAmount` 按"SKU+请求单价"回匹配来源行，匹配不上则取订单默认税率）。
- 触发场景：来源单价 10 元，退货请求填 100 元，审批/完成时冲减 100 元（`getReceivableAmount()`，ReturnOrder.java:215-218）。冲减是 PENDING 调整、靠财务审批把关，所以是"审批前不拦"，不是"必然多冲"。
- 性质：**缺陷（原型已要求而正式缺失）**；是否允许议价退货（例如折价退货）为口径问题，**待 Steve 裁决**：
  - 口径 A（一律等于来源价，与原型一致）：改动小，但折价退货业务无法录入。
  - 口径 B（允许不等价但必须有原因并经财务审批）：保留现状，加"不等于来源价时要求原因并在财务审批界面高亮"。
- 建议修法：在 `createReturnOrder` 逐行回取来源订单行价（销售取 `SalesOrderItem.unitPrice`、采购取 `PurchaseOrderItem.unitPrice`），不等则 422；采购同时保留含税/未税口径与 `salesReturnTaxAmount` 一致。取价必须按工厂过滤。

**D-R3 销售退货往来方、来源订单与租户未校验（读写两面）**
- 位置：`ReturnOrderServiceImpl.java:195-201` 无货路径不要求 `sourceOrderId`；全文无 `customerId` 比对；`:1015` `salesOrderRepository.findById(sourceOrderId)` 无工厂过滤（`salesReturnTaxAmount`）；`:287-294` 用该 sourceOrderId 写 `BusinessLink`；`:672-676`、`:684-687`、`:798-828` 用无工厂过滤的 findById/findAllById 带出对方名称与源单号。
- 触发场景：构造无货销售退货，`counterpartyId` 填任一客户 UUID，`sourceOrderId` 填另一工厂的销售订单 ID：税额按对方订单税率计算并写入 `taxAmount`，详情/列表带出对方客户名与对方订单号。
- 性质：**缺陷（租户隔离）**；需要真实租户数据复现才能确认可利用性，本次只读未复现，标"代码层可见、未实测"。
- 建议修法：`createReturnOrder` 对销售类型统一用 `customerRepository.findByIdAndFactoryId`、`salesOrderRepository.findByIdAndFactoryId` 校验，并比较订单客户=往来方；`salesReturnTaxAmount` 与 enrich 全部换成带工厂的查询；增加一条阴性测试（跨工厂 ID 被拒）。

**D-R4 无货退款审批时吞掉应收应付冲减失败**
- 位置：`ReturnOrderServiceImpl.java:894-917` `try { recordAdjustment(...) } catch (Exception e) { log.error(...) }` 后继续 `order.setStatus(APPROVED)` 并 save。
- 对照：同文件完成路径 `:1205-1239` 已改成 fail-loud（注释 `#3(b)`：失败整体回滚，原因"吞异常会让冲减失败却仍完成"）。同一件事两处相反做法（measurement-and-wiring 形态 D）。
- 触发场景：往来方缺失、期间关闭等导致 `recordAdjustment` 抛错。`recordAdjustment` 是 `@Transactional`（service/finance/impl/ArApServiceImpl.java:855-866），异常穿过代理会把共享事务标记为仅回滚，吞掉后提交时大概率变成 UnexpectedRollbackException（500），而不是静默落账；但这取决于异常类型与传播属性，**未实测**。无论哪种，行为都与完成路径不一致。
- 性质：缺陷（口径一致性）。
- 建议修法：照 `:1233-1239` 复制 catch 结构，BusinessException 原样上抛，其它异常包装成 500 并回滚。

**D-R5 采购退货容量白名单漏 PROCESSING**
- 位置：`PurchaseReturnCapacityService.java:43-46`；枚举 `entity/enums/ReturnOrderStatus.java:12` 含 `PROCESSING`。
- 说明：全库 grep `ReturnOrderStatus.PROCESSING` 无任何命中，当前没有代码把退货单置为 PROCESSING，所以现状不触发；但枚举值存在，一旦启用该状态，采购退货会不占容量。
- 性质：潜在缺陷（现状不触发）。建议改成"排除 REJECTED 的黑名单"，与销售侧一致，避免枚举新增再漏。

**D-R6 无单价退货行金额记 0 而不是 null**
- 位置：`ReturnOrderServiceImpl.java:263-268`。原型要求"不能算就不算"（缺单价→null，整单金额 null）。
- 性质：口径问题，**待 Steve 裁决**：A 保持 0（财务简单，但无法区分"0 元"和"没价"）；B 改 null（与硬规则"不能算就不算不能退化成 0"一致，但 `ReturnOrder.totalAmount`、AR 调整、统计 `getReturnOrderStatistics` 的 `reduce` 要全部处理 null）。D-R2 修好后基本不会再出现无价行，两个问题可合并裁决。

## 2. 销售发货（fusion-sales-delivery.mjs → SalesService 发货体系）

正式后端入口：`controller/sales/SalesDeliveryShipmentController.java`（母单下建/取消子发运单）、`controller/warehouse/WarehouseDeliveryController.java`（仓库确认，未读全文）、`controller/sales/SalesDeliveryBatchAllocationController.java`；规则全部在 `service/inventory/impl/SalesServiceImpl.java`，批次分配在 `service/sales/impl/SalesDeliveryBatchAllocationServiceImpl.java`。

| 原型规则（行） | 正式后端方法（文件:行号） | 判定 | 说明 |
|---|---|---|---|
| 只有 FINANCE_APPROVED/PROCESSING/PARTIAL_DELIVERED 的订单可建发货计划（L21） | `createDeliveryRecord` 用 `OrderUsageWhitelists.SO_DELIVERABLE`（SalesServiceImpl.java:3157-3161，注释说含 CONFIRMED） | 有偏差 | 正式白名单多一个 CONFIRMED（预发/寄售场景，3146-3148 注释）。口径问题，非缺陷。 |
| 必填确认编号（idempotencyKey）、发货地址、明细（L21） | 地址：`DELIVERY_ADDRESS_REQUIRED`，缺则回填订单/客户地址（:3222-3236）；idempotencyKey **非必填**（:3171 只在非空时判重） | 有偏差 | 没有幂等键时，重复点击会建多张母单（量上由行锁 + 剩余数量约束，:3153-3220）。 |
| 同一确认编号：内容相同返回旧单，内容不同 409（L22 digest 比较） | `createDeliveryRecord`：命中同 key 直接 `return replay`，**不比较内容**（:3171-3180）；`createDeliveryShipment` 同样直接返回旧子单（:3365-3370） | **有偏差（正式更松）** | 同一 key 换了数量也静默返回旧单，调用方以为新内容已生效。见缺陷 D-S2。另外 key 仅在**同一销售订单**内查重（:3169-3173 `findBySalesOrderId`），跨订单复用同一 key 会各建一张。 |
| 明细必须与订单行一致：产品、单位、数量>0，行不重复（L23） | `resolveDeliverySourceOrderItem` + 数量>0（:3186-3196）；单位 `Objects.equals(sourceOrderItem.getUnit(), itemDTO.getUnit())` 不等则 400（:3276-3280）；重复行靠订单行合并累计（`requestedByOrderItem.merge`） | 一致 | 未读到 `resolveDeliverySourceOrderItem` 本体对"产品与订单行不一致"的报错，标"无法判断该子条"。 |
| 发货单价取自订单行（L23 `...i` 展开，价格来自请求） | `item.setUnitPrice(itemDTO.getUnitPrice())`（:3295）取请求体；子发运从母行复制（:3484）。**应收挂账不用发货行单价**：`taxInclusiveShippedValue(order, record)` 按订单行税率借价（:3816，注释 3796-3815） | 有偏差（影响有限） | 母单行价来自请求，但应收口径回订单行借税率。是否用请求价算金额：`taxInclusiveShippedValue` 方法体**未读到**，不能确认单价是订单价还是发货行价。列为"待确认"。 |
| 计划发货累计不能超过订单行数量（L23，1e-8 容差） | `arrangedByOrderLine` + `resolveOverShipLimit`：累计安排量 ≤ 订单量×(1+超发上限)（SalesServiceImpl.java:3201-3220、:4198-4216、:279） | 有偏差（正式更宽，可配） | 上限可配，且"不限制"时不设上限（:3199-3201 注释）；原型严格不许超。属于已拍板口径（A16，Steve 11-11 开工），非缺陷。 |
| 母单→子发运单（`FULLY_SCHEDULED`）、子单数量不超母单剩余、配送方式校验（L26-29） | `createDeliveryShipment`（:3347-3506）：剩余数量校验（:3466-3476）、物流配送必填公司和运单号（:3437-3446）、母单状态 `updateMasterDeliveryStatus`（:3598-3633，含 PARTIALLY_SCHEDULED 等更多态）、`FULL` 模式必须覆盖全部剩余（:3403-3416） | 一致/正式更全 | 创建后自动 FIFO 预填批次，库存不足只回提示不拦（:3504、:3524-3558）。 |
| 子发运取消（原型无） | `cancelDeliveryShipment`：已发货不可取消、释放批次分配（:3574-3596） | 正式独有 | |
| 仓库确认：只能确认子发运单；已出库同摘要重放返回成功，不同数量 409（L32） | `warehouseConfirmDelivery` → `shipDelivery`：母单直接 409（:3641-3645）；状态不是 DRAFT/PICKED/PENDING_WAREHOUSE_CONFIRM 则 409（:3646-3651、:4081-4086）。重放（已 SHIPPED）统一 409，**不返回旧结果** | 一致（安全）/ 幂等语义有偏差 | 重放不会二次扣库存（状态闸），但前端重试要自己判 409。口径问题，低优先级。 |
| 实发数量 0..本次计划，超出拒绝（L33） | `warehouseConfirmDelivery`：不得为负（:4105-4108）；上限 `assertWithinOverShipCap` + 订单行累计上限（:4117-4118），超限留痕（REQUIRES_NEW）后 409（:4119-4124） | 有偏差（正式允许按超发上限多发） | 同上，已拍板口径。 |
| **实发合计必须>0，全零不许确认**（L34 `totalActual>0`） | `warehouseConfirmDelivery`/`shipDelivery` 没有全零校验；`autoAllocateFifo` 对 requiredQty<=0 直接返回 true（SalesDeliveryBatchAllocationServiceImpl.java:743-745）；`deductMaterialInventory` 对 ≤0 直接 return（SalesServiceImpl.java:4977-4979）；`recordReceivableIfAbsent` 对 ≤0 金额返回 null 不挂账（ArApServiceImpl.java:221-226） | **缺失** | 全部实发填 0 时：状态改 SHIPPED、母单状态推进、订单状态走 `updateOrderDeliveryStatus`（"没有任何发出"落入 else 仍置 PARTIAL_DELIVERED，:5509-5512），只是不扣库存不挂账。见缺陷 D-S1。 |
| 指定来源仓库必须明确，否则报错（L33 `!item.sourceWarehouseCode`） | 空仓库码时对"全部可售仓库（除研发库）"跨仓 FEFO（SalesServiceImpl.java:5069-5095，注释 G1 说明） | 有偏差（正式更宽，已拍板口径） | 研发/中试库被排除。未读到其它"不可售仓"排除。 |
| 先到期先出：按 `expireDate||createdAt` 字符串排序（L33） | 候选批次查询 `ORDER BY b.expireDate ASC NULLS LAST, b.productionDate ASC`（repository/inventory/FinishedGoodsBatchRepository.java:327-335） | 有偏差（正式更合理） | 原型把"无到期日"用 createdAt 与到期日字符串混排，会让无到期日的批次排进最前；正式把无到期日放最后。融合以正式为准。两处都不碰 createdAt。 |
| 排除客户自有库存（L33 `ownershipType!=='CUSTOMER_OWNED'`） | 查询限定 `ownership IS NULL OR COMPANY_OWNED`（同上 :332）；客户自有走 `findShippableCustomerOwnedBatches...`（SalesServiceImpl.java:5078-5081）+ `SalesFinishedGoodsOwnershipGuard.assertBatchAllowed`（:5124） | 一致/正式更全 | |
| 指定批次分配时按分配量扣；实际量超过分配量则拒绝（L33 `selected...<actual`） | 有分配记录就**精确按分配扣**（`deductByAllocations`，:5052-5059、:5219-5282），可用不足 409 不静默少扣（注释 :5214-5217）；`shipDelivery` 前 `isFullyAllocated`（分配合计==发货量）不满足则自动 FIFO 补足，补不到 409（:3682-3718） | 一致/正式更严 | 本函数后半段（:5282 以后的逐批扣减与 lineage 写入）**未读到**。 |
| 库存不足：整单不动，不修改任何库存（L33 "未修改任何库存"） | 整个确认在一个 `@Transactional`（SalesServiceImpl.java:3635-3637）；缺货抛 `BusinessException`（:5188-5199、:3714-3717），事务回滚；物料行缺货同（:5001-5006） | 一致 | 唯一例外是故意提交的超发尝试留痕（`recordBlockedOverShipAttempt`，REQUIRES_NEW，:4116-4124）。 |
| 过期批次是否可发（原型不校验） | `FinishedGoodsBatchSaleabilityGuard.excludeExpired` 过滤过期批次（:5099-5102）；分配时 `assertSaleable`（SalesDeliveryBatchAllocationServiceImpl.java:210） | 正式更严 | |
| 不同单位批次折算 | FEFO 与分配用 `FgQuantityUnitConverter.convert`/`convertBatchToDeliveryUnit`，换不了的批次跳过并打 warn（:5128-5134），最终缺额则报库存不足 | 一致/正式更严 | 这里是 E 部分"诚实 null"的正面例子，但跳过只打日志、不进错误文案（见 E 文件）。 |
| 订单状态：全部实发→`SHIPPED`，签收→`DELIVERED`（L18、L34） | `updateOrderDeliveryStatus`：全部发完→`COMPLETED`，部分→`PARTIAL_DELIVERED`；`transportPlanStatus` 另记 IN_TRANSIT/RECEIVED（SalesServiceImpl.java:5444-5520、:3929-3949） | 有偏差（状态机定义不同） | 订单头状态语义不一致，融合时 UI 的状态文案不能直接搬。口径：待 Steve 裁决要不要新增 SHIPPED 订单态（现状用 COMPLETED + 运输状态）。 |
| 签收：必须签收人+至少 1 张本工厂本发运单上传的图片，才能 `delivered`（L18） | `confirmDelivered`：只检查 `signedByName` 非空、`signaturePhotoUrls` 非空非 `[]`（SalesServiceImpl.java:3908-3916） | 有偏差/无法判断 | 图片是否属于本工厂、本发运单：`uploadDeliverySignature`（:4487）**未读到**，不能确认。 |
| 短交结案：仅 PARTIAL_DELIVERED；原因枚举 4 项，OTHER 必须说明；有待仓库确认发运单时拒绝（L19） | `closeShortShipment`（:2838-2925）：状态闸、非终态子发运单拒绝（:2862-2874）、有缺口才可结案、原因必填、OTHER 必填说明、说明≤500 字、重放幂等返回（:2845-2848）；原因枚举与原型一致（dto/inventory/CloseShortShipmentRequest.java:21-29） | 一致/正式更严 | |
| 发货→应收挂账（原型不记账） | `shipDelivery` 内 `recordReceivableIfAbsent`，金额=实发含税价税合计，幂等键=子发运单（:3751-3834） | 正式独有（更严） | 吞异常见缺陷 D-S3。 |
| 租户隔离 | 取单 `lockDeliveryHierarchyForMutation`（:3993 起，未读全文）、订单 `findByIdAndFactoryIdForUpdate`、批次 `findByIdAndFactoryIdForUpdate`、分配/扣减均校验批次所属工厂（:5245-5248）；`deductFinishedGoodsAmounts` 取订单用 `findById` 后比对工厂（:5025-5035） | 一致 | `lockDeliveryHierarchyForMutation` 的实现未读全，按其调用名与其它同类代码推断含工厂过滤，标"推断"。 |

### 发货：疑似正式后端缺陷

**D-S1 全部实发为 0 仍可确认出库**
- 位置：`service/inventory/impl/SalesServiceImpl.java:4074-4151`（`warehouseConfirmDelivery`）、`:3637-3837`（`shipDelivery`）均无 `totalActual > 0`；`service/sales/impl/SalesDeliveryBatchAllocationServiceImpl.java:743-745`。
- 触发场景：仓管把整张子发运单每一行都填 0 确认：状态 SHIPPED，母单、订单状态被推进，无库存扣减、无应收、无 lineage，发货记录里留下一张"已发出 0 件"的出库单。
- 性质：**缺陷**（原型已拦，正式缺失）。是否允许"整单取消式零发货"可能是口径：若业务要允许，应该走取消流程而非 SHIPPED。
- 建议修法：`warehouseConfirmDelivery` 应用实发覆盖后，若所有行 `deliveredQuantity` 之和 ≤0 则 409，提示改用取消子发运单。

**D-S2 幂等键换内容时静默返回旧单**
- 位置：`SalesServiceImpl.java:3171-3180`、`:3365-3370`。
- 触发场景：同一订单同一 idempotencyKey，第二次请求换了数量/地址，返回第一次建的单，HTTP 200。
- 性质：缺陷（幂等语义）。原型 `fusion-sales-delivery.mjs:22` 用 sha256 摘要比较并 409。
- 建议修法：在 `SalesDeliveryRecord` 保存请求摘要（需要新列或复用现有指纹字段，**未读到是否已有**），命中同 key 时比较，不一致 409；采购退货已有同类实现可参照（`PurchaseReturnCapacityService.samePayload`，:274-297）。

**D-S3 发货后应收挂账失败被吞，且 BusinessException 被误标为"可能已存在"**
- 位置：`SalesServiceImpl.java:3829-3833`：`catch (BusinessException e) { log.warn("应收自动挂账跳过(可能已存在)") } catch (Exception e) { log.error(...) }`。
- 触发场景：会计期间关闭、客户缺失、`requireSalesOrderOwner` 失败等任何异常，发货已 SHIPPED、库存已扣，但应收没挂，调用方拿到成功响应。
- 说明：`recordReceivableIfAbsent` 自己的"跳过"全部是返回 null 不抛（ArApServiceImpl.java:219-226 注释），所以吞掉的异常恰好是"真失败"。而 `shipDelivery` 前面已 `assertShipmentPeriodOpen`（:3640），期间类失败多数已被前置拦截，剩余是客户/订单归属类。
- 性质：缺陷（账务静默漏记）；是否应该回滚发货属于口径，**待 Steve 裁决**：A 回滚（账实强一致，但"货发不出去"的风险——ArApServiceImpl.java:210 的注释正是为了避免 doomed-tx 把货卡住）；B 保持发货成功但写入待补账队列并给用户可见告警（需新机制）。现状既不回滚也无告警，是最差的一种。
- 建议修法：至少把 catch 中的失败写入一条可查询的"待补账"记录或阻断式告警；若选 A，去掉两个 catch 并让 `recordReceivableIfAbsent` 的校验失败抛出。

## 3. 主数据（fusion-masterdata.mjs → RawMaterialType / ProductType / SkuAssembly）

| 原型规则（行） | 正式后端方法（文件:行号） | 判定 | 说明 |
|---|---|---|---|
| `GET raw-material-types/suggest`：按**精确名称**+类别找一条，返回整行与 `unitConfidence:HIGH`（L11） | `RawMaterialTypeController.suggestFields`（controller/RawMaterialTypeController.java:375-387）→ `RawMaterialTypeServiceImpl.suggestFields`（service/impl/RawMaterialTypeServiceImpl.java:1329-）：`findSimilarByNameAndCategory` 模糊 LIKE，仅启用物料，按创建时间倒序取 3；无结果退化为前两个字，别名命中优先（:1337-1349） | 有偏差（正式更宽） | 前缀回退 `keyword.substring(0,2)` 会让"三文鱼柳"建议到任意以"三文"开头的物料的单位/类别；仅作预填建议，不写库，**非缺陷**，但融合到新建界面时必须保留"建议可改"。置信度字段在 `unitConfidence` 的取值未读到。 |
| `GET suggest-unit`：同名返回其 unit，未命中 null（L11） | `RawMaterialTypeController.suggestUnit`（:304-312）→ `RawMaterialTypeServiceImpl.suggestUnit`（service/impl/RawMaterialTypeServiceImpl.java:1127-1151）同样模糊 + 前两个字退化，无命中返回 null | 有偏差（同上） | 无命中返回 null，不臆造 kg，一致。 |
| `GET preview-code`：返回 `LOCAL-RAW-NNNNN` 避重，不写库（L10） | `previewMaterialCodeContract` → `generateNextCode`（RawMaterialTypeServiceImpl.java:1312-1326、:1243-1262）：按基本类型前缀 YL/RL/BC/WL + 三位序号，取同前缀最大号+1，**未知基本类型 400 不猜前缀**（:1156-1173）；只读 | 有偏差（正式为生产编码口径） | 原型的 `LOCAL-RAW-` 是本机占位，不应搬。注意预览与创建之间没有预占号，并发创建同号时靠创建时 `findCodeConflictIncludingDeleted` 409（:183-190）兜底；`generateNextCode` 本身无锁。 |
| 创建物料：名称、料号、类别、库存单位必填；料号重复 409（L12） | `createMaterialTypeInternal`（:129-263）：类别必填且必须在 `MaterialMainCategory`；料号为空则自动生成；**名称归一后重名 409**（:158-161，原型无）；料号冲突**含已删除记录** 409（:183-190）；单位 `normalizeInventoryUnit`；税务与包材价必填；非包材必须填储存类型（:251-257） | 正式更严 | 融合时原型"只查料号"的校验不能替换正式的名称+料号+已删除联合冲突。 |
| 保质期必须为非负整数（L12） | `createMaterialTypeInternal` 直接 `setShelfLifeDays(dto.getShelfLifeDays())`（:212） | 无法判断 | 是否有 DTO 注解（`RawMaterialTypeDTO.shelfLifeDays`）校验**未读到**。 |
| 删除物料：被批次、BOM 配方（主料与调料）、采购单引用则 409（L13） | `deleteMaterialType`（:636-706）：只查 `material_batches` 与 `material_product_conversions`（原生 SQL，:650-682）；**不查 BOM 配方项、BOM 调料项、采购单行**。两处检查块遇到非业务异常只 `log.warn`，继续删除（:664-667、:686-688）。删除是软删（`@SQLDelete`，entity/RawMaterialType.java:103） | **缺失 + 失败放行** | 见缺陷 D-M2。 |
| 包装规格：基本单位必须等于物料库存单位；包装单位不得重复且不得等于库存单位；系数>0（L9） | 未在本切片读到对应的 `MaterialPackagingSpec` 写入服务；`ProductPackagingSpecServiceImpl`、`MaterialPackagingHierarchyServiceImpl` 在 `UnitContractService` 用户清单中出现（见 E 文件） | 无法判断 | 未读到物料侧 `material-packaging/by-material` 的 Controller/Service 实现。 |
| 产品模板与 SKU 列表：`templateId` 空=模板；SKU 按模板列出且仅启用（L18-19） | `SkuAssemblyService.getTemplates` / `getSkusByTemplate`（service/SkuAssemblyService.java:140-152）：`findByFactoryIdAndTemplateIdIsNullAndIsActiveTrue`、`...AndTemplateIdAndIsActiveTrue` | 一致 | 按工厂过滤。 |
| **SKU 组装**：模板必须是本工厂启用的基础产品；客户（若给）必须有效且属于本工厂（L21-22） | `SkuAssemblyService.assemblesku`（:79-138）：模板 `findById` 后过滤 `factoryId==null \|\| 相同工厂`（:84-86）；**客户 `customerRepository.findById(customerId)` 无工厂过滤**（:100-103）；不检查模板 `isActive`；不检查客户是否停用 | **缺陷（租户隔离）** | 见缺陷 D-M1。模板允许 `factoryId==null`（全局模板，设计）。 |
| 配方版本不能为空且 ≤50 字（L23） | Controller 直接 `(String) body.getOrDefault("recipeVersion","default")`（controller/ProductTypeController.java:116）；`assemblesku` 与 `generateSkuCode` 不校验长度/空白（SkuAssemblyService.java:96-98、:164-175） | 缺失 | 空白版本会生成 `CP-客户-模板- ` 之类编码；超长依赖 DB 列长度报错。另 `(String)` 强转对非字符串 body 抛 ClassCastException。低风险。 |
| SKU 编码 `CP-客户前 6 字-模板前 10 字[-版本]`，重复 409（L24） | `generateSkuCode`（:164-175）同样截断规则；`existsByFactoryIdAndCode` 重复 409（:96-98） | 一致 | **两边共有的口径风险**：两个不同客户名前 6 字相同（例如"六扇门A厂""六扇门B厂"）+ 同模板 → 编码冲突 409，被误报为"该客户已创建过"。待 Steve 裁决是否改成带客户 ID 后缀。 |
| 组装事务：SKU + 转换率 + 工序链 + 当前 BOM（含调料、注入配置）一起提交，失败整体回滚（L25-29） | `assemblesku` 标 `@Transactional`（SkuAssemblyService.java:77-79）；`copyRecipe`（:174-208）、`copyWorkProcessChain`（目标已有工序链则 409，:209-243）、`copyBom`（目标已有当前 BOM 则 409，:245-254）、`copyCurrentBomRecipe`（含明细、调料、注入配置，:256-338） | 一致/正式更全 | 正式带工艺保密守卫 `processSecretGuard.requireCopySource`（:88）、`recipeModifications` 配方修改（:176-203）。复制的 BOM 沿用源的状态/激活信息（:272-287），而不是重置为草稿——原型也复制 `isCurrent`，一致。 |
| `/conversions?productTypeId=` 只读（L17） | 未在本切片读 ConversionController | 无法判断 | |
| 客户信用状态 `credit-status`（L16） | 未读到对应 Controller/Service | 无法判断 | 本地新建客户测算，口径文案含"未连接生产账务"，不应搬。 |

### 主数据：疑似正式后端缺陷

**D-M1 SKU 组装的客户查询没有工厂隔离**
- 位置：`service/SkuAssemblyService.java:100-103`
  `customerRepository.findById(customerId).orElseThrow(...)`
- 触发场景：携带本工厂 token 调 `POST /api/mobile/{factoryId}/product-types/assemble-sku`，`customerId` 填另一工厂的客户 ID：通过校验，`sku.setCustomerId`、`sku.setRelatedCustomer(customer.getName())`、`sku.setName("模板 (他厂客户名)")`、SKU 编码都写入他厂客户名。读取到他厂客户名称即越权读；同时 SKU 与他厂客户 ID 关联。
- 对照：同一代码库的发货路径用 `customerRepository.findByIdAndFactoryId`（SalesServiceImpl.java:3141）。
- 性质：**缺陷（租户隔离）**；代码层可见，本次只读未真实复现，标"未实测"。
- 建议修法：换成 `findByIdAndFactoryId(customerId, factoryId)`；补一条跨工厂阴性测试（同 `ReturnOrder` 的修法）；顺手检查模板 `isActive`。

**D-M2 删除原料不检查 BOM 与采购单引用，且检查异常时放行**
- 位置：`service/impl/RawMaterialTypeServiceImpl.java:636-706`，特别是 `:664-667`、`:686-688` 的 `catch (Exception e) { log.warn(...) }` 之后继续 `delete`。
- 触发场景：物料被某 BOM 配方项/调料项或历史采购单引用但无库存批次、无转换率：可删，BOM 与采购单行里的 `materialTypeId` 变成指向软删物料的悬空引用（实体 `@Where(deleted_at IS NULL)` 让后续读不到该物料）。
- 性质：缺陷（原型 L13 已有的引用保护，正式缺失）；`BomRecipeServiceImpl` 与采购侧是否有自己的物料存在性校验**未读到**，所以不能判定下游一定出错。
- 建议修法：删除前补 BOM 配方项、BOM 调料项、采购单行、供应商物料关系的引用计数；两个检查块的异常分支改为失败（500 或 409），不要放行。

## 4. 本切片读取范围与未读部分

已读：`ReturnOrderServiceImpl` 1-1509 行；`PurchaseReturnCapacityService` 40-340 行；`ReturnOrderController` 关键注解；`SalesServiceImpl` 2838-2935、3105-3343、3347-3506、3562-3837、3860-3950、4060-4290、4953-5282、5444-5520；`SalesDeliveryBatchAllocationServiceImpl` 61-310、742-765、866-887；`SalesDeliveryShipmentController` 全文；`RawMaterialTypeServiceImpl` 115-263、636-706、1120-1151、1243-1350；`RawMaterialTypeController` 关键方法；`SkuAssemblyService` 全文；`ProductTypeController` assemble-sku。

未读到（判定里已标注）：`WarehouseDeliveryController`、`lockDeliveryHierarchyForMutation`、`taxInclusiveShippedValue`、`uploadDeliverySignature`、`resolveDeliverySourceOrderItem`、`deductByAllocations` 后半段、`ProductTypeServiceImpl`（产品侧 suggest/建档）、`MaterialPackagingSpec` 写入服务、`recordAdjustment` 的待审批队列细节、`RawMaterialTypeDTO` 校验注解。

---
