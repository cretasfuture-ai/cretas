package com.cretas.aims.gate;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO;
import com.cretas.aims.dto.yield.YieldReportRequest;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.quality.firstarticle.FirstArticleInspection;
import com.cretas.aims.entity.quality.firstarticle.FirstArticlePolicy.Mode;
import com.cretas.aims.entity.quality.firstarticle.FirstArticleStandard;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.quality.firstarticle.FirstArticleInspectionRepository;
import com.cretas.aims.repository.quality.firstarticle.FirstArticlePolicyRepository;
import com.cretas.aims.repository.quality.firstarticle.FirstArticleStandardRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl;
import com.cretas.aims.service.quality.firstarticle.FirstArticleGate;
import com.cretas.aims.service.workflow.WorkflowClerkSheetService;
import com.cretas.aims.service.yield.impl.YieldReportServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 工序首检闸接在另外两个能把任务记成完成的入口上 (真实入口方法, 只桩仓储):
 * <ul>
 *   <li>App 产量报工 {@code YieldReportServiceImpl#submitReport} —— 产出报工且要完工时;</li>
 *   <li>Web 逐道录入 {@code ProcessSheetServiceImpl#saveRow} —— 有正数产出的正式行。</li>
 * </ul>
 * 拦截: 首检没合格 → 409 FIRST_ARTICLE_NOT_PASSED, 不落报工单 / 工序行。
 * 开关关: 与<b>没装首检闸</b>的同一入口结果逐字相同 (同一异常 / 同一结果), 且不查首检标准与首检单。
 */
@DisplayName("工序首检: App 报工 / Web 逐道录入两个完工入口接上了闸")
class FirstArticleEntryWiringGateTest {

    private static final String F = "F006";

    private static WorkProcessTask task() {
        WorkProcessTask t = new WorkProcessTask();
        t.setId(3L);
        t.setFactoryId(F);
        t.setProductionBatchId(500L);
        t.setWorkflowInstanceId(900L);
        t.setWorkflowNodeId("node-braise");
        t.setProcessOrder(3);
        t.setWorkProcessId("WP-BRAISE");
        t.setProductTypeId("P");
        t.setStatus(WorkProcessTask.Status.IN_PROGRESS);
        return t;
    }

    /** 首检闸的三个仓储: 这道工序有启用标准、还没送检。 */
    private record GateRepos(FirstArticlePolicyRepository policies, FirstArticleStandardRepository standards,
                             FirstArticleInspectionRepository inspections) {
        static GateRepos of(Mode mode) {
            GateRepos r = new GateRepos(mock(FirstArticlePolicyRepository.class), mock(FirstArticleStandardRepository.class),
                    mock(FirstArticleInspectionRepository.class));
            when(r.policies.findModeByFactoryId(F)).thenReturn(mode);
            FirstArticleStandard std = new FirstArticleStandard();
            std.setId(5L);
            std.setName("卤制首检");
            when(r.standards.findActive(F, "WP-BRAISE")).thenReturn(Optional.of(std));
            when(r.inspections.findEffectiveForTasks(anyString(), anyCollection())).thenReturn(List.<FirstArticleInspection>of());
            return r;
        }

        /** 开关读取抛错 (连接抖动 / 锁超时 / 表权限): 闸必须按关处理。 */
        static GateRepos failingRead() {
            GateRepos r = of(Mode.BLOCK);
            when(r.policies.findModeByFactoryId(F)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("connection reset"));
            return r;
        }

        FirstArticleGate gate() {
            return new FirstArticleGate(policies, standards, inspections);
        }
    }

    /** 结果的文字形态: 成功 = "OK", 失败 = 异常类 + 错误码 + 文案 —— 用来比「装闸 (关)」与「没装闸」逐字相同。 */
    private static String outcome(Runnable call) {
        try {
            call.run();
            return "OK";
        } catch (BusinessException e) {
            return e.getClass().getSimpleName() + "|" + e.getCode() + "|" + e.getErrorCode() + "|" + e.getMessage();
        } catch (RuntimeException e) {
            return e.getClass().getName() + "|" + e.getMessage();
        }
    }

    // ==================== App 产量报工 ====================

    private record AppFixture(YieldReportServiceImpl svc, ProductionReportRepository reports) {
    }

    private static AppFixture app(FirstArticleGate gate) {
        WorkProcessTaskRepository taskRepo = mock(WorkProcessTaskRepository.class);
        ProductionReportRepository reportRepo = mock(ProductionReportRepository.class);
        when(taskRepo.findByFactoryIdAndId(F, 3L)).thenReturn(Optional.of(task()));
        YieldReportServiceImpl svc = WorkflowStepUnlockWiringContractTest.construct(YieldReportServiceImpl.class, Map.of(
                WorkProcessTaskRepository.class, taskRepo,
                ProductionReportRepository.class, reportRepo));
        if (gate != null) {
            ReflectionTestUtils.setField(svc, "firstArticleGate", gate);
        }
        return new AppFixture(svc, reportRepo);
    }

    private static YieldReportRequest output(Boolean markComplete) {
        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(3L);
        req.setReportKind("OUTPUT");
        req.setOutputQuantity(new BigDecimal("40"));
        req.setMarkComplete(markComplete);
        return req;
    }

    @Test
    @DisplayName("App 报工 拦截: 产出完工报工, 首检没送检 → 409 FIRST_ARTICLE_NOT_PASSED, 不落报工单")
    void appCompletionIsBlocked() {
        AppFixture f = app(GateRepos.of(Mode.BLOCK).gate());
        String out = outcome(() -> f.svc().submitReport(F, 500L, 7L, output(null)));
        assertThat(out).contains("|409|" + FirstArticleGate.CODE + "|").contains("卤制首检");
        verify(f.reports(), never()).save(any());
    }

    @Test
    @DisplayName("App 报工 拦截下的对照: 部分产出续报 (markComplete=false) 不过首检闸")
    void appPartialReportNotGated() {
        GateRepos repos = GateRepos.of(Mode.BLOCK);
        AppFixture f = app(repos.gate());
        String out = outcome(() -> f.svc().submitReport(F, 500L, 7L, output(false)));
        assertThat(out).doesNotContain(FirstArticleGate.CODE);
        verifyNoInteractions(repos.policies(), repos.standards(), repos.inspections());
    }

    @Test
    @DisplayName("App 报工 开关关: 结果与没装闸逐字相同, 不查标准 / 首检单; 落库次数也相同")
    void appOffIsIdenticalToNoGate() {
        AppFixture without = app(null);
        String baseline = outcome(() -> without.svc().submitReport(F, 500L, 7L, output(null)));
        GateRepos repos = GateRepos.of(null);
        AppFixture with = app(repos.gate());
        String gated = outcome(() -> with.svc().submitReport(F, 500L, 7L, output(null)));

        assertThat(gated).isEqualTo(baseline).doesNotContain(FirstArticleGate.CODE);
        assertThat(Mockito.mockingDetails(with.reports()).getInvocations().size())
                .isEqualTo(Mockito.mockingDetails(without.reports()).getInvocations().size());
        verify(repos.policies()).findModeByFactoryId(F); // 「一定会发生」: 开关确实被问了
        verifyNoInteractions(repos.standards(), repos.inspections());
    }

    @Test
    @DisplayName("App 报工 开关读取抛错: 按关处理, 结果与没装闸逐字相同, 不查标准 / 首检单")
    void appSwitchReadFailureBehavesAsOff() {
        AppFixture without = app(null);
        String baseline = outcome(() -> without.svc().submitReport(F, 500L, 7L, output(null)));
        GateRepos repos = GateRepos.failingRead();
        AppFixture with = app(repos.gate());
        String gated = outcome(() -> with.svc().submitReport(F, 500L, 7L, output(null)));
        assertThat(gated).isEqualTo(baseline).doesNotContain(FirstArticleGate.CODE).doesNotContain("connection reset");
        verify(repos.policies()).findModeByFactoryId(F);
        verifyNoInteractions(repos.standards(), repos.inspections());
    }

    // ==================== Web 逐道录入 ====================

    private record WebFixture(ProcessSheetServiceImpl svc, ProcessSheetRowRepository rows, WorkflowClerkSheetService sheet) {
    }

    private static WebFixture web(FirstArticleGate gate) {
        ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
        when(planRepo.findByIdAndFactoryId("P1", F)).thenReturn(Optional.of(new ProductionPlan()));
        WorkProcessTaskRepository taskRepo = mock(WorkProcessTaskRepository.class);
        when(taskRepo.findByFactoryIdAndWorkflowInstanceIdOrderByProcessOrderAsc(F, 900L)).thenReturn(List.of(task()));
        ProcessSheetRowRepository rowRepo = mock(ProcessSheetRowRepository.class);
        WorkflowClerkSheetService sheet = mock(WorkflowClerkSheetService.class);
        WorkflowClerkSheetConfigDTO cfg = new WorkflowClerkSheetConfigDTO();
        cfg.setWorkflowInstanceId(900L);
        WorkflowClerkSheetConfigDTO.ProcessDescriptor d = new WorkflowClerkSheetConfigDTO.ProcessDescriptor();
        d.setProcessOrder(3);
        d.setWorkflowNodeId("node-braise");
        cfg.setProcesses(List.of(d));
        when(sheet.getWorkflowSheetConfig(F, "P1")).thenReturn(cfg);
        ProcessSheetServiceImpl svc = WorkflowStepUnlockWiringContractTest.construct(ProcessSheetServiceImpl.class, Map.of(
                ProductionPlanRepository.class, planRepo,
                WorkProcessTaskRepository.class, taskRepo,
                ProcessSheetRowRepository.class, rowRepo));
        ReflectionTestUtils.setField(svc, "workflowClerkSheetService", sheet);
        if (gate != null) {
            ReflectionTestUtils.setField(svc, "firstArticleGate", gate);
        }
        return new WebFixture(svc, rowRepo, sheet);
    }

    private static ProcessSheetRowRequest row(String output) {
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setProcessOrder(3);
        req.setProcessCode("BRAISE");
        req.setClientRowId("r1");
        req.setOutputQuantity(new BigDecimal(output));
        return req;
    }

    @Test
    @DisplayName("Web 正式录入 拦截: 正数产出行, 首检没送检 → 409 FIRST_ARTICLE_NOT_PASSED, 不写工序行")
    void webFormalRowIsBlocked() {
        WebFixture f = web(GateRepos.of(Mode.BLOCK).gate());
        String out = outcome(() -> f.svc().saveRow(F, "P1", row("40"), 7L));
        assertThat(out).contains("|409|" + FirstArticleGate.CODE + "|");
        verify(f.rows(), never()).save(any());
    }

    @Test
    @DisplayName("Web 正式录入 拦截下的对照: 首检合格 → 不被首检闸拦; 产出为 0 的行不过闸")
    void webPassedOrZeroOutputNotBlocked() {
        GateRepos repos = GateRepos.of(Mode.BLOCK);
        FirstArticleInspection passed = new FirstArticleInspection();
        passed.setWorkProcessTaskId(3L);
        passed.setStatus(FirstArticleInspection.Status.PASSED);
        when(repos.inspections().findEffectiveForTasks(anyString(), anyCollection())).thenReturn(List.of(passed));
        WebFixture f = web(repos.gate());
        assertThat(outcome(() -> f.svc().saveRow(F, "P1", row("40"), 7L))).doesNotContain(FirstArticleGate.CODE);

        GateRepos zeroRepos = GateRepos.of(Mode.BLOCK);
        WebFixture z = web(zeroRepos.gate());
        assertThat(outcome(() -> z.svc().saveRow(F, "P1", row("0"), 7L))).doesNotContain(FirstArticleGate.CODE);
        verifyNoInteractions(zeroRepos.standards(), zeroRepos.inspections());
    }

    @Test
    @DisplayName("Web 正式录入 开关读取抛错: 按关处理, 结果与没装闸逐字相同, 不查标准 / 首检单")
    void webSwitchReadFailureBehavesAsOff() {
        WebFixture without = web(null);
        String baseline = outcome(() -> without.svc().saveRow(F, "P1", row("40"), 7L));
        GateRepos repos = GateRepos.failingRead();
        WebFixture with = web(repos.gate());
        String gated = outcome(() -> with.svc().saveRow(F, "P1", row("40"), 7L));
        assertThat(gated).isEqualTo(baseline).doesNotContain(FirstArticleGate.CODE).doesNotContain("connection reset");
        verify(repos.policies(), Mockito.atLeastOnce()).findModeByFactoryId(F);
        verifyNoInteractions(repos.standards(), repos.inspections());
    }

    @Test
    @DisplayName("Web 正式录入 开关关: 结果与没装闸逐字相同, 不查标准 / 首检单, 读 Workflow 快照次数相同")
    void webOffIsIdenticalToNoGate() {
        WebFixture without = web(null);
        String baseline = outcome(() -> without.svc().saveRow(F, "P1", row("40"), 7L));
        GateRepos repos = GateRepos.of(null);
        WebFixture with = web(repos.gate());
        String gated = outcome(() -> with.svc().saveRow(F, "P1", row("40"), 7L));

        assertThat(gated).isEqualTo(baseline).doesNotContain(FirstArticleGate.CODE);
        assertThat(Mockito.mockingDetails(with.sheet()).getInvocations().size())
                .isEqualTo(Mockito.mockingDetails(without.sheet()).getInvocations().size());
        assertThat(Mockito.mockingDetails(with.rows()).getInvocations().size())
                .isEqualTo(Mockito.mockingDetails(without.rows()).getInvocations().size());
        verify(repos.policies(), Mockito.atLeastOnce()).findModeByFactoryId(F);
        verifyNoInteractions(repos.standards(), repos.inspections());
    }
}
