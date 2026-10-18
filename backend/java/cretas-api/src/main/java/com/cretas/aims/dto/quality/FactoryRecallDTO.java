package com.cretas.aims.dto.quality;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** API contract for a factory-scoped, single production-batch recall. */
public final class FactoryRecallDTO {

    private FactoryRecallDTO() {
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        @NotNull
        private Long batchId;
        @NotBlank
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CustomerAckRequest {
        @NotBlank
        private String shipmentId;
        @NotBlank
        private String note;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReturnRequest {
        @NotBlank
        private String shipmentId;
        @NotNull
        @DecimalMin(value = "0.0001")
        @Digits(integer = 15, fraction = 4)
        private BigDecimal quantity;
        @NotBlank
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CloseRequest {
        private String note;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecallSummary {
        private Long id;
        private String eventCode;
        private Long batchId;
        private String batchNumber;
        private String productName;
        private String status;
        private String reason;
        private LocalDateTime completedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecallView {
        private Long id;
        private String eventCode;
        private Long batchId;
        private String batchNumber;
        private String productName;
        private String unit;
        private BigDecimal remainingInventory;
        private String status;
        private String reason;
        private LocalDateTime completedAt;
        private Boolean canClose;
        private List<Blocker> blockers;
        private List<ShipmentView> shipments;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ShipmentView {
        private String shipmentId;
        private String shipmentNumber;
        private String customerName;
        private BigDecimal quantity;
        private String unit;
        private BigDecimal returnedQuantity;
        private BigDecimal pendingReturnQuantity;
        private BigDecimal remainingReturnQuantity;
        private Boolean acknowledged;
        private List<ReturnOrderSummary> returnOrders;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReturnOrderSummary {
        private String id;
        private String returnNumber;
        private String status;
        private BigDecimal quantity;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Blocker {
        private String code;
        private String message;
        private String shipmentId;
        private BigDecimal quantity;
    }

    /** Read-only post-completion progress; deliberately contains no money or balance fields. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FollowUpView {
        private Long recallId;
        private Long batchId;
        private String batchNumber;
        private String unit;
        private Boolean recallCompleted;
        private Boolean inventoryResolved;
        private Boolean receivableResolved;
        private Boolean completed;
        private List<FollowUpIssue> issues;
        private List<FollowUpStock> stock;
        private List<FollowUpReturn> returns;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FollowUpIssue {
        private String code;
        private String message;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FollowUpStock {
        private String finishedGoodsBatchId;
        private String batchNumber;
        private String origin;
        private String unit;
        private BigDecimal remainingQuantity;
        private BigDecimal disposalQuantity;
        private Boolean evidenceComplete;
        private List<DisposalSummary> disposals;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DisposalSummary {
        private Long id;
        private String status;
        private BigDecimal quantity;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FollowUpReturn {
        private String returnOrderId;
        private String returnNumber;
        private String status;
        private BigDecimal quantity;
        private String receivableStatus;
        private List<AdjustmentSummary> adjustments;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AdjustmentSummary {
        private String id;
        private String transactionNumber;
        private String approvalStatus;
    }
}
