package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.PurchaseRequisitionApprovalRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PurchaseRequisitionApprovalRecordRepository
        extends JpaRepository<PurchaseRequisitionApprovalRecord, String> {

    List<PurchaseRequisitionApprovalRecord> findByFactoryIdAndRequisitionIdOrderBySeqAsc(
            String factoryId, String requisitionId);

    long countByFactoryIdAndRequisitionId(String factoryId, String requisitionId);
}
