package com.cretas.aims.repository.shortage;

import com.cretas.aims.entity.shortage.ProcessParamDeviationCaseDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProcessParamDeviationCaseDetailRepository
        extends JpaRepository<ProcessParamDeviationCaseDetail, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM ProcessParamDeviationCaseDetail d WHERE d.factoryId = :factoryId AND d.sourceKey = :sourceKey")
    Optional<ProcessParamDeviationCaseDetail> findByFactoryIdAndSourceKey(@Param("factoryId") String factoryId,
                                                                            @Param("sourceKey") String sourceKey);

    Optional<ProcessParamDeviationCaseDetail> findByCaseIdAndFactoryId(String caseId, String factoryId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM ProcessParamDeviationCaseDetail d WHERE d.caseId = :caseId AND d.factoryId = :factoryId")
    Optional<ProcessParamDeviationCaseDetail> findByCaseIdAndFactoryIdForUpdate(@Param("caseId") String caseId,
                                                                                  @Param("factoryId") String factoryId);

    List<ProcessParamDeviationCaseDetail> findByCaseIdIn(Collection<String> caseIds);
}
