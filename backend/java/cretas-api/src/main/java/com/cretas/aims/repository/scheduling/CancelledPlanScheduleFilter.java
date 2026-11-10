package com.cretas.aims.repository.scheduling;

/**
 * 「所属生产计划已取消、自己还没开工」的排程任务 —— 幽灵排程 (D-S4-CANCEL-GHOST-DAILY)。
 *
 * <p>生产计划取消后, 它名下没开工的排程任务不再占产线。取消时会把它们级联置为 cancelled
 * ({@link CancelledPlanScheduleRepository#cancelNotStartedOfCancelledPlan}); 这里的过滤兜住级联之前留下的存量,
 * 以及没走取消入口就被置为已取消的计划。
 *
 * <ul>
 *   <li>「所属生产计划」: 任务直接挂的 {@code production_plan_id}, 或任务批次所属的生产计划 (手工排程只有批次)。
 *       与 {@code ShortageScheduleQueryRepository.findNotStarted} 判「报工」用的两种归属一致。</li>
 *   <li>「没开工」: 状态 pending 且没有实际开始时间 —— 与级联作废的条件相同。已开工的照常占线
 *       (人和设备真在这条线上, 取消计划不会让它们自己停下, 要在调度计划里手工收尾)。</li>
 * </ul>
 *
 * <p>JPQL 片段, 由两条占线查询共用 (只此一份, 免得两处各写一遍后漂移)。要求查询里有别名 {@code ls}
 * (LineSchedule) 与参数 {@code :factoryId}、{@code :notStarted}、{@code :cancelledPlan}。
 */
public final class CancelledPlanScheduleFilter {

    /** 以 {@code AND} 开头, 直接拼在 WHERE 后面。 */
    public static final String AND_NOT_GHOST_OF_CANCELLED_PLAN =
            " AND NOT (ls.status = :notStarted AND ls.actualStartTime IS NULL AND EXISTS ("
            + "SELECT 1 FROM ProductionPlan gp WHERE gp.factoryId = :factoryId AND gp.status = :cancelledPlan "
            + "AND (gp.id = ls.productionPlanId OR gp.id IN (SELECT gb.productionPlanId FROM ProductionBatch gb "
            + "WHERE gb.id = ls.batchId AND gb.factoryId = :factoryId)))) ";

    private CancelledPlanScheduleFilter() {
    }
}
