package com.cretas.aims.dto.inventory;

import com.cretas.aims.security.PriceSensitive;
import com.cretas.aims.service.inventory.TradeCaliberSettings;

import java.math.BigDecimal;
import java.util.Map;

/** HTTP projections of the ledger statistics, including the existing compatibility keys. */
public final class CounterpartyStatisticsResponse {
    private CounterpartyStatisticsResponse() {
    }

    public record Customer(
            String customerId, String customerName, String type, String industry, Integer rating,
            @PriceSensitive BigDecimal creditLimit,
            @PriceSensitive BigDecimal currentBalance,
            @PriceSensitive BigDecimal creditAvailable,
            Boolean isActive, Long committedOrderCount,
            @PriceSensitive BigDecimal contractAmount,
            @PriceSensitive BigDecimal averageOrderValue,
            String lastOrderDate,
            @PriceSensitive BigDecimal pendingShipmentAmount,
            @PriceSensitive BigDecimal pendingInvoiceAmount,
            @PriceSensitive BigDecimal pendingCollection,
            @PriceSensitive BigDecimal receivedAmount,
            Long totalOrders,
            @PriceSensitive BigDecimal totalSales, TradeCaliberSettings.LedgerMode counterpartyLedgerScopeMode) {

        public static Customer from(Map<String, Object> statistics) {
            return new Customer((String) statistics.get("customerId"), (String) statistics.get("customerName"),
                    (String) statistics.get("type"), (String) statistics.get("industry"),
                    (Integer) statistics.get("rating"), (BigDecimal) statistics.get("creditLimit"),
                    (BigDecimal) statistics.get("currentBalance"), (BigDecimal) statistics.get("creditAvailable"),
                    (Boolean) statistics.get("isActive"), (Long) statistics.get("committedOrderCount"),
                    (BigDecimal) statistics.get("contractAmount"), (BigDecimal) statistics.get("averageOrderValue"),
                    (String) statistics.get("lastOrderDate"), (BigDecimal) statistics.get("pendingShipmentAmount"),
                    (BigDecimal) statistics.get("pendingInvoiceAmount"), (BigDecimal) statistics.get("pendingCollection"),
                    (BigDecimal) statistics.get("receivedAmount"), (Long) statistics.get("totalOrders"),
                    (BigDecimal) statistics.get("totalSales"),
                    (TradeCaliberSettings.LedgerMode) statistics.get(TradeCaliberSettings.LEDGER_KEY));
        }
    }

    public record Supplier(
            String supplierId, String supplierName, Integer rating, Boolean isActive, Long committedOrderCount,
            @PriceSensitive(permission = PriceSensitive.PURCHASE_OR_FINANCE) BigDecimal creditLimit,
            @PriceSensitive(permission = PriceSensitive.PURCHASE_OR_FINANCE) BigDecimal currentBalance,
            @PriceSensitive(permission = PriceSensitive.PURCHASE) BigDecimal purchaseAmount,
            @PriceSensitive(permission = PriceSensitive.PURCHASE) BigDecimal pendingReceiptAmount,
            @PriceSensitive(permission = PriceSensitive.PURCHASE) BigDecimal pendingInvoiceAmount,
            @PriceSensitive(permission = PriceSensitive.PURCHASE_OR_FINANCE) BigDecimal pendingPayable,
            @PriceSensitive(permission = PriceSensitive.PURCHASE_OR_FINANCE) BigDecimal paidAmount,
            Long totalOrders,
            @PriceSensitive(permission = PriceSensitive.PURCHASE) BigDecimal totalAmount,
            Number averageDeliveryDays, Number onTimeDeliveryRate,
            TradeCaliberSettings.LedgerMode counterpartyLedgerScopeMode) {

        public static Supplier from(Map<String, Object> statistics) {
            return new Supplier((String) statistics.get("supplierId"), (String) statistics.get("supplierName"),
                    (Integer) statistics.get("rating"), (Boolean) statistics.get("isActive"),
                    (Long) statistics.get("committedOrderCount"), (BigDecimal) statistics.get("creditLimit"),
                    (BigDecimal) statistics.get("currentBalance"), (BigDecimal) statistics.get("purchaseAmount"),
                    (BigDecimal) statistics.get("pendingReceiptAmount"), (BigDecimal) statistics.get("pendingInvoiceAmount"),
                    (BigDecimal) statistics.get("pendingPayable"), (BigDecimal) statistics.get("paidAmount"),
                    (Long) statistics.get("totalOrders"), (BigDecimal) statistics.get("totalAmount"),
                    (Number) statistics.get("averageDeliveryDays"), (Number) statistics.get("onTimeDeliveryRate"),
                    (TradeCaliberSettings.LedgerMode) statistics.get(TradeCaliberSettings.LEDGER_KEY));
        }
    }
}
