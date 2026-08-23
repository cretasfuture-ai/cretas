package com.cretas.aims.repository.smartbi;

import com.cretas.aims.entity.smartbi.SmartBiAnalysisCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * SmartBI 分析结果缓存 Repository
 *
 * <p>管理AI分析结果缓存，支持按缓存键和分析类型查询，以及过期清理。</p>
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-18
 */
@Repository
public interface SmartBiAnalysisCacheRepository extends JpaRepository<SmartBiAnalysisCache, Long> {

    /**
     * 根据工厂ID和缓存键查询缓存
     *
     * @param factoryId 工厂ID
     * @param cacheKey 缓存键
     * @return 缓存记录
     */
    Optional<SmartBiAnalysisCache> findByFactoryIdAndCacheKey(String factoryId, String cacheKey);

    /**
     * 同上, 但<b>看得到已软删的行</b> —— 只给<b>写</b>路径用。
     *
     * <h3>🔴 2026-08-19 prod: 每刷新一次驾驶舱就撞一次 uk_cache_key</h3>
     * {@code SmartBiAnalysisCache} 上有类级 {@code @Where(clause = "deleted_at IS NULL")},
     * 而 {@code BaseEntity} 上的 {@code @SQLDelete} 让所有删除都是<b>软删</b>。
     * 唯一约束 {@code uk_cache_key (factory_id, cache_key)} 却<b>不排除软删行</b>。
     * 于是一行缓存被软删之后:
     * <pre>
     *   写路径 findByFactoryIdAndCacheKey → 空 (被 @Where 挡了)
     *        → 走 "新建" 分支 → INSERT
     *        → 唯一索引看得见那条死行 → duplicate key ⇒ 永久撞, 每次都撞
     * </pre>
     * prod 实测: {@code smart_bi_analysis_cache} 全表 5 行, 其中 3 行已软删,
     * {@code (F006, dashboard:month)} 就在其中 —— 该签名全窗口 5625 次、近 7 天 <b>2090 次</b>,
     * 是本次普查里最高频的报错。
     *
     * <p>⚠️ 读路径 {@link #findByFactoryIdAndCacheKey} <b>保持被过滤</b>是对的:
     * 软删的缓存不该被端给用户。<b>两条路径需要相反的可见性</b> —— 这也是为什么
     * ⛔ 不把 {@code uk_cache_key} 改成部分索引就完事: 那只解决写, 不表达这个区别。
     *
     * <p>⚠️ 必须 {@code nativeQuery = true}: {@code @Where} 由 Hibernate 在 HQL/Criteria 层
     * 拼装, 原生 SQL 不经过它。改回 JPQL 会<b>静默</b>恢复这个 bug ——
     * 有 {@code SoftDeleteBlindSpotContractTest} 钉着。
     */
    @Query(value = "SELECT * FROM smart_bi_analysis_cache WHERE factory_id = ?1 AND cache_key = ?2",
            nativeQuery = true)
    Optional<SmartBiAnalysisCache> findIncludingDeletedByFactoryIdAndCacheKey(String factoryId, String cacheKey);

    /**
     * 根据工厂ID和分析类型查询缓存列表
     *
     * @param factoryId 工厂ID
     * @param analysisType 分析类型
     * @return 缓存记录列表
     */
    List<SmartBiAnalysisCache> findByFactoryIdAndAnalysisType(String factoryId, String analysisType);

    /**
     * 删除指定时间之前过期的缓存
     *
     * @param dateTime 过期时间阈值
     */
    @Modifying
    void deleteByExpiresAtBefore(LocalDateTime dateTime);

    /**
     * 删除指定工厂的所有缓存（用于重新上传时失效旧分析）
     *
     * @param factoryId 工厂ID
     */
    @Modifying
    void deleteByFactoryId(String factoryId);

    /**
     * 查询最新的分析缓存（按工厂、类型和日期）
     *
     * @param factoryId 工厂ID
     * @param type 分析类型
     * @param date 分析日期
     * @return 最新缓存记录
     */
    @Query("SELECT c FROM SmartBiAnalysisCache c WHERE c.factoryId = :factoryId " +
           "AND c.analysisType = :type AND c.analysisDate = :date")
    Optional<SmartBiAnalysisCache> findLatestCache(@Param("factoryId") String factoryId,
                                                    @Param("type") String type,
                                                    @Param("date") LocalDate date);
}
