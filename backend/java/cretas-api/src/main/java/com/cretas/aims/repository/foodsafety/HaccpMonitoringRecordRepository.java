package com.cretas.aims.repository.foodsafety;

import com.cretas.aims.entity.foodsafety.HaccpMonitoringRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository — Sprint 8 P3 Phase A {@link HaccpMonitoringRecord}.
 *
 * <p>主要供 Skill `food-safety-recall` step 3 (haccp_checkpoint_review) 召回追溯使用;
 * 缺口 13 起也是工序报工 CCP 录入 / 偏离挡放行 / 批次 CCP 导出的数据源。
 */
@Repository
public interface HaccpMonitoringRecordRepository extends JpaRepository<HaccpMonitoringRecord, Long> {

    /** 查 batch 全部监控记录, 倒序 (最新优先). */
    List<HaccpMonitoringRecord> findByFactoryIdAndBatchNumberOrderByMonitoringTimeDesc(
            String factoryId, String batchNumber);

    /** 查 factory 全部偏离记录 (is_deviation=true), 用于异常告警 list. */
    List<HaccpMonitoringRecord> findByFactoryIdAndIsDeviationTrue(String factoryId);

    // ==================== 缺口 13 ====================

    /** 未处理偏离条数: 按批次号。 */
    @Query("SELECT COUNT(r) FROM HaccpMonitoringRecord r WHERE r.factoryId = :factoryId " +
            "AND r.isDeviation = true AND r.deviationHandledAt IS NULL AND r.batchNumber IN :batchNumbers")
    long countOpenDeviationsByBatchNumbers(@Param("factoryId") String factoryId,
                                           @Param("batchNumbers") Collection<String> batchNumbers);

    /** 未处理偏离条数: 按生产计划。 */
    @Query("SELECT COUNT(r) FROM HaccpMonitoringRecord r WHERE r.factoryId = :factoryId " +
            "AND r.isDeviation = true AND r.deviationHandledAt IS NULL AND r.productionPlanId IN :planIds")
    long countOpenDeviationsByPlanIds(@Param("factoryId") String factoryId,
                                      @Param("planIds") Collection<String> planIds);

    /**
     * 未处理偏离条数: 按计划 id 前缀 —— 逐道录入的常驻半成品锚 {@code CLK-SEMI-<计划前8位>-<产品前8位>}
     * 只带得出计划 id 的前 8 位。
     */
    @Query("SELECT COUNT(r) FROM HaccpMonitoringRecord r WHERE r.factoryId = :factoryId " +
            "AND r.isDeviation = true AND r.deviationHandledAt IS NULL " +
            "AND r.productionPlanId LIKE CONCAT(:planIdPrefix, '%')")
    long countOpenDeviationsByPlanIdPrefix(@Param("factoryId") String factoryId,
                                           @Param("planIdPrefix") String planIdPrefix);

    /** 批次 CCP 记录 (导出 / 查看): 批次号命中 或 同一生产计划, 按时间正序。 */
    @Query("SELECT r FROM HaccpMonitoringRecord r WHERE r.factoryId = :factoryId " +
            "AND (r.batchNumber IN :batchNumbers OR r.productionPlanId IN :planIds) " +
            "ORDER BY r.monitoringTime ASC, r.id ASC")
    List<HaccpMonitoringRecord> findForBatchView(@Param("factoryId") String factoryId,
                                                 @Param("batchNumbers") Collection<String> batchNumbers,
                                                 @Param("planIds") Collection<String> planIds);

    /** 处理偏离时加写锁 (两个质检员同时处理同一条)。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM HaccpMonitoringRecord r WHERE r.id = :id AND r.factoryId = :factoryId")
    Optional<HaccpMonitoringRecord> findByIdAndFactoryIdForUpdate(@Param("id") Long id,
                                                                  @Param("factoryId") String factoryId);

    /** 防双击: 同批次同 CCP 同值同人在时间窗内已有一条 → 视为重试。 */
    @Query("SELECT r FROM HaccpMonitoringRecord r WHERE r.factoryId = :factoryId " +
            "AND r.batchNumber = :batchNumber AND r.checkpointId = :checkpointId " +
            "AND r.operatorUserId = :operatorUserId AND r.measuredValue = :measuredValue " +
            "AND r.monitoringTime >= :since ORDER BY r.monitoringTime DESC")
    List<HaccpMonitoringRecord> findRecentDuplicates(@Param("factoryId") String factoryId,
                                                     @Param("batchNumber") String batchNumber,
                                                     @Param("checkpointId") Long checkpointId,
                                                     @Param("operatorUserId") Long operatorUserId,
                                                     @Param("measuredValue") java.math.BigDecimal measuredValue,
                                                     @Param("since") LocalDateTime since);
}
