package com.cretas.aims.repository;

import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.SchedulingPlan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 调度计划 Repository
 */
@Repository
public interface SchedulingPlanRepository extends JpaRepository<SchedulingPlan, String> {

    Optional<SchedulingPlan> findByFactoryIdAndPlanDateAndDeletedAtIsNull(
        String factoryId, LocalDate planDate);

    Optional<SchedulingPlan> findByIdAndFactoryIdAndDeletedAtIsNull(String id, String factoryId);

    /**
     * 锁住这一行并返回它<b>已提交的最新</b>状态 (重开已取消的调度计划前用)。
     * 原生 SQL: 不经持久化上下文, 同一事务早先读过的旧对象不会挡住最新状态。
     */
    @Query(value = "SELECT status FROM scheduling_plans WHERE id = :id AND factory_id = :factoryId "
            + "AND deleted_at IS NULL FOR UPDATE", nativeQuery = true)
    Optional<String> lockStatusForUpdate(@Param("id") String id, @Param("factoryId") String factoryId);

    /** 当天调度计划的 id / 状态 / 备注 (标量查询: 永远读库, 不经持久化上下文里可能过期的副本)。 */
    interface DayPlanHead {
        String getId();

        SchedulingPlan.PlanStatus getStatus();

        String getNotes();
    }

    @Query("SELECT sp.id AS id, sp.status AS status, sp.notes AS notes FROM SchedulingPlan sp "
            + "WHERE sp.factoryId = :factoryId AND sp.planDate = :planDate AND sp.deletedAt IS NULL")
    Optional<DayPlanHead> findDayPlanHead(@Param("factoryId") String factoryId, @Param("planDate") LocalDate planDate);

    /**
     * 同一天的调度计划行加悲观写锁 (SELECT ... FOR UPDATE), 只用来「排队」: 取得锁之后, 状态以
     * {@link #findDayPlanHead} 的标量读为准 (锁前若已把这个实体载入过持久化上下文, 这里返回的是旧副本)。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT sp FROM SchedulingPlan sp WHERE sp.factoryId = :factoryId AND sp.planDate = :planDate "
            + "AND sp.deletedAt IS NULL")
    Optional<SchedulingPlan> lockDayPlan(@Param("factoryId") String factoryId, @Param("planDate") LocalDate planDate);

    /**
     * 重开已取消的日调度计划前, 作废它名下还没开工的 pending 排程 (一条带条件的 UPDATE, 直接落库, 不经过持久化上下文里
     * 可能已过期的副本; 条件在数据库里判, 并发刚开工的行不会被改回)。返回作废条数。
     * (枚举用绑定参数: JPQL 里写内部枚举的全限定字面量 Hibernate 解析不了。)
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE LineSchedule ls SET ls.status = :to, ls.adjustmentReason = :reason "
            + "WHERE ls.planId = :planId AND ls.status = :from AND ls.actualStartTime IS NULL")
    int voidRowsNotStarted(@Param("planId") String planId, @Param("reason") String reason,
                           @Param("from") LineSchedule.ScheduleStatus from, @Param("to") LineSchedule.ScheduleStatus to);

    default int voidPendingRowsOnReopen(String planId, String reason) {
        return voidRowsNotStarted(planId, reason, LineSchedule.ScheduleStatus.pending, LineSchedule.ScheduleStatus.cancelled);
    }

    /** 名下有多少排程「已开工」(状态在 started 里, 或有实际开工时间且状态不在 terminal 里)。数据库里现数, 不读缓存。 */
    @Query("SELECT COUNT(ls) FROM LineSchedule ls WHERE ls.planId = :planId AND (ls.status IN :started "
            + "OR (ls.actualStartTime IS NOT NULL AND ls.status NOT IN :terminal))")
    long countRowsStarted(@Param("planId") String planId,
                          @Param("started") java.util.Collection<LineSchedule.ScheduleStatus> started,
                          @Param("terminal") java.util.Collection<LineSchedule.ScheduleStatus> terminal);

    default long countStartedRows(String planId) {
        return countRowsStarted(planId,
                java.util.List.of(LineSchedule.ScheduleStatus.in_progress, LineSchedule.ScheduleStatus.delayed),
                java.util.List.of(LineSchedule.ScheduleStatus.cancelled, LineSchedule.ScheduleStatus.completed));
    }

    /** 把已取消的日调度计划改回草稿 (只在它此刻仍是 cancelled 时生效), 清掉旧的确认人 / 确认时间, 写入留痕备注。 */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE SchedulingPlan sp SET sp.status = :to, sp.confirmedBy = null, sp.confirmedAt = null, "
            + "sp.notes = :notes, sp.updatedAt = :now WHERE sp.id = :id AND sp.status = :from")
    int reopenStatus(@Param("id") String id, @Param("notes") String notes, @Param("now") LocalDateTime now,
                     @Param("from") SchedulingPlan.PlanStatus from, @Param("to") SchedulingPlan.PlanStatus to);

    default int reopenCancelled(String id, String notes, LocalDateTime now) {
        return reopenStatus(id, notes, now, SchedulingPlan.PlanStatus.cancelled, SchedulingPlan.PlanStatus.draft);
    }

    /**
     * 只写调度计划的两个汇总数 (批次数 / 派工人数), 不碰状态等其他列: 调用方手里的实体可能是重开前的旧副本,
     * 整行保存会把旧状态写回去。
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE SchedulingPlan sp SET sp.totalBatches = :batches, sp.totalWorkers = :workers "
            + "WHERE sp.id = :id AND sp.factoryId = :factoryId")
    int writeTotals(@Param("factoryId") String factoryId, @Param("id") String id,
                    @Param("batches") Integer batches, @Param("workers") Integer workers);

    Page<SchedulingPlan> findByFactoryIdAndDeletedAtIsNullOrderByPlanDateDesc(
        String factoryId, Pageable pageable);

    List<SchedulingPlan> findByFactoryIdAndStatusAndDeletedAtIsNull(
        String factoryId, SchedulingPlan.PlanStatus status);

    @Query("SELECT sp FROM SchedulingPlan sp WHERE sp.factoryId = :factoryId " +
           "AND sp.planDate BETWEEN :startDate AND :endDate " +
           "AND sp.deletedAt IS NULL ORDER BY sp.planDate")
    List<SchedulingPlan> findByFactoryIdAndDateRange(
        @Param("factoryId") String factoryId,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate);

    @Query("SELECT sp FROM SchedulingPlan sp WHERE sp.factoryId = :factoryId " +
           "AND sp.planDate BETWEEN :startDate AND :endDate " +
           "AND sp.deletedAt IS NULL ORDER BY sp.planDate DESC")
    Page<SchedulingPlan> findByFactoryIdAndDateRangePaged(
        @Param("factoryId") String factoryId,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate,
        Pageable pageable);

    @Query("SELECT sp FROM SchedulingPlan sp WHERE sp.factoryId = :factoryId " +
           "AND sp.planDate BETWEEN :startDate AND :endDate " +
           "AND sp.status = :status " +
           "AND sp.deletedAt IS NULL ORDER BY sp.planDate DESC")
    Page<SchedulingPlan> findByFactoryIdAndDateRangeAndStatusPaged(
        @Param("factoryId") String factoryId,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate,
        @Param("status") SchedulingPlan.PlanStatus status,
        Pageable pageable);

    @Query("SELECT sp FROM SchedulingPlan sp WHERE sp.factoryId = :factoryId " +
           "AND sp.planDate BETWEEN :startDate AND :endDate " +
           "AND sp.status IN :statuses " +
           "AND sp.deletedAt IS NULL ORDER BY sp.planDate DESC")
    Page<SchedulingPlan> findByFactoryIdAndDateRangeAndStatusesPaged(
        @Param("factoryId") String factoryId,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate,
        @Param("statuses") List<SchedulingPlan.PlanStatus> statuses,
        Pageable pageable);

    @Query("SELECT sp FROM SchedulingPlan sp WHERE sp.factoryId = :factoryId " +
           "AND sp.status IN ('confirmed', 'in_progress') " +
           "AND sp.planDate >= :today " +
           "AND sp.deletedAt IS NULL ORDER BY sp.planDate")
    List<SchedulingPlan> findActivePlans(
        @Param("factoryId") String factoryId,
        @Param("today") LocalDate today);

    @Query("SELECT COUNT(sp) FROM SchedulingPlan sp WHERE sp.factoryId = :factoryId " +
           "AND sp.status = :status AND sp.deletedAt IS NULL")
    long countByFactoryIdAndStatus(
        @Param("factoryId") String factoryId,
        @Param("status") SchedulingPlan.PlanStatus status);

    @Query("SELECT sp FROM SchedulingPlan sp WHERE sp.factoryId = :factoryId " +
           "AND sp.planDate = :today AND sp.deletedAt IS NULL")
    Optional<SchedulingPlan> findTodayPlan(
        @Param("factoryId") String factoryId,
        @Param("today") LocalDate today);
}
