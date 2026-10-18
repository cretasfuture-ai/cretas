package com.cretas.aims.controller.dto.indicator;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Same completion period and authoritative formulas as the factory actual-flow indicators. */
@Data
@Builder
public class ProductProductionMetricsResponse {
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private String sourceVersion;
    private List<ProductResult> products;

    @Data
    @Builder
    public static class ProductResult {
        private String productTypeId;
        private String productName;
        private int batchCount;
        private List<String> batchNumbers;
        private BigDecimal yieldRate;
        private BigDecimal goodOutputRate;
        private BigDecimal totalCost;
        private BigDecimal unitCost;
        private boolean costMasked;
    }
}
