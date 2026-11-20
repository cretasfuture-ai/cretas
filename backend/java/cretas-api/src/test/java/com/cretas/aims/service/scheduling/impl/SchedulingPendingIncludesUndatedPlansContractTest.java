package com.cretas.aims.service.scheduling.impl;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.repository.*;
import com.cretas.aims.service.NotificationService;
import com.cretas.aims.service.PushNotificationService;
import com.cretas.aims.service.scheduling.core.SchedulingAIService;
import com.cretas.aims.service.scheduling.core.WorkerAssignmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-S411-03: 「按日产能自动排程」的候选计划列表 ({@code GET /scheduling/pending-batches}) 不能静默丢掉
 * 没有日期的待处理计划。
 *
 * <p>候选列表按预计完成日 BETWEEN 过滤, 对 NULL 恒不命中 —— 订单没填交期时财审自动生成的 PP-AUTO-*
 * 原来在排程页里看不到, 生产经理只能先进计划编辑手填日期。现在: 不传日期范围时 (web-admin 排程页)
 * 把「未定日期」的 PENDING 计划一并列出; 传了日期范围的调用方 (RN AIScheduleScreen) 行为不变。
 *
 * <p>夹具与 {@link SchedulingPendingPlansIncludeOverdueTest} 相同。本类命名 *ContractTest
 * 是为了被 PR 作业选中 (那个类在 PR 选择基线里, PR CI 不跑)。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("D-S411-03 排程候选列表纳入「未定日期」的待处理计划")
class SchedulingPendingIncludesUndatedPlansContractTest {
    @Mock private SchedulingPlanRepository planRepository;
    @Mock private LineScheduleRepository scheduleRepository;
    @Mock private WorkerAssignmentRepository assignmentRepository;
    @Mock private ProductionLineRepository lineRepository;
    @Mock private SchedulingAlertRepository alertRepository;
    @Mock private UserRepository userRepository;
    @Mock private ProductionBatchRepository batchRepository;
    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private DroolsRuleRepository droolsRuleRepository;
    @Mock private NotificationService notificationService;
    @Mock private PushNotificationService pushNotificationService;
    @Mock private ProductionLineSupervisorRepository supervisorRepository;
    @Mock private WorkerAssignmentService workerAssignmentService;
    @Mock private SchedulingAIService schedulingAIService;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;
    @InjectMocks private SchedulingPlanCrudServiceImpl service;

    @BeforeEach
    void wireFieldInjectedDependencies() {
        // 服务走构造器注入, Mockito 用了构造器就不再做字段注入: 字段注入的依赖手动放进去
        ReflectionTestUtils.setField(service, "schedulingAIService", schedulingAIService);
        ReflectionTestUtils.setField(service, "rawMaterialTypeRepository", rawMaterialTypeRepository);
    }

    private static ProductionPlan plan(String id, LocalDate due) {
        ProductionPlan p = new ProductionPlan();
        p.setId(id);
        p.setFactoryId("F1");
        p.setStatus(ProductionPlanStatus.PENDING);
        p.setExpectedCompletionDate(due);
        return p;
    }

    @Test
    @DisplayName("🔴 不传日期范围: 「未定日期」的待处理计划 (订单没交期的 PP-AUTO) 在候选里; 有日期的计划不变")
    void defaultRangeIncludesUndatedPendingPlans() {
        LocalDate due = LocalDate.now().plusDays(10);
        when(productionPlanRepository.findByFactoryIdAndStatusAndExpectedCompletionDateBetween(
                eq("F1"), eq(ProductionPlanStatus.PENDING), any(), any())).thenReturn(List.of(plan("P-DATED", due)));
        when(productionPlanRepository.findByFactoryIdAndStatusAndExpectedCompletionDateIsNull(
                "F1", ProductionPlanStatus.PENDING)).thenReturn(List.of(plan("PP-AUTO-UNDATED", null)));

        var dtos = service.getPendingBatches("F1", null, null);

        assertThat(dtos).extracting(d -> d.getId() + "@" + d.getExpectedCompletionDate())
                .as("有日期的计划照旧; 没日期的计划单独补上, 交期为空 (前端显示「未定日期」)")
                .containsExactlyInAnyOrder("P-DATED@" + due, "PP-AUTO-UNDATED@null");
    }

    @Test
    @DisplayName("不传日期范围: 带订单交期 (一个月内) 的自动计划落在默认候选窗口里")
    void datedPlanWithinAMonthFallsInsideDefaultWindow() {
        when(productionPlanRepository.findByFactoryIdAndStatusAndExpectedCompletionDateBetween(
                eq("F1"), eq(ProductionPlanStatus.PENDING), any(), any())).thenReturn(List.of());

        service.getPendingBatches("F1", null, null);

        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(productionPlanRepository).findByFactoryIdAndStatusAndExpectedCompletionDateBetween(
                eq("F1"), eq(ProductionPlanStatus.PENDING), from.capture(), to.capture());
        assertThat(LocalDate.now().plusDays(10)).isBetween(from.getValue(), to.getValue());
    }

    @Test
    @DisplayName("阴性对照: 传了日期范围 → 原样使用, 不补「未定日期」的计划 (不替调用方放宽范围)")
    void explicitRangeIsUnchanged() {
        LocalDate start = LocalDate.of(2026, 11, 1);
        LocalDate end = LocalDate.of(2026, 11, 30);
        when(productionPlanRepository.findByFactoryIdAndStatusAndExpectedCompletionDateBetween(
                "F1", ProductionPlanStatus.PENDING, start, end)).thenReturn(List.of(plan("P-NOV", LocalDate.of(2026, 11, 20))));

        var dtos = service.getPendingBatches("F1", start, end);

        assertThat(dtos).extracting(d -> d.getId()).containsExactly("P-NOV");
        verify(productionPlanRepository, never()).findByFactoryIdAndStatusAndExpectedCompletionDateIsNull(any(), any());
    }
}
