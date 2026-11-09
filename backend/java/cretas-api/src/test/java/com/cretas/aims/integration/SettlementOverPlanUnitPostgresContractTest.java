package com.cretas.aims.integration;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.production.ProductionSettlementPrefillResponse;
import com.cretas.aims.dto.production.ProductionSettlementRequest;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.exception.BusinessException;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * D-N-01 (2026-11-09 S3-15 实测) 真 PostgreSQL + 真入口 {@code settleProduction} (一键确认, 非人工补录):
 * 计划 0.3 kg、末道产出 3 袋 —— 超产判断要先把 3 袋按产品每袋克重折到 kg 再比。
 *
 * <p>线上返工计划 SEC-F006-20261109-4661D1 的旧行为: 3 和 0.3 裸比 ⇒ 「超产超过 5%」,
 * 409 带的是客供的 {@code CUSTOMER_SUPPLIED_MATERIAL_NOT_ON_ORDER}, 填了超产原因也过不去。
 * 这里每个用例都核: 拒绝时一行结单都没写、计划没被关; 放行时结单真的落库。
 *
 * <p>开关同 {@link SecondaryHoldPostgresContractTest}, 跑在 postgres-reversal-concurrency 车道。
 */
@SpringBootTest(classes = SettlementOverPlanUnitPostgresContractTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoint.health.validate-group-membership=false",
        "spring.datasource.hikari.data-source-properties.stringtype=unspecified",
        "spring.datasource.hikari.maximum-pool-size=8"
})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class SettlementOverPlanUnitPostgresContractTest {

    private static final String F = "DN01-PG";
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
    }

    /** 计划 planned (planUnit), 末道一行产出 output (outputUnit) 的产品 P; P 每单位 gramsPerUnit 克 (null = 没配)。 */
    private String fixture(String planned, String planUnit, String output, String outputUnit, String gramsPerUnit) {
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
            plan.setId("PLAN-DN01-" + suffix);
            plan.setFactoryId(F);
            plan.setPlanNumber("DN01-" + suffix);
            plan.setProductTypeId(product.getId());
            plan.setPlannedQuantity(new BigDecimal(planned));
            plan.setPlannedUnit(planUnit);
            plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
            plan.setCreatedBy(OPERATOR);
            plans.save(plan);

            ProductionBatch batch = new ProductionBatch();
            batch.setFactoryId(F);
            batch.setBatchNumber("PB-DN01-" + suffix);
            batch.setProductTypeId(product.getId());
            batch.setQuantity(new BigDecimal(output));
            batch.setUnit(outputUnit);
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
            return plan.getId();
        });
    }

    /** 一键确认 (非人工补录): 产量以报工为准, 只带结单人要回答的那几项。 */
    private static ProductionSettlementRequest confirm(String key, String overPlanReason) {
        return ProductionSettlementRequest.builder()
                .confirm(true)
                .idempotencyKey(key)
                .actualSemiFinishedQuantity(BigDecimal.ZERO)
                .materialVarianceReason("无原料领用")
                .quantityVarianceReason(overPlanReason)
                .build();
    }

    private int settlementsOf(String planId) {
        return jdbc.queryForObject("select count(*) from production_settlements where production_plan_id = ? "
                + "and deleted_at is null", Integer.class, planId);
    }

    private String statusOf(String planId) {
        return jdbc.queryForObject("select status from production_plans where id = ?", String.class, planId);
    }

    private static boolean hasIssue(ProductionSettlementPrefillResponse prefill, String code) {
        return prefill.getAudit().getIssues().stream().anyMatch(i -> code.equals(i.getCode()));
    }

    @Test
    void threeBagsOfOneHundredGramsAreThePlannedZeroPointThreeKilogramsAndSettleWithoutAReason() {
        String planId = fixture("0.3", "kg", "3", "袋", "100");

        ProductionSettlementPrefillResponse prefill = planService.getSettlementPrefill(F, planId);
        assertThat(hasIssue(prefill, "QUANTITY_VARIANCE_OVER_PLAN")).as("3 袋 × 100 g = 0.3 kg, 不是超产").isFalse();
        assertThat(prefill.getPrefill().getQuantityVarianceReason()).isEqualTo("无显著差异");

        planService.settleProduction(F, planId, confirm("k-" + planId, null), OPERATOR);

        assertThat(settlementsOf(planId)).isEqualTo(1);
        assertThat(statusOf(planId)).isEqualTo("COMPLETED");
    }

    @Test
    void realOverProductionAfterConversionIsRefusedWithItsOwnCodeAndAcceptedOnceAReasonIsGiven() {
        String planId = fixture("0.3", "kg", "3", "袋", "150");   // 3 袋 × 150 g = 0.45 kg > 0.3 kg

        assertThatThrownBy(() -> planService.settleProduction(F, planId, confirm("k1-" + planId, null), OPERATOR))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("PRODUCTION_OVER_PLAN_REASON_REQUIRED");
                    assertThat(e.getMessage()).contains("折 0.45").contains("0.3");
                });
        assertThat(settlementsOf(planId)).as("拒绝时一行结单都不写").isZero();
        assertThat(statusOf(planId)).isEqualTo("IN_PROGRESS");

        planService.settleProduction(F, planId, confirm("k2-" + planId, "UNIT_CONVERSION"), OPERATOR);

        assertThat(settlementsOf(planId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select quantity_variance_reason from production_settlements "
                + "where production_plan_id = ? and deleted_at is null", String.class, planId))
                .isEqualTo("UNIT_CONVERSION");
        assertThat(statusOf(planId)).isEqualTo("COMPLETED");
    }

    @Test
    void withoutAnyConversionTheDifferenceIsUnverifiedNotOverProduction() {
        String planId = fixture("0.3", "kg", "3", "袋", null);

        ProductionSettlementPrefillResponse prefill = planService.getSettlementPrefill(F, planId);
        assertThat(hasIssue(prefill, "QUANTITY_VARIANCE_OVER_PLAN")).isFalse();
        assertThat(prefill.getAudit().getIssues()).anySatisfy(issue -> {
            assertThat(issue.getCode()).isEqualTo("QUANTITY_UNIT_CROSS");
            assertThat(issue.getSeverity()).isEqualTo(ProductionSettlementPrefillResponse.Severity.INFO);
            assertThat(issue.getMessage()).contains("未核实");
        });

        planService.settleProduction(F, planId, confirm("k-" + planId, null), OPERATOR);
        assertThat(settlementsOf(planId)).isEqualTo(1);
    }

    @Test
    void sameUnitOverProductionStillNeedsAReasonAndOtherIsAccepted() {
        String planId = fixture("0.3", "kg", "0.5", "kg", null);

        assertThatThrownBy(() -> planService.settleProduction(F, planId, confirm("k1-" + planId, null), OPERATOR))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("PRODUCTION_OVER_PLAN_REASON_REQUIRED"));
        assertThat(settlementsOf(planId)).isZero();

        planService.settleProduction(F, planId, confirm("k2-" + planId, "OTHER"), OPERATOR);
        assertThat(settlementsOf(planId)).isEqualTo(1);
    }
}
