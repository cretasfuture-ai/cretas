package com.cretas.aims.dto.inventory;

import jakarta.validation.constraints.NotNull;
import lombok.*;
import java.math.BigDecimal;
import java.util.*;

@Data @NoArgsConstructor @AllArgsConstructor
public class WarehouseTransferReceiptRequest {
    @NotNull private UUID requestKey;
    @NotNull private Map<Long, BigDecimal> itemQuantities = new LinkedHashMap<>();
}
