package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.dto.aps.GlobalDashboard;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.scheduling.*;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.aps.APSAdaptiveSchedulingService;
import com.cretas.aims.service.aps.DashboardAggregationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * APS自适应排产控制器
 *
 * 提供自适应排产相关API，包括：
 * - 任务进度上报与追踪
 * - 实时仪表盘
 * - 完成概率预测
 * - 自适应重排
 * - 策略权重调整
 *
 * @author Cretas APS
 * @since 2026-01-21
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/aps/adaptive")
@RequiredArgsConstructor
@Tag(name = "APS自适应排产", description = "自适应排产相关接口，包括进度追踪、预测、重排等功能")
@RequireModule("scheduling")
public class APSAdaptiveController {

    private final APSAdaptiveSchedulingService adaptiveService;
    private final DashboardAggregationService dashboardAggregationService;

    // ==================== 1. 进度上报 ====================

    /**
     * 更新任务进度
     */
    @RequirePermission({"scheduling:read_write"})
    @PostMapping("/tasks/{taskId}/progress")
    @Operation(summary = "更新任务进度", description = "上报任务当前完成数量和实际效率，系统将自动计算完成概率和风险等级")
    public ApiResponse<ProgressUpdateResponse> updateProgress(
            @Parameter(description = "工厂ID", example = "F001")
            @PathVariable String factoryId,
            @Parameter(description = "任务ID", example = "TASK-001")
            @PathVariable String taskId,
            @Valid @RequestBody ProgressUpdateRequest request) {
        log.info("更新任务进度: factoryId={}, taskId={}, completedQty={}, actualEfficiency={}",
                factoryId, taskId, request.getCompletedQty(), request.getActualEfficiency());

        requireOwnTask(factoryId, taskId);
        APSAdaptiveSchedulingService.ProgressUpdateResult result =
                adaptiveService.updateTaskProgress(taskId, request.getCompletedQty(), request.getActualEfficiency());

        ProgressUpdateResponse response = convertToResponse(result);
        return ApiResponse.success("进度更新成功", response);
    }

    // ==================== 2. 获取实时仪表盘 ====================

    /**
     * 获取自适应排产仪表盘 (增强版)
     */
    @GetMapping("/dashboard")
    @Operation(summary = "获取自适应排产仪表盘", description = "获取全局实时状态，包括任务统计、性能指标、产线状态、风险任务等")
    public ApiResponse<GlobalDashboard> getDashboard(
            @Parameter(description = "工厂ID", example = "F001")
            @PathVariable String factoryId) {
        log.info("获取自适应排产仪表盘: factoryId={}", factoryId);

        GlobalDashboard dashboard = dashboardAggregationService.generateDashboard(factoryId);
        return ApiResponse.success("获取成功", dashboard);
    }

    /**
     * 获取自适应排产仪表盘 (兼容旧版)
     */
    @GetMapping("/dashboard/v1")
    @Operation(summary = "获取自适应排产仪表盘(旧版)", description = "获取全局实时状态(旧版DTO格式)")
    public ApiResponse<AdaptiveDashboardDTO> getDashboardV1(
            @Parameter(description = "工厂ID", example = "F001")
            @PathVariable String factoryId) {
        log.info("获取自适应排产仪表盘(旧版): factoryId={}", factoryId);

        APSAdaptiveSchedulingService.GlobalDashboard dashboard = adaptiveService.getGlobalDashboard(factoryId);
        AdaptiveDashboardDTO dto = convertToDashboardDTO(dashboard);
        return ApiResponse.success("获取成功", dto);
    }

    // ==================== 3. 获取任务预测 ====================

    /**
     * 获取任务完成概率预测
     */
    @GetMapping("/tasks/{taskId}/prediction")
    @Operation(summary = "获取任务完成概率预测", description = "基于当前进度、效率趋势等因素预测任务准时完成概率")
    public ApiResponse<TaskPredictionDTO> getTaskPrediction(
            @Parameter(description = "工厂ID", example = "F001")
            @PathVariable String factoryId,
            @Parameter(description = "任务ID", example = "TASK-001")
            @PathVariable String taskId) {
        log.info("获取任务预测: factoryId={}, taskId={}", factoryId, taskId);

        requireOwnTask(factoryId, taskId);
        double probability = adaptiveService.predictCompletionProbability(taskId);
        APSAdaptiveSchedulingService.TaskRealTimeStatus status = adaptiveService.getTaskRealTimeStatus(taskId);

        TaskPredictionDTO dto = new TaskPredictionDTO();
        dto.setTaskId(taskId);
        dto.setCompletionProbability(probability);
        dto.setRiskLevel(status.getRiskLevel());
        dto.setPredictedEnd(status.getEstimatedEnd());
        dto.setEstimatedDelayMinutes(status.getDelayMinutes());

        return ApiResponse.success("获取成功", dto);
    }

    // ==================== 4. 检查是否需要重排 ====================

    /**
     * 检查是否需要重排
     */
    @GetMapping("/check-reschedule")
    @Operation(summary = "检查是否需要重排", description = "分析当前任务状态，判断是否需要触发自适应重排")
    public ApiResponse<RescheduleRecommendationDTO> checkReschedule(
            @Parameter(description = "工厂ID", example = "F001")
            @PathVariable String factoryId) {
        log.info("检查是否需要重排: factoryId={}", factoryId);

        APSAdaptiveSchedulingService.RescheduleRecommendation recommendation =
                adaptiveService.checkRescheduleNeed(factoryId);
        RescheduleRecommendationDTO dto = convertToRecommendationDTO(recommendation);
        return ApiResponse.success("检查完成", dto);
    }

    // ==================== 5. 触发自适应重排 ====================

    /**
     * 触发自适应重排
     */
    @RequirePermission({"scheduling:read_write"})
    @PostMapping("/reschedule")
    @Operation(summary = "触发自适应重排", description = "根据指定模式执行重排：full-全量重排，partial-仅重排受影响任务")
    public ApiResponse<AdaptiveRescheduleResponse> triggerReschedule(
            @Parameter(description = "工厂ID", example = "F001")
            @PathVariable String factoryId,
            @Valid @RequestBody AdaptiveRescheduleRequest request) {
        log.info("触发自适应重排: factoryId={}, mode={}, affectedTaskIds={}",
                factoryId, request.getMode(), request.getAffectedTaskIds());

        // full = 本厂全部进行中的任务 (服务层按本厂取, ⛔ 不再把 null 传下去); affected_only = 指定任务, 必须都属于本厂
        boolean full = "full".equals(request.getMode());
        List<String> taskIds = full ? List.of() : request.getAffectedTaskIds();
        if (!full && (taskIds == null || taskIds.isEmpty())) {
            throw new BusinessException(400, "affected_only 模式要给出受影响的任务");
        }

        APSAdaptiveSchedulingService.RescheduleSimulation simulation =
                adaptiveService.simulateReschedule(factoryId, taskIds);
        APSAdaptiveSchedulingService.SchedulingResult result =
                adaptiveService.adaptiveReschedule(factoryId, taskIds);

        AdaptiveRescheduleResponse response = new AdaptiveRescheduleResponse();
        response.setScheduleBatchNo("SCH-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        response.setRescheduledTasks(result.getScheduledTasks());
        double before = simulation.getBeforeOnTimeRate();
        response.setBeforeOnTimeRate(Double.isNaN(before) ? null : before);
        response.setAfterOnTimeRate(null);
        response.setImprovementPercent(null);
        response.setNote(result.getMessage());

        return ApiResponse.success("已登记调整标记, 排程时间未改动", response);
    }

    // ==================== 6. 手动调整策略权重 ====================

    /**
     * 手动触发策略权重调整
     */
    @RequirePermission({"scheduling:read_write"})
    @PostMapping("/adjust-weights")
    @Operation(summary = "手动触发策略权重调整", description = "根据历史效果数据自动调整各排产策略的权重")
    public ApiResponse<WeightAdjustmentResponse> adjustWeights(
            @Parameter(description = "工厂ID", example = "F001")
            @PathVariable String factoryId,
            HttpServletRequest http) {
        log.info("手动调整策略权重: factoryId={}", factoryId);
        // 这组策略权重是全平台共用的 (DEFAULT), 改了影响每一家工厂 ⇒ 只给能跨厂的平台角色
        boolean platform = AuthenticatedActor.current(http).map(AuthenticatedActor::hasFactoryBypass).orElse(false);
        if (!platform) {
            throw new BusinessException(403, "排产策略权重是全平台共用的, 只有平台管理员可以调整");
        }

        // 获取调整前权重
        Map<String, APSAdaptiveSchedulingService.StrategyEffectiveness> before =
                adaptiveService.evaluateStrategyEffectiveness();

        // 执行调整
        adaptiveService.autoAdjustStrategyWeights();

        // 获取调整后权重
        Map<String, APSAdaptiveSchedulingService.StrategyEffectiveness> after =
                adaptiveService.evaluateStrategyEffectiveness();

        // 构建响应
        WeightAdjustmentResponse response = new WeightAdjustmentResponse();
        response.setAdjustedAt(LocalDateTime.now());
        response.setPreviousWeights(before.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getCurrentWeight())));
        response.setNewWeights(after.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getCurrentWeight())));
        response.setReason("手动触发调整");

        return ApiResponse.success("权重调整完成", response);
    }

    // ==================== 7. 获取产线实时状态 ====================

    /**
     * 获取产线实时状态
     */
    @GetMapping("/lines/{lineId}/status")
    @Operation(summary = "获取产线实时状态", description = "获取指定产线的实时状态，包括效率、利用率、当前任务等")
    public ApiResponse<APSAdaptiveSchedulingService.LineRealTimeStatus> getLineStatus(
            @Parameter(description = "工厂ID", example = "F001")
            @PathVariable String factoryId,
            @Parameter(description = "产线ID", example = "LINE-001")
            @PathVariable String lineId) {
        log.info("获取产线状态: factoryId={}, lineId={}", factoryId, lineId);

        if (!adaptiveService.lineBelongsToFactory(factoryId, lineId)) {
            throw new BusinessException(404, "产线不存在或不属于本厂");
        }
        return ApiResponse.success("获取成功", adaptiveService.getLineRealTimeStatus(lineId));
    }

    // ==================== 8. 获取任务实时状态 ====================

    /**
     * 获取任务实时状态
     */
    @GetMapping("/tasks/{taskId}/status")
    @Operation(summary = "获取任务实时状态", description = "获取指定任务的实时状态，包括进度、效率、预计完成时间等")
    public ApiResponse<APSAdaptiveSchedulingService.TaskRealTimeStatus> getTaskStatus(
            @Parameter(description = "工厂ID", example = "F001")
            @PathVariable String factoryId,
            @Parameter(description = "任务ID", example = "TASK-001")
            @PathVariable String taskId) {
        log.info("获取任务状态: factoryId={}, taskId={}", factoryId, taskId);

        requireOwnTask(factoryId, taskId);
        return ApiResponse.success("获取成功", adaptiveService.getTaskRealTimeStatus(taskId));
    }

    // ==================== 9. 获取高风险任务列表 ====================

    /**
     * 获取高风险任务列表 (增强版 - 带分页)
     */
    @GetMapping("/risks")
    @Operation(summary = "获取高风险任务列表", description = "获取完成概率较低的任务列表，按风险程度排序")
    public ApiResponse<List<GlobalDashboard.RiskTask>> getRiskTasks(
            @Parameter(description = "工厂ID", example = "F001")
            @PathVariable String factoryId,
            @Parameter(description = "返回数量限制", example = "10")
            @RequestParam(defaultValue = "10") int limit) {
        log.info("获取高风险任务: factoryId={}, limit={}", factoryId, limit);

        List<GlobalDashboard.RiskTask> risks = dashboardAggregationService.getTopRiskTasks(factoryId, limit);
        return ApiResponse.success("获取成功", risks);
    }

    /**
     * 获取高风险任务列表 (旧版 - 按阈值过滤)
     */
    @GetMapping("/risks/v1")
    @Operation(summary = "获取高风险任务列表(旧版)", description = "获取完成概率低于指定阈值的任务列表")
    public ApiResponse<List<TaskRiskDTO>> getRiskTasksV1(
            @Parameter(description = "工厂ID", example = "F001")
            @PathVariable String factoryId,
            @Parameter(description = "概率阈值(0-1)，低于此值视为高风险", example = "0.7")
            @RequestParam(defaultValue = "0.7") double threshold) {
        log.info("获取高风险任务(旧版): factoryId={}, threshold={}", factoryId, threshold);

        List<APSAdaptiveSchedulingService.TaskRiskInfo> risks =
                adaptiveService.getLowProbabilityTasks(factoryId, threshold);
        List<TaskRiskDTO> dtos = risks.stream()
                .map(this::convertToTaskRiskDTO)
                .collect(Collectors.toList());
        return ApiResponse.success("获取成功", dtos);
    }

    // ==================== 10. 获取权重调整历史 ====================

    /**
     * 获取权重调整历史
     */
    @GetMapping("/weight-history")
    @Operation(summary = "获取权重调整历史", description = "获取最近N天的策略权重调整记录")
    public ApiResponse<List<APSAdaptiveSchedulingService.WeightAdjustmentRecord>> getWeightHistory(
            @Parameter(description = "工厂ID", example = "F001")
            @PathVariable String factoryId,
            @Parameter(description = "查询天数", example = "7")
            @RequestParam(defaultValue = "7") int days) {
        log.info("获取权重调整历史: factoryId={}, days={}", factoryId, days);

        return ApiResponse.success("获取成功", adaptiveService.getWeightAdjustmentHistory(days));
    }

    /** 按 id 的任务接口先确认任务属于路径上这家厂; 不属于和不存在一样 404 (⛔ 不透露别厂有这个任务)。 */
    private void requireOwnTask(String factoryId, String taskId) {
        if (!adaptiveService.taskBelongsToFactory(factoryId, taskId)) {
            throw new BusinessException(404, "任务不存在或不属于本厂");
        }
    }

    // ==================== 私有转换方法 ====================

    /**
     * 转换进度更新结果为响应DTO
     */
    private ProgressUpdateResponse convertToResponse(APSAdaptiveSchedulingService.ProgressUpdateResult result) {
        ProgressUpdateResponse response = new ProgressUpdateResponse();
        response.setTaskId(result.getTaskId());
        response.setPreviousProgress(result.getPreviousProgress());
        response.setCurrentProgress(result.getCurrentProgress());
        response.setCompletionProbability(result.getCompletionProbability());
        response.setNeedsAttention(result.isNeedsAttention());
        response.setMessage(result.getMessage());

        // 计算风险等级
        if (result.getCompletionProbability() >= 0.8) {
            response.setRiskLevel("low");
        } else if (result.getCompletionProbability() >= 0.5) {
            response.setRiskLevel("medium");
        } else {
            response.setRiskLevel("high");
        }

        return response;
    }

    /**
     * 转换全局仪表盘为DTO
     */
    private AdaptiveDashboardDTO convertToDashboardDTO(APSAdaptiveSchedulingService.GlobalDashboard dashboard) {
        AdaptiveDashboardDTO dto = new AdaptiveDashboardDTO();
        dto.setUpdateTime(dashboard.getUpdateTime());

        // 任务统计摘要
        AdaptiveDashboardDTO.SummaryStats summary = new AdaptiveDashboardDTO.SummaryStats();
        summary.setTotalActiveTasks(dashboard.getTotalActiveTasks());
        summary.setOnTrackTasks(dashboard.getOnTrackTasks());
        summary.setAtRiskTasks(dashboard.getAtRiskTasks());
        summary.setDelayedTasks(dashboard.getDelayedTasks());
        dto.setSummary(summary);

        // 性能指标
        AdaptiveDashboardDTO.PerformanceMetrics metrics = new AdaptiveDashboardDTO.PerformanceMetrics();
        metrics.setOverallOnTimeRate(dashboard.getOverallOnTimeRate());
        metrics.setAverageEfficiency(dashboard.getAverageEfficiency());
        metrics.setAverageCompletionProbability(dashboard.getAverageCompletionProbability());
        dto.setMetrics(metrics);

        // 产线统计
        AdaptiveDashboardDTO.LineStats lines = new AdaptiveDashboardDTO.LineStats();
        lines.setActive(dashboard.getActiveLines());
        lines.setIdle(dashboard.getIdleLines());
        lines.setMaintenance(dashboard.getMaintenanceLines());
        dto.setLines(lines);

        // 高风险任务
        if (dashboard.getTopRisks() != null) {
            dto.setTopRisks(dashboard.getTopRisks().stream()
                    .map(this::convertToTaskRiskDTO)
                    .collect(Collectors.toList()));
        }

        // 重排建议
        if (dashboard.getRescheduleRecommendation() != null) {
            dto.setRescheduleRecommendation(convertToRecommendationDTO(dashboard.getRescheduleRecommendation()));
        }

        return dto;
    }

    /**
     * 转换任务风险信息为DTO
     */
    private TaskRiskDTO convertToTaskRiskDTO(APSAdaptiveSchedulingService.TaskRiskInfo info) {
        TaskRiskDTO dto = new TaskRiskDTO();
        dto.setTaskId(info.getTaskId());
        dto.setTaskNo(info.getTaskNo());
        dto.setProductName(info.getProductName());
        dto.setLineName(info.getLineName());
        dto.setCompletionProbability(info.getCompletionProbability());
        dto.setEstimatedDelayMinutes(info.getEstimatedDelayMinutes());
        dto.setRiskLevel(info.getRiskLevel());
        dto.setRiskReason(info.getRiskReason());
        dto.setSuggestedActions(info.getSuggestedActions());
        return dto;
    }

    /**
     * 转换重排建议为DTO
     */
    private RescheduleRecommendationDTO convertToRecommendationDTO(
            APSAdaptiveSchedulingService.RescheduleRecommendation recommendation) {
        RescheduleRecommendationDTO dto = new RescheduleRecommendationDTO();
        dto.setNeedReschedule(recommendation.isNeedReschedule());
        dto.setUrgencyLevel(recommendation.getUrgencyLevel());
        dto.setReasons(recommendation.getReasons());
        dto.setAffectedTaskIds(recommendation.getAffectedTaskIds());
        dto.setEstimatedImprovementMinutes(recommendation.getEstimatedImprovementMinutes());
        dto.setExpectedOnTimeRateImprovement(recommendation.getExpectedOnTimeRateImprovement());
        return dto;
    }
}
