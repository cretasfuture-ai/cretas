package com.cretas.aims.service.yield;

import com.cretas.aims.controller.YieldReportController;
import com.cretas.aims.dto.yield.YieldReportRequest;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.workflow.ProductionWorkflowInstance;
import com.cretas.aims.entity.workflow.WorkflowTaskPort;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * App 产出报工真实入口({@link YieldReportController#submitReport}): 两个产品共用同一道工序「炒制」,
 * 各自的画布(运行时实例 = 计划锁定的工艺版本快照)里配了不同的油温标准, 各按各的判;
 * 没带画布参数的产品走工序 schema 的旧标准; 画布新参数补出的可选字段(没有标准值)可以不填、不会被判超标。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("App 产出报工: 工艺参数按本计划锁定的产品工艺版本判")
class YieldReportProductStandardsContractTest {

    private static final String F = "YPS-FACTORY";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private YieldReportController yieldReportController;
    @Autowired private WorkProcessRepository processRepo;
    @Autowired private WorkProcessTaskRepository taskRepo;
    @Autowired private ProductionBatchRepository batchRepo;
    @Autowired private ProductionReportRepository reportRepo;
    @Autowired private ProductionWorkflowInstanceRepository instanceRepo;
    @Autowired private WorkflowTaskPortRepository portRepo;

    private WorkProcess fry;
    private WorkProcessTask taskA;
    private WorkProcessTask taskB;
    private WorkProcessTask taskPlain;

    @BeforeEach
    void setUp() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        Map<String, Object> oil = new LinkedHashMap<>();
        oil.put("key", "oilTemp");
        oil.put("label", "油温");
        oil.put("type", "number");
        oil.put("enabled", true);
        oil.put("unit", "℃");
        oil.put("min", 100);   // 工序上原有的宽松旧标准
        oil.put("max", 200);
        Map<String, Object> pot = new LinkedHashMap<>();   // 画布新参数补出的可选字段: 只有名字和单位
        pot.put("key", "param_pot");
        pot.put("label", "锅压");
        pot.put("type", "number");
        pot.put("enabled", true);
        pot.put("unit", "kPa");
        fry = process(List.of(oil, pot));
        taskA = taskOn("PT-A", "[" + node("process:1", fry.getId(), "油温", 150, 160) + "]");
        taskB = taskOn("PT-B", "[" + node("process:1", fry.getId(), "油温", 100, 120) + "]");
        taskPlain = taskOn("PT-C", "[{\"id\":\"process:1\",\"kind\":\"PROCESS\",\"data\":{\"workProcessId\":\""
                + fry.getId() + "\"}}]");
    }

    @Test
    @DisplayName("同一道工序、同样 155℃: A(150-160)合格, B(100-120)偏高, 画布没配的产品按旧标准(100-200)合格")
    void eachProductIsJudgedByItsOwnCanvasStandard() {
        assertThat(statusOf(taskA, "oilTemp", 155)).isEqualTo("OK");
        assertThat(statusOf(taskB, "oilTemp", 155)).isEqualTo("HIGH");
        assertThat(statusOf(taskPlain, "oilTemp", 155)).isEqualTo("OK");
    }

    @Test
    @DisplayName("165℃: A 偏高、B 偏高; 没配画布的产品仍按旧标准合格 (旧行为)")
    void legacyBehaviourIsKeptWhenTheCanvasHasNoParams() {
        assertThat(statusOf(taskA, "oilTemp", 165)).isEqualTo("HIGH");
        assertThat(statusOf(taskB, "oilTemp", 165)).isEqualTo("HIGH");
        assertThat(statusOf(taskPlain, "oilTemp", 165)).isEqualTo("OK");
    }

    @Test
    @DisplayName("只填了没有标准的可选字段(锅压)也能报工, 不判超标; 不带参数的报工仍然正常")
    void anOptionalFieldWithoutAnyStandardIsAcceptedAndNotJudged() {
        submit(taskPlain, Map.of("param_pot", 90));
        ProductionReport r = onlyReport(taskPlain);
        assertThat(r.getCustomFields()).containsKey("clerkCustomFields");
        assertThat(r.getCustomFields()).doesNotContainKey("paramChecks");
    }

    @Test
    @DisplayName("租户隔离: 任务指向的实例若不属于本工厂, 它的画布标准不得被采用 (按旧标准判)")
    void anInstanceOfAnotherFactoryIsNeverUsed() {
        // 别的工厂的实例里画布标准是 100-120: 若被误采用, 165℃ 会判 HIGH; 本工厂旧标准 100-200 下是 OK
        WorkProcessTask foreign = taskOn("PT-X", "[" + node("process:1", fry.getId(), "油温", 100, 120) + "]",
                "YPS-OTHER-FACTORY");
        assertThat(statusOf(foreign, "oilTemp", 165)).isEqualTo("OK");
        // 阳性对照: 同样的画布放在本工厂的实例里就会采用 → HIGH
        assertThat(statusOf(taskB, "oilTemp", 165)).isEqualTo("HIGH");
    }

    // ---------- helpers ----------

    @SuppressWarnings("unchecked")
    private String statusOf(WorkProcessTask task, String key, int value) {
        submit(task, Map.of(key, value));
        List<Map<String, Object>> checks = (List<Map<String, Object>>) onlyReport(task).getCustomFields().get("paramChecks");
        assertThat(checks).as("判定一定发生 (阳性读数)").hasSize(1);
        return String.valueOf(checks.get(0).get("status"));
    }

    private void submit(WorkProcessTask task, Map<String, Object> params) {
        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(task.getId());
        req.setReportKind("OUTPUT");
        req.setOutputQuantity(new BigDecimal("80"));
        req.setOutputUnit("kg");
        req.setCustomFields(params);
        yieldReportController.submitReport(F, task.getProductionBatchId(), req, 7L, "yps_operator");
    }

    private ProductionReport onlyReport(WorkProcessTask task) {
        List<ProductionReport> reports = reportRepo.findYieldReportsByTask(F, task.getId());
        assertThat(reports).hasSize(1);
        return reports.get(0);
    }

    private static String node(String id, String workProcessId, String label, int min, int max) {
        return "{\"id\":\"" + id + "\",\"kind\":\"PROCESS\",\"data\":{\"workProcessId\":\"" + workProcessId
                + "\",\"processParams\":[{\"label\":\"" + label + "\",\"unit\":\"℃\",\"min\":" + min
                + ",\"max\":" + max + "}]}}";
    }

    private WorkProcess process(List<Map<String, Object>> schema) {
        WorkProcess wp = new WorkProcess();
        wp.setId("YPS-WP-" + UUID.randomUUID().toString().substring(0, 8));
        wp.setFactoryId(F);
        wp.setProcessName("炒制");
        wp.setProcessCategory("加工");
        wp.setUnit("kg");
        wp.setDefaultOutputMaterialKind(com.cretas.aims.entity.enums.WorkProcessOutputMaterialKind.SEMI_FINISHED);
        wp.setCustomFieldSchema(schema);
        return processRepo.saveAndFlush(wp);
    }

    /** 一个产品 = 一个批次 + 一个运行时实例(锁定的画布快照) + 一个指向该实例节点的工序任务。 */
    private WorkProcessTask taskOn(String productTypeId, String nodesJson) {
        return taskOn(productTypeId, nodesJson, F);
    }

    private WorkProcessTask taskOn(String productTypeId, String nodesJson, String instanceFactoryId) {
        ProductionBatch batch = batchRepo.saveAndFlush(ProductionBatch.builder()
                .factoryId(F).batchNumber("YPS-" + UUID.randomUUID().toString().substring(0, 8))
                .productTypeId(productTypeId).quantity(new BigDecimal("100")).unit("kg")
                .status(ProductionBatchStatus.IN_PROGRESS).build());
        ProductionWorkflowInstance instance = instanceRepo.saveAndFlush(ProductionWorkflowInstance.create(
                instanceFactoryId, batch.getId(), productTypeId, 1L, 1, nodesJson, "[]", LocalDateTime.now()));
        WorkProcessTask t = new WorkProcessTask();
        t.setFactoryId(F);
        t.setProductionBatchId(batch.getId());
        t.setProductWorkProcessId(1L);
        t.setWorkProcessId(fry.getId());
        t.setProductTypeId(productTypeId);
        t.setProcessOrder(1);
        t.setWorkflowInstanceId(instance.getId());
        t.setWorkflowNodeId("process:1");
        t.setStatus(WorkProcessTask.Status.IN_PROGRESS);
        WorkProcessTask saved = taskRepo.saveAndFlush(t);
        // 工作流任务报工要求有冻结的端口快照: 一个产出端口(kg)
        WorkflowTaskPort out = new WorkflowTaskPort();
        out.setFactoryId(F);
        out.setWorkflowInstanceId(instance.getId());
        out.setTaskId(saved.getId());
        out.setWorkflowPortId("out-" + saved.getId());
        out.setDirection(WorkflowTaskPort.Direction.OUTPUT);
        out.setOrdinal(1);
        out.setMaterialNodeId("material-out-" + saved.getId());
        out.setMaterialKind("SEMI_FINISHED");
        out.setSkuId("SKU-OUT");
        out.setUnit("kg");
        out.setUnitCode("KG");
        out.setRequired(true);
        portRepo.saveAndFlush(out);
        return saved;
    }
}
