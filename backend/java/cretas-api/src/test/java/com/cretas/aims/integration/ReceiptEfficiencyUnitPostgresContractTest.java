package com.cretas.aims.integration;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.production.ProductionSettlementRequest;
import com.cretas.aims.dto.production.ProductionWarehouseReceiptRequest;
import com.cretas.aims.dto.production.ProductionWarehouseReceiptResponse;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.ProductionPlanService;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.service.inventory.impl.FinishedGoodsFeedServiceImpl;
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
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * D-W-01 (2026-11-10 prod 实测) 真 PostgreSQL + 真入口: {@code settleProduction} 一键确认之后,
 * 仓管走 {@code confirmWarehouseReceipt} 实收 (= {@code POST /warehouse/transit-ledgers/{planId}/confirm})。
 *
 * <p>线上返工计划 SEC-F006-20261109-4661D1: 计划 0.3 kg (半成品口径), 实际 3 袋, 产品没配每袋克重。
 * 旧代码批次效率 = 3 ÷ 0.3 × 100 = 1000.00, production_batches.efficiency 是 numeric(5,2) ⇒
 * PostgreSQL「numeric field overflow」, 整笔实收 409「数据处理异常」, 两次都过不去。
 * numeric(5,2) 的溢出只有真 PG 才报, 所以本测试必须跑在真库上 (postgres-reversal-concurrency 车道)。
 *
 * <p>每个用例都核: 实收成功、成品批真的落库、批次效率是按同单位算出的数或如实为 null。
 */
@SpringBootTest(classes = ReceiptEfficiencyUnitPostgresContractTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoint.health.validate-group-membership=false",
        "spring.datasource.hikari.data-source-properties.stringtype=unspecified",
        "spring.datasource.hikari.maximum-pool-size=8"
})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class ReceiptEfficiencyUnitPostgresContractTest {

    private static final String F = "DW01-PG";
    private static final Long OPERATOR = 7L;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @EntityScan("com.cretas.aims.entity")
    @EnableJpaRepositories("com.cretas.aims.repository")
    @Import({ProductionPlanServiceImpl.class, WipInventoryServiceImpl.class, SecondaryPlanFeedService.class,
            SettlementSemiStockingService.class, FinishedGoodsFeedServiceImpl.class,
            CustomerSuppliedSettlementCoverageGuard.class, UnitContractServiceImpl.class})
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
    @MockBean IntermediateOwnershipGuard ownershipGuard;
    @MockBean WarehouseResolver warehouseResolver;
    @MockBean com.cretas.aims.service.workprocess.WorkProcessTaskService workProcessTaskService;
    @MockBean com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope processSecretLlmCostScope;
    @MockBean com.cretas.aims.service.finance.ProductionCostConsistency productionCostConsistency;
    @MockBean com.cretas.aims.service.finance.ReturnedCostSynchronizationService returnedCostSynchronizationService;
    @MockBean PermissionService permissionService;
    @MockBean UserRepository userRepository;

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ProductionPlanService planService;
    @Autowired ProductionPlanRepository plans;
    @Autowired ProductionBatchRepository batches;
    @Autowired ProcessSheetRowRepository rows;
    @Autowired ProductTypeRepository productTypes;

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
        when(warehouseResolver.resolveFinishedGoodsId(anyString())).thenReturn("WH-FG-DW01");
    }

    /** 列的真实精度先读出来 —— 本测试守的就是「写不进 numeric(5,2)」, 列定义变了就没在测原来那件事。 */
    private void assertEfficiencyColumnIsNumericFiveTwo() {
        Map<String, Object> column = jdbc.queryForMap("select numeric_precision, numeric_scale "
                + "from information_schema.columns where table_name = 'production_batches' "
                + "and column_name = 'efficiency'");
        assertThat(((Number) column.get("numeric_precision")).intValue()).isEqualTo(5);
        assertThat(((Number) column.get("numeric_scale")).intValue()).isEqualTo(2);
    }

    /**
     * 计划 planned (planUnit) 的产品 P; 正式批次的计划量从计划拷来 = planned, 并按建批路径
     * (转批次 / 文员逐道录入) 的做法把计划单位记在批次 plannedUnit 上; 末道一行产出 output (outputUnit)。
     * P 每单位 gramsPerUnit 克 (null = 没配)。
     */
    private Fixture fixture(String planned, String planUnit, String output, String outputUnit,
                            String batchUnit, String gramsPerUnit) {
        return fixture(planned, planUnit, planned, planUnit, output, outputUnit, batchUnit, gramsPerUnit);
    }

    /** 批次计划量 / 计划单位与计划不同源时 (手工建批: 计划量按批次自己的单位填, plannedUnit 为 null)。 */
    private Fixture fixture(String planned, String planUnit, String batchPlanned, String batchPlannedUnit,
                            String output, String outputUnit, String batchUnit, String gramsPerUnit) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return tx.execute(state -> {
            ProductType product = new ProductType();
            product.setFactoryId(F);
            product.setCode("BAG-" + suffix);
            product.setName("返工小包 " + suffix);
            product.setUnit(outputUnit);
            product.setGramsPerUnit(gramsPerUnit == null ? null : new BigDecimal(gramsPerUnit));
            product = productTypes.save(product);

            ProductionPlan plan = new ProductionPlan();
            plan.setId("PLAN-DW01-" + suffix);
            plan.setFactoryId(F);
            plan.setPlanNumber("SEC-DW01-" + suffix);
            plan.setProductTypeId(product.getId());
            plan.setPlannedQuantity(new BigDecimal(planned));
            plan.setPlannedUnit(planUnit);
            plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
            plan.setCreatedBy(OPERATOR);
            plans.save(plan);

            ProductionBatch batch = new ProductionBatch();
            batch.setFactoryId(F);
            batch.setBatchNumber("PB-DW01-" + suffix);
            batch.setProductTypeId(product.getId());
            batch.setPlannedQuantity(new BigDecimal(batchPlanned));
            batch.setPlannedUnit(batchPlannedUnit);
            batch.setQuantity(new BigDecimal(output));
            batch.setUnit(batchUnit);
            batch.setStatus(ProductionBatchStatus.IN_PROGRESS);
            batch.setProductionPlanId(plan.getId());
            batch.setBatchType("REGULAR");
            batch = batches.save(batch);

            ProcessSheetRowRequest req = new ProcessSheetRowRequest();
            req.setClientRowId("final-" + suffix);
            req.setProcessCode("pack");
            req.setProcessOrder(1);
            req.setProductTypeId(product.getId());
            req.setFinished(true);
            req.setOutputQuantity(new BigDecimal(output));
            req.setUnit(outputUnit);
            req.setUpstreamSources(new ArrayList<>());
            ProcessSheetRow row = new ProcessSheetRow();
            row.setFactoryId(F);
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
            return new Fixture(plan.getId(), batch.getId());
        });
    }

    private record Fixture(String planId, Long batchId) { }

    /** 一键确认结单, 再按报产量全数实收 —— 两步都走真入口。 */
    private ProductionWarehouseReceiptResponse settleThenReceive(Fixture fixture, String received, String unit,
                                                                 String quantityVarianceReason) {
        planService.settleProduction(F, fixture.planId(), ProductionSettlementRequest.builder()
                .confirm(true)
                .idempotencyKey("settle-" + fixture.planId())
                .actualSemiFinishedQuantity(BigDecimal.ZERO)
                .materialVarianceReason("无原料领用")
                .quantityVarianceReason(quantityVarianceReason)
                .build(), OPERATOR);
        ProductionWarehouseReceiptRequest receipt = new ProductionWarehouseReceiptRequest();
        receipt.setIdempotencyKey("receipt-" + fixture.planId());
        receipt.setReceivedQuantity(new BigDecimal(received));
        receipt.setQuantityUnit(unit);
        return planService.confirmWarehouseReceipt(F, fixture.planId(), receipt, OPERATOR);
    }

    private Map<String, Object> batchRow(Long batchId) {
        return jdbc.queryForMap("select status, efficiency, actual_quantity from production_batches where id = ?",
                batchId);
    }

    private int finishedGoodsOf(String planId) {
        return jdbc.queryForObject("select count(*) from finished_goods_batches where production_plan_id = ? "
                + "and deleted_at is null", Integer.class, planId);
    }

    private BigDecimal receivedAt(String planId) {
        return jdbc.queryForObject("select warehouse_received_quantity from production_settlements "
                + "where production_plan_id = ? and warehouse_received_at is not null and deleted_at is null",
                BigDecimal.class, planId);
    }

    @Test
    void zeroPointThreeKilogramPlanReceivedAsThreeBagsWithoutBagWeightSucceedsAndLeavesEfficiencyUnknown() {
        assertEfficiencyColumnIsNumericFiveTwo();
        Fixture fixture = fixture("0.3", "kg", "3", "袋", "袋", null);

        ProductionWarehouseReceiptResponse response = settleThenReceive(fixture, "3", "袋", null);

        assertThat(response).isNotNull();
        assertThat(receivedAt(fixture.planId())).isEqualByComparingTo("3");
        assertThat(finishedGoodsOf(fixture.planId())).as("成品批生成").isEqualTo(1);
        Map<String, Object> batch = batchRow(fixture.batchId());
        assertThat(batch.get("status")).isEqualTo("COMPLETED");
        assertThat(batch.get("efficiency")).as("袋与 kg 之间没有换算: 效率不知道, 不是 1000、不是 999.99、不是 0")
                .isNull();
    }

    @Test
    void bagsAgainstKilogramsWithoutBagWeightStayUnknownEvenWhenTheBareRatioWouldFitTheColumn() {
        // 3 袋 / 2 kg 裸除 = 150.00, 存得进 numeric(5,2) —— 只靠溢出兜底拦不住这个假数
        Fixture fixture = fixture("2", "kg", "3", "袋", "袋", null);

        settleThenReceive(fixture, "3", "袋", null);

        assertThat(finishedGoodsOf(fixture.planId())).isEqualTo(1);
        assertThat(batchRow(fixture.batchId()).get("efficiency")).isNull();
    }

    @Test
    void aLaterSaveInAnotherTransactionStillLeavesTheCrossUnitEfficiencyUnknownButKeepsTheUnitCost() {
        Fixture fixture = fixture("2", "kg", "3", "袋", "袋", null);
        settleThenReceive(fixture, "3", "袋", null);
        assertThat(batchRow(fixture.batchId()).get("efficiency")).isNull();

        // 实收之后的另一笔写入 (同 YieldReportServiceImpl 回写人工成本: 读批次 → 改成本 → calculateMetrics → save),
        // 新事务里没有实收那次的瞬态单位核对, 只剩批次上持久化的 plannedUnit。
        tx.executeWithoutResult(state -> {
            ProductionBatch batch = batches.findById(fixture.batchId()).orElseThrow();
            assertThat(batch.getPlannedUnit()).isEqualTo("kg");
            batch.setLaborCost(new BigDecimal("12.00"));
            batch.calculateMetrics();
            batches.save(batch);
        });

        Map<String, Object> after = jdbc.queryForMap(
                "select efficiency, unit_cost, labor_cost from production_batches where id = ?", fixture.batchId());
        assertThat((BigDecimal) after.get("labor_cost")).as("这笔写入真的落库了").isEqualByComparingTo("12.00");
        assertThat(after.get("efficiency")).as("3 袋 / 2 kg 不许在后来的 save 里变回 150.00").isNull();
        // 单位成本 = 金额 ÷ 产出袋数, 与 plannedUnit 无关 —— 跨单位标记不能顺手把单袋成本抹掉
        assertThat((BigDecimal) after.get("unit_cost")).isEqualByComparingTo("4.0000");
    }

    @Test
    void manualBatchPlannedInItsOwnUnitUnderAKilogramPlanIsComparedInTheBatchUnit() {
        // 手工建批 (ProcessingController.createBatch): 计划量 100 箱、批次单位箱、不记 plannedUnit; 挂的计划按 kg
        Fixture fixture = fixture("500", "kg", "100", null, "95", "箱", "箱", null);

        settleThenReceive(fixture, "95", "箱", null);

        assertThat(finishedGoodsOf(fixture.planId())).isEqualTo(1);
        assertThat((BigDecimal) batchRow(fixture.batchId()).get("efficiency"))
                .as("95 箱 / 100 箱, 不拿计划的 kg 去折").isEqualByComparingTo("95.00");
    }

    @Test
    void bagsWithAConfiguredBagWeightAreConvertedToThePlanUnitBeforeTheRatio() {
        Fixture fixture = fixture("0.3", "kg", "3", "袋", "袋", "100");   // 3 袋 × 100 g = 0.3 kg

        settleThenReceive(fixture, "3", "袋", null);

        assertThat(finishedGoodsOf(fixture.planId())).isEqualTo(1);
        assertThat((BigDecimal) batchRow(fixture.batchId()).get("efficiency")).isEqualByComparingTo("100.00");
    }

    @Test
    void sameUnitHundredKilogramPlanReceivedAsNinetyFiveKilogramsIsNinetyFivePercent() {
        Fixture fixture = fixture("100", "kg", "95", "kg", "kg", null);

        settleThenReceive(fixture, "95", "kg", "OTHER");

        assertThat(finishedGoodsOf(fixture.planId())).isEqualTo(1);
        Map<String, Object> batch = batchRow(fixture.batchId());
        assertThat(batch.get("status")).isEqualTo("COMPLETED");
        assertThat((BigDecimal) batch.get("efficiency")).isEqualByComparingTo("95.00");
    }

    @Test
    void sameUnitRatioThatDoesNotFitTheColumnIsLeftUnknownAndTheReceiptStillSucceeds() {
        assertEfficiencyColumnIsNumericFiveTwo();
        // 批次单位与计划同为 kg, 但数是 10 倍 (例如袋数被记在 kg 批次上) ⇒ 1000.00 存不进 numeric(5,2)
        Fixture fixture = fixture("0.3", "kg", "3", "kg", "kg", null);

        settleThenReceive(fixture, "3", "kg", "OTHER");

        assertThat(finishedGoodsOf(fixture.planId())).isEqualTo(1);
        assertThat(batchRow(fixture.batchId()).get("efficiency")).as("不截断成 999.99, 不写 0").isNull();
    }
}
