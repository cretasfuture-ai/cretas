package com.cretas.aims.ai.tool.impl.scheduling;

import com.cretas.aims.dto.scheduling.SchedulingPlanDTO;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 排班计划进 Tool 结果前的扁平化: 只放标量字段与中文状态, 不带 DTO/实体对象图
 * (流式入口线程没有 Hibernate Session, 带出懒加载关联会「结果处理失败」)。
 */
public final class SchedulingPlanViews {

    private SchedulingPlanViews() {
    }

    public static String statusLabel(String status) {
        if (status == null) {
            return "未知";
        }
        return switch (status.toLowerCase()) {
            case "draft" -> "草稿";
            case "confirmed" -> "已确认";
            case "in_progress" -> "执行中";
            case "completed" -> "已完成";
            case "cancelled" -> "已取消";
            default -> "未知";
        };
    }

    public static Map<String, Object> toMap(SchedulingPlanDTO plan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", plan.getId());
        m.put("planName", plan.getPlanName());
        m.put("planDate", plan.getPlanDate() != null ? plan.getPlanDate().toString() : null);
        m.put("status", statusLabel(plan.getStatus()));
        m.put("totalBatches", plan.getTotalBatches());
        m.put("totalWorkers", plan.getTotalWorkers());
        m.put("pendingSchedules", plan.getPendingSchedules());
        m.put("inProgressSchedules", plan.getInProgressSchedules());
        m.put("completedSchedules", plan.getCompletedSchedules());
        return m;
    }
}
