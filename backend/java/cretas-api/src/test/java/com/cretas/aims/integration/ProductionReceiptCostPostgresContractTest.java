package com.cretas.aims.integration;

import com.cretas.aims.dto.production.ProductionWarehouseReceiptRequest;
import com.cretas.aims.dto.production.ProductionWarehouseReceiptResponse;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionSettlement;
import com.cretas.aims.entity.ProductionSettlementOutputLine;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionSettlementOutputLineRepository;
import com.cretas.aims.repository.ProductionSettlementRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.finance.ProductionCostConsistency;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.service.yield.OrderCostBreakdownService;
import com.cretas.aims.utils.ExcelUtil;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Opt-in real PostgreSQL proof for the actual two-step warehouse receipt path. */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.minimum-idle=1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
@EntityScan("com.cretas.aims.entity")
@EnableJpaRepositories(basePackageClasses = {
        ProductionSettlementRepository.class, ProductionSettlementOutputLineRepository.class,
        FinishedGoodsBatchRepository.class})
class ProductionReceiptCostPostgresContractTest {

    private static final String FACTORY_ID = "F006";
    private static final String PLAN_ID = "PG-COST-PLAN";
    private static final String SETTLEMENT_ID = "PG-COST-SETTLEMENT";
    private static final String BATCH_NUMBER = "PG-COST-BATCH";
    private static final String PRIVATE_SCHEMA =
            "receipt_cost_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        createPrivateSchema(url);
        String scopedUrl = url + (url.contains("?") ? "&" : "?")
                + "currentSchema=" + PRIVATE_SCHEMA
                + "&stringtype=unspecified&ApplicationName=cretas_receipt_cost_test";
        registry.add("spring.datasource.url", () -> scopedUrl);
        registry.add("spring.datasource.username", () -> requiredEnv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @AfterAll
    static void dropPrivateSchema() throws Exception {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        try (Connection connection = DriverManager.getConnection(
                url, requiredEnv("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS \"" + PRIVATE_SCHEMA + "\" CASCADE");
        }
    }

    private static void createPrivateSchema(String url) {
        try (Connection connection = DriverManager.getConnection(
                url, requiredEnv("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS \"" + PRIVATE_SCHEMA + "\"");
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create private PostgreSQL test schema", exception);
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the real PostgreSQL contract test");
        }
        return value;
    }

    @Autowired private ProductionSettlementRepository settlementRepository;
    @Autowired private ProductionSettlementOutputLineRepository outputLineRepository;
    @Autowired private FinishedGoodsBatchRepository finishedGoodsRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private com.cretas.aims.repository.inventory.ReturnOrderItemRepository returnItems;
    @Autowired private com.cretas.aims.repository.inventory.ReturnOrderRepository returnOrders;
    @Autowired private com.cretas.aims.repository.inventory.FinishedGoodsAdjustmentLogRepository adjustmentLogs;

    private ProductionPlanServiceImpl service;
    private ProductionPlanRepository planRepository;
    private ProductTypeRepository productTypeRepository;
    private OrderCostBreakdownService costService;
    private WarehouseResolver warehouseResolver;
    private ProductionCostConsistency productionCostConsistency;

    @BeforeEach
    void setUpFixture() {
        entityManager.clear();
        jdbcTemplate.execute("TRUNCATE TABLE production_settlement_output_lines, production_settlements, "
                + "finished_goods_batches, disposal_records, return_order_items, return_orders, "
                + "finished_goods_adjustment_log, product_types, factories CASCADE");
        jdbcTemplate.update("INSERT INTO factories "
                + "(id,name,type,level,is_active,manually_verified,ai_weekly_quota,created_at,updated_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?)",
                FACTORY_ID, "PG cost factory", "FACTORY", 0, true, false, 20,
                LocalDateTime.now(), LocalDateTime.now());
        jdbcTemplate.update("INSERT INTO product_types "
                + "(id,factory_id,code,name,unit,inspection_required,is_active,created_at,updated_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?)",
                "FG-SKU-A", FACTORY_ID, "PG-FG-A", "PG finished good", "kg", false, true,
                LocalDateTime.now(), LocalDateTime.now());

        ProductionSettlement settlement = new ProductionSettlement();
        settlement.setId(SETTLEMENT_ID);
        settlement.setFactoryId(FACTORY_ID);
        settlement.setProductionPlanId(PLAN_ID);
        settlement.setPlanNumber("PG-COST-001");
        settlement.setIdempotencyKey("pg-settlement");
        settlement.setPlannedQuantity(new BigDecimal("0.02"));
        settlement.setActualFinishedQuantity(new BigDecimal("0.02"));
        settlement.setActualSemiFinishedQuantity(BigDecimal.ZERO);
        settlement.setQuantityUnit("kg");
        settlement.setPlanStatusAfter(ProductionPlanStatus.COMPLETED);
        settlement.setPostingStatus("PENDING_WAREHOUSE_RECEIPT");
        settlement.setSettledAt(LocalDateTime.now());
        settlementRepository.saveAndFlush(settlement);

        ProductionSettlementOutputLine output = ProductionSettlementOutputLine.create();
        output.setFactoryId(FACTORY_ID);
        output.setSettlementId(SETTLEMENT_ID);
        output.setProductionPlanId(PLAN_ID);
        output.setProductTypeId("FG-SKU-A");
        output.setReportedBatchNumber(BATCH_NUMBER);
        output.setReportedQuantity(new BigDecimal("0.02"));
        output.setQuantityUnit("kg");
        output.setBomFamilyId("family-v3");
        output.setBomRecipeId("bom-a");
        output.setBomRecipeVersion(2);
        output.setTargetTerminalNodeId("terminal-FG-SKU-A");
        output.setOutputRole(BomRecipe.OutputRole.MAIN);
        output.setCostAllocationRatio(BigDecimal.ONE);
        output.setStatus("REPORTED");
        outputLineRepository.saveAndFlush(output);

        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY_ID);
        plan.setProductTypeId("RAW-OWNER");
        plan.setWorkflowSelectionMode(ProductionBatch.WorkflowSelectionMode.WORKFLOW);
        plan.setSelectedWorkflowRevisionId(4403L);
        plan.setSelectedWorkflowRevisionHash("revision-hash-v3");
        plan.setSelectedBomFamilyId("family-v3");
        plan.setSelectedBomRecipeIdsByProduct(Map.of("FG-SKU-A", "bom-a"));
        plan.setSelectedBomVersionsByProduct(Map.of("FG-SKU-A", 2));
        plan.setWorkflowOutputUnitsByProduct(Map.of("FG-SKU-A", "kg"));
        plan.setTargetFinishedGoodIds(List.of("FG-SKU-A"));

        planRepository = mock(ProductionPlanRepository.class);
        when(planRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID)).thenReturn(Optional.of(plan));
        costService = mock(OrderCostBreakdownService.class);
        when(costService.computeByPlan(FACTORY_ID, PLAN_ID, false))
                .thenReturn(OrderCostBreakdownDTO.builder().hasData(true)
                        .totalCost(new BigDecimal("0.56")).build());
        warehouseResolver = mock(WarehouseResolver.class);
        when(warehouseResolver.resolveFinishedGoodsId(FACTORY_ID)).thenReturn("WH-FG-ID");
        productTypeRepository = mock(ProductTypeRepository.class);
        ProductType productType = new ProductType();
        productType.setId("FG-SKU-A");
        productType.setFactoryId(FACTORY_ID);
        productType.setName("PG finished good");
        productType.setUnit("kg");
        when(productTypeRepository.findByIdAndFactoryId("FG-SKU-A", FACTORY_ID))
                .thenReturn(Optional.of(productType));

        service = new ProductionPlanServiceImpl(
                planRepository, mock(ProductionBatchRepository.class), mock(MaterialBatchRepository.class),
                mock(MaterialConsumptionRepository.class), mock(ProductionPlanBatchUsageRepository.class),
                productTypeRepository, mock(ProductionPlanMapper.class), mock(ConversionRepository.class),
                mock(SchedulingService.class), mock(ProductionLineRepository.class), mock(UserRepository.class),
                mock(ExcelUtil.class), mock(SalesOrderRepository.class), mock(SalesOrderItemRepository.class));
        ReflectionTestUtils.setField(service, "productionSettlementRepository", settlementRepository);
        ReflectionTestUtils.setField(service, "productionSettlementOutputLineRepository", outputLineRepository);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        ReflectionTestUtils.setField(service, "finishedGoodsBatchRepository", finishedGoodsRepository);
        ReflectionTestUtils.setField(service, "productionTransitLedgerRepository", mock(
                com.cretas.aims.repository.ProductionTransitLedgerRepository.class));
        productionCostConsistency = mock(ProductionCostConsistency.class);
        ReflectionTestUtils.setField(service, "productionCostConsistency", productionCostConsistency);
        ReflectionTestUtils.setField(service, "orderCostBreakdownService", costService);
        ReflectionTestUtils.setField(service, "warehouseResolver", warehouseResolver);
        ReflectionTestUtils.setField(service, "inventoryLowStockEventPublisher", mock(
                com.cretas.aims.service.alerts.InventoryLowStockEventPublisher.class));
        var returnedCosts = new com.cretas.aims.service.finance.ReturnedCostSynchronizationService(
                returnItems, returnOrders, finishedGoodsRepository, adjustmentLogs,
                mock(com.cretas.aims.service.finance.AccountingPeriodService.class),
                mock(com.cretas.aims.service.finance.DisposalCostAccountingService.class));
        ReflectionTestUtils.setField(returnedCosts, "entityManager", entityManager);
        ReflectionTestUtils.setField(service, "returnedCostSynchronizationService", returnedCosts);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void unavailableCostClearsUnencumberedInventoryUsingRealDownstreamQueries() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.execute(status -> service.confirmWarehouseReceipt(
                FACTORY_ID, PLAN_ID, receipt("clear-first", "0.01"), 11L));
        when(costService.computeByPlan(FACTORY_ID, PLAN_ID, false))
                .thenReturn(OrderCostBreakdownDTO.builder().hasData(false).build());
        transaction.execute(status -> service.confirmWarehouseReceipt(
                FACTORY_ID, PLAN_ID, receipt("clear-second", "0.01"), 11L));
        Map<String, Object> fg = jdbcTemplate.queryForMap(
                "SELECT produced_quantity,unit_cost FROM finished_goods_batches WHERE factory_id=? AND batch_number=?",
                FACTORY_ID, BATCH_NUMBER);
        assertThat((BigDecimal) fg.get("produced_quantity")).isEqualByComparingTo("0.02");
        assertThat(fg.get("unit_cost")).isNull();
        assertThat(jdbcTemplate.queryForMap(
                "SELECT allocated_cost,unit_cost FROM production_settlement_output_lines WHERE settlement_id=?",
                SETTLEMENT_ID)).containsEntry("allocated_cost", null).containsEntry("unit_cost", null);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void approvedLegacyDisposalWithoutAdjustmentLogBlocksClearAndRollsBackReceipt() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.execute(status -> service.confirmWarehouseReceipt(
                FACTORY_ID, PLAN_ID, receipt("disposal-first", "0.01"), 11L));
        String fgId = jdbcTemplate.queryForObject(
                "SELECT id FROM finished_goods_batches WHERE factory_id=? AND batch_number=?",
                String.class, FACTORY_ID, BATCH_NUMBER);
        // Legacy approved disposal fixture deliberately has no stock-adjustment log.
        transaction.executeWithoutResult(status -> {
            var disposal = new com.cretas.aims.entity.DisposalRecord();
            disposal.setFactoryId(FACTORY_ID);
            disposal.setFinishedGoodsBatchId(fgId);
            disposal.setDisposalQuantity(new BigDecimal("0.01"));
            disposal.setDisposalType("SCRAP");
            disposal.setDisposalDate(LocalDateTime.now());
            disposal.setIsApproved(true);
            disposal.setApprovalStatus("APPROVED");
            entityManager.persist(disposal);
            entityManager.flush();
        });
        when(costService.computeByPlan(FACTORY_ID, PLAN_ID, false))
                .thenReturn(OrderCostBreakdownDTO.builder().hasData(false).build());
        var failure = assertThrows(com.cretas.aims.exception.BusinessException.class,
                () -> transaction.execute(status -> service.confirmWarehouseReceipt(
                        FACTORY_ID, PLAN_ID, receipt("disposal-second", "0.01"), 11L)));
        assertThat(failure.getMessage()).contains("批准处置");
        assertThat(failure.getErrorCode()).isEqualTo("RECEIPT_COST_CLEAR_DOWNSTREAM_FACTS");
        Map<String, Object> fg = jdbcTemplate.queryForMap(
                "SELECT produced_quantity,unit_cost FROM finished_goods_batches WHERE id=?", fgId);
        assertThat((BigDecimal) fg.get("produced_quantity")).isEqualByComparingTo("0.01");
        assertThat((BigDecimal) fg.get("unit_cost")).isEqualByComparingTo("28");
        Map<String, Object> line = jdbcTemplate.queryForMap(
                "SELECT received_quantity,allocated_cost,unit_cost FROM production_settlement_output_lines WHERE settlement_id=?",
                SETTLEMENT_ID);
        assertThat((BigDecimal) line.get("received_quantity")).isEqualByComparingTo("0.01");
        assertThat((BigDecimal) line.get("allocated_cost")).isEqualByComparingTo("0.56");
        assertThat((BigDecimal) line.get("unit_cost")).isEqualByComparingTo("28");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT warehouse_received_quantity FROM production_settlements WHERE id=?",
                BigDecimal.class, SETTLEMENT_ID)).isEqualByComparingTo("0.01");
        assertThat(jdbcTemplate.queryForMap(
                "SELECT warehouse_receipt_idempotency_key,posting_status,warehouse_received_at "
                        + "FROM production_settlements WHERE id=?", SETTLEMENT_ID))
                .containsEntry("warehouse_receipt_idempotency_key", "disposal-first")
                .containsEntry("posting_status", "PENDING_WAREHOUSE_RECEIPT")
                .containsEntry("warehouse_received_at", null);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void actualReceiptServicePersistsStableCostForBothDeltasAndFreshReads() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        ProductionWarehouseReceiptResponse first = transaction.execute(status -> service.confirmWarehouseReceipt(
                FACTORY_ID, PLAN_ID, receipt("pg-cost-1", "0.01"), 11L));
        assertThat(first).isNotNull();
        assertThat(first.getOutputLines()).singleElement().satisfies(line -> {
            assertThat(line.getUnitCost()).isEqualByComparingTo("28");
            assertThat(line.getAllocatedCost()).isEqualByComparingTo("0.56");
        });

        entityManager.clear();
        FinishedGoodsBatch firstFresh = finishedGoodsRepository
                .findByFactoryIdAndBatchNumber(FACTORY_ID, BATCH_NUMBER).orElseThrow();
        assertThat(firstFresh.getProducedQuantity()).isEqualByComparingTo("0.01");
        assertThat(firstFresh.getUnitCost()).isEqualByComparingTo("28");
        Map<String, Object> firstOutputSql = jdbcTemplate.queryForMap(
                "SELECT received_quantity, allocated_cost, unit_cost "
                        + "FROM production_settlement_output_lines WHERE settlement_id=?",
                SETTLEMENT_ID);
        assertThat((BigDecimal) firstOutputSql.get("received_quantity")).isEqualByComparingTo("0.01");
        assertThat((BigDecimal) firstOutputSql.get("allocated_cost")).isEqualByComparingTo("0.56");
        assertThat((BigDecimal) firstOutputSql.get("unit_cost")).isEqualByComparingTo("28");
        Map<String, Object> firstSettlementSql = jdbcTemplate.queryForMap(
                "SELECT warehouse_received_quantity, warehouse_received_at, posting_status "
                        + "FROM production_settlements WHERE id=?", SETTLEMENT_ID);
        assertThat((BigDecimal) firstSettlementSql.get("warehouse_received_quantity"))
                .isEqualByComparingTo("0.01");
        assertThat(firstSettlementSql.get("warehouse_received_at")).isNull();
        assertThat(firstSettlementSql.get("posting_status")).isEqualTo("PENDING_WAREHOUSE_RECEIPT");
        Map<String, Object> firstFgSql = jdbcTemplate.queryForMap(
                "SELECT produced_quantity, unit_cost, version FROM finished_goods_batches "
                        + "WHERE factory_id=? AND batch_number=?", FACTORY_ID, BATCH_NUMBER);
        assertThat((BigDecimal) firstFgSql.get("produced_quantity")).isEqualByComparingTo("0.01");
        assertThat((BigDecimal) firstFgSql.get("unit_cost")).isEqualByComparingTo("28");
        assertThat(firstFgSql.get("version")).isNotNull();

        entityManager.clear();
        ProductionWarehouseReceiptResponse second = transaction.execute(status -> service.confirmWarehouseReceipt(
                FACTORY_ID, PLAN_ID, receipt("pg-cost-2", "0.01"), 11L));
        assertThat(second).isNotNull();
        assertThat(second.getOutputLines()).singleElement()
                .extracting(line -> line.getUnitCost()).isEqualTo(new BigDecimal("28.000000"));

        entityManager.clear();
        FinishedGoodsBatch fresh = finishedGoodsRepository
                .findByFactoryIdAndBatchNumber(FACTORY_ID, BATCH_NUMBER).orElseThrow();
        assertThat(fresh.getProducedQuantity()).isEqualByComparingTo("0.02");
        assertThat(fresh.getUnitCost()).isEqualByComparingTo("28");
        assertThat(second.getWarnings()).noneMatch(message -> message.contains("单位成本留空"));
        Map<String, Object> finalOutputSql = jdbcTemplate.queryForMap(
                "SELECT received_quantity, allocated_cost, unit_cost "
                        + "FROM production_settlement_output_lines WHERE settlement_id=?",
                SETTLEMENT_ID);
        assertThat((BigDecimal) finalOutputSql.get("received_quantity")).isEqualByComparingTo("0.02");
        assertThat((BigDecimal) finalOutputSql.get("allocated_cost")).isEqualByComparingTo("0.56");
        assertThat((BigDecimal) finalOutputSql.get("unit_cost")).isEqualByComparingTo("28");
        Map<String, Object> finalSettlementSql = jdbcTemplate.queryForMap(
                "SELECT warehouse_received_quantity, warehouse_received_at, posting_status "
                        + "FROM production_settlements WHERE id=?", SETTLEMENT_ID);
        assertThat((BigDecimal) finalSettlementSql.get("warehouse_received_quantity"))
                .isEqualByComparingTo("0.02");
        assertThat(finalSettlementSql.get("warehouse_received_at")).isNotNull();
        assertThat(finalSettlementSql.get("posting_status")).isEqualTo("POSTED");
        Map<String, Object> finalFgSql = jdbcTemplate.queryForMap(
                "SELECT produced_quantity, unit_cost, version FROM finished_goods_batches "
                        + "WHERE factory_id=? AND batch_number=?", FACTORY_ID, BATCH_NUMBER);
        assertThat((BigDecimal) finalFgSql.get("produced_quantity")).isEqualByComparingTo("0.02");
        assertThat((BigDecimal) finalFgSql.get("unit_cost")).isEqualByComparingTo("28");
        assertThat(finalFgSql.get("version")).isNotNull();
        verify(productionCostConsistency, times(2)).assertChangeAllowed(FACTORY_ID, PLAN_ID);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void receiptOutputFlushFailureRollsBackAllReceiptWrites() {
        String planId = "PG-FLUSH-PLAN";
        String settlementId = "PG-FLUSH-SETTLEMENT";
        String batchNumber = "PG-FLUSH-BATCH";
        ProductionSettlement settlement = new ProductionSettlement();
        settlement.setId(settlementId);
        settlement.setFactoryId(FACTORY_ID);
        settlement.setProductionPlanId(planId);
        settlement.setPlanNumber("PG-FLUSH-001");
        settlement.setIdempotencyKey("pg-flush-settlement");
        settlement.setPlannedQuantity(new BigDecimal("0.02"));
        settlement.setActualFinishedQuantity(new BigDecimal("0.02"));
        settlement.setActualSemiFinishedQuantity(BigDecimal.ZERO);
        settlement.setQuantityUnit("kg");
        settlement.setPlanStatusAfter(ProductionPlanStatus.COMPLETED);
        settlement.setPostingStatus("PENDING_WAREHOUSE_RECEIPT");
        settlement.setSettledAt(LocalDateTime.now());
        settlementRepository.saveAndFlush(settlement);

        ProductionSettlementOutputLine output = ProductionSettlementOutputLine.create();
        output.setFactoryId(FACTORY_ID);
        output.setSettlementId(settlementId);
        output.setProductionPlanId(planId);
        output.setProductTypeId("FG-SKU-A");
        output.setReportedBatchNumber(batchNumber);
        output.setReportedQuantity(new BigDecimal("0.02"));
        output.setQuantityUnit("kg");
        output.setBomFamilyId("family-v3");
        output.setBomRecipeId("bom-a");
        output.setBomRecipeVersion(2);
        output.setTargetTerminalNodeId("terminal-FG-SKU-A");
        output.setOutputRole(BomRecipe.OutputRole.MAIN);
        output.setCostAllocationRatio(BigDecimal.ONE);
        output.setStatus("REPORTED");
        outputLineRepository.saveAndFlush(output);

        ProductionPlan plan = new ProductionPlan();
        plan.setId(planId);
        plan.setFactoryId(FACTORY_ID);
        plan.setProductTypeId("RAW-OWNER");
        plan.setWorkflowSelectionMode(ProductionBatch.WorkflowSelectionMode.WORKFLOW);
        plan.setSelectedWorkflowRevisionId(4403L);
        plan.setSelectedWorkflowRevisionHash("revision-hash-v3");
        plan.setSelectedBomFamilyId("family-v3");
        plan.setSelectedBomRecipeIdsByProduct(Map.of("FG-SKU-A", "bom-a"));
        plan.setSelectedBomVersionsByProduct(Map.of("FG-SKU-A", 2));
        plan.setWorkflowOutputUnitsByProduct(Map.of("FG-SKU-A", "kg"));
        plan.setTargetFinishedGoodIds(List.of("FG-SKU-A"));
        when(planRepository.findByIdAndFactoryId(planId, FACTORY_ID)).thenReturn(Optional.of(plan));

        // Delegate every real repository operation except the injected flush
        // failure. Do not Mockito-spy a Spring Data AOP proxy: Boot's resolver
        // may unwrap it while a stub is being configured.
        ProductionSettlementOutputLineRepository flushFailingRepository =
                (ProductionSettlementOutputLineRepository) java.lang.reflect.Proxy.newProxyInstance(
                        ProductionSettlementOutputLineRepository.class.getClassLoader(),
                        new Class<?>[]{ProductionSettlementOutputLineRepository.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("flush") && method.getParameterCount() == 0) {
                                throw new IllegalStateException("forced output-line flush failure");
                            }
                            try {
                                return method.invoke(outputLineRepository, arguments);
                            } catch (java.lang.reflect.InvocationTargetException invocation) {
                                throw invocation.getCause();
                            }
                        });
        ReflectionTestUtils.setField(service, "productionSettlementOutputLineRepository",
                flushFailingRepository);

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> transaction.execute(status ->
                service.confirmWarehouseReceipt(
                        FACTORY_ID, planId, receipt("pg-flush-1", "0.01", batchNumber), 11L)));

        entityManager.clear();
        Map<String, Object> settlementSql = jdbcTemplate.queryForMap(
                "SELECT warehouse_received_quantity, warehouse_received_at, posting_status "
                        + "FROM production_settlements WHERE id=?", settlementId);
        assertThat(settlementSql.get("warehouse_received_quantity")).isNull();
        assertThat(settlementSql.get("warehouse_received_at")).isNull();
        assertThat(settlementSql.get("posting_status")).isEqualTo("PENDING_WAREHOUSE_RECEIPT");
        Map<String, Object> outputSql = jdbcTemplate.queryForMap(
                "SELECT received_quantity, allocated_cost, unit_cost "
                        + "FROM production_settlement_output_lines WHERE settlement_id=?", settlementId);
        assertThat(outputSql.get("received_quantity")).isNull();
        assertThat(outputSql.get("allocated_cost")).isNull();
        assertThat(outputSql.get("unit_cost")).isNull();
        Integer finishedGoodsRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM finished_goods_batches WHERE factory_id=? AND batch_number=?",
                Integer.class, FACTORY_ID, batchNumber);
        assertThat(finishedGoodsRows).isZero();
        verify(productionCostConsistency).assertChangeAllowed(FACTORY_ID, planId);
    }

    private ProductionWarehouseReceiptRequest receipt(String key, String quantity) {
        return receipt(key, quantity, BATCH_NUMBER);
    }

    private ProductionWarehouseReceiptRequest receipt(String key, String quantity, String batchNumber) {
        ProductionWarehouseReceiptRequest request = new ProductionWarehouseReceiptRequest();
        request.setIdempotencyKey(key);
        request.setOutputLines(List.of(ProductionWarehouseReceiptRequest.ReceiptLine.builder()
                .productTypeId("FG-SKU-A").batchNumber(batchNumber)
                .receivedQuantity(new BigDecimal(quantity)).quantityUnit("kg").build()));
        return request;
    }
}
