package com.cretas.aims.repository.shortage;

import com.cretas.aims.entity.shortage.DeliveryRiskCaseDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DeliveryRiskCaseDetailRepository extends JpaRepository<DeliveryRiskCaseDetail, String> {

    Optional<DeliveryRiskCaseDetail> findByCaseIdAndFactoryId(String caseId, String factoryId);
}
