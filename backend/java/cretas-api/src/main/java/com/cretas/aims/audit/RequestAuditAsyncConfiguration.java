package com.cretas.aims.audit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.ThreadPoolExecutor;

/** Audit traffic must not create an unbounded OS thread per business request. */
@Configuration
public class RequestAuditAsyncConfiguration {
    @Bean(name = "operationAuditExecutor")
    public ThreadPoolTaskExecutor operationAuditExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("operation-audit-");
        // Call sites catch rejection and emit a bounded failure log; never run DB
        // writes in the caller thread or silently discard the audit failure.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
