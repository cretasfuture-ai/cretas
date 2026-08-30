package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductionReportCorrection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductionReportCorrectionRepository extends JpaRepository<ProductionReportCorrection, String> {
    Optional<ProductionReportCorrection> findByIdAndFactoryId(String id, String factoryId);
    Optional<ProductionReportCorrection> findFirstByFactoryIdAndProductionReportIdAndStatusOrderBySubmittedAtDesc(
            String factoryId, Long reportId, ProductionReportCorrection.Status status);
    List<ProductionReportCorrection> findByFactoryIdAndProductionReportIdOrderBySubmittedAtDesc(
            String factoryId, Long reportId);
    List<ProductionReportCorrection> findByFactoryIdAndProductionReportIdInOrderBySubmittedAtDesc(
            String factoryId, Collection<Long> reportIds);
    List<ProductionReportCorrection> findByFactoryIdAndProductionPlanIdAndStatus(
            String factoryId, String productionPlanId, ProductionReportCorrection.Status status);
    List<ProductionReportCorrection> findByFactoryIdAndProductTypeIdInAndStatusOrderByAppliedAtDesc(
            String factoryId, Collection<String> productTypeIds, ProductionReportCorrection.Status status);
    List<ProductionReportCorrection> findByFactoryIdAndProductionPlanIdInAndStatusOrderByAppliedAtDesc(
            String factoryId, Collection<String> productionPlanIds, ProductionReportCorrection.Status status);
}
