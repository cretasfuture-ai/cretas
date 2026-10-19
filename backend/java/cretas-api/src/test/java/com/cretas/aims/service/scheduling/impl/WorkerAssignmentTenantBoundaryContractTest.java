package com.cretas.aims.service.scheduling.impl;

import com.cretas.aims.dto.scheduling.AssignWorkerRequest;
import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.SchedulingPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.WorkerAssignment;
import com.cretas.aims.repository.*;
import com.cretas.aims.service.FeatureEngineeringService;
import com.cretas.aims.service.LinUCBService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Real WorkerAssignmentServiceImpl calls with repository/model boundaries mocked. */
@ExtendWith(MockitoExtension.class)
class WorkerAssignmentTenantBoundaryContractTest {
    private static final String FACTORY = "F-S1";
    private static final String OTHER_FACTORY = "F-OTHER";

    @Mock private WorkerAssignmentRepository assignmentRepository;
    @Mock private LineScheduleRepository scheduleRepository;
    @Mock private SchedulingPlanRepository planRepository;
    @Mock private ProductionLineRepository lineRepository;
    @Mock private ProductionBatchRepository batchRepository;
    @Mock private UserRepository userRepository;
    @Mock private FeatureEngineeringService featureEngineeringService;
    @Mock private LinUCBService linUCBService;
    @InjectMocks private WorkerAssignmentServiceImpl service;

    @Test
    void sameFactoryAssignmentRunsAfterAllTenantChecks() {
        LineSchedule schedule = schedule("S1", FACTORY);
        User worker = worker(11L, FACTORY, true);
        AssignWorkerRequest request = new AssignWorkerRequest();
        request.setScheduleId("S1");
        request.setWorkerIds(List.of(11L));

        when(scheduleRepository.findById("S1")).thenReturn(Optional.of(schedule));
        when(userRepository.findByIdAndFactoryId(11L, FACTORY)).thenReturn(Optional.of(worker));
        when(featureEngineeringService.extractTaskFeatures(eq(FACTORY), anyMap())).thenReturn(new double[0]);
        when(linUCBService.recommendWorkers(eq(FACTORY), any(), eq(List.of(11L)))).thenReturn(List.of());
        when(featureEngineeringService.extractWorkerFeatures(FACTORY, 11L)).thenReturn(new double[0]);
        when(featureEngineeringService.combineFeatures(any(), any())).thenReturn(new double[0]);
        when(assignmentRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(assignmentRepository.countByScheduleId("S1")).thenReturn(1L);
        when(assignmentRepository.findByScheduleIdAndUserId("S1", 11L)).thenReturn(Optional.empty());
        when(userRepository.findById(11L)).thenReturn(Optional.of(worker));
        when(userRepository.findAllById(any())).thenReturn(List.of(worker));
        when(lineRepository.findAllById(any())).thenReturn(List.of());

        service.assignWorkers(FACTORY, request);

        verify(linUCBService).recommendWorkers(eq(FACTORY), any(), eq(List.of(11L)));
        verify(assignmentRepository).saveAll(anyList());
        verify(scheduleRepository).save(schedule);
    }

    @Test
    void foreignScheduleAndMixedWorkersFailBeforeModelOrWrites() {
        LineSchedule schedule = schedule("S1", FACTORY);
        User local = worker(11L, FACTORY, true);
        User foreign = worker(12L, OTHER_FACTORY, true);
        AssignWorkerRequest request = new AssignWorkerRequest();
        request.setScheduleId("S1");
        request.setWorkerIds(List.of(11L, 12L));

        when(scheduleRepository.findById("S1")).thenReturn(Optional.of(schedule));
        when(userRepository.findByIdAndFactoryId(11L, FACTORY)).thenReturn(Optional.of(local));
        when(userRepository.findByIdAndFactoryId(12L, FACTORY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assignWorkers(FACTORY, request)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(linUCBService, featureEngineeringService, assignmentRepository);

        reset(scheduleRepository, userRepository);
        when(scheduleRepository.findById("S2")).thenReturn(Optional.of(schedule("S2", OTHER_FACTORY)));
        request.setScheduleId("S2");
        assertThatThrownBy(() -> service.assignWorkers(FACTORY, request)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(linUCBService, featureEngineeringService, assignmentRepository);
    }

    @Test
    void removeAndCheckInOutRequireAssignmentScheduleAndWorkerTenant() {
        WorkerAssignment assignment = assignment("A1", "S1", 11L);
        LineSchedule schedule = schedule("S1", FACTORY);
        when(assignmentRepository.findById("A1")).thenReturn(Optional.of(assignment));
        when(scheduleRepository.findById("S1")).thenReturn(Optional.of(schedule));
        when(userRepository.findByIdAndFactoryId(11L, FACTORY)).thenReturn(Optional.of(worker(11L, FACTORY, true)));
        when(assignmentRepository.countByScheduleId("S1")).thenReturn(0L);
        when(assignmentRepository.save(assignment)).thenReturn(assignment);
        when(userRepository.findById(11L)).thenReturn(Optional.empty());

        service.removeWorkerAssignment(FACTORY, "A1");
        verify(assignmentRepository).delete(assignment);
        verify(scheduleRepository).save(schedule);

        service.workerCheckIn(FACTORY, "A1");
        service.workerCheckOut(FACTORY, "A1", 90);
        verify(assignmentRepository, atLeast(2)).save(assignment);

        reset(scheduleRepository, userRepository, assignmentRepository);
        when(assignmentRepository.findById("A1")).thenReturn(Optional.of(assignment));
        when(scheduleRepository.findById("S1")).thenReturn(Optional.of(schedule("S1", OTHER_FACTORY)));
        assertThatThrownBy(() -> service.workerCheckIn(FACTORY, "A1")).isInstanceOf(RuntimeException.class);
        verify(assignmentRepository).findById("A1");
        verify(assignmentRepository, never()).save(any());
        verify(assignmentRepository, never()).delete(any());
    }

    @Test
    void inactiveSameFactoryWorkerCanCloseExistingAssignmentButCannotCheckIn() {
        WorkerAssignment assignment = assignment("A2", "S2", 21L);
        LineSchedule schedule = schedule("S2", FACTORY);
        User inactiveWorker = worker(21L, FACTORY, false);
        when(assignmentRepository.findById("A2")).thenReturn(Optional.of(assignment));
        when(scheduleRepository.findById("S2")).thenReturn(Optional.of(schedule));
        when(userRepository.findByIdAndFactoryId(21L, FACTORY)).thenReturn(Optional.of(inactiveWorker));
        when(assignmentRepository.countByScheduleId("S2")).thenReturn(0L);
        when(assignmentRepository.save(assignment)).thenReturn(assignment);
        when(userRepository.findById(21L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.workerCheckIn(FACTORY, "A2"))
            .isInstanceOf(RuntimeException.class);
        verify(assignmentRepository, never()).save(any());

        service.removeWorkerAssignment(FACTORY, "A2");
        service.workerCheckOut(FACTORY, "A2", 80);
        verify(assignmentRepository).delete(assignment);
        verify(assignmentRepository).save(assignment);
    }

    @Test
    void readEntrypointsDropForeignUserAndScheduleRecords() {
        when(userRepository.findByIdAndFactoryId(11L, FACTORY)).thenReturn(Optional.empty());
        org.assertj.core.api.Assertions.assertThat(service.getWorkerAssignments(FACTORY, 11L, LocalDate.now())).isEmpty();
        verifyNoInteractions(assignmentRepository);

        reset(userRepository, scheduleRepository);
        when(userRepository.findByFactoryIdAndIsActive(FACTORY, true)).thenReturn(List.of(worker(11L, FACTORY, true)));
        when(scheduleRepository.findById("S2")).thenReturn(Optional.of(schedule("S2", OTHER_FACTORY)));
        org.assertj.core.api.Assertions.assertThat(service.getAvailableWorkers(FACTORY, LocalDate.now(), "S2")).isEmpty();
        verifyNoInteractions(assignmentRepository);
    }

    @Test
    void personalHistoryKeepsSameFactoryInactiveWorkerRecords() {
        WorkerAssignment assignment = assignment("A3", "S3", 31L);
        when(userRepository.findByIdAndFactoryId(31L, FACTORY))
            .thenReturn(Optional.of(worker(31L, FACTORY, false)));
        when(assignmentRepository.findByUserIdAndDate(eq(31L), any()))
            .thenReturn(List.of(assignment));
        when(scheduleRepository.findById("S3")).thenReturn(Optional.of(schedule("S3", FACTORY)));
        when(userRepository.findAllById(any())).thenReturn(List.of());

        org.assertj.core.api.Assertions.assertThat(
            service.getWorkerAssignments(FACTORY, 31L, LocalDate.now()))
            .hasSize(1);
    }

    @Test
    void personalHistoryFiltersForeignAndStructurallyIncompleteAssignmentsBeforeEnrichment() {
        WorkerAssignment local = assignment("LOCAL", "S-LOCAL", 41L);
        WorkerAssignment foreignSchedule = assignment("FOREIGN-SCHEDULE", "S-FOREIGN", 41L);
        WorkerAssignment foreignWorker = assignment("FOREIGN-WORKER", "S-LOCAL", 42L);
        WorkerAssignment missingSchedule = assignment("MISSING-SCHEDULE", null, 41L);
        User localWorker = worker(41L, FACTORY, false);

        when(userRepository.findByIdAndFactoryId(41L, FACTORY)).thenReturn(Optional.of(localWorker));
        when(userRepository.findByIdAndFactoryId(42L, FACTORY)).thenReturn(Optional.empty());
        when(assignmentRepository.findByUserIdAndDate(eq(41L), any()))
            .thenReturn(List.of(local, foreignSchedule, foreignWorker, missingSchedule));
        when(scheduleRepository.findById("S-LOCAL")).thenReturn(Optional.of(schedule("S-LOCAL", FACTORY)));
        when(scheduleRepository.findById("S-FOREIGN")).thenReturn(Optional.of(schedule("S-FOREIGN", OTHER_FACTORY)));
        when(userRepository.findAllById(any())).thenReturn(List.of(localWorker));

        org.assertj.core.api.Assertions.assertThat(
            service.getWorkerAssignments(FACTORY, 41L, LocalDate.now()))
            .extracting("id")
            .containsExactly("LOCAL");
        verify(userRepository).findAllById(java.util.Set.of(41L));
    }

    private static SchedulingPlan plan(String factoryId) {
        SchedulingPlan plan = new SchedulingPlan();
        plan.setId("P1");
        plan.setFactoryId(factoryId);
        plan.setPlanDate(LocalDate.now());
        return plan;
    }

    private static LineSchedule schedule(String id, String factoryId) {
        LineSchedule schedule = new LineSchedule();
        schedule.setId(id);
        schedule.setPlan(plan(factoryId));
        schedule.setProductionLineId("L1");
        return schedule;
    }

    private static User worker(Long id, String factoryId, boolean active) {
        User user = new User();
        user.setId(id);
        user.setFactoryId(factoryId);
        user.setIsActive(active);
        return user;
    }

    private static WorkerAssignment assignment(String id, String scheduleId, Long userId) {
        WorkerAssignment assignment = new WorkerAssignment();
        assignment.setId(id);
        assignment.setScheduleId(scheduleId);
        assignment.setUserId(userId);
        assignment.setStatus(WorkerAssignment.AssignmentStatus.assigned);
        return assignment;
    }
}
