package com.cretas.aims.dto.inventory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/** Read-only, non-financial source and returned-stock projection for a return order. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReturnOrderLineageDTO {

    private String returnOrderId;
    private String returnNumber;
    private List<Line> lines;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {
        private Long returnOrderItemId;
        private String sourceFinishedGoodsBatchId;
        private String sourceBatchNumber;
        private String productTypeId;
        private String unit;
        private BigDecimal quantity;
        private String sourceStatus;
        private List<ReturnedStock> returnedStock;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReturnedStock {
        private String id;
        private String batchNumber;
        private String unit;
        private BigDecimal remainingQuantity;
        private String status;
    }
}
