package com.cretas.aims.service.scheduling.impl;

import com.cretas.aims.dto.scheduling.*;
import com.cretas.aims.entity.*;
import com.cretas.aims.entity.rules.DroolsRule;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.*;
import com.cretas.aims.service.NotificationService;
import com.cretas.aims.service.PushNotificationService;
import com.cretas.aims.service.scheduling.core.LineScheduleService;
import com.cretas.aims.service.scheduling.core.SchedulingAIService;
import com.cretas.aims.service.scheduling.core.SchedulingPlanCrudService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 产线排程操作：查询、更新、开始、完成、进度更新（含效率计算与延期检测）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LineScheduleServiceImpl implements LineScheduleService {

    private final LineScheduleRepository scheduleRepository;
    private final SchedulingPlanRepository planRepository;
    private final ProductionLineRepository lineRepository;
    private final ProductionBatchRepository batchRepository;
    private final WorkerAssignmentRepository assignmentRepository;
    private final SchedulingAlertRepository alertRepository;
    private final UserRepository userRepository;
    private final DroolsRuleRepository droolsRuleRepository;
    private final NotificationService notificationService;
    private final PushNotificationService pushNotificationService;
    @Lazy
    private final SchedulingAIService schedulingAIService;
    @Lazy
    private final SchedulingPlanCrudService schedulingPlanCrudService;
    private final com.cretas.aims.repository.scheduling.CancelledPlanScheduleRepository cancelledPlanSchedules;

    private LineSchedule findScopedSchedule(String factoryId, String scheduleId) {
        LineSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new EntityNotFoundException("ProductionSchedule", scheduleId));
        SchedulingPlan plan = schedule.getPlan();
        if (plan == null || plan.getDeletedAt() != null || !factoryId.equals(plan.getFactoryId())) {
            throw new EntityNotFoundException("ProductionSchedule", scheduleId);
        }
        return schedule;
    }

    @Override
    public LineScheduleDTO getSchedule(String factoryId, String scheduleId) {
        LineSchedule schedule = findScopedSchedule(factoryId, scheduleId);
        return enrichScheduleDTO(schedule);
    }

    @Override
    @Transactional
    public LineScheduleDTO updateSchedule(String factoryId, String scheduleId, UpdateScheduleRequest request) {
        LineSchedule schedule = findScopedSchedule(factoryId, scheduleId);
        // 客户端回传了它读到的版本号: 与库里不一致 = 它读完之后别人改过, 拒绝 (⛔ 不覆盖、不自动重试)。
        // 抛与 Hibernate 版本冲突同一个异常, 统一由 GlobalExceptionHandler 转成 409「排程已被别人修改，请刷新后再改」。
        if (request.getVersion() != null && !request.getVersion().equals(schedule.getVersion())) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(LineSchedule.class, scheduleId);
        }

        if (request.getSequenceOrder() != null) {
            schedule.setSequenceOrder(request.getSequenceOrder());
        }
        if (request.getPlannedStartTime() != null) {
            schedule.setPlannedStartTime(request.getPlannedStartTime());
        }
        if (request.getPlannedEndTime() != null) {
            schedule.setPlannedEndTime(request.getPlannedEndTime());
        }
        if (request.getPlannedQuantity() != null) {
            schedule.setPlannedQuantity(request.getPlannedQuantity());
        }
        if (request.getNotes() != null) {
            schedule.setDelayReason(request.getNotes());
        }

        schedule = scheduleRepository.saveAndFlush(schedule); // 当场刷库: 版本冲突在这里抛, 返回的版本号是新的
        return enrichScheduleDTO(schedule);
    }

    @Override
    @Transactional
    public LineScheduleDTO startSchedule(String factoryId, String scheduleId) {
        LineSchedule schedule = findScopedSchedule(factoryId, scheduleId);
        // 先锁它所在的日调度计划, 再锁排程行: 与取消生产计划的级联、重开已取消的调度计划、插单落库同一顺序
        // (日计划 → 排程行)。反过来 (先锁行、提交时再写日计划) 会和取消级联互相等: 一边拿着行等日计划,
        // 另一边拿着日计划等行 —— 死锁, 其中一个请求 500。
        String dayPlanStatus = planRepository.lockStatusForUpdate(schedule.getPlan().getId(), factoryId).orElse(null);
        // 锁行读最新: 已作废 / 已取消的排程不能开工 (调度计划取消后重开时作废的旧排程, 开工就等于复活)。
        // 与作废那条 UPDATE 串行: 它先提交 → 这里读到 cancelled 拒绝; 这里先提交 → 它重判条件跳过这条。
        String current = scheduleRepository.lockStatusForUpdate(scheduleId).orElse(null);
        if (LineSchedule.ScheduleStatus.cancelled.name().equals(current)) {
            throw new BusinessException(409, "这条排程已作废，不能开工")
                    .withHint("请刷新调度计划，按当前的排程开工");
        }
        // D-S4-CANCEL-GHOST-DAILY: 所属生产计划 (直接或经批次) 已取消的排程, 开工就等于把取消了的计划复活。
        // 级联上线之前取消的计划, 它的排程还是待开始, 页面上仍有「开始」按钮 —— 在这里拦住。
        if (cancelledPlanSchedules.ownerPlanCancelled(factoryId, scheduleId)) {
            throw new BusinessException(409, "这条排程所属的生产计划已取消，不能开工")
                    .withHint("如需继续生产，请重新建生产计划再排程");
        }

        schedule.setStatus(LineSchedule.ScheduleStatus.in_progress);
        schedule.setActualStartTime(LocalDateTime.now());

        // 更新计划状态: 按锁住后读到的最新状态判, 只写状态列 (手里的日计划实体是锁之前读的, 整行保存会把
        // 等锁期间别人刚重算的批次数 / 派工人数写回旧值)
        if (SchedulingPlan.PlanStatus.confirmed.name().equals(dayPlanStatus)) {
            planRepository.markInProgressIfConfirmed(factoryId, schedule.getPlan().getId());
        }

        schedule = scheduleRepository.saveAndFlush(schedule); // 当场刷库: 版本冲突在这里抛, 返回的版本号是新的
        return enrichScheduleDTO(schedule);
    }

    @Override
    @Transactional
    public LineScheduleDTO completeSchedule(String factoryId, String scheduleId, Integer completedQuantity) {
        LineSchedule schedule = findScopedSchedule(factoryId, scheduleId);

        schedule.setStatus(LineSchedule.ScheduleStatus.completed);
        schedule.setActualEndTime(LocalDateTime.now());
        schedule.setCompletedQuantity(completedQuantity);

        // 计算实际效率
        if (schedule.getActualStartTime() != null && schedule.getPlannedQuantity() != null
            && schedule.getPlannedQuantity() > 0) {
            long minutes = java.time.Duration.between(schedule.getActualStartTime(),
                LocalDateTime.now()).toMinutes();
            if (minutes > 0) {
                BigDecimal efficiency = BigDecimal.valueOf(completedQuantity)
                    .divide(BigDecimal.valueOf(schedule.getPlannedQuantity()), 4, BigDecimal.ROUND_HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
                schedule.setActualEfficiency(efficiency);
            }
        }

        schedule = scheduleRepository.saveAndFlush(schedule); // 当场刷库: 版本冲突在这里抛, 返回的版本号是新的
        return enrichScheduleDTO(schedule);
    }

    @Override
    @Transactional
    public LineScheduleDTO updateProgress(String factoryId, String scheduleId, Integer completedQuantity) {
        LineSchedule schedule = findScopedSchedule(factoryId, scheduleId);

        schedule.setCompletedQuantity(completedQuantity);

        // ========== Phase 2: 效率计算与延期检测 ==========
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime startTime = schedule.getActualStartTime() != null
                ? schedule.getActualStartTime()
                : schedule.getPlannedStartTime();
            LocalDateTime endTime = schedule.getPlannedEndTime();

            if (startTime != null && endTime != null && schedule.getPlannedQuantity() != null
                && schedule.getPlannedQuantity() > 0) {

                long totalMinutes = java.time.Duration.between(startTime, endTime).toMinutes();
                long elapsedMinutes = java.time.Duration.between(startTime, now).toMinutes();

                if (totalMinutes > 0 && elapsedMinutes > 0) {
                    double timeProgressRatio = Math.min(1.0, (double) elapsedMinutes / totalMinutes);
                    int expectedByNow = (int) Math.ceil(schedule.getPlannedQuantity() * timeProgressRatio);
                    double actualEfficiency = expectedByNow > 0
                        ? (double) completedQuantity / expectedByNow
                        : 1.0;

                    schedule.setActualEfficiency(BigDecimal.valueOf(actualEfficiency));

                    log.info("效率计算: scheduleId={}, 已完成={}, 预期={}, 效率={}%",
                        scheduleId, completedQuantity, expectedByNow,
                        String.format("%.2f", actualEfficiency * 100));

                    double efficiencyThreshold = getEfficiencyThreshold(factoryId);

                    if (actualEfficiency < efficiencyThreshold
                        && schedule.getStatus() != LineSchedule.ScheduleStatus.delayed
                        && schedule.getStatus() != LineSchedule.ScheduleStatus.completed
                        && schedule.getStatus() != LineSchedule.ScheduleStatus.cancelled) {

                        schedule.setStatus(LineSchedule.ScheduleStatus.delayed);

                        String delayReason = String.format(
                            "效率低于预期: 当前效率 %.1f%% (阈值 %.0f%%)，已完成 %d / 预期 %d",
                            actualEfficiency * 100, efficiencyThreshold * 100,
                            completedQuantity, expectedByNow);
                        schedule.setDelayReason(delayReason);

                        log.warn("排程 {} 效率 {}% 低于阈值 {}%，已标记为延期",
                            scheduleId,
                            String.format("%.1f", actualEfficiency * 100),
                            String.format("%.0f", efficiencyThreshold * 100));

                        try {
                            notificationService.notifyScheduleDelayed(
                                factoryId, scheduleId, delayReason, actualEfficiency);
                        } catch (Exception notifyEx) {
                            log.error("发送延期通知失败: scheduleId={}", scheduleId, notifyEx);
                        }

                        try {
                            Map<String, Object> pushData = new HashMap<>();
                            pushData.put("type", "schedule_delayed");
                            pushData.put("scheduleId", scheduleId);
                            pushData.put("efficiency", actualEfficiency);
                            pushData.put("screen", "ScheduleProgressScreen");
                            pushNotificationService.sendToFactory(
                                factoryId,
                                "排程延期预警",
                                delayReason,
                                pushData
                            );
                            log.info("排程延期推送通知已发送: scheduleId={}", scheduleId);
                        } catch (Exception pushEx) {
                            log.error("发送延期推送通知失败: scheduleId={}", scheduleId, pushEx);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("效率计算失败: scheduleId={}", scheduleId, e);
        }

        schedule = scheduleRepository.saveAndFlush(schedule); // 当场刷库: 版本冲突在这里抛, 返回的版本号是新的

        // ========== 紧急状态监控 (基于完成概率) ==========
        try {
            CompletionProbabilityResponse probability =
                schedulingAIService.calculateCompletionProbability(factoryId, scheduleId);

            double urgentThreshold = schedulingPlanCrudService.getUrgentThreshold(factoryId);

            if (probability.getProbability() != null &&
                probability.getProbability().doubleValue() < urgentThreshold) {

                createUrgentAlert(factoryId, scheduleId, schedule.getPlanId(),
                    probability.getProbability().doubleValue(), urgentThreshold);

                log.warn("排程 {} 完成概率 {}% 低于紧急阈值 {}%，已创建告警",
                    scheduleId, probability.getProbability(), urgentThreshold * 100);
            }
        } catch (Exception e) {
            log.error("紧急状态监控失败: scheduleId={}", scheduleId, e);
        }

        return enrichScheduleDTO(schedule);
    }

    // ==================== 私有方法 ====================

    /**
     * 获取效率阈值配置（三级回退）
     */
    private double getEfficiencyThreshold(String factoryId) {
        Optional<DroolsRule> factoryRule = droolsRuleRepository
            .findByFactoryIdAndRuleGroupAndRuleName(factoryId, "scheduling", "efficiency_threshold");

        if (factoryRule.isPresent() && factoryRule.get().getEnabled()) {
            try {
                return Double.parseDouble(factoryRule.get().getRuleContent());
            } catch (NumberFormatException e) {
                log.warn("工厂 {} 的效率阈值配置格式错误: {}", factoryId,
                         factoryRule.get().getRuleContent(), e);
            }
        }

        Optional<DroolsRule> systemRule = droolsRuleRepository
            .findByFactoryIdAndRuleGroupAndRuleName("SYSTEM", "scheduling", "efficiency_threshold");

        if (systemRule.isPresent() && systemRule.get().getEnabled()) {
            try {
                return Double.parseDouble(systemRule.get().getRuleContent());
            } catch (NumberFormatException e) {
                log.warn("系统级效率阈值配置格式错误: {}", systemRule.get().getRuleContent(), e);
            }
        }

        return 0.8;
    }

    /**
     * 创建紧急告警
     */
    private void createUrgentAlert(String factoryId, String scheduleId, String planId,
                                  double currentProbability, double threshold) {
        SchedulingAlert alert = new SchedulingAlert();
        alert.setFactoryId(factoryId);
        alert.setScheduleId(scheduleId);
        alert.setPlanId(planId);
        alert.setAlertType(SchedulingAlert.AlertType.low_probability);
        alert.setSeverity(SchedulingAlert.Severity.warning);
        alert.setMessage(String.format("排程完成概率已降至 %.1f%%，低于紧急阈值 %.1f%%",
            currentProbability * 100, threshold * 100));
        alert.setSuggestedAction("建议调度员重新分析该批次，调整产线或人员配置");
        alert.setIsResolved(false);

        alertRepository.save(alert);

        try {
            String delayReason = String.format("完成概率 %.1f%% 低于阈值 %.1f%%",
                currentProbability * 100, threshold * 100);
            notificationService.notifyScheduleDelayed(factoryId, scheduleId, delayReason, currentProbability);
            log.info("延期预警通知已发送: scheduleId={}, probability={}", scheduleId, currentProbability);
        } catch (Exception e) {
            log.error("发送延期预警通知失败: scheduleId={}", scheduleId, e);
        }

        try {
            Map<String, Object> pushData = new HashMap<>();
            pushData.put("type", "urgent_alert");
            pushData.put("scheduleId", scheduleId);
            pushData.put("planId", planId);
            pushData.put("probability", currentProbability);
            pushData.put("screen", "SchedulingAlertScreen");
            pushNotificationService.sendToFactory(
                factoryId,
                "紧急告警 - 完成概率过低",
                alert.getMessage(),
                pushData
            );
            log.info("紧急告警推送通知已发送: scheduleId={}", scheduleId);
        } catch (Exception e) {
            log.error("发送紧急告警推送通知失败: scheduleId={}", scheduleId, e);
        }
    }

    private LineScheduleDTO enrichScheduleDTO(LineSchedule entity) {
        LineScheduleDTO dto = LineScheduleDTO.fromEntity(entity);

        lineRepository.findById(entity.getProductionLineId()).ifPresent(line ->
            dto.setProductionLineName(line.getName()));

        if (entity.getBatchId() != null) {
            batchRepository.findById(entity.getBatchId()).ifPresent(batch ->
                dto.setBatchNumber(batch.getBatchNumber()));
        }

        List<WorkerAssignment> assignments = assignmentRepository.findByScheduleId(entity.getId());
        dto.setWorkerAssignments(enrichAssignmentDTOs(assignments));

        return dto;
    }

    /**
     * Inline enrichment for single-schedule context to avoid circular dependency
     */
    private List<WorkerAssignmentDTO> enrichAssignmentDTOs(List<WorkerAssignment> assignments) {
        if (assignments == null || assignments.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Long> userIds = assignments.stream()
                .map(WorkerAssignment::getUserId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());

        Map<Long, User> userMap = userIds.isEmpty() ? Collections.emptyMap() :
                userRepository.findAllById(userIds).stream()
                        .collect(java.util.stream.Collectors.toMap(User::getId, java.util.function.Function.identity()));

        return assignments.stream().map(entity -> {
            WorkerAssignmentDTO dto = WorkerAssignmentDTO.fromEntity(entity);
            User user = userMap.get(entity.getUserId());
            if (user != null) {
                dto.setUserName(user.getFullName());
                dto.setUserPhone(user.getPhone());
            }
            if (entity.getSchedule() != null) {
                LineSchedule schedule = entity.getSchedule();
                dto.setScheduledStartTime(schedule.getPlannedStartTime());
                dto.setScheduledEndTime(schedule.getPlannedEndTime());
                lineRepository.findById(schedule.getProductionLineId()).ifPresent(line ->
                    dto.setProductionLineName(line.getName()));
                if (schedule.getBatchId() != null) {
                    batchRepository.findById(schedule.getBatchId()).ifPresent(batch ->
                        dto.setBatchNumber(batch.getBatchNumber()));
                }
            }
            return dto;
        }).collect(java.util.stream.Collectors.toList());
    }
}
