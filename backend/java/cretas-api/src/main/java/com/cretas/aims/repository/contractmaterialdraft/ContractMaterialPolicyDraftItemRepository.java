package com.cretas.aims.repository.contractmaterialdraft;

import com.cretas.aims.entity.contractmaterialdraft.ContractMaterialPolicyDraftItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ContractMaterialPolicyDraftItemRepository
        extends JpaRepository<ContractMaterialPolicyDraftItem, String> {
    List<ContractMaterialPolicyDraftItem> findByDraftIdAndFactoryIdOrderByOrdinal(
            String draftId, String factoryId);
}
