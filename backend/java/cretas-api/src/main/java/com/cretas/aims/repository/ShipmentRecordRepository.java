package com.cretas.aims.repository;

import com.cretas.aims.entity.ShipmentRecord;
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
 * 出货记录数据访问层
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Repository
public interface ShipmentRecordRepository extends JpaRepository<ShipmentRecord, String> {

    /**
     * 根据工厂ID分页查询出货记录
     */
    Page<ShipmentRecord> findByFactoryIdOrderByShipmentDateDesc(String factoryId, Pageable pageable);

    /**
     * 防呆 R4 (幂等防双击, edge-case 审计 2026-06-24): 60s 窗口内同 工厂/客户/数量/经手人 的出货 → 视为重复点击。
     * 键含 recordedBy (reviewer must-fix: 否则 customer+quantity 太宽, 会拦不同经手人对同客户出同量的合法分单)。
     * 误拦仅"同一经手人 60s 内对同客户出完全相同数量"(几乎必是双击)。
     */
    @Query("SELECT s FROM ShipmentRecord s WHERE s.factoryId = ?1 AND s.customerId = ?2 "
            + "AND s.quantity = ?3 AND s.recordedBy = ?4 AND s.deletedAt IS NULL "
            + "AND s.createdAt >= ?5 ORDER BY s.createdAt DESC")
    List<ShipmentRecord> findRecentDuplicateShipments(String factoryId, String customerId,
            java.math.BigDecimal quantity, Long recordedBy, java.time.LocalDateTime cutoff);

    /**
     * 出货记录列表的条件筛选 (客户 / 状态 / 出货日期), 全部在分页之前由数据库过滤 (S6 验收 2026-11-03:
     * 「上个月的出货记录」不按日期筛; 以前日期一条路径、状态一条路径、客户一条路径, 同时给就静默丢掉其余条件)。
     * 每个条件可选, 用布尔标志 + 永远非空的值 (null 的 LocalDate 在 PG 上绑不出类型, 见 #1357)。
     */
    default Page<ShipmentRecord> searchFiltered(String factoryId, String customerId, String status,
                                                java.time.LocalDate shipmentDateFrom,
                                                java.time.LocalDate shipmentDateTo, Pageable pageable) {
        return searchFilteredBound(factoryId,
                customerId != null, customerId != null ? customerId : "",
                status != null, status != null ? status : "",
                shipmentDateFrom != null, shipmentDateFrom != null ? shipmentDateFrom : java.time.LocalDate.of(1970, 1, 1),
                shipmentDateTo != null, shipmentDateTo != null ? shipmentDateTo : java.time.LocalDate.of(9999, 12, 31),
                pageable);
    }

    /** 只由 {@link #searchFiltered} 调用: 值参数都非空, 是否生效看布尔标志。 */
    @Query("SELECT s FROM ShipmentRecord s WHERE s.factoryId = :factoryId " +
           "AND (:byCustomer = false OR s.customerId = :customerId) " +
           "AND (:byStatus = false OR s.status = :status) " +
           "AND (:byFrom = false OR s.shipmentDate >= :shipmentDateFrom) " +
           "AND (:byTo = false OR s.shipmentDate <= :shipmentDateTo) " +
           "ORDER BY s.shipmentDate DESC")
    Page<ShipmentRecord> searchFilteredBound(@Param("factoryId") String factoryId,
                                             @Param("byCustomer") boolean byCustomer,
                                             @Param("customerId") String customerId,
                                             @Param("byStatus") boolean byStatus,
                                             @Param("status") String status,
                                             @Param("byFrom") boolean byFrom,
                                             @Param("shipmentDateFrom") java.time.LocalDate shipmentDateFrom,
                                             @Param("byTo") boolean byTo,
                                             @Param("shipmentDateTo") java.time.LocalDate shipmentDateTo,
                                             Pageable pageable);

    /**
     * 根据工厂ID和状态查询出货记录
     */
    Page<ShipmentRecord> findByFactoryIdAndStatusOrderByShipmentDateDesc(
            String factoryId, String status, Pageable pageable);

    /**
     * 根据工厂ID和客户ID查询出货记录
     */
    List<ShipmentRecord> findByFactoryIdAndCustomerIdOrderByShipmentDateDesc(
            String factoryId, String customerId);

    /**
     * 根据工厂ID和日期范围查询出货记录
     */
    @Query("SELECT s FROM ShipmentRecord s WHERE s.factoryId = :factoryId " +
           "AND s.shipmentDate BETWEEN :startDate AND :endDate " +
           "ORDER BY s.shipmentDate DESC")
    List<ShipmentRecord> findByFactoryIdAndDateRange(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * 根据出货单号查询
     */
    Optional<ShipmentRecord> findByShipmentNumber(String shipmentNumber);

    /**
     * 根据订单号查询
     */
    List<ShipmentRecord> findByOrderNumber(String orderNumber);

    /**
     * 根据物流单号查询
     */
    Optional<ShipmentRecord> findByTrackingNumber(String trackingNumber);

    /**
     * 统计工厂出货记录数量
     */
    long countByFactoryId(String factoryId);

    /**
     * 统计工厂指定状态的出货记录数量
     */
    long countByFactoryIdAndStatus(String factoryId, String status);

    /**
     * 根据生产批次号查询出货记录（用于溯源）
     * 优先通过 batch_number 精确匹配，fallback 到 product_name LIKE 匹配
     */
    @Query("SELECT s FROM ShipmentRecord s WHERE s.factoryId = :factoryId " +
           "AND (s.batchNumber = :batchNumber OR (s.batchNumber IS NULL AND s.productName LIKE %:batchNumber% ESCAPE '\\')) " +
           "ORDER BY s.shipmentDate DESC")
    List<ShipmentRecord> findByFactoryIdAndBatchNumber(@Param("factoryId") String factoryId,
                                                        @Param("batchNumber") String batchNumber);

    /**
     * 计算指定日期范围内的总收入
     */
    @Query("SELECT COALESCE(SUM(s.totalAmount), 0) FROM ShipmentRecord s WHERE s.factoryId = :factoryId " +
           "AND s.shipmentDate BETWEEN :startDate AND :endDate AND s.status IN ('shipped', 'delivered')")
    java.math.BigDecimal calculateTotalRevenue(@Param("factoryId") String factoryId,
                                                @Param("startDate") LocalDate startDate,
                                                @Param("endDate") LocalDate endDate);

    /**
     * 统计指定日期范围内的订单数量
     */
    @Query("SELECT COUNT(s) FROM ShipmentRecord s WHERE s.factoryId = :factoryId " +
           "AND s.shipmentDate BETWEEN :startDate AND :endDate")
    long countByFactoryIdAndDateRange(@Param("factoryId") String factoryId,
                                      @Param("startDate") LocalDate startDate,
                                      @Param("endDate") LocalDate endDate);

    /**
     * 计算平均订单金额
     */
    @Query("SELECT COALESCE(AVG(s.totalAmount), 0) FROM ShipmentRecord s WHERE s.factoryId = :factoryId " +
           "AND s.shipmentDate BETWEEN :startDate AND :endDate AND s.totalAmount IS NOT NULL")
    java.math.BigDecimal calculateAverageOrderValue(@Param("factoryId") String factoryId,
                                                     @Param("startDate") LocalDate startDate,
                                                     @Param("endDate") LocalDate endDate);

    /**
     * 计算指定日期的收入（用于趋势分析）
     */
    @Query("SELECT COALESCE(SUM(s.totalAmount), 0) FROM ShipmentRecord s WHERE s.factoryId = :factoryId " +
           "AND s.shipmentDate = :date AND s.status IN ('shipped', 'delivered')")
    java.math.BigDecimal calculateDailyRevenue(@Param("factoryId") String factoryId,
                                                @Param("date") LocalDate date);

    /**
     * 根据ID和工厂ID查询（工厂隔离）
     */
    Optional<ShipmentRecord> findByIdAndFactoryId(String id, String factoryId);

    /**
     * 根据出货单号和工厂ID查询（工厂隔离）
     */
    Optional<ShipmentRecord> findByShipmentNumberAndFactoryId(String shipmentNumber, String factoryId);

    /**
     * 根据物流单号和工厂ID查询（工厂隔离）
     */
    Optional<ShipmentRecord> findByTrackingNumberAndFactoryId(String trackingNumber, String factoryId);
}
