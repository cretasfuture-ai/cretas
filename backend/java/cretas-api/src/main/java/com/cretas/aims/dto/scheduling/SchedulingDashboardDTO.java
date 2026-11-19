package com.cretas.aims.dto.scheduling;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 调度 Dashboard DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchedulingDashboardDTO {
    private LocalDate date;

    // 计划统计
    private Integer totalPlans;
    private Integer confirmedPlans;
    private Integer inProgressPlans;
    private Integer completedPlans;

    // 排程统计
    private Integer totalSchedules;
    private Integer pendingSchedules;
    private Integer inProgressSchedules;
    private Integer completedSchedules;
    private Integer delayedSchedules;

    // 产能统计
    private Integer totalPlannedQuantity;
    private Integer totalCompletedQuantity;
    private BigDecimal overallCompletionRate;
    private BigDecimal averageEfficiency;

    // 人员统计
    private Integer totalWorkers;
    private Integer checkedInWorkers;
    private Integer temporaryWorkers;
    private Integer onLeaveWorkers;

    // 告警统计
    private Integer totalAlerts;
    private Integer criticalAlerts;
    private Integer unresolvedAlerts;

    // 产线状态
    private List<ProductionLineDTO> productionLines;

    // 实时排程
    private List<LineScheduleDTO> currentSchedules;

    // 未解决告警
    private List<SchedulingAlertDTO> alerts;

    // ===== web-admin 调度中心 / 实时监控读的形状 (web-admin/src/api/scheduling.ts SchedulingDashboard) =====
    // 口径见 SchedulingPlanCrudServiceImpl#getDashboard。量不到的指标给 null, 原因写进 unavailableReasons,
    // ⛔ 不要用 0 兜底 —— 0 和「真的是 0」在页面上长得一模一样。

    /** 当日调度计划 (scheduling_plans, 每厂每天至多一份, 已取消不计)。 */
    private PlanStats planStats;

    /** 当日排程上的派工 (worker_assignments); 工作流批次没有派工记录, 不计入。 */
    private WorkerStats workerStats;

    /** 产线占用。 */
    private LineStats lineStats;

    /** 今日排程: 当日调度计划的排程行 + 当天有活动、没被排程覆盖的工作流批次 (不重复计数)。 */
    private List<LineScheduleDTO> todaySchedules;

    /**
     * 跨日在产: 今天之前开工、此刻仍在产、今天没有任何活动的批次数 (不进今日排程与利用率)。
     * 只在查看今天时统计, 其它日期 → null。
     */
    private Integer carryOverIdleBatches;

    /**
     * 取值为 null 或口径受限的指标 → 原因 (键是字段路径, 如 "lineStats.utilizationRate";
     * "workerStats.workflowBatches" / "lineStats.unattributedBatches" 是口径说明, 对应字段不为 null)。
     */
    private Map<String, String> unavailableReasons;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlanStats {
        private Integer totalPlans;
        private Integer draftPlans;
        private Integer confirmedPlans;
        private Integer inProgressPlans;
        private Integer completedPlans;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkerStats {
        /** 派到当日排程上的不同工人数。 */
        private Integer totalAssigned;
        /** 其中已签到 (checked_in / working / checked_out) 的不同工人数。 */
        private Integer checkedIn;
        private Integer temporaryWorkers;
        /** 有绩效分 (1-100) 的派工的平均分; 一条都没有 → null。 */
        private BigDecimal averagePerformance;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineStats {
        /** 启用中 (status=active, 未删) 的产线数。 */
        private Integer activeLines;
        /** 今日有任务的启用产线数 (利用率的分子)。 */
        private Integer occupiedLines;
        /** 今日排程里没有产线归属的工作流批次数 (未计入利用率)。 */
        private Integer unattributedBatches;
        /** occupiedLines / activeLines, 0~1; 没有启用产线, 或今日任务全都没有产线归属 → null。 */
        private BigDecimal utilizationRate;
        /** 量不到 → null, 原因见 unavailableReasons。 */
        private BigDecimal averageEfficiency;
    }
}
