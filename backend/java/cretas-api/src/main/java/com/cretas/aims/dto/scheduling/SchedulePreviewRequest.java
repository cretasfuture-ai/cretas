package com.cretas.aims.dto.scheduling;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 只算不落库的自动排程入参 (食品 DAG 缺口 18; 插单 / 局部重排调用)。
 *
 * @param firstDay               从哪天开始排
 * @param productionLineId       排在哪条线; 空 = 工厂唯一的活跃产线 (多条时必须指定, 同 generate)
 * @param planIds                要排的生产计划; <b>顺序是兜底优先级</b>: 引擎先按计划预计完成日 (交期) 早的排,
 *                               没有交期的排在后面并保持这里的顺序。插单要绝对优先时用 {@code pinFirstPlanIds}。
 * @param pinFirstPlanIds        这几个计划不论交期都排在最前面 (按 planIds 里的先后); 必须是 planIds 的子集, 可空
 * @param movableLineScheduleIds 这次允许挪的已有排程任务: 引擎不把它们当固定占用。对应的计划要放进 planIds 才会被重排
 * @param dailyCapacityOverrides 补的日产能, 只用于这次计算, ⛔ 不写库 (generate 才会保存到产品)
 */
public record SchedulePreviewRequest(LocalDate firstDay, String productionLineId, List<String> planIds,
                                     List<String> pinFirstPlanIds, Set<String> movableLineScheduleIds,
                                     List<DailyCapacityOverride> dailyCapacityOverrides) {
}
