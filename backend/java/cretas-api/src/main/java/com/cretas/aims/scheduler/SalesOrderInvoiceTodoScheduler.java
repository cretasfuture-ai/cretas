package com.cretas.aims.scheduler;

import com.cretas.aims.service.finance.impl.SalesOrderInvoiceTodoProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Compensates durable invoice todos after transient failures or a process restart. */
@Slf4j
@Component
@RequiredArgsConstructor
public class SalesOrderInvoiceTodoScheduler {

    private final SalesOrderInvoiceTodoProcessor processor;

    @Scheduled(fixedDelayString = "${cretas.finance.invoice-todo.fixed-delay-ms:60000}")
    @SchedulerLock(name = "SalesOrderInvoiceTodoScheduler.processDueTodos",
            lockAtMostFor = "PT5M", lockAtLeastFor = "PT1S")
    public void processDueTodos() {
        try {
            int count = processor.processDueTodos();
            if (count > 0) {
                log.info("销售订单自动开票待办补偿处理完成: count={}", count);
            }
        } catch (Exception e) {
            log.error("销售订单自动开票待办补偿扫描失败", e);
        }
    }
}
