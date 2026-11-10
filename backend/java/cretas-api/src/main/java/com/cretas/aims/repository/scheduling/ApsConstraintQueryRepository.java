package com.cretas.aims.repository.scheduling;

import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.SchedulingPlan;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;

/**
 * 自动排程约束 (食品 DAG 缺口 18) 要读的东西: 已落库还在用的排程任务、在走采购单还没到的量。
 * 都按工厂取; 单独一个仓库, 不动排程 / 采购的共享仓库。
 */
@org.springframework.stereotype.Repository
public interface ApsConstraintQueryRepository extends Repository<LineSchedule, String> {

    /**
     * 本厂还在用 (草稿 / 已确认 / 执行中) 的调度计划里, 没完工没取消、结束时间不早于 {@code from} 的排程任务。
     * 这些是新排程要绕开的固定占用 (自动排的与人工改过的不区分)。
     */
    @Query("SELECT ls FROM LineSchedule ls WHERE ls.plan.factoryId = :factoryId AND ls.plan.deletedAt IS NULL "
            + "AND ls.status IN :live AND ls.plan.status IN :livePlans "
            + "AND ls.plannedStartTime IS NOT NULL AND ls.plannedEndTime IS NOT NULL AND ls.plannedEndTime >= :from "
            + "ORDER BY ls.plannedStartTime, ls.id")
    List<LineSchedule> findLiveEndingFromIn(@Param("factoryId") String factoryId, @Param("from") LocalDateTime from,
                                            @Param("live") Collection<LineSchedule.ScheduleStatus> live,
                                            @Param("livePlans") Collection<SchedulingPlan.PlanStatus> livePlans);

    /**
     * 这次要重开的那个已取消调度计划里<b>已开工</b>的排程 (执行中, 或已有实际开始时间, 且没完工没取消):
     * 重开后它们保留并重新算占用, 所以排之前就当固定占用, 不能等重开之后才发现撞了。
     * 与 {@code SchedulingPlanCrudServiceImpl.notStarted} 互补: 没开工的那些会被作废, 不算。
     */
    @Query("SELECT ls FROM LineSchedule ls WHERE ls.plan.id = :planId AND ls.plan.factoryId = :factoryId "
            + "AND ls.plan.deletedAt IS NULL AND ls.status IN :live "
            + "AND (ls.actualStartTime IS NOT NULL OR ls.status = :inProgress) "
            + "AND ls.plannedStartTime IS NOT NULL AND ls.plannedEndTime IS NOT NULL AND ls.plannedEndTime >= :from "
            + "ORDER BY ls.plannedStartTime, ls.id")
    List<LineSchedule> findStartedEndingFromInPlan(@Param("factoryId") String factoryId,
                                                   @Param("planId") String planId,
                                                   @Param("from") LocalDateTime from,
                                                   @Param("live") Collection<LineSchedule.ScheduleStatus> live,
                                                   @Param("inProgress") LineSchedule.ScheduleStatus inProgress);

    default List<LineSchedule> findKeptOnReopen(String factoryId, String reopeningPlanId, LocalDateTime from) {
        return findStartedEndingFromInPlan(factoryId, reopeningPlanId, from,
                EnumSet.of(LineSchedule.ScheduleStatus.pending, LineSchedule.ScheduleStatus.in_progress,
                        LineSchedule.ScheduleStatus.delayed),
                LineSchedule.ScheduleStatus.in_progress);
    }

    default List<LineSchedule> findLiveEndingFrom(String factoryId, LocalDateTime from) {
        return findLiveEndingFromIn(factoryId, from,
                EnumSet.of(LineSchedule.ScheduleStatus.pending, LineSchedule.ScheduleStatus.in_progress,
                        LineSchedule.ScheduleStatus.delayed),
                EnumSet.of(SchedulingPlan.PlanStatus.draft, SchedulingPlan.PlanStatus.confirmed,
                        SchedulingPlan.PlanStatus.in_progress));
    }

    /**
     * 这种原料在走的采购单 (已提交到部分收货; 草稿不算, 与缺料重排同口径) 上还没到的量:
     * [预计到货日 (可空), 未到量, 采购单位, 库存基本单位快照, 包装→基本单位系数快照]。
     * 按包装单位 (箱 / 件) 下的单, 用下单时的快照系数折成库存基本单位。
     */
    @Query("SELECT o.expectedDeliveryDate, (i.quantity - COALESCE(i.receivedQuantity, 0)), i.unit, "
            + "i.inventoryBaseUnitSnapshot, i.packageToBaseFactorSnapshot "
            + "FROM PurchaseOrder o, PurchaseOrderItem i WHERE i.purchaseOrderId = o.id "
            + "AND o.factoryId = :factoryId AND o.deletedAt IS NULL AND i.deletedAt IS NULL "
            + "AND i.materialTypeId = :materialTypeId "
            + "AND o.status IN (com.cretas.aims.entity.enums.PurchaseOrderStatus.SUBMITTED, "
            + "com.cretas.aims.entity.enums.PurchaseOrderStatus.WORKFLOW_RUNNING, "
            + "com.cretas.aims.entity.enums.PurchaseOrderStatus.APPROVED, "
            + "com.cretas.aims.entity.enums.PurchaseOrderStatus.PENDING_FINANCE_REVIEW, "
            + "com.cretas.aims.entity.enums.PurchaseOrderStatus.FINANCE_APPROVED, "
            + "com.cretas.aims.entity.enums.PurchaseOrderStatus.PARTIAL_RECEIVED) "
            + "AND (i.quantity - COALESCE(i.receivedQuantity, 0)) > 0")
    List<Object[]> findOpenPurchaseQuantities(@Param("factoryId") String factoryId,
                                              @Param("materialTypeId") String materialTypeId);
}
