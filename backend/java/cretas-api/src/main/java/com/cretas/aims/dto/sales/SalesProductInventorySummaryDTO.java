package com.cretas.aims.dto.sales;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Read-only available inventory total used by the sales order product picker. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesProductInventorySummaryDTO {
    private String productTypeId;
    private BigDecimal availableQuantity;
    private String unit;
}
