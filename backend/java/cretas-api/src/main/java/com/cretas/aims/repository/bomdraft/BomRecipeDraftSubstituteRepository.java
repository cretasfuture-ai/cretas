package com.cretas.aims.repository.bomdraft;

import com.cretas.aims.entity.bomdraft.BomRecipeDraftSubstitute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BomRecipeDraftSubstituteRepository extends JpaRepository<BomRecipeDraftSubstitute, String> {
    List<BomRecipeDraftSubstitute> findByDraftIdAndFactoryIdOrderByItemIdAscPriorityAsc(
            String draftId, String factoryId);
}
