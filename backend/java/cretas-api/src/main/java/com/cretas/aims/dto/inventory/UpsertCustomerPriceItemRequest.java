package com.cretas.aims.dto.inventory;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpsertCustomerPriceItemRequest {
    @NotBlank(message = "产品不能为空")
    @Size(max = 191)
    private String productTypeId;

    /** 未税单价, 口径同订单行 unitPrice。 */
    @NotNull(message = "单价不能为空")
    @DecimalMin(value = "0.0001", message = "单价必须大于 0")
    private BigDecimal standardPrice;
}
