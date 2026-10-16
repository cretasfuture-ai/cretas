package com.cretas.aims.dto.inventory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/** Remaining received quantity available for physical purchase returns. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseReturnAvailability {
    private String purchaseOrderId;
    private List<Item> items;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private String materialTypeId;
        private String baseUnit;
        private BigDecimal receivedQuantity;
        private BigDecimal returnedQuantity;
        private BigDecimal remainingQuantity;
    }
}
