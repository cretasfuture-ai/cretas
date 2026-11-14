package com.cretas.aims.dto.inventory;

import com.cretas.aims.security.PriceSensitive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** 客户 × 产品最近成交价。unitPrice 走 @PriceSensitive: 无价格查看权限者后端置 null (原先是不脱敏的 Map.of)。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CustomerPriceMemoryView {
    @PriceSensitive
    private BigDecimal unitPrice;
    private String sourceOrderNumber;
    private String sourceSalesOrderId;
    private String orderDate;
}
