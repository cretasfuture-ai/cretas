package com.cretas.aims.repository.shortage;

import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.SchedulingPlan;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.repository.scheduling.CancelledPlanScheduleFilter;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 缺料重排要读写的排程任务与到货日期。都按工厂取; 放在缺料自己的仓库里, 不动排程 / 采购的共享仓库。
 */
@org.springframework.stereotype.Repository
public interface ShortageScheduleQueryRepository extends Repository<LineSchedule, String> {

    /**
     * 这张销售订单的生产计划下, 还没开工的排程任务, 按计划开始时间排。「没开工」= 任务 pending、没有实际开始、
     * 所属排程计划还在用 (草稿 / 已确认 / 执行中), 且<b>这个批次、这个生产计划都还没有报工</b> ——
     * 报工挂在批次 / 计划上, 从不回写排程任务, 只看任务状态会把已经在做的任务挪走。
     */
    @Query("SELECT ls FROM LineSchedule ls, ProductionPlan p WHERE ls.productionPlanId = p.id "
            + "AND p.factoryId = :factoryId AND p.sourceOrderId = :salesOrderId AND ls.plan.factoryId = :factoryId "
            + "AND ls.status = :pending AND ls.plan.status IN :livePlans "
            + "AND ls.actualStartTime IS NULL AND ls.plannedStartTime IS NOT NULL AND ls.plannedEndTime IS NOT NULL "
            + "AND NOT EXISTS (SELECT 1 FROM ProductionReport r WHERE r.factoryId = :factoryId AND r.deletedAt IS NULL "
            + "AND (r.batchId = ls.batchId OR r.batchId IN (SELECT b.id FROM ProductionBatch b "
            + "WHERE b.factoryId = :factoryId AND b.productionPlanId = ls.productionPlanId))) "
            + "ORDER BY ls.plannedStartTime")
    List<LineSchedule> findNotStarted(@Param("factoryId") String factoryId, @Param("salesOrderId") String salesOrderId,
                                      @Param("pending") LineSchedule.ScheduleStatus pending,
                                      @Param("livePlans") Collection<SchedulingPlan.PlanStatus> livePlans);

    default List<LineSchedule> findNotStartedForSalesOrder(String factoryId, String salesOrderId) {
        return findNotStarted(factoryId, salesOrderId, LineSchedule.ScheduleStatus.pending, java.util.EnumSet.of(
                SchedulingPlan.PlanStatus.draft, SchedulingPlan.PlanStatus.confirmed, SchedulingPlan.PlanStatus.in_progress));
    }

    /** 应用前把要改的任务锁住再比对基线, 免得比对完、写之前被别人改掉。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ls FROM LineSchedule ls WHERE ls.id IN :ids AND ls.plan.factoryId = :factoryId ORDER BY ls.id")
    List<LineSchedule> lockByIds(@Param("factoryId") String factoryId, @Param("ids") Collection<String> ids);

    /**
     * 这条线在 [start, end) 里与之重叠的其它在排 / 在做任务 (不含 exclude 里的)。
     * 所属生产计划已取消、自己还没开工的不算 ({@link CancelledPlanScheduleFilter})。
     */
    @Query("SELECT ls FROM LineSchedule ls WHERE ls.plan.factoryId = :factoryId AND ls.productionLineId = :lineId "
            + "AND ls.status IN :live "
            + "AND ls.plan.status IN :livePlans "
            + "AND ls.plannedStartTime < :end AND ls.plannedEndTime > :start AND ls.id NOT IN :exclude"
            + CancelledPlanScheduleFilter.AND_NOT_GHOST_OF_CANCELLED_PLAN)
    List<LineSchedule> findOverlappingIn(@Param("factoryId") String factoryId, @Param("lineId") String lineId,
                                         @Param("start") LocalDateTime start, @Param("end") LocalDateTime end,
                                         @Param("exclude") Collection<String> exclude,
                                         @Param("live") Collection<LineSchedule.ScheduleStatus> live,
                                         @Param("livePlans") Collection<SchedulingPlan.PlanStatus> livePlans,
                                         @Param("notStarted") LineSchedule.ScheduleStatus notStarted,
                                         @Param("cancelledPlan") ProductionPlanStatus cancelledPlan);

    default List<LineSchedule> findOverlapping(String factoryId, String lineId, LocalDateTime start, LocalDateTime end,
                                               Collection<String> exclude) {
        return findOverlappingIn(factoryId, lineId, start, end, exclude, java.util.EnumSet.of(
                LineSchedule.ScheduleStatus.pending, LineSchedule.ScheduleStatus.in_progress,
                LineSchedule.ScheduleStatus.delayed), java.util.EnumSet.of(
                SchedulingPlan.PlanStatus.draft, SchedulingPlan.PlanStatus.confirmed,
                SchedulingPlan.PlanStatus.in_progress), LineSchedule.ScheduleStatus.pending, ProductionPlanStatus.CANCELLED);
    }

    /**
     * 这种物料在走 (已提交到部分收货; 草稿不算 —— 还没下给供应商, 到货日不作数) 的采购单: [最晚预计到货日, 没填到货日的单数]。
     * 有单没填日期 ⇒ 到货日未知, 调用方不得据此顺延。
     */
    @Query("SELECT MAX(o.expectedDeliveryDate), SUM(CASE WHEN o.expectedDeliveryDate IS NULL THEN 1 ELSE 0 END), COUNT(o) "
            + "FROM PurchaseOrder o WHERE o.factoryId = :factoryId AND o.deletedAt IS NULL "
            + "AND o.status IN (com.cretas.aims.entity.enums.PurchaseOrderStatus.SUBMITTED, "
            + "com.cretas.aims.entity.enums.PurchaseOrderStatus.WORKFLOW_RUNNING, "
            + "com.cretas.aims.entity.enums.PurchaseOrderStatus.APPROVED, "
            + "com.cretas.aims.entity.enums.PurchaseOrderStatus.PENDING_FINANCE_REVIEW, "
            + "com.cretas.aims.entity.enums.PurchaseOrderStatus.FINANCE_APPROVED, "
            + "com.cretas.aims.entity.enums.PurchaseOrderStatus.PARTIAL_RECEIVED) "
            + "AND EXISTS (SELECT 1 FROM PurchaseOrderItem i WHERE i.purchaseOrderId = o.id AND i.deletedAt IS NULL "
            + "AND i.materialTypeId = :materialTypeId AND (i.quantity - COALESCE(i.receivedQuantity, 0)) > 0)")
    List<Object[]> findLiveOrderArrival(@Param("factoryId") String factoryId,
                                        @Param("materialTypeId") String materialTypeId);

    /** 这些生产计划下还没做完的排程任务 (含已开工); 所属排程计划还在用。交付风险按它们的计划完工时间比交期。 */
    @Query("SELECT ls FROM LineSchedule ls WHERE ls.productionPlanId IN :planIds AND ls.plan.factoryId = :factoryId "
            + "AND ls.status IN :live AND ls.plan.status IN :livePlans")
    List<LineSchedule> findLiveByPlanIdsIn(@Param("factoryId") String factoryId, @Param("planIds") Collection<String> planIds,
                                           @Param("live") Collection<LineSchedule.ScheduleStatus> live,
                                           @Param("livePlans") Collection<SchedulingPlan.PlanStatus> livePlans);

    default List<LineSchedule> findLiveByPlanIds(String factoryId, Collection<String> planIds) {
        return planIds.isEmpty() ? List.of() : findLiveByPlanIdsIn(factoryId, planIds, java.util.EnumSet.of(
                LineSchedule.ScheduleStatus.pending, LineSchedule.ScheduleStatus.in_progress,
                LineSchedule.ScheduleStatus.delayed), java.util.EnumSet.of(
                SchedulingPlan.PlanStatus.draft, SchedulingPlan.PlanStatus.confirmed, SchedulingPlan.PlanStatus.in_progress));
    }
}
