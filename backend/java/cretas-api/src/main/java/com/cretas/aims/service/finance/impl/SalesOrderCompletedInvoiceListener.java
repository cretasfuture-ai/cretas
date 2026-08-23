package com.cretas.aims.service.finance.impl;

import com.cretas.aims.event.SalesOrderCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 销售订单完成后自动创建财务待审核开票记录。
 *
 * <p>创建的是 {@code REQUESTED} 待办，不会自动审核或开具发票。监听发生在发货事务提交后，
 * 因而开票失败不会回滚已完成的仓储发货；409 表示已有记录或订单已开票，是幂等正常路径。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SalesOrderCompletedInvoiceListener {

    private final SalesOrderInvoiceTodoProcessor processor;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSalesOrderCompleted(SalesOrderCompletedEvent event) {
        // The marker was committed with the delivery.  A failed immediate attempt is
        // therefore recoverable by the scheduler and cannot roll back the delivery.
        processor.process(event.getFactoryId(), event.getSalesOrderId());
    }
}
