package com.cretas.aims.dto.inventory;

import com.cretas.aims.entity.inventory.PurchaseInvoiceLine;
import com.cretas.aims.security.PriceSensitive;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

/** 发票行视图（财务/采购收票页逐行展示差异）。 */
@Value
@Builder
public class PurchaseInvoiceLineViewDTO {
    Long id;
    Long purchaseOrderItemId;
    Long receiveItemId;
    String materialTypeId;
    BigDecimal quantity;
    String unit;
    @PriceSensitive
    BigDecimal unitPrice;
    @PriceSensitive
    BigDecimal amount;
    @PriceSensitive
    BigDecimal expectedAmount;
    String matchStatus;
    String matchNote;

    public static PurchaseInvoiceLineViewDTO from(PurchaseInvoiceLine line) {
        return PurchaseInvoiceLineViewDTO.builder()
                .id(line.getId())
                .purchaseOrderItemId(line.getPurchaseOrderItemId())
                .receiveItemId(line.getReceiveItemId())
                .materialTypeId(line.getMaterialTypeId())
                .quantity(line.getQuantity())
                .unit(line.getUnit())
                .unitPrice(line.getUnitPrice())
                .amount(line.getAmount())
                .expectedAmount(line.getExpectedAmount())
                .matchStatus(line.getMatchStatus())
                .matchNote(line.getMatchNote())
                .build();
    }
}
