package com.cretas.aims.service.yield;

import com.cretas.aims.controller.WorkProcessTaskController;
import com.cretas.aims.controller.YieldReportController;
import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.dto.yield.YieldReportRequest;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.WorkProcessRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * App 产出报工录工艺参数 —— 真 Spring + H2, 走 App 的真实入口:
 * 拉工序任务 ({@link WorkProcessTaskController#listByBatch}) 带出参数定义 →
 * 产出报工 ({@link YieldReportController#submitReport}) 带 customFields →
 * 报工 custom_fields 里有 clerkCustomFields (原值) + paramChecks (当时标准的快照), 与 web 工序表同口径。
 *
 * <p>阴性对照: 没带参数的报工不出现这两个键; 领料报工带参数被拒; 未配置的键被拒;
 * 工序没配任何参数时带参数被拒。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("App 产出报工录工艺参数: 任务带出定义 → 报工落值与快照 → 越界照收不拦")
class YieldReportProcessParamsContractTest {

    private static final String F = "YPP-FACTORY";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private YieldReportController yieldReportController;
    @Autowired private WorkProcessTaskController taskController;
    @Autowired private WorkProcessRepository processRepo;
    @Autowired private WorkProcessTaskRepository taskRepo;
    @Autowired private ProductionBatchRepository batchRepo;
    @Autowired private ProductionReportRepository reportRepo;

    private Long batchId;
    private WorkProcessTask fryTask;
    private WorkProcessTask plainTask;

    @BeforeEach
    void setUp() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        WorkProcess fry = process("炒制", List.of(
                field("oilTemp", "油温", "℃", 155, 150, 160),
                field("fryMinutes", "炒制时间", "min", null, 20, 30)));
        WorkProcess plain = process("包装", null);
        ProductionBatch batch = batchRepo.saveAndFlush(ProductionBatch.builder()
                .factoryId(F).batchNumber("YPP-" + UUID.randomUUID().toString().substring(0, 8))
                .productTypeId("YPP-PT").quantity(new BigDecimal("100")).unit("kg")
                .status(ProductionBatchStatus.IN_PROGRESS).build());
        batchId = batch.getId();
        fryTask = task(fry.getId(), 1);
        plainTask = task(plain.getId(), 2);
    }

    @Test
    @DisplayName("任务带出参数定义; 产出报工落原值 + 快照 (越界照收, 判 HIGH; 只配上下限的判 OK)")
    void outputReportSnapshotsProcessParams() {
        List<WorkProcessTaskDTO> tasks = taskController.listByBatch(F, batchId, null).getData();
        WorkProcessTaskDTO fryDto = tasks.stream().filter(t -> t.getId().equals(fryTask.getId())).findFirst().orElseThrow();
        assertThat(fryDto.getCustomFieldSchema()).extracting(m -> m.get("key")).containsExactly("oilTemp", "fryMinutes");
        assertThat(fryDto.getCustomFieldSchema().get(0)).containsEntry("unit", "℃").containsEntry("max", 160);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("oilTemp", 165);
        params.put("fryMinutes", "25");
        submit(fryTask, "OUTPUT", params);

        ProductionReport report = onlyReport(fryTask);
        assertThat(report.getCustomFields()).containsEntry("clerkCustomFields", params);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checks = (List<Map<String, Object>>) report.getCustomFields().get("paramChecks");
        assertThat(checks).hasSize(2);
        Map<String, Object> oil = checks.get(0);
        assertThat(oil).containsEntry("key", "oilTemp").containsEntry("label", "油温").containsEntry("unit", "℃")
                .containsEntry("status", "HIGH");
        assertThat(new BigDecimal(oil.get("value").toString())).isEqualByComparingTo("165");
        assertThat(new BigDecimal(oil.get("max").toString())).isEqualByComparingTo("160");
        assertThat(new BigDecimal(oil.get("standardValue").toString())).isEqualByComparingTo("155");
        assertThat(checks.get(1)).containsEntry("key", "fryMinutes").containsEntry("status", "OK");
    }

    @Test
    @DisplayName("阴性对照: 不带参数不出现两个键; 领料报工带参数 / 未配置的键 / 工序没配参数 都被拒且一行不写")
    void rejectsParamsWhereTheyDoNotBelong() {
        submit(fryTask, "OUTPUT", null);
        ProductionReport plainReport = onlyReport(fryTask);
        assertThat(plainReport.getCustomFields()).doesNotContainKeys("clerkCustomFields", "paramChecks");

        assertThatThrownBy(() -> submit(fryTask, "INPUT", Map.of("oilTemp", 155)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("只在产出报工时填写");
        assertThatThrownBy(() -> submit(fryTask, "OUTPUT", Map.of("saltPct", 2)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("未配置自定义字段「saltPct」");
        assertThatThrownBy(() -> submit(plainTask, "OUTPUT", Map.of("oilTemp", 155)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("没有配置工艺参数");

        assertThat(reportRepo.findYieldReportsByTask(F, fryTask.getId())).hasSize(1);
        assertThat(reportRepo.findYieldReportsByTask(F, plainTask.getId())).isEmpty();
    }

    // ---------- helpers ----------

    private void submit(WorkProcessTask task, String kind, Map<String, Object> params) {
        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(task.getId());
        req.setReportKind(kind);
        if ("INPUT".equals(kind)) {
            req.setInputQuantity(new BigDecimal("100"));
            req.setInputUnit("kg");
        } else {
            req.setOutputQuantity(new BigDecimal("80"));
            req.setOutputUnit("kg");
        }
        req.setCustomFields(params);
        yieldReportController.submitReport(F, batchId, req, 7L, "ypp_operator");
    }

    private ProductionReport onlyReport(WorkProcessTask task) {
        List<ProductionReport> reports = reportRepo.findYieldReportsByTask(F, task.getId());
        assertThat(reports).hasSize(1);
        return reports.get(0);
    }

    private WorkProcess process(String name, List<Map<String, Object>> schema) {
        WorkProcess wp = new WorkProcess();
        wp.setId("YPP-WP-" + UUID.randomUUID().toString().substring(0, 8));
        wp.setFactoryId(F);
        wp.setProcessName(name);
        wp.setProcessCategory("加工");
        wp.setUnit("kg");
        wp.setDefaultOutputMaterialKind(com.cretas.aims.entity.enums.WorkProcessOutputMaterialKind.SEMI_FINISHED);
        wp.setCustomFieldSchema(schema);
        return processRepo.saveAndFlush(wp);
    }

    private static Map<String, Object> field(String key, String label, String unit,
                                             Integer standard, Integer min, Integer max) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("key", key);
        f.put("label", label);
        f.put("type", "number");
        f.put("enabled", true);
        f.put("unit", unit);
        if (standard != null) f.put("standardValue", standard);
        f.put("min", min);
        f.put("max", max);
        return f;
    }

    private WorkProcessTask task(String workProcessId, int order) {
        WorkProcessTask t = new WorkProcessTask();
        t.setFactoryId(F);
        t.setProductionBatchId(batchId);
        t.setProductWorkProcessId((long) order);
        t.setWorkProcessId(workProcessId);
        t.setProductTypeId("YPP-PT");
        t.setProcessOrder(order);
        t.setStatus(WorkProcessTask.Status.IN_PROGRESS);
        return taskRepo.saveAndFlush(t);
    }
}
