package com.cretas.aims.repository.shortage;

import com.cretas.aims.entity.LineSchedule;
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

    /** 这张销售订单的生产计划下, 还没开工 (pending、没有实际开始) 的排程任务, 按计划开始时间排。 */
    @Query("SELECT ls FROM LineSchedule ls, ProductionPlan p WHERE ls.productionPlanId = p.id "
            + "AND p.factoryId = :factoryId AND p.sourceOrderId = :salesOrderId AND ls.plan.factoryId = :factoryId "
            + "AND ls.status = :pending "
            + "AND ls.actualStartTime IS NULL AND ls.plannedStartTime IS NOT NULL AND ls.plannedEndTime IS NOT NULL "
            + "ORDER BY ls.plannedStartTime")
    List<LineSchedule> findNotStarted(@Param("factoryId") String factoryId, @Param("salesOrderId") String salesOrderId,
                                      @Param("pending") LineSchedule.ScheduleStatus pending);

    default List<LineSchedule> findNotStartedForSalesOrder(String factoryId, String salesOrderId) {
        return findNotStarted(factoryId, salesOrderId, LineSchedule.ScheduleStatus.pending);
    }

    /** 应用前把要改的任务锁住再比对基线, 免得比对完、写之前被别人改掉。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ls FROM LineSchedule ls WHERE ls.id IN :ids AND ls.plan.factoryId = :factoryId")
    List<LineSchedule> lockByIds(@Param("factoryId") String factoryId, @Param("ids") Collection<String> ids);

    /** 这条线在 [start, end) 里与之重叠的其它在排 / 在做任务 (不含 exclude 里的)。 */
    @Query("SELECT ls FROM LineSchedule ls WHERE ls.plan.factoryId = :factoryId AND ls.productionLineId = :lineId "
            + "AND ls.status IN :live "
            + "AND ls.plannedStartTime < :end AND ls.plannedEndTime > :start AND ls.id NOT IN :exclude")
    List<LineSchedule> findOverlappingIn(@Param("factoryId") String factoryId, @Param("lineId") String lineId,
                                         @Param("start") LocalDateTime start, @Param("end") LocalDateTime end,
                                         @Param("exclude") Collection<String> exclude,
                                         @Param("live") Collection<LineSchedule.ScheduleStatus> live);

    default List<LineSchedule> findOverlapping(String factoryId, String lineId, LocalDateTime start, LocalDateTime end,
                                               Collection<String> exclude) {
        return findOverlappingIn(factoryId, lineId, start, end, exclude, java.util.EnumSet.of(
                LineSchedule.ScheduleStatus.pending, LineSchedule.ScheduleStatus.in_progress,
                LineSchedule.ScheduleStatus.delayed));
    }

    /**
     * 这种物料在走 (草稿到部分收货) 的采购单: [最晚预计到货日, 没填到货日的单数]。
     * 有单没填日期 ⇒ 到货日未知, 调用方不得据此顺延。
     */
    @Query("SELECT MAX(o.expectedDeliveryDate), SUM(CASE WHEN o.expectedDeliveryDate IS NULL THEN 1 ELSE 0 END), COUNT(o) "
            + "FROM PurchaseOrder o WHERE o.factoryId = :factoryId AND o.deletedAt IS NULL "
            + "AND o.status IN (com.cretas.aims.entity.enums.PurchaseOrderStatus.DRAFT, "
            + "com.cretas.aims.entity.enums.PurchaseOrderStatus.SUBMITTED, "
            + "com.cretas.aims.entity.enums.PurchaseOrderStatus.WORKFLOW_RUNNING, "
            + "com.cretas.aims.entity.enums.PurchaseOrderStatus.APPROVED, "
            + "com.cretas.aims.entity.enums.PurchaseOrderStatus.PENDING_FINANCE_REVIEW, "
            + "com.cretas.aims.entity.enums.PurchaseOrderStatus.FINANCE_APPROVED, "
            + "com.cretas.aims.entity.enums.PurchaseOrderStatus.PARTIAL_RECEIVED) "
            + "AND EXISTS (SELECT 1 FROM PurchaseOrderItem i WHERE i.purchaseOrderId = o.id AND i.deletedAt IS NULL "
            + "AND i.materialTypeId = :materialTypeId AND (i.quantity - COALESCE(i.receivedQuantity, 0)) > 0)")
    List<Object[]> findLiveOrderArrival(@Param("factoryId") String factoryId,
                                        @Param("materialTypeId") String materialTypeId);

}
