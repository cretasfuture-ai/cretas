package com.cretas.aims.repository;

import com.cretas.aims.entity.SchedulingPlan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
     * 同一天的调度计划行加悲观写锁 (SELECT ... FOR UPDATE)。重开已取消的日调度计划前先锁, 锁到之后重读状态,
     * 并发的另一条路径 (紧急插单 / 自动排程) 已经重开就按「已存在」处理。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT sp FROM SchedulingPlan sp WHERE sp.factoryId = :factoryId AND sp.planDate = :planDate "
            + "AND sp.deletedAt IS NULL")
    Optional<SchedulingPlan> lockDayPlan(@Param("factoryId") String factoryId, @Param("planDate") LocalDate planDate);

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
