package com.cretas.aims.repository.quality.firstarticle;

import com.cretas.aims.entity.quality.firstarticle.FirstArticleStandard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** 全部查询带 factory_id。 */
public interface FirstArticleStandardRepository extends JpaRepository<FirstArticleStandard, Long> {

    Optional<FirstArticleStandard> findByFactoryIdAndId(String factoryId, Long id);

    @Query("SELECT s FROM FirstArticleStandard s WHERE s.factoryId = :factoryId"
            + " AND s.workProcessId = :workProcessId AND s.active = true")
    Optional<FirstArticleStandard> findActive(@Param("factoryId") String factoryId,
                                              @Param("workProcessId") String workProcessId);

    @Query("SELECT s FROM FirstArticleStandard s WHERE s.factoryId = :factoryId AND s.active = true"
            + " AND s.workProcessId IN :workProcessIds")
    List<FirstArticleStandard> findActiveIn(@Param("factoryId") String factoryId,
                                            @Param("workProcessIds") java.util.Collection<String> workProcessIds);

    @Query("SELECT s FROM FirstArticleStandard s WHERE s.factoryId = :factoryId"
            + " AND (:includeInactive = true OR s.active = true) ORDER BY s.active DESC, s.id DESC")
    List<FirstArticleStandard> listForFactory(@Param("factoryId") String factoryId,
                                              @Param("includeInactive") boolean includeInactive);
}
