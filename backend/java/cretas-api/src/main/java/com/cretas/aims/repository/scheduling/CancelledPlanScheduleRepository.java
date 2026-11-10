package com.cretas.aims.repository.scheduling;

import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumSet;

/**
 * 生产计划取消后, 它名下排程任务的级联 (D-S4-CANCEL-GHOST-DAILY)。都按工厂取; 单独一个仓库, 不动排程的共享仓库。
 *
 * <p>「名下」= 任务直接挂这个生产计划, 或任务的批次属于这个生产计划; 与 {@link CancelledPlanScheduleFilter} 同口径。
 */
@org.springframework.stereotype.Repository
public interface CancelledPlanScheduleRepository extends Repository<LineSchedule, String> {

    /** 级联作废时写进 adjustment_reason 的原话; 也用它数「这次取消作废了几条」。 */
    String CASCADE_REASON = "所属生产计划已取消，未开工的排程随之取消";

    /**
     * 一条语句作废: 这个生产计划<b>此刻在库里已是已取消</b>时, 它名下 pending 且没有实际开始时间的排程任务置为 cancelled。
     * 条件写在 UPDATE 里: 与「开工」并发时, 已提交开工的那条在 PostgreSQL 重判条件后被跳过, 不会被作废。⛔ 不删行。
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE LineSchedule ls SET ls.status = :cancelled, ls.adjustmentReason = :reason, "
            + "ls.lastAdjustmentTime = :now "
            + "WHERE ls.status = :notStarted AND ls.actualStartTime IS NULL "
            + "AND ls.planId IN (SELECT sp.id FROM SchedulingPlan sp WHERE sp.factoryId = :factoryId) "
            + "AND (ls.productionPlanId = :productionPlanId OR ls.batchId IN (SELECT b.id FROM ProductionBatch b "
            + "WHERE b.factoryId = :factoryId AND b.productionPlanId = :productionPlanId)) "
            + "AND EXISTS (SELECT 1 FROM ProductionPlan p WHERE p.id = :productionPlanId AND p.factoryId = :factoryId "
            + "AND p.status = :cancelledPlan)")
    int cancelNotStartedRows(@Param("factoryId") String factoryId,
                             @Param("productionPlanId") String productionPlanId,
                             @Param("notStarted") LineSchedule.ScheduleStatus notStarted,
                             @Param("cancelled") LineSchedule.ScheduleStatus cancelled,
                             @Param("cancelledPlan") ProductionPlanStatus cancelledPlan,
                             @Param("reason") String reason,
                             @Param("now") LocalDateTime now);

    default int cancelNotStartedOfCancelledPlan(String factoryId, String productionPlanId, LocalDateTime now) {
        return cancelNotStartedRows(factoryId, productionPlanId, LineSchedule.ScheduleStatus.pending,
                LineSchedule.ScheduleStatus.cancelled, ProductionPlanStatus.CANCELLED, CASCADE_REASON, now);
    }

    /** 名下被取消级联作废的条数 (按 {@link #CASCADE_REASON} 认)。 */
    @Query("SELECT COUNT(ls) FROM LineSchedule ls WHERE ls.plan.factoryId = :factoryId "
            + "AND ls.status = :cancelled AND ls.adjustmentReason = :reason "
            + "AND (ls.productionPlanId = :productionPlanId OR ls.batchId IN (SELECT b.id FROM ProductionBatch b "
            + "WHERE b.factoryId = :factoryId AND b.productionPlanId = :productionPlanId))")
    long countCancelledWithReason(@Param("factoryId") String factoryId,
                                  @Param("productionPlanId") String productionPlanId,
                                  @Param("cancelled") LineSchedule.ScheduleStatus cancelled,
                                  @Param("reason") String reason);

    default long countCascadeCancelled(String factoryId, String productionPlanId) {
        return countCancelledWithReason(factoryId, productionPlanId, LineSchedule.ScheduleStatus.cancelled, CASCADE_REASON);
    }

    /** 名下已开工还没收尾的排程任务 (状态不是待开始, 或已有实际开始时间; 且没完工没取消)。级联不动它们。 */
    @Query("SELECT COUNT(ls) FROM LineSchedule ls WHERE ls.plan.factoryId = :factoryId "
            + "AND ls.status NOT IN :terminal AND (ls.status <> :notStarted OR ls.actualStartTime IS NOT NULL) "
            + "AND (ls.productionPlanId = :productionPlanId OR ls.batchId IN (SELECT b.id FROM ProductionBatch b "
            + "WHERE b.factoryId = :factoryId AND b.productionPlanId = :productionPlanId))")
    long countStartedRows(@Param("factoryId") String factoryId,
                          @Param("productionPlanId") String productionPlanId,
                          @Param("notStarted") LineSchedule.ScheduleStatus notStarted,
                          @Param("terminal") Collection<LineSchedule.ScheduleStatus> terminal);

    default long countStartedNotFinished(String factoryId, String productionPlanId) {
        return countStartedRows(factoryId, productionPlanId, LineSchedule.ScheduleStatus.pending,
                EnumSet.of(LineSchedule.ScheduleStatus.completed, LineSchedule.ScheduleStatus.cancelled));
    }

    /** 生产计划所属工厂 (审批回调入口只带计划 id)。 */
    @Query("SELECT p.factoryId FROM ProductionPlan p WHERE p.id = :productionPlanId")
    java.util.Optional<String> findFactoryIdOfPlan(@Param("productionPlanId") String productionPlanId);
}
