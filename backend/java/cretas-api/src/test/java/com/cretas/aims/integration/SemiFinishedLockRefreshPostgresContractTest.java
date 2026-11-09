package com.cretas.aims.integration;

import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.service.processentry.IntermediateOwnershipGuard;
import com.cretas.aims.service.production.SecondaryPlanFeedService;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.wip.ProductFamilyResolver;
import com.cretas.aims.service.wip.WipInventoryService;
import com.cretas.aims.service.wip.impl.WipInventoryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * R6 (2026-11-09) 真 PostgreSQL + 真 Hibernate: 半成品库存行「锁到之后读的是库里的真余额」。
 *
 * <p>同一事务先读过这一行 (结单预览、折算投料量、找来源都会先读), 之后 {@code SELECT … FOR UPDATE} 等到锁时,
 * Hibernate 返回的是持久化上下文里的旧对象。表上有 {@code @Version}, 按旧余额写回会在 flush 时撞乐观锁整单回滚
 * —— 不会静默覆盖, 但一次本该成功的扣减变成了 500, 而且「够不够扣」是按旧余额判的。
 * 这里每个用例都在加锁前先读一次, 然后让别的事务提交一笔扣减, 再走真实的扣减/还原入口。
 * 每一步之后核账: 产出 = 已消耗 + 余额; 已消耗 = Σ出库流水 − Σ还回流水。
 *
 * <p>开关同 {@link SecondaryHoldPostgresContractTest}, 跑在 postgres-reversal-concurrency 车道。
 */
@SpringBootTest(classes = SemiFinishedLockRefreshPostgresContractTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoint.health.validate-group-membership=false",
        "spring.datasource.hikari.data-source-properties.stringtype=unspecified",
        "spring.datasource.hikari.maximum-pool-size=8"
})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class SemiFinishedLockRefreshPostgresContractTest {

    private static final String F = "R6-PG";

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @EntityScan("com.cretas.aims.entity")
    @EnableJpaRepositories("com.cretas.aims.repository")
    @Import({WipInventoryServiceImpl.class, SecondaryPlanFeedService.class})
    static class Runtime { }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(System.getenv("CRETAS_WORKFLOW_PG_URL"));
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @MockBean ProductFamilyResolver familyResolver;
    @MockBean UnitContractService unitContractService;
    @MockBean IntermediateOwnershipGuard ownershipGuard;
    @MockBean com.cretas.aims.service.workprocess.WorkProcessTaskService workProcessTaskService;
    @MockBean com.cretas.aims.service.alerts.InventoryLowStockEventPublisher lowStockEvents;

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired WipInventoryService wipService;
    @Autowired SemiFinishedInventoryRepository wips;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        when(ownershipGuard.ofClerkAnchor(any(), any())).thenReturn(IntermediateOwnershipGuard.Owner.COMPANY);
    }

    private record Sfi(Long id, String no) {
    }

    private Sfi sfi(String produced) {
        String no = "R6-" + UUID.randomUUID().toString().substring(0, 8);
        SemiFinishedInventory row = tx.execute(state -> wips.save(SemiFinishedInventory.builder()
                .factoryId(F).intermediateBatchNo(no).productTypeId("SEMI-1").unit("kg")
                .producedQuantity(new BigDecimal(produced)).consumedQuantity(BigDecimal.ZERO)
                .availableQuantity(new BigDecimal(produced)).processOrder(1)
                .status(SemiFinishedInventory.Status.AVAILABLE).build()));
        return new Sfi(row.getId(), no);
    }

    private BigDecimal column(Sfi s, String column) {
        return jdbc.queryForObject("select " + column + " from semi_finished_inventory where id = ?",
                BigDecimal.class, s.id());
    }

    /** 产出 = 已消耗 + 余额; 已消耗 = Σ出库 − Σ还回 = 期望值。 */
    private void assertLedger(Sfi s, String expectedConsumed) {
        BigDecimal produced = column(s, "produced_quantity");
        BigDecimal consumed = column(s, "consumed_quantity");
        BigDecimal available = column(s, "available_quantity");
        BigDecimal out = jdbc.queryForObject("select coalesce(sum(-quantity),0) from semi_finished_inventory_transactions "
                + "where semi_finished_id = ? and txn_type = 'OUT'", BigDecimal.class, s.id());
        BigDecimal back = jdbc.queryForObject("select coalesce(sum(quantity),0) from semi_finished_inventory_transactions "
                + "where semi_finished_id = ? and txn_type = 'REVERSE' and quantity > 0", BigDecimal.class, s.id());
        assertThat(consumed.add(available)).as("产出 = 已消耗 + 余额").isEqualByComparingTo(produced);
        assertThat(out.subtract(back)).as("流水净出 = 已消耗").isEqualByComparingTo(consumed);
        assertThat(consumed).isEqualByComparingTo(expectedConsumed);
    }

    /** 在 tx 里先读一次 (把行装进持久化上下文), 等另一事务提交一笔 JPA 扣减, 再走 body。 */
    private void afterStaleRead(Sfi s, Runnable concurrentCommit, Runnable body) {
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(state -> {
                // 与结单预览同一条读路径: 折算投料量时按批次号把库存行读进来
                wipService.resolveSemiFeedQtyInSourceUnit(F, s.no(), BigDecimal.ONE);
                try {
                    other.submit(concurrentCommit).get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                body.run();
            });
        } finally {
            other.shutdownNow();
        }
    }

    private void consumeInOwnTx(Sfi s, String qty) {
        tx.executeWithoutResult(state -> wipService.consumeClerkSemiStrict(
                F, IntermediateOwnershipGuard.Owner.COMPANY, s.no(), new BigDecimal(qty)));
    }

    @Test
    void deductionAfterAnEarlierReadSeesTheCommittedBalanceAndSucceeds() {
        Sfi s = sfi("100");

        afterStaleRead(s, () -> consumeInOwnTx(s, "30"),
                () -> wipService.consumeClerkSemiStrict(F, IntermediateOwnershipGuard.Owner.COMPANY, s.no(),
                        new BigDecimal("30")));

        assertLedger(s, "60");
        assertThat(column(s, "available_quantity")).isEqualByComparingTo("40");
    }

    @Test
    void deductionAfterAnEarlierReadIsJudgedOnTheCommittedBalance() {
        Sfi s = sfi("100");
        List<String> outcome = new ArrayList<>();

        try {
            afterStaleRead(s, () -> consumeInOwnTx(s, "60"),
                    () -> wipService.consumeClerkSemiStrict(F, IntermediateOwnershipGuard.Owner.COMPANY, s.no(),
                            new BigDecimal("60")));
            outcome.add("OK");
        } catch (BusinessException e) {
            outcome.add(e.getErrorCode());
        } catch (RuntimeException e) {
            outcome.add(e.getClass().getSimpleName());
        }

        // 锁内只剩 40: 按真余额判「不够」, 是业务拒绝 —— 不是按旧余额放行后在 flush 撞乐观锁
        assertThat(outcome).containsExactly("SFI_INSUFFICIENT");
        assertLedger(s, "60");
    }

    @Test
    void restoreAfterAnEarlierReadKeepsTheOtherTransactionsDeduction() {
        Sfi s = sfi("100");
        consumeInOwnTx(s, "50");

        afterStaleRead(s, () -> consumeInOwnTx(s, "20"),
                () -> wipService.restoreClerkSemiExact(F, s.no(), new BigDecimal("50"), "reversal-log:r6", 7L));

        assertLedger(s, "20");
    }

    @Test
    void concurrentDeductionsAfterEarlierReadsNeverOverdrawAndFailOnlyAsBusinessRefusals() throws Exception {
        for (int round = 0; round < 5; round++) {
            Sfi s = sfi("100");
            List<String> outcomes = race(List.of(
                    () -> { readThenConsume(s, "60"); return null; },
                    () -> { readThenConsume(s, "60"); return null; }));

            assertThat(outcomes).containsExactlyInAnyOrder("OK", "SFI_INSUFFICIENT");
            assertLedger(s, "60");
        }
    }

    private void readThenConsume(Sfi s, String qty) {
        tx.executeWithoutResult(state -> {
            wipService.resolveSemiFeedQtyInSourceUnit(F, s.no(), BigDecimal.ONE);
            wipService.consumeClerkSemiStrict(F, IntermediateOwnershipGuard.Owner.COMPANY, s.no(), new BigDecimal(qty));
        });
    }

    private List<String> race(List<Callable<Void>> jobs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(jobs.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (Callable<Void> job : jobs) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        job.call();
                        return "OK";
                    } catch (BusinessException e) {
                        return e.getErrorCode();
                    } catch (RuntimeException e) {
                        return e.getClass().getSimpleName();
                    }
                }));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> result : results) {
                outcomes.add(result.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
