package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.inventory.*;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import com.cretas.aims.service.finance.impl.InvoiceServiceImpl;
import com.cretas.aims.entity.finance.InvoiceRecord.TaxBreakdownEntry;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;

class QuoteShipmentMoneyContractTest {
    private BigDecimal shipped(String actual, String snapshot, Long reference) {
        SalesOrderItem line = new SalesOrderItem();
        line.setId(901L);
        line.setQuantity(new BigDecimal("2"));
        line.setUnit("个");
        line.setUnitPrice(new BigDecimal("54.8673"));
        line.setTaxRate(new BigDecimal("13"));
        line.setQuotedGrossUnitPrice(snapshot == null ? null : new BigDecimal(snapshot));
        SalesOrder order = new SalesOrder();
        order.setItems(List.of(line));
        SalesDeliveryItem item = new SalesDeliveryItem();
        item.setSalesOrderItemId(reference);
        item.setUnitPrice(new BigDecimal("54.8673"));
        item.setDeliveredQuantity(new BigDecimal(actual));
        SalesDeliveryRecord record = new SalesDeliveryRecord();
        record.setItems(List.of(item));
        SalesServiceImpl service = Mockito.mock(SalesServiceImpl.class, Mockito.CALLS_REAL_METHODS);
        return ReflectionTestUtils.invokeMethod(service, "taxInclusiveShippedValue", order, record);
    }

    @Test void fullShipmentUsesApprovedGrossSnapshot() {
        assertThat(shipped("2", "62", 901L)).isEqualByComparingTo("124.00");
    }
    @Test void partialShipmentUsesActualQuantityAndApprovedSnapshot() {
        assertThat(shipped("1", "62", 901L)).isEqualByComparingTo("62.00");
        assertThat(shipped("1000", "62", 901L)).isEqualByComparingTo("62000.00");
    }
    @Test void historicalNullSnapshotPreservesExistingRounding() {
        assertThat(shipped("2", null, 901L)).isEqualByComparingTo("123.99");
    }
    @Test void unrelatedLineCannotBorrowQuoteSnapshot() {
        assertThat(shipped("2", "62", 902L)).isEqualByComparingTo("109.73");
    }

    private TaxBreakdownEntry invoice(String delivered, String snapshot) {
        SalesOrderItem line = new SalesOrderItem();
        line.setQuantity(new BigDecimal("2"));
        line.setDeliveredQuantity(new BigDecimal(delivered));
        line.setUnitPrice(new BigDecimal("54.8673"));
        line.setTaxRate(new BigDecimal("13"));
        line.setQuotedGrossUnitPrice(snapshot == null ? null : new BigDecimal(snapshot));
        InvoiceServiceImpl service = Mockito.mock(InvoiceServiceImpl.class, Mockito.CALLS_REAL_METHODS);
        List<TaxBreakdownEntry> entries = ReflectionTestUtils.invokeMethod(service, "aggregateByTaxRate", List.of(line));
        return entries.getFirst();
    }
    @Test void automaticInvoiceMatchesFullShipmentGrossAndTax() {
        TaxBreakdownEntry entry = invoice("2", "62");
        assertThat(entry.getTaxableAmount()).isEqualByComparingTo("109.73");
        assertThat(entry.getTaxAmount()).isEqualByComparingTo("14.27");
        assertThat(entry.getTaxableAmount().add(entry.getTaxAmount())).isEqualByComparingTo("124.00");
    }
    @Test void automaticInvoiceUsesActualPartialQuantity() {
        TaxBreakdownEntry entry = invoice("1", "62");
        assertThat(entry.getTaxableAmount().add(entry.getTaxAmount())).isEqualByComparingTo("62.00");
    }
    @Test void automaticInvoiceDoesNotAccumulateNetReversalRounding() {
        TaxBreakdownEntry entry = invoice("1000", "62");
        assertThat(entry.getTaxableAmount().add(entry.getTaxAmount())).isEqualByComparingTo("62000.00");
    }
    @Test void automaticInvoicePreservesHistoricalNullSnapshot() {
        TaxBreakdownEntry entry = invoice("2", null);
        assertThat(entry.getTaxAmount()).isEqualByComparingTo("14.26");
        assertThat(entry.getTaxableAmount().add(entry.getTaxAmount())).isEqualByComparingTo("123.99");
    }
}
