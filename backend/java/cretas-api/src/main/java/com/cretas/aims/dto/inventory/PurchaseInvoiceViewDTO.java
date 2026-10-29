package com.cretas.aims.dto.inventory;

import com.cretas.aims.entity.inventory.PurchaseInvoice;
import com.cretas.aims.security.PriceSensitive;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 采购收票列表行，补齐发票实体本身没有冗余保存的采购单号与供应商名称。 */
@Value
@Builder
public class PurchaseInvoiceViewDTO {
    String id;
    String purchaseOrderId;
    String purchaseOrderNumber;
    String supplierId;
    String supplierName;
    String invoiceNumber;
    @PriceSensitive(permission = PriceSensitive.PURCHASE_OR_FINANCE)
    BigDecimal amount;
    String reconcileStatus;
    String reconcileNote;
    String ocrRawResult;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
    /** 逐行三单：发票行及其差异；空列表 = 这张票没有行级资料 */
    List<PurchaseInvoiceLineViewDTO> lines;

    public static PurchaseInvoiceViewDTO from(PurchaseInvoice invoice, String purchaseOrderNumber,
                                              String supplierName) {
        return from(invoice, purchaseOrderNumber, supplierName, List.of());
    }

    public static PurchaseInvoiceViewDTO from(PurchaseInvoice invoice, String purchaseOrderNumber,
                                              String supplierName, List<PurchaseInvoiceLineViewDTO> lines) {
        return PurchaseInvoiceViewDTO.builder()
                .lines(lines)
                .id(invoice.getId())
                .purchaseOrderId(invoice.getPurchaseOrderId())
                .purchaseOrderNumber(purchaseOrderNumber)
                .supplierId(invoice.getSupplierId())
                .supplierName(supplierName)
                .invoiceNumber(invoice.getInvoiceNumber())
                .amount(invoice.getAmount())
                .reconcileStatus(invoice.getReconcileStatus())
                .reconcileNote(invoice.getReconcileNote())
                .ocrRawResult(invoice.getOcrRawResult())
                .createdAt(invoice.getCreatedAt())
                .updatedAt(invoice.getUpdatedAt())
                .build();
    }
}
