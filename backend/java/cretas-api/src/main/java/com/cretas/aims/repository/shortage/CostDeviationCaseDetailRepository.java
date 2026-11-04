package com.cretas.aims.repository.shortage;

import com.cretas.aims.entity.shortage.CostDeviationCaseDetail;
import com.cretas.aims.entity.shortage.ShortageCase;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface CostDeviationCaseDetailRepository extends JpaRepository<CostDeviationCaseDetail, String> {

    List<CostDeviationCaseDetail> findByCaseIdIn(Collection<String> caseIds);

    /** 同一来源最近关闭的实例; 读数没变时不重开 (已经有人下过结论)。 */
    @Query("SELECT c FROM ShortageCase c WHERE c.factoryId = :factoryId AND c.sourceType = :sourceType "
            + "AND c.sourceId = :sourceId AND c.closedAt IS NOT NULL ORDER BY c.closedAt DESC")
    List<ShortageCase> findClosedCases(@Param("factoryId") String factoryId,
                                       @Param("sourceType") ShortageCase.SourceType sourceType,
                                       @Param("sourceId") String sourceId,
                                       Pageable pageable);
}
