package com.cretas.aims.service.scheduling.impl;

import com.cretas.aims.dto.scheduling.UpdateScheduleRequest;
import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.SchedulingPlan;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.*;
import com.cretas.aims.service.NotificationService;
import com.cretas.aims.service.PushNotificationService;
import com.cretas.aims.service.scheduling.core.SchedulingAIService;
import com.cretas.aims.service.scheduling.core.SchedulingPlanCrudService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LineScheduleTenantBoundaryContractTest {
    @Mock private LineScheduleRepository scheduleRepository;
    @Mock private SchedulingPlanRepository planRepository;
    @Mock private ProductionLineRepository lineRepository;
    @Mock private ProductionBatchRepository batchRepository;
    @Mock private WorkerAssignmentRepository assignmentRepository;
    @Mock private SchedulingAlertRepository alertRepository;
    @Mock private UserRepository userRepository;
    @Mock private DroolsRuleRepository droolsRuleRepository;
    @Mock private NotificationService notificationService;
    @Mock private PushNotificationService pushNotificationService;
    @Mock private SchedulingAIService schedulingAIService;
    @Mock private SchedulingPlanCrudService schedulingPlanCrudService;
    @Mock private com.cretas.aims.repository.scheduling.CancelledPlanScheduleRepository cancelledPlanSchedules;
    @InjectMocks private LineScheduleServiceImpl service;

    @Test
    void foreignParentBlocksAllFiveScheduleEntryPointsBeforeSideEffects() {
        LineSchedule schedule = schedule("F-OTHER");
        when(scheduleRepository.findById("S-1")).thenReturn(Optional.of(schedule));

        assertThatThrownBy(() -> service.getSchedule("F-LOCAL", "S-1")).isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> service.updateSchedule("F-LOCAL", "S-1", new UpdateScheduleRequest())).isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> service.startSchedule("F-LOCAL", "S-1")).isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> service.completeSchedule("F-LOCAL", "S-1", 1)).isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> service.updateProgress("F-LOCAL", "S-1", 1)).isInstanceOf(EntityNotFoundException.class);

        verify(scheduleRepository, times(5)).findById("S-1");
        verifyNoMoreInteractions(scheduleRepository);
        verifyNoInteractions(planRepository, notificationService, pushNotificationService,
                schedulingAIService, schedulingPlanCrudService, alertRepository);
    }

    @Test
    void deletedParentAndMissingScheduleAreRejectedWithoutWrites() {
        LineSchedule deleted = schedule("F-LOCAL");
        deleted.getPlan().softDelete();
        when(scheduleRepository.findById("S-2")).thenReturn(Optional.of(deleted));
        assertThatThrownBy(() -> service.startSchedule("F-LOCAL", "S-2")).isInstanceOf(EntityNotFoundException.class);

        when(scheduleRepository.findById("MISSING")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.completeSchedule("F-LOCAL", "MISSING", 1)).isInstanceOf(EntityNotFoundException.class);
        verify(scheduleRepository, never()).save(any());
        verifyNoInteractions(planRepository, notificationService, pushNotificationService,
                schedulingAIService, schedulingPlanCrudService, alertRepository);
    }

    @Test
    void sameFactoryScheduleCanReadAndPerformTheFiveExistingOperations() {
        LineSchedule schedule = schedule("F-LOCAL");
        schedule.setProductionLineId("LINE-1");
        schedule.setPlannedQuantity(10);
        schedule.setStatus(LineSchedule.ScheduleStatus.pending);
        schedule.getPlan().setStatus(SchedulingPlan.PlanStatus.confirmed);
        when(scheduleRepository.findById("S-1")).thenReturn(Optional.of(schedule));
        when(scheduleRepository.saveAndFlush(any(LineSchedule.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(assignmentRepository.findByScheduleId("S-1")).thenReturn(Collections.emptyList());
        // 开工先锁日调度计划, 按锁住后读到的最新状态决定要不要推进到「进行中」
        when(planRepository.lockStatusForUpdate("P-1", "F-LOCAL")).thenReturn(Optional.of("confirmed"));

        service.getSchedule("F-LOCAL", "S-1");
        service.updateSchedule("F-LOCAL", "S-1", new UpdateScheduleRequest());
        service.startSchedule("F-LOCAL", "S-1");
        service.completeSchedule("F-LOCAL", "S-1", 10);
        service.updateProgress("F-LOCAL", "S-1", 10);

        assertThat(schedule.getStatus()).isEqualTo(LineSchedule.ScheduleStatus.completed);
        assertThat(schedule.getCompletedQuantity()).isEqualTo(10);
        verify(scheduleRepository, times(4)).saveAndFlush(schedule);
        // 「已确认 → 进行中」只写状态列 (按本厂限定), 不整行保存锁之前读到的旧日计划实体
        org.mockito.InOrder order = inOrder(planRepository, scheduleRepository);
        order.verify(planRepository).lockStatusForUpdate("P-1", "F-LOCAL");
        order.verify(scheduleRepository).lockStatusForUpdate("S-1");
        verify(planRepository).markInProgressIfConfirmed("F-LOCAL", "P-1");
        verify(planRepository, never()).save(any());
    }

    @Test
    void startOnADayPlanThatIsNoLongerConfirmedLeavesItsStatusAlone() {
        LineSchedule schedule = schedule("F-LOCAL");
        schedule.setStatus(LineSchedule.ScheduleStatus.pending);
        // 实体是锁之前读的「已确认」, 锁住后读到的最新状态已经是「进行中」: 以最新的为准, 不再写
        schedule.getPlan().setStatus(SchedulingPlan.PlanStatus.confirmed);
        when(scheduleRepository.findById("S-1")).thenReturn(Optional.of(schedule));
        when(scheduleRepository.saveAndFlush(any(LineSchedule.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(assignmentRepository.findByScheduleId("S-1")).thenReturn(Collections.emptyList());
        when(planRepository.lockStatusForUpdate("P-1", "F-LOCAL")).thenReturn(Optional.of("in_progress"));

        service.startSchedule("F-LOCAL", "S-1");

        verify(planRepository, never()).markInProgressIfConfirmed(any(), any());
        verify(planRepository, never()).save(any());
    }

    private static LineSchedule schedule(String factoryId) {
        SchedulingPlan plan = new SchedulingPlan();
        plan.setId("P-1");
        plan.setFactoryId(factoryId);
        LineSchedule schedule = new LineSchedule();
        schedule.setId("S-1");
        schedule.setPlan(plan);
        return schedule;
    }
}
