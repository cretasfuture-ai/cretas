package com.cretas.aims.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;

/**
 * 销售订单全部发货完成事件。
 *
 * <p>只在订单第一次进入 {@code COMPLETED} 时发布。开票待办监听器在主事务提交后处理，
 * 避免开票异常回滚仓储发货。</p>
 */
@Getter
public class SalesOrderCompletedEvent extends ApplicationEvent {

    private final String factoryId;
    private final String salesOrderId;
    private final LocalDateTime completedAt;

    public SalesOrderCompletedEvent(Object source, String factoryId, String salesOrderId) {
        super(source);
        this.factoryId = factoryId;
        this.salesOrderId = salesOrderId;
        this.completedAt = LocalDateTime.now();
    }
}
