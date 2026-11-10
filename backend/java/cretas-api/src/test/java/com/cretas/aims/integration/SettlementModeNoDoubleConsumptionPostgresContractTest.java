package com.cretas.aims.integration;

import com.cretas.aims.dto.orchestration.MaterialAllocation;
import com.cretas.aims.dto.orchestration.MaterialCheckResult;
import com.cretas.aims.dto.orchestration.MaterialRequirement;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.production.ProductionSettlementRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.event.BatchCompletedEvent;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.ProductionPlanService;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.canvas.impl.ThresholdResolverServiceImpl;
import com.cretas.aims.service.discipline.FactoryDisciplinePolicy;
import com.cretas.aims.service.impl.BatchConsumptionServiceImpl;
import com.cretas.aims.service.impl.MaterialBatchServiceImpl;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.service.inventory.impl.FinishedGoodsFeedServiceImpl;
import com.cretas.aims.service.orchestration.BomExpansionService;
import com.cretas.aims.service.orchestration.SupplyChainOrchestrator;
import com.cretas.aims.service.processentry.IntermediateOwnershipGuard;
import com.cretas.aims.service.production.CustomerSuppliedSettlementCoverageGuard;
import com.cretas.aims.service.production.SecondaryPlanFeedService;
import com.cretas.aims.service.production.SettlementSemiStockingService;
import com.cretas.aims.service.unit.impl.UnitContractServiceImpl;
import com.cretas.aims.service.wip.ProductFamilyResolver;
import com.cretas.aims.service.wip.impl.WipInventoryServiceImpl;
import com.cretas.aims.utils.ExcelUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 纪律排查 A7 (#1582 复核 2026-11-11): 「完工必须先结单」默认开之后, 同一批料不能被扣两次。
 *
 * <p>真 PostgreSQL 走一条完整链: 原料入库 (100 kg) → 报工 (末道产出行) → 批次完工 (真编排器
 * {@link SupplyChainOrchestrator#onBatchCompleted}, 真自动扣料 {@link BatchConsumptionServiceImpl}, 真
 * {@code MaterialBatchServiceImpl.useBatchQuantity}) → 结单 (真 {@code settleProduction}, 实际领用 8 kg)。
 * 工厂开关读真 {@code factory_thresholds} 表 (真 {@link FactoryDisciplinePolicy} + 真阈值解析)。
 *
 * <p>只有「BOM 展开」打桩 (产品 10 kg 需要这批原料 10 kg) —— 它不是被测对象; 扣库存、写消耗行、结单过账全部落真库。
 * <ul>
 *   <li>开关开 (默认): 批次完工不扣料、不写 AUTO_BOM 消耗行; 结单后原料 used = 实际 8 kg (不是 10 + 8)。</li>
 *   <li>开关关 (阴性对照, 改动前行为): 批次完工照旧按 BOM 扣 10 kg 并写 AUTO_BOM 行, 计划被批次完工结束。</li>
 * </ul>
 * 开关同 {@link SettlementOverPlanUnitPostgresContractTest}, 跑在 postgres-reversal-concurrency 车道 (清单 java-gate-pg-tests.txt)。
 */
@SpringBootTest(classes = SettlementModeNoDoubleConsumptionPostgresContractTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoint.health.validate-group-membership=false",
        "spring.datasource.hikari.data-source-properties.stringtype=unspecified",
        "spring.datasource.hikari.maximum-pool-size=8"
})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class SettlementModeNoDoubleConsumptionPostgresContractTest {

    private static final String F_ON = "A7-PG-ON";
    private static final String F_OFF = "A7-PG-OFF";
    private static final Long OPERATOR = 7L;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final BigDecimal BOM_QTY = new BigDecimal("10");
    private static final BigDecimal ACTUAL_QTY = new BigDecimal("8");

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @EntityScan("com.cretas.aims.entity")
    @EnableJpaRepositories("com.cretas.aims.repository")
    @Import({ProductionPlanServiceImpl.class, WipInventoryServiceImpl.class, SecondaryPlanFeedService.class,
            SettlementSemiStockingService.class, FinishedGoodsFeedServiceImpl.class,
            CustomerSuppliedSettlementCoverageGuard.class, UnitContractServiceImpl.class,
            SupplyChainOrchestrator.class, BatchConsumptionServiceImpl.class, MaterialBatchServiceImpl.class,
            FactoryDisciplinePolicy.class, ThresholdResolverServiceImpl.class})
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

    // 与 SettlementOverPlanUnitPostgresContractTest 相同的结单旁路
    @MockBean ProductionPlanMapper planMapper;
    @MockBean SchedulingService schedulingService;
    @MockBean ExcelUtil excelUtil;
    @MockBean InventoryLowStockEventPublisher lowStockEvents;
    @MockBean ProductFamilyResolver familyResolver;
    @MockBean IntermediateOwnershipGuard ownershipGuard;
    @MockBean com.cretas.aims.service.workprocess.WorkProcessTaskService workProcessTaskService;
    @MockBean com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope processSecretLlmCostScope;
    @MockBean com.cretas.aims.service.finance.ProductionCostConsistency productionCostConsistency;
    @MockBean com.cretas.aims.service.finance.ReturnedCostSynchronizationService returnedCostSynchronizationService;
    @MockBean PermissionService permissionService;
    @MockBean UserRepository userRepository;
    // 编排器 / 自动扣料 / 原料批次服务里与本链无关的协作者
    @MockBean BomExpansionService bomExpansionService;
    @MockBean com.cretas.aims.service.orchestration.InventoryMatchingService inventoryMatchingService;
    @MockBean com.cretas.aims.service.orchestration.ProcurementSuggestionService procurementSuggestionService;
    @MockBean com.cretas.aims.service.QualityInspectionService qualityInspectionService;
    @MockBean com.cretas.aims.service.sales.SellableLineDomainResolver sellableLineDomainResolver;
    @MockBean com.cretas.aims.service.factory.WarehouseResolver warehouseResolver;
    @MockBean com.cretas.aims.mapper.MaterialBatchMapper materialBatchMapper;
    @MockBean com.cretas.aims.service.FuturePlanMatchingService futurePlanMatchingService;
    @MockBean com.cretas.aims.service.warehouse.WarehouseLocationCatalog locationCatalog;

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ProductionPlanService planService;
    @Autowired SupplyChainOrchestrator orchestrator;
    @Autowired ProductionPlanRepository plans;
    @Autowired ProductionBatchRepository batches;
    @Autowired ProcessSheetRowRepository rows;
    @Autowired ProductTypeRepository productTypes;
    @Autowired RawMaterialTypeRepository rawTypes;
    @Autowired MaterialBatchRepository materialBatches;
    @Autowired BomRecipeRepository recipes;
    @Autowired BomRecipeItemRepository bomItems;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        jdbc.execute("DO $$ DECLARE r record; BEGIN "
                + "FOR r IN SELECT conrelid::regclass AS t, conname FROM pg_constraint "
                + "WHERE contype = 'f' AND connamespace = 'public'::regnamespace LOOP "
                + "EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.t, r.conname); END LOOP; END $$");
        when(ownershipGuard.ofClerkAnchor(any(), any())).thenReturn(IntermediateOwnershipGuard.Owner.COMPANY);
        when(permissionService.hasPermission(any(), anyString())).thenReturn(true);
        when(userRepository.findById(any())).thenReturn(Optional.of(new User()));
        when(warehouseResolver.resolveWorkshopId(anyString())).thenReturn("WH-WKS");
        when(warehouseResolver.resolveFinishedGoodsId(anyString())).thenReturn("WH-FG");
        when(warehouseResolver.resolveLogisticsId(anyString())).thenReturn("WH-LOG");
        when(warehouseResolver.isWorkshopWarehouse(anyString(), eq("WH-WKS"))).thenReturn(true);   // 原料已领到生产仓
        // 工厂开关落真表: 全局默认开 (= 迁移 V20261110_81), A7-PG-OFF 显式关
        threshold("*", "1");
        threshold(F_OFF, "0");
    }

    private void threshold(String factoryId, String value) {
        jdbc.update("insert into factory_thresholds (id, factory_id, threshold_key, category, value_type,"
                        + " threshold_value, default_value, enabled, version, created_at, updated_at)"
                        + " select gen_random_uuid(), ?, ?, 'PRODUCTION', 'INTEGER', ?, '1', true, 0, now(), now()"
                        + " where not exists (select 1 from factory_thresholds where factory_id = ? and threshold_key = ?"
                        + " and deleted_at is null)",
                factoryId, ThresholdKeys.PRODUCTION_COMPLETION_REQUIRE_SETTLEMENT, value,
                factoryId, ThresholdKeys.PRODUCTION_COMPLETION_REQUIRE_SETTLEMENT);
    }

    private record Fixture(String planId, Long batchId, String materialBatchId, String materialTypeId) { }

    /** 原料入库 100 kg + 计划 10 kg + 进行中的生产批次 + 末道报工 10 kg。BOM 展开: 这批原料 10 kg。 */
    private Fixture fixture(String factoryId) {
        String s = UUID.randomUUID().toString().substring(0, 8);
        Fixture f = tx.execute(state -> {
            RawMaterialType rm = new RawMaterialType();
            rm.setId("RM-" + s);
            rm.setFactoryId(factoryId);
            rm.setCode("RM-" + s);
            rm.setName("冻猪肉-" + s);
            rm.setUnit("kg");
            rm.setMainCategory("原料");   // 结单按大类判归属; 大类为空时结单原有 NPE, 见 PR 说明
            rawTypes.saveAndFlush(rm);

            MaterialBatch mb = new MaterialBatch();
            mb.setId("MB-" + s);
            mb.setFactoryId(factoryId);
            mb.setBatchNumber("MB-" + s);
            mb.setMaterialTypeId(rm.getId());
            mb.setReceiptDate(LocalDate.now());
            mb.setWarehouseId("WH-WKS");
            mb.setReceiptQuantity(new BigDecimal("100"));
            mb.setQuantityUnit("kg");
            mb.setUsedQuantity(BigDecimal.ZERO);
            mb.setReservedQuantity(BigDecimal.ZERO);
            mb.setUnitPrice(new BigDecimal("20"));
            mb.setStatus(MaterialBatchStatus.AVAILABLE);
            mb.setSourceDocType("PURCHASE_RECEIVE");
            mb.setCreatedBy(OPERATOR);
            materialBatches.saveAndFlush(mb);

            ProductType product = new ProductType();
            product.setFactoryId(factoryId);
            product.setCode("P-" + s);
            product.setName("卤肉-" + s);
            product.setUnit("kg");
            product = productTypes.save(product);

            // 产品 BOM: 每 kg 成品用这种原料 1 kg (结单只认 BOM 内原料; 自动扣料的展开另由桩给出 10 kg)
            BomRecipe bom = new BomRecipe();
            bom.setId("BOM-A7-" + s);
            bom.setFactoryId(factoryId);
            bom.setRecipeCode("BOM-A7-" + s);
            bom.setProductTypeId(product.getId());
            bom.setProductName(product.getName());
            bom.setVersion(1);
            bom.setIsCurrent(true);
            bom.setOutputQuantityPerUnit(BigDecimal.ONE);
            bom.setOutputUnit("kg");
            bom.setStatus(BomRecipe.Status.ACTIVE);
            recipes.saveAndFlush(bom);
            BomRecipeItem item = new BomRecipeItem();
            item.setRecipeId(bom.getId());
            item.setFactoryId(factoryId);
            item.setMaterialTypeId(rm.getId());
            item.setMaterialName(rm.getName());
            item.setStandardQuantity(BigDecimal.ONE);
            item.setYieldRate(new BigDecimal("100.00"));
            item.setUnit("kg");
            item.setQuantityToPriceFactor(BigDecimal.ONE);
            item.setMaterialCategory("RAW");
            item.setSortOrder(0);
            item.setIsOptional(false);
            item.setPerPortion(false);
            bomItems.saveAndFlush(item);

            ProductionPlan plan = new ProductionPlan();
            plan.setId("PLAN-A7-" + s);
            plan.setFactoryId(factoryId);
            plan.setPlanNumber("A7-" + s);
            plan.setProductTypeId(product.getId());
            plan.setPlannedQuantity(new BigDecimal("10"));
            plan.setPlannedUnit("kg");
            plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
            plan.setCreatedBy(OPERATOR);
            plans.save(plan);

            ProductionBatch batch = new ProductionBatch();
            batch.setFactoryId(factoryId);
            batch.setBatchNumber("PB-A7-" + s);
            batch.setProductTypeId(product.getId());
            batch.setProductName(product.getName());
            batch.setQuantity(new BigDecimal("10"));
            batch.setUnit("kg");
            batch.setStatus(ProductionBatchStatus.IN_PROGRESS);
            batch.setProductionPlanId(plan.getId());
            batch.setBatchType("REGULAR");
            batch.setCreatedBy(OPERATOR);
            batch = batches.save(batch);

            ProcessSheetRowRequest req = new ProcessSheetRowRequest();
            req.setClientRowId("final-" + s);
            req.setProcessCode("pack");
            req.setProcessOrder(1);
            req.setProductTypeId(product.getId());
            req.setFinished(true);
            req.setOutputQuantity(new BigDecimal("10"));
            req.setUnit("kg");
            req.setUpstreamSources(new ArrayList<>());
            ProcessSheetRow row = new ProcessSheetRow();
            row.setFactoryId(factoryId);
            row.setPlanId(plan.getId());
            row.setProcessCode("pack");
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
            rows.save(row);
            return new Fixture(plan.getId(), batch.getId(), mb.getId(), rm.getId());
        });

        MaterialRequirement need = new MaterialRequirement();
        need.setMaterialTypeId(f.materialTypeId());
        need.setRequiredQuantity(BOM_QTY);
        when(bomExpansionService.expandBOM(eq(factoryId), anyString(), any())).thenReturn(List.of(need));
        when(bomExpansionService.checkMaterialAvailability(eq(factoryId), any())).thenReturn(new MaterialCheckResult(
                true, List.of(), List.of(new MaterialAllocation(f.materialTypeId(), f.materialBatchId(),
                        "MB", BOM_QTY))));
        return f;
    }

    /** 批次完工: 与 ProcessingServiceImpl.completeProduction 落库后发的同一个事件, 交给真编排器处理。 */
    private void completeBatch(Fixture f) {
        ProductionBatch done = tx.execute(state -> {
            ProductionBatch b = batches.findById(f.batchId()).orElseThrow();
            b.setStatus(ProductionBatchStatus.COMPLETED);
            b.setActualQuantity(new BigDecimal("10"));
            b.setGoodQuantity(new BigDecimal("10"));
            b.setSupervisorId(OPERATOR);
            return batches.save(b);
        });
        orchestrator.onBatchCompleted(new BatchCompletedEvent(this, done));
    }

    /** 结单: 实际领用这批原料 8 kg (人工核对的实际明细)。 */
    private void settle(String factoryId, Fixture f) {
        ProductionSettlementRequest req = ProductionSettlementRequest.builder()
                .confirm(true)
                .idempotencyKey("k-" + f.planId())
                .manualOverride(true)
                .manualOverrideReason("A7 重复扣料核账")
                .laborDeferredReason("工时稍后补录")
                .actualFinishedQuantity(new BigDecimal("10"))
                .actualSemiFinishedQuantity(BigDecimal.ZERO)
                .rawMaterialConsumptions(new ArrayList<>(List.of(ProductionSettlementRequest.ConsumptionLine.builder()
                        .materialBatchId(f.materialBatchId())
                        .materialTypeId(f.materialTypeId())
                        .quantity(ACTUAL_QTY)
                        .unit("kg")
                        .build())))
                .semiFinishedConsumptions(new ArrayList<>())
                .auxiliaryConsumptions(new ArrayList<>())
                .build();
        planService.settleProduction(factoryId, f.planId(), req, OPERATOR);
    }

    private BigDecimal usedOf(String materialBatchId) {
        return jdbc.queryForObject("select used_quantity from material_batches where id = ?",
                BigDecimal.class, materialBatchId);
    }

    private int autoBomRowsOf(Long batchId) {
        return jdbc.queryForObject("select count(*) from material_consumptions where production_batch_id = ?"
                + " and source_type = 'AUTO_BOM'", Integer.class, batchId);
    }

    private String statusOf(String planId) {
        return jdbc.queryForObject("select status from production_plans where id = ?", String.class, planId);
    }

    @Test
    void switchOnBatchCompletionDoesNotConsumeAndSettlementDeductsExactlyTheActualQuantity() {
        Fixture f = fixture(F_ON);

        completeBatch(f);
        assertThat(usedOf(f.materialBatchId())).as("开关开: 批次完工不按 BOM 扣料").isEqualByComparingTo("0");
        assertThat(autoBomRowsOf(f.batchId())).as("开关开: 不写 AUTO_BOM 消耗行").isZero();
        assertThat(statusOf(f.planId())).as("开关开: 计划不被批次完工结束, 等结单").isEqualTo("IN_PROGRESS");

        settle(F_ON, f);
        assertThat(usedOf(f.materialBatchId())).as("原料扣减总量 = 实际领用 8 kg, 不是 BOM 10 + 实际 8")
                .isEqualByComparingTo(ACTUAL_QTY);
        assertThat(autoBomRowsOf(f.batchId())).as("全链走完仍没有 AUTO_BOM 消耗行").isZero();
        assertThat(statusOf(f.planId())).isEqualTo("COMPLETED");
    }

    @Test
    void switchOffBatchCompletionStillConsumesByBomAsBefore() {
        Fixture f = fixture(F_OFF);

        completeBatch(f);
        assertThat(usedOf(f.materialBatchId())).as("开关关 (改动前行为): 批次完工按 BOM 扣 10 kg")
                .isEqualByComparingTo(BOM_QTY);
        assertThat(autoBomRowsOf(f.batchId())).as("开关关: 写一行 AUTO_BOM 消耗").isEqualTo(1);
        assertThat(statusOf(f.planId())).as("开关关: 计划被批次完工结束").isEqualTo("COMPLETED");
    }
}
