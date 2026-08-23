package com.cretas.aims.service.finance.impl;

import com.cretas.aims.event.SalesOrderSettledEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * E-10 / #754: 收款结清 → 开票联动.
 *
 * <p>结清事件仍然被观察用于兼容日志，但绝不再创建开票申请。销售订单完成待办是唯一自动开票触发真值。
 */
@Slf4j
@Component
public class PaymentSettledInvoiceListener {

    // Intentionally dependency-free: settlement no longer owns invoice creation.

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSalesOrderSettled(SalesOrderSettledEvent event) {
        if (event == null) {
            log.warn("PaymentSettledInvoiceListener received a null event; no automatic invoice action taken");
            return;
        }
        log.info("PaymentSettledInvoiceListener compatibility no-op: settlement event for salesOrderId={}, " +
                        "factoryId={}, totalPaid={}; automatic invoicing is owned by the completion outbox",
                event.getSalesOrderId(), event.getFactoryId(), event.getTotalPaid());
    }
}
