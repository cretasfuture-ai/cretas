package com.cretas.aims.service.finance.impl;

import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.finance.InvoiceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Durable processor for automatic sales-order invoice todos.
 *
 * <p>Claiming is an atomic repository update with a lease.  Invoice creation then
 * uses the existing pessimistic sales-order lock in {@link InvoiceService}; the two
 * operations are deliberately separate so a failed invoice transaction cannot roll
 * back the claim or the completed delivery.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SalesOrderInvoiceTodoProcessor {

    private static final int DEFAULT_MAX_ATTEMPTS = 5;
    private static final int DEFAULT_BATCH_SIZE = 50;
    private static final int LEASE_MINUTES = 10;

    private final SalesOrderRepository salesOrderRepository;
    private final InvoiceService invoiceService;

    @Value("${cretas.finance.invoice-todo.max-attempts:5}")
    private int maxAttempts = DEFAULT_MAX_ATTEMPTS;

    @Value("${cretas.finance.invoice-todo.batch-size:50}")
    private int batchSize = DEFAULT_BATCH_SIZE;

    /** Process the event immediately after the completion transaction commits. */
    public boolean process(String factoryId, String salesOrderId) {
        return claimAndProcess(factoryId, salesOrderId);
    }

    /** Process due todos, including stale PROCESSING leases after a restart. */
    public int processDueTodos() {
        LocalDateTime now = LocalDateTime.now();
        List<SalesOrder> candidates = salesOrderRepository.findInvoiceTodoCandidates(
                now, effectiveMaxAttempts(), org.springframework.data.domain.PageRequest.of(0, effectiveBatchSize()));
        int processed = 0;
        for (SalesOrder order : candidates) {
            if (claimAndProcess(order.getFactoryId(), order.getId())) {
                processed++;
            }
        }
        return processed;
    }

    private boolean claimAndProcess(String factoryId, String salesOrderId) {
        if (factoryId == null || salesOrderId == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        int claimed = salesOrderRepository.claimInvoiceTodo(
                salesOrderId, factoryId, now, now.plusMinutes(LEASE_MINUTES), effectiveMaxAttempts());
        if (claimed != 1) {
            return false;
        }

        try {
            invoiceService.requestInvoiceFromOrder(factoryId, salesOrderId, null, null,
                    "销售订单全部发货完成，系统自动生成待审核开票记录");
            salesOrderRepository.markInvoiceTodoDone(salesOrderId, factoryId);
            return true;
        } catch (com.cretas.aims.exception.BusinessException e) {
            // Existing active invoice or already-issued amount is an idempotent terminal
            // outcome.  Other business failures remain durable and bounded for retry.
            if (Integer.valueOf(409).equals(e.getCode())) {
                salesOrderRepository.markInvoiceTodoDone(salesOrderId, factoryId);
                log.debug("销售订单开票待办已存在/已开票，标记完成: {}", salesOrderId);
                return true;
            }
            markFailure(factoryId, salesOrderId, e);
            return false;
        } catch (DataIntegrityViolationException e) {
            // Includes a defensive retry path for a concurrent (factory_id, invoice_number)
            // unique-key collision. The durable bounded retry reruns the whole locked request.
            markFailure(factoryId, salesOrderId, e);
            return false;
        } catch (Exception e) {
            markFailure(factoryId, salesOrderId, e);
            return false;
        }
    }

    private void markFailure(String factoryId, String salesOrderId, Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        if (message.length() > 1000) {
            message = message.substring(0, 1000);
        }
        int attempt = effectiveMaxAttempts();
        LocalDateTime next = LocalDateTime.now().plusMinutes(1);
        salesOrderRepository.markInvoiceTodoFailure(salesOrderId, factoryId, next, message, attempt);
        log.warn("销售订单自动开票待办处理失败，保留并等待有界重试: salesOrderId={}, error={}",
                salesOrderId, message);
    }

    private int effectiveMaxAttempts() {
        return maxAttempts > 0 ? maxAttempts : DEFAULT_MAX_ATTEMPTS;
    }

    private int effectiveBatchSize() {
        return batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
    }
}
