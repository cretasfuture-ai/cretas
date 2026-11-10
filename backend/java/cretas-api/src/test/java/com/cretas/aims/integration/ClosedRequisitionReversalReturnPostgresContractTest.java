package com.cretas.aims.integration;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.ReportReversalLog;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.factory.FactoryMaterialRequisitionItem;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository;
import com.cretas.aims.service.NotificationService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.service.factory.FactoryMaterialRequisitionService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.factory.impl.FactoryMaterialRequisitionServiceImpl;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.service.inventory.impl.FinishedGoodsFeedServiceImpl;
import com.cretas.aims.service.processentry.IntermediateOwnershipGuard;
import com.cretas.aims.service.production.SecondaryPlanFeedService;
import com.cretas.aims.service.production.SettlementSemiStockingService;
import com.cretas.aims.service.production.WorkshopReportReservationService;
import com.cretas.aims.service.reversal.ReportReversalService;
import com.cretas.aims.service.reversal.ReversalConsumptionCleanupService;
import com.cretas.aims.service.reversal.impl.ReportReversalServiceImpl;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.wip.ProductFamilyResolver;
import com.cretas.aims.service.wip.impl.WipInventoryServiceImpl;
import com.cretas.aims.utils.ExcelUtil;
import jakarta.persistence.EntityManagerFactory;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * C1 (工厂纪律排查 2026-11-10, Steve 裁定 B) 真 PostgreSQL + 真 Hibernate + 真入口:
 * 先关领料单、后撤回报工, 撤回放回生产仓的料要直接退回原料仓, 并留退料流水。
 *
 * <p>以领料 100、App 报工在生产仓批次上占 30 为例:
 * <pre>
 *   关单: 按可用 70 退回原料仓; 生产仓批次「用掉 70、占着 30、已用完」
 *   撤回: 放掉占用 30 —— 修前: 生产仓「可用 30 但已用完」, 原料仓少 30, 没有任何出路
 *                      修后: 30 直接退回原料仓, 生产仓归零
 * </pre>
 *
 * <p>领料那一步 (确认领料把料从原料仓搬到生产仓) 用夹具直接写成搬完后的形状, 字段与
 * {@code FactoryMaterialRequisitionServiceImpl.relocatePickedMaterialToWorkshop} 写的一致
 * (源批次已用 +100、生产仓新批次 sourceDocType=MATERIAL_REQUISITION / sourceDocId=领料单、
 * 领料行 batchNumbers[*].workshopBatchId)。报工占用、关单、撤回都走真实服务。
 *
 * <p>为什么要真库: 撤回与关单并发时「重复退料」只能靠真实行锁排除, Mockito 证明不了。
 * 开关同 {@link SecondaryHoldPostgresContractTest}, 跑在 postgres-reversal-concurrency 车道。
 */
@SpringBootTest(classes = ClosedRequisitionReversalReturnPostgresContractTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoint.health.validate-group-membership=false",
        "spring.datasource.hikari.data-source-properties.stringtype=unspecified",
        "spring.datasource.hikari.maximum-pool-size=10"
})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class ClosedRequisitionReversalReturnPostgresContractTest {

    private static final String F = "C1-PG";
    private static final Long OPERATOR = 7L;
    private static final String RAW_WH = "RAW-WH";
    private static final String WORKSHOP_WH = "WKS-WH";

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @EntityScan("com.cretas.aims.entity")
    @EnableJpaRepositories("com.cretas.aims.repository")
    @Import({ProductionPlanServiceImpl.class, WipInventoryServiceImpl.class, SecondaryPlanFeedService.class,
            SettlementSemiStockingService.class, FinishedGoodsFeedServiceImpl.class,
            ReportReversalServiceImpl.class, ReversalConsumptionCleanupService.class,
            FactoryMaterialRequisitionServiceImpl.class})
    static class Runtime {
        /** 生产上它按 @Qualifier("primaryEntityManagerFactory") 取 EntityManager; 测试上下文只有一个 EMF。 */
        @Bean
        WorkshopReportReservationService workshopReportReservationService(
                MaterialBatchRepository materialBatches, ProductionBatchRepository productionBatches,
                ProductionReportRepository productionReports, WarehouseResolver warehouseResolver,
                EntityManagerFactory emf) {
            return new WorkshopReportReservationService(materialBatches, productionBatches, productionReports,
                    warehouseResolver, SharedEntityManagerCreator.createSharedEntityManager(emf));
        }
    }

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
    @MockBean NotificationService notificationService;
    @MockBean PermissionService permissionService;
    @MockBean UserRepository userRepository;
    @MockBean WarehouseResolver warehouseResolver;
    @MockBean com.cretas.aims.service.processentry.ClerkProcessEntryService clerkProcessEntryService;
    @MockBean com.cretas.aims.service.foodsafety.ccp.CcpMonitoringService ccpMonitoringService;

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ReportReversalService reversalService;
    @Autowired FactoryMaterialRequisitionService requisitionService;
    @Autowired WorkshopReportReservationService reservations;
    @Autowired ProductionPlanRepository plans;
    @Autowired ProductionBatchRepository batches;
    @Autowired ProductionReportRepository reports;
    @Autowired MaterialBatchRepository materialBatches;
    @Autowired FactoryMaterialRequisitionRepository requisitions;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        jdbc.execute("DO $$ DECLARE r record; BEGIN "
                + "FOR r IN SELECT conrelid::regclass AS t, conname FROM pg_constraint "
                + "WHERE contype = 'f' AND connamespace = 'public'::regnamespace LOOP "
                + "EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.t, r.conname); END LOOP; END $$");
        when(permissionService.hasPermission(any(), anyString())).thenReturn(true);
        when(userRepository.findById(any())).thenReturn(Optional.of(new User()));
        when(warehouseResolver.resolveWorkshopId(any())).thenReturn(WORKSHOP_WH);
        when(warehouseResolver.resolveFinishedGoodsId(any())).thenReturn("FG-WH");
        when(ownershipGuard.ofProductionBatch(any(), any())).thenReturn(IntermediateOwnershipGuard.Owner.COMPANY);
    }

    /** 领料 100 已搬到生产仓、报工占了 30 的现场。 */
    private record Fixture(String planId, Long batchId, String requisitionId, String itemId,
                           String sourceBatchId, String workshopBatchId) {
    }

    private Fixture issuedAndReported(String reportedKg) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Fixture f = tx.execute(state -> {
            ProductionPlan plan = new ProductionPlan();
            plan.setId("PLAN-C1-" + suffix);
            plan.setFactoryId(F);
            plan.setPlanNumber("C1-" + suffix);
            plan.setProductTypeId("FG-1");
            plan.setPlannedQuantity(new BigDecimal("100"));
            plan.setPlannedUnit("kg");
            plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
            plan.setCreatedBy(OPERATOR);
            plans.save(plan);

            ProductionBatch batch = new ProductionBatch();
            batch.setFactoryId(F);
            batch.setBatchNumber("PB-C1-" + suffix);
            batch.setProductTypeId("FG-1");
            batch.setQuantity(new BigDecimal("100"));
            batch.setUnit("kg");
            batch.setStatus(ProductionBatchStatus.IN_PROGRESS);
            batch.setProductionPlanId(plan.getId());
            batch = batches.save(batch);

            // 原料仓源批次: 收货 100, 确认领料时整批搬去生产仓 (已用 100)
            MaterialBatch source = materialBatch("SRC-" + suffix, RAW_WH, "100", "100", MaterialBatchStatus.DEPLETED);
            materialBatches.save(source);

            FactoryMaterialRequisition mr = new FactoryMaterialRequisition();
            mr.setFactoryId(F);
            mr.setRequisitionNo("MR-C1-" + suffix);
            mr.setProductionPlanId(plan.getId());
            mr.setSourceWarehouseId(RAW_WH);
            mr.setTargetWarehouseId(WORKSHOP_WH);
            mr.setStatus(FactoryMaterialRequisition.Status.ISSUED);
            mr.setRequestedBy(OPERATOR);
            mr = requisitions.save(mr);

            // 生产仓新批次 (与 relocatePickedMaterialToWorkshop 写的字段一致)
            MaterialBatch workshop = materialBatch("SRC-" + suffix + "-WKS", WORKSHOP_WH, "100", "0",
                    MaterialBatchStatus.AVAILABLE);
            workshop.setSourceDocType("MATERIAL_REQUISITION");
            workshop.setSourceDocId(mr.getId());
            materialBatches.save(workshop);

            FactoryMaterialRequisitionItem item = new FactoryMaterialRequisitionItem();
            item.setRequisition(mr);
            item.setMaterialTypeId("MT-1");
            item.setMaterialName("牛肉");
            item.setRequiredQty(new BigDecimal("100"));
            item.setPickedQty(new BigDecimal("100"));
            item.setIssuedQty(new BigDecimal("100"));
            item.setUnit("kg");
            Map<String, Object> row = new HashMap<>();
            row.put("batchId", source.getId());
            row.put("batchNo", source.getBatchNumber());
            row.put("qty", new BigDecimal("100"));
            row.put("workshopBatchId", workshop.getId());
            item.setBatchNumbers(new ArrayList<>(List.of(row)));
            mr.getItems().add(item);
            mr = requisitions.save(mr);

            return new Fixture(plan.getId(), batch.getId(), mr.getId(), mr.getItems().get(0).getId(),
                    source.getId(), workshop.getId());
        });
        // App 报工: 报工行与生产仓占用同一事务 (真实占用服务)
        tx.executeWithoutResult(state -> {
            ProductionReport report = new ProductionReport();
            report.setFactoryId(F);
            report.setWorkerId(OPERATOR);
            report.setReportType("YIELD");
            report.setReportDate(LocalDate.now());
            report.setBatchId(f.batchId());
            report.setProcessOrder(1);
            report.setProductTypeId("FG-1");
            report.setOutputQuantity(new BigDecimal(reportedKg));
            report.setApprovalStatus("APPROVED");
            Map<String, Object> ref = new HashMap<>();
            ref.put("materialBatchId", f.workshopBatchId());
            ref.put("quantity", new BigDecimal(reportedKg));
            ref.put("unit", "kg");
            report.setMaterialBatchRefs(new ArrayList<>(List.of(ref)));
            ProductionReport saved = reports.save(report);
            reservations.reserveForReport(F, saved);
        });
        return f;
    }

    private MaterialBatch materialBatch(String batchNumber, String warehouseId, String receipt, String used,
                                        MaterialBatchStatus status) {
        MaterialBatch b = new MaterialBatch();
        b.setId(UUID.randomUUID().toString());
        b.setFactoryId(F);
        b.setBatchNumber(batchNumber);
        b.setMaterialTypeId("MT-1");
        b.setReceiptQuantity(new BigDecimal(receipt));
        b.setUsedQuantity(new BigDecimal(used));
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setQuantityUnit("kg");
        b.setUnitPrice(new BigDecimal("10.00"));
        b.setReceiptDate(LocalDate.now());
        b.setExpireDate(LocalDate.now().plusDays(30));
        b.setOwnership(InventoryOwnership.COMPANY_OWNED);
        b.setWarehouseId(warehouseId);
        b.setStatus(status);
        b.setCreatedBy(OPERATOR);
        return b;
    }

    private void close(Fixture f) {
        requisitionService.close(F, f.requisitionId(), OPERATOR);
    }

    private void reverse(Fixture f) {
        ReportReversalLog log = reversalService.submitReversal(F, f.batchId(), OPERATOR, "改报工");
        if (log.getStatus() != ReportReversalLog.ReversalStatus.DONE) {
            reversalService.executeReversal(log.getId(), F);
        }
    }

    // ---------- 读数 (全部按真库现值) ----------

    private Map<String, Object> batchRow(String id) {
        return jdbc.queryForMap("select receipt_quantity, used_quantity, reserved_quantity, status "
                + "from material_batches where id = ?", id);
    }

    private BigDecimal available(String id) {
        Map<String, Object> r = batchRow(id);
        return ((BigDecimal) r.get("receipt_quantity")).subtract((BigDecimal) r.get("used_quantity"))
                .subtract((BigDecimal) r.get("reserved_quantity"));
    }

    private BigDecimal returnLedger(Fixture f) {
        return jdbc.queryForObject("select coalesce(sum(-quantity), 0) from material_consumptions "
                + "where batch_id = ? and source_type = 'MATERIAL_RETURN' and deleted_at is null",
                BigDecimal.class, f.sourceBatchId());
    }

    private BigDecimal productionReturnOrders(Fixture f) {
        return jdbc.queryForObject("select coalesce(sum(return_quantity), 0) from production_material_returns "
                + "where requisition_id = ? and material_batch_id = ?",
                BigDecimal.class, f.requisitionId(), f.sourceBatchId());
    }

    private int reversalReturnRows(Fixture f) {
        return jdbc.queryForObject("select count(*) from material_consumptions where batch_id = ? "
                + "and source_type = 'MATERIAL_RETURN' and notes like '%reason=report-reversal:%'",
                Integer.class, f.sourceBatchId());
    }

    private Map<String, Object> itemRow(Fixture f) {
        return jdbc.queryForMap("select consumed_qty, returned_qty from factory_material_requisition_items "
                + "where id = ?", f.itemId());
    }

    /**
     * 账能对平: 原料仓源批次现存 = 退料流水合计 = 生产退料单合计; 源批次 + 生产仓 (可用 + 占用) = 100。
     * 不论关单、撤回谁先谁后、有没有并发, 都必须成立 —— 重复退料会让第一条等式右边多出来 / 源批次已用量下溢。
     */
    private void assertLedgerBalanced(Fixture f, String step) {
        BigDecimal sourceAvailable = available(f.sourceBatchId());
        Map<String, Object> wks = batchRow(f.workshopBatchId());
        BigDecimal wksAvailable = available(f.workshopBatchId());
        assertThat(sourceAvailable).as(step + ": 原料仓现存 = 退料流水").isEqualByComparingTo(returnLedger(f));
        assertThat(sourceAvailable).as(step + ": 原料仓现存 = 生产退料单").isEqualByComparingTo(productionReturnOrders(f));
        assertThat(sourceAvailable.add(wksAvailable).add((BigDecimal) wks.get("reserved_quantity")))
                .as(step + ": 原料仓 + 生产仓 (可用 + 占用) = 领料 100").isEqualByComparingTo("100");
    }

    // ---------- 1. 复现 C1: 领料 → 报工 → 关单 → 撤回 ----------

    @Test
    void closeThenReverse_returnsReleasedQuantityToRawWarehouseWithReturnLedger() {
        Fixture f = issuedAndReported("30");
        assertThat(batchRow(f.workshopBatchId()).get("reserved_quantity")).as("报工占了 30")
                .satisfies(v -> assertThat((BigDecimal) v).isEqualByComparingTo("30"));

        close(f);
        assertThat(available(f.sourceBatchId())).as("关单按可用 70 退回").isEqualByComparingTo("70");
        assertLedgerBalanced(f, "关单后");

        reverse(f);

        Map<String, Object> wks = batchRow(f.workshopBatchId());
        assertThat(available(f.workshopBatchId())).as("生产仓批次不留余量").isEqualByComparingTo("0");
        assertThat((BigDecimal) wks.get("reserved_quantity")).as("占用已放").isEqualByComparingTo("0");
        assertThat(wks.get("status")).as("生产仓批次已用完").isEqualTo(MaterialBatchStatus.DEPLETED.name());
        assertThat(available(f.sourceBatchId())).as("原料仓收回全部 100").isEqualByComparingTo("100");
        assertThat(batchRow(f.sourceBatchId()).get("status")).as("原料仓批次重新可用")
                .isEqualTo(MaterialBatchStatus.AVAILABLE.name());
        assertThat(reversalReturnRows(f)).as("撤回补了一笔退料流水").isEqualTo(1);
        assertLedgerBalanced(f, "撤回后");
        Map<String, Object> item = itemRow(f);
        assertThat((BigDecimal) item.get("consumed_qty")).as("领料行实用归 0").isEqualByComparingTo("0");
        assertThat((BigDecimal) item.get("returned_qty")).as("领料行退回 100").isEqualByComparingTo("100");
    }

    // ---------- 2. 阴性对照: 领料单没关, 撤回仍把料放回生产仓 ----------

    @Test
    void reverseBeforeClose_keepsMaterialInWorkshopAndCloseReturnsItLater() {
        Fixture f = issuedAndReported("30");

        reverse(f);

        Map<String, Object> wks = batchRow(f.workshopBatchId());
        assertThat(available(f.workshopBatchId())).as("料回到生产仓").isEqualByComparingTo("100");
        assertThat((BigDecimal) wks.get("reserved_quantity")).isEqualByComparingTo("0");
        assertThat(wks.get("status")).isEqualTo(MaterialBatchStatus.AVAILABLE.name());
        assertThat(available(f.sourceBatchId())).as("原料仓没动").isEqualByComparingTo("0");
        assertThat(returnLedger(f)).as("未关单不写退料流水").isEqualByComparingTo("0");
        assertThat(reversalReturnRows(f)).isZero();

        close(f);
        assertThat(available(f.sourceBatchId())).as("之后关单按生产仓现存退回 100").isEqualByComparingTo("100");
        assertThat(reversalReturnRows(f)).as("退料来自关单, 不是撤回").isZero();
        assertLedgerBalanced(f, "撤回后关单");
    }

    // ---------- 3. 并发: 撤回与关单同时发生, 不重复退料 ----------

    @Test
    void concurrentCloseAndReverse_neverReturnTwice() throws Exception {
        int rounds = 6;
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < rounds; round++) {
                Fixture f = issuedAndReported("30");
                ReportReversalLog pending = reversalService.submitReversal(F, f.batchId(), 99L, "并发撤回");
                // 撤回可能当场完成 (快速通道), 那样就没有并发可测 —— 本测试用别人提交 (99) 走审批, 由我们执行。
                assertThat(pending.getStatus()).as("要的是待执行的撤回").isNotEqualTo(ReportReversalLog.ReversalStatus.DONE);

                // 第三个连接先锁住生产仓批次, 让撤回与关单都堵在同一把锁上, 放开后两者真正抢跑。
                CountDownLatch gateHeld = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);
                Future<?> gate = pool.submit(() -> {
                    try (Connection c = dataSource.getConnection()) {
                        c.setAutoCommit(false);
                        try (PreparedStatement ps = c.prepareStatement(
                                "select id from material_batches where id = ? for update")) {
                            ps.setString(1, f.workshopBatchId());
                            ps.executeQuery();
                        }
                        gateHeld.countDown();
                        release.await(30, TimeUnit.SECONDS);
                        c.rollback();
                    }
                    return null;
                });
                assertThat(gateHeld.await(30, TimeUnit.SECONDS)).isTrue();

                Callable<Throwable> closeCall = () -> attempt(() -> close(f));
                Callable<Throwable> reverseCall = () -> attempt(() -> reversalService.executeReversal(pending.getId(), F));
                Future<Throwable> first = pool.submit(round % 2 == 0 ? closeCall : reverseCall);
                Future<Throwable> second = pool.submit(round % 2 == 0 ? reverseCall : closeCall);
                Thread.sleep(300); // 两个事务都已走到生产仓批次的行锁上
                release.countDown();
                gate.get(30, TimeUnit.SECONDS);
                Throwable e1 = first.get(60, TimeUnit.SECONDS);
                Throwable e2 = second.get(60, TimeUnit.SECONDS);

                String step = "第 " + round + " 轮";
                assertThat(e1).as(step + " 先发的不应失败").isNull();
                assertThat(e2).as(step + " 后发的不应失败").isNull();
                assertThat(available(f.sourceBatchId())).as(step + " 原料仓恰好收回 100").isEqualByComparingTo("100");
                assertThat(available(f.workshopBatchId())).as(step + " 生产仓归零").isEqualByComparingTo("0");
                assertThat((BigDecimal) batchRow(f.workshopBatchId()).get("reserved_quantity")).isEqualByComparingTo("0");
                assertLedgerBalanced(f, step);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static Throwable attempt(Runnable work) {
        try {
            work.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }
}
