package com.cretas.aims.dto.inventory;

import lombok.*;
import java.math.BigDecimal;
import java.util.*;
import java.time.LocalDateTime;

@Data @NoArgsConstructor @AllArgsConstructor
public class WarehouseTransferDeliveryDTO {
    private boolean supported;
    private boolean staged;
    private String stage;
    private String transferId;
    private String transferNumber;
    private BigDecimal inTransitQuantity = BigDecimal.ZERO;
    private String inTransitUnit;
    private Map<String, BigDecimal> inTransitByUnit = new LinkedHashMap<>();
    private List<Item> items = new ArrayList<>();
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Item {
        private Long id; private String itemName; private String unit; private String sourceBatchId;
        private BigDecimal shippedQuantity; private BigDecimal receivedQuantity; private BigDecimal pendingQuantity;
        private List<Receipt> receipts = new ArrayList<>();
    }
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Receipt {
        private String requestKey; private String operationId; private String targetBatchId; private BigDecimal quantity; private String unit;
        private Long actorId; private LocalDateTime createdAt;
    }
}
