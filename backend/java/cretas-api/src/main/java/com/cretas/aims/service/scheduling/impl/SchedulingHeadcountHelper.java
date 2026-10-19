package com.cretas.aims.service.scheduling.impl;

import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.SchedulingPlan;
import com.cretas.aims.entity.WorkerAssignment;
import com.cretas.aims.repository.LineScheduleRepository;
import com.cretas.aims.repository.WorkerAssignmentRepository;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Shared tenant-bound derivation of a scheduling plan's actual headcount. */
final class SchedulingHeadcountHelper {
    private SchedulingHeadcountHelper() {
    }

    static int countDistinctAssignedWorkers(
            String factoryId,
            String planId,
            LineScheduleRepository scheduleRepository,
            WorkerAssignmentRepository assignmentRepository) {
        List<LineSchedule> schedules = safeSchedules(scheduleRepository.findByPlanId(planId));
        Map<String, LineSchedule> validSchedules = schedules.stream()
                .filter(schedule -> isValidSchedule(factoryId, planId, schedule))
                .collect(Collectors.toMap(LineSchedule::getId, schedule -> schedule, (first, ignored) -> first));
        if (validSchedules.isEmpty()) {
            return 0;
        }

        return safeAssignments(assignmentRepository.findByScheduleIdIn(validSchedules.keySet())).stream()
                .filter(assignment -> isAssignmentOnValidSchedule(
                        assignment, factoryId, planId, validSchedules))
                .map(WorkerAssignment::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .mapToInt(ignored -> 1)
                .sum();
    }

    static boolean isValidSchedule(String factoryId, String planId, LineSchedule schedule) {
        if (schedule == null || schedule.getId() == null || schedule.getPlan() == null) {
            return false;
        }
        SchedulingPlan plan = schedule.getPlan();
        String schedulePlanId = schedule.getPlanId() != null ? schedule.getPlanId() : plan.getId();
        return Objects.equals(planId, schedulePlanId)
                && Objects.equals(factoryId, plan.getFactoryId())
                && schedule.getStatus() != LineSchedule.ScheduleStatus.cancelled;
    }

    static boolean isAssignmentOnValidSchedule(
            WorkerAssignment assignment,
            String factoryId,
            String planId,
            Map<String, LineSchedule> validSchedules) {
        if (assignment == null || assignment.getUserId() == null) {
            return false;
        }
        String assignmentScheduleId = assignment.getScheduleId();
        if (assignmentScheduleId == null && assignment.getSchedule() != null) {
            assignmentScheduleId = assignment.getSchedule().getId();
        }
        if (!validSchedules.containsKey(assignmentScheduleId)) {
            return false;
        }
        return assignment.getSchedule() == null
                || (Objects.equals(assignmentScheduleId, assignment.getSchedule().getId())
                && isValidSchedule(factoryId, planId, assignment.getSchedule()));
    }

    private static List<LineSchedule> safeSchedules(List<LineSchedule> schedules) {
        return schedules == null ? Collections.emptyList() : schedules;
    }

    private static List<WorkerAssignment> safeAssignments(List<WorkerAssignment> assignments) {
        return assignments == null ? Collections.emptyList() : assignments;
    }
}
