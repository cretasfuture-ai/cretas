package com.cretas.aims.integration;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.workflow.ProductionWorkflowInstance;
import com.cretas.aims.entity.workflow.WorkflowTaskPort;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.foodsafety.timelimit.ProcessTimeLimitEventWriter;
import com.cretas.aims.service.foodsafety.timelimit.ProcessTimeLimitService;
import com.cretas.aims.service.processentry.IntermediateOwnershipGuard;
import com.cretas.aims.service.quality.release.QcReleaseService;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 缺口 16 (2026-11-14 F006 验收): 逐道录入的「工序时限列表」就是这道可领来源的清单。
 * 真 PostgreSQL + 真 Hibernate + 真服务入口 {@link ProcessTimeLimitService#statusForPlan}。
 *
 * <ul>
 *   <li>阳性: 客户 A 的在制批 / 半成品行出现在 A 自己的计划和 A 的另一张计划里;</li>
 *   <li>阴性: 不出现在客户 B 的计划和本厂自有计划里; 服务端直接领 (领用闸) 同样 409;</li>
 *   <li>不变: 公司自有的在制批在所有计划里照旧列出 (是否收掉留给 organizer 定);</li>
 *   <li>根因读数: 待检 (INSPECTING) 的在制批本来就不进列表 —— F006 的 15549 就是这种。</li>
 * </ul>
 * 每条「不出现」都和同一张列表里「一定出现」的公司自有批同时断言 (列表确实跑出来了)。
 */
@SpringBootTest(classes = TimeLimitCandidateOwnershipPostgresContractTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoint.health.validate-group-membership=false",
        "spring.datasource.hikari.data-source-properties.stringtype=unspecified",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class TimeLimitCandidateOwnershipPostgresContractTest {

    static final String FACTORY = "G16-TL-PG";
    static final String OTHER_FACTORY = "G16-TL-OTHER";
    static final String SEMI = "G16-SEMI";
    static final Long OPERATOR = 10016L;

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @EntityScan("com.cretas.aims.entity")
    @EnableJpaRepositories("com.cretas.aims.repository")
    @Import({ProcessTimeLimitService.class, IntermediateOwnershipGuard.class})
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

    /** 写事件 / 质检放行 / 车间仓解析 与「列出哪些来源」无关 (原料端口才用车间仓, 本测试只有半成品端口)。 */
    @MockBean ProcessTimeLimitEventWriter eventWriter;
    @MockBean QcReleaseService qcReleaseService;
    @MockBean WarehouseResolver warehouseResolver;

    @Autowired JdbcTemplate jdbc;
    @Autowired ProcessTimeLimitService timeLimits;
    @Autowired IntermediateOwnershipGuard ownershipGuard;
    @Autowired ProductionPlanRepository plans;
    @Autowired ProductionBatchRepository batches;
    @Autowired ProductionWorkflowInstanceRepository instances;
    @Autowired WorkflowTaskPortRepository ports;
    @Autowired MaterialBatchRepository materialBatches;
    @Autowired SemiFinishedInventoryRepository semis;

    @BeforeEach
    void setUp() {
        jdbc.execute("DO $$ DECLARE r record; BEGIN "
                + "FOR r IN SELECT conrelid::regclass AS t, conname FROM pg_constraint "
                + "WHERE contype = 'f' AND connamespace = 'public'::regnamespace LOOP "
                + "EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.t, r.conname); END LOOP; END $$");
    }

    private record PlanFixture(String planId, ProductionBatch batch) { }

    /** 一张计划 + 它的生产批次 + 工艺实例 + 一个配了时限的半成品投入端口 (SKU = {@link #SEMI})。 */
    private PlanFixture plan(String factoryId, String customerId) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        ProductionPlan plan = new ProductionPlan();
        plan.setId("PLAN-G16-" + tag);
        plan.setFactoryId(factoryId);
        plan.setPlanNumber(plan.getId());
        plan.setProductTypeId("G16-FIN");
        plan.setPlannedQuantity(new BigDecimal("10"));
        plan.setPlannedUnit("kg");
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        plan.setCreatedBy(OPERATOR);
        plan.setIsLocked(false);
        plan.setSkipProcessReporting(false);
        plan.setCreatedAt(LocalDateTime.now());
        plan.setUpdatedAt(LocalDateTime.now());
        plan.setCustomerId(customerId);
        plan.setOutputOwnership(customerId == null ? InventoryOwnership.COMPANY_OWNED : InventoryOwnership.CUSTOMER_OWNED);
        plans.saveAndFlush(plan);

        ProductionBatch batch = ProductionBatch.builder()
                .factoryId(factoryId)
                .batchNumber("CLK-W-G16-" + tag)
                .productTypeId("G16-FIN")
                .productionPlanId(plan.getId())
                .quantity(new BigDecimal("10"))
                .unit("kg")
                .status(ProductionBatchStatus.IN_PROGRESS)
                .build();
        batch = batches.saveAndFlush(batch);

        ProductionWorkflowInstance instance = instances.saveAndFlush(ProductionWorkflowInstance.create(
                factoryId, batch.getId(), "G16-FIN", 1L, 1, "[]", "[]", LocalDateTime.now()));

        Map<String, Object> limit = new LinkedHashMap<>();
        limit.put("maxMinutes", 240);
        limit.put("startEvent", "OUTPUT_POSTED");
        WorkflowTaskPort port = new WorkflowTaskPort();
        port.setFactoryId(factoryId);
        port.setWorkflowInstanceId(instance.getId());
        port.setTaskId(900L);
        port.setWorkflowPortId("in-semi-" + tag);
        port.setDirection(WorkflowTaskPort.Direction.INPUT);
        port.setOrdinal(0);
        port.setMaterialNodeId("node-semi");
        port.setMaterialKind("SEMI_FINISHED");
        port.setSkuId(SEMI);
        port.setUnit("kg");
        port.setUnitCode("kg");
        port.setTimeLimitConfig(limit);
        ports.saveAndFlush(port);
        return new PlanFixture(plan.getId(), batch);
    }

    /** 逐道录入产出的在制批 (归属不落在行上, 从产出它的生产批次的计划现算)。 */
    private String wip(String factoryId, ProductionBatch producer, MaterialBatchStatus status) {
        MaterialBatch mb = new MaterialBatch();
        mb.setId(UUID.randomUUID().toString());
        mb.setFactoryId(factoryId);
        mb.setBatchNumber("WIP-" + producer.getBatchNumber() + "-" + mb.getId().substring(0, 4));
        mb.setProductTypeId(SEMI);
        mb.setWarehouseId("G16-WKS");
        mb.setReceiptQuantity(new BigDecimal("0.4"));
        mb.setQuantityUnit("kg");
        mb.setUsedQuantity(BigDecimal.ZERO);
        mb.setReservedQuantity(BigDecimal.ZERO);
        mb.setUnitPrice(BigDecimal.ZERO);
        mb.setStatus(status);
        mb.setSourceDocType("PRODUCTION_BATCH");
        mb.setSourceDocId(String.valueOf(producer.getId()));
        mb.setOwnership(InventoryOwnership.COMPANY_OWNED);   // 与 prod 一致: 在制批这一列恒为公司自有
        mb.setReceiptDate(LocalDate.now());
        mb.setCreatedBy(OPERATOR);
        materialBatches.saveAndFlush(mb);
        return mb.getBatchNumber();
    }

    /** 常驻半成品行 (归属落在行上)。 */
    private String semiRow(String factoryId, String ownerCustomerId) {
        SemiFinishedInventory sfi = new SemiFinishedInventory();
        sfi.setFactoryId(factoryId);
        sfi.setIntermediateBatchNo("SFI-G16-" + UUID.randomUUID().toString().substring(0, 8));
        sfi.setProcessOrder(1);
        sfi.setProductTypeId(SEMI);
        sfi.setProducedQuantity(new BigDecimal("2"));
        sfi.setConsumedQuantity(BigDecimal.ZERO);
        sfi.setAvailableQuantity(new BigDecimal("2"));
        sfi.setUnit("kg");
        sfi.setOwnership(ownerCustomerId == null ? InventoryOwnership.COMPANY_OWNED : InventoryOwnership.CUSTOMER_OWNED);
        sfi.setOwnerCustomerId(ownerCustomerId);
        sfi.setCreatedAt(LocalDateTime.now());
        sfi.setUpdatedAt(LocalDateTime.now());
        semis.saveAndFlush(sfi);
        return sfi.getIntermediateBatchNo();
    }

    private List<String> listed(String factoryId, String planId) {
        return timeLimits.statusForPlan(factoryId, planId, null).stream()
                .map(ProcessTimeLimitService.SourceStatus::batchNumber).toList();
    }

    @Test
    void customerOwnedIntermediatesAreListedOnlyForTheSameCustomer() {
        String custA = "CUST-A-" + UUID.randomUUID().toString().substring(0, 6);
        String custB = "CUST-B-" + UUID.randomUUID().toString().substring(0, 6);
        PlanFixture a1 = plan(FACTORY, custA);
        PlanFixture a2 = plan(FACTORY, custA);
        PlanFixture b1 = plan(FACTORY, custB);
        PlanFixture company = plan(FACTORY, null);

        String wipA = wip(FACTORY, a1.batch(), MaterialBatchStatus.AVAILABLE);
        String wipAPending = wip(FACTORY, a1.batch(), MaterialBatchStatus.INSPECTING);
        String wipB = wip(FACTORY, b1.batch(), MaterialBatchStatus.AVAILABLE);
        String wipCompany = wip(FACTORY, company.batch(), MaterialBatchStatus.AVAILABLE);
        String semiA = semiRow(FACTORY, custA);
        String semiCompany = semiRow(FACTORY, null);
        // 别厂同 SKU 的公司自有在制批: 任何一张本厂计划都不该看到 (factory_id 隔离)
        PlanFixture foreign = plan(OTHER_FACTORY, null);
        String wipForeign = wip(OTHER_FACTORY, foreign.batch(), MaterialBatchStatus.AVAILABLE);

        // 阳性: A 自己的计划 + A 的另一张计划
        for (PlanFixture own : List.of(a1, a2)) {
            assertThat(listed(FACTORY, own.planId()))
                    .as("客户 A 的计划 %s 能看到 A 的在制批 / 半成品", own.planId())
                    .contains(wipA, semiA, wipCompany, semiCompany)
                    .doesNotContain(wipB, wipAPending, wipForeign);
        }
        // 阴性: 客户 B
        assertThat(listed(FACTORY, b1.planId()))
                .as("客户 B 的计划看不到 A 的料, 但看得到自己的 + 公司自有 (列表确实跑出来了)")
                .contains(wipB, wipCompany, semiCompany)
                .doesNotContain(wipA, semiA, wipAPending, wipForeign);
        // 阴性: 本厂自有计划也不列客户的料
        assertThat(listed(FACTORY, company.planId()))
                .contains(wipCompany, semiCompany)
                .doesNotContain(wipA, semiA, wipB, wipAPending, wipForeign);
    }

    /** 服务端直接领 (不走列表): 领用闸用同一归属口径, B 领 A 的在制批 409, A 的另一张计划放行。 */
    @Test
    void directDrawOfAnotherCustomersWipIsRefused() {
        String custA = "CUST-A-" + UUID.randomUUID().toString().substring(0, 6);
        String custB = "CUST-B-" + UUID.randomUUID().toString().substring(0, 6);
        PlanFixture a1 = plan(FACTORY, custA);
        PlanFixture a2 = plan(FACTORY, custA);
        PlanFixture b1 = plan(FACTORY, custB);

        assertThatThrownBy(() -> ownershipGuard.assertWipBatchDrawable(FACTORY,
                ownershipGuard.ofPlanId(FACTORY, b1.planId()), a1.batch(), a1.batch().getBatchNumber(), "P2"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IntermediateOwnershipGuard.CODE_CROSS_OWNER));
        assertThatCode(() -> ownershipGuard.assertWipBatchDrawable(FACTORY,
                ownershipGuard.ofPlanId(FACTORY, a2.planId()), a1.batch(), a1.batch().getBatchNumber(), "P2"))
                .doesNotThrowAnyException();
    }
}
