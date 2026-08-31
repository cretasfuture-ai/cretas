package com.cretas.aims.service;

import com.cretas.aims.dto.production.ProductionReportCorrectionDTO;
import com.cretas.aims.dto.production.ProductionReportCorrectionRequest;
import com.cretas.aims.entity.ProductionReportCorrection;
import com.cretas.aims.entity.workflow.ApprovalHistory.HistoryAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface ProductionReportCorrectionService {
    ProductionReportCorrectionDTO submit(String factoryId, Long reportId,
                                         ProductionReportCorrectionRequest request, Long userId);
    ProductionReportCorrectionDTO applyWorkflowAction(String factoryId, String correctionId,
                                                       String workflowInstanceId, Long actorId,
                                                       String actorRole, HistoryAction action, String notes);
    List<ProductionReportCorrectionDTO> history(String factoryId, Long reportId);
    Page<Map<String, Object>> listCorrectable(String factoryId, Pageable pageable);
    void assertNoPendingForPlan(String factoryId, String planId);
    Map<String, Object> approvalSummary(String factoryId, String correctionId);
    List<ProductionReportCorrectionDTO> approvedForProducts(String factoryId, Collection<String> productTypeIds);
    List<ProductionReportCorrectionDTO> approvedForPlans(String factoryId, Collection<String> productionPlanIds);
}
