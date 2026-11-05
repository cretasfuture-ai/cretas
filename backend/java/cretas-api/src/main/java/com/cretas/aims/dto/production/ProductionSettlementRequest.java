package com.cretas.aims.dto.production;

import com.cretas.aims.entity.enums.DefectCountOutcome;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionSettlementRequest {

    @NotBlank(message = "idempotencyKey is required")
    private String idempotencyKey;

    /**
     * false = 只预览, 不写任何数据; true = 结单 (D-S315-02)。
     *
     * <p>true 时生产事实以报工为准, 在结单事务内重算: {@code actualFinishedQuantity} 与报工不一致
     * 直接拒绝, 不静默替换。报工里没有的 {@code actualSemiFinishedQuantity} 按传入的量结单, 半成品入半成品库。
     */
    @Builder.Default
    private boolean confirm = false;

    /**
     * 人工补录: 文员处理完阻塞项后, 按手工录入的事实结单 (web「部分数据需人工核对补全」那条路)。
     * 只在 {@code confirm=true} 时写库; Workflow 计划的产出与领用仍以报工为准。
     * 需要 production:read_write 和 {@link #manualOverrideReason}, 标记、原因、操作人都落在结单上。
     */
    @Builder.Default
    private boolean manualOverride = false;

    /** 人工补录的原因, {@code manualOverride=true} 时必填。 */
    @jakarta.validation.constraints.Size(max = 255, message = "manualOverrideReason 不能超过 255 字")
    private String manualOverrideReason;

    @PositiveOrZero(message = "actualFinishedQuantity must be >= 0")
    private BigDecimal actualFinishedQuantity;

    @Builder.Default
    @PositiveOrZero(message = "actualSemiFinishedQuantity must be >= 0")
    private BigDecimal actualSemiFinishedQuantity = BigDecimal.ZERO;

    private String quantityUnit;
    private String quantityVarianceReason;
    private String quantityVarianceNote;
    private String materialVarianceReason;
    private String materialVarianceNote;
    /**
     * 不良品清点结论（三态）。
     *
     * <p>⚠️ 允许为 {@code null}（旧客户端 / 尚未升级的入口）—— 那时按 NOT_COUNTED 处理，
     * <b>落库仍写 null 而不是 0</b>。⛔ 不许把「没回答」翻译成「零不良」，那正是
     * V20260826_05/06 刚清掉的东西。</p>
     */
    private DefectCountOutcome defectCountOutcome;

    /**
     * 不良品数量。语义由 {@link #defectCountOutcome} 决定，服务端会校验二者一致：
     * COUNTED 要求 &gt; 0；NONE_CONFIRMED 要求为空或 0；NOT_COUNTED 要求为空。
     */
    @PositiveOrZero(message = "不良品数量不能为负数")
    private BigDecimal defectQuantity;

    private String laborDeferredReason;

    @Valid
    @Builder.Default
    private List<ConsumptionLine> rawMaterialConsumptions = new ArrayList<>();

    @Valid
    @Builder.Default
    private List<ConsumptionLine> semiFinishedConsumptions = new ArrayList<>();

    @Valid
    @Builder.Default
    private List<ConsumptionLine> auxiliaryConsumptions = new ArrayList<>();

    @Valid
    @Builder.Default
    private List<LaborSegment> laborSegments = new ArrayList<>();

    /** Server-derived terminal outputs, keyed by SKU + batch + unit. */
    @Builder.Default
    private List<OutputLine> terminalOutputs = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OutputLine {
        private String productTypeId;
        private String batchNumber;
        private BigDecimal quantity;
        private String unit;
        /** Exact Workflow material node that produced this terminal batch. */
        private String materialNodeId;
        /** Exact Workflow output port used for the process report. */
        private String workflowPortId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConsumptionLine {
        private String materialBatchId;
        private Long semiFinishedInventoryId;
        /**
         * Legacy row-level product context. Workflow plans validate the exact
         * material-node/input-port against the pinned BOM family instead;
         * intermediate WIP identity must never be treated as a terminal owner.
         */
        private String productTypeId;
        private String materialTypeId;
        private String batchNumber;
        /** Exact raw-material Cell from the submitted pinned Workflow row. */
        private String workflowMaterialNodeId;
        /** Exact consuming-process input port from the submitted pinned Workflow row. */
        private String workflowInputPortId;

        @NotNull(message = "quantity is required")
        @Positive(message = "quantity must be > 0")
        private BigDecimal quantity;

        private String unit;
        private String warehouseId;
        private String note;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LaborSegment {
        private Long workerId;
        private String workerName;
        private String workType;

        @NotNull(message = "minutes is required")
        @Positive(message = "minutes must be > 0")
        private Integer minutes;

        @Builder.Default
        @Positive(message = "headcount must be > 0")
        private Integer headcount = 1;

        @PositiveOrZero(message = "hourlyRate must be >= 0")
        private BigDecimal hourlyRate;

        @PositiveOrZero(message = "laborCost must be >= 0")
        private BigDecimal laborCost;

        private String note;
    }
}
