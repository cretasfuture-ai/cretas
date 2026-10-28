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

    Optional<ShortageCase> findByIdAndFactoryId(String id, String factoryId);

    /** 本厂还没关闭、且缺口里有这种物料的实例 —— 入库 / 放行时只刷新它们。 */
    @Query("SELECT DISTINCT c FROM ShortageCase c, ShortageCaseLine l WHERE l.caseId = c.id "
            + "AND c.factoryId = :factoryId AND c.closedAt IS NULL AND l.materialTypeId = :materialTypeId "
            + "AND c.deletedAt IS NULL AND l.deletedAt IS NULL")
    List<ShortageCase> findOpenByFactoryIdAndMaterial(@Param("factoryId") String factoryId,
                                                      @Param("materialTypeId") String materialTypeId);

    @Query("SELECT c FROM ShortageCase c WHERE c.factoryId = :factoryId "
            + "AND (:openOnly = false OR c.closedAt IS NULL) ORDER BY c.lastRefreshedAt DESC")
    Page<ShortageCase> findForFactory(@Param("factoryId") String factoryId,
                                      @Param("openOnly") boolean openOnly,
                                      Pageable pageable);
}
