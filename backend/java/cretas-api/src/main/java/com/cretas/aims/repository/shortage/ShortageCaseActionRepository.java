package com.cretas.aims.repository.shortage;

import com.cretas.aims.entity.shortage.ShortageCaseAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ShortageCaseActionRepository extends JpaRepository<ShortageCaseAction, Long> {

    List<ShortageCaseAction> findByCaseIdOrderByIdAsc(String caseId);

    /** 这个实例这种物料「还在走」的那张采购草稿 (最多一行, 部分唯一索引保证)。 */
    Optional<ShortageCaseAction> findByCaseIdAndMaterialTypeIdAndActionTypeAndStatus(
            String caseId, String materialTypeId, ShortageCaseAction.ActionType actionType,
            ShortageCaseAction.Status status);
}
