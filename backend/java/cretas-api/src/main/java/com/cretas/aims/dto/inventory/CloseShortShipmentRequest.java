package com.cretas.aims.dto.inventory;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 销售订单短交结案请求 (2026-09-04 owner 拍板)。
 *
 * <p>只能对 {@code PARTIAL_DELIVERED} 且没有活跃发运单的订单调用；关闭只终止订单剩余
 * 数量的后续发货，不回滚已经确认发出的库存/AR。镜像采购侧
 * {@link ClosePurchaseReceivingTaskRequest}（仓储少收关闭）的结构。
 */
@Data
public class CloseShortShipmentRequest {

    private ReasonCode reasonCode;

    @Size(max = 500, message = "补充说明不能超过500字")
    private String notes;

    public enum ReasonCode {
        /** 客户同意放弃剩余数量（不再补发）。 */
        CUSTOMER_WAIVED_REMAINDER,
        /** 原料/成品已耗尽，短期内无法补齐。 */
        SOURCE_SHORTAGE,
        /** 客户取消了订单的剩余部分。 */
        ORDER_BALANCE_CANCELLED,
        OTHER
    }
}
