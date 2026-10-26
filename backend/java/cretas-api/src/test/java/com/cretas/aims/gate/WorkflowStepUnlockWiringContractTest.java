package com.cretas.aims.gate;

import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO;
import com.cretas.aims.dto.yield.YieldReportRequest;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl;
import com.cretas.aims.service.workflow.WorkflowClerkSheetService;
import com.cretas.aims.service.workprocess.WorkflowStepUnlockGuard;
import com.cretas.aims.service.workprocess.impl.WorkProcessTaskServiceImpl;
import com.cretas.aims.service.yield.impl.YieldReportServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.stereotype.Component;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺口 04 的闸<b>接上没有</b> (形态 B)。
 *
 * <p>{@link WorkflowStepUnlockGuard} 自己的规则测试再绿, 也不证明开工 / App 报工 / web 正式录入
 * 三个写入口真的调了它。三处都用 {@code @Autowired(required = false)} 字段注入 (构造器被大量测试按位置
 * {@code new}, 不能动) —— bean 缺了会<b>静默注入 null</b>, 闸就没了。所以这里两层:
 * <ol>
 *   <li>行为: 给三个服务塞一个「一定拒绝」的 guard, 断言请求被 409 拒绝且<b>什么都没落库</b>;</li>
 *   <li>注入: guard 带 {@code @Component} (Spring 一定建得出来), 三处字段名与类型都在。</li>
 * </ol>
 */
@DisplayName("按工艺图解锁: 三个写入口都接上了闸")
class WorkflowStepUnlockWiringContractTest {

    private static final String F = "F006";
    private static final BusinessException BLOCKED = new BusinessException(409, "上游工序「解冻」还没有产出")
            .withCode(WorkflowStepUnlockGuard.ERROR_CODE);

    private static WorkflowStepUnlockGuard rejectingGuard() {
        WorkflowStepUnlockGuard guard = mock(WorkflowStepUnlockGuard.class);
        doThrow(BLOCKED).when(guard).assertUnlocked(any(), any());
        return guard;
    }

    private static WorkProcessTask task() {
        WorkProcessTask t = new WorkProcessTask();
        t.setId(3L);
        t.setFactoryId(F);
        t.setProductionBatchId(500L);
        t.setWorkflowInstanceId(900L);
        t.setWorkflowNodeId("node-braise");
        t.setProcessOrder(3);
        t.setWorkProcessId("WP-BRAISE");
        t.setStatus(WorkProcessTask.Status.PENDING);
        return t;
    }

    @Test
    @DisplayName("开工: 上游没料 → 409, 任务仍是 PENDING, 没有保存")
    void startIsGated() {
        WorkProcessTaskRepository taskRepo = mock(WorkProcessTaskRepository.class);
        WorkProcessTask t = task();
        when(taskRepo.findByFactoryIdAndId(F, 3L)).thenReturn(Optional.of(t));
        WorkProcessTaskServiceImpl svc = construct(WorkProcessTaskServiceImpl.class,
                Map.of(WorkProcessTaskRepository.class, taskRepo));
        ReflectionTestUtils.setField(svc, "unlockGuard", rejectingGuard());

        assertThatThrownBy(() -> svc.start(F, 3L, 7L)).isSameAs(BLOCKED);
        assertThat(t.getStatus()).isEqualTo(WorkProcessTask.Status.PENDING);
        verify(taskRepo, never()).save(any());
    }

    @Test
    @DisplayName("列表: 每个未完结任务带上 unlocked / blockedReason (与写入口同一份判定)")
    void listCarriesReadiness() {
        WorkProcessTaskRepository taskRepo = mock(WorkProcessTaskRepository.class);
        WorkProcessTask t = task();
        when(taskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(F, 500L)).thenReturn(List.of(t));
        WorkProcessTaskServiceImpl svc = construct(WorkProcessTaskServiceImpl.class,
                Map.of(WorkProcessTaskRepository.class, taskRepo));
        WorkflowStepUnlockGuard guard = mock(WorkflowStepUnlockGuard.class);
        when(guard.evaluateAll(eq(F), anyList())).thenReturn(Map.of(3L,
                new WorkflowStepUnlockGuard.Readiness(false, List.of("解冻"))));
        ReflectionTestUtils.setField(svc, "unlockGuard", guard);

        List<WorkProcessTaskDTO> dtos = svc.listByBatch(F, 500L, null);

        assertThat(dtos).hasSize(1);
        assertThat(dtos.get(0).getUnlocked()).isFalse();
        assertThat(dtos.get(0).getBlockedReason()).contains("解冻");
    }

    @Test
    @DisplayName("App 报工: 上游没料 → 409, 不落报工单")
    void appReportIsGated() {
        WorkProcessTaskRepository taskRepo = mock(WorkProcessTaskRepository.class);
        ProductionReportRepository reportRepo = mock(ProductionReportRepository.class);
        when(taskRepo.findByFactoryIdAndId(F, 3L)).thenReturn(Optional.of(task()));
        YieldReportServiceImpl svc = construct(YieldReportServiceImpl.class, Map.of(
                WorkProcessTaskRepository.class, taskRepo,
                ProductionReportRepository.class, reportRepo));
        ReflectionTestUtils.setField(svc, "unlockGuard", rejectingGuard());

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(3L);
        req.setReportKind("OUTPUT");
        req.setOutputQuantity(new BigDecimal("40"));

        assertThatThrownBy(() -> svc.submitReport(F, 500L, 7L, req)).isSameAs(BLOCKED);
        verify(reportRepo, never()).save(any());
    }

    @Test
    @DisplayName("web 正式录入 (saveRow / submitRow): 上游没料 → 409, 不写工序行; 存草稿行 (产出≤0) 不拦")
    void webFormalRowIsGated() {
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

        ProcessSheetServiceImpl svc = construct(ProcessSheetServiceImpl.class, Map.of(
                ProductionPlanRepository.class, planRepo,
                WorkProcessTaskRepository.class, taskRepo,
                ProcessSheetRowRepository.class, rowRepo));
        ReflectionTestUtils.setField(svc, "workflowClerkSheetService", sheet);
        WorkflowStepUnlockGuard guard = rejectingGuard();
        ReflectionTestUtils.setField(svc, "unlockGuard", guard);

        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setProcessOrder(3);
        req.setProcessCode("BRAISE");
        req.setClientRowId("r1");
        req.setOutputQuantity(new BigDecimal("40"));

        assertThatThrownBy(() -> svc.saveRow(F, "P1", req, 7L)).isSameAs(BLOCKED);
        verify(rowRepo, never()).save(any());

        // 阴性对照: 产出为 0 的行 (草稿形态) 不调闸
        Mockito.clearInvocations(guard);
        req.setOutputQuantity(BigDecimal.ZERO);
        try {
            svc.saveRow(F, "P1", req, 7L);
        } catch (RuntimeException ignored) {
            // 草稿路径后面还有别的依赖没装配, 这里只关心它没被闸拦
        }
        verify(guard, never()).assertUnlocked(any(), any());
    }

    @Test
    @DisplayName("注入: guard 是 @Component; 三个写入口都声明了字段并调用 assertUnlocked")
    void wiredInAllThreeEntryPoints() throws IOException {
        assertThat(WorkflowStepUnlockGuard.class.isAnnotationPresent(Component.class))
                .as("没有 @Component, required=false 的字段会静默注入 null").isTrue();
        Path root = Paths.get("src/main/java/com/cretas/aims/service");
        for (String f : List.of(
                "workprocess/impl/WorkProcessTaskServiceImpl.java",
                "yield/impl/YieldReportServiceImpl.java",
                "processentry/impl/ProcessSheetServiceImpl.java")) {
            String src = Files.readString(root.resolve(f), StandardCharsets.UTF_8);
            assertThat(src).as("%s 没声明 guard 字段", f).contains("WorkflowStepUnlockGuard unlockGuard;");
            assertThat(src).as("%s 没调 assertUnlocked", f).contains("unlockGuard.assertUnlocked(");
        }
    }

    /** 用最多参数的构造器建服务, 没给的依赖一律 mock (与 UrgentInsertTestSupport 同法, 不绑死构造器形状)。 */
    static <T> T construct(Class<T> type, Map<Class<?>, Object> provided) {
        try {
            Constructor<?> ctor = Arrays.stream(type.getDeclaredConstructors())
                    .max(Comparator.comparingInt(Constructor::getParameterCount))
                    .orElseThrow();
            Class<?>[] params = ctor.getParameterTypes();
            Object[] args = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                args[i] = provided.containsKey(params[i]) ? provided.get(params[i]) : Mockito.mock(params[i]);
            }
            ctor.setAccessible(true);
            return type.cast(ctor.newInstance(args));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot construct " + type.getSimpleName(), e);
        }
    }
}
