package com.cretas.aims.dto.inventory;

import com.cretas.aims.security.PriceSensitive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** 开单取价结果。price 走 @PriceSensitive: 无价格查看权限的角色后端置 null (不再是不经脱敏的裸 Map)。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PriceLookupResult {
    private boolean found;
    @PriceSensitive
    private BigDecimal price;
    private String source;
    private String priceListName;
}
