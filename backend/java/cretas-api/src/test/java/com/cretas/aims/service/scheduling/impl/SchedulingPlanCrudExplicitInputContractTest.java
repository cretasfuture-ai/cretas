package com.cretas.aims.service.scheduling.impl;

import com.cretas.aims.dto.scheduling.CreateSchedulingPlanRequest;
import com.cretas.aims.dto.scheduling.SchedulingPlanDTO;
import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.SchedulingPlan;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.entity.WorkerAssignment;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.*;
import com.cretas.aims.service.NotificationService;
import com.cretas.aims.service.PushNotificationService;
import com.cretas.aims.service.scheduling.core.SchedulingAIService;
import com.cretas.aims.service.scheduling.core.WorkerAssignmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SchedulingPlanCrudExplicitInputContractTest {
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
    void workerIdsAreRejectedBeforePlanWriteInsteadOfBeingCountedAsAssignments() {
        CreateSchedulingPlanRequest request = validRequest();
        request.getSchedules().get(0).setWorkerIds(List.of(42L));

        assertThatThrownBy(() -> service.createPlan("F-MANUAL", request, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("手动指派服务");
        verifyNoInteractions(planRepository, scheduleRepository, notificationService, pushNotificationService);
    }

    @Test
    void invalidQuantityAndTimeAreRejectedBeforeAnyResourceWrite() {
        CreateSchedulingPlanRequest request = validRequest();
        request.getSchedules().get(0).setPlannedQuantity(0);

        assertThatThrownBy(() -> service.createPlan("F-MANUAL", request, 7L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("数量");
        verifyNoInteractions(planRepository, scheduleRepository);
    }

    @Test
    void completedBatchIsRejectedButPlannedBatchCanCreateExplicitSchedule() {
        CreateSchedulingPlanRequest request = validRequest();
        request.getSchedules().get(0).setBatchId(99L);
        ProductionLine line = new ProductionLine();
        line.setId("LINE-001");
        line.setFactoryId("F-MANUAL");
        line.setStatus(ProductionLine.LineStatus.active);
        ProductionBatch batch = new ProductionBatch();
        batch.setId(99L);
        batch.setFactoryId("F-MANUAL");
        batch.setStatus(ProductionBatchStatus.COMPLETED);
        when(lineRepository.findByIdAndFactoryIdAndDeletedAtIsNull("LINE-001", "F-MANUAL"))
                .thenReturn(Optional.of(line));
        when(batchRepository.findByIdAndFactoryId(99L, "F-MANUAL"))
                .thenReturn(Optional.of(batch));

        assertThatThrownBy(() -> service.createPlan("F-MANUAL", request, 7L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不可排程");
        verifyNoInteractions(planRepository, scheduleRepository);

        batch.setStatus(ProductionBatchStatus.PLANNED);
        SchedulingPlan saved = new SchedulingPlan();
        when(planRepository.findByFactoryIdAndPlanDateAndDeletedAtIsNull("F-MANUAL", request.getPlanDate()))
                .thenReturn(Optional.empty());
        when(planRepository.save(any(SchedulingPlan.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service.createPlan("F-MANUAL", request, 7L);
        verify(scheduleRepository).saveAll(anyList());
    }

    @Test
    void enrichPlanDerivesDistinctWorkersAcrossValidSameFactorySchedules() {
        SchedulingPlan localPlan = plan("P-HEADCOUNT", "F-MANUAL");
        LineSchedule first = schedule("S-1", localPlan);
        LineSchedule second = schedule("S-2", localPlan);
        LineSchedule cancelled = schedule("S-CANCELLED", localPlan);
        cancelled.setStatus(LineSchedule.ScheduleStatus.cancelled);
        SchedulingPlan foreignPlan = plan("P-FOREIGN", "F-OTHER");
        LineSchedule foreign = schedule("S-FOREIGN", foreignPlan);

        WorkerAssignment firstWorker = assignment("A-1", first, 42L);
        WorkerAssignment secondWorker = assignment("A-2", second, 43L);
        WorkerAssignment cancelledWorker = assignment("A-CANCELLED", cancelled, 99L);
        WorkerAssignment foreignWorker = assignment("A-3", foreign, 99L);
        SchedulingPlanDTO dto = new SchedulingPlanDTO();
        dto.setId(localPlan.getId());
        dto.setFactoryId(localPlan.getFactoryId());

        when(scheduleRepository.findByPlanId(localPlan.getId()))
                .thenReturn(List.of(first, second, cancelled, foreign));
        when(assignmentRepository.findByScheduleIdIn(anySet()))
                .thenReturn(List.of(firstWorker, secondWorker, cancelledWorker, foreignWorker));

        SchedulingPlanDTO enriched = service.enrichPlanDTO(dto);

        assertThat(enriched.getTotalWorkers()).isEqualTo(2);
        verify(assignmentRepository).findByScheduleIdIn(eq(Set.of("S-1", "S-2")));
        secondWorker.setUserId(42L);
        assertThat(service.enrichPlanDTO(dto).getTotalWorkers()).isEqualTo(1);
    }

    private static SchedulingPlan plan(String id, String factoryId) {
        SchedulingPlan plan = new SchedulingPlan();
        plan.setId(id);
        plan.setFactoryId(factoryId);
        plan.setPlanDate(LocalDate.of(2026, 10, 19));
        return plan;
    }

    private static LineSchedule schedule(String id, SchedulingPlan plan) {
        LineSchedule schedule = new LineSchedule();
        schedule.setId(id);
        schedule.setPlan(plan);
        schedule.setStatus(LineSchedule.ScheduleStatus.pending);
        return schedule;
    }

    private static WorkerAssignment assignment(String id, LineSchedule schedule, Long userId) {
        WorkerAssignment assignment = new WorkerAssignment();
        assignment.setId(id);
        assignment.setSchedule(schedule);
        assignment.setUserId(userId);
        assignment.setStatus(WorkerAssignment.AssignmentStatus.assigned);
        return assignment;
    }

    @ParameterizedTest
    @EnumSource(value = ProductionBatchStatus.class, names = {"PLANNED", "PLANNING"})
    void everyPlannableBatchStatusCreatesAnExplicitSchedule(ProductionBatchStatus status) {
        CreateSchedulingPlanRequest request = requestWithBatch();
        when(lineRepository.findByIdAndFactoryIdAndDeletedAtIsNull("LINE-001", "F-MANUAL"))
                .thenReturn(Optional.of(activeLine()));
        when(batchRepository.findByIdAndFactoryId(99L, "F-MANUAL"))
                .thenReturn(Optional.of(batchWithStatus(status)));
        when(planRepository.findByFactoryIdAndPlanDateAndDeletedAtIsNull("F-MANUAL", request.getPlanDate()))
                .thenReturn(Optional.empty());
        when(planRepository.save(any(SchedulingPlan.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.createPlan("F-MANUAL", request, 7L);

        verify(planRepository, atLeastOnce()).save(any(SchedulingPlan.class));
        verify(scheduleRepository).saveAll(anyList());
    }

    @ParameterizedTest
    @EnumSource(value = ProductionBatchStatus.class, names = {"PLANNED", "PLANNING"}, mode = EnumSource.Mode.EXCLUDE)
    void everyNonPlannableBatchStatusIsRejectedBeforeAnyPlanOrScheduleWrite(ProductionBatchStatus status) {
        CreateSchedulingPlanRequest request = requestWithBatch();
        when(lineRepository.findByIdAndFactoryIdAndDeletedAtIsNull("LINE-001", "F-MANUAL"))
                .thenReturn(Optional.of(activeLine()));
        when(batchRepository.findByIdAndFactoryId(99L, "F-MANUAL"))
                .thenReturn(Optional.of(batchWithStatus(status)));

        assertThatThrownBy(() -> service.createPlan("F-MANUAL", request, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不可排程");
        verifyNoInteractions(planRepository, scheduleRepository, notificationService, pushNotificationService);
    }

    private static CreateSchedulingPlanRequest requestWithBatch() {
        CreateSchedulingPlanRequest request = validRequest();
        request.getSchedules().get(0).setBatchId(99L);
        return request;
    }

    private static ProductionLine activeLine() {
        ProductionLine line = new ProductionLine();
        line.setId("LINE-001");
        line.setFactoryId("F-MANUAL");
        line.setStatus(ProductionLine.LineStatus.active);
        return line;
    }

    private static ProductionBatch batchWithStatus(ProductionBatchStatus status) {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(99L);
        batch.setFactoryId("F-MANUAL");
        batch.setStatus(status);
        return batch;
    }

    private static CreateSchedulingPlanRequest validRequest() {
        CreateSchedulingPlanRequest request = new CreateSchedulingPlanRequest();
        request.setPlanDate(LocalDate.of(2026, 10, 19));
        CreateSchedulingPlanRequest.ScheduleItem item = new CreateSchedulingPlanRequest.ScheduleItem();
        item.setProductionLineId("LINE-001");
        item.setPlannedQuantity(50);
        item.setPlannedStartTime(LocalDateTime.of(2026, 10, 19, 8, 0));
        item.setPlannedEndTime(LocalDateTime.of(2026, 10, 19, 9, 0));
        request.setSchedules(List.of(item));
        return request;
    }
}
