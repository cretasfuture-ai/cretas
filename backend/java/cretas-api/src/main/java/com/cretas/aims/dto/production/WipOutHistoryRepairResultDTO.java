package com.cretas.aims.dto.production;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** Auditable result of a WIP OUT history repair. */
@Data
@AllArgsConstructor
public class WipOutHistoryRepairResultDTO {
    private Long reversalLogId;
    private List<Item> items;

    @Data
    @AllArgsConstructor
    public static class Item {
        private Long originalOutTxnId;
        private Long reverseTxnId;
        private Long semiFinishedId;
        private BigDecimal quantity;
        private BigDecimal beforeProduced;
        private BigDecimal afterProduced;
        private BigDecimal beforeConsumed;
        private BigDecimal afterConsumed;
        private BigDecimal beforeAvailable;
        private BigDecimal afterAvailable;
        private String beforeStatus;
        private String afterStatus;
        private boolean idempotent;
    }
}
