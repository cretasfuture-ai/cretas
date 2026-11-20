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
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 缺口 16 (2026-11-14 F006 验收): 逐道录入的「工序时限列表」就是这道可领来源的清单。
 * 真 PostgreSQL + 真 Hibernate + 真服务入口 {@link ProcessTimeLimitService#statusForPlan}。
 *
 * <ul>
 *   <li>阳性: 客户 A 的在制批 / 半成品行出现在 A 自己的计划和 A 的另一张计划里;</li>
 *   <li>阴性: 不出现在客户 B 的计划和本厂自有计划里; 服务端直接领 (领用闸) 同样 409;</li>
 *   <li>对齐领用闸: 客户计划不列公司自有的在制批 / 半成品; 公司自有计划照旧列出;</li>
 *   <li>根因读数: 待检 (INSPECTING) 的在制批本来就不进列表 —— F006 的 15549 就是这种。</li>
 * </ul>
 * 每条「不出现」都和同一张列表里「一定出现」的批次同时断言 (列表确实跑出来了)。
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
    static final String RAW = "G16-RAW";
    static final String WORKSHOP = "G16-WKS";
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

    /** 写事件 / 质检放行与「列出哪些来源」无关; 车间仓解析桩成固定仓 (原料端口按它找候选)。 */
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
    @Autowired WorkProcessTaskRepository tasks;

    @BeforeEach
    void setUp() {
        jdbc.execute("DO $$ DECLARE r record; BEGIN "
                + "FOR r IN SELECT conrelid::regclass AS t, conname FROM pg_constraint "
                + "WHERE contype = 'f' AND connamespace = 'public'::regnamespace LOOP "
                + "EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.t, r.conname); END LOOP; END $$");
        when(warehouseResolver.resolveWorkshopId(anyString())).thenReturn(WORKSHOP);
    }

    private record PlanFixture(String planId, ProductionBatch batch, Long taskId) { }

    private PlanFixture plan(String factoryId, String customerId) {
        return plan(factoryId, customerId,
                customerId == null ? InventoryOwnership.COMPANY_OWNED : InventoryOwnership.CUSTOMER_OWNED, false);
    }

    /**
     * 一张计划 + 它的生产批次 + 工艺实例 + 一道工序任务 (App 入口 statusForTask 用) +
     * 这道任务上一个配了时限的半成品投入端口 (SKU = {@link #SEMI}); {@code withRawPort} 时再加一个原料端口 ({@link #RAW})。
     */
    private PlanFixture plan(String factoryId, String customerId, InventoryOwnership outputOwnership,
                             boolean withRawPort) {
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
        plan.setOutputOwnership(outputOwnership);
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

        WorkProcessTask task = new WorkProcessTask();
        task.setFactoryId(factoryId);
        task.setProductionBatchId(batch.getId());
        task.setWorkflowInstanceId(instance.getId());
        task.setWorkProcessId("WP-G16");
        task.setProductTypeId("G16-FIN");
        task.setProcessOrder(2);
        task = tasks.saveAndFlush(task);

        ports.saveAndFlush(port(factoryId, instance.getId(), task.getId(), "in-semi-" + tag, "SEMI_FINISHED", SEMI,
                "OUTPUT_POSTED"));
        if (withRawPort) {
            ports.saveAndFlush(port(factoryId, instance.getId(), task.getId(), "in-raw-" + tag, "RAW_MATERIAL", RAW,
                    "RAW_OUTBOUND"));
        }
        return new PlanFixture(plan.getId(), batch, task.getId());
    }

    private static WorkflowTaskPort port(String factoryId, Long instanceId, Long taskId, String portId, String kind,
                                         String sku, String startEvent) {
        Map<String, Object> limit = new LinkedHashMap<>();
        limit.put("maxMinutes", 240);
        limit.put("startEvent", startEvent);
        WorkflowTaskPort port = new WorkflowTaskPort();
        port.setFactoryId(factoryId);
        port.setWorkflowInstanceId(instanceId);
        port.setTaskId(taskId);
        port.setWorkflowPortId(portId);
        port.setDirection(WorkflowTaskPort.Direction.INPUT);
        port.setOrdinal(0);
        port.setMaterialNodeId("node-" + portId);
        port.setMaterialKind(kind);
        port.setSkuId(sku);
        port.setUnit("kg");
        port.setUnitCode("kg");
        port.setTimeLimitConfig(limit);
        return port;
    }

    /** 车间仓里的原料批次 (原料端口的候选)。 */
    private String rawBatch(String factoryId) {
        MaterialBatch mb = new MaterialBatch();
        mb.setId(UUID.randomUUID().toString());
        mb.setFactoryId(factoryId);
        mb.setBatchNumber("RM-G16-" + mb.getId().substring(0, 8));
        mb.setMaterialTypeId(RAW);
        mb.setWarehouseId(WORKSHOP);
        mb.setReceiptQuantity(new BigDecimal("5"));
        mb.setQuantityUnit("kg");
        mb.setUsedQuantity(BigDecimal.ZERO);
        mb.setReservedQuantity(BigDecimal.ZERO);
        mb.setUnitPrice(BigDecimal.ONE);
        mb.setStatus(MaterialBatchStatus.AVAILABLE);
        mb.setOwnership(InventoryOwnership.COMPANY_OWNED);
        mb.setReceiptDate(LocalDate.now());
        mb.setCreatedBy(OPERATOR);
        materialBatches.saveAndFlush(mb);
        return mb.getBatchNumber();
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
        String semiB = semiRow(FACTORY, custB);
        String semiCompany = semiRow(FACTORY, null);
        // 别厂同 SKU 的公司自有在制批: 任何一张本厂计划都不该看到 (factory_id 隔离)
        PlanFixture foreign = plan(OTHER_FACTORY, null);
        String wipForeign = wip(OTHER_FACTORY, foreign.batch(), MaterialBatchStatus.AVAILABLE);

        // 阳性: A 自己的计划 + A 的另一张计划
        for (PlanFixture own : List.of(a1, a2)) {
            assertThat(listed(FACTORY, own.planId()))
                    .as("客户 A 的计划 %s 能看到 A 的在制批 / 半成品", own.planId())
                    .contains(wipA, semiA)
                    .doesNotContain(wipB, semiB, wipAPending, wipForeign);
        }
        // 阴性: 客户 B
        assertThat(listed(FACTORY, b1.planId()))
                .as("客户 B 的计划看不到 A 的料, 看得到自己的 (列表确实跑出来了)")
                .contains(wipB, semiB)
                .doesNotContain(wipA, semiA, wipAPending, wipForeign);
        // 阴性: 本厂自有计划也不列客户的料
        assertThat(listed(FACTORY, company.planId()))
                .contains(wipCompany, semiCompany)
                .doesNotContain(wipA, semiA, wipB, semiB, wipAPending, wipForeign);
    }

    /**
     * organizer 2026-11-14 定: 与领用闸 (sameOwner) 和半成品投料下拉对齐 ——
     * 代工 (客户) 计划的列表不列公司自有的在制批 / 半成品 (领了也会 409); 公司自有计划的行为不变。
     * 两边各配一条「一定出现」的读数 (客户计划看得到自己的料, 公司计划看得到公司的料)。
     */
    @Test
    void customerPlansDoNotListCompanyOwnedIntermediates_companyPlansUnchanged() {
        String custA = "CUST-A-" + UUID.randomUUID().toString().substring(0, 6);
        PlanFixture a1 = plan(FACTORY, custA);
        PlanFixture company = plan(FACTORY, null);
        PlanFixture company2 = plan(FACTORY, null);

        String wipA = wip(FACTORY, a1.batch(), MaterialBatchStatus.AVAILABLE);
        String wipCompany = wip(FACTORY, company.batch(), MaterialBatchStatus.AVAILABLE);
        String semiCompany = semiRow(FACTORY, null);

        assertThat(listed(FACTORY, a1.planId()))
                .as("客户计划: 看得到自己的在制批, 看不到公司自有的")
                .contains(wipA)
                .doesNotContain(wipCompany, semiCompany);
        for (PlanFixture own : List.of(company, company2)) {
            assertThat(listed(FACTORY, own.planId()))
                    .as("公司自有计划 %s: 公司自有的在制批 / 半成品照常列出 (本计划和别的公司计划产出的)", own.planId())
                    .contains(wipCompany, semiCompany)
                    .doesNotContain(wipA);
        }
        // 服务端同口径: 客户计划直接领公司自有在制批 409
        assertThatThrownBy(() -> ownershipGuard.assertWipBatchDrawable(FACTORY,
                ownershipGuard.ofPlanId(FACTORY, a1.planId()), company.batch(), company.batch().getBatchNumber(), "P2"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IntermediateOwnershipGuard.CODE_CROSS_OWNER));
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

    private List<String> listedForTask(String factoryId, Long taskId) {
        return timeLimits.statusForTask(factoryId, taskId).stream()
                .map(ProcessTimeLimitService.SourceStatus::batchNumber).toList();
    }

    /** App 走 statusForTask (按工序任务): 同一归属口径, 归属从任务所在生产批次的计划算。 */
    @Test
    void appTaskEntryFiltersByTheTasksPlanOwner() {
        String custA = "CUST-A-" + UUID.randomUUID().toString().substring(0, 6);
        String custB = "CUST-B-" + UUID.randomUUID().toString().substring(0, 6);
        PlanFixture a1 = plan(FACTORY, custA);
        PlanFixture b1 = plan(FACTORY, custB);
        PlanFixture company = plan(FACTORY, null);

        String wipA = wip(FACTORY, a1.batch(), MaterialBatchStatus.AVAILABLE);
        String wipB = wip(FACTORY, b1.batch(), MaterialBatchStatus.AVAILABLE);
        String wipCompany = wip(FACTORY, company.batch(), MaterialBatchStatus.AVAILABLE);

        assertThat(listedForTask(FACTORY, b1.taskId()))
                .as("客户 B 的工序任务: 看得到 B 的在制批, 看不到 A 的和公司自有的")
                .contains(wipB)
                .doesNotContain(wipA, wipCompany);
        assertThat(listedForTask(FACTORY, a1.taskId()))
                .contains(wipA)
                .doesNotContain(wipB, wipCompany);
        assertThat(listedForTask(FACTORY, company.taskId()))
                .contains(wipCompany)
                .doesNotContain(wipA, wipB);
    }

    /**
     * 旧数据: 代工计划缺客户 id ⇒ 归属判不出。以前整张列表 409 打不开; 现在原料照常列出,
     * 在制批 / 半成品一律不列, 并带上原因 (web / App 两个入口都一样)。
     */
    @Test
    void legacyCustomerPlanWithoutCustomerIdStillListsRawAndExplainsMissingIntermediates() {
        PlanFixture legacy = plan(FACTORY, null, InventoryOwnership.CUSTOMER_OWNED, true);
        PlanFixture company = plan(FACTORY, null);
        String raw = rawBatch(FACTORY);
        String wipOwn = wip(FACTORY, legacy.batch(), MaterialBatchStatus.AVAILABLE);
        String wipCompany = wip(FACTORY, company.batch(), MaterialBatchStatus.AVAILABLE);
        String semiCompany = semiRow(FACTORY, null);

        for (ProcessTimeLimitService.SourceStatusView view : List.of(
                timeLimits.statusViewForPlan(FACTORY, legacy.planId(), null),
                timeLimits.statusViewForTask(FACTORY, legacy.taskId()))) {
            assertThat(view.sources().stream().map(ProcessTimeLimitService.SourceStatus::batchNumber).toList())
                    .as("原料照常列出, 在制批 / 半成品一律不列")
                    .contains(raw)
                    .doesNotContain(wipOwn, wipCompany, semiCompany);
            assertThat(view.ownershipNotice())
                    .startsWith("本计划缺客户信息，无法判定在制品归属")
                    .contains(legacy.planId())
                    .contains("原料照常显示");
        }
        // 对照: 归属判得出的公司计划没有提示, 且公司自有料照常列出
        ProcessTimeLimitService.SourceStatusView companyView = timeLimits.statusViewForPlan(FACTORY, company.planId(), null);
        assertThat(companyView.ownershipNotice()).isNull();
        assertThat(companyView.sources().stream().map(ProcessTimeLimitService.SourceStatus::batchNumber).toList())
                .contains(wipCompany, semiCompany);
    }
}
