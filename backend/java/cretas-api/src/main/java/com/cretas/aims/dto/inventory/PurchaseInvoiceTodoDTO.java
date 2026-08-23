package com.cretas.aims.dto.inventory;

import com.cretas.aims.entity.enums.PurchaseInvoiceStatus;
import com.cretas.aims.security.PriceSensitive;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 完成采购单自动派生的待收票工作项；不是一张伪造的供应商发票。 */
@Value
@Builder
public class PurchaseInvoiceTodoDTO {
    String purchaseOrderId;
    String purchaseOrderNumber;
    String supplierId;
    String supplierName;
    @PriceSensitive
    BigDecimal orderAmount;
    LocalDate orderDate;
    PurchaseInvoiceStatus invoiceStatus;
}
