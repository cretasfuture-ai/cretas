package com.cretas.aims.dto.production;

import com.cretas.aims.entity.ProductionReportCorrection;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class ProductionReportCorrectionDTO {
    private String id;
    private Long productionReportId;
    private Long workProcessTaskId;
    private Long productionBatchId;
    private String productionPlanId;
    private String productTypeId;
    private String processCategory;
    private String status;
    private BigDecimal originalOutputQuantity;
    private BigDecimal proposedOutputQuantity;
    private BigDecimal originalGoodQuantity;
    private BigDecimal proposedGoodQuantity;
    private BigDecimal originalDefectQuantity;
    private BigDecimal proposedDefectQuantity;
    private Integer originalTotalWorkers;
    private Integer proposedTotalWorkers;
    private Integer originalTotalWorkMinutes;
    private Integer proposedTotalWorkMinutes;
    private String originalNotes;
    private String proposedNotes;
    private String reason;
    private Long submittedBy;
    private String submittedByName;
    private LocalDateTime submittedAt;
    private String workflowInstanceId;
    private Long decidedBy;
    private String decidedByName;
    private LocalDateTime decidedAt;
    private String decisionNotes;
    private LocalDateTime appliedAt;

    public static ProductionReportCorrectionDTO from(ProductionReportCorrection c) {
        return ProductionReportCorrectionDTO.builder()
                .id(c.getId()).productionReportId(c.getProductionReportId())
                .workProcessTaskId(c.getWorkProcessTaskId()).productionBatchId(c.getProductionBatchId())
                .productionPlanId(c.getProductionPlanId()).productTypeId(c.getProductTypeId())
                .processCategory(c.getProcessCategory()).status(c.getStatus().name())
                .originalOutputQuantity(c.getOriginalOutputQuantity()).proposedOutputQuantity(c.getProposedOutputQuantity())
                .originalGoodQuantity(c.getOriginalGoodQuantity()).proposedGoodQuantity(c.getProposedGoodQuantity())
                .originalDefectQuantity(c.getOriginalDefectQuantity()).proposedDefectQuantity(c.getProposedDefectQuantity())
                .originalTotalWorkers(c.getOriginalTotalWorkers()).proposedTotalWorkers(c.getProposedTotalWorkers())
                .originalTotalWorkMinutes(c.getOriginalTotalWorkMinutes()).proposedTotalWorkMinutes(c.getProposedTotalWorkMinutes())
                .originalNotes(c.getOriginalNotes()).proposedNotes(c.getProposedNotes())
                .reason(c.getCorrectionReason()).submittedBy(c.getSubmittedBy()).submittedAt(c.getSubmittedAt())
                .workflowInstanceId(c.getWorkflowInstanceId()).decidedBy(c.getDecidedBy()).decidedAt(c.getDecidedAt())
                .decisionNotes(c.getDecisionNotes()).appliedAt(c.getAppliedAt()).build();
    }
}
