package com.cretas.aims.dto.finance;

import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.security.PriceSensitive;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** Keeps existing finance JSON keys while exposing monetary fields to response masking. */
public final class ArApQueryResponses {
    private ArApQueryResponses() { }

    @Data
    public static class Overview {
        @PriceSensitive private BigDecimal totalReceivable;
        @PriceSensitive private BigDecimal totalPayable;
        @PriceSensitive private BigDecimal netPosition;
        @PriceSensitive private BigDecimal overdueReceivable;
        @PriceSensitive private BigDecimal overduePayable;
        private Long overdueARCount;
        private Long overdueAPCount;
        private Long payableCount;
        private Long receivableCount;
        private Long monthlyTransactionCount;
        private Long customerCount;
        private Long supplierCount;
        private List<TransactionTypeStat> transactionsByType;
    }

    @Data
    public static class TransactionTypeStat {
        private String type;
        private Long count;
        @PriceSensitive private BigDecimal amount;
    }

    @Data
    public static class Aging {
        private String counterpartyId;
        private String counterpartyName;
        @PriceSensitive private BigDecimal current;
        @PriceSensitive private BigDecimal days1_30;
        @PriceSensitive private BigDecimal days31_60;
        @PriceSensitive private BigDecimal days61_90;
        @PriceSensitive private BigDecimal days91_120;
        @PriceSensitive private BigDecimal days120plus;
        @PriceSensitive private BigDecimal total;
    }

    @Data
    public static class Statement {
        private String counterpartyId;
        private String counterpartyType;
        private String startDate;
        private String endDate;
        @PriceSensitive private BigDecimal openingBalance;
        @PriceSensitive private BigDecimal periodDebit;
        @PriceSensitive private BigDecimal periodCredit;
        @PriceSensitive private BigDecimal closingBalance;
        private Long transactionCount;
        private List<ArApTransaction> transactions;
    }
}
