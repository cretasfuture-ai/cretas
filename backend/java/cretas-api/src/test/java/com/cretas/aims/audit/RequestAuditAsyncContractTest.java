package com.cretas.aims.audit;

import com.cretas.aims.entity.datacenter.OperationLog;
import com.cretas.aims.repository.datacenter.OperationLogRepository;
import com.cretas.aims.service.datacenter.OperationLogService;
import com.cretas.aims.service.datacenter.impl.OperationLogServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RequestAuditAsyncContractTest {
    @Configuration @EnableAsync static class AsyncTestConfiguration {}

    @Test void boundedPoolRejectsExcessWork() throws Exception {
        ThreadPoolTaskExecutor executor = new RequestAuditAsyncConfiguration().operationAuditExecutor();
        executor.initialize();
        CountDownLatch release = new CountDownLatch(1);
        Runnable held = () -> { try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } };
        try {
            for (int i = 0; i < 258; i++) executor.execute(held);
            assertThatThrownBy(() -> executor.execute(held)).isInstanceOf(TaskRejectedException.class);
            assertThat(executor.getPoolSize()).isLessThanOrEqualTo(2);
            assertThat(executor.getThreadPoolExecutor().getQueue().size()).isLessThanOrEqualTo(256);
        } finally { release.countDown(); executor.shutdown(); }
    }

    @Test void realSpringProxyUsesDedicatedAuditExecutor() throws Exception {
        OperationLogRepository repository = mock(OperationLogRepository.class);
        CountDownLatch saved = new CountDownLatch(1);
        AtomicReference<String> thread = new AtomicReference<>();
        when(repository.save(any())).thenAnswer(call -> {
            thread.set(Thread.currentThread().getName()); saved.countDown(); return call.getArgument(0);
        });
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(OperationLogRepository.class, () -> repository);
            context.register(AsyncTestConfiguration.class, RequestAuditAsyncConfiguration.class, OperationLogServiceImpl.class);
            context.refresh();
            context.getBean(OperationLogService.class).record(OperationLog.builder().factoryId("F006").build());
            assertThat(saved.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(thread.get()).startsWith("operation-audit-");
        }
    }
}
