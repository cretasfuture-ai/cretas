package com.cretas.aims.repository;

import com.cretas.aims.entity.config.QualityCheckItem;
import com.cretas.aims.entity.enums.QualityCheckCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 质检项配置 Repository
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-29
 */
@Repository
public interface QualityCheckItemRepository extends JpaRepository<QualityCheckItem, String> {

    // ==================== 基础查询 ====================

    /**
     * 根据工厂ID分页查询
     */
    Page<QualityCheckItem> findByFactoryIdAndDeletedAtIsNull(String factoryId, Pageable pageable);

    /**
     * 根据工厂ID查询所有启用的项目
     */
    List<QualityCheckItem> findByFactoryIdAndEnabledTrueAndDeletedAtIsNull(String factoryId);

    /**
     * 根据工厂ID和类别查询
     */
    List<QualityCheckItem> findByFactoryIdAndCategoryAndDeletedAtIsNull(
            String factoryId, QualityCheckCategory category);

    /**
     * 根据工厂ID和项目编号查询
     */
    Optional<QualityCheckItem> findByFactoryIdAndItemCodeAndDeletedAtIsNull(
            String factoryId, String itemCode);

    /**
     * 检查编号是否存在
     */
    boolean existsByFactoryIdAndItemCodeAndDeletedAtIsNull(String factoryId, String itemCode);

    /**
     * 查询系统默认项目模板（factoryId 为 null）
     */
    List<QualityCheckItem> findByFactoryIdIsNullAndEnabledTrueAndDeletedAtIsNull();

    // ==================== 必检项查询 ====================

    /**
     * 查询工厂的必检项目
     */
    List<QualityCheckItem> findByFactoryIdAndIsRequiredTrueAndEnabledTrueAndDeletedAtIsNull(
            String factoryId);

    /**
     * 按类别查询必检项目
     */
    List<QualityCheckItem> findByFactoryIdAndCategoryAndIsRequiredTrueAndEnabledTrueAndDeletedAtIsNull(
            String factoryId, QualityCheckCategory category);

    // ==================== 关键项查询 ====================

    /**
     * 查询关键质检项（CRITICAL 级别）
     */
    @Query("SELECT q FROM QualityCheckItem q WHERE q.factoryId = :factoryId " +
           "AND q.severity = 'CRITICAL' AND q.enabled = true AND q.deletedAt IS NULL")
    List<QualityCheckItem> findCriticalItems(@Param("factoryId") String factoryId);

    // ==================== 统计查询 ====================

    /**
     * 统计工厂【已启用】的质检项数量。
     *
     * <p>⚠️ 方法名里的 {@code EnabledTrue} 是判据的一部分 —— 它<b>不是</b>「总数」。
     * 此前 {@code getStatistics} 把它的结果放进了名为 {@code "total"} 的键，
     * 而前端那张卡的标题是「质检项总数」⇒ 名字与含义对不上。见下面那个方法。</p>
     */
    long countByFactoryIdAndEnabledTrueAndDeletedAtIsNull(String factoryId);

    /**
     * 统计工厂的质检项【总数】—— 含已禁用的，只排除软删。
     *
     * <p>2026-08-28 新增：此前后端<b>根本不产出</b>这个概念，而页面上有一张
     * 「质检项总数」卡片，它靠前端 {@code || tableData.length} 兜底 ——
     * 那是<b>当前页</b>的行数，超过一页就少报。</p>
     */
    long countByFactoryIdAndDeletedAtIsNull(String factoryId);

    /**
     * 按类别统计
     */
    @Query("SELECT q.category, COUNT(q) FROM QualityCheckItem q " +
           "WHERE q.factoryId = :factoryId AND q.enabled = true AND q.deletedAt IS NULL " +
           "GROUP BY q.category")
    List<Object[]> countByCategory(@Param("factoryId") String factoryId);

    /**
     * 按严重程度统计
     */
    @Query("SELECT q.severity, COUNT(q) FROM QualityCheckItem q " +
           "WHERE q.factoryId = :factoryId AND q.enabled = true AND q.deletedAt IS NULL " +
           "GROUP BY q.severity")
    List<Object[]> countBySeverity(@Param("factoryId") String factoryId);

    // ==================== 批量操作 ====================

    /**
     * 批量启用/禁用
     */
    @Modifying
    @Query("UPDATE QualityCheckItem q SET q.enabled = :enabled WHERE q.id IN :ids")
    int batchUpdateEnabled(@Param("ids") List<String> ids, @Param("enabled") boolean enabled);

    /**
     * 按类别排序查询
     */
    @Query("SELECT q FROM QualityCheckItem q WHERE q.factoryId = :factoryId " +
           "AND q.enabled = true AND q.deletedAt IS NULL " +
           "ORDER BY q.category, q.sortOrder, q.itemCode")
    List<QualityCheckItem> findAllSortedByCategoryAndOrder(@Param("factoryId") String factoryId);
}
