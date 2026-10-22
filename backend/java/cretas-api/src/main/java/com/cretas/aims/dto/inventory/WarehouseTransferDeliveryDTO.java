package com.cretas.aims.dto.inventory;

import lombok.*;
import java.math.BigDecimal;
import java.util.*;

@Data @NoArgsConstructor @AllArgsConstructor
public class WarehouseTransferDeliveryDTO {
    private boolean supported;
    private String stage;
    private String transferId;
    private String transferNumber;
    private BigDecimal inTransitQuantity = BigDecimal.ZERO;
    private List<Item> items = new ArrayList<>();
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Item {
        private Long id; private String itemName; private String unit; private String sourceBatchId;
        private BigDecimal shippedQuantity; private BigDecimal receivedQuantity; private BigDecimal pendingQuantity;
        private List<Receipt> receipts = new ArrayList<>();
    }
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Receipt {
        private String operationId; private String targetBatchId; private BigDecimal quantity; private String unit;
    }
}
