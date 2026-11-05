package com.cretas.aims.repository.shortage;

import com.cretas.aims.entity.shortage.ShortageCase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ShortageCaseRepository extends JpaRepository<ShortageCase, String> {

    /** 同一来源的未关闭实例 (最多一条, 由部分唯一索引保证); 刷新时加锁防并发重复建。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ShortageCase c WHERE c.factoryId = :factoryId AND c.sourceType = :sourceType "
            + "AND c.sourceId = :sourceId AND c.closedAt IS NULL")
    Optional<ShortageCase> findOpenForUpdate(@Param("factoryId") String factoryId,
                                             @Param("sourceType") ShortageCase.SourceType sourceType,
                                             @Param("sourceId") String sourceId);

    /** Lock any source-specific case, including a closed case, before its detail row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ShortageCase c WHERE c.id = :caseId AND c.factoryId = :factoryId "
            + "AND c.sourceType IN :sourceTypes")
    Optional<ShortageCase> findForUpdateByIdAndFactoryIdAndSourceTypeIn(
            @Param("caseId") String caseId,
            @Param("factoryId") String factoryId,
            @Param("sourceTypes") Collection<ShortageCase.SourceType> sourceTypes);

    /** Lock the source row before reading/updating its parameter-detail row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ShortageCase c WHERE c.factoryId = :factoryId AND c.sourceType = :sourceType "
            + "AND c.sourceId = :sourceId")
    Optional<ShortageCase> findByFactoryIdAndSourceTypeAndSourceIdForUpdate(
            @Param("factoryId") String factoryId,
            @Param("sourceType") ShortageCase.SourceType sourceType,
            @Param("sourceId") String sourceId);

    /**
     * 缺料入口只认销售订单来源: 同一张表里的成本 / 损耗异常带金额口径, 权限门比缺料窄,
     * ⛔ 不能经缺料的详情 / 台账 / 重排入口读到或改到。
     */
    default Optional<ShortageCase> findByIdAndFactoryId(String id, String factoryId) {
        return findByIdAndFactoryIdAndSourceTypeIn(id, factoryId, List.of(ShortageCase.SourceType.SALES_ORDER));
    }

    Optional<ShortageCase> findByIdAndFactoryIdAndSourceTypeIn(String id, String factoryId,
                                                               Collection<ShortageCase.SourceType> sourceTypes);

    /** 本厂还没关闭、且缺口里有这种物料的实例 —— 入库 / 放行时只刷新它们。 */
    @Query("SELECT DISTINCT c FROM ShortageCase c, ShortageCaseLine l WHERE l.caseId = c.id "
            + "AND c.factoryId = :factoryId AND c.closedAt IS NULL AND l.materialTypeId = :materialTypeId "
            + "AND c.deletedAt IS NULL AND l.deletedAt IS NULL")
    List<ShortageCase> findOpenByFactoryIdAndMaterial(@Param("factoryId") String factoryId,
                                                      @Param("materialTypeId") String materialTypeId);

    /** 缺料列表, 同样只认销售订单来源。 */
    default Page<ShortageCase> findForFactory(String factoryId, boolean openOnly, Pageable pageable) {
        return findForFactoryBySources(factoryId, openOnly, List.of(ShortageCase.SourceType.SALES_ORDER), pageable);
    }

    @Query("SELECT c FROM ShortageCase c WHERE c.factoryId = :factoryId AND c.sourceType IN :sourceTypes "
            + "AND (:openOnly = false OR c.closedAt IS NULL) ORDER BY c.lastRefreshedAt DESC")
    Page<ShortageCase> findForFactoryBySources(@Param("factoryId") String factoryId,
                                               @Param("openOnly") boolean openOnly,
                                               @Param("sourceTypes") Collection<ShortageCase.SourceType> sourceTypes,
                                               Pageable pageable);

    /** 同一来源最近一条已结束的实例: 风险没变就不因为重新扫描再开一条。 */
    Optional<ShortageCase> findFirstByFactoryIdAndSourceTypeAndSourceIdAndClosedAtIsNotNullOrderByClosedAtDesc(
            String factoryId, ShortageCase.SourceType sourceType, String sourceId);
}
