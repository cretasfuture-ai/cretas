package com.cretas.aims.dto.scheduling;

import com.cretas.aims.entity.SchedulingPlan;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 调度计划 DTO
 */
@Data
public class SchedulingPlanDTO {
    private String id;
    private String factoryId;
    private LocalDate planDate;
    private String planName;
    private String status;
    private Integer totalBatches;
    /** Distinct user IDs actually assigned across this plan's valid schedules; not required staffing. */
    private Integer totalWorkers;
    private Long createdBy;
    private String createdByName;
    private Long confirmedBy;
    private String confirmedByName;
    private LocalDateTime confirmedAt;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 关联数据
    private List<LineScheduleDTO> lineSchedules;

    /** 自动排程的提示: 按历史估算 / 人工与历史相差大 / 计划已完成不排等。只在生成那次返回, 不落库。 */
    private List<String> warnings;

    /** 自动排程结果: SCHEDULED = 已排出并落库; CAPACITY_REQUIRED = 有产品要先补日产能, 什么都没落库。 */
    public static final String OUTCOME_SCHEDULED = "SCHEDULED";
    public static final String OUTCOME_CAPACITY_REQUIRED = "CAPACITY_REQUIRED";

    /** 只在自动排程那次返回。 */
    private String outcome;

    /** outcome = CAPACITY_REQUIRED 时: 需要当场补日产能的产品 (id 为空, 没有落库)。 */
    private List<DailyCapacityRequiredDTO> capacityRequired;

    // 统计数据
    private Integer completedSchedules;
    private Integer inProgressSchedules;
    private Integer pendingSchedules;

    public static SchedulingPlanDTO fromEntity(SchedulingPlan entity) {
        SchedulingPlanDTO dto = new SchedulingPlanDTO();
        dto.setId(entity.getId());
        dto.setFactoryId(entity.getFactoryId());
        dto.setPlanDate(entity.getPlanDate());
        dto.setPlanName(entity.getPlanName());
        dto.setStatus(entity.getStatus() != null ? entity.getStatus().name() : null);
        dto.setTotalBatches(entity.getTotalBatches());
        dto.setTotalWorkers(entity.getTotalWorkers());
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setConfirmedBy(entity.getConfirmedBy());
        dto.setConfirmedAt(entity.getConfirmedAt());
        dto.setNotes(entity.getNotes());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }
}
