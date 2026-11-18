package com.cretas.aims.dto.inventory;

import com.cretas.aims.security.PriceSensitive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 跨客户的客户价目表一行 (客户 × 产品): 专属价 + 最近成交价 + 平均成交价 (G7)。
 * 价格列按权限配置隐藏 (同订单单价); 没有成交历史时成交价字段为 null, 不返回 0。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CustomerPriceOverviewRow {
    private String customerId;
    private String customerName;
    private Long itemId;
    private String priceListId;
    private String priceListName;
    private String productTypeId;
    private String productCode;
    private String productName;
    private String specification;
    private String unit;
    @PriceSensitive
    private BigDecimal standardPrice;
    /** 最近一次成交单价 (按订单日倒序); 从未成交为 null。 */
    @PriceSensitive
    private BigDecimal latestTradedPrice;
    private LocalDate latestTradedDate;
    /** 历史成交单价的简单平均 (历史表无数量, 不加权); 从未成交为 null。 */
    @PriceSensitive
    private BigDecimal averageTradedPrice;
    private long tradedCount;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    /** 价目表启用且在有效期内。 */
    private boolean effective;
}
