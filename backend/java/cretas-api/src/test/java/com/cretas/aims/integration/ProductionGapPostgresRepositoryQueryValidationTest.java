package com.cretas.aims.integration;

import com.cretas.aims.dto.production.ProductionPlanListFilter;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition.Status;
import com.cretas.aims.entity.factory.FactoryMaterialRequisitionItem;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.factory.FactoryMaterialRequisitionService;
import com.cretas.aims.service.factory.impl.FactoryMaterialRequisitionServiceImpl;
import com.cretas.aims.service.impl.ProductionAnalyticsServiceImpl;
import com.cretas.aims.service.production.ProductionProgressDashboardService;
import com.cretas.aims.service.yield.WeighingPendingTaskService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #1832 复审 H5: 生产缺口补齐新增 / 改过的查询在<b>真 PostgreSQL</b> 上跑 (不是 test profile 的 H2):
 * <ul>
 *   <li>工序产出 / 不良 native SQL {@code ProductionReportRepository#getProcessBreakdown} (含 YIELD、按单位拆行, 复审 M5)</li>
 *   <li>车间大屏两条 native SQL: 当日报工笔数 + 最近报工 (只算报工行, 复审 L5)</li>
 *   <li>待称重任务 JPQL {@code WorkProcessTaskRepository#findWithoutWeighingByStatus} 与批量取批次 / 计划 / 工序 (N+1 修复)</li>
 *   <li>领料单列表筛选 / 按物料汇总 JPQL (null 参数绑定、LIKE 转义 % _)</li>
 *   <li>生产计划列表的批量单号 Criteria 子查询 (LIKE 转义)</li>
 * </ul>
 * 独立 schema + 实体生成 DDL (跟随实体, 不手抄表结构), 连接参数 stringtype=unspecified 与 prod 一致; 用完整个 schema 删掉。
 */
@SpringBootTest(classes = ProductionGapPostgresRepositoryQueryValidationTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
        "spring.sql.init.mode=never", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoint.health.validate-group-membership=false",
        "spring.datasource.hikari.data-source-properties.stringtype=unspecified",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class ProductionGapPostgresRepositoryQueryValidationTest {

    private static final String F = "IT-PROD-GAP-PG";
    private static final String OTHER = "IT-PROD-GAP-PG-OTHER";
    private static final String SCHEMA = "prod_gap_" + UUID.randomUUID().toString().replace("-", "");
    private static final LocalDate TODAY = LocalDate.now();

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @EntityScan("com.cretas.aims.entity")
    @EnableJpaRepositories("com.cretas.aims.repository")
    @Import({FactoryMaterialRequisitionServiceImpl.class, ProductionProgressDashboardService.class,
            WeighingPendingTaskService.class, ProductionAnalyticsServiceImpl.class})
    static class Runtime {
    }

    @BeforeAll
    static void createIsolatedSchema() throws Exception {
        try (var c = DriverManager.getConnection(url(), requiredEnv("CRETAS_WORKFLOW_PG_USER"), password());
             var st = c.createStatement()) {
            st.execute("CREATE SCHEMA \"" + SCHEMA + "\"");
        }
    }

    @AfterAll
    static void dropIsolatedSchema() throws Exception {
        try (var c = DriverManager.getConnection(url(), requiredEnv("CRETAS_WORKFLOW_PG_USER"), password());
             var st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS \"" + SCHEMA + "\" CASCADE");
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = url();
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> requiredEnv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password", ProductionGapPostgresRepositoryQueryValidationTest::password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO \"" + SCHEMA + "\"");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired ProductionReportRepository reports;
    @Autowired WorkProcessTaskRepository tasks;
    @Autowired WorkProcessRepository processes;
    @Autowired ProductionBatchRepository batches;
    @Autowired ProductionPlanRepository plans;
    @Autowired FactoryMaterialRequisitionRepository requisitions;
    @Autowired FactoryMaterialRequisitionService requisitionService;
    @Autowired ProductionProgressDashboardService dashboard;
    @Autowired WeighingPendingTaskService pendingTasks;
    @Autowired ProductionAnalyticsServiceImpl analytics;

    private Long taskNoWeighing;
    private Long taskWeighed;
    private Long taskOnlyVoidedWeighing;

    @BeforeEach
    void fixture() {
        // 仪器自检: 真连的是 PostgreSQL, 并且落在本类的独立 schema 里
        assertThat(jdbc.queryForObject("SELECT version()", String.class)).startsWith("PostgreSQL");
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(SCHEMA);
        // 实体生成的 DDL 带外键, 夹具不建全量上游行 —— 只在本 schema 里摘掉外键
        jdbc.execute("DO $$ DECLARE r record; BEGIN "
                + "FOR r IN SELECT conrelid::regclass AS t, conname FROM pg_constraint "
                + "WHERE contype = 'f' AND connamespace = '" + SCHEMA + "'::regnamespace LOOP "
                + "EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.t, r.conname); END LOOP; END $$");
        for (String t : List.of("production_reports", "work_process_tasks", "work_processes", "production_batches",
                "production_plans", "factory_material_requisition_items", "factory_material_requisitions")) {
            jdbc.execute("DELETE FROM " + t);
        }
        new TransactionTemplate(txManager).executeWithoutResult(s -> seed());
    }

    private void seed() {
        processes.save(WorkProcess.builder().id("WP-PG-CUT").factoryId(F).processName("切丁").defaultOutputMaterialKind(com.cretas.aims.entity.enums.WorkProcessOutputMaterialKind.SEMI_FINISHED).build());
        processes.save(WorkProcess.builder().id("WP-PG-OTHER").factoryId(OTHER).processName("别厂工序").defaultOutputMaterialKind(com.cretas.aims.entity.enums.WorkProcessOutputMaterialKind.SEMI_FINISHED).build());

        ProductionPlan p1 = plan("PLAN-PG-1", "PP-PG-001", F);
        p1.setPlannedDate(TODAY);
        p1.setExpectedCompletionDate(TODAY.plusDays(2));
        plans.save(p1);
        plans.save(plan("PLAN-PG-2", "PP-PG-002", F));
        ProductionBatch b1 = batches.save(batch("PB-100%_A", "PLAN-PG-1", F));
        batches.save(batch("PB-1000XA", "PLAN-PG-2", F));
        ProductionBatch bOther = batches.save(batch("PB-OTHER-1", null, OTHER));

        taskNoWeighing = tasks.save(task(F, b1.getId(), "WP-PG-CUT", 1, WorkProcessTask.Status.IN_PROGRESS)).getId();
        taskWeighed = tasks.save(task(F, b1.getId(), "WP-PG-CUT", 2, WorkProcessTask.Status.IN_PROGRESS)).getId();
        taskOnlyVoidedWeighing = tasks.save(task(F, b1.getId(), "WP-PG-CUT", 3, WorkProcessTask.Status.IN_PROGRESS)).getId();
        tasks.save(task(F, b1.getId(), "WP-PG-CUT", 4, WorkProcessTask.Status.COMPLETED));
        tasks.save(task(OTHER, bOther.getId(), "WP-PG-OTHER", 1, WorkProcessTask.Status.IN_PROGRESS));

        // 称重: 三阶段 INPUT (无产出) + OUTPUT (良品 95kg, 不良 5kg) + 旧式整合 (30 盒, 没清点不良)
        reports.save(yieldReport(F, taskWeighed, "INPUT", null, null, null, new BigDecimal("100")));
        reports.save(yieldReport(F, taskWeighed, "OUTPUT", "95", "kg", "5", null));
        reports.save(yieldReport(F, taskWeighed, null, "30", "盒", null, null));
        // 已软删的称重: 不算报工, 也不算「已称重」
        ProductionReport voided = yieldReport(F, taskOnlyVoidedWeighing, "OUTPUT", "999", "kg", "0", null);
        voided.setDeletedAt(LocalDateTime.now());
        reports.save(voided);
        // 旧进度报工: 切丁 100kg 不良 5; 未分类 10kg
        reports.save(progress(F, "切丁", "100", "kg", "5"));
        reports.save(progress(F, null, "10", "kg", null));
        // 别厂
        reports.save(yieldReport(OTHER, null, "OUTPUT", "777", "kg", "7", null));
        reports.save(progress(OTHER, "切丁", "500", "kg", "50"));

        FactoryMaterialRequisition a = req("REQ-GAP-1", "PP-GAP-001", "含50%脂五花", Status.ISSUED, LocalDate.of(2026, 11, 10));
        item(a, "M-1", "鸡胸", "kg", "50", "48", "3");
        item(a, "M-2", "盐", "kg", "5", "5", "0");
        FactoryMaterialRequisition b = req("REQ-GAP-2", "PP-GAP-002", "牛肉面", Status.CLOSED, LocalDate.of(2026, 11, 15));
        item(b, "M-1", "鸡胸", "kg", "20", "20", "2");
        item(b, "M-1", "鸡胸", "个", "7", "7", "1");
        FactoryMaterialRequisition c = req("REQ-GAP-3", "PP-GAP-003", "500克_装黄油鸡", Status.CANCELLED, LocalDate.of(2026, 11, 12));
        item(c, "M-1", "鸡胸", "kg", "900", "900", "0");
        requisitions.saveAll(List.of(a, b, c));
    }

    // ==================== 工序产出 / 不良 (复审 M5) ====================

    @Test
    void processBreakdownIncludesWeighingOutputRowsSplitsUnitsAndKeepsUncountedDefectNull() {
        List<Map<String, Object>> raw = reports.getProcessBreakdown(F, TODAY, TODAY);
        // (PROGRESS,切丁,kg) (YIELD,切丁,kg) (YIELD,切丁,盒) (PROGRESS,未分类,kg); INPUT 行 / 软删 / 别厂都不在
        assertThat(raw).hasSize(4);
        Map<String, Object> yieldKg = find(raw, "YIELD", "切丁", "kg");
        assertThat(dec(yieldKg.get("output"))).isEqualByComparingTo("95");
        assertThat(dec(yieldKg.get("defect"))).isEqualByComparingTo("5");
        assertThat(dec(yieldKg.get("counted_output"))).isEqualByComparingTo("95");
        assertThat(((Number) yieldKg.get("report_count")).longValue()).isEqualTo(1L);
        Map<String, Object> yieldBox = find(raw, "YIELD", "切丁", "盒");
        assertThat(yieldBox.get("defect")).as("没清点不良 = 不知道, 不是 0").isNull();
        assertThat(((Number) yieldBox.get("defect_counted_count")).longValue()).isZero();
        assertThat(find(raw, "PROGRESS", "未分类", "kg")).isNotNull();

        List<Map<String, Object>> merged = analytics.getByProcess(F, TODAY, TODAY);
        Map<String, Object> cutKg = merged.stream()
                .filter(m -> "切丁".equals(m.get("process_category")) && "kg".equals(m.get("unit"))).findFirst().orElseThrow();
        // (称重 5 + 进度 5) / (称重 95+5 + 进度 100) = 0.05
        assertThat((BigDecimal) cutKg.get("defectRate")).isEqualByComparingTo("0.05");
        assertThat((BigDecimal) cutKg.get("output")).isEqualByComparingTo("195");
        Map<String, Object> cutBox = merged.stream()
                .filter(m -> "切丁".equals(m.get("process_category")) && "盒".equals(m.get("unit"))).findFirst().orElseThrow();
        assertThat(cutBox.get("defectRate")).isNull();
        assertThat(cutBox).containsEntry("defectStatus", ProductionAnalyticsServiceImpl.DEFECT_NOT_COUNTED);
    }

    // ==================== 车间大屏两条 native SQL (复审 L5) ====================

    @Test
    void dashboardCountsOnlyReportRowsAndListsRecentWithProcessName() {
        Map<String, Object> data = dashboard.getDashboard(F, TODAY);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) data.get("summary");
        // 称重 OUTPUT + 称重旧式整合 + 进度 ×2 = 4; INPUT 行 / 软删 / 别厂 不算
        assertThat(summary.get("todayReportCount")).isEqualTo(4L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recent = (List<Map<String, Object>>) data.get("recentReports");
        assertThat(recent).hasSize(4);
        assertThat(recent).allSatisfy(r -> assertThat(r.get("outputQuantity")).isNotNull());
        assertThat(recent).filteredOn(r -> "切丁".equals(r.get("processName"))).hasSize(2);
        // 计划那条 SQL 也在真 PG 上跑过: 当天建的计划带时间进度
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> planRows = (List<Map<String, Object>>) data.get("plans");
        assertThat(planRows).extracting(p -> p.get("planNumber")).contains("PP-PG-001");
        Map<String, Object> p1 = planRows.stream().filter(p -> "PP-PG-001".equals(p.get("planNumber"))).findFirst().orElseThrow();
        assertThat(p1.get("timeProgressPct")).isEqualTo(0);
        assertThat(p1.get("dueDate")).isEqualTo(TODAY.plusDays(2).toString());
    }

    // ==================== 待称重任务 JPQL + 批量取关联 ====================

    @Test
    void pendingWeighingTasksAreInProgressWithoutLiveWeighingAndScopedToFactory() {
        List<WorkProcessTask> found = tasks.findWithoutWeighingByStatus(F, WorkProcessTask.Status.IN_PROGRESS);
        assertThat(found).extracting(WorkProcessTask::getId)
                .containsExactlyInAnyOrder(taskNoWeighing, taskOnlyVoidedWeighing);

        List<WeighingPendingTaskService.PendingTask> list = pendingTasks.list(F, TODAY);
        assertThat(list).hasSize(2);
        assertThat(list).allSatisfy(t -> {
            assertThat(t.batchNumber()).isEqualTo("PB-100%_A");
            assertThat(t.planNumber()).isEqualTo("PP-PG-001");
            assertThat(t.processName()).isEqualTo("切丁");
            assertThat(t.plannedToday()).isTrue();
        });
    }

    // ==================== 领料单列表 / 汇总 JPQL ====================

    @Test
    void requisitionSearchBindsNullFiltersAndHonoursEachFilter() {
        PageRequest page = PageRequest.of(0, 50);
        assertThat(requisitionService.list(F, null, null, null, null, null, page).getTotalElements()).isEqualTo(3);
        assertThat(requisitionService.list(F, null, "牛肉", null, null, null, page).getContent())
                .extracting(FactoryMaterialRequisition::getRequisitionNo).containsExactly("REQ-GAP-2");
        assertThat(requisitionService.list(F, null, null, "gap-001", null, null, page).getContent())
                .extracting(FactoryMaterialRequisition::getRequisitionNo).containsExactly("REQ-GAP-1");
        assertThat(requisitionService.list(F, null, null, null, LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 14), page)
                .getContent()).extracting(FactoryMaterialRequisition::getRequisitionNo).containsExactly("REQ-GAP-3");
        assertThat(requisitionService.list(F, Status.CLOSED, null, null, null, null, page).getTotalElements()).isEqualTo(1);
        assertThat(requisitionService.list(OTHER, null, null, null, null, null, page).getTotalElements()).isZero();
    }

    @Test
    void requisitionKeywordWildcardsAreLiteral() {
        PageRequest page = PageRequest.of(0, 50);
        // 「%」只匹配名字里真有 % 的单, 不再匹配一切
        assertThat(requisitionService.list(F, null, "%", null, null, null, page).getContent())
                .extracting(FactoryMaterialRequisition::getRequisitionNo).containsExactly("REQ-GAP-1");
        assertThat(requisitionService.list(F, null, "50%脂", null, null, null, page).getContent())
                .extracting(FactoryMaterialRequisition::getRequisitionNo).containsExactly("REQ-GAP-1");
        // 「_」只匹配真有下划线的, 不当单字符通配
        assertThat(requisitionService.list(F, null, "_", null, null, null, page).getContent())
                .extracting(FactoryMaterialRequisition::getRequisitionNo).containsExactly("REQ-GAP-3");
        // 「gap_00」: 下划线是字面量, 单号里是「-」⇒ 无命中 (通配时会命中三张)
        assertThat(requisitionService.list(F, null, null, "gap_00", null, null, page).getTotalElements()).isZero();
        // 转义符本身也按字面量
        assertThat(requisitionService.list(F, null, "!", null, null, null, page).getTotalElements()).isZero();
    }

    @Test
    void summarizeByMaterialSplitsUnitsAndSkipsCancelled() {
        List<FactoryMaterialRequisitionService.MaterialSummaryRow> rows =
                requisitionService.summarizeByMaterial(F, null, null, null, null, null);
        assertThat(rows).hasSize(3);
        var chickenKg = rows.stream().filter(r -> "M-1".equals(r.materialTypeId()) && "kg".equals(r.unit()))
                .findFirst().orElseThrow();
        assertThat(chickenKg.issuedQty()).isEqualByComparingTo("68");
        assertThat(chickenKg.returnedQty()).isEqualByComparingTo("5");
        assertThat(chickenKg.netIssuedQty()).isEqualByComparingTo("63");
        var chickenPiece = rows.stream().filter(r -> "M-1".equals(r.materialTypeId()) && "个".equals(r.unit()))
                .findFirst().orElseThrow();
        assertThat(chickenPiece.issuedQty()).isEqualByComparingTo("7");
        // 关键词同样作用于汇总, 且 % 是字面量
        assertThat(requisitionService.summarizeByMaterial(F, null, "%", null, null, null))
                .extracting(FactoryMaterialRequisitionService.MaterialSummaryRow::materialName)
                .containsExactlyInAnyOrder("鸡胸", "盐");
    }

    // ==================== 生产计划列表: 批量单号 Criteria 子查询 ====================

    @Test
    void planBatchNumberFilterMatchesAnyBatchOfThePlanWithLiteralWildcards() {
        Specification<ProductionPlan> factory = (root, q, cb) -> cb.equal(root.get("factoryId"), F);
        assertThat(plans.findAll(factory.and(ProductionPlanListFilter.of(null, null, "pb-100").toSpecification())))
                .extracting(ProductionPlan::getId).containsExactlyInAnyOrder("PLAN-PG-1", "PLAN-PG-2");
        // % 与 _ 按字面量: 只有 PB-100%_A 命中
        assertThat(plans.findAll(factory.and(ProductionPlanListFilter.of(null, null, "100%").toSpecification())))
                .extracting(ProductionPlan::getId).containsExactly("PLAN-PG-1");
        assertThat(plans.findAll(factory.and(ProductionPlanListFilter.of(null, null, "%_a").toSpecification())))
                .extracting(ProductionPlan::getId).containsExactly("PLAN-PG-1");
        assertThat(plans.findAll(factory.and(ProductionPlanListFilter.of(null, null, "PB-1000_A").toSpecification())))
                .isEmpty();
        assertThat(plans.findAll(factory.and(ProductionPlanListFilter.of(null, "kg", null).toSpecification())))
                .hasSize(2);
        assertThat(plans.findAll(factory.and(ProductionPlanListFilter.of("PT-NONE", null, null).toSpecification())))
                .isEmpty();
    }

    // ==================== 夹具 ====================

    private static ProductionPlan plan(String id, String number, String factory) {
        ProductionPlan p = new ProductionPlan();
        p.setId(id);
        p.setFactoryId(factory);
        p.setPlanNumber(number);
        p.setProductTypeId("PT-PG");
        p.setPlannedQuantity(new BigDecimal("100"));
        p.setPlannedUnit("kg");
        p.setStatus(ProductionPlanStatus.IN_PROGRESS);
        p.setCreatedBy(1L);
        return p;
    }

    private static ProductionBatch batch(String number, String planId, String factory) {
        ProductionBatch b = new ProductionBatch();
        b.setFactoryId(factory);
        b.setBatchNumber(number);
        b.setProductionPlanId(planId);
        b.setProductTypeId("PT-PG");
        b.setQuantity(BigDecimal.TEN);
        b.setUnit("kg");
        b.setStatus(ProductionBatchStatus.IN_PROGRESS);
        b.setBatchType("REGULAR");
        return b;
    }

    private static WorkProcessTask task(String factory, Long batchId, String wpId, int order, WorkProcessTask.Status st) {
        WorkProcessTask t = new WorkProcessTask();
        t.setFactoryId(factory);
        t.setProductionBatchId(batchId);
        t.setWorkProcessId(wpId);
        t.setProductTypeId("PT-PG");
        t.setProcessOrder(order);
        t.setStatus(st);
        t.setPlannedQuantity(BigDecimal.TEN);
        t.setPlannedUnit("kg");
        return t;
    }

    private static ProductionReport yieldReport(String factory, Long taskId, String kind, String out, String unit,
                                          String defect, BigDecimal input) {
        return ProductionReport.builder().factoryId(factory).workerId(1L).reportType("YIELD").reportKind(kind)
                .reportDate(TODAY).workProcessTaskId(taskId)
                .inputQuantity(input).inputUnit(input == null ? null : "kg")
                .outputQuantity(out == null ? null : new BigDecimal(out)).outputUnit(unit)
                .defectQuantity(defect == null ? null : new BigDecimal(defect))
                .goodQuantity(defect == null || out == null ? null : new BigDecimal(out))
                .build();
    }

    private static ProductionReport progress(String factory, String category, String out, String unit, String defect) {
        return ProductionReport.builder().factoryId(factory).workerId(1L).reportType("PROGRESS")
                .processCategory(category).reportDate(TODAY)
                .outputQuantity(new BigDecimal(out)).outputUnit(unit)
                .defectQuantity(defect == null ? null : new BigDecimal(defect))
                .build();
    }

    private static FactoryMaterialRequisition req(String no, String planNo, String product, Status status, LocalDate required) {
        FactoryMaterialRequisition r = new FactoryMaterialRequisition();
        r.setFactoryId(F);
        r.setRequisitionNo(no);
        r.setProductionPlanId("PLAN-" + no);
        r.setProductionPlanNumber(planNo);
        r.setProductName(product);
        r.setStatus(status);
        r.setRequiredDate(required);
        return r;
    }

    private static void item(FactoryMaterialRequisition r, String matId, String name, String unit,
                             String picked, String issued, String returned) {
        FactoryMaterialRequisitionItem i = new FactoryMaterialRequisitionItem();
        i.setRequisition(r);
        i.setMaterialTypeId(matId);
        i.setMaterialName(name);
        i.setUnit(unit);
        i.setRequiredQty(new BigDecimal("100"));
        i.setPickedQty(new BigDecimal(picked));
        i.setIssuedQty(new BigDecimal(issued));
        i.setReturnedQty(new BigDecimal(returned));
        r.getItems().add(i);
    }

    private static Map<String, Object> find(List<Map<String, Object>> rows, String source, String process, String unit) {
        return rows.stream().filter(r -> source.equals(r.get("source")) && process.equals(r.get("process_category"))
                && unit.equals(r.get("unit"))).findFirst().orElseThrow(() -> new AssertionError(
                "缺行 " + source + "/" + process + "/" + unit + " in " + rows));
    }

    private static BigDecimal dec(Object v) {
        return v instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(v));
    }

    private static String url() {
        return DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
    }

    private static String password() {
        return System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set when PostgreSQL verification is enabled");
        }
        return value;
    }
}
