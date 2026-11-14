package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.inventory.SalesOrderItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 订单行价格来源: 编辑重建行 / 复制订单不能丢来源 (独立审查 中3)。 */
class SalesOrderPriceSourceContractTest {

    private static SalesOrderItem line(String price, String source, String ref) {
        SalesOrderItem it = new SalesOrderItem();
        it.setUnitPrice(price == null ? null : new BigDecimal(price));
        it.setPriceSource(source);
        it.setPriceRef(ref);
        return it;
    }

    @Test
    void editKeepingSamePriceKeepsSourceAndRef() {
        SalesOrderItem previous = line("12.4956", "CUSTOMER", "客户专属价目");
        SalesOrderItem rebuilt = line("12.4956", null, null);
        SalesServiceImpl.applyPriceSourceOnUpdate(rebuilt, previous);
        assertEquals("CUSTOMER", rebuilt.getPriceSource());
        assertEquals("客户专属价目", rebuilt.getPriceRef());
    }

    @Test
    void editChangingPriceBecomesManual() {
        SalesOrderItem previous = line("12.4956", "CUSTOMER", "客户专属价目");
        SalesOrderItem rebuilt = line("10.0000", null, null);
        SalesServiceImpl.applyPriceSourceOnUpdate(rebuilt, previous);
        assertEquals("MANUAL", rebuilt.getPriceSource());
        assertNull(rebuilt.getPriceRef());
    }

    @Test
    void newLineOrLegacyLineWithoutSourceIsManualNotKeptBlank() {
        SalesOrderItem added = line("9.0000", null, null);
        SalesServiceImpl.applyPriceSourceOnUpdate(added, null);
        assertEquals("MANUAL", added.getPriceSource());
        SalesOrderItem legacyPrev = line("9.0000", null, null);
        SalesOrderItem rebuilt = line("9.0000", null, null);
        SalesServiceImpl.applyPriceSourceOnUpdate(rebuilt, legacyPrev);
        assertEquals("MANUAL", rebuilt.getPriceSource());
    }

    @Test
    void quoteOrderLineKeepsQuoteSource() {
        SalesOrderItem rebuilt = line("8.0000", null, null);
        rebuilt.setQuotedGrossUnitPrice(new BigDecimal("9.04"));
        SalesServiceImpl.applyPriceSourceOnUpdate(rebuilt, line("8.0000", "CUSTOMER", "x"));
        assertEquals("QUOTE", rebuilt.getPriceSource());
    }

    @Test
    void copyCarriesSourceAndRef() {
        SalesOrderItem copy = new SalesOrderItem();
        SalesServiceImpl.copyPriceSource(line("12.4956", "LAST_DEAL", "SO-1"), copy);
        assertEquals("LAST_DEAL", copy.getPriceSource());
        assertEquals("SO-1", copy.getPriceRef());
    }
}
