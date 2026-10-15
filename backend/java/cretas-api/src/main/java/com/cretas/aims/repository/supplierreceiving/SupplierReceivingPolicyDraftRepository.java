package com.cretas.aims.repository.supplierreceiving;

import com.cretas.aims.entity.supplierreceiving.SupplierReceivingPolicyDraft;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SupplierReceivingPolicyDraftRepository extends JpaRepository<SupplierReceivingPolicyDraft, String> {
    Optional<SupplierReceivingPolicyDraft> findByFactoryIdAndIdempotencyKey(String factoryId, String idempotencyKey);
    Optional<SupplierReceivingPolicyDraft> findByIdAndFactoryId(String id, String factoryId);
}
