package com.cretas.aims.repository;

import com.cretas.aims.entity.SchedulingPlan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
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
