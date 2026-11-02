package com.cretas.aims.event.listener;

import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.event.WorkflowTaskProgressRequestedEvent;
import com.cretas.aims.service.processentry.WorkflowTaskProgressWriter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 逐道录入事务里发布 {@link WorkflowTaskProgressRequestedEvent} → 提交后 Workflow 工序任务真的被回写
 * (真监听器 + 真 {@link WorkflowTaskProgressWriter} + 真仓储)。
 *
 * <p>{@link WorkflowTaskProgressEventListener} 本身是同步 AFTER_COMMIT、没有事务注解; 它今天是对的,
 * <b>只</b>因为 {@code WorkflowTaskProgressWriter.stamp} 标了 {@code REQUIRES_NEW}, 挂起了那个已提交
 * 却仍绑在线程上的事务。把那个 {@code REQUIRES_NEW} 改成默认的 REQUIRED, {@code saveAndFlush} 就会在
 * 已结束的事务里 flush 抛 {@code TransactionRequiredException}, 被监听器 catch 成一条 warn ——
 * 报工照常, 任务进度永远停在 PENDING。现有单测 mock 掉了仓储, 看不见这件事; 这一条钉住它。
 *
 * <p>阴性对照: 发布方回滚时不回写; 同一 Workflow 实例的其它节点一行不动。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:workflow_progress_after_commit;MODE=PostgreSQL;"
        + "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=MONTH,YEAR,VALUE")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({WorkflowTaskProgressEventListener.class, WorkflowTaskProgressWriter.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WorkflowTaskProgressEventListenerAfterCommitContractTest {

    @Autowired ApplicationEventPublisher publisher;
    @Autowired PlatformTransactionManager transactionManager;
    @PersistenceContext EntityManager entityManager;

    @Test
    void committedProcessSheetRowStampsTheExactWorkflowTask() {
        Long first = task("F-WF-1", 901L, "node-1", 1);
        Long target = task("F-WF-1", 901L, "node-2", 2);

        tx().executeWithoutResult(s -> publisher.publishEvent(new WorkflowTaskProgressRequestedEvent(
                "F-WF-1", "PLAN-WF-1", 901L, "node-2", 2, new BigDecimal("48"))));

        WorkProcessTask stamped = reload(target);
        assertThat(stamped.getStatus()).as("报工提交后工序任务必须真的回写").isEqualTo(WorkProcessTask.Status.COMPLETED);
        assertThat(stamped.getActualQuantity()).isEqualByComparingTo("48");
        WorkProcessTask other = reload(first);
        assertThat(other.getStatus()).isEqualTo(WorkProcessTask.Status.PENDING);
        assertThat(other.getActualQuantity()).isNull();
    }

    @Test
    void rolledBackProcessSheetRowDoesNotStamp() {
        Long target = task("F-WF-2", 902L, "node-1", 1);

        tx().executeWithoutResult(s -> {
            publisher.publishEvent(new WorkflowTaskProgressRequestedEvent(
                    "F-WF-2", "PLAN-WF-2", 902L, "node-1", 1, BigDecimal.TEN));
            s.setRollbackOnly();
        });

        assertThat(reload(target).getStatus()).isEqualTo(WorkProcessTask.Status.PENDING);
    }

    private Long task(String factoryId, Long instanceId, String nodeId, int order) {
        return tx().execute(s -> {
            WorkProcessTask task = WorkProcessTask.builder()
                    .factoryId(factoryId)
                    .productionBatchId(1L)
                    .workflowInstanceId(instanceId)
                    .workflowNodeId(nodeId)
                    .workProcessId("WP-" + nodeId)
                    .productTypeId("PT-1")
                    .processOrder(order)
                    .status(WorkProcessTask.Status.PENDING)
                    .build();
            entityManager.persist(task);
            entityManager.flush();
            return task.getId();
        });
    }

    private WorkProcessTask reload(Long id) {
        return tx().execute(s -> {
            WorkProcessTask task = entityManager.find(WorkProcessTask.class, id);
            entityManager.detach(task);
            return task;
        });
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }
}
