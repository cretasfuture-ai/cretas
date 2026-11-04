package com.cretas.aims.service.yield;

import com.cretas.aims.controller.YieldReportController;
import com.cretas.aims.dto.yield.StepYieldDTO;
import com.cretas.aims.dto.yield.YieldReportRequest;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.ProductionBatchRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 留单产出 (markComplete=false) —— 真 Spring + H2, 走 App 的真实入口:
 * 报工 ({@link YieldReportController#submitReport}) → 阶段读数 ({@link YieldReportController#getYield})。
 *
 * <p>真机实测 (2026-11-04, F006 批次 11023): 留单后 App 显示「本道已完成」、任务从「我的工序」消失,
 * 而任务其实还是 IN_PROGRESS。根因是阶段只看「有没有产出」, 不看这次产出是不是完工。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("留单产出: 阶段保持生产中, 可续报, 全部留单后可不填新增直接完工")
class YieldPartialOutputPhaseContractTest {

    private static final String F = "YPO-FACTORY";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private YieldReportController yieldReportController;
    @Autowired private WorkProcessRepository processRepo;
    @Autowired private WorkProcessTaskRepository taskRepo;
    @Autowired private ProductionBatchRepository batchRepo;

    private Long batchId;
    private WorkProcessTask task;

    @BeforeEach
    void setUp() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        WorkProcess wp = new WorkProcess();
        wp.setId("YPO-WP-" + UUID.randomUUID().toString().substring(0, 8));
        wp.setFactoryId(F);
        wp.setProcessName("卤制");
        wp.setProcessCategory("加工");
        wp.setUnit("kg");
        wp.setDefaultOutputMaterialKind(com.cretas.aims.entity.enums.WorkProcessOutputMaterialKind.SEMI_FINISHED);
        processRepo.saveAndFlush(wp);

        batchId = batchRepo.saveAndFlush(ProductionBatch.builder()
                .factoryId(F).batchNumber("YPO-" + UUID.randomUUID().toString().substring(0, 8))
                .productTypeId("YPO-PT").quantity(new BigDecimal("100")).unit("kg")
                .status(ProductionBatchStatus.IN_PROGRESS).build()).getId();

        WorkProcessTask t = new WorkProcessTask();
        t.setFactoryId(F);
        t.setProductionBatchId(batchId);
        t.setProductWorkProcessId(1L);
        t.setWorkProcessId(wp.getId());
        t.setProductTypeId("YPO-PT");
        t.setProcessOrder(1);
        t.setStatus(WorkProcessTask.Status.IN_PROGRESS);
        task = taskRepo.saveAndFlush(t);

        YieldReportRequest input = new YieldReportRequest();
        input.setWorkProcessTaskId(task.getId());
        input.setReportKind("INPUT");
        input.setInputQuantity(new BigDecimal("100"));
        input.setInputUnit("kg");
        submit(input);
    }

    @Test
    @DisplayName("留单 → 生产中; 再留单累计; 不填新增完工 → 已完成, 累计不变")
    void partialOutputsStayInProductionUntilMarkedComplete() {
        output("30", false);
        assertThat(step().getPhase()).isEqualTo("IN_PRODUCTION");
        assertThat(taskStatus()).isEqualTo(WorkProcessTask.Status.IN_PROGRESS);

        output("50", false);
        assertThat(step().getPhase()).isEqualTo("IN_PRODUCTION");
        assertThat(step().getTotalOutput()).isEqualByComparingTo("80");

        output("0", true);
        assertThat(step().getPhase()).isEqualTo("COMPLETED");
        assertThat(step().getTotalOutput()).isEqualByComparingTo("80");
        assertThat(taskStatus()).isEqualTo(WorkProcessTask.Status.COMPLETED);
    }

    @Test
    @DisplayName("阴性对照: 一次完工照旧判已完成")
    void completingOutputStillCompletes() {
        output("80", true);
        assertThat(step().getPhase()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("阴性对照: 旧版 App 不传 markComplete, 与提交侧同口径算完工")
    void outputWithoutMarkCompleteStillCompletes() {
        output("80", null);
        assertThat(step().getPhase()).isEqualTo("COMPLETED");
        assertThat(taskStatus()).isEqualTo(WorkProcessTask.Status.COMPLETED);
    }

    // ---------- helpers ----------

    private void output(String quantity, Boolean markComplete) {
        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(task.getId());
        req.setReportKind("OUTPUT");
        req.setOutputQuantity(new BigDecimal(quantity));
        req.setOutputUnit("kg");
        req.setMarkComplete(markComplete);
        submit(req);
    }

    private void submit(YieldReportRequest req) {
        yieldReportController.submitReport(F, batchId, req, 7L, "ypo_operator");
    }

    private StepYieldDTO step() {
        return yieldReportController.getYield(F, batchId).getData().getSteps().stream()
                .filter(s -> task.getId().equals(s.getWorkProcessTaskId()))
                .findFirst().orElseThrow();
    }

    private WorkProcessTask.Status taskStatus() {
        return taskRepo.findByFactoryIdAndId(F, task.getId()).orElseThrow().getStatus();
    }
}
