package com.cretas.aims.integration;

import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.service.ProductionPlanService;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.service.processentry.IntermediateOwnershipGuard;
import com.cretas.aims.service.production.SecondaryPlanFeedService;
import com.cretas.aims.service.production.SettlementSemiStockingService;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.wip.ProductFamilyResolver;
import com.cretas.aims.service.wip.impl.WipInventoryServiceImpl;
import com.cretas.aims.utils.ExcelUtil;
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
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真 PostgreSQL + 真 Hibernate: 报工全部撤回后, 计划能不能取消 (2026-11-10 F006 计划 f86d2c6d)。
 *
 * <p>现场: 报工撤回把真实消耗软删了, 随后关领料单写了两条退料流水 (source_type=MATERIAL_RETURN, 数量为负,
 * production_plan_id 指向本计划)。取消仍 409 PLAN_HAS_PRODUCTION_DATA, 提示却说「撤回完成后即可取消」。
 *
 * <p>为什么要真库: 「撤回后还剩什么」取决于 {@code @Where(deleted_at IS NULL)} 软删过滤和按计划查流水的
 * 真实查询, Mockito 直接给列表就绕过了这两层。
 *
 * <p>开关同 {@link SecondaryHoldPostgresContractTest}, 跑在 java-gate-pr 的 PostgreSQL 车道。
 */
@SpringBootTest(classes = PlanCancelAfterMaterialReturnPostgresContractTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoint.health.validate-group-membership=false",
        "spring.datasource.hikari.data-source-properties.stringtype=unspecified",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class PlanCancelAfterMaterialReturnPostgresContractTest {

    private static final String F = "PCMR-PG";
    /** 与 FactoryMaterialRequisitionServiceImpl 写入的退料流水来源同值 (字面量: 流水落库的就是这个串)。 */
    private static final String MATERIAL_RETURN = "MATERIAL_RETURN";

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
    @Autowired ProductionPlanRepository plans;
    @Autowired ProductionBatchRepository batches;
    @Autowired MaterialConsumptionRepository consumptions;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        jdbc.execute("DO $$ DECLARE r record; BEGIN "
                + "FOR r IN SELECT conrelid::regclass AS t, conname FROM pg_constraint "
                + "WHERE contype = 'f' AND connamespace = 'public'::regnamespace LOOP "
                + "EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.t, r.conname); END LOOP; END $$");
    }

    /** 已开工计划 + 一个报工批次 + 一笔报工消耗 (带计划 id) + 关领料单写的两条退料流水。 */
    private record Fixture(String planId, Integer reportConsumptionId) {
    }

    private Fixture startedPlanWithReportConsumptionAndReturns() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return tx.execute(state -> {
            ProductionPlan plan = new ProductionPlan();
            plan.setId("PLAN-" + suffix);
            plan.setFactoryId(F);
            plan.setPlanNumber("PCMR-" + suffix);
            plan.setProductTypeId("FG-1");
            plan.setPlannedQuantity(BigDecimal.ONE);
            plan.setPlannedUnit("盒");
            plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
            plan.setCreatedBy(7L);
            plan.setPlanSourceType("NORMAL");
            plans.save(plan);

            ProductionBatch batch = new ProductionBatch();
            batch.setFactoryId(F);
            batch.setBatchNumber("PB-" + suffix);
            batch.setProductTypeId("FG-1");
            batch.setQuantity(BigDecimal.ONE);
            batch.setUnit("盒");
            batch.setStatus(ProductionBatchStatus.IN_PROGRESS);
            batch.setProductionPlanId(plan.getId());
            batch = batches.save(batch);

            MaterialConsumption used = consumption(plan.getId(), batch.getId(), "1.000", "RAW_MATERIAL", "报工消耗");
            consumptions.save(consumption(plan.getId(), null, "-1.200",
                    MATERIAL_RETURN,
                    "production material return: requisition=R1, item=I1"));
            consumptions.save(consumption(plan.getId(), null, "-1.000",
                    MATERIAL_RETURN,
                    "production material return: requisition=R1, item=I2"));
            return new Fixture(plan.getId(), consumptions.save(used).getId());
        });
    }

    private MaterialConsumption consumption(String planId, Long batchId, String qty, String sourceType, String notes) {
        BigDecimal quantity = new BigDecimal(qty);
        MaterialConsumption c = new MaterialConsumption();
        c.setFactoryId(F);
        c.setProductionPlanId(planId);
        c.setProductionBatchId(batchId);
        c.setBatchId("MB-1");
        c.setQuantity(quantity);
        c.setUnitPrice(new BigDecimal("10.00"));
        c.setTotalCost(quantity.multiply(new BigDecimal("10.00")));
        c.setConsumptionTime(LocalDateTime.now());
        c.setConsumedAt(LocalDateTime.now());
        c.setRecordedBy(7L);
        c.setMaterialTypeId("MT-1");
        c.setSourceType(sourceType);
        c.setNotes(notes);
        return c;
    }

    /** 报工撤回对消耗的真实动作: 软删 (deleted_at), 不是物理删除。 */
    private void reverseReportConsumption(Fixture f) {
        jdbc.update("update material_consumptions set deleted_at = now() where id = ?", f.reportConsumptionId());
    }

    private String status(Fixture f) {
        return jdbc.queryForObject("select status from production_plans where id = ?", String.class, f.planId());
    }

    private int liveReturnRows(Fixture f) {
        return jdbc.queryForObject("select count(*) from material_consumptions where production_plan_id = ? "
                + "and source_type = 'MATERIAL_RETURN' and deleted_at is null", Integer.class, f.planId());
    }

    @Test
    void afterAllReportsReversedAndRequisitionClosedThePlanCanBeCancelled() {
        Fixture f = startedPlanWithReportConsumptionAndReturns();
        reverseReportConsumption(f);
        assertThat(liveReturnRows(f)).as("前提: 先关领料单, 两条退料流水仍在").isEqualTo(2);

        planService.cancelProductionPlan(F, f.planId(), "验收计划撤回后取消", 7L);

        assertThat(status(f)).isEqualTo("CANCELLED");
        assertThat(liveReturnRows(f)).as("取消不碰退料流水").isEqualTo(2);
    }

    @Test
    void unreversedReportConsumptionStillBlocksAndSaysWhatRemains() {
        Fixture f = startedPlanWithReportConsumptionAndReturns();

        assertThatThrownBy(() -> planService.cancelProductionPlan(F, f.planId(), "想直接取消", 7L))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo("PLAN_HAS_PRODUCTION_DATA");
                    assertThat(ex.getMessage())
                            .as("说清还剩哪类: 只数那一笔真实消耗, 两条退料流水不计入")
                            .contains("1 笔未冲销的物料消耗");
                });
        assertThat(status(f)).isEqualTo("IN_PROGRESS");
    }

    @Test
    void returnRowsAloneAreNotProductionActivityForStopEither() {
        Fixture f = startedPlanWithReportConsumptionAndReturns();
        reverseReportConsumption(f);

        assertThatThrownBy(() -> planService.stopProduction(F, f.planId()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).as("停产与取消同一判据: 只剩退料 = 空计划, 导向取消")
                                .isEqualTo("PLAN_HAS_NO_PRODUCTION_ACTIVITY"));
        assertThat(status(f)).isEqualTo("IN_PROGRESS");
    }
}
