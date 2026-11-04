package com.cretas.aims.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.Set;

/**
 * 采购单进出「在途」口径时触发 (提交 / 审批通过 / 驳回 / 取消 / 少收关闭)。
 * 收货引起的在途变化由 {@link MaterialReceivedEvent} 负责, 这里不重复。
 */
@Getter
public class PurchaseOrderSupplyChangedEvent extends ApplicationEvent {

    private final String factoryId;
    private final String purchaseOrderId;
    private final Set<String> materialTypeIds;

    public PurchaseOrderSupplyChangedEvent(Object source, String factoryId, String purchaseOrderId,
                                           Set<String> materialTypeIds) {
        super(source);
        this.factoryId = factoryId;
        this.purchaseOrderId = purchaseOrderId;
        this.materialTypeIds = Set.copyOf(materialTypeIds);
    }
}
