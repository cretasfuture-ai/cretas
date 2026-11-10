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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only, evidence-based check used before an urgent insert can be planned.
 *
 * <p>This class deliberately does not reserve or assign anybody.  A candidate
 * is available only when HR scheduling proves complete coverage for the whole
 * half-open request window and no leave or active production assignment blocks
 * it.  Missing or contradictory facts remain unknown rather than becoming a
 * guessed free worker.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkerShiftAvailabilityCheck {

    private static final String UNVERIFIED = "人员未验证：无法确认人员排班数据";
    /** 窗口内一条排班都没有: 没数据 ≠ 数据显示人不够; 不编人数、不当 0 处理, 只如实说没核对。 */
    public static final String NO_SCHEDULE_DATA = "人员未核对：没有排班数据";
    private static final String INSUFFICIENT = "人员不足：已验证可用人员不足";
    private static final String AVAILABLE = "人员排班可用：已验证人数满足需求";
    private static final Set<String> PRODUCTION_ROLES = Set.of(
            "operator", "yield_operator", "workshop_supervisor", "team_leader", "group_leader");

    private final EmployeeShiftAssignmentRepository shiftAssignments;
    private final AttendanceShiftRepository shifts;
    private final UserRepository users;
    private final LeaveRequestRepository leaveRequests;
    private final WorkerAssignmentRepository workerAssignments;

    @Transactional(readOnly = true)
    public Result check(String factoryId, LocalDateTime start, LocalDateTime end,
                        Integer requiredWorkers) {
        int required = requiredWorkers == null ? 0 : requiredWorkers;
        if (factoryId == null || factoryId.isBlank() || start == null || end == null
                || required <= 0 || !validWindow(start, end)) {
            return Result.unverified(requiredWorkers, UNVERIFIED);
        }

        LocalDate queryStart = start.toLocalDate().minusDays(1);
        LocalDate queryEnd = end.minusNanos(1).toLocalDate();
        final List<EmployeeShiftAssignment> assignments;
        final List<User> activeUsers;
        try {
            assignments = shiftAssignments.findByFactoryIdAndWorkDateBetween(factoryId, queryStart, queryEnd);
            activeUsers = users.findByFactoryIdAndIsActive(factoryId, true);
        } catch (RuntimeException failure) {
            log.warn("人员核验读取失败: factoryId={}, source=roster, type={}", factoryId, failure.getClass().getSimpleName());
            return Result.unverified(required, UNVERIFIED);
        }
        if (assignments == null || assignments.isEmpty()) {
            return Result.noScheduleData(required);
        }

        Map<Long, User> userById = new HashMap<>();
        if (activeUsers != null) {
            for (User user : activeUsers) {
                if (user != null && user.getId() != null && factoryId.equals(user.getFactoryId())
                        && Boolean.TRUE.equals(user.getIsActive()) && !user.isDeleted()) {
                    userById.put(user.getId(), user);
                }
            }
        }

        Map<Long, Candidate> candidates = new HashMap<>();
        Map<String, Optional<AttendanceShift>> shiftCache = new HashMap<>();
        boolean unknownFact = false;
        for (EmployeeShiftAssignment assignment : assignments) {
            if (assignment == null || assignment.isDeleted() || assignment.getUserId() == null
                    || !factoryId.equals(assignment.getFactoryId())
                    || assignment.getWorkDate() == null
                    || assignment.getWorkDate().isBefore(queryStart)
                    || assignment.getWorkDate().isAfter(queryEnd)) {
                unknownFact = true;
                continue;
            }
            long userId = assignment.getUserId();
            User user = userById.get(userId);
            if (user == null) {
                // The active, factory-scoped user query intentionally omits
                // inactive users.  The assignment is therefore known not to
                // provide an eligible worker; no user data is exposed.
                continue;
            }
            Candidate candidate = candidates.computeIfAbsent(userId, ignored -> new Candidate(user));
            if (!productionRole(user)) {
                continue;
            }
            EmploymentEvidence employment = employmentEvidence(user, start, end);
            if (employment == EmploymentEvidence.UNKNOWN) {
                candidate.unknown = true;
                continue;
            }
            if (employment == EmploymentEvidence.NOT_COVERED) {
                candidate.knownUnavailable = true;
                continue;
            }
            ShiftAssignmentStatus status = assignment.getStatus();
            if (status == null || assignment.getShiftId() == null) {
                candidate.unknown = true;
                continue;
            }

            String shiftId = assignment.getShiftId();
            Optional<AttendanceShift> shift;
            if (shiftCache.containsKey(shiftId)) {
                shift = shiftCache.get(shiftId);
            } else {
                try {
                    shift = shifts.findByIdAndFactoryId(shiftId, factoryId);
                    shift = shift == null ? Optional.empty() : shift;
                    shiftCache.put(shiftId, shift);
                } catch (RuntimeException failure) {
                    log.warn("人员核验读取失败: factoryId={}, source=shift, type={}", factoryId, failure.getClass().getSimpleName());
                    candidate.unknown = true;
                    continue;
                }
            }
            if (shift.isEmpty()) {
                candidate.unknown = true;
                continue;
            }
            AttendanceShift definition = shift.get();
            if (definition.isDeleted() || !factoryId.equals(definition.getFactoryId())
                    || !Boolean.TRUE.equals(definition.getIsActive())) {
                candidate.unknown = true;
                continue;
            }
            Optional<Window> window = toWindow(assignment.getWorkDate(), definition);
            if (window.isEmpty()) {
                candidate.unknown = true;
                continue;
            }
            Window clipped = window.get().clip(start, end);
            if (status == ShiftAssignmentStatus.MISSED || status == ShiftAssignmentStatus.EXCEPTION) {
                if (clipped != null) {
                    candidate.knownUnavailable = true;
                }
                continue;
            }
            if (status != ShiftAssignmentStatus.SCHEDULED && status != ShiftAssignmentStatus.CLOCKED) {
                candidate.unknown = true;
                continue;
            }
            if (clipped != null) {
                candidate.coverage.add(clipped);
            }
        }

        Set<Long> available = new LinkedHashSet<>();
        boolean unknownCandidate = unknownFact;
        for (Candidate candidate : candidates.values()) {
            if (!productionRole(candidate.user)) {
                continue;
            }
            EmploymentEvidence employment = employmentEvidence(candidate.user, start, end);
            if (employment == EmploymentEvidence.UNKNOWN) {
                unknownCandidate = true;
                continue;
            }
            if (employment != EmploymentEvidence.COVERED) {
                continue;
            }
            if (candidate.unknown) {
                unknownCandidate = true;
                continue;
            }
            if (candidate.knownUnavailable || !covers(candidate.coverage, start, end)) {
                continue;
            }

            CandidateEvidence evidence = readCandidateEvidence(factoryId, candidate.user.getId(),
                    start, end);
            if (evidence == CandidateEvidence.UNKNOWN) {
                unknownCandidate = true;
            } else if (evidence == CandidateEvidence.AVAILABLE) {
                available.add(candidate.user.getId());
            }
        }

        if (available.size() >= required) {
            return new Result(true, required, available.size(), available, AVAILABLE);
        }
        if (unknownCandidate) {
            return new Result(false, required, available.size(), available, UNVERIFIED);
        }
        return new Result(true, required, available.size(), available, INSUFFICIENT);
    }

    private CandidateEvidence readCandidateEvidence(String factoryId, Long userId,
                                                     LocalDateTime start, LocalDateTime end) {
        final List<LeaveRequest> leaves;
        final List<WorkerAssignment> occupied;
        LocalDate effectiveEndDate = end.minusNanos(1).toLocalDate();
        try {
            leaves = leaveRequests.findOverlappingActive(factoryId, userId,
                    start.toLocalDate(), effectiveEndDate);
            occupied = workerAssignments.findRecentByFactoryIdAndUserId(factoryId, userId);
        } catch (RuntimeException failure) {
            log.warn("人员核验读取失败: factoryId={}, source=leave-or-assignment, type={}", factoryId, failure.getClass().getSimpleName());
            return CandidateEvidence.UNKNOWN;
        }
        if (leaves == null || occupied == null) {
            return CandidateEvidence.UNKNOWN;
        }
        for (LeaveRequest leave : leaves) {
                if (leave == null || leave.isDeleted() || !factoryId.equals(leave.getFactoryId())
                        || !userId.equals(leave.getUserId()) || leave.getStartDate() == null
                        || leave.getEndDate() == null || leave.getStartDate().isAfter(leave.getEndDate())) {
                    return CandidateEvidence.UNKNOWN;
                }
                HrRequestStatus status = leave.getStatus();
                if (status == HrRequestStatus.SUBMITTED) {
                    return CandidateEvidence.UNKNOWN;
                }
                if (status == HrRequestStatus.APPROVED
                        && overlaps(leave.getStartDate(), leave.getEndDate(), start.toLocalDate(), effectiveEndDate)) {
                    return CandidateEvidence.UNAVAILABLE;
                }
                if (status == null) {
                    return CandidateEvidence.UNKNOWN;
                }
                // DRAFT, REJECTED and CANCELLED are not active leave.
        }
        for (WorkerAssignment assignment : occupied) {
            CandidateEvidence state = occupiedState(factoryId, userId, assignment, start, end);
            if (state != CandidateEvidence.AVAILABLE) {
                return state;
            }
        }
        return CandidateEvidence.AVAILABLE;
    }

    private CandidateEvidence occupiedState(String factoryId, Long userId, WorkerAssignment assignment,
                                            LocalDateTime start, LocalDateTime end) {
        if (assignment == null || assignment.getUserId() == null || !userId.equals(assignment.getUserId())) {
            return CandidateEvidence.UNKNOWN;
        }
        WorkerAssignment.AssignmentStatus status = assignment.getStatus();
        if (status == null) {
            return CandidateEvidence.UNKNOWN;
        }
        if (assignment.getSchedule() == null) {
            return CandidateEvidence.UNKNOWN;
        }
        LineSchedule schedule = assignment.getSchedule();
        SchedulingPlan plan = schedule.getPlan();
        if (plan == null || plan.isDeleted() || !factoryId.equals(plan.getFactoryId())
                || plan.getStatus() == null || schedule.getPlannedStartTime() == null
                || schedule.getPlannedEndTime() == null) {
            return CandidateEvidence.UNKNOWN;
        }
        if (status == WorkerAssignment.AssignmentStatus.checked_out) {
            return CandidateEvidence.AVAILABLE;
        }
        if (schedule.getStatus() == null) {
            return CandidateEvidence.UNKNOWN;
        }
        if (plan.getStatus() == SchedulingPlan.PlanStatus.cancelled
                || plan.getStatus() == SchedulingPlan.PlanStatus.completed
                || schedule.getStatus() == LineSchedule.ScheduleStatus.cancelled
                || schedule.getStatus() == LineSchedule.ScheduleStatus.completed) {
            return CandidateEvidence.AVAILABLE;
        }
        if (status != WorkerAssignment.AssignmentStatus.assigned
                && status != WorkerAssignment.AssignmentStatus.checked_in
                && status != WorkerAssignment.AssignmentStatus.working
                && status != WorkerAssignment.AssignmentStatus.absent) {
            return CandidateEvidence.UNKNOWN;
        }
        LocalDateTime occupiedStart = schedule.getPlannedStartTime();
        LocalDateTime occupiedEnd = schedule.getPlannedEndTime();
        if (!occupiedStart.isBefore(occupiedEnd)) {
            return CandidateEvidence.UNKNOWN;
        }
        if (assignment.getActualStartTime() != null && assignment.getActualStartTime().isBefore(occupiedStart)) {
            occupiedStart = assignment.getActualStartTime();
        }
        if (assignment.getActualEndTime() != null && assignment.getActualEndTime().isAfter(occupiedEnd)) {
            occupiedEnd = assignment.getActualEndTime();
        }
        if (assignment.getActualStartTime() != null && assignment.getActualEndTime() != null
                && assignment.getActualStartTime().isAfter(assignment.getActualEndTime())) {
            return CandidateEvidence.UNKNOWN;
        }
        return overlaps(occupiedStart, occupiedEnd, start, end)
                ? CandidateEvidence.UNAVAILABLE : CandidateEvidence.AVAILABLE;
    }

    private static boolean validWindow(LocalDateTime start, LocalDateTime end) {
        return start.isBefore(end) && !end.isAfter(start.plusDays(7));
    }

    private static boolean productionRole(User user) {
        return user != null && PRODUCTION_ROLES.contains(user.getRoleCode());
    }

    private static EmploymentEvidence employmentEvidence(User user, LocalDateTime start, LocalDateTime end) {
        if (user == null) {
            return EmploymentEvidence.UNKNOWN;
        }
        // Older employee rows predate hire_date.  A null start has no
        // restrictive evidence, whereas an explicit date outside the window
        // is a known non-match.
        if (user.getHireDate() == null) {
            return user.getContractEndDate() == null || !user.getContractEndDate().isBefore(end.minusNanos(1).toLocalDate())
                    ? EmploymentEvidence.COVERED : EmploymentEvidence.NOT_COVERED;
        }
        if (user.getHireDate().isAfter(start.toLocalDate())) {
            return EmploymentEvidence.NOT_COVERED;
        }
        return user.getContractEndDate() == null || !user.getContractEndDate().isBefore(end.minusNanos(1).toLocalDate())
                ? EmploymentEvidence.COVERED : EmploymentEvidence.NOT_COVERED;
    }

    private static Optional<Window> toWindow(LocalDate workDate, AttendanceShift shift) {
        LocalTime shiftStart = shift.getStartTime();
        LocalTime shiftEnd = shift.getEndTime();
        if (workDate == null || shiftStart == null || shiftEnd == null || shiftStart.equals(shiftEnd)
                || shift.getIsOvernight() == null) {
            return Optional.empty();
        }
        LocalDateTime start = LocalDateTime.of(workDate, shiftStart);
        LocalDateTime end = LocalDateTime.of(workDate, shiftEnd);
        if (Boolean.TRUE.equals(shift.getIsOvernight())) {
            if (!shiftStart.isAfter(shiftEnd)) {
                return Optional.empty();
            }
            end = end.plusDays(1);
        } else if (!shiftStart.isBefore(shiftEnd)) {
            return Optional.empty();
        }
        return Optional.of(new Window(start, end));
    }

    private static boolean covers(List<Window> windows, LocalDateTime start, LocalDateTime end) {
        if (windows == null || windows.isEmpty()) {
            return false;
        }
        List<Window> sorted = new ArrayList<>(windows);
        sorted.sort(Comparator.comparing(Window::start));
        LocalDateTime cursor = start;
        for (Window window : sorted) {
            if (window.end().isAfter(cursor) && !window.start().isAfter(cursor)) {
                cursor = window.end().isAfter(cursor) ? window.end() : cursor;
                if (!cursor.isBefore(end)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean overlaps(LocalDate from, LocalDate to, LocalDate requestedFrom, LocalDate requestedTo) {
        return !from.isAfter(requestedTo) && !to.isBefore(requestedFrom);
    }

    private static boolean overlaps(LocalDateTime from, LocalDateTime to,
                                    LocalDateTime requestedFrom, LocalDateTime requestedTo) {
        return from.isBefore(requestedTo) && to.isAfter(requestedFrom);
    }

    private enum CandidateEvidence { AVAILABLE, UNAVAILABLE, UNKNOWN }

    private enum EmploymentEvidence { COVERED, NOT_COVERED, UNKNOWN }

    private static final class Candidate {
        private final User user;
        private final List<Window> coverage = new ArrayList<>();
        private boolean knownUnavailable;
        private boolean unknown;

        private Candidate(User user) {
            this.user = user;
        }
    }

    private record Window(LocalDateTime start, LocalDateTime end) {
        private Window clip(LocalDateTime requestedStart, LocalDateTime requestedEnd) {
            LocalDateTime clippedStart = start.isAfter(requestedStart) ? start : requestedStart;
            LocalDateTime clippedEnd = end.isBefore(requestedEnd) ? end : requestedEnd;
            return clippedStart.isBefore(clippedEnd) ? new Window(clippedStart, clippedEnd) : null;
        }
    }

    public record Result(boolean verified, int requiredWorkers, int availableWorkers,
                         Set<Long> availableWorkerIds, String message, boolean noScheduleData) {
        public Result(boolean verified, int requiredWorkers, int availableWorkers,
                      Set<Long> availableWorkerIds, String message) {
            this(verified, requiredWorkers, availableWorkers, availableWorkerIds, message, false);
        }

        public Result {
            Set<Long> safe = availableWorkerIds == null
                    ? Collections.emptySet()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(availableWorkerIds));
            availableWorkerIds = safe;
            availableWorkers = safe.size();
            message = message == null ? UNVERIFIED : message;
        }

        public boolean available() {
            return verified && availableWorkers >= requiredWorkers;
        }

        /** 窗口内没有任何排班记录: 未核对, 不是「人不够」。 */
        public static Result noScheduleData(Integer required) {
            int safeRequired = required == null ? 0 : Math.max(0, required);
            return new Result(false, safeRequired, 0, Collections.emptySet(), NO_SCHEDULE_DATA, true);
        }

        /** 产线没配标准人数: 没有可比的数, 不是「人够」也不是「人不够」。 */
        public static Result notConfigured() {
            return new Result(true, 0, 0, Collections.emptySet(), "未配置标准人数，不核对人数");
        }

        public boolean isNotConfigured() {
            return verified && requiredWorkers == 0;
        }

        public static Result unverified(Integer required, String message) {
            int safeRequired = required == null ? 0 : Math.max(0, required);
            return new Result(false, safeRequired, 0, Collections.emptySet(),
                    message == null ? UNVERIFIED : message);
        }
    }
}
