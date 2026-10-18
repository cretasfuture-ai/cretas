package com.cretas.aims.service.finance.impl;

import com.cretas.aims.event.SalesOrderCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 销售订单完成后自动创建财务待审核开票记录。
 *
 * <p>创建的是 {@code REQUESTED} 待办，不会自动审核或开具发票。监听发生在发货事务提交后，
 * 因而开票失败不会回滚已完成的仓储发货；409 表示已有记录或订单已开票，是幂等正常路径。</p>
 *
 * <p>{@code NOT_SUPPORTED} 不能去掉：AFTER_COMMIT 回调时，刚提交的发货事务的资源仍绑在线程上，
 * 处理器里的仓储 {@code @Transactional}（REQUIRED）会「加入」这个已结束的事务，认领 UPDATE 抛
 * {@code TransactionRequiredException: no transaction is in progress}，被 Spring 记日志后吞掉，
 * 即时开票从不成功、只剩补偿扫描在开票。挂起之后，认领 / 开票 / 标记各自开新事务，
 * 且仍互相独立（不用 REQUIRES_NEW 包成一个事务，否则开票失败会连认领一起回滚）。
 * 守门测试：{@code SalesOrderInvoiceTodoAfterCommitContractTest}。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SalesOrderCompletedInvoiceListener {

    private final SalesOrderInvoiceTodoProcessor processor;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void onSalesOrderCompleted(SalesOrderCompletedEvent event) {
        // The marker was committed with the delivery.  A failed immediate attempt is
        // therefore recoverable by the scheduler and cannot roll back the delivery.
        processor.process(event.getFactoryId(), event.getSalesOrderId());
    }
}
