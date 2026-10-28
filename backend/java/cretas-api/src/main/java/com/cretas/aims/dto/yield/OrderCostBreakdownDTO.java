package com.cretas.aims.dto.yield;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单级单盒成本拆分 — 单一权威服务输出 (T1 批次谱系遍历 + T2 上游成本回溯).
 *
 * <p>原料成本 = 沿 {@link com.cretas.aims.entity.MaterialConsumption} 边递归回溯到上游各批次的
 * 实测成本之和 (混批按实测投料量×各自单价精确归集, 非按重量糊平均); 人工/调料/包装来自该批
 * {@code ProductionReport} 逐道报工。单盒成本 = 总成本 ÷ 盒数。
 *
 * <p>价格脱敏: 无 {@code procurement:price:view} 权限时所有金额字段返回 null (masked=true),
 * 仅保留投料量/占比结构 (与 MaterialConsumptionController 一致的红线策略)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCostBreakdownDTO {

    private String orderId;
    /** Business order number (for example SO-20260720-0001), distinct from source order UUID. */
    private String orderNumber;
    private String productionPlanId;
    private String productionPlanNumber;
    private String finishedBatchNumber;
    private String productSku;
    private String productName;
    private String pinnedBomRecipeId;
    private Integer pinnedBomVersion;
    private Long pinnedWorkflowId;
    private Integer pinnedWorkflowVersion;
    private LocalDateTime calculatedAt;
    /** COMPLETE / PARTIAL / NO_DATA. */
    private String calculationStatus;
    private BigDecimal outputQuantity;
    private String outputUnit;
    private BigDecimal netWeightGramsPerUnit;
    private BigDecimal convertedOutputKg;
    private BigDecimal costDenominatorQuantity;
    private String costDenominatorUnit;
    /** 产出盒数 (Σ 成品批次 quantity)。 */
    private Integer boxCount;

    // ---- 金额 (masked 时为 null) ----
    /** 原料成本 = 上游各批 traced 成本之和 (混批闭环)。 */
    private BigDecimal rawMaterialCost;
    private BigDecimal laborCost;
    private BigDecimal seasoningCost;
    private BigDecimal packagingCost;
    private BigDecimal equipmentCost;
    private String equipmentCostStatus;
    private BigDecimal otherCost;
    private String otherCostStatus;
    /**
     * 外部库存投料成本 (半成品 SFI + 成品 FG 作投料; R4 2026-07-04)。
     *
     * <p>SFI/FG 投料边 <b>不写</b> {@link com.cretas.aims.entity.MaterialConsumption} (投料的是常驻外部
     * 库存, 非本计划在制 WIP), 故原料谱系遍历 {@code traceCost} 看不到它们 → 此前 totalCost 对 SFI/FG
     * 投料成本失明, 与小结算的 {@code FinishedGoodsBatch.unitCost} (含 SFI/FG 投料) 差一个投料桶。此字段
     * 补齐: Σ(feedInSourceUnit × 输入 SFI/FG.unitCost), 与小结 {@code computeOutputUnitCost} 同口径同源服务。
     *
     * <p>null = 该批无 SFI/FG 投料 (常规生产, 零回归) 或 脱敏 或 {@link #costComplete}=false (成本不完整)。
     */
    private BigDecimal semiFeedCost;
    private BigDecimal totalCost;
    private BigDecimal perBoxCost;
    /**
     * 已知成本合计 = 当前已采集到的各成本桶之和, {@link #costComplete}=false 时<b>也照常给值</b>
     * (2026-07-30 Steve 拍板: 设备/其他成本不强制填写, 填了就算进去)。
     *
     * <p>⚠️ 与 {@link #totalCost} 的分工必须守住:
     * <ul>
     *   <li>{@link #totalCost} = <b>完整</b>成本, 缺任一项即 null。成品入库 unitCost / COGS / 毛利
     *       只认它 —— 用不完整的数写这些账会系统性低估成本、虚增毛利。</li>
     *   <li>本字段 = <b>展示用</b>的已知合计, 永远伴随 {@link #calculationStatus} 与
     *       {@link #missingCostItems} 一起呈现, 让读数的人知道它不含什么。</li>
     * </ul>
     * 脱敏时置 null。
     */
    private BigDecimal knownCostSubtotal;
    /** 已知成本合计 ÷ 产出盒数; 口径同 {@link #knownCostSubtotal} (不完整也给值)。 */
    private BigDecimal knownPerBoxCost;

    // ---- 副产回收 (肥油/料头等可变现副产物; masked 时为 null) ----
    /** 副产回收价值 = Σ 各道报工副产物(quantity×单价); 无单价的副产物按 0 (诚实, 不臆造). */
    private BigDecimal byproductCredit;
    /** 净成本 = 总成本 − 副产回收 (副产物变现冲减主产品成本)。 */
    private BigDecimal netTotalCost;
    /** 单盒净成本 = 净成本 ÷ 盒数。 */
    private BigDecimal netPerBoxCost;
    /** 副产物明细 (跨批次跨道按 名称+单位 归集)。 */
    private List<ByproductLine> byproducts;

    // ---- 留样/料头 (AUDIT-006) ----
    /** 留样数 (产出但不可售; Σ各道, 通常仅末道)。物理量, 不脱敏。 */
    private Integer sampleRetainCount;
    /** 损耗/料头量 (仅展示; 已体现在出成率, 不二次扣成本; 无则 null)。物理量, 不脱敏。 */
    private BigDecimal wasteQuantity;
    /** 可售盒数 = 产出盒数 − 留样数。物理量, 不脱敏。 */
    private Integer sellableBoxCount;
    /** 单盒可售成本 = 净成本 ÷ 可售盒数 (留样成本由售出盒承担; masked 时 null)。 */
    private BigDecimal sellablePerBoxCost;

    /** 包装明细 (AUDIT-002: 膜/气体/标签/其他, 按名称归集; 各项 cost 之和=packagingCost; masked 时 cost null)。 */
    private List<PackagingItem> packagingDetail;

    /** 辅料按锅分摊 (AUDIT-004: 一锅辅料被多批共用时按产出量分摊到本批; masked 时金额 null)。 */
    private List<AuxiliaryAllocation> auxiliaryAllocations;

    /** 价格是否被脱敏 (无价格查看权限)。 */
    private boolean priceMasked;
    /** 遍历是否完整 (无该订单批次/无消耗边时 false, 诚实空)。 */
    private boolean hasData;
    /**
     * 成本是否完整 (R4 2026-07-04, 诚实 null)。false = 存在<b>未计价</b>的 SFI/FG 投料
     * (输入半成品/成品 unitCost 未知, 或计数单位来源缺每盒克重无法折算) → 整批产出成本未知,
     * {@code totalCost/perBoxCost/netTotalCost/netPerBoxCost/sellablePerBoxCost/semiFeedCost} 全置 null
     * (与小结 {@code computeOutputUnitCost} 诚实 null 同口径, 绝不伪造 ¥0 稀释)。已知的
     * {@code rawMaterialCost/laborCost/seasoningCost/packagingCost} 仍保留展示。默认 true。
     */
    @Builder.Default
    private boolean costComplete = true;

    /** Number and identities of unknown cost inputs; never represented as zero. */
    private Integer missingCostItemCount;
    private List<String> missingCostItems;

    /**
     * 🔴 客供主料成本口径 (a)（owner 裁定，2026-09-04）：来料加工下，客供
     * ({@code MaterialBatch.ownership == CUSTOMER_OWNED}) 主料<b>显式记 0</b>（不计入我方原料成本，
     * 成品成本 = 辅料 + 包材 + 工时），而<b>不是</b>「缺价、算不出」。这份清单区分「已知是 0（因为
     * 是客供）」和「不知道」（后者仍进 {@link #missingCostItems} 的 {@code RAW_MATERIAL_PRICE:…}）——
     * 两者都表现为单价缺失，但含义完全相反（本仓形态 A¹⁰：兜底的默认值会把「我不知道」翻译成
     * 「是 0」；这里反过来，这个 0 是真实值，不是兜底）。
     *
     * <p>{@code CODE:批次号} 形态，与 {@link #missingCostItems} 同一套约定。
     * ⛔ 公司自有（{@code COMPANY_OWNED}）批次缺价<b>不</b>进这份清单——那仍然是真的不知道，
     * 仍然进 {@link #missingCostItems} 并阻断 {@link #costComplete}。
     */
    private Integer customerSuppliedItemCount;
    private List<String> customerSuppliedItems;

    /**
     * 🔴 辅料先估 (owner 裁定, 2026-09-06)：已结单计划的辅料/调料
     * （{@code bom_seasoning_items} 那一族）如果<b>一条实际耗用都没有</b>，就按
     * 「BOM 配比 × 实际主料投入」折算成本，并把每一条标上
     * {@code 按配比估算}（{@link EstimatedAuxItem#getBasis()}）。
     *
     * <p>这些金额<b>已经计入</b> {@link #seasoningCost} / {@link #totalCost} —— 它是成本，
     * 不是缺项。⛔ 但必须让读数的人看见它是估的：本仓形态 A¹³「每条读数都要带来源标记」，
     * 否则「估出来的」和「实际领用的」在同一个 {@code seasoningCost} 里长得一模一样。
     *
     * <p>⛔ 有实际耗用记录的辅料<b>不</b>估（{@code quantity=0} 的记录也算记录 ——
     * 0 是「这次没用」这个明确事实，不是「还不知道」）；配比或单价缺失的<b>不</b>按 0 计，
     * 走 {@link #missingCostItems} 的 {@code ESTIMATED_AUX_RATIO_MISSING} /
     * {@code ESTIMATED_AUX_PRICE_MISSING}（本仓形态 A¹⁰：兜底的 0 会被当成真实读数往下传）。
     *
     * <p>masked 时金额字段置 null，保留物理量与标签。
     */
    private Integer estimatedAuxItemCount;
    private List<EstimatedAuxItem> estimatedAuxItems;

    /**
     * 🔴 辅料<b>部分记录</b>留痕（2026-09-06 终审补）：本批<b>有</b>辅料实耗记录，但同一张 BOM 里
     * 还有几种<b>一条记录都没有</b> —— 按「信记录不补差」口径这几种<b>不估</b>（见
     * {@code OrderCostBreakdownService#estimateAuxByBomRatio} 决定 ①-b），于是本批的
     * {@link #seasoningCost} 只含<b>已记录</b>的那几种。
     *
     * <p>⛔ 不说出来就又是一次「静默少算」：成本非 null、{@link #costComplete} 为 true，
     * 看起来完整，实际短了这几种的钱 —— 那正是本轮改动要消灭的东西，不能在这条路上原样活着。
     *
     * <p>{@code AUX_PARTIALLY_RECORDED:<辅料名>} 形态，与 {@link #customerSuppliedItems} 同一套约定。
     * <b>非阻断</b>：它<b>不</b>进 {@link #missingCostItems}，所以「阻断不了」是结构上的保证，
     * 不是靠记得把它登记进 {@code OPTIONAL_COST_ITEM_CODES}。
     *
     * <p>⚠️ 只在「确实有辅料被逐物料记录过」时才产出。调料成本整个来自报工侧一笔总额时
     * <b>不</b>产出 —— 那是「按批次粒度记录」，对逐物料覆盖度无法断言。
     */
    private Integer partiallyRecordedAuxItemCount;
    private List<String> partiallyRecordedAuxItems;

    /** Auditable quantity x price/rate ledgers. */
    private List<RawMaterialDetail> rawMaterialDetails;
    private List<PackagingDetail> packagingDetails;
    private List<LaborDetail> laborDetails;

    /** 上游来源明细 (混批各批次)。 */
    private List<SourceCost> sources;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SourceCost {
        private String batchId;

        /** 报工自动带出 / BOM 自动扣料 (数量 = 配比 × 投入): 工艺保密时按配比类裁剪 (独立审查 V2.1 M4)。 */
        private boolean automatic;
        private String batchName;
        private BigDecimal quantity;
        private String unit;
        /**
         * masked 时 null。约定 (2026-09-04): 客供
         * ({@code priceSource}=CUSTOMER_SUPPLIED 场景, 见 {@link RawMaterialDetail#priceSource})
         * 行也如实展示批次自己记录的价 —— 这是审计记录, ⛔ <b>不</b>代表要计入我方成本；
         * 我方成本口径看 {@link #cost}(客供恒为 0), 不要看这个字段。
         */
        private BigDecimal unitPrice;
        /** masked 时 null (= 该上游链 traced 累计成本)。 */
        private BigDecimal cost;
        /** 按投料量占比 % (scale 1)。 */
        private BigDecimal weightSharePct;
        /** 按成本占比 % (scale 1); masked 时 null。异质单价下 ≠ weightSharePct。 */
        private BigDecimal costSharePct;
        /** 谱系深度 (1=直接上游; >1=多级回溯)。 */
        private Integer depth;
    }

    /** 副产物归集行 (名称+单位 维度跨批次汇总)。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ByproductLine {
        private String name;
        private BigDecimal quantity;
        private String unit;
        /** 单价 (元/单位); 报工未录则 null; masked 时 null。 */
        private BigDecimal unitPrice;
        /** 变现价值 = quantity × unitPrice (报工未录单价则 null/0); masked 时 null。 */
        private BigDecimal value;
    }

    /** 包装明细项 (AUDIT-002: 膜/气体/标签/其他, 按名称跨批次归集)。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PackagingItem {
        private String name;
        /** 来自报工自动带出 / BOM 自动扣料的包材 (金额 = 配比 × 投入 × 单价): 工艺保密时按配比类裁剪。 */
        private boolean automatic;
        /** 该包材项成本 (元); masked 时 null。 */
        private BigDecimal cost;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RawMaterialDetail {
        private String batchNumber;

        /** 报工自动带出 / BOM 自动扣料 (数量 = 配比 × 投入): 工艺保密时按配比类裁剪 (独立审查 V2.1 M4)。 */
        private boolean automatic;
        private String materialCode;
        private String materialName;
        private BigDecimal quantity;
        private String unit;
        /**
         * 约定 (2026-09-04): 客供 ({@code priceSource}=CUSTOMER_SUPPLIED) 行同样保留批次自己
         * 记录的原价 —— 那是客户物料的原值(审计记录), ⛔ <b>不</b>代表这笔要计入我方成本。
         * 我方成本口径看 {@link #amount}(客供恒为 0), 不要拿这个字段乘 quantity 去算钱。
         */
        private BigDecimal unitPrice;
        private String priceSource;
        private BigDecimal amount;
        private String collectionStatus;
        private String missingReason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PackagingDetail {
        private String materialCode;

        /** 报工自动带出 / BOM 自动扣料 (数量 = 配比 × 投入): 工艺保密时按配比类裁剪 (独立审查 V2.1 M4)。 */
        private boolean automatic;
        private String materialName;
        private BigDecimal quantity;
        private String unit;
        private BigDecimal unitPrice;
        private String priceSource;
        private BigDecimal amount;
        private String collectionStatus;
        private String missingReason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LaborDetail {
        private Integer processOrder;
        private String processName;
        private Integer workerCount;
        private String startTime;
        private String endTime;
        private Integer durationMinutes;
        private Integer laborMinutes;
        private BigDecimal laborHours;
        private BigDecimal hourlyRate;
        private String rateSource;
        private BigDecimal amount;
        private String collectionStatus;
        private String missingReason;
    }

    /**
     * 辅料先估的逐条明细 (2026-09-06)。一条 = 一条 {@code bom_seasoning_items} 行在这一批上的估算。
     *
     * <p>形状刻意与 {@link RawMaterialDetail} / {@link PackagingDetail} 对齐
     * (量 × 单价 = 金额 + 来源 + 状态), ⛔ 不新造一套展示约定。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EstimatedAuxItem {
        /** {@code bom_seasoning_items.material_type_id}。 */
        private String materialCode;
        /** 恒为 true: 用量 = 配方 g/kg × 主料投入, 工艺保密时按配比类裁剪 (独立审查 V2.1 M4)。 */
        @Builder.Default
        private boolean automatic = true;
        private String materialName;
        /** 这条估算所依据的 BOM 配方 (批次绑定优先, 见 {@code BatchBomBinding})。 */
        private String bomRecipeId;
        private Integer bomVersion;
        /** 生产批次号 —— 估算是<b>按批</b>做的, 不带批次号就分不清同一物料被估了几次。 */
        private String batchNumber;
        /** BOM 配比 (g 辅料 / kg 投入主料)。物理量, 不脱敏。 */
        private BigDecimal dosagePerKgG;
        /** 估算基数: 本批<b>实际</b>主料投入量 (kg)。物理量, 不脱敏。 */
        private BigDecimal rawInputKg;
        /** 估算耗用量 = dosagePerKgG × rawInputKg ÷ 1000。物理量, 不脱敏。 */
        private BigDecimal quantity;
        /** 恒为 {@code kg} —— 配比与单价两侧的单位都由定义钉死, 见 {@code SeasoningRatioContract}。 */
        private String unit;
        /** ¥/kg; masked 时 null。 */
        private BigDecimal unitPrice;
        /** = quantity × unitPrice (scale 4); masked 时 null。 */
        private BigDecimal amount;
        /** 人话标签, 恒为 {@code 按配比估算} (来源: {@code OrderCostBreakdownService.ESTIMATED_AUX_LABEL})。 */
        private String basis;
        /** {@code ESTIMATED_BY_BOM_RATIO} —— 与 {@code COLLECTED}/{@code MISSING_PRICE} 同一套状态词表。 */
        private String collectionStatus;
        /** 配比读自哪里 (当前恒为 {@code BOM_SEASONING_ITEMS_LIVE})。 */
        private String ratioSource;
        /** 单价读自哪里 ({@code BOM_SEASONING_PRICE_SOURCE1} / {@code ..._SOURCE2})。 */
        private String priceSource;
    }

    /** 辅料按锅分摊 (AUDIT-004)。本批 share = potTotalCost × batchOutput ÷ potTotalOutput。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuxiliaryAllocation {
        private String potNo;
        /** 分摊方式 BY_OUTPUT/FIXED_RATIO。 */
        private String method;
        /** 该锅辅料总成本 (元); masked 时 null。 */
        private BigDecimal potTotalCost;
        /** 该锅总产出量 (用于分摊基数); 物理量, 不脱敏。 */
        private BigDecimal potTotalOutput;
        /** 本批本道产出量; 物理量, 不脱敏。 */
        private BigDecimal batchOutput;
        /** 本批分摊到的辅料成本 (元); masked 时 null。 */
        private BigDecimal batchShare;
        /** 本批分摊占比 % (scale 1); 物理量, 不脱敏。 */
        private BigDecimal batchSharePct;
    }
}
