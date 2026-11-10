package com.cretas.aims.repository;

import com.cretas.aims.entity.LineSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 产线排程 Repository
 */
@Repository
public interface LineScheduleRepository extends JpaRepository<LineSchedule, String> {

    List<LineSchedule> findByPlanId(String planId);

    List<LineSchedule> findByPlanIdOrderBySequenceOrder(String planId);

    Optional<LineSchedule> findByIdAndPlanId(String id, String planId);

    /** 按 id 取任务, 但只取路径上这家工厂的 (任务经 plan 归属工厂)。 */
    @Query("SELECT ls FROM LineSchedule ls WHERE ls.id = :id AND ls.plan.factoryId = :factoryId")
    Optional<LineSchedule> findByIdAndFactoryId(@Param("id") String id, @Param("factoryId") String factoryId);

    List<LineSchedule> findByProductionLineIdAndStatus(
        String productionLineId, LineSchedule.ScheduleStatus status);

    @Query("SELECT ls FROM LineSchedule ls WHERE ls.plan.factoryId = :factoryId " +
           "AND ls.status = :status ORDER BY ls.plannedStartTime")
    List<LineSchedule> findByFactoryIdAndStatus(
        @Param("factoryId") String factoryId,
        @Param("status") LineSchedule.ScheduleStatus status);

    @Query("SELECT ls FROM LineSchedule ls WHERE ls.productionLineId = :lineId " +
           "AND ls.plannedStartTime BETWEEN :start AND :end " +
           "ORDER BY ls.plannedStartTime")
    List<LineSchedule> findByProductionLineAndTimeRange(
        @Param("lineId") String lineId,
        @Param("start") LocalDateTime start,
        @Param("end") LocalDateTime end);

    /**
     * 重开已取消的调度计划时, 一条语句作废名下没开工的旧排程 (待开始 / 延误, 且没有实际开始时间)。
     * 条件写在 UPDATE 里: 与「开工」并发时, 已提交开工的那条在 PostgreSQL 重判条件后被跳过, 不会被作废。
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE LineSchedule ls SET ls.status = :cancelled, ls.adjustmentReason = :reason, "
            + "ls.lastAdjustmentTime = :now WHERE ls.planId = :planId AND ls.status IN :notStarted "
            + "AND ls.actualStartTime IS NULL")
    int voidNotStartedRows(@Param("planId") String planId,
                           @Param("notStarted") java.util.Collection<LineSchedule.ScheduleStatus> notStarted,
                           @Param("cancelled") LineSchedule.ScheduleStatus cancelled,
                           @Param("reason") String reason,
                           @Param("now") LocalDateTime now);

    @Query("SELECT COUNT(ls) FROM LineSchedule ls WHERE ls.planId = :planId AND ls.status <> :cancelled")
    long countByPlanIdAndStatusNot(@Param("planId") String planId,
                                   @Param("cancelled") LineSchedule.ScheduleStatus cancelled);

    /**
     * 调度计划名下没作废的排程上派了多少个不同的人 (与 SchedulingHeadcountHelper 同口径: 作废的排程上的派工不算)。
     * 标量查询, 读库现数, 不经持久化上下文里可能过期的排程副本。
     */
    @Query("SELECT COUNT(DISTINCT wa.userId) FROM WorkerAssignment wa WHERE wa.userId IS NOT NULL AND wa.scheduleId IN "
            + "(SELECT ls.id FROM LineSchedule ls WHERE ls.planId = :planId AND ls.plan.factoryId = :factoryId "
            + "AND ls.status <> :cancelled)")
    long countDistinctWorkersOnLiveRows(@Param("factoryId") String factoryId, @Param("planId") String planId,
                                        @Param("cancelled") LineSchedule.ScheduleStatus cancelled);

    /** 锁住这条排程并返回它已提交的最新状态 (原生 SQL, 不经持久化上下文)。 */
    @Query(value = "SELECT status FROM line_schedules WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<String> lockStatusForUpdate(@Param("id") String id);

    @Query("SELECT COUNT(ls) FROM LineSchedule ls WHERE ls.planId = :planId " +
           "AND ls.status = :status")
    long countByPlanIdAndStatus(
        @Param("planId") String planId,
        @Param("status") LineSchedule.ScheduleStatus status);

    @Query("SELECT ls FROM LineSchedule ls WHERE ls.plan.factoryId = :factoryId " +
           "AND ls.status = 'delayed' ORDER BY ls.plannedEndTime")
    List<LineSchedule> findDelayedSchedules(@Param("factoryId") String factoryId);

    @Query("SELECT ls FROM LineSchedule ls WHERE ls.plan.factoryId = :factoryId " +
           "AND ls.status = 'in_progress' ORDER BY ls.plannedEndTime")
    List<LineSchedule> findInProgressSchedules(@Param("factoryId") String factoryId);

    /**
     * 按状态查询所有排程（用于定时任务延期检测）
     */
    List<LineSchedule> findByStatus(LineSchedule.ScheduleStatus status);

    /**
     * 根据计划ID删除所有产线排程（用于重新生成排程）
     */
    void deleteByPlanId(String planId);

    /**
     * 查询分配给某车间主任的排程任务
     */
    List<LineSchedule> findBySupervisorIdAndStatusIn(
        Long supervisorId, List<LineSchedule.ScheduleStatus> statuses);

    /**
     * 查询分配给某车间主任的所有排程
     */
    List<LineSchedule> findBySupervisorId(Long supervisorId);

    /**
     * 查询某工厂分配给某车间主任的排程任务
     */
    @Query("SELECT ls FROM LineSchedule ls WHERE ls.plan.factoryId = :factoryId " +
           "AND ls.supervisorId = :supervisorId " +
           "AND ls.status IN :statuses " +
           "ORDER BY ls.plannedStartTime")
    List<LineSchedule> findBySupervisorAndStatuses(
        @Param("factoryId") String factoryId,
        @Param("supervisorId") Long supervisorId,
        @Param("statuses") List<LineSchedule.ScheduleStatus> statuses);

    /**
     * 查询某工厂分配给某车间主任的待处理任务
     */
    @Query("SELECT ls FROM LineSchedule ls WHERE ls.plan.factoryId = :factoryId " +
           "AND ls.supervisorId = :supervisorId " +
           "AND ls.status = 'pending' " +
           "ORDER BY ls.plannedStartTime")
    List<LineSchedule> findPendingTasksBySupervisor(
        @Param("factoryId") String factoryId,
        @Param("supervisorId") Long supervisorId);
}
