package com.cretas.aims.dto.inventory;

import com.cretas.aims.security.PriceSensitive;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.List;

/**
 * 采购单逐行三单状态（S5-05/06/07）：每条采购单行的有效收货、退货抵减、已开票与剩余可开票量。
 *
 * <p>数量单位一律是采购单行的单位。任何一项量不出来（单位换算缺失）时对应字段为 null，
 * 并在 {@code blocker} 写明原因 —— 不把「不知道」写成 0。
 */
@Value
@Builder
public class PurchaseInvoiceMatchStatusDTO {
    String purchaseOrderId;
    String purchaseOrderNumber;
    /** 所有可开票行都已被「已核对」发票开满 */
    boolean fullyMatched;
    List<Line> lines;

    @Value
    @Builder
    public static class Line {
        Long purchaseOrderItemId;
        String materialTypeId;
        String materialName;
        String unit;
        BigDecimal orderedQuantity;
        /** 已确认收货单上指向本行的数量合计 */
        BigDecimal receivedQuantity;
        /** 采购退货（未驳回、带货）折算到采购单位 */
        BigDecimal returnedQuantity;
        /** 有效收货 − 退货 */
        BigDecimal invoiceableQuantity;
        /** 待核对 + 已核对发票行（不含「不符」）的开票量 */
        BigDecimal invoicedQuantity;
        /** 已核对发票行开票量 */
        BigDecimal matchedQuantity;
        BigDecimal remainingQuantity;
        @PriceSensitive(permission = PriceSensitive.PURCHASE_OR_FINANCE)
        BigDecimal expectedUnitAmount;
        /** NOT_RECEIVED / UNINVOICED / PARTIAL / INVOICED / BLOCKED */
        String status;
        String blocker;
        List<Receipt> receipts;
    }

    @Value
    @Builder
    public static class Receipt {
        Long receiveItemId;
        String receiveRecordId;
        String receiveNumber;
        BigDecimal receivedQuantity;
        BigDecimal invoicedQuantity;
        BigDecimal remainingQuantity;
    }
}
