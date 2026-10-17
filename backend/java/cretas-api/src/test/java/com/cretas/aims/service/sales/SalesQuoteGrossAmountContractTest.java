package com.cretas.aims.service.sales;

import com.cretas.aims.entity.inventory.SalesOrderItem;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class SalesQuoteGrossAmountContractTest {
    @Test void twoPiecesAtGross62Keep124InsteadOfLosingACentBeforeTax() {
        var item = line("2", "54.8673", "13");
        item.setQuotedGrossUnitPrice(new BigDecimal("62"));
        assertEquals(new BigDecimal("109.73"), item.getLineAmount());
        assertEquals(new BigDecimal("124.00"), item.getLineAmountWithTax());
        assertEquals(new BigDecimal("14.27"), item.getLineAmountWithTax().subtract(item.getLineAmount()));
    }
    @Test void aLargeQuotedQuantityDoesNotAccumulateNetPriceConversionError() {
        var item = line("1000", "54.8673", "13"); item.setQuotedGrossUnitPrice(new BigDecimal("62"));
        assertEquals(new BigDecimal("62000.00"), item.getLineAmountWithTax());
    }
    @Test void oldLinesWithoutSnapshotsKeepTheirHistoricalRounding() {
        var item = line("2", "54.8673", "13");
        assertEquals(new BigDecimal("123.99"), item.getLineAmountWithTax());
        item.setDiscountRate(new BigDecimal("10")); assertEquals(new BigDecimal("111.60"), item.getLineAmountWithTax());
    }
    @Test void theGrossSnapshotIsPriceSensitiveAndNotAClientWriteField() throws Exception {
        assertNotNull(SalesOrderItem.class.getDeclaredField("quotedGrossUnitPrice").getAnnotation(com.cretas.aims.security.PriceSensitive.class));
        assertTrue(java.util.Arrays.stream(com.cretas.aims.dto.inventory.CreateSalesOrderRequest.SalesOrderItemDTO.class.getDeclaredFields())
                .noneMatch(f -> f.getName().equals("quotedGrossUnitPrice")));
    }
    @Test void zeroTaxAndHiddenPricesRetainTheirExistingContract() {
        var item = line("2", "54.8673", "0");
        assertEquals(item.getLineAmount(), item.getLineAmountWithTax());
        item.setUnitPrice(null); assertNull(item.getLineAmountWithTax());
        item = line("2", "54.8673", "13"); item.setQuantity(null); assertNull(item.getLineAmountWithTax());
    }
    private SalesOrderItem line(String quantity,String net,String tax) {
        var item = new SalesOrderItem(); item.setQuantity(new BigDecimal(quantity));
        item.setUnitPrice(new BigDecimal(net)); item.setTaxRate(new BigDecimal(tax)); return item;
    }
}
