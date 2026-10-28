package com.cretas.aims.dto.scheduling;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 自动排程 (APS) 落库的一条排程: 计划级, 计划还没开工时没有批次。
 * 只在服务端内部构造, 不作为请求体接收。
 *
 * @param productionPlanId 必填: 这条排程对应的生产计划
 * @param batchId          可空: 已开工时为首个未完工批次
 * @param taskTimeline     每道工序 {taskName, processOrder, workProcessId, start, end, status}
 */
public record AutoScheduleItem(
        String productionLineId,
        String productionPlanId,
        Long batchId,
        int sequenceOrder,
        LocalDateTime plannedStartTime,
        LocalDateTime plannedEndTime,
        Integer plannedQuantity,
        List<Map<String, Object>> taskTimeline) {
}
