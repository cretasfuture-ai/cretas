package com.cretas.aims.integration;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.ProductionSettlement;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.service.ProductionPlanService;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.service.processentry.IntermediateOwnershipGuard;
import com.cretas.aims.service.production.SecondaryPlanFeedService;
import com.cretas.aims.service.production.SettlementSemiStockingService;
import com.cretas.aims.service.reversal.impl.ReportReversalServiceImpl;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.wip.ProductFamilyResolver;
import com.cretas.aims.service.wip.WipInventoryService;
import com.cretas.aims.service.wip.impl.WipInventoryServiceImpl;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * 真 PostgreSQL + 真 Hibernate: 返工计划占用和结单入库的账务、并发 (PR #1446 审查)。
 *
 * <p>mock 证明不了这些: 行锁是不是真的串行化、Hibernate 在锁查询上会不会返回旧值、流水是不是真能按计划算回来。
 * 只验账本身, 谁在什么时候调它由 {@code SecondaryPlanStartAndSettleContractTest} 验。
 *
 * <p>开关同 {@link ClerkChainLineagePostgresIntegrationTest}, 跑在 postgres-reversal-concurrency 车道。
 */
@SpringBootTest(classes = SecondaryHoldPostgresContractTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoint.health.validate-group-membership=false",
        "spring.datasource.hikari.data-source-properties.stringtype=unspecified",
        "spring.datasource.hikari.maximum-pool-size=8"
})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class SecondaryHoldPostgresContractTest {

    private static final String F = "S315-PG";

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @EntityScan("com.cretas.aims.entity")
    @EnableJpaRepositories("com.cretas.aims.repository")
    @Import({ProductionPlanServiceImpl.class, WipInventoryServiceImpl.class,
            SecondaryPlanFeedService.class, SettlementSemiStockingService.class})
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

    @MockBean ProductionPlanMapper planMapper;
    @MockBean SchedulingService schedulingService;
    @MockBean ExcelUtil excelUtil;
    @MockBean InventoryLowStockEventPublisher lowStockEvents;
    @MockBean ProductFamilyResolver familyResolver;
    @MockBean UnitContractService unitContractService;
    @MockBean IntermediateOwnershipGuard ownershipGuard;
    @MockBean com.cretas.aims.service.workprocess.WorkProcessTaskService workProcessTaskService;
    @MockBean com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope processSecretLlmCostScope;
    @MockBean com.cretas.aims.service.finance.ProductionCostConsistency productionCostConsistency;
    @MockBean com.cretas.aims.service.finance.ReturnedCostSynchronizationService returnedCostSynchronizationService;

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ProductionPlanService planService;
    @Autowired WipInventoryService wipService;
    @Autowired SecondaryPlanFeedService feed;
    @Autowired SettlementSemiStockingService stocking;
    @Autowired ProductionPlanRepository plans;
    @Autowired ProductionBatchRepository batches;
    @Autowired SemiFinishedInventoryRepository wips;
    @Autowired MaterialBatchRepository materialBatches;
    @Autowired ProductionReportRepository reports;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        jdbc.execute("DO $$ DECLARE r record; BEGIN "
                + "FOR r IN SELECT conrelid::regclass AS t, conname FROM pg_constraint "
                + "WHERE contype = 'f' AND connamespace = 'public'::regnamespace LOOP "
                + "EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.t, r.conname); END LOOP; END $$");
        when(ownershipGuard.ofClerkAnchor(any(), any())).thenReturn(IntermediateOwnershipGuard.Owner.COMPANY);
        when(ownershipGuard.ofProductionBatch(any(), any())).thenReturn(IntermediateOwnershipGuard.Owner.COMPANY);
    }

    /** 一份独立的来源 + 返工计划 + 批次, 并发用例每轮一份, 互不干扰。 */
    private record Fixture(String planId, Long wipId, String wipNo, Long batchId) {
    }

    private Fixture fixture(ProductionPlanStatus status) {
        return fixture(status, true);
    }

    private Fixture fixture(ProductionPlanStatus status, boolean initiallyHeld) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return tx.execute(state -> {
            SemiFinishedInventory wip = SemiFinishedInventory.builder()
                    .factoryId(F).intermediateBatchNo("SRC-" + suffix).productTypeId("SEMI-1").unit("kg")
                    .producedQuantity(new BigDecimal("1.000")).consumedQuantity(BigDecimal.ZERO)
                    .availableQuantity(new BigDecimal("1.000")).processOrder(1)
                    .status(SemiFinishedInventory.Status.AVAILABLE)
                    .build();
            wip = wips.save(wip);

            ProductionPlan plan = new ProductionPlan();
            plan.setId("PLAN-" + suffix);
            plan.setFactoryId(F);
            plan.setPlanNumber("SEC-" + suffix);
            plan.setProductTypeId("FG-1");
            plan.setPlannedQuantity(new BigDecimal("0.6"));
            plan.setPlannedUnit("kg");
            plan.setStatus(status);
            plan.setCreatedBy(7L);
            plan.setPlanSourceType("SECONDARY");
            plan.setSecondarySourceWipId(wip.getId());
            plans.save(plan);

            ProductionBatch batch = new ProductionBatch();
            batch.setFactoryId(F);
            batch.setBatchNumber("PB-" + suffix);
            batch.setProductTypeId("FG-1");
            batch.setQuantity(new BigDecimal("0.6"));
            batch.setUnit("kg");
            batch.setStatus(ProductionBatchStatus.IN_PROGRESS);
            batch.setProductionPlanId(plan.getId());
            batch = batches.save(batch);

            if (initiallyHeld) {
                wipService.deductForSecondaryPlan(wip.getId(), new BigDecimal("0.6"), F, 7L,
                        IntermediateOwnershipGuard.Owner.COMPANY, plan.getId());
            }
            return new Fixture(plan.getId(), wip.getId(), wip.getIntermediateBatchNo(), batch.getId());
        });
    }

    private ProductionReport report(Fixture f, String quantity) {
        ProductionReport report = new ProductionReport();
        report.setFactoryId(F);
        report.setWorkerId(7L);
        report.setReportType("YIELD");
        report.setReportDate(LocalDate.now());
        report.setBatchId(f.batchId());
        report.setSourceWipNo(f.wipNo());
        report.setInputQuantity(new BigDecimal(quantity));
        report.setInputUnit("kg");
        report.setApprovalStatus("APPROVED");
        Map<String, Object> fields = new HashMap<>();
        fields.put("sourceWipQuantity", new BigDecimal(quantity));
        report.setCustomFields(fields);
        return report;
    }

    private WorkProcessTask task(Fixture f) {
        WorkProcessTask task = new WorkProcessTask();
        task.setId(1L);
        task.setFactoryId(F);
        task.setProductionBatchId(f.batchId());
        task.setProcessOrder(2);
        task.setProductTypeId("FG-1");
        return task;
    }

    /** App 报工的真实过账: 报工行和扣账在同一事务。 */
    private void postReport(Fixture f, String quantity) {
        tx.executeWithoutResult(state -> {
            ProductionReport saved = reports.save(report(f, quantity));
            wipService.postApprovedOutput(F, saved, task(f), 7L);
        });
    }

    private BigDecimal consumed(Fixture f) {
        return jdbc.queryForObject("select consumed_quantity from semi_finished_inventory where id = ?",
                BigDecimal.class, f.wipId());
    }

    @Test
    void ordinaryPlanRefreshPreservesPinnedJsonSnapshotsWithoutTakingHold() {
        Fixture f = fixture(ProductionPlanStatus.PENDING, false);
        tx.executeWithoutResult(state -> {
            ProductionPlan plan = plans.findById(f.planId()).orElseThrow();
            plan.setPlanSourceType("NORMAL");
            plan.setSecondarySourceWipId(null);
            plan.setWorkflowOutputUnitsByProduct(Map.of("FG-1", "盒"));
            plan.setSelectedBomRecipeIdsByProduct(Map.of("FG-1", "RECIPE-PINNED"));
            plan.setSelectedBomVersionsByProduct(Map.of("FG-1", 7));
            plan.setSourceOrderIds(List.of("QA-ORDER-A", "QA-ORDER-B"));
            plans.saveAndFlush(plan);
        });
        tx.executeWithoutResult(state -> {
            ProductionPlan plan = plans.findById(f.planId()).orElseThrow();
            feed.takeHold(F, plan, 7L);
            assertThat(plan.getWorkflowOutputUnitsByProduct()).containsExactlyEntriesOf(Map.of("FG-1", "盒"));
            assertThat(plan.getSelectedBomRecipeIdsByProduct()).containsExactlyEntriesOf(Map.of("FG-1", "RECIPE-PINNED"));
            assertThat(plan.getSelectedBomVersionsByProduct()).containsExactlyEntriesOf(Map.of("FG-1", 7));
            assertThat(plan.getSourceOrderIds()).containsExactly("QA-ORDER-A", "QA-ORDER-B");
        });
        assertThat(consumed(f)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select count(*) from semi_finished_inventory_transactions "
                + "where factory_id = ? and semi_finished_id = ? and source_ref = ?",
                Integer.class, F, f.wipId(), SecondaryPlanFeedService.deductRef(f.wipId(), f.planId())))
                .isZero();
    }

    private BigDecimal held(Fixture f) {
        return tx.execute(state -> {
            ProductionPlan plan = plans.findById(f.planId()).orElseThrow();
            return feed.heldQuantity(F, feed.forPlan(F, plan).orElseThrow());
        });
    }

    private String status(Fixture f) {
        return jdbc.queryForObject("select status from production_plans where id = ?", String.class, f.planId());
    }

    @Test
    void reportConsumesTheHoldOnceAndLedgerAddsUp() {
        Fixture f = fixture(ProductionPlanStatus.IN_PROGRESS);
        assertThat(consumed(f)).isEqualByComparingTo("0.6");

        postReport(f, "0.4");

        assertThat(consumed(f)).as("占用转消耗, 不扣第二遍").isEqualByComparingTo("0.6");
        assertThat(held(f)).isEqualByComparingTo("0.2");
    }

    @Test
    void pausedPlanCancelReturnsTheHold() {
        Fixture f = fixture(ProductionPlanStatus.PAUSED);

        planService.cancelProductionPlan(F, f.planId(), "不做了", 7L);

        assertThat(consumed(f)).isEqualByComparingTo("0");
        assertThat(held(f)).isEqualByComparingTo("0");
        assertThat(status(f)).isEqualTo("CANCELLED");
    }

    @Test
    void closingPlanReturnsOnlyWhatWasNotUsed() {
        Fixture f = fixture(ProductionPlanStatus.IN_PROGRESS);
        postReport(f, "0.4");

        BigDecimal released = tx.execute(state ->
                feed.closeHold(F, plans.findById(f.planId()).orElseThrow(), 7L));

        assertThat(released).isEqualByComparingTo("0.2");
        assertThat(consumed(f)).as("来源只少实际用掉的 0.4").isEqualByComparingTo("0.4");
    }

    @Test
    void lockAfterEarlierReadInSameTransactionSeesCommittedBalance() throws Exception {
        Fixture f = fixture(ProductionPlanStatus.IN_PROGRESS);
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(state -> {
                wips.findById(f.wipId()).orElseThrow();   // 同一事务先读过: 持久化上下文里是旧余额
                try {
                    other.submit(() -> jdbc.update("update semi_finished_inventory "
                            + "set consumed_quantity = consumed_quantity + 0.1 where id = ?", f.wipId())).get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                wipService.reverseSecondaryDeduct(f.wipId(), new BigDecimal("0.6"), F, 7L, f.planId());
            });
        } finally {
            other.shutdownNow();
        }

        assertThat(consumed(f)).as("别人提交的 0.1 不能被旧余额盖掉").isEqualByComparingTo("0.1");
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

    @Test
    void concurrentDistinctEntriesTakeOnlyOnePlanHold() throws Exception {
        Fixture f = fixture(ProductionPlanStatus.PENDING, false);
        assertThat(jdbc.update("update semi_finished_inventory set produced_quantity=3, "
                + "available_quantity=3, consumed_quantity=0 where id=? and factory_id=?", f.wipId(), F))
                .isEqualTo(1);
        CountDownLatch firstHeld = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondRead = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger secondPid = new java.util.concurrent.atomic.AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> tx.executeWithoutResult(state -> {
                ProductionPlan plan = plans.findByIdForUpdate(f.planId()).orElseThrow();
                feed.takeHold(F, plan, 7L);
                firstHeld.countDown();
                try {
                    if (!releaseFirst.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("first release timed out");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
            }));
            assertThat(firstHeld.await(30, TimeUnit.SECONDS)).isTrue();
            Future<?> second = pool.submit(() -> tx.executeWithoutResult(state -> {
                // A distinct entry has its own transaction and an earlier, unlocked managed plan.
                ProductionPlan earlierRead = plans.findByIdAndFactoryId(f.planId(), F).orElseThrow();
                secondPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                secondRead.countDown();
                feed.takeHold(F, earlierRead, 8L);
            }));
            assertThat(secondRead.await(30, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            boolean waiting = false;
            while (System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject(
                        "select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",
                        Boolean.class, secondPid.get()));
                if (waiting) break;
                Thread.sleep(20); // Observe an actual DB lock wait, not a probabilistic scheduling delay.
            }
            assertThat(waiting).as("second entry really waits while first hold is uncommitted").isTrue();
            releaseFirst.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from semi_finished_inventory_transactions "
                + "where factory_id=? and semi_finished_id=? and source_ref=?", Integer.class,
                F, f.wipId(), SecondaryPlanFeedService.deductRef(f.wipId(), f.planId())))
                .as("same plan has exactly one source hold despite distinct concurrent entries").isEqualTo(1);
        assertThat(consumed(f)).isEqualByComparingTo("0.6");
        assertThat(jdbc.queryForObject("select available_quantity from semi_finished_inventory where id=?",
                BigDecimal.class, f.wipId())).isEqualByComparingTo("2.4");
    }

    @Test
    void concurrentReportsCannotOverdrawThePlan() throws Exception {
        for (int round = 0; round < 5; round++) {
            Fixture f = fixture(ProductionPlanStatus.IN_PROGRESS);

            List<String> outcomes = race(List.of(
                    () -> { postReport(f, "0.4"); return null; },
                    () -> { postReport(f, "0.4"); return null; }));

            assertThat(outcomes).containsExactlyInAnyOrder("OK", "SECONDARY_SOURCE_OVER_PLAN");
            assertThat(consumed(f)).isEqualByComparingTo("0.6");
            assertThat(held(f)).isEqualByComparingTo("0.2");
        }
    }

    @Test
    void reportAndCancelRaceEndsConsistently() throws Exception {
        for (int round = 0; round < 8; round++) {
            Fixture f = fixture(ProductionPlanStatus.IN_PROGRESS);

            List<String> outcomes = race(List.of(
                    () -> { postReport(f, "0.4"); return null; },
                    () -> { planService.cancelProductionPlan(F, f.planId(), "不做了", 7L); return null; }));

            if ("OK".equals(outcomes.get(0))) {
                assertThat(outcomes.get(1)).as("报工先到, 取消要走报工撤回").isEqualTo("PLAN_HAS_PRODUCTION_DATA");
                assertThat(status(f)).isEqualTo("IN_PROGRESS");
                assertThat(consumed(f)).isEqualByComparingTo("0.6");
            } else {
                assertThat(outcomes).containsExactly("SECONDARY_PLAN_NOT_ACTIVE", "OK");
                assertThat(status(f)).isEqualTo("CANCELLED");
                assertThat(consumed(f)).as("取消先到, 全部还回, 报工不落账").isEqualByComparingTo("0");
            }
        }
    }

    private String wipBatch(Fixture f) {
        String id = "MB-" + f.planId();
        tx.executeWithoutResult(state -> {
            MaterialBatch batch = new MaterialBatch();
            batch.setId(id);
            batch.setFactoryId(F);
            batch.setBatchNumber("WIP-" + f.planId());
            batch.setReceiptDate(LocalDate.now());
            batch.setWarehouseId("WH");
            batch.setReceiptQuantity(new BigDecimal("0.9"));
            batch.setQuantityUnit("kg");
            batch.setUnitPrice(new BigDecimal("20"));
            batch.setCreatedBy(7L);
            batch.setSourceDocType("PRODUCTION_BATCH");
            batch.setSourceDocId("999");
            materialBatches.save(batch);
        });
        return id;
    }

    private ReportReversalServiceImpl reversalService() throws Exception {
        Constructor<?> ctor = Arrays.stream(ReportReversalServiceImpl.class.getDeclaredConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Object[] args = new Object[ctor.getParameterCount()];
        Class<?>[] types = ctor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            args[i] = types[i] == SemiFinishedInventoryRepository.class ? wips : Mockito.mock(types[i]);
        }
        ctor.setAccessible(true);
        ReportReversalServiceImpl service = (ReportReversalServiceImpl) ctor.newInstance(args);
        ReflectionTestUtils.setField(service, "wipInventoryService", wipService);
        ReflectionTestUtils.setField(service, "materialBatchRepository", materialBatches);
        return service;
    }

    @Test
    void reversalReturnsStockedSemiToBothSides() throws Exception {
        Fixture f = fixture(ProductionPlanStatus.IN_PROGRESS);
        String materialBatchId = wipBatch(f);
        ProductionPlan plan = tx.execute(state -> plans.findById(f.planId()).orElseThrow());
        var plan0 = new SettlementSemiStockingService.Stocking("SEMI-1", "kg", 1, new BigDecimal("0.3"),
                List.of(new SettlementSemiStockingService.Slice(999L, "CLK-W-1", materialBatchId,
                        new BigDecimal("0.3"), new BigDecimal("0.3"), BigDecimal.ZERO)));
        ProductionSettlement settlement = new ProductionSettlement();
        settlement.setId(UUID.randomUUID().toString());

        settlement.setSemiStockingDetail(tx.execute(state -> stocking.stock(F, plan, plan0, settlement.getId())));

        String anchor = SettlementSemiStockingService.parseDetail(settlement.getSemiStockingDetail()).get(0).anchor();
        assertThat(jdbc.queryForObject("select used_quantity from material_batches where id = ?",
                BigDecimal.class, materialBatchId)).isEqualByComparingTo("0.3");
        assertThat(jdbc.queryForObject("select produced_quantity from semi_finished_inventory where intermediate_batch_no = ?",
                BigDecimal.class, anchor)).isEqualByComparingTo("0.3");
        assertThat(jdbc.queryForObject("select accumulated_cost from semi_finished_inventory where intermediate_batch_no = ?",
                BigDecimal.class, anchor)).as("成本随货走: 0.3 × 20").isEqualByComparingTo("6");

        ReportReversalServiceImpl reversal = reversalService();
        tx.executeWithoutResult(state -> ReflectionTestUtils.invokeMethod(
                reversal, "reverseSettlementSemiStocking", F, settlement, 123L, 7L,
                new java.util.TreeMap<String, BigDecimal>()));

        assertThat(jdbc.queryForObject("select used_quantity from material_batches where id = ?",
                BigDecimal.class, materialBatchId)).as("在制批次还回").isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select produced_quantity from semi_finished_inventory where intermediate_batch_no = ?",
                BigDecimal.class, anchor)).as("半成品库退出").isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select accumulated_cost from semi_finished_inventory where intermediate_batch_no = ?",
                BigDecimal.class, anchor)).as("成本一起退回").isEqualByComparingTo("0");
    }

    @Test
    void createBatchTakesTheSameHoldAndCancelReturnsIt() {
        Fixture f = fixture(ProductionPlanStatus.IN_PROGRESS);
        tx.executeWithoutResult(state -> {
            // 这份 fixture 的占用先还掉, 计划退回待处理, 再走 /create-batch 开工
            ProductionPlan plan = plans.findById(f.planId()).orElseThrow();
            feed.release(F, feed.forPlan(F, plan).orElseThrow(), null, 7L);
        });
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ProductionPlan fresh = tx.execute(state -> {
            ProductionPlan plan = new ProductionPlan();
            plan.setId("PLAN-CB-" + suffix);
            plan.setFactoryId(F);
            plan.setPlanNumber("SEC-CB-" + suffix);
            plan.setProductTypeId("FG-1");
            plan.setPlannedQuantity(new BigDecimal("0.6"));
            plan.setPlannedUnit("kg");
            plan.setStatus(ProductionPlanStatus.PENDING);
            plan.setCreatedBy(7L);
            plan.setPlanSourceType("SECONDARY");
            plan.setSecondarySourceWipId(f.wipId());
            return plans.save(plan);
        });
        when(workProcessTaskService.spawnTasks(any(), any(), any(), any(), any(), any())).thenReturn(List.of());

        planService.createBatchFromPlan(F, fresh.getId(), 7L, null);
        assertThat(consumed(f)).as("开工就占用").isEqualByComparingTo("0.6");

        planService.cancelProductionPlan(F, fresh.getId(), "不做了", 7L);
        assertThat(consumed(f)).as("取消全部还回").isEqualByComparingTo("0");
    }
}
