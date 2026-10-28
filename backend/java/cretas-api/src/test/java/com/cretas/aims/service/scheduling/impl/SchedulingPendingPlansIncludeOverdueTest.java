package com.cretas.aims.service.scheduling.impl;

import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.repository.*;
import com.cretas.aims.service.NotificationService;
import com.cretas.aims.service.PushNotificationService;
import com.cretas.aims.service.scheduling.core.SchedulingAIService;
import com.cretas.aims.service.scheduling.core.WorkerAssignmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 排程页的「待排产计划」默认要含已逾期的计划 (2026-10-28 线上: F006 三个 PENDING 计划到期 10-26/10-27,
 * 下限是今天, 列表为空, 自动排程无计划可选)。传了日期范围的调用方行为不变。
 */
@ExtendWith(MockitoExtension.class)
class SchedulingPendingPlansIncludeOverdueTest {
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
    @InjectMocks private SchedulingPlanCrudServiceImpl service;

    @Test
    @DisplayName("不传起始日期: 下限早于今天, 逾期的待处理计划在列表里")
    void defaultRangeIncludesOverduePlans() {
        when(productionPlanRepository.findByFactoryIdAndStatusAndExpectedCompletionDateBetween(
                eq("F1"), eq(ProductionPlanStatus.PENDING), any(), any())).thenReturn(List.of());
        service.getPendingBatches("F1", null, null);

        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(productionPlanRepository).findByFactoryIdAndStatusAndExpectedCompletionDateBetween(
                eq("F1"), eq(ProductionPlanStatus.PENDING), from.capture(), to.capture());
        // 一个两天前到期的计划必须落在区间内
        LocalDate overdue = LocalDate.now().minusDays(2);
        assertThat(from.getValue()).isBefore(overdue);
        assertThat(to.getValue()).isAfterOrEqualTo(LocalDate.now().plusMonths(1));
    }

    @Test
    @DisplayName("传了日期范围: 原样使用 (阴性对照, 调用方明确的范围不被放宽)")
    void explicitRangeIsUsedAsGiven() {
        LocalDate start = LocalDate.of(2026, 11, 1);
        LocalDate end = LocalDate.of(2026, 11, 30);
        when(productionPlanRepository.findByFactoryIdAndStatusAndExpectedCompletionDateBetween(
                eq("F1"), eq(ProductionPlanStatus.PENDING), eq(start), eq(end))).thenReturn(List.of());
        service.getPendingBatches("F1", start, end);
        verify(productionPlanRepository).findByFactoryIdAndStatusAndExpectedCompletionDateBetween(
                "F1", ProductionPlanStatus.PENDING, start, end);
    }
}
