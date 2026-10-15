package com.cretas.aims.repository.supplierreceiving;

import com.cretas.aims.entity.supplierreceiving.SupplierReceivingPolicyDraftQualification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupplierReceivingPolicyDraftQualificationRepository extends JpaRepository<SupplierReceivingPolicyDraftQualification, String> {
    List<SupplierReceivingPolicyDraftQualification> findByDraftIdAndFactoryIdOrderByItemIdAscOrdinalAsc(
            String draftId, String factoryId);
}
