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
 * 价格差 + 用量差 + 替代料差 + 人工差; 回料本期不单列。</p>
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
    /**
     * 可比部分的实际成本 (与 standardCost 同口径同范围)。按<b>净额</b>: 结单转入半成品库的那部分成本已按
     * 成本核算 (OrderCostBreakdownService) 的同一比例从各项实际里扣掉, 见 {@link #semiTransferredCost}。
     */
    private BigDecimal actualCost;
    /**
     * 结单转入半成品库、已从实际侧扣掉的成本 (取自成本核算, 不另算); null = 没有划转或价格被脱敏。
     * 划转额未知时整张差异标「不完整」({@code comparable=false}), ⛔ 不当 0。
     */
    private BigDecimal semiTransferredCost;
    /** 实际 − 标准 (正 = 超支)。 */
    private BigDecimal totalVariance;
    /** totalVariance / standardCost × 100。 */
    private BigDecimal varianceRate;

    private BigDecimal priceVariance;
    private BigDecimal usageVariance;
    private BigDecimal substituteVariance;
    private BigDecimal laborVariance;
    /** 回料差异本期不单列的说明。 */
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
        /** MATERIAL / SUBSTITUTE / UNPLANNED / LABOR。 */
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
        /** COMPARABLE / PARTIAL / INCOMPARABLE。 */
        private String status;
        /** 不可比 / 部分可比的原因 (给人看的话)。 */
        @Builder.Default
        private List<String> reasons = new ArrayList<>();
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
