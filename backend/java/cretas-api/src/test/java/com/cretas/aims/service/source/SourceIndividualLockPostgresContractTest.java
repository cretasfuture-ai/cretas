package com.cretas.aims.service.source;

import com.cretas.aims.dto.source.CreateSourceIndividualRequest;
import com.cretas.aims.dto.source.UpdateSourceIndividualRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.integration.DisposablePostgresTargetGuard;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.datacenter.OperationLogRepository;
import com.cretas.aims.repository.source.SourceIndividualRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1844 终审 L-c: {@link SourceIndividualRevokeLockContractTest} 的两条撤销/挂载串行用例在<b>真 PostgreSQL</b>
 * 上再跑一遍, 外加 L-a 的「两人同时互设父本」。
 *
 * <p>H2 证明不了的正是这里要证的: PG READ COMMITTED 下, 挂载方 {@code SELECT … FOR UPDATE} 等到撤销方提交后,
 * 会按新版本行重判 {@code deleted_at IS NULL}(EvalPlanQual) ⇒ 查不到 ⇒ 409; 以及互设父本在 PG 上
 * 不会走到 40P01 死锁(那会变成 500), 而是后到方被串行并拿到 409 PEDIGREE_CYCLE。
 *
 * <p>开关与清单同 {@code SettlementModeNoDoubleConsumptionPostgresContractTest}: 只在
 * {@code CRETAS_WORKFLOW_PG_VERIFY=true} 时跑(postgres-reversal-concurrency 分片, 清单
 * scripts/ci/java-gate-pg-tests.txt), 没 PG 时 Skipped。库地址必须过 {@link DisposablePostgresTargetGuard}。
 * 本类不跑在测试事务里: 两个线程各开真事务、数据真提交; 每条用例独立 factoryId。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class SourceIndividualLockPostgresContractTest {

    private static final long HOLD_MS = 600;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(System.getenv("CRETAS_WORKFLOW_PG_URL"));
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username",
                () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "8");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @Autowired SourceIndividualRepository repository;
    @Autowired MaterialBatchRepository materialBatchRepository;
    @Autowired OperationLogRepository operationLogRepository;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JdbcTemplate jdbc;
    @PersistenceContext EntityManager entityManager;
    private SourceIndividualService service;
    private TransactionTemplate tx;
    private Long userId;

    @BeforeEach
    void setUp() {
        service = new SourceIndividualService(repository, operationLogRepository);
        ReflectionTestUtils.setField(service, "materialBatchRepository", materialBatchRepository);
        tx = new TransactionTemplate(txManager);
        // 仪器自检: 这条用例真的连在 PostgreSQL 上, 不是 H2。
        assertThat(jdbc.queryForObject("SELECT version()", String.class)).startsWith("PostgreSQL");
    }

    private String newIndividual(String factory, String code) {
        userId = tx.execute(s -> SourceIndividualTestUsers.persistUser(entityManager, factory));
        assertThat(userId).isNotNull();
        return tx.execute(s -> service.create(factory,
                CreateSourceIndividualRequest.builder().individualCode(code).build()).getId());
    }

    private MaterialBatch batchFor(String factory, String individualId) {
        MaterialBatch mb = new MaterialBatch();
        mb.setId("MB-PG-" + UUID.randomUUID());
        mb.setFactoryId(factory);
        mb.setBatchNumber("MB-PG-" + System.nanoTime());
        mb.setReceiptDate(LocalDate.of(2026, 11, 19));
        mb.setWarehouseId("WH-1");
        mb.setReceiptQuantity(new BigDecimal("10"));
        mb.setQuantityUnit("kg");
        mb.setUsedQuantity(BigDecimal.ZERO);
        mb.setReservedQuantity(BigDecimal.ZERO);
        mb.setStatus(MaterialBatchStatus.AVAILABLE);
        mb.setCreatedBy(userId);
        mb.setSourceIndividualId(individualId);
        mb.setCustomFields(null);
        return mb;
    }

    @Test
    @DisplayName("PG: 挂载方先锁 → 撤销被阻塞到批次提交, 随后查到批次并 409; 个体仍在")
    void revokeWaitsForLinkAndThenSeesTheBatch() throws Exception {
        String factory = "F-PGLK-A-" + UUID.randomUUID().toString().substring(0, 8);
        String id = newIndividual(factory, "PGLK-A");
        CountDownLatch linked = new CountDownLatch(1);

        CompletableFuture<Void> linker = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            service.requireLinkable(factory, id);
            linked.countDown();
            sleep(HOLD_MS);
            materialBatchRepository.saveAndFlush(batchFor(factory, id));
        }));
        assertThat(linked.await(10, TimeUnit.SECONDS)).isTrue();

        AtomicReference<Throwable> revokeError = new AtomicReference<>();
        long t0 = System.nanoTime();
        try {
            tx.executeWithoutResult(s -> service.revoke(factory, id, "录入错误", "1"));
        } catch (Throwable t) {
            revokeError.set(t);
        }
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        linker.get(20, TimeUnit.SECONDS);

        assertThat(revokeError.get())
                .as("撤销必须因为刚提交的批次而 409 (waited=%dms)", waitedMs)
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(409));
        assertThat(waitedMs).as("撤销确实被个体行锁阻塞").isGreaterThanOrEqualTo(HOLD_MS / 2);
        assertThat(repository.findByIdAndFactoryId(id, factory)).isPresent();
        assertThat(materialBatchRepository.findByFactoryIdAndSourceIndividualIdOrderByReceiptDateDesc(factory, id))
                .hasSize(1);
    }

    @Test
    @DisplayName("PG: 撤销方先锁 → 挂载被阻塞到撤销提交, 锁后按新版本重判 deleted_at, 被拒 409")
    void linkWaitsForRevokeAndThenIsRejected() throws Exception {
        String factory = "F-PGLK-B-" + UUID.randomUUID().toString().substring(0, 8);
        String id = newIndividual(factory, "PGLK-B");
        CountDownLatch revoked = new CountDownLatch(1);

        CompletableFuture<Void> revoker = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            service.revoke(factory, id, "录入错误", "1");
            revoked.countDown();
            sleep(HOLD_MS);
        }));
        assertThat(revoked.await(10, TimeUnit.SECONDS)).isTrue();

        AtomicReference<Throwable> linkError = new AtomicReference<>();
        long t0 = System.nanoTime();
        try {
            tx.executeWithoutResult(s -> {
                service.requireLinkable(factory, id);
                materialBatchRepository.saveAndFlush(batchFor(factory, id));
            });
        } catch (Throwable t) {
            linkError.set(t);
        }
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        revoker.get(20, TimeUnit.SECONDS);

        assertThat(linkError.get())
                .as("挂载必须在撤销提交后被拒 (waited=%dms)", waitedMs)
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(409));
        assertThat(waitedMs).as("挂载确实被个体行锁阻塞").isGreaterThanOrEqualTo(HOLD_MS / 2);
        assertThat(materialBatchRepository.findByFactoryIdAndSourceIndividualIdOrderByReceiptDateDesc(factory, id))
                .isEmpty();
        assertThat(repository.findByIdAndFactoryId(id, factory)).isEmpty();
    }

    @Test
    @DisplayName("PG: 两人同时互设父本 → 后到方被串行, 409 PEDIGREE_CYCLE, 不是 40P01 死锁")
    void concurrentMutualSireIsABusinessErrorNotADeadlock() throws Exception {
        String factory = "F-PGLK-C-" + UUID.randomUUID().toString().substring(0, 8);
        String x = newIndividual(factory, "PGLK-X");
        String a = tx.execute(s -> service.create(factory,
                CreateSourceIndividualRequest.builder().individualCode("PGLK-A").build()).getId());
        CountDownLatch firstLocked = new CountDownLatch(1);

        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            service.update(factory, x, UpdateSourceIndividualRequest.builder().sireIndividualId(a).build());
            firstLocked.countDown();
            sleep(HOLD_MS);
        }));
        assertThat(firstLocked.await(10, TimeUnit.SECONDS)).isTrue();

        AtomicReference<Throwable> secondError = new AtomicReference<>();
        long t0 = System.nanoTime();
        try {
            tx.executeWithoutResult(s -> service.update(factory, a,
                    UpdateSourceIndividualRequest.builder().sireIndividualId(x).build()));
        } catch (Throwable t) {
            secondError.set(t);
        }
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        first.get(20, TimeUnit.SECONDS);

        assertThat(secondError.get())
                .as("后到方必须拿到成环的业务错误, 不是死锁 (waited=%dms)", waitedMs)
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(409);
                    assertThat(e.getErrorCode()).isEqualTo("PEDIGREE_CYCLE");
                });
        assertThat(waitedMs).as("后到方确实被行锁阻塞").isGreaterThanOrEqualTo(HOLD_MS / 2);
        assertThat(repository.findByIdAndFactoryId(x, factory).orElseThrow().getSireIndividualId()).isEqualTo(a);
        assertThat(repository.findByIdAndFactoryId(a, factory).orElseThrow().getSireIndividualId()).isNull();
    }

    /**
     * #1968 M-1: 上面那条互设父本用例是<b>串行时序</b>(先到方整个跑完才放后到方), 守不住「按 id 排序加锁防死锁」。
     * 这里用第三个事务 T0 先 {@code FOR UPDATE} 锁住 min(x,a), 让两个互设父本的 update <b>都</b>排进等锁队列
     * (用 pg_stat_activity 的 wait_event_type='Lock' 计数确认, 不靠 sleep 猜), 再放开 T0。
     * <ul>
     *   <li>有序加锁: 两边第一把锁都是 min ⇒ 排在同一行上, 先排队的一方拿到后一路锁完提交, 后一方锁后重读看到环 ⇒ 409;</li>
     *   <li>去掉排序(先锁自己): self=max 的一方先拿到自己那一行再等 min, self=min 的一方拿到 min 后要等 max ⇒ 必然 40P01。</li>
     * </ul>
     * 先排队的是 self=min 那一方(PG 行锁等待者按到达顺序拿锁), 这样「去掉排序」的变异下死锁是确定的。
     */
    @Test
    @DisplayName("PG 强制交错: 两个互设父本的 update 同时排队等锁 → 一成一 409, 无 40P01, 库里不成环")
    void forcedInterleavingOfMutualSireNeverDeadlocks() throws Exception {
        String factory = "F-PGLK-D-" + UUID.randomUUID().toString().substring(0, 8);
        String x = newIndividual(factory, "PGIL-X");
        String a = tx.execute(s -> service.create(factory,
                CreateSourceIndividualRequest.builder().individualCode("PGIL-A").build()).getId());
        String lo = x.compareTo(a) < 0 ? x : a;
        String hi = lo.equals(x) ? a : x;
        assertThat(lockWaiters()).as("开始前不应有人在等锁(仪器基线)").isZero();

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            CountDownLatch holderLocked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            java.util.concurrent.Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
                assertThat(jdbc.queryForList("SELECT id FROM source_individuals WHERE id = ? FOR UPDATE", lo))
                        .hasSize(1);
                holderLocked.countDown();
                try {
                    assertThat(release.await(60, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertThat(holderLocked.await(10, TimeUnit.SECONDS)).isTrue();

            java.util.concurrent.Future<Throwable> selfLo = pool.submit(() -> attemptSetSire(factory, lo, hi));
            awaitLockWaiters(1);
            java.util.concurrent.Future<Throwable> selfHi = pool.submit(() -> attemptSetSire(factory, hi, lo));
            awaitLockWaiters(2);

            release.countDown();
            holder.get(20, TimeUnit.SECONDS);
            Throwable rLo = selfLo.get(30, TimeUnit.SECONDS);
            Throwable rHi = selfHi.get(30, TimeUnit.SECONDS);

            assertThat(isDeadlock(rLo)).as("self=min 一方不许 40P01: %s", rLo).isFalse();
            assertThat(isDeadlock(rHi)).as("self=max 一方不许 40P01: %s", rHi).isFalse();
            java.util.List<Throwable> errors = java.util.stream.Stream.of(rLo, rHi)
                    .filter(java.util.Objects::nonNull).toList();
            assertThat(errors).as("恰好一方失败 (rLo=%s, rHi=%s)", rLo, rHi).hasSize(1);
            assertThat(errors.get(0)).isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.getCode()).isEqualTo(409);
                assertThat(e.getErrorCode()).isEqualTo("PEDIGREE_CYCLE");
            });
            String sireOfX = repository.findByIdAndFactoryId(x, factory).orElseThrow().getSireIndividualId();
            String sireOfA = repository.findByIdAndFactoryId(a, factory).orElseThrow().getSireIndividualId();
            assertThat(a.equals(sireOfX) && x.equals(sireOfA)).as("库里成环了").isFalse();
            assertThat(a.equals(sireOfX) || x.equals(sireOfA)).as("成功的那一方必须真的写进去了").isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    private Throwable attemptSetSire(String factory, String self, String sire) {
        try {
            tx.executeWithoutResult(s -> service.update(factory, self,
                    UpdateSourceIndividualRequest.builder().sireIndividualId(sire).build()));
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private int lockWaiters() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity "
                + "WHERE datname = current_database() AND wait_event_type = 'Lock'", Integer.class);
        return n == null ? -1 : n;
    }

    private void awaitLockWaiters(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        int seen = lockWaiters();
        while (seen < expected && System.nanoTime() < deadline) {
            Thread.sleep(50);
            seen = lockWaiters();
        }
        assertThat(seen).as("等锁的会话数没到 %d (pg_stat_activity wait_event_type='Lock')", expected)
                .isGreaterThanOrEqualTo(expected);
    }

    private static boolean isDeadlock(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof java.sql.SQLException sql && "40P01".equals(sql.getSQLState())) {
                return true;
            }
            if (c.getMessage() != null && c.getMessage().contains("deadlock detected")) {
                return true;
            }
        }
        return false;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
