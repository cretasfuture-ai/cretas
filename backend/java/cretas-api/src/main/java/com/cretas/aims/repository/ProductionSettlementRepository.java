package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductionSettlement;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductionSettlementRepository extends JpaRepository<ProductionSettlement, String> {
    Optional<ProductionSettlement> findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
            String factoryId, String productionPlanId);

    List<ProductionSettlement> findByFactoryIdAndPostingStatusAndDeletedAtIsNull(
            String factoryId, String postingStatus);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ProductionSettlement s " +
            "WHERE s.factoryId = :factoryId " +
            "AND s.productionPlanId = :productionPlanId " +
            "AND s.deletedAt IS NULL")
    Optional<ProductionSettlement> findByFactoryIdAndProductionPlanIdForUpdate(
            @Param("factoryId") String factoryId,
            @Param("productionPlanId") String productionPlanId);

    Optional<ProductionSettlement> findByFactoryIdAndProductionPlanIdAndIdempotencyKeyAndDeletedAtIsNull(
            String factoryId, String productionPlanId, String idempotencyKey);

    /**
     * 结单时<b>没有回答</b>「这批有没有不良品」的结算记录 —— 盘点时的补录待办。
     *
     * <p>两种都算没回答，且必须分得开：</p>
     * <ul>
     *   <li>{@code defect_count_outcome IS NULL} —— <b>从没被问过</b>
     *       （2026-08-27 之前的存量结单，那时结单流程里没有这一问）</li>
     *   <li>{@code = NOT_COUNTED} —— <b>人主动答了「没清点」</b></li>
     * </ul>
     *
     * <p>⛔ {@code NONE_CONFIRMED}（已清点确认零）<b>不在</b>待办里 ——
     * 那是一个真实读数，不需要补录。这正是三态存在的意义。</p>
     */
    @Query("SELECT s FROM ProductionSettlement s "
           + "WHERE s.factoryId = :factoryId "
           + "AND s.deletedAt IS NULL "
           + "AND (s.defectCountOutcome IS NULL "
           + "     OR s.defectCountOutcome = com.cretas.aims.entity.enums.DefectCountOutcome.NOT_COUNTED) "
           + "ORDER BY s.settledAt DESC")
    List<ProductionSettlement> findPendingDefectCounts(@Param("factoryId") String factoryId);
}
