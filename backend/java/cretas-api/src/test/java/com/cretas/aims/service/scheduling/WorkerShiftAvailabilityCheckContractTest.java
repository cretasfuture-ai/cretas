package com.cretas.aims.service.scheduling;

import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.SchedulingPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.WorkerAssignment;
import com.cretas.aims.entity.hr.AttendanceShift;
import com.cretas.aims.entity.hr.EmployeeShiftAssignment;
import com.cretas.aims.entity.hr.LeaveRequest;
import com.cretas.aims.entity.hr.enums.HrRequestStatus;
import com.cretas.aims.entity.hr.enums.ShiftAssignmentStatus;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WorkerAssignmentRepository;
import com.cretas.aims.repository.hr.AttendanceShiftRepository;
import com.cretas.aims.repository.hr.EmployeeShiftAssignmentRepository;
import com.cretas.aims.repository.hr.LeaveRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WorkerShiftAvailabilityCheckContractTest {

    private static final String FACTORY = "F-WORKER";
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 6, 8, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 11, 6, 16, 0);

    @Mock private EmployeeShiftAssignmentRepository shiftAssignments;
    @Mock private AttendanceShiftRepository shifts;
    @Mock private UserRepository users;
    @Mock private LeaveRequestRepository leaveRequests;
    @Mock private WorkerAssignmentRepository workerAssignments;

    private WorkerShiftAvailabilityCheck check;

    @BeforeEach
    void setUp() {
        check = new WorkerShiftAvailabilityCheck(shiftAssignments, shifts, users,
                leaveRequests, workerAssignments);
        when(users.findByFactoryIdAndIsActive(FACTORY, true)).thenReturn(List.of(worker(1L, "operator")));
        // Invalid/missing roster tests deliberately stop before these optional fact reads.
        lenient().when(leaveRequests.findOverlappingActive(eq(FACTORY), any(Long.class), any(), any())).thenReturn(List.of());
        lenient().when(workerAssignments.findRecentByFactoryIdAndUserId(eq(FACTORY), any(Long.class))).thenReturn(List.of());
    }

    @Test
    void missingWindowOrScheduleIsUnverified() {
        WorkerShiftAvailabilityCheck.Result noRequirement = check.check(FACTORY, START, END, null);
        assertThat(noRequirement.verified()).isFalse();
        assertThat(noRequirement.noScheduleData()).as("没填需求人数不是「没有排班数据」").isFalse();

        when(shiftAssignments.findByFactoryIdAndWorkDateBetween(
                FACTORY, START.toLocalDate().minusDays(1), END.toLocalDate())).thenReturn(List.of());
        WorkerShiftAvailabilityCheck.Result result = check.check(FACTORY, START, END, 1);

        assertThat(result.verified()).isFalse();
        assertThat(result.message()).isEqualTo(WorkerShiftAvailabilityCheck.NO_SCHEDULE_DATA);
        assertThat(result.noScheduleData()).as("窗口内一条排班都没有 = 没数据, 不是人不够").isTrue();
        assertThat(result.availableWorkers()).isZero();
        assertThat(result.available()).isFalse();
    }

    @Test
    void overnightAssignmentStartsOnPreviousWorkDateAndUsesHalfOpenEnd() {
        AttendanceShift night = shift("NIGHT", LocalTime.of(22, 0), LocalTime.of(6, 0), true);
        when(shifts.findByIdAndFactoryId("NIGHT", FACTORY)).thenReturn(Optional.of(night));
        when(shiftAssignments.findByFactoryIdAndWorkDateBetween(
                FACTORY, START.toLocalDate().minusDays(1), END.toLocalDate()))
                .thenReturn(List.of(assignment(1L, START.toLocalDate().minusDays(1), "NIGHT",
                        ShiftAssignmentStatus.SCHEDULED)));

        WorkerShiftAvailabilityCheck.Result result = check.check(FACTORY,
                LocalDateTime.of(2026, 11, 6, 1, 0), LocalDateTime.of(2026, 11, 6, 6, 0), 1);

        assertThat(result.available()).isTrue();
        assertThat(result.availableWorkerIds()).containsExactly(1L);
    }

    @Test
    void adjacentSegmentsCoverButGapDoesNot() {
        // One row per (factory,user,workDate), matching the real HR unique constraint.
        AttendanceShift morning = shift("MORNING", LocalTime.of(23, 0), LocalTime.of(2, 0), true);
        AttendanceShift afternoon = shift("AFTERNOON", LocalTime.of(2, 0), LocalTime.of(6, 0), false);
        when(shifts.findByIdAndFactoryId("MORNING", FACTORY)).thenReturn(Optional.of(morning));
        when(shifts.findByIdAndFactoryId("AFTERNOON", FACTORY)).thenReturn(Optional.of(afternoon));
        when(shiftAssignments.findByFactoryIdAndWorkDateBetween(
                eq(FACTORY), any(), any()))
                .thenReturn(List.of(
                        assignment(1L, START.toLocalDate().minusDays(1), "MORNING", ShiftAssignmentStatus.SCHEDULED),
                        assignment(1L, START.toLocalDate(), "AFTERNOON", ShiftAssignmentStatus.SCHEDULED)));

        LocalDateTime from = START.toLocalDate().minusDays(1).atTime(23, 0);
        LocalDateTime to = START.toLocalDate().atTime(6, 0);
        assertThat(check.check(FACTORY, from, to, 1).available()).isTrue();
        afternoon.setStartTime(LocalTime.of(2, 1));
        var gap = check.check(FACTORY, from, to, 1);
        assertThat(gap.verified()).isTrue();
        assertThat(gap.available()).isFalse();
        assertThat(gap.message()).startsWith("人员不足");
    }

    @Test
    void midnightEndExcludesNextDayFromRosterLeaveAndContractChecks() {
        User employee = worker(1L, "operator");employee.setContractEndDate(START.toLocalDate());
        when(users.findByFactoryIdAndIsActive(FACTORY, true)).thenReturn(List.of(employee));
        AttendanceShift night = shift("NIGHT", LocalTime.of(22, 0), LocalTime.of(6, 0), true);
        when(shifts.findByIdAndFactoryId("NIGHT", FACTORY)).thenReturn(Optional.of(night));
        when(shiftAssignments.findByFactoryIdAndWorkDateBetween(FACTORY,
                START.toLocalDate().minusDays(1), START.toLocalDate()))
                .thenReturn(List.of(assignment(1L, START.toLocalDate(), "NIGHT", ShiftAssignmentStatus.SCHEDULED)));
        var result = check.check(FACTORY, START.toLocalDate().atTime(22, 0),
                START.toLocalDate().plusDays(1).atStartOfDay(), 1);
        assertThat(result.available()).isTrue();
        verify(shiftAssignments).findByFactoryIdAndWorkDateBetween(FACTORY,
                START.toLocalDate().minusDays(1), START.toLocalDate());
        verify(leaveRequests).findOverlappingActive(FACTORY, 1L, START.toLocalDate(), START.toLocalDate());
    }

    @Test
    void duplicateRowsAreDeduplicatedAndOnlyProductionRolesCount() {
        User supervisor = worker(2L, "workshop_supervisor");
        User office = worker(3L, "hr_admin");
        when(users.findByFactoryIdAndIsActive(FACTORY, true)).thenReturn(List.of(
                worker(1L, "operator"), supervisor, office));
        AttendanceShift day = shift("DAY", LocalTime.of(8, 0), LocalTime.of(16, 0), false);
        when(shifts.findByIdAndFactoryId("DAY", FACTORY)).thenReturn(Optional.of(day));
        when(shiftAssignments.findByFactoryIdAndWorkDateBetween(
                FACTORY, START.toLocalDate().minusDays(1), END.toLocalDate()))
                .thenReturn(List.of(
                        assignment(1L, START.toLocalDate(), "DAY", ShiftAssignmentStatus.SCHEDULED),
                        assignment(1L, START.toLocalDate(), "DAY", ShiftAssignmentStatus.SCHEDULED),
                        assignment(2L, START.toLocalDate(), "DAY", ShiftAssignmentStatus.SCHEDULED),
                        assignment(3L, START.toLocalDate(), "DAY", ShiftAssignmentStatus.SCHEDULED)));

        WorkerShiftAvailabilityCheck.Result result = check.check(FACTORY, START, END, 2);

        assertThat(result.available()).isTrue();
        assertThat(result.availableWorkerIds()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void approvedLeaveBlocksSubmittedIsUnknownDraftIsIgnored() {
        AttendanceShift day = shift("DAY", LocalTime.of(8, 0), LocalTime.of(16, 0), false);
        when(shifts.findByIdAndFactoryId("DAY", FACTORY)).thenReturn(Optional.of(day));
        when(shiftAssignments.findByFactoryIdAndWorkDateBetween(
                FACTORY, START.toLocalDate().minusDays(1), END.toLocalDate()))
                .thenReturn(List.of(assignment(1L, START.toLocalDate(), "DAY", ShiftAssignmentStatus.SCHEDULED)));

        LeaveRequest draft = leave(HrRequestStatus.DRAFT);
        when(leaveRequests.findOverlappingActive(FACTORY, 1L, START.toLocalDate(), END.toLocalDate()))
                .thenReturn(List.of(draft));
        assertThat(check.check(FACTORY, START, END, 1).available()).isTrue();

        LeaveRequest submitted = leave(HrRequestStatus.SUBMITTED);
        when(leaveRequests.findOverlappingActive(FACTORY, 1L, START.toLocalDate(), END.toLocalDate()))
                .thenReturn(List.of(submitted));
        assertThat(check.check(FACTORY, START, END, 1).message()).startsWith("人员未验证");

        LeaveRequest approved = leave(HrRequestStatus.APPROVED);
        when(leaveRequests.findOverlappingActive(FACTORY, 1L, START.toLocalDate(), END.toLocalDate()))
                .thenReturn(List.of(approved));
        WorkerShiftAvailabilityCheck.Result result = check.check(FACTORY, START, END, 1);
        assertThat(result.verified()).isTrue();
        assertThat(result.available()).isFalse();
        assertThat(result.noScheduleData()).as("有排班数据而数据显示人不够: 仍是「人员不足」").isFalse();
        assertThat(result.message()).startsWith("人员不足");
    }

    @Test
    void overlappingActiveAssignmentBlocksButCompletedCancelledAndNonOverlapDoNot() {
        AttendanceShift day = shift("DAY", LocalTime.of(8, 0), LocalTime.of(16, 0), false);
        when(shifts.findByIdAndFactoryId("DAY", FACTORY)).thenReturn(Optional.of(day));
        when(shiftAssignments.findByFactoryIdAndWorkDateBetween(
                FACTORY, START.toLocalDate().minusDays(1), END.toLocalDate()))
                .thenReturn(List.of(assignment(1L, START.toLocalDate(), "DAY", ShiftAssignmentStatus.SCHEDULED)));

        SchedulingPlan plan = new SchedulingPlan();
        plan.setFactoryId(FACTORY);
        plan.setStatus(SchedulingPlan.PlanStatus.confirmed);
        LineSchedule schedule = new LineSchedule();
        schedule.setPlan(plan);
        schedule.setStatus(LineSchedule.ScheduleStatus.pending);
        schedule.setPlannedStartTime(LocalDateTime.of(2026, 11, 6, 10, 0));
        schedule.setPlannedEndTime(LocalDateTime.of(2026, 11, 6, 12, 0));
        WorkerAssignment busy = new WorkerAssignment();
        busy.setUserId(1L);
        busy.setSchedule(schedule);
        busy.setStatus(WorkerAssignment.AssignmentStatus.working);
        when(workerAssignments.findRecentByFactoryIdAndUserId(FACTORY, 1L)).thenReturn(List.of(busy));
        assertThat(check.check(FACTORY, START, END, 1).message()).startsWith("人员不足");

        schedule.setStatus(LineSchedule.ScheduleStatus.completed);
        assertThat(check.check(FACTORY, START, END, 1).available()).isTrue();
    }

    @Test
    void knownEnoughWorkersMayPassWithAnUnknownOtherCandidate() {
        User unknown = worker(2L, "operator");
        when(users.findByFactoryIdAndIsActive(FACTORY, true)).thenReturn(List.of(
                worker(1L, "operator"), unknown));
        AttendanceShift day = shift("DAY", LocalTime.of(8, 0), LocalTime.of(16, 0), false);
        when(shifts.findByIdAndFactoryId("DAY", FACTORY)).thenReturn(Optional.of(day));
        when(shiftAssignments.findByFactoryIdAndWorkDateBetween(
                FACTORY, START.toLocalDate().minusDays(1), END.toLocalDate()))
                .thenReturn(List.of(
                        assignment(1L, START.toLocalDate(), "DAY", ShiftAssignmentStatus.SCHEDULED),
                        assignment(2L, START.toLocalDate(), "MISSING", ShiftAssignmentStatus.SCHEDULED)));
        when(shifts.findByIdAndFactoryId("MISSING", FACTORY)).thenReturn(Optional.empty());

        WorkerShiftAvailabilityCheck.Result result = check.check(FACTORY, START, END, 1);

        assertThat(result.verified()).isTrue();
        assertThat(result.availableWorkerIds()).containsExactly(1L);
    }

    @Test
    void invalidShiftAndRepositoryFailureNeverBecomeSuccess() {
        AttendanceShift invalid = shift("BAD", LocalTime.of(8, 0), LocalTime.of(8, 0), false);
        when(shifts.findByIdAndFactoryId("BAD", FACTORY)).thenReturn(Optional.of(invalid));
        when(shiftAssignments.findByFactoryIdAndWorkDateBetween(
                FACTORY, START.toLocalDate().minusDays(1), END.toLocalDate()))
                .thenReturn(List.of(assignment(1L, START.toLocalDate(), "BAD", ShiftAssignmentStatus.SCHEDULED)));
        assertThat(check.check(FACTORY, START, END, 1).message()).startsWith("人员未验证");

        when(shiftAssignments.findByFactoryIdAndWorkDateBetween(
                FACTORY, START.toLocalDate().minusDays(1), END.toLocalDate()))
                .thenThrow(new IllegalStateException("database unavailable"));
        assertThat(check.check(FACTORY, START, END, 1).message()).startsWith("人员未验证");
    }

    private static User worker(long id, String role) {
        User user = new User();
        user.setId(id);
        user.setFactoryId(FACTORY);
        user.setIsActive(true);
        user.setRoleCode(role);
        user.setHireDate(LocalDate.of(2026, 1, 1));
        return user;
    }

    private static EmployeeShiftAssignment assignment(long userId, LocalDate date, String shiftId,
                                                      ShiftAssignmentStatus status) {
        EmployeeShiftAssignment assignment = new EmployeeShiftAssignment();
        assignment.setFactoryId(FACTORY);
        assignment.setUserId(userId);
        assignment.setWorkDate(date);
        assignment.setShiftId(shiftId);
        assignment.setStatus(status);
        return assignment;
    }

    private static AttendanceShift shift(String id, LocalTime start, LocalTime end, boolean overnight) {
        AttendanceShift shift = new AttendanceShift();
        shift.setId(id);
        shift.setFactoryId(FACTORY);
        shift.setStartTime(start);
        shift.setEndTime(end);
        shift.setIsOvernight(overnight);
        shift.setIsActive(true);
        return shift;
    }

    private static LeaveRequest leave(HrRequestStatus status) {
        LeaveRequest leave = new LeaveRequest();
        leave.setFactoryId(FACTORY);
        leave.setUserId(1L);
        leave.setStartDate(START.toLocalDate());
        leave.setEndDate(END.toLocalDate());
        leave.setStatus(status);
        return leave;
    }
}
