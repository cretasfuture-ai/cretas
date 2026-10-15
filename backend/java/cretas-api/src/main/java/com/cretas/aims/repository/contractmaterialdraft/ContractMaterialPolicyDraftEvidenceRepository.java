package com.cretas.aims.repository.contractmaterialdraft;

import com.cretas.aims.entity.contractmaterialdraft.ContractMaterialPolicyDraftEvidence;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ContractMaterialPolicyDraftEvidenceRepository
        extends JpaRepository<ContractMaterialPolicyDraftEvidence, String> {
    List<ContractMaterialPolicyDraftEvidence> findByDraftIdAndFactoryIdOrderByAuthorityCode(
            String draftId, String factoryId);
}
