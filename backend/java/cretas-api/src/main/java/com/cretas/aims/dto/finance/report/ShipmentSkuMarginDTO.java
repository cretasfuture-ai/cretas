package com.cretas.aims.dto.finance.report;

import com.cretas.aims.security.PriceSensitive;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ShipmentSkuMarginDTO {
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private List<Row> rows;
    @PriceSensitive private BigDecimal totalRevenue;
    @PriceSensitive private BigDecimal totalCost;
    @PriceSensitive private BigDecimal grossProfit;
    @PriceSensitive private BigDecimal marginRate;
    @PriceSensitive private BigDecimal roundingAdjustment;
    private String reconciliationStatus;
    private String reconciliationMessage;
    @PriceSensitive private IncomeStatementDTO financialStatement;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Row {
        private String productTypeId;
        private String productName;
        private String unit;
        private BigDecimal quantity;
        private int shipmentLineCount;
        private int batchCount;
        @PriceSensitive private List<SourceLine> sources;
        @PriceSensitive private BigDecimal revenue;
        @PriceSensitive private BigDecimal rawCost;
        @PriceSensitive private BigDecimal cost;
        @PriceSensitive private BigDecimal grossProfit;
        @PriceSensitive private BigDecimal marginRate;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SourceLine {
        private Long shipmentLineId;
        private String deliveryNumber;
        private String deliveryDate;
        private BigDecimal quantity;
        private String unit;
        @PriceSensitive private BigDecimal revenue;
        @PriceSensitive private BigDecimal rawCost;
        @PriceSensitive private BigDecimal cost;
        private List<BatchSource> batches;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BatchSource {
        private String batchNumber;
        private BigDecimal quantity;
        private String unit;
        @PriceSensitive private BigDecimal unitCost;
        @PriceSensitive private BigDecimal rawCost;
        @PriceSensitive private BigDecimal cost;
    }
}
