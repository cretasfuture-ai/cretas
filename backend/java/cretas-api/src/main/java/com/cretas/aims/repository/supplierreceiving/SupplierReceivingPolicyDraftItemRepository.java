package com.cretas.aims.repository.supplierreceiving;

import com.cretas.aims.entity.supplierreceiving.SupplierReceivingPolicyDraftItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupplierReceivingPolicyDraftItemRepository extends JpaRepository<SupplierReceivingPolicyDraftItem, String> {
    List<SupplierReceivingPolicyDraftItem> findByDraftIdAndFactoryIdOrderByOrdinal(String draftId, String factoryId);
}
