package com.cretas.aims.repository;

import com.cretas.aims.entity.InsertSlot;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 紧急插单时段数据访问接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-28
 */
@Repository
public interface InsertSlotRepository extends JpaRepository<InsertSlot, String> {

    /**
     * 查找工厂的可用时段
     *
     * @param factoryId 工厂ID
     * @param status 状态
     * @return 可用时段列表
     */
    List<InsertSlot> findByFactoryIdAndStatus(String factoryId, String status);

    /**
     * 按工厂查时段 —— 写路径 (确认/强制插单) 必须用它, ⛔ 不用 findById:
     * 他厂时段在这里查不到, 调用方回 404 且不改任何数据。
     */
    Optional<InsertSlot> findByIdAndFactoryId(String id, String factoryId);

    /**
     * 原子占用时段: 只有「本厂 + 当前 available + 未删除」那一行会被改成 selected。
     *
     * <p>返回影响行数。0 = 时段已被别人占 / 不是 available / 不属于本厂 ⇒ 调用方必须当冲突处理,
     * ⛔ 不得继续建计划。两个并发确认对同一行做条件更新, 数据库行锁保证至多一个拿到 1。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE InsertSlot s SET s.status = 'selected', s.updatedAt = :now " +
           "WHERE s.id = :id AND s.factoryId = :factoryId " +
           "AND s.status = 'available' AND s.deletedAt IS NULL")
    int claimAvailableSlot(
            @Param("id") String id,
            @Param("factoryId") String factoryId,
            @Param("now") LocalDateTime now);

    /**
     * 查找工厂在指定时间范围内的时段
     *
     * @param factoryId 工厂ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 时段列表
     */
    @Query("SELECT s FROM InsertSlot s WHERE s.factoryId = :factoryId " +
           "AND s.startTime >= :startTime AND s.endTime <= :endTime " +
           "ORDER BY s.startTime ASC")
    List<InsertSlot> findByFactoryIdAndTimeRange(
            @Param("factoryId") String factoryId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 查找可用且推荐分数高的时段
     *
     * @param factoryId 工厂ID
     * @param minScore 最低推荐分数
     * @return 高分时段列表
     */
    @Query("SELECT s FROM InsertSlot s WHERE s.factoryId = :factoryId " +
           "AND s.status = 'available' " +
           "AND s.recommendScore >= :minScore " +
           "ORDER BY s.recommendScore DESC")
    List<InsertSlot> findHighScoreSlots(
            @Param("factoryId") String factoryId,
            @Param("minScore") Integer minScore);

    /**
     * 按产线查找可用时段
     *
     * @param factoryId 工厂ID
     * @param productionLineId 产线ID
     * @param status 状态
     * @return 时段列表
     */
    List<InsertSlot> findByFactoryIdAndProductionLineIdAndStatus(
            String factoryId,
            String productionLineId,
            String status);

    /**
     * 查找无影响的可用时段
     *
     * @param factoryId 工厂ID
     * @return 无影响时段列表
     */
    @Query("SELECT s FROM InsertSlot s WHERE s.factoryId = :factoryId " +
           "AND s.status = 'available' " +
           "AND s.impactLevel = 'none' " +
           "ORDER BY s.recommendScore DESC, s.startTime ASC")
    List<InsertSlot> findNoImpactSlots(@Param("factoryId") String factoryId);

    /**
     * 将过期时段标记为expired
     *
     * @param factoryId 工厂ID
     * @param currentTime 当前时间
     * @return 更新数量
     */
    @Modifying
    @Query("UPDATE InsertSlot s SET s.status = 'expired' " +
           "WHERE s.factoryId = :factoryId " +
           "AND s.status = 'available' " +
           "AND s.endTime < :currentTime")
    int markExpiredSlots(
            @Param("factoryId") String factoryId,
            @Param("currentTime") LocalDateTime currentTime);

    /**
     * 统计工厂可用时段数量
     *
     * @param factoryId 工厂ID
     * @return 可用时段数量
     */
    long countByFactoryIdAndStatus(String factoryId, String status);

    /**
     * 分页查询工厂时段
     *
     * @param factoryId 工厂ID
     * @param pageable 分页参数
     * @return 时段分页数据
     */
    Page<InsertSlot> findByFactoryId(String factoryId, Pageable pageable);

    /**
     * 删除工厂的所有时段（用于重新生成）
     *
     * @param factoryId 工厂ID
     */
    @Modifying
    @Query("DELETE FROM InsertSlot s WHERE s.factoryId = :factoryId AND s.status = 'available'")
    void deleteAvailableSlotsByFactoryId(@Param("factoryId") String factoryId);
}
