package com.cretas.aims.audit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Dedicated, bounded pool for platform data-access rows. Separate from
 * {@code operationAuditExecutor} so a burst of platform GETs (a dashboard fires dozens)
 * cannot starve mutation auditing, and vice versa.
 */
@Configuration
public class PlatformDataAccessLogAsyncConfiguration {

    public static final String EXECUTOR = "platformDataAccessLogExecutor";

    @Bean(name = EXECUTOR)
    public ThreadPoolTaskExecutor platformDataAccessLogExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(2000);
        executor.setThreadNamePrefix("platform-access-log-");
        // Never run the DB write on the request thread; the writer catches the rejection
        // and logs the full row at ERROR (no silent drop).
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
