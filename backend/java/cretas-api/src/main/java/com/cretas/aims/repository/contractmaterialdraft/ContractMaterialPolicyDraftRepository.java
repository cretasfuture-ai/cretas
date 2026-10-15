package com.cretas.aims.repository.contractmaterialdraft;

import com.cretas.aims.entity.contractmaterialdraft.ContractMaterialPolicyDraft;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ContractMaterialPolicyDraftRepository
        extends JpaRepository<ContractMaterialPolicyDraft, String> {
    Optional<ContractMaterialPolicyDraft> findByFactoryIdAndIdempotencyKey(
            String factoryId, String idempotencyKey);
    Optional<ContractMaterialPolicyDraft> findByIdAndFactoryId(String id, String factoryId);
}
