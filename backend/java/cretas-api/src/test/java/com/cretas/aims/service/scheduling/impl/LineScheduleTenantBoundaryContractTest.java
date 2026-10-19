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
        when(scheduleRepository.save(any(LineSchedule.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(assignmentRepository.findByScheduleId("S-1")).thenReturn(Collections.emptyList());

        service.getSchedule("F-LOCAL", "S-1");
        service.updateSchedule("F-LOCAL", "S-1", new UpdateScheduleRequest());
        service.startSchedule("F-LOCAL", "S-1");
        service.completeSchedule("F-LOCAL", "S-1", 10);
        service.updateProgress("F-LOCAL", "S-1", 10);

        assertThat(schedule.getStatus()).isEqualTo(LineSchedule.ScheduleStatus.completed);
        assertThat(schedule.getCompletedQuantity()).isEqualTo(10);
        assertThat(schedule.getPlan().getStatus()).isEqualTo(SchedulingPlan.PlanStatus.in_progress);
        verify(scheduleRepository, times(4)).save(schedule);
        verify(planRepository).save(schedule.getPlan());
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
