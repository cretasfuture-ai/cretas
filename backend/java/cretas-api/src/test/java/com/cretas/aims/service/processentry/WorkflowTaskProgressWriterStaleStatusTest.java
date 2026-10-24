package com.cretas.aims.service.processentry;

import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.event.WorkflowTaskProgressRequestedEvent;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * 评审 #1143: {@link WorkflowTaskProgressWriter#stamp} 先不加锁地按 Workflow 实例列出任务 (实体进入一级缓存),
 * 再用 FOR UPDATE 按 id 取同一条 —— Hibernate 返回的是缓存里那个<b>旧实例</b>, 不会用数据库新值覆盖。
 * 任务没有 {@code @Version} / {@code @DynamicUpdate}, saveAndFlush 是全列 UPDATE
 * ⇒ 两次查询之间别人提交的 status 被写回旧值。
 *
 * <p>复现方式 (确定性, 不靠线程赛跑): 用一个委托给真仓储的 Mockito 包装, 在<b>不加锁的列表查询返回之后</b>,
 * 另起线程、独立事务把 status 改成 IN_PROGRESS 并提交 (join 等它结束), 正好落在「定位」与「加锁」之间。
 * 真 JPA + H2, 写回器用独立事务跑 (与生产 REQUIRES_NEW 同形)。
 *
 * <p>任务初始 actualQuantity=10、计划下没有逐道行 ⇒ 重算结果 null ⇒ 实体确实变脏 ⇒ 确实发出 UPDATE
 * (否则旧写法也不会写库, 测试会假绿)。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:workflow_progress_stale_status;MODE=PostgreSQL;"
        + "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=MONTH,YEAR,VALUE")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WorkflowTaskProgressWriterStaleStatusTest {

    private static final String FACTORY = "F-STALE-1";

    @Autowired WorkProcessTaskRepository taskRepository;
    @Autowired ProcessSheetRowRepository rowRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbcTemplate;
    @PersistenceContext EntityManager entityManager;

    @Test
    void statusCommittedBetweenLocateAndLockIsNotWrittenBack() {
        Long taskId = tx().execute(s -> {
            WorkProcessTask task = WorkProcessTask.builder()
                    .factoryId(FACTORY)
                    .productionBatchId(1L)
                    .workflowInstanceId(701L)
                    .workflowNodeId("node-1")
                    .workProcessId("WP-node-1")
                    .productTypeId("PT-1")
                    .processOrder(1)
                    .status(WorkProcessTask.Status.PENDING)
                    .actualQuantity(new BigDecimal("10"))
                    .build();
            entityManager.persist(task);
            entityManager.flush();
            return task.getId();
        });

        AtomicInteger concurrentCommits = new AtomicInteger();
        WorkProcessTaskRepository wrapped =
                mock(WorkProcessTaskRepository.class, delegatesTo(taskRepository));
        doAnswer(inv -> {
            List<WorkProcessTask> located = taskRepository.findByFactoryIdAndWorkflowInstanceIdOrderByProcessOrderAsc(
                    inv.getArgument(0), inv.getArgument(1));
            commitStatusFromAnotherTransaction(taskId, "IN_PROGRESS");
            concurrentCommits.incrementAndGet();
            return located;
        }).when(wrapped).findByFactoryIdAndWorkflowInstanceIdOrderByProcessOrderAsc(any(), any());

        WorkflowTaskProgressWriter writer = new WorkflowTaskProgressWriter(
                wrapped, rowRepository,
                JsonMapper.builder().findAndAddModules()
                        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build(),
                entityManager);

        TransactionTemplate requiresNew = tx();
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNew.executeWithoutResult(s -> writer.stamp(new WorkflowTaskProgressRequestedEvent(
                FACTORY, "PLAN-NO-ROWS", 701L, "node-1", 1, null, "port-main", false)));

        // 观测量: 并发提交确实发生在「定位」与「加锁」之间 (否则下面的断言什么也证明不了)
        assertThat(concurrentCommits.get()).as("并发提交注入次数").isEqualTo(1);
        String statusInDb = jdbcTemplate.queryForObject(
                "SELECT status FROM work_process_tasks WHERE id = ?", String.class, taskId);
        BigDecimal actualInDb = jdbcTemplate.queryForObject(
                "SELECT actual_quantity FROM work_process_tasks WHERE id = ?", BigDecimal.class, taskId);
        assertThat(actualInDb).as("写回器确实写了库 (10 → null), 不是没发 UPDATE").isNull();
        assertThat(statusInDb)
                .as("别人在定位与加锁之间提交的状态不许被写回旧值 PENDING")
                .isEqualTo("IN_PROGRESS");
    }

    /** 另起线程 + 独立连接/事务提交, 模拟另一个请求; join 后才返回 (确定性)。 */
    private void commitStatusFromAnotherTransaction(Long taskId, String status) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                tx().executeWithoutResult(s -> jdbcTemplate.update(
                        "UPDATE work_process_tasks SET status = ? WHERE id = ?", status, taskId));
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        other.start();
        other.join(10_000);
        if (other.isAlive()) {
            throw new IllegalStateException("并发事务 10 秒没提交完 (被锁住?)");
        }
        if (failure.get() != null) {
            throw new IllegalStateException("并发事务失败", failure.get());
        }
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }
}
