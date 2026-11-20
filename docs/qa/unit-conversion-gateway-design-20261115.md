# E. Java 后端单位换算散点清单与统一入口设计草案

读取基线：worktree HEAD `d6dc07cbaa`，Java 根 `backend/java/cretas-api/src/main/java/com/cretas/aims/`（下文路径省略此前缀）。全程只读，只设计不实现。

对照原型：`prototypes/ydwm-v1/fusion-report-contract.mjs` 的 `unitCode` / `convertQuantity`（只认 kg/g/pcs，同单位直通，kg⇄g 乘除 1000，其余一律 `throw Error('单位 X 与 Y 缺少明确换算关系')`，数量非有限数也 throw）。

## 0. 先纠正对照表里的一句话

BACKEND-MERGE-MAP 第五节与第七节写的"Java 没有等价的统一入口，只在 `YieldReportServiceImpl` 有 5 处"**不准确**。读到的事实：

- Java 已有统一换算引擎 `service/unit/UnitContractService`（实现 `service/unit/impl/UnitContractServiceImpl.java`，1220 行）。`convert(BigDecimal, UnitConversionContext)` 返回带状态的 `UnitConversionResult`（`IDENTITY / CONVERTED / UNKNOWN_UNIT / INCOMPATIBLE_DIMENSION / PRODUCT_CONVERSION_MISSING / AMBIGUOUS_CONVERSION`，service/unit/UnitConversionStatus.java），**从不在无换算关系时返回 1:1 或 0**。同量纲（质量/体积/长度）按内置系数换算（UnitContractServiceImpl.java:247-260、:516-523）；计数/包装与质量之间必须有 `product_unit_conversions` 的版本化换算图（实体 entity/unit/ProductUnitConversion.java，含 factor、effectiveFrom/To、version），或物料包装规格（`convertViaMaterialPackagingHierarchy`，UnitContractServiceImpl.java:353-）；没有 factoryId/productTypeId 就返回 `PRODUCT_CONVERSION_MISSING`（:275-285）；多条最短路径乘积不同返回 `AMBIGUOUS_CONVERSION`（:320-330）。
- 真正的问题不是"没有入口"，而是**入口之外还有约 40 个文件各自做换算/比较**：私有 kg/g 表、`gramsPerUnit` 直算、默认 `"kg"`、字面字符串比较、以及至少 5 种互不相同的"两个单位是否相同"实现。融合要做的是**收敛散点**，不是新建引擎。
- 原型 `convertQuantity` 的语义（不能换就报错）对应的是 Java 里 `UnitContractService.convert(...).succeeded()==false` 之后**抛业务异常**。目前只有一部分调用点这样做。

## 1. 现有换算相关设施（分层）

| 层 | 类（文件） | 作用 | 无换算关系时的行为 |
|---|---|---|---|
| 引擎 | `UnitContractServiceImpl`（service/unit/impl/UnitContractServiceImpl.java） | 归一、别名、量纲、图换算、`storageUnit` 落库字面值、`areEquivalent` | 返回非成功状态，不抛、不 1:1 |
| 门面 | `UnitConversionService`（service/UnitConversionService.java:17-57） | 旧调用点的兼容壳 | `convert` 失败返回 `null`；**`convertOrSame` 失败返回原值**（:43-46，当前无调用者，是陷阱）；`context` 里 factoryId/productTypeId/scene 全是 null（:53-56），所以**只能做质量/体积/长度同量纲换算**，计数↔质量永远返回 null |
| 成品批次 | `FgQuantityUnitConverter`（service/inventory/FgQuantityUnitConverter.java:55-123） | 成品批次跨单位，用 `ProductType.gramsPerUnit` | 诚实 null（:62-64、:68-70、:82-84、:93-95）；两个不同计数单位也返回 null（:81-84） |
| 投料 | `FeedUnitConverter`（service/inventory/FeedUnitConverter.java:70-81） | kg 投料折来源单位 | 计数单位缺克重返回 null；**非计数单位原样透传**（:74-76） |
| 备货 | `RestockUnitConverter`（service/restock/RestockUnitConverter.java:16-22） | kg→盒，硬编码 ×1000 | 缺克重返回 null |
| 物料 | `MaterialUomConverter`（service/uom/MaterialUomConverter.java:65-） | BOM 单位↔库存单位，用装箱规格 | 返回 `ConversionResult`：CONVERTED/ABACA_SKIP/UNCONVERTIBLE（类注释声明不默认 1.0） |
| 产品克重 | `ProductType.gramsPerUnit`（`ProductSpecificationConversionSyncServiceImpl` 把规格同步成换算图行） | 同一事实有**两份**：`gramsPerUnit` 字段与 `product_unit_conversions` 行 | — |

## 2. 散点清单

判定列含义：**静默 1:1** 指无换算关系时把数量原值当目标单位数量；**静默 0** 指当 0；**默认 kg** 指单位缺失时假定 kg；**诚实失败** 指返回 null 或抛业务异常。"已读"= 读到了该函数本体。

### 2.1 会在无换算关系时静默当 1:1 / 假定 kg / 当 0（需要修）

| 文件:行号 | 做什么 | 静默风险 | 说明 |
|---|---|---|---|
| `ai/tool/impl/material/MaterialBatchCreateTool.java:282-288` | AI 建原料批次时把数量折 kg | **静默 1:1** | `toKg` 返回 null（"非重量单位 箱/件/袋/桶/瓶/个"）时 `return quantity`（:287），数量原样当 kg 写入批次（已读）。库存红线域，AI 入口。 |
| `service/inventory/impl/InventoryPostingServiceImpl.java:341-344、:395-408` | 送货单 OCR 单位→物料标准单位 | **静默 1:1** | `sameDiscreteUnit`：个/pcs/件/只 任意两个视为同一单位，数量不变（已读）。与 `UnitContractServiceImpl.crossLanguageCode` 注释里的 #1976「一只≠一件」直接冲突；`TransferServiceImpl.java:596-608` 注释写明这正是 LIUSHANMEN 2026-07-30 事故形态（501 只看不见）。 |
| `service/impl/BomServiceImpl.java:396-415` | BOM 行数量归一到物料主数据计价单位 | **静默 1:1**（有意） | 非重量单位、主档非重量单位、转换结果 null 三处都 `return actualQuantity`（已读）。注释声明"不在最小修范围内"，所以是已知妥协，但成本数会在单位不同时错。 |
| `service/yield/CostReconcileService.java:307-318` | 投料对账折 kg | **静默 1:1** + 误用克重 | `unit==null 或 baseUnit==null 或字符串相等` 原样返回（:311）；字符串不等且有 gpu 就一律 ×gpu/1000（:314-316，已读）。若首道单位是 kg、某步单位是 g，会被当"份"乘克重。 |
| `service/yield/impl/YieldCalculationServiceImpl.java:369-382、:397-405` | 出成率末道折首道单位 | 误用克重 | `sameUnit` 是**原始字符串** `equals`（:369-371），不同就乘 `standardGramsPerUnit/1000`（:378-379、:403-404，已读）。kg 对 g、「盒」对 box 都会被当成"份→kg"。同模块的 `YieldReportServiceImpl.java:2621-2651` 已改成 `crossLanguageCode` + 严格条件（`to.equals(countUnit)`），**两份出成率实现已经漂移**。 |
| `service/inventory/FeedUnitConverter.java:70-81` | kg 投料折来源单位 | 未知单位按 kg 透传 | `isCountUnit` 为 false（包括内置表不认识的单位）→ 返回 `feedKg` 原值（:74-76，已读）。下游 `FinishedGoodsFeedServiceImpl.java:156-158` 注释说非计数不一致会 loud-fail，但对"单位未知且恰好等于..."这类组合**未读到**完整分支，保守标"部分保护"。 |
| `service/yield/impl/InterimSettleServiceImpl.java:507` | 小结成品行单位 | **默认 kg** | `ur.req.getUnit() != null ? ... : "kg"`（已读），成品入库单位缺省当 kg。 |
| `service/processentry/impl/ProcessSheetServiceImpl.java:566` | 投入分配行单位 | **默认 kg** | `source.getUnit()==null ? "kg" : ...`（已读）。 |
| `service/processentry/impl/ProcessSheetServiceImpl.java:967-976` | 报工投入量折 kg | **静默 0**（被兜住） | `reportingMassToKg` 未知单位返回 `BigDecimal.ZERO`（已读）；唯一调用点 `:987` 紧接 `if (kg.signum()>0)` 否则走引擎并在失败时抛 `SEASONING_BASIS_UNIT_NOT_CONVERTIBLE`（:983-1005），目前安全，但方法本身是陷阱。 |
| `service/impl/ProductionPlanServiceImpl.java:5200-5203` | 小结投料单位解析 | **默认 kg** | `firstNonBlank(input.unit, inputUnit, unit, "kg")`（已读）。 |
| `service/orchestration/SupplyChainOrchestrator.java:556-558` | 自动开计划的计划单位 | **默认 kg** | 产品存在但单位空则 `kg`（已读；注释说这是查不到产品与单位为空两个分支拆开后的"第二问"）。 |
| `service/orchestration/SupplyChainOrchestrator.java:801、:814-833` | 小结入成品库 | **默认 kg** + 换算失败保留 kg | `batch.getUnit()!=null ? ... : "kg"`；只处理 kg→盒（`"盒".equals(productUnit)` 硬编码），换算失败只 warn 并"保持kg入库"（已读）——会让同一产品出现不同单位的成品批次，这正是 `FgQuantityUnitConverter` 注释里 C1 问题的源头。 |
| `service/factory/impl/FactoryMaterialRequisitionServiceImpl.java:523-524、:645-653` | 调料领料行单位 | **默认 kg** | 库存单位查不到则 `kg`；`targetUnit==null` 也当 `kg`（已读）；非质量单位返回 null 不硬折（:652-653，诚实）。 |
| `service/yield/OrderCostBreakdownService.java:521、:539、:1160` | 成本明细展示单位 | 默认 kg（仅展示） | `mb != null ? mb.getQuantityUnit() : "kg"`（已读 :515-523），展示字段，不参与换算。 |
| `service/impl/MaterialBatchServiceImpl.java:626-636` | 写批次时检查单位与物料兼容 | fail-OPEN | `materialUomConverter == null` 直接 return（:633，已读片段），测试环境约定，生产应恒注入。 |
| `service/UnitConversionService.java:43-46` | `convertOrSame` | **静默 1:1**（陷阱） | 失败返回原值；全库无调用者（grep `convertOrSame` 仅此一处）。应删除。 |

### 2.2 私有换算表 / 重复实现（不一定出错，但会漂移）

| 文件:行号 | 做什么 | 说明 |
|---|---|---|
| `service/impl/ProductionPlanServiceImpl.java:5399-5435` | 小结投料→批次单位 | 先走 `UnitConversionService.convert`，失败后落到本地 kg/g/t switch（:5417-5425）。本地表只覆盖引擎已覆盖的单位，纯重复。 |
| `service/impl/ProductionPlanServiceImpl.java:4617-4666` | 产出折计划单位 | `areEquivalent` → 引擎 → `FeedUnitConverter.isCountUnit` + `gramsPerUnit` 本地算 kg → 再折计划单位。三段混用，失败返回 null（诚实）。 |
| `service/yield/impl/YieldReportServiceImpl.java:2621-2668` | 出成率换算 | 本地 `convertQuantity`/`toKilograms`（kg、g、`gramsPerUnit`），归一用 `crossLanguageCode`。失败 null。这是对照表说的"5 处"所在。 |
| `service/yield/OrderCostBreakdownService.java:2120-2152` | 锁定计划产出折 kg | 同上，用 `plannedNetWeightGrams`；失败 null。 |
| `service/processentry/impl/ProcessSheetServiceImpl.java:1020-1030` | `canonicalBomUnit` | 5 组私有别名表，把 片/slice/piece/pcs/个 全折成 "slice"（已读，注释 :1011-1018 自述会误拦/漏拦）；仅用于 `reportingMassToKg` 判 kg/g，影响面小。 |
| `service/processentry/impl/ProductionStockAllocationServiceImpl.java:1086-1133` | 报工 kg↔批次 g | 本地只认 g/kg（含中文），其余抛 `PRODUCTION_INPUT_UNIT_INVALID`（:1098-1109）；`:503` 的 `"g".equals(batchUnit) ? 1000 : ONE` 前面已被 `:477` 过滤成只剩 kg/g，安全。吨/斤在这里会被拒，引擎其实能换。 |
| `service/production/SettlementSemiStockingService.java:299-318` | 在制↔半成品单位 | 本地 kg⇄g，其它 409（诚实）。 |
| `service/bom/impl/BomRecipeServiceImpl.java:3803-3814` | 配方净含量折 kg | 本地 kg/g，其它返回 null（诚实）。 |
| `service/bom/SeasoningRatioContract.java:55、:146-149` | 调料克/千克 | 本地 kg/g；注释 :43 说刻意不走 `crossLanguageCode`。 |
| `service/inventory/impl/InventoryPostingServiceImpl.java:410-424` | 斤↔kg 兜底 | 本地 0.5 系数（引擎里 `jin` 已内置，`systemAliases` :967），重复。 |
| `service/production/PlanCostVarianceService.java:829-838` | 成本偏差换算 | 引擎失败时若 `areEquivalent` 则原值；诚实。 |
| `service/supplyimpact/SupplyImpactService.java:652-660` | 同上 | `equalsIgnoreCase` 直通后走门面；诚实 null。 |
| `event/listener/SampleApprovedEventListener.java:196-215`、`service/bom/impl/StandardCostServiceImpl.java:233-250` | 折 kg | 优先 `toKg`，未注入时落本地 switch；失败返回 null（诚实），本地分支是测试兜底。 |

### 2.3 已走统一引擎（合格）或诚实失败（可作为迁移模板）

| 文件:行号 | 做什么 | 说明 |
|---|---|---|
| `service/inventory/impl/PurchaseServiceImpl.java:3575-3594` | `conversionFactor` | 调 `unitContractService.convert`，失败抛 400；服务未注入抛 503（已读）。采购价/需求量折算用它（:3510-3512、:4531-4550）。 |
| `service/inventory/impl/PurchaseServiceImpl.java:3624-3664` | 收货换算 | 无快照且单位不同抛 409 `PURCHASE_RECEIPT_CONVERSION_SNAPSHOT_REQUIRED`（已读）——正好对应"收货换算必须沿用采购单保存时的快照，不能用当前档案重算"。 |
| `service/inventory/impl/PurchaseReturnCapacityService.java:229-263` | 采购可退量 | 先用快照与系数，再引擎；失败返回 null → 422（已读）。注意 `convert` 吞 RuntimeException 并返回 null（:260-262），`sameUnit` 在服务缺失时退到 `equalsIgnoreCase`（:265-272）。 |
| `service/processentry/impl/ProcessSheetServiceImpl.java:983-1005` | 调料用量基数 | 引擎失败抛 409。 |
| `service/production/SalesOrderPlanQuantityNormalizer.java:59-107` | 销售量折计划量 | 引擎 + 克重，失败不静默（已读片段，:66 对缺克重分支的后续处理未读）。 |
| `service/inventory/FgQuantityUnitConverter.java:55-123` | 成品跨单位 | 见 §1；`SalesServiceImpl.java:5128-5134` 调用后跳过批次并 warn，最终缺额报库存不足。 |
| `service/factory/impl/FactoryMaterialRequisitionServiceImpl.java:357-379` | BOM→库存单位 | 经 `MaterialUomConverter`，UNCONVERTIBLE 抛 409 `MATERIAL_UOM_UNCONFIGURED`。 |
| `service/bom/impl/BomRecipeServiceImpl.java:3070-3086` | BOM 计价单位 | 经 `MaterialUomConverter`，不可换算抛 409。 |
| `service/impl/WorkReportingServiceImpl.java:93-98、:418` | 报工单位归一与换算 | 用 `normalize`/`storageUnit`/`convert`（已看到调用点，未读上下文）。 |
| `service/yield/impl/YieldUnitResolver.java:73、:118-129` | 端口单位→克 | 走 `FgQuantityUnitConverter` + 端口快照克重，null 即不可比。 |

### 2.4 "是否同一单位"的比较实现（不是换算，但同样会漂）

| 文件:行号 | 实现 | 对 只/个/件 | 对 袋/bag |
|---|---|---|---|
| `service/unit/impl/UnitContractServiceImpl.java:188-192` `areEquivalent` | `normalize().code()` 相等 | 件≡pcs，个、只独立（systemAliases :982-984） | 相同 |
| 同类 `:1030-1038` `canonicalCodeOrRaw` | 码 + `COUNT_MATCH_FOLD`（个、只→件，筐→框） | **折叠为同一个**（有意，仅匹配侧） | 相同 |
| 同类 `:1088-1098` `crossLanguageCode` | 只、个保字面；其余归码 | 只≠个≠件（件≡pcs） | 相同 |
| `service/inventory/impl/TransferServiceImpl.java:609-615`、`service/impl/MaterialBatchServiceImpl.java:650-656`、`service/factory/impl/FactoryStocktakeServiceImpl.java:513-517`、`service/impl/TraceabilityServiceImpl.java:775-779` | `storageUnit` 相等（四处几乎相同的拷贝） | 只/个/件各自独立 | 相同 |
| `service/inventory/impl/ReturnOrderServiceImpl.java:634-641` | `storageUnit` 相等 | 独立 | — |
| `service/inventory/impl/PurchaseReturnCapacityService.java:265-272` | `areEquivalent`，服务缺失退 `equalsIgnoreCase` | 个、只、件互不相同 | — |
| `service/inventory/impl/InventoryPostingServiceImpl.java:395-408` | 私有离散名单 | **并成一个** | — |
| `service/processentry/impl/ProcessSheetServiceImpl.java:1020-1030` | 私有 5 组表 | 个并入片 | 袋≠bag |
| 字面 `equals`：`service/inventory/SalesOrderSuppliedMaterialRequirementService.java:186、:364、:429、:554`；`service/inventory/CustomerMaterialArrivalNoticeService.java:214`；`service/inventory/impl/PurchaseServiceImpl.java:4538`；`service/yield/impl/YieldCalculationServiceImpl.java:283、:371、:398`；`service/yield/CostReconcileService.java:108、:311` | 字面比较 | 写法不同即判不同（误拒；在 Yield/CostReconcile 里会进入"跨单位"分支，见 §2.1） | 袋≠bag |

小结：同一个问题（两个单位是不是同一个）在读到的范围内至少有 **7 种不同语义**，融合时单位相等判断必须统一，否则同一张单据在不同页面会得出不同结论。

### 2.5 引用了 UnitContractService 但本次未逐处读到的文件

以下文件在 `grep UnitContractService` 中出现，未读上下文，**未判定**，迁移前需逐个读：`ai/tool/impl/dataop/ProductionPlanCreateTool`、`ai/tool/impl/factorycatalog/SalesDeliveryBatchAllocateTool`、`blueprint/*`（BomCompileRequestAssembler、FactoryBuildApplyService、FactoryRuleValidator 等 10 余个）、`controller/inventory/SalesController`、`controller/ProductionWarehouseReceiptMobileController`、`entity/bom/BomRecipeItem`、`entity/ProductionBatch`、`service/bom/BomWorkflowRevisionService`、`service/bom/impl/BomItemSubstituteServiceImpl`、`service/bom/impl/BomSeasoningWorkspaceServiceImpl`、`service/datasuspect/*`、`service/finance/*Return*`、`service/inventory/ReturnedGoodsCostResolver`、`service/inventory/ReturnOrderLineageService`、`service/material/*`、`service/pot/PotNodeIndex`、`service/processsecret/ProcessSecretExternalService`、`service/product/impl/ProductPackagingSpecServiceImpl`、`service/productimport/SkuImportServiceImpl`、`service/production/*`（PlanCostVarianceService 之外）、`service/purchase/impl/InquiryQuoteServiceImpl`、`service/quality/*`、`service/scheduling/*`、`service/shortage/ShortageCaseActionService`、`service/impl/ProductTypeServiceImpl`、`service/impl/SystemEnumServiceImpl`；以及引用 `gramsPerUnit`/"kg" 字面量但未读的 `LaborEfficiencyServiceImpl`、`EquipmentServiceImpl`（多半是误命中，如 1000ms，需确认）。
`MaterialUomConverter` 本体只读到 1-80 行，`toComparableQuantity` 的后续分支和 `isWriteUnitCompatible` 未读；`ProductionPlanServiceImpl.java:1350、:1497` 两处 `toComparableQuantity` 调用对结果的处理未读。

## 3. 统一入口设计草案（仅设计）

### 3.1 原则

1. **引擎不动，收敛调用**：保留 `UnitContractService` 为唯一换算事实源；新增一层薄的"业务换算门面"，把"失败怎么办"统一成**三种显式策略**，调用点不再自己写 `if (x == null) return quantity`。
2. 与原型 `convertQuantity` 对齐：默认策略是**失败即抛业务异常**；需要"可比较但不强求"的地方显式选 `tryConvert`，拿到 `Optional.empty()` 自己负责展示成"不可比"，不允许兜底成原值或 0。
3. 一个事实一份数据：`ProductType.gramsPerUnit` 与 `product_unit_conversions` 两份克重事实，门面内部只读换算图；`gramsPerUnit` 经 `ProductSpecificationConversionSyncService` 投射到图（已存在，service/unit/ProductSpecificationConversionSyncService.java），迁移完成后 `FgQuantityUnitConverter` 等直读 `gramsPerUnit` 的地方改为读图。
4. 单位"是否同一个"也走同一处，且明确区分**身份相等**（只≠个≠件）与**匹配容忍**（可折叠），两个方法名分开，不再各写各的。

### 3.2 类名、包名、方法签名

放在现有 `service/unit/` 包下（与引擎同包，不新增顶层包）。

```java
package com.cretas.aims.service.unit;

/** 业务换算门面。所有库存/采购/调拨/收货/BOM/生产/成本的数量单位换算只允许经过这里。 */
public interface QuantityConversionGateway {

    /** 失败抛 UnitConversionException。数量为 null 抛 IllegalArgumentException（调用方先判空）。 */
    BigDecimal convertOrThrow(ConversionRequest request);

    /** 失败返回 empty，附原因；用于"不可比就显示不可比"的报表/看板，调用方必须自己处理 empty。 */
    ConversionOutcome tryConvert(ConversionRequest request);

    /** 带版本快照的换算：收货、调拨、发货这类"沿用当时快照，不用当前档案重算"的场景。 */
    BigDecimal convertWithSnapshotOrThrow(ConversionRequest request, ConversionSnapshot snapshot);

    /** 身份相等：只≠个≠件，袋≡bag。用于过闸、幂等、落库前比较。 */
    boolean sameUnitIdentity(String factoryId, String left, String right);

    /** 匹配容忍：个/只/件折叠，框/筐折叠。仅用于 BOM 槽位、工艺端口这类"匹配"，禁止用于数量换算。 */
    boolean sameUnitForMatching(String factoryId, String left, String right);

    /** 落库字面值，直接委托 UnitContractService.storageUnit。 */
    String storageUnit(String factoryId, String rawUnit);
}

public record ConversionRequest(
        String factoryId,          // 必填
        String subjectId,          // 产品或物料 ID；同量纲换算可为 null，计数↔质量必填
        SubjectKind subjectKind,   // PRODUCT | MATERIAL（消除现在 productTypeId 槽位里混放 materialTypeId 的歧义）
        BigDecimal quantity,
        String fromUnit,
        String toUnit,
        UnitUsageScene scene,      // 复用现有枚举
        LocalDateTime at,          // 业务时间，决定生效版本；禁止默认 now() 悄悄代替业务日期
        Integer scale,
        RoundingMode roundingMode) {}

public record ConversionOutcome(
        Optional<BigDecimal> quantity,
        UnitConversionStatus status,     // 复用现有枚举
        String reasonCode,               // 稳定码，如 UNIT_CONVERSION_MISSING / UNIT_UNKNOWN / UNIT_AMBIGUOUS
        String message,                  // 人话，含两端单位与产品名
        List<UnitConversionStep> steps,  // 复用现有，用于成本/追溯留痕
        String conversionRefId,
        Long conversionVersion) {}
```

- `ConversionSnapshot`：收货/调拨已有字段 `purchasePackageUnitSnapshot / inventoryBaseUnitSnapshot / packageToBaseFactorSnapshot`（PurchaseServiceImpl.java:3624-3664、PurchaseReturnCapacityService.java:229-244）、`packagingUnit/BaseUnit/Factor`（FgQuantityUnitConverter.convertWithPackaging :139-166）归并成一个值对象，门面内部优先用快照、再退到引擎。
- 实现类：`service/unit/impl/QuantityConversionGatewayImpl`，只依赖 `UnitContractService`；不做 `@Autowired(required=false)`（现在大量服务把换算器写成可选注入，缺失时静默跳过，如 MaterialBatchServiceImpl.java:633 fail-open；门面必须是构造器必需依赖，缺失就启动失败）。

### 3.3 异常类型

新增 `com.cretas.aims.exception.UnitConversionException extends BusinessException`（沿用 `BusinessException(status, message).withCode().withHint()` 的现有形态，exception 包下现有类）：

| reasonCode | HTTP | 对应 UnitConversionStatus |
|---|---|---|
| `UNIT_UNKNOWN` | 422 | UNKNOWN_UNIT |
| `UNIT_DIMENSION_INCOMPATIBLE` | 422 | INCOMPATIBLE_DIMENSION |
| `UNIT_CONVERSION_MISSING` | 409 | PRODUCT_CONVERSION_MISSING（hint 指向"在产品资料登记换算/克重"） |
| `UNIT_CONVERSION_AMBIGUOUS` | 409 | AMBIGUOUS_CONVERSION |
| `UNIT_SNAPSHOT_REQUIRED` | 409 | 无快照且单位不同（现有 `PURCHASE_RECEIPT_CONVERSION_SNAPSHOT_REQUIRED` 迁入） |

现有业务码（`MATERIAL_UOM_UNCONFIGURED`、`SEASONING_BASIS_UNIT_NOT_CONVERTIBLE`、`PURCHASE_...` 等）迁移期保留，由调用点用 `catch` 或门面参数 `errorCodeOverride` 转写，避免前端按 code 分支的地方一次性全变。

### 3.4 与已有实体/表的关系

- `product_unit_conversions`（ProductUnitConversion，字段 factory_id/product_type_id/from_unit_code/to_unit_code/factor/effective_from/effective_to/version）：门面的**唯一**产品级换算数据源。`SubjectKind.PRODUCT` 直接用；`SubjectKind.MATERIAL` 目前引擎是借 `productTypeId` 槽位传物料 ID 去查 `material_packaging_specs`（UnitContractServiceImpl.java:353-380），门面把这个隐式借用显式化为 `SubjectKind`，引擎内部先不改，只在门面分流。
- 物料装箱规格（`material_packaging_specs`、`MaterialPackagingHierarchy.level1PerLevel2`）：留在引擎的 `convertViaMaterialPackagingHierarchy`；`MaterialUomConverter` 的 ABACA_SKIP（抄码料无确定箱重）语义要保留为门面的第四种结果 `ABACA_SKIP`（`ConversionOutcome.status` 需扩枚举，或另给 `isAbaca` 标记），不能并进"失败"。
- `ProductType.gramsPerUnit` / `netContentQuantity`：保留为输入字段，由 `ProductSpecificationConversionSyncService.synchronize` 投射成换算图行；**迁移的验收条件**：对线上每个 gramsPerUnit 非空的产品，换算图里能查到 kg↔计数单位行，否则 `FgQuantityUnitConverter` 等迁移后会从"有克重能算"变成"图里没有而拒绝"。这条要用真实数据读数（只读 SQL）验证，**本次未做**。
- `UnitOfMeasurement`（工厂自定义单位，由 `factoryCatalog` 读入）：门面不碰，继续在引擎内解析。
- `UnitConversionService`（旧门面）：在迁移完成前保留为只读壳，内部转调新门面的 `tryConvert`；删除 `convertOrSame`；`context()` 里的 null factoryId/productTypeId 是它"永远不能换计数↔质量"的根因，新门面要求显式传入。
- 对前端的契约：`controller/UnitContractController.java` 的 `POST /api/mobile/{factoryId}/units/convert`（:61）保持，内部改走门面，响应里增加 `reasonCode`。

### 3.5 迁移散点的顺序

按"静默出错的危害 × 是否在库存/账务红线域"排序，每一步独立 PR，前一步绿了再下一步：

1. **先堵静默 1:1 / 默认 kg（红线域优先）**
   - ① `MaterialBatchCreateTool.java:282-288`（AI 入库，null 当 kg）→ 改 `convertOrThrow` 或失败返回错误给 AI。
   - ② `InventoryPostingServiceImpl.java:341-344、:395-408`（OCR 入库，个/件/只并一）→ 改 `sameUnitIdentity`；注意这会让原来"悄悄过"的单据开始被拦，需要先查近 30 天 OCR 入库里这类单位组合的占比（只读 SQL）。
   - ③ `InterimSettleServiceImpl.java:507`、`ProcessSheetServiceImpl.java:566`、`ProductionPlanServiceImpl.java:5200-5203`、`SupplyChainOrchestrator.java:801`、`FactoryMaterialRequisitionServiceImpl.java:524、:645`：把"单位缺失当 kg"改为"缺失则拒绝并提示补单位"。
   - ④ `CostReconcileService.java:307-318`、`YieldCalculationServiceImpl.java:369-405`：对齐 `YieldReportServiceImpl.java:2621-2651` 的严格实现（出成率先收敛成一份）。
2. **收敛"同一单位"判断**：把 §2.4 的四处 `storageUnit` 拷贝（Transfer/MaterialBatch/Stocktake/Traceability）与 ReturnOrder/PurchaseReturnCapacity 改为门面的 `sameUnitIdentity`；`BomWorkflowRevisionService`、槽位匹配用 `sameUnitForMatching`。这一步不改数量，风险最低，先做可以先暴露语义分歧。
3. **收敛私有 kg/g 表**：`ProductionStockAllocationServiceImpl`、`SettlementSemiStockingService`、`BomRecipeServiceImpl.outputKilograms`、`SeasoningRatioContract`、`OrderCostBreakdownService`、`YieldReportServiceImpl`、`ProductionPlanServiceImpl.5399-5435`、`InventoryPostingServiceImpl.convertJinKg`、监听器与成本 fallback 逐个改为 `tryConvert`/`convertOrThrow`。此时顺带获得吨、斤支持（现在这几处会拒绝吨/斤，如 ProductionStockAllocationServiceImpl.java:1098-1109）。
4. **克重来源一份化**：`FgQuantityUnitConverter`、`FeedUnitConverter`、`RestockUnitConverter`、`SalesOrderPlanQuantityNormalizer` 从读 `gramsPerUnit` 改为读换算图（`SubjectKind.PRODUCT`）；前置条件是 §3.4 的数据核对通过。`convertWithPackaging` 的快照逻辑并入 `convertWithSnapshotOrThrow`。
5. **BOM / 采购 / 收货**：`MaterialUomConverter` 并入门面（保留 ABACA_SKIP），`BomServiceImpl.396-415` 的"非重量透传"在这一步按业务口径处理（见 §4 问题 2）；`PurchaseServiceImpl.conversionFactor`、收货快照逻辑改成门面调用，行为不变。
6. **删除旧入口**：`UnitConversionService.convertOrSame`、本地 switch 兜底分支、`canonicalBomUnit`、`sameDiscreteUnit`、测试专用的 `required=false` 注入；加一条源码闸（AST 级）：业务包内禁止出现 `new BigDecimal("1000")` 与单位字符串 `"kg"`/`"g"` 同函数共现、禁止调用 `UnitContractService.convert` 以外的换算路径（`FgQuantityUnitConverter` 之外），门禁登记进 CI（与 measurement-and-wiring 的"闸必须真执行"要求一致，新增后去 CI 日志 grep 类名确认）。

每一步的验收：对该步触及的每个调用点各留一条"无换算关系时报错/返回 empty"的阴性测试，以及一条"有换算关系时数值与改前一致"的阳性测试（用改前的实测值对照，不引用记忆中的数）；库存/账务类调用点按规则走真实 JPA 上下文（Mockito 不算）。

## 4. 需要 Steve 拍板的口径问题

1. **个/只/件 在数量换算上到底是不是同一个**（`InventoryPostingServiceImpl.java:395-408` 并成一个，引擎与 `TransferServiceImpl.java:596-608` 的 #1976 说不是）。方案 A：全部按"不同"处理，OCR 入库遇到个/件混写就拦，操作员要改单位；方案 B：OCR/导入入口放宽（用户写啥存啥，但不参与数量换算）。现状是 A 与 B 混用，所以同一张单在入库能过、在调拨被拒。
2. **非重量单位的 BOM 行要不要换算到计价单位**（`BomServiceImpl.java:396-415` 透传）。A：改成缺换算就报错（成本更真，但存量配方会开始报错）；B：保持透传并在成本结果上标"含未换算行"（不拦，但数不全）。
3. **单位缺失默认值**：全部拒绝（A，需要补数据），还是保留"产品有单位则用产品单位、产品也没有才报错"的两级回退（B）。现状是各处直接给 `kg`。
4. **无换算关系的批次在发货/FEFO 里被"跳过"**（`SalesServiceImpl.java:5128-5134` 只打 warn）。A：保持跳过，但把"有 N 个批次因单位不可换算被排除"带进库存不足提示；B：直接拦并要求补克重。

## 5. 读取范围与限制

已读函数本体：`UnitContractService` 接口与 `UnitContractServiceImpl` 的 `areEquivalent/convert 前 125 行/isIntrinsicConvertible/systemAliases/canonicalCodeOrRaw/crossLanguageCode/isBuiltInCountingUnit/convertViaMaterialPackagingHierarchy 前 30 行`；`UnitConversionService`、`RestockUnitConverter`、`FeedUnitConverter`、`FgQuantityUnitConverter` 全文；`MaterialUomConverter` 1-80 行；表 §2.1-§2.3 中标"已读"的所有片段。
未做：线上数据读数（没有任何 SQL）、`UnitContractServiceImpl` 中图搜索/循环检测的实现（:480-720）、`UnitOfMeasurement` 自定义单位解析细节。§2.5 的文件未判定。上表的"静默"判断仅基于上述已读代码行，不代表这些路径在生产上真的被走到。
