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
     * Normal settlement contract: the caller only confirms that the plan should be closed.
     * When true, the service ignores client-supplied production facts and re-derives them
     * from submitted process reports inside the settlement transaction.
     */
    @Builder.Default
    private boolean confirm = false;

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
