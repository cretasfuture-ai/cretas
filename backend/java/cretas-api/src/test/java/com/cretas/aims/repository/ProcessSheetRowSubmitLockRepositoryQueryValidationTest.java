package com.cretas.aims.repository;

import com.cretas.aims.entity.processentry.ProcessSheetRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 缺口 13 第三轮审查 M-2 —— 正式提交前读已存在的行要加行锁, 并发重放的后到者必须<b>等</b>前一个提交完、
 * 再读到 SUBMITTED (被 409 拒), 而不是两个都读到「还没提交」双双入账。
 *
 * <p>在真实 Hibernate + H2 上用两个独立事务跑: 事务 A 先锁住这一行、把它改成 SUBMITTED，
 * 主线程确认 B 的读取行为后才允许 A 提交；不依赖持锁线程固定 sleep 的调度时机。
 * 事务 B 在 A 持锁期间读同一行。
 * <ul>
 *   <li>B 用加锁查询 ({@code submitRow} 现在用的那条) → 被挡住直到 A 提交, 读到 SUBMITTED;</li>
 *   <li>阴性对照: B 用旧的不加锁查询 → 立刻返回, 读到的仍是 DRAFT (就是双入账的那个竞态)。</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:process_sheet_row_submit_lock;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=MONTH,YEAR,VALUE;LOCK_TIMEOUT=15000")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("正式提交行锁: 并发重放的后到者等前一个提交完再读")
class ProcessSheetRowSubmitLockRepositoryQueryValidationTest {

    private static final String F = "F-PSLOCK";

    @Autowired private ProcessSheetRowRepository rowRepo;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;

    private String planId;

    @BeforeEach
    void seed() {
        planId = "PLAN-LOCK-" + UUID.randomUUID().toString().substring(0, 8);
        new TransactionTemplate(txManager).executeWithoutResult(st -> {
            ProcessSheetRow row = new ProcessSheetRow();
            row.setFactoryId(F);
            row.setPlanId(planId);
            row.setProcessCode("lu");
            row.setProcessOrder(2);
            row.setClientRowId("row-1");
            row.setRowPayload("{}");
            row.setRowStatus("DRAFT");
            row.setSubmissionStatus(ProcessSheetRow.SUBMISSION_DRAFT);
            rowRepo.saveAndFlush(row);
        });
    }

    @AfterEach
    void cleanup() {
        new TransactionTemplate(txManager).executeWithoutResult(st ->
                rowRepo.findByFactoryIdAndPlanId(F, planId).forEach(rowRepo::delete));
    }

    private record Read(String status, long waitedMs) {
    }

    private Read raceWith(boolean lockedReadInB) throws Exception {
        CountDownLatch aHoldsLock = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        CountDownLatch bStarted = new CountDownLatch(1);
        AtomicReference<Long> bSession = new AtomicReference<>();
        try (var threads = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Void> a = CompletableFuture.runAsync(() ->
                    new TransactionTemplate(txManager).executeWithoutResult(st -> {
                        ProcessSheetRow row = rowRepo.findForUpdateByFactoryIdAndPlanIdAndProcessCodeAndClientRowId(
                                F, planId, "lu", "row-1").orElseThrow();
                        row.setSubmissionStatus(ProcessSheetRow.SUBMISSION_SUBMITTED);
                        rowRepo.saveAndFlush(row);
                        aHoldsLock.countDown();
                        try {
                            if (!allowCommit.await(15, TimeUnit.SECONDS))
                                throw new IllegalStateException("Main thread did not release transaction A");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                    }), threads);
            try {
                assertThat(aHoldsLock.await(15, TimeUnit.SECONDS)).isTrue();
                CompletableFuture<Read> b = CompletableFuture.supplyAsync(() ->
                        new TransactionTemplate(txManager).execute(st -> {
                            long t0 = System.nanoTime();
                            bSession.set(((Number) em.createNativeQuery("SELECT SESSION_ID()")
                                    .getSingleResult()).longValue());
                            bStarted.countDown();
                            String status = (lockedReadInB
                                    ? rowRepo.findForUpdateByFactoryIdAndPlanIdAndProcessCodeAndClientRowId(F, planId, "lu", "row-1")
                                    : rowRepo.findByFactoryIdAndPlanIdAndProcessCodeAndClientRowId(F, planId, "lu", "row-1"))
                                    .orElseThrow().getSubmissionStatus();
                            return new Read(status, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
                        }), threads);
                assertThat(bStarted.await(15, TimeUnit.SECONDS)).isTrue();
                if (lockedReadInB) {
                    // A future being unfinished could merely be unscheduled. Require the
                    // database itself to report this exact connection waiting on a lock.
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    boolean databaseBlocked = false;
                    while (System.nanoTime() < deadline) {
                        databaseBlocked = Integer.valueOf(1).equals(jdbc.queryForObject(
                                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE SESSION_ID=? AND BLOCKER_ID IS NOT NULL",
                                Integer.class, bSession.get()));
                        if (databaseBlocked || b.isDone()) break;
                        Thread.sleep(10); // Observe one in-flight query; never retry the business read.
                    }
                    assertThat(databaseBlocked).as("B's SQL connection must really wait on A's row lock").isTrue();
                    assertThatThrownBy(() -> b.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                    allowCommit.countDown();
                }
                Read result = b.get(15, TimeUnit.SECONDS);
                System.out.println("LOCK_RACE locked=" + lockedReadInB + " status=" + result.status() + " waitedMs=" + result.waitedMs());
                return result;
            } finally {
                allowCommit.countDown();
                a.get(15, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    @DisplayName("加锁读: 后到者被挡到前一个提交完, 读到 SUBMITTED (submitRow 据此 409)")
    void lockedReadWaitsAndSeesSubmitted() throws Exception {
        Read r = raceWith(true);
        assertThat(r.status()).isEqualTo(ProcessSheetRow.SUBMISSION_SUBMITTED);
        assertThat(r.waitedMs()).isGreaterThanOrEqualTo(300);
    }

    @Test
    @DisplayName("阴性对照: 不加锁读 → 不等, 读到的仍是 DRAFT (这就是并发重放双入账的竞态)")
    void plainReadSeesStaleDraft() throws Exception {
        Read r = raceWith(false);
        assertThat(r.status()).isEqualTo(ProcessSheetRow.SUBMISSION_DRAFT);
    }
}
