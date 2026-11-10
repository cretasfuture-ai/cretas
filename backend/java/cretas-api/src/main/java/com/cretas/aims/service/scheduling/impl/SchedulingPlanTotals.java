package com.cretas.aims.service.scheduling.impl;

import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.repository.LineScheduleRepository;
import com.cretas.aims.repository.SchedulingPlanRepository;

/**
 * 调度计划的两个汇总数, 按名下排程现算 (重开 / 插单之后用; 缺口 18 后续)。
 * <ul>
 *   <li>批次数 total_batches = 名下没作废的排程条数 (作废 = cancelled; 已完成的也算, 它确实是这一天的排程)。</li>
 *   <li>派工人数 total_workers = 这些排程上派了多少个不同的人 (作废排程上的派工不算, 与详情页同口径)。</li>
 * </ul>
 * 两个数都用标量查询读库, 查询前自动 flush, 不依赖持久化上下文里可能过期的排程副本。
 * 普通类, 不是 Spring Bean: 调用方用自己已注入的两个 repository 现场构造, 不改它们的依赖清单。
 */
public final class SchedulingPlanTotals {

    private final LineScheduleRepository scheduleRepository;
    private final SchedulingPlanRepository planRepository;

    public SchedulingPlanTotals(LineScheduleRepository scheduleRepository, SchedulingPlanRepository planRepository) {
        this.scheduleRepository = scheduleRepository;
        this.planRepository = planRepository;
    }

    public record Totals(int batches, int workers) {
    }

    /** 只算不写。 */
    public Totals count(String factoryId, String planId) {
        long batches = scheduleRepository.countByPlanIdAndStatusNot(planId, LineSchedule.ScheduleStatus.cancelled);
        long workers = scheduleRepository.countDistinctWorkersOnLiveRows(factoryId, planId,
                LineSchedule.ScheduleStatus.cancelled);
        return new Totals(Math.toIntExact(batches), Math.toIntExact(workers));
    }

    /**
     * 现算并只写这两列 (一条 UPDATE, 不整行保存)。调用方手里若还有这个调度计划的实体, 之后不要再整行保存它的旧汇总数。
     */
    public Totals recount(String factoryId, String planId) {
        Totals totals = count(factoryId, planId);
        planRepository.writeTotals(factoryId, planId, totals.batches(), totals.workers());
        return totals;
    }
}
