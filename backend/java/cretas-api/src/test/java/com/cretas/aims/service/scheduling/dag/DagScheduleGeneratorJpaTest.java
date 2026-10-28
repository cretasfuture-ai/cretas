package com.cretas.aims.service.scheduling.dag;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.scheduling.GenerateScheduleRequest;
import com.cretas.aims.dto.scheduling.LineScheduleDTO;
import com.cretas.aims.dto.scheduling.SchedulingPlanDTO;
import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.ProductProcessWorkflowRevision;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.WorkProcessOutputMaterialKind;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.LineScheduleRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRevisionRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.SchedulingPlanRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.NotificationService;
import com.cretas.aims.service.PushNotificationService;
import com.cretas.aims.service.scheduling.core.SchedulingAIService;
import com.cretas.aims.service.scheduling.core.WorkerAssignmentService;
import com.cretas.aims.service.scheduling.impl.SchedulingPlanCrudServiceImpl;
import com.cretas.aims.service.workflow.CompiledProductProcessWorkflow;
import com.cretas.aims.service.workflow.ProductProcessWorkflowRuntimeCompiler;
import com.cretas.aims.service.workflow.impl.ProductProcessWorkflowRuntimeServiceImpl;
import com.cretas.aims.service.workprocess.WorkflowStepUnlockGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * APS v1 服务层: 真 JPA (独立 H2 库) 跑 生成器 → 工艺版本编译 → 引擎 → 调度计划落库 整条链。
 * 只有「工艺图编译器」被桩掉 (给定一张固定的图), 其余 —— 计划 / 批次 / 工序 / 产线 / 版本查找、
 * 落 line_schedules (含 jsonb task_timeline)、回写 work_process_tasks —— 都是真库。
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        // create-drop 必须独占自己的库: 上下文被缓存驱逐时不能 drop 别的测试上下文的 schema。
        // JSONB 映成 VARCHAR: H2 原生 JSON 读回是双重编码字符串, hypersistence 反序列化会炸
        // (与 LineageEdgeRecorderTransactionContractTest 同一处理); task_timeline 要真往返, 不能置空绕过。
        "spring.datasource.url=jdbc:h2:mem:aps_dag_generator;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=MONTH,YEAR,VALUE"
                + ";INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS VARCHAR",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims")
@Import({
        DagScheduleGenerator.class,
        SchedulingPlanCrudServiceImpl.class,
        WorkflowStepUnlockGuard.class,
        ProductProcessWorkflowRuntimeServiceImpl.class,
        DagScheduleGeneratorJpaTest.TestBeans.class
})
class DagScheduleGeneratorJpaTest {

    private static final String F = "F-APS";
    private static final String OTHER = "F-APS-OTHER";
    private static final LocalDate DAY = LocalDate.of(2026, 10, 29);

    @TestConfiguration
    static class TestBeans {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @MockBean private ProductProcessWorkflowRuntimeCompiler compiler;
    @MockBean private com.cretas.aims.service.validation.ProductProcessWorkflowUnitValidator unitValidator;
    @MockBean private com.cretas.aims.service.unit.UnitContractService unitContractService;
    @MockBean private NotificationService notificationService;
    @MockBean private PushNotificationService pushNotificationService;
    @MockBean private WorkerAssignmentService workerAssignmentService;
    @MockBean private SchedulingAIService schedulingAIService;

    @Autowired private DagScheduleGenerator generator;
    @Autowired private EntityManager em;
    @Autowired private ProductionPlanRepository planRepository;
    @Autowired private ProductionBatchRepository batchRepository;
    @Autowired private ProductionLineRepository lineRepository;
    @Autowired private WorkProcessRepository workProcessRepository;
    @Autowired private WorkProcessTaskRepository taskRepository;
    @Autowired private ProductProcessWorkflowRevisionRepository revisionRepository;
    @Autowired private SchedulingPlanRepository schedulingPlanRepository;
    @Autowired private LineScheduleRepository lineScheduleRepository;

    /** productTypeId → 编译出的图 (编译器桩按版本的产品分发)。 */
    private final Map<String, CompiledProductProcessWorkflow> graphs = new HashMap<>();
    private String lineId;

    @BeforeEach
    void setUp() {
        // 本测不验外键 (工厂 / 产品 / 用户主数据与排程无关), 只验排程读写。
        em.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();
        graphs.clear();
        when(compiler.compile(any())).thenAnswer(inv -> {
            ProductProcessWorkflowDTO def = inv.getArgument(0);
            CompiledProductProcessWorkflow g = graphs.get(def.getProductTypeId());
            if (g == null) {
                throw new IllegalStateException("no graph for " + def.getProductTypeId());
            }
            return g;
        });
        process(F, "WP-CUT", "分切", 30);
        process(F, "WP-MIX", "搅拌", 45);
        process(F, "WP-SEASON", "调味", 15);
        process(F, "WP-PACK", "包装", 10);
        process(F, "WP-COOK", "蒸煮", null);
        lineId = line(F, "一号线").getId();
    }

    // ==================== 未开工计划: 按编译出的图排 ====================

    @Test
    void unstartedPlanIsScheduledFromItsCompiledGraphAndPersistedWithPlanIdAndTimeline() {
        // 分切(30) → 搅拌(任务上没工时, 回落工序 45) ┐
        // 调味(15, 只吃原料, 并行)                  ┴→ 包装(10)
        graphs.put("PT-A", graph(
                node("n-cut", "WP-CUT", 1, null, List.of("raw-pork"), List.of("m-cut")),
                node("n-mix", "WP-MIX", 2, null, List.of("m-cut"), List.of("m-mix")),
                node("n-season", "WP-SEASON", 3, 15, List.of("raw-salt"), List.of("m-season")),
                node("n-pack", "WP-PACK", 4, 10, List.of("m-mix", "m-season"), List.of("fg"))));
        ProductionPlan plan = workflowPlan(F, "PP-A", "PLAN-A", "PT-A", "12.4");

        SchedulingPlanDTO dto = generator.generate(F, request(List.of("PP-A")), 7L);

        assertThat(dto.getWarnings()).contains(DagScheduleGenerator.DEFAULT_START_NOTE);
        assertThat(dto.getNotes()).contains("08:00");
        assertThat(dto.getLineSchedules()).hasSize(1);
        LineScheduleDTO ls = dto.getLineSchedules().get(0);
        assertThat(ls.getProductionPlanId()).isEqualTo(plan.getId());
        assertThat(ls.getPlanNumber()).isEqualTo("PLAN-A");
        assertThat(ls.getProductionLineId()).isEqualTo(lineId);
        assertThat(ls.getBatchId()).isNull();
        assertThat(ls.getPlannedQuantity()).isEqualTo(12);
        assertThat(ls.getPlannedStartTime()).isEqualTo(DAY.atTime(8, 0));
        assertThat(ls.getPlannedEndTime()).isEqualTo(DAY.atTime(9, 25));

        // 真库往返: 清掉一级缓存再读, task_timeline 走 jsonb 列回来
        em.flush();
        em.clear();
        List<LineSchedule> persisted = lineScheduleRepository.findByPlanIdOrderBySequenceOrder(dto.getId());
        assertThat(persisted).hasSize(1);
        LineSchedule row = persisted.get(0);
        assertThat(row.getProductionPlanId()).isEqualTo("PP-A");
        Map<String, Map<String, Object>> byName = new HashMap<>();
        row.getTaskTimeline().forEach(e -> byName.put((String) e.get("taskName"), e));
        System.out.println("APS_TIMELINE " + row.getTaskTimeline());
        assertThat(byName).containsOnlyKeys("分切", "搅拌", "调味", "包装");
        assertThat(byName.get("分切").get("start")).isEqualTo("2026-10-29T08:00");
        assertThat(byName.get("搅拌").get("start")).isEqualTo("2026-10-29T08:30");
        assertThat(byName.get("搅拌").get("end")).isEqualTo("2026-10-29T09:15");
        assertThat(byName.get("调味").get("start")).isEqualTo("2026-10-29T08:00"); // 并行分支
        assertThat(byName.get("包装").get("start")).isEqualTo("2026-10-29T09:15"); // 等慢的那支
        assertThat(byName.get("包装").get("workProcessId")).isEqualTo("WP-PACK");
        assertThat(byName.get("包装").get("status")).isEqualTo("SCHEDULED");
        // 未开工计划不写任务表
        assertThat(taskRepository.findAll()).isEmpty();
    }

    @Test
    void explicitStartTimeIsUsedAndNoDefaultWarningIsAdded() {
        graphs.put("PT-A", graph(node("n-cut", "WP-CUT", 1, null, List.of("raw"), List.of("fg"))));
        workflowPlan(F, "PP-A", "PLAN-A", "PT-A", "5");
        GenerateScheduleRequest req = request(List.of("PP-A"));
        req.setStartTime("13:30");

        SchedulingPlanDTO dto = generator.generate(F, req, 7L);

        assertThat(dto.getWarnings()).doesNotContain(DagScheduleGenerator.DEFAULT_START_NOTE);
        assertThat(dto.getLineSchedules().get(0).getPlannedStartTime()).isEqualTo(DAY.atTime(13, 30));
        assertThat(dto.getLineSchedules().get(0).getPlannedEndTime()).isEqualTo(DAY.atTime(14, 0));
    }

    // ==================== 缺工时: 不排、不占工位、列出工序名 ====================

    @Test
    void planWithMissingDurationIsNotPersistedReservesNoStationAndNamesTheStep() {
        // P-MISSING 排在前面, 也用分切工位; 它的蒸煮缺工时 (任务和工序定义上都没有)
        graphs.put("PT-MISSING", graph(
                node("m-cut", "WP-CUT", 1, null, List.of("raw"), List.of("m1")),
                node("m-cook", "WP-COOK", 2, null, List.of("m1"), List.of("m2")),
                node("m-pack", "WP-PACK", 3, null, List.of("m2"), List.of("fg"))));
        graphs.put("PT-A", graph(node("a-cut", "WP-CUT", 1, null, List.of("raw"), List.of("fg"))));
        workflowPlan(F, "PP-MISSING", "PLAN-MISSING", "PT-MISSING", "10");
        workflowPlan(F, "PP-A", "PLAN-A", "PT-A", "10");

        SchedulingPlanDTO dto = generator.generate(F, request(List.of("PP-MISSING", "PP-A")), 7L);

        System.out.println("APS_WARNINGS " + dto.getWarnings());
        assertThat(dto.getLineSchedules()).extracting(LineScheduleDTO::getProductionPlanId)
                .containsExactly("PP-A");
        assertThat(dto.getWarnings()).anySatisfy(w -> assertThat(w)
                .contains("PLAN-MISSING").contains("缺预计工时").contains("蒸煮").contains("工序管理"));
        // 不占工位: PP-A 的分切不被一个不会落库的排程挤后
        assertThat(dto.getLineSchedules().get(0).getPlannedStartTime()).isEqualTo(DAY.atTime(8, 0));
    }

    @Test
    void whenNothingCanBeScheduledNothingIsPersistedAndTheErrorNamesTheStep() {
        graphs.put("PT-MISSING", graph(
                node("m-cook", "WP-COOK", 1, null, List.of("raw"), List.of("fg"))));
        workflowPlan(F, "PP-MISSING", "PLAN-MISSING", "PT-MISSING", "10");
        long before = schedulingPlanRepository.count();

        assertThatThrownBy(() -> generator.generate(F, request(List.of("PP-MISSING")), 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("蒸煮")
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(422));
        assertThat(schedulingPlanRepository.count()).isEqualTo(before);
        assertThat(lineScheduleRepository.count()).isZero();
    }

    @Test
    void planWithoutWorkflowIsListedAsNotSchedulable() {
        graphs.put("PT-A", graph(node("a-cut", "WP-CUT", 1, null, List.of("raw"), List.of("fg"))));
        workflowPlan(F, "PP-A", "PLAN-A", "PT-A", "10");
        // 旧式计划: 没有工艺选择 (workflowSelectionMode 为空)
        planRepository.saveAndFlush(basePlan(F, "PP-LEGACY", "PLAN-LEGACY", "PT-L", "10"));

        SchedulingPlanDTO dto = generator.generate(F, request(List.of("PP-LEGACY", "PP-A")), 7L);

        assertThat(dto.getLineSchedules()).extracting(LineScheduleDTO::getProductionPlanId).containsExactly("PP-A");
        assertThat(dto.getWarnings()).anySatisfy(w -> assertThat(w).contains("PLAN-LEGACY").contains("没有工艺图"));
    }

    // ==================== 已开工计划: 用实际任务, 回写任务表 ====================

    @Test
    void startedPlanUsesItsRealTasksSkipsFinishedOnesAndWritesPlannedTimesBack() {
        ProductionPlan plan = basePlan(F, "PP-S", "PLAN-S", "PT-S", "8");
        planRepository.saveAndFlush(plan);
        ProductionBatch batch = batch(F, "PP-S", "B-S-1", ProductionBatchStatus.IN_PROGRESS);
        WorkProcessTask done = task(F, batch.getId(), "WP-CUT", 1, null, WorkProcessTask.Status.COMPLETED);
        WorkProcessTask mix = task(F, batch.getId(), "WP-MIX", 2, null, WorkProcessTask.Status.PENDING); // 回落 45
        WorkProcessTask pack = task(F, batch.getId(), "WP-PACK", 3, 20, WorkProcessTask.Status.PENDING);

        SchedulingPlanDTO dto = generator.generate(F, request(List.of("PP-S")), 7L);

        LineScheduleDTO ls = dto.getLineSchedules().get(0);
        assertThat(ls.getBatchId()).isEqualTo(batch.getId());
        assertThat(ls.getPlannedStartTime()).isEqualTo(DAY.atTime(8, 0));
        assertThat(ls.getPlannedEndTime()).isEqualTo(DAY.atTime(9, 5));
        em.flush();
        em.clear();
        WorkProcessTask mixAfter = taskRepository.findById(mix.getId()).orElseThrow();
        WorkProcessTask packAfter = taskRepository.findById(pack.getId()).orElseThrow();
        WorkProcessTask doneAfter = taskRepository.findById(done.getId()).orElseThrow();
        assertThat(mixAfter.getPlannedStartAt()).isEqualTo(DAY.atTime(8, 0)); // 已完工的分切不占时间
        assertThat(mixAfter.getPlannedEndAt()).isEqualTo(DAY.atTime(8, 45));
        assertThat(packAfter.getPlannedStartAt()).isEqualTo(DAY.atTime(8, 45));
        assertThat(packAfter.getPlannedEndAt()).isEqualTo(DAY.atTime(9, 5));
        assertThat(doneAfter.getPlannedStartAt()).isNull();
    }

    @Test
    void batchIdsAreMappedToTheirPlan() {
        graphs.put("PT-A", graph(node("a-cut", "WP-CUT", 1, null, List.of("raw"), List.of("fg"))));
        workflowPlan(F, "PP-A", "PLAN-A", "PT-A", "10");
        ProductionBatch b = batch(F, "PP-A", "B-A-1", ProductionBatchStatus.PLANNED); // 没有任务 → 按图排
        GenerateScheduleRequest req = new GenerateScheduleRequest();
        req.setPlanDate(DAY);
        req.setBatchIds(List.of(b.getId()));

        SchedulingPlanDTO dto = generator.generate(F, req, 7L);

        assertThat(dto.getLineSchedules()).extracting(LineScheduleDTO::getProductionPlanId).containsExactly("PP-A");
    }

    // ==================== 租户 / 入参 ====================

    @Test
    void planFromAnotherFactoryIsRejectedAndNothingIsWritten() {
        graphs.put("PT-A", graph(node("a-cut", "WP-CUT", 1, 30, List.of("raw"), List.of("fg"))));
        workflowPlan(OTHER, "PP-FOREIGN", "PLAN-FOREIGN", "PT-A", "10");
        // 阳性对照: 同一张图在本厂的计划能排
        workflowPlan(F, "PP-A", "PLAN-A", "PT-A", "10");

        assertThatThrownBy(() -> generator.generate(F, request(List.of("PP-A", "PP-FOREIGN")), 7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(404));
        assertThat(schedulingPlanRepository.count()).isZero();

        assertThat(generator.generate(F, request(List.of("PP-A")), 7L).getLineSchedules()).hasSize(1);
    }

    @Test
    void multipleActiveLinesWithoutLineIdIsA400AndChoosingOneWorks() {
        graphs.put("PT-A", graph(node("a-cut", "WP-CUT", 1, 30, List.of("raw"), List.of("fg"))));
        workflowPlan(F, "PP-A", "PLAN-A", "PT-A", "10");
        ProductionLine second = line(F, "二号线");

        assertThatThrownBy(() -> generator.generate(F, request(List.of("PP-A")), 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("2 条产线")
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));

        GenerateScheduleRequest req = request(List.of("PP-A"));
        req.setProductionLineId(second.getId());
        assertThat(generator.generate(F, req, 7L).getLineSchedules().get(0).getProductionLineId())
                .isEqualTo(second.getId());
    }

    @Test
    void noActiveLineIsA409AndForeignLineIdIsRejected() {
        graphs.put("PT-A", graph(node("a-cut", "WP-CUT", 1, 30, List.of("raw"), List.of("fg"))));
        workflowPlan(F, "PP-A", "PLAN-A", "PT-A", "10");
        ProductionLine foreign = line(OTHER, "别厂产线");
        GenerateScheduleRequest req = request(List.of("PP-A"));
        req.setProductionLineId(foreign.getId());
        assertThatThrownBy(() -> generator.generate(F, req, 7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(404));

        ProductionLine only = lineRepository.findById(lineId).orElseThrow();
        only.setStatus(ProductionLine.LineStatus.inactive);
        lineRepository.saveAndFlush(only);
        assertThatThrownBy(() -> generator.generate(F, request(List.of("PP-A")), 7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409));
    }

    @Test
    void emptySelectionIsA400() {
        GenerateScheduleRequest req = new GenerateScheduleRequest();
        req.setPlanDate(DAY);
        assertThatThrownBy(() -> generator.generate(F, req, 7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
    }

    @Test
    void sameDayExistingSchedulingPlanIsA409() {
        graphs.put("PT-A", graph(node("a-cut", "WP-CUT", 1, 30, List.of("raw"), List.of("fg"))));
        workflowPlan(F, "PP-A", "PLAN-A", "PT-A", "10");
        generator.generate(F, request(List.of("PP-A")), 7L);

        assertThatThrownBy(() -> generator.generate(F, request(List.of("PP-A")), 7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409));
    }

    // ==================== fixtures ====================

    private static GenerateScheduleRequest request(List<String> planIds) {
        GenerateScheduleRequest req = new GenerateScheduleRequest();
        req.setPlanDate(DAY);
        req.setPlanIds(new ArrayList<>(planIds));
        return req;
    }

    private record Node(String nodeId, String wp, int order, Integer minutes, List<String> in, List<String> out) {
    }

    private static Node node(String id, String wp, int order, Integer minutes, List<String> in, List<String> out) {
        return new Node(id, wp, order, minutes, in, out);
    }

    private static CompiledProductProcessWorkflow graph(Node... nodes) {
        List<CompiledProductProcessWorkflow.CompiledTask> tasks = new ArrayList<>();
        List<CompiledProductProcessWorkflow.CompiledPort> ports = new ArrayList<>();
        for (Node n : nodes) {
            tasks.add(new CompiledProductProcessWorkflow.CompiledTask(n.nodeId(), n.wp(), n.order(), "kg",
                    n.minutes(), true));
            int ordinal = 0;
            for (String m : n.in()) {
                ports.add(new CompiledProductProcessWorkflow.CompiledPort(n.nodeId(), n.nodeId() + "-in-" + m,
                        "INPUT", ordinal++, m, "RAW_MATERIAL", "SKU-" + m, "kg", false, null, null));
            }
            for (String m : n.out()) {
                ports.add(new CompiledProductProcessWorkflow.CompiledPort(n.nodeId(), n.nodeId() + "-out-" + m,
                        "OUTPUT", ordinal++, m, "SEMI_FINISHED", "SKU-" + m, "kg", false, null, null));
            }
        }
        return new CompiledProductProcessWorkflow("[]", "[]", tasks, ports);
    }

    private void process(String factoryId, String id, String name, Integer minutes) {
        WorkProcess wp = new WorkProcess();
        wp.setId(id);
        wp.setFactoryId(factoryId);
        wp.setProcessName(name);
        wp.setUnit("kg");
        wp.setEstimatedMinutes(minutes);
        wp.setDefaultOutputMaterialKind(WorkProcessOutputMaterialKind.SEMI_FINISHED);
        workProcessRepository.saveAndFlush(wp);
    }

    private ProductionLine line(String factoryId, String name) {
        ProductionLine line = new ProductionLine();
        line.setFactoryId(factoryId);
        line.setName(name);
        line.setStatus(ProductionLine.LineStatus.active);
        line.setHourlyCapacity(new BigDecimal("999")); // 不参与排时间
        return lineRepository.saveAndFlush(line);
    }

    private static ProductionPlan basePlan(String factoryId, String id, String number, String productTypeId,
                                           String quantity) {
        ProductionPlan p = new ProductionPlan();
        p.setId(id);
        p.setFactoryId(factoryId);
        p.setPlanNumber(number);
        p.setProductTypeId(productTypeId);
        p.setPlannedQuantity(new BigDecimal(quantity));
        p.setPlannedUnit("kg");
        p.setStatus(ProductionPlanStatus.PENDING);
        p.setSourceType(PlanSourceType.MANUAL);
        p.setCreatedBy(7L);
        p.setIsLocked(false);
        p.setSkipProcessReporting(false);
        return p;
    }

    private ProductionPlan workflowPlan(String factoryId, String id, String number, String productTypeId,
                                        String quantity) {
        ProductProcessWorkflowRevision rev = new ProductProcessWorkflowRevision();
        rev.setFactoryId(factoryId);
        rev.setProductTypeId(productTypeId);
        rev.setWorkflowId(Math.abs((long) (factoryId + id).hashCode()));
        rev.setDefinitionVersion(1);
        rev.setRevisionNumber(1);
        rev.setRevisionHash("hash-" + id);
        rev.setStatus(ProductProcessWorkflowRevision.Status.PUBLISHED);
        rev = revisionRepository.saveAndFlush(rev);
        ProductionPlan p = basePlan(factoryId, id, number, productTypeId, quantity);
        p.setWorkflowSelectionMode(ProductionBatch.WorkflowSelectionMode.WORKFLOW);
        p.setSelectedWorkflowId(rev.getWorkflowId());
        p.setSelectedWorkflowVersion(1);
        p.setSelectedWorkflowRevisionId(rev.getId());
        p.setSelectedWorkflowRevisionHash(rev.getRevisionHash());
        return planRepository.saveAndFlush(p);
    }

    private ProductionBatch batch(String factoryId, String planId, String number, ProductionBatchStatus status) {
        ProductionBatch b = new ProductionBatch();
        b.setFactoryId(factoryId);
        b.setBatchNumber(number);
        b.setProductTypeId("PT-S");
        b.setQuantity(new BigDecimal("8"));
        b.setUnit("kg");
        b.setStatus(status);
        b.setProductionPlanId(planId);
        return batchRepository.saveAndFlush(b);
    }

    private WorkProcessTask task(String factoryId, Long batchId, String wp, int order, Integer minutes,
                                 WorkProcessTask.Status status) {
        return taskRepository.saveAndFlush(WorkProcessTask.builder()
                .factoryId(factoryId)
                .productionBatchId(batchId)
                .workProcessId(wp)
                .productTypeId("PT-S")
                .processOrder(order)
                .status(status)
                .estimatedMinutes(minutes)
                .build());
    }
}
