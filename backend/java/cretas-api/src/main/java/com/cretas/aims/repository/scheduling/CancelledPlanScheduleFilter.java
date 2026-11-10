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
 *   <li>「没开工」: 状态在 {@link com.cretas.aims.entity.LineSchedule#NOT_STARTED_STATUSES} (待开始 / 延误) 里且没有
 *       实际开始时间 —— 与调度计划重开时作废旧排程同一口径, 调用方把那份常量绑到 {@code :notStarted}。
 *       已开工的照常占线 (人和设备真在这条线上, 取消计划不会让它们自己停下, 要在调度计划里手工收尾)。</li>
 * </ul>
 *
 * <p>JPQL 片段, 只此一份 (两条占线查询、级联、开工守卫共用, 免得各写一遍后漂移)。要求查询里有别名 {@code ls}
 * (LineSchedule) 与参数 {@code :factoryId}、{@code :cancelledPlan}; 「没开工」那段另要 {@code :notStarted}。
 */
public final class CancelledPlanScheduleFilter {

    /** 这条排程的所属生产计划 (直接或经批次) 在本厂已取消。一个布尔表达式, 不带 AND。 */
    public static final String OWNER_PLAN_CANCELLED =
            "EXISTS (SELECT 1 FROM ProductionPlan gp WHERE gp.factoryId = :factoryId AND gp.status = :cancelledPlan "
            + "AND (gp.id = ls.productionPlanId OR gp.id IN (SELECT gb.productionPlanId FROM ProductionBatch gb "
            + "WHERE gb.id = ls.batchId AND gb.factoryId = :factoryId)))";

    /** 这条排程还没开工。一个布尔表达式, 不带 AND。 */
    public static final String NOT_STARTED = "(ls.status IN :notStarted AND ls.actualStartTime IS NULL)";

    /** 以 {@code AND} 开头, 直接拼在 WHERE 后面: 排除幽灵排程。 */
    public static final String AND_NOT_GHOST_OF_CANCELLED_PLAN =
            " AND NOT (" + NOT_STARTED + " AND " + OWNER_PLAN_CANCELLED + ") ";

    private CancelledPlanScheduleFilter() {
    }
}
