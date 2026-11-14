package com.cretas.aims.dto.inventory;

import com.cretas.aims.security.PriceSensitive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 客户档案「价格」页的一行: 该客户的一条专属售价。价格列按权限配置隐藏 (同订单单价)。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CustomerPriceItemView {
    private Long itemId;
    private String priceListId;
    private String priceListName;
    private String productTypeId;
    private String itemName;
    private String unit;
    @PriceSensitive
    private BigDecimal standardPrice;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    /** 今天是否生效 (启用且在有效期内)。 */
    private boolean effective;
}
