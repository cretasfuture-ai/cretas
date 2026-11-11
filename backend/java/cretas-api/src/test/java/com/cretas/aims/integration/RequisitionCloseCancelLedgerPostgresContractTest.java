package com.cretas.aims.integration;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
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
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
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
import com.cretas.aims.service.yield.InterimSettleReversalService;
import com.cretas.aims.service.yield.InterimSettleService;
import com.cretas.aims.utils.ExcelUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.LocalDateTime;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 排查 2026-11-10 C3 / C6 在真 PostgreSQL + 真 Hibernate + 真 Spring 事务上 (夹具照抄
 * {@code ClosedRequisitionReversalReturnPostgresContractTest}):
 * <ul>
 *   <li>C6: 结单族计划逐道录入消耗还没结单时关单 —— 关单用盘点同一个查询 (sumUnsettledConsumptionGroupedByBatch,
 *       带 process_sheet_rows 子查询) 在真库上数到待扣行并拒绝; 结单打戳后照常关单, 只退没用掉的。</li>
 *   <li>C3: 取消与调拨并发 —— 领料单没有乐观锁版本列, 取消不加行锁时会读到「备料中」并把已调拨的单改成已取消。</li>
 * </ul>
 * 本机禁 Docker/WSL 跑不了, 由 java-gate 的 postgres-reversal-concurrency 分片执行 (清单 java-gate-pg-tests.txt)。
 */
@SpringBootTest(classes = RequisitionCloseCancelLedgerPostgresContractTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoint.health.validate-group-membership=false",
        "spring.datasource.hikari.data-source-properties.stringtype=unspecified",
        "spring.datasource.hikari.maximum-pool-size=10"
})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class RequisitionCloseCancelLedgerPostgresContractTest {

    private static final String F = "C36-PG";
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
            FactoryMaterialRequisitionServiceImpl.class,
            com.cretas.aims.service.yield.impl.InterimSettleServiceImpl.class,
            com.cretas.aims.service.yield.impl.InterimSettleReversalServiceImpl.class})
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
    @Autowired MaterialConsumptionRepository consumptions;
    @Autowired ProcessSheetRowRepository rows;
    @Autowired InterimSettleService interimSettle;
    @Autowired InterimSettleReversalService undoInterim;

    private static final ObjectMapper JSON = new ObjectMapper();

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
        when(ownershipGuard.ofClerkAnchor(any(), any())).thenReturn(IntermediateOwnershipGuard.Owner.COMPANY);
        when(clerkProcessEntryService.resolveLaborRate(any(), any())).thenReturn(BigDecimal.ZERO);
        when(clerkProcessEntryService.computeLaborCost(any(), any())).thenReturn(BigDecimal.ZERO);
    }

    /** 领料 100 已搬到生产仓、报工占了 30 的现场。 */
    private record Fixture(String planId, Long batchId, String requisitionId, String itemId,
                           String sourceBatchId, String workshopBatchId) {
    }

    private Fixture issuedAndReported(String reportedKg) {
        Fixture f = issued(false);
        appReport(f, reportedKg);
        return f;
    }

    /** 领料 100 已搬到生产仓 (ISSUED); byStock = 存货生产计划 (走小结)。 */
    private Fixture issued(boolean byStock) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return tx.execute(state -> {
            ProductionPlan plan = new ProductionPlan();
            plan.setId("PLAN-C1-" + suffix);
            plan.setFactoryId(F);
            plan.setPlanNumber("C1-" + suffix);
            plan.setProductTypeId("FG-1");
            plan.setPlannedQuantity(new BigDecimal("100"));
            plan.setPlannedUnit("kg");
            plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
            plan.setCreatedBy(OPERATOR);
            if (byStock) {
                plan.setSourceType(PlanSourceType.SAFETY_STOCK);
            }
            plans.save(plan);

            ProductionBatch batch = new ProductionBatch();
            batch.setFactoryId(F);
            batch.setBatchNumber("PB-C1-" + suffix);
            batch.setProductTypeId("FG-1");
            batch.setQuantity(new BigDecimal("100"));
            batch.setUnit("kg");
            batch.setStatus(ProductionBatchStatus.IN_PROGRESS);
            batch.setProductionPlanId(plan.getId());
            batch.setBatchType("REGULAR");
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
    }

    /** App 报工: 报工行与生产仓占用同一事务 (真实占用服务)。 */
    private void appReport(Fixture f, String reportedKg) {
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

    private static ProcessSheetRow finishedRow(String planId, ProductionBatch batch) {
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setClientRowId("c2-" + batch.getId());
        req.setProcessCode("step1");
        req.setProcessOrder(1);
        req.setProductTypeId("FG-1");
        req.setFinished(true);
        req.setOutputQuantity(new BigDecimal("30"));
        req.setUnit("kg");
        req.setUpstreamSources(new ArrayList<>());
        ProcessSheetRow row = new ProcessSheetRow();
        row.setFactoryId(F);
        row.setPlanId(planId);
        row.setProcessCode("step1");
        row.setProcessOrder(1);
        row.setClientRowId(req.getClientRowId());
        row.setBatchId(batch.getId());
        row.setBatchNumber(batch.getBatchNumber());
        row.setRowStatus("SAVED");
        row.setSubmissionStatus(ProcessSheetRow.SUBMISSION_SUBMITTED);
        try {
            row.setRowPayload(JSON.writeValueAsString(req));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return row;
    }

    private String requisitionStatus(Fixture f) {
        return jdbc.queryForObject("select status from factory_material_requisitions where id = ?",
                String.class, f.requisitionId());
    }

    // =====================================================================================
    // 排查 2026-11-10 C6: 结单族 (非存货生产) 计划, 逐道录入消耗还没结单时关单 —— 真库上真查询
    // =====================================================================================

    /** 非存货生产计划 + 成品道录入行 + 逐道录入写的未结原料消耗 (生产仓批次, 结单时才扣)。 */
    private Fixture orderPlanIssuedAndClerkReported(String reportedKg) {
        Fixture f = issued(false);
        tx.executeWithoutResult(state -> {
            ProductionBatch fin = batches.findById(f.batchId()).orElseThrow();
            rows.save(finishedRow(f.planId(), fin));
            MaterialConsumption mc = new MaterialConsumption();
            mc.setFactoryId(F);
            mc.setProductionPlanId(f.planId());
            mc.setProductionBatchId(f.batchId());
            mc.setBatchId(f.workshopBatchId());
            mc.setQuantity(new BigDecimal(reportedKg));
            mc.setUnitPrice(new BigDecimal("10.00"));
            mc.setTotalCost(new BigDecimal(reportedKg).multiply(new BigDecimal("10.00")));
            mc.setConsumptionTime(LocalDateTime.now());
            mc.setConsumedAt(LocalDateTime.now());
            mc.setRecordedBy(OPERATOR);
            mc.setMaterialTypeId("MT-1");
            mc.setSourceType("RAW_MATERIAL");
            consumptions.save(mc);
        });
        return f;
    }

    @Test
    void c6_orderPlanCloseBeforeSettlement_isBlockedOnRealQuery_noPhantomReturn() {
        Fixture f = orderPlanIssuedAndClerkReported("30");
        BigDecimal sourceBefore = available(f.sourceBatchId());

        assertThatThrownBy(() -> close(f))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("PRODUCTION_REQUISITION_CLOSE_BEFORE_SETTLEMENT");
                    assertThat(e.getActionHint()).contains("结单");
                });
        assertThat(requisitionStatus(f)).isEqualTo("ISSUED");
        assertThat(available(f.sourceBatchId())).as("原料仓没多出已用掉的 30").isEqualByComparingTo(sourceBefore);
        assertThat(available(f.workshopBatchId())).as("生产仓没被划空").isEqualByComparingTo("100");
        assertThat(returnLedger(f)).isEqualByComparingTo("0");
    }

    @Test
    void c6_afterSettlementStamp_closeProceeds() {
        Fixture f = orderPlanIssuedAndClerkReported("30");
        // 结单: 扣生产仓 30 并给逐道录入消耗行打戳 (与 settleProduction 的两步同形, 这里直接写库)
        jdbc.update("update material_batches set used_quantity = used_quantity + 30 where id = ?", f.workshopBatchId());
        jdbc.update("update material_consumptions set interim_settled_at = now() where batch_id = ?", f.workshopBatchId());

        close(f);
        assertThat(requisitionStatus(f)).isEqualTo("CLOSED");
        assertThat(available(f.sourceBatchId())).as("只退回没用掉的 70").isEqualByComparingTo("70");
        assertThat(available(f.workshopBatchId())).isEqualByComparingTo("0");
    }

    // =====================================================================================
    // 排查 2026-11-10 C3: 取消与调拨并发 —— 取消必须拿同一把领料单行锁, 读到对方提交后的「已调拨」
    // =====================================================================================

    // =====================================================================================
    // #1606 独立审查 M4: 中途小结过账前按领料同一口径判原料归属 (真库, 真小结)
    // =====================================================================================

    /** 存货生产 (走小结) + 成品道录入行 + 一行未小结的原料消耗 (生产仓批次)。 */
    private Fixture byStockReportedOn(InventoryOwnership ownership) {
        Fixture f = orderPlanIssuedAndClerkReported("30");
        jdbc.update("update production_plans set source_type = 'SAFETY_STOCK' where id = ?", f.planId());
        jdbc.update("update material_batches set ownership = ?, owner_customer_id = ? where id = ?",
                ownership.name(), ownership == InventoryOwnership.CUSTOMER_OWNED ? "CUST-X" : null, f.workshopBatchId());
        return f;
    }

    private BigDecimal unsettledConsumption(Fixture f) {
        return jdbc.queryForObject("select coalesce(sum(quantity), 0) from material_consumptions where batch_id = ? "
                + "and interim_settled_at is null and deleted_at is null", BigDecimal.class, f.workshopBatchId());
    }

    @Test
    void m4_interimSettle_companyPlanWithCustomerOwnedSource_isRefused_nothingPosted() {
        Fixture f = byStockReportedOn(InventoryOwnership.CUSTOMER_OWNED);
        assertThatThrownBy(() -> interimSettle.interimSettle(F, f.planId(), OPERATOR))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("CUSTOMER_OWNED_MATERIAL_FORBIDDEN"));
        assertThat(available(f.workshopBatchId())).as("客户料没被扣").isEqualByComparingTo("100");
        assertThat(unsettledConsumption(f)).as("消耗行仍未小结").isEqualByComparingTo("30");
    }

    /** 阳性对照: 同一夹具换成公司料, 小结照常扣 30 —— 上一条的拒绝不是夹具或小结本身坏了。 */
    @Test
    void m4_interimSettle_companyPlanWithCompanySource_posts() {
        Fixture f = byStockReportedOn(InventoryOwnership.COMPANY_OWNED);
        interimSettle.interimSettle(F, f.planId(), OPERATOR);
        assertThat(available(f.workshopBatchId())).isEqualByComparingTo("70");
        assertThat(unsettledConsumption(f)).isEqualByComparingTo("0");
    }

    // #1606 独立审查 L10: 原用例用「拿锁 + 改状态」模拟调拨, 只证明了取消会等锁, 证明不了调拨本身加了锁。
    //   下面两条都走真实的 transferToFactory (锁领料单 → 按已拣批次从原料仓划出 → 生产仓建批次 → 推到已调拨)。

    /** 备料中 (PICKING) 的领料单: 已确认拣货 100, 料还在原料仓源批次上 (可用 100), 尚未调拨。 */
    private Fixture picked() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return tx.execute(state -> {
            ProductionPlan plan = new ProductionPlan();
            plan.setId("PLAN-C3-" + suffix);
            plan.setFactoryId(F);
            plan.setPlanNumber("C3-" + suffix);
            plan.setProductTypeId("FG-1");
            plan.setPlannedQuantity(new BigDecimal("100"));
            plan.setPlannedUnit("kg");
            plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
            plan.setCreatedBy(OPERATOR);
            plans.save(plan);

            MaterialBatch source = materialBatch("SRC-C3-" + suffix, RAW_WH, "100", "0", MaterialBatchStatus.AVAILABLE);
            materialBatches.save(source);

            FactoryMaterialRequisition mr = new FactoryMaterialRequisition();
            mr.setFactoryId(F);
            mr.setRequisitionNo("MR-C3-" + suffix);
            mr.setProductionPlanId(plan.getId());
            mr.setSourceWarehouseId(RAW_WH);
            mr.setTargetWarehouseId(WORKSHOP_WH);
            mr.setStatus(FactoryMaterialRequisition.Status.PICKING);
            mr.setRequestedBy(OPERATOR);
            mr = requisitions.save(mr);

            FactoryMaterialRequisitionItem item = new FactoryMaterialRequisitionItem();
            item.setRequisition(mr);
            item.setMaterialTypeId("MT-1");
            item.setMaterialName("牛肉");
            item.setRequiredQty(new BigDecimal("100"));
            item.setPickedQty(new BigDecimal("100"));
            item.setUnit("kg");
            Map<String, Object> row = new HashMap<>();
            row.put("batchId", source.getId());
            row.put("batchNo", source.getBatchNumber());
            row.put("qty", new BigDecimal("100"));
            item.setBatchNumbers(new ArrayList<>(List.of(row)));
            mr.getItems().add(item);
            mr = requisitions.save(mr);
            return new Fixture(plan.getId(), null, mr.getId(), mr.getItems().get(0).getId(), source.getId(), null);
        });
    }

    private int workshopBatchesOf(Fixture f) {
        return jdbc.queryForObject("select count(*) from material_batches where source_doc_type = 'MATERIAL_REQUISITION' "
                + "and source_doc_id = ? and deleted_at is null", Integer.class, f.requisitionId());
    }

    /** 阳性对照: 这份夹具真调拨能走通 (料从原料仓搬到生产仓), 之后取消返回 409 —— 下一条用例的拒绝不是夹具坏了。 */
    @Test
    void c3_realTransferMovesMaterial_thenCancelIsRefused() {
        Fixture f = picked();
        requisitionService.transferToFactory(F, f.requisitionId(), OPERATOR);
        assertThat(requisitionStatus(f)).isEqualTo("TRANSFERRED");
        assertThat(available(f.sourceBatchId())).as("原料仓源批次已划出").isEqualByComparingTo("0");
        assertThat(workshopBatchesOf(f)).as("生产仓建了批次").isEqualTo(1);

        assertThatThrownBy(() -> requisitionService.cancel(F, f.requisitionId(), OPERATOR, "调拨后取消"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("PRODUCTION_REQUISITION_CANCEL_AFTER_TRANSFER"));
        assertThat(requisitionStatus(f)).isEqualTo("TRANSFERRED");
    }

    /**
     * 取消先拿到领料单行锁 (未提交), 真实调拨随后开始: 调拨必须在读单子时就等这把锁, 等到取消提交后读到「已取消」并拒绝。
     * 调拨不加锁时它会读到「备料中」, 把料搬去生产仓, 最后的整行 UPDATE 再把「已取消」覆盖成「已调拨」——
     * 单子被取消了、料却已经搬走 (C3 原形)。
     */
    @Test
    void c3_realTransferWaitsForCancelLock_andThenRefuses() throws Exception {
        Fixture f = picked();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            // T1 = 取消 (真实服务), 外层事务拿着行锁不提交, 等放行
            Future<?> cancel = pool.submit(() -> tx.executeWithoutResult(s -> {
                requisitionService.cancel(F, f.requisitionId(), OPERATOR, "并发取消");
                lockHeld.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(lockHeld.await(30, TimeUnit.SECONDS)).isTrue();

            // T2 = 真实调拨
            Future<Throwable> transfer = pool.submit(() -> {
                try {
                    requisitionService.transferToFactory(F, f.requisitionId(), OPERATOR);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            Thread.sleep(1500);
            assertThat(transfer.isDone()).as("调拨要等取消的行锁").isFalse();

            release.countDown();
            cancel.get(30, TimeUnit.SECONDS);
            Throwable outcome = transfer.get(30, TimeUnit.SECONDS);
            assertThat(outcome).as("调拨等到锁后读到已取消, 必须按状态拒绝").isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getMessage()).contains("当前 CANCELLED"));
            assertThat(requisitionStatus(f)).isEqualTo("CANCELLED");
            assertThat(available(f.sourceBatchId())).as("料没被搬走").isEqualByComparingTo("100");
            assertThat(workshopBatchesOf(f)).as("生产仓没有建批次").isZero();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
