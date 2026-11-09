package com.cretas.aims.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 一张生产计划的成本差异 (食品 DAG 缺口 09, Steve 2026-11-03 裁定)。
 *
 * <p>标准成本 = 计划锁定配方版本的标准用量 × 开计划那一刻的物料单价快照。差异拆成
 * 价格差 + 用量差 + 替代料差 + 人工差 + 回料差。</p>
 *
 * <p>回料 (半成品回投, 食品 DAG 缺口 09 补齐, 2026-11-09): 从别的计划的回料池回投的量, 按回料池单位成本计价
 * (与成本核算 {@code OrderCostBreakdownService} 的 SFI 投料成本同源), 来源批次取追溯里先进先出归属写下的边。
 * 配方不含回料时标准回料用量就是 0, 回料差 = 回投金额 (它顶替的原料体现为主料用量差的节约)。
 * 本计划自己产出又回投的回料是内部转移 (原料已在本计划领用里算过), 列出数量, 不另计。</p>
 *
 * <p>合计 ({@link #standardCost} / {@link #actualCost} / 各分项差) <b>只加完全可比的那几项</b>,
 * 所以 {@code 价格差 + 用量差 + 替代料差 + 人工差 = 实际 − 标准} 恒成立; 无法比较的项逐条列在
 * {@link #lines} 里并计入 {@link #incomparableItemCount}, ⛔ 不按 0 计入合计。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanCostVarianceDTO {

    public static final String STATUS_COMPARABLE = "COMPARABLE";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_INCOMPARABLE = "INCOMPARABLE";
    /** 回料: 本计划自产自用, 内部转移, 不进合计。 */
    public static final String STATUS_INTERNAL = "INTERNAL";

    /** 计划级回料状态: 没有回投回料。 */
    public static final String REWORK_NONE = "NONE";
    /** 回料全部可比, 回料差已计入合计。 */
    public static final String REWORK_COMPARABLE = "COMPARABLE";
    /** 只有本计划自产自用的回料 (内部转移, 不另计)。 */
    public static final String REWORK_INTERNAL_ONLY = "INTERNAL_ONLY";
    /** 至少一项回料无法比较 (未计入合计)。 */
    public static final String REWORK_INCOMPARABLE = "INCOMPARABLE";
    public static final String INCOMPARABLE_LABEL = "无法比较";

    private String planId;
    private String planNumber;
    private String productTypeId;
    private String productName;
    private String planStatus;

    /** 至少有一项完全可比时为 true。 */
    private boolean comparable;
    /** 不可比 (或只部分可比) 的总说明。 */
    private String comparabilityReason;
    /** 开计划时快照的时间; null = 这张计划没有快照。 */
    private LocalDateTime snapshotCapturedAt;
    private List<String> pinnedBomRecipeIds;

    private BigDecimal outputQuantity;
    private String outputUnit;
    private BigDecimal outputKg;

    /** 可比部分的标准成本。 */
    private BigDecimal standardCost;
    /** 可比部分的实际成本 (与 standardCost 同口径同范围)。 */
    private BigDecimal actualCost;
    /** 实际 − 标准 (正 = 超支)。 */
    private BigDecimal totalVariance;
    /** totalVariance / standardCost × 100。 */
    private BigDecimal varianceRate;

    private BigDecimal priceVariance;
    private BigDecimal usageVariance;
    private BigDecimal substituteVariance;
    private BigDecimal laborVariance;
    /**
     * 回料差 (实际回投金额 − 标准回料金额), 只含可比的回料项; null = 没有可比的回料项
     * (没有回料 / 只有内部转移 / 无法比较, 看 {@link #reworkStatus}), ⛔ 不是 0。
     */
    private BigDecimal reworkVariance;
    /** NONE / COMPARABLE / INTERNAL_ONLY / INCOMPARABLE。 */
    private String reworkStatus;
    /** 回投回料的量 (按回料池单位分列, 如 "1.0000 kg"); 没有回料时 null。 */
    private String reworkQuantityText;
    /** 本计划产出、转给别的计划或留在池里的回料量 (产出方转出, 按池价记负); 没有时 null。 */
    private String reworkOutputQuantityText;
    /** 回料口径说明 (给人看的话): 回投了多少、按什么价、怎么计入。 */
    private String recoveredMaterialNote;

    private int comparableItemCount;
    private int incomparableItemCount;
    /** 客供料不计我方成本, 不参与比较的条数。 */
    private int customerSuppliedItemCount;
    /** 价格被脱敏 (无采购价查看权限): 单价与逐项金额为 null。 */
    private boolean priceMasked;

    @Builder.Default
    private List<Line> lines = new ArrayList<>();
    /** 多产出工序用的成本分摊方法 (缺口 10: 报表注明用的是哪种分法)。 */
    @Builder.Default
    private List<AllocationMethod> allocationMethods = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {
        /** MATERIAL / SUBSTITUTE / UNPLANNED / LABOR / REWORK (回投) / REWORK_OUTPUT (回料产出转出)。 */
        private String lineType;
        private String materialTypeId;
        private String materialName;
        private String materialCategory;
        /** 替代料: 它替的主料。 */
        private String parentMaterialTypeId;
        private String priceUnit;
        /** 开计划时的单价快照; null = 当时没有单价。 */
        private BigDecimal snapshotUnitPrice;
        private BigDecimal standardQuantity;
        private BigDecimal standardAmount;
        private BigDecimal actualQuantity;
        private BigDecimal actualUnitPrice;
        private BigDecimal actualAmount;
        private BigDecimal priceVariance;
        private BigDecimal usageVariance;
        private BigDecimal substituteVariance;
        private BigDecimal laborVariance;
        /** 回料行: 实际回投金额 − 标准回料金额。 */
        private BigDecimal reworkVariance;
        /** 回料行: 回料池 (常驻半成品库存号)。 */
        private String reworkPoolNo;
        /** 回料行: 单价从哪来 (给人看的话)。 */
        private String priceSource;
        /** 回料行: 来源合计与回投量对不上 / 没有来源边时的提示 (只做展示, 不影响可比性)。 */
        private String reworkSourcesNote;
        /** 回料行: 先进先出归属到的产出批次 (追溯边)。 */
        @Builder.Default
        private List<ReworkSource> reworkSources = new ArrayList<>();
        /** COMPARABLE / PARTIAL / INCOMPARABLE / INTERNAL (回料内部转移)。 */
        private String status;
        /** 不可比 / 部分可比的原因 (给人看的话)。 */
        @Builder.Default
        private List<String> reasons = new ArrayList<>();
    }

    /** 回料的一段来源: 这么多量是哪个生产批次 (哪张计划) 产出的 —— 与追溯 (REWORK_POOL_FIFO 边) 同一份数据。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReworkSource {
        private String producerBatchNumber;
        private String producerPlanId;
        private String producerPlanNumber;
        private BigDecimal quantity;
        private String unit;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AllocationMethod {
        private String processCode;
        private Integer processOrder;
        /** 报工落库的分摊依据: MASS / QUANTITY / FIXED_RATIO / EXPLICIT_RATIO (可带 +BYPRODUCT_NRV)。 */
        private String basis;
        /** 给人看的分法: 按重量 / 按数量 / 固定比例 / 报工时手填比例 …。 */
        private String basisLabel;
        @Builder.Default
        private List<AllocationOutput> outputs = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AllocationOutput {
        private String productTypeId;
        private String productName;
        private String batchNumber;
        private BigDecimal costAllocationRatio;
        /** 工艺版本上的固定份额; 与 costAllocationRatio 不同 = 报工时改过。 */
        private BigDecimal workflowFixedCostShare;
        private BigDecimal allocatedCost;
    }
}
