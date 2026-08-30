package com.cretas.aims.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** 一次报工结果修正草稿及其不可覆盖的审批审计。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "production_report_corrections")
public class ProductionReportCorrection {

    public enum Status {
        PENDING_APPROVAL,
        APPROVED_APPLIED,
        REJECTED
    }

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "production_report_id", nullable = false)
    private Long productionReportId;

    @Column(name = "work_process_task_id")
    private Long workProcessTaskId;

    @Column(name = "production_batch_id")
    private Long productionBatchId;

    @Column(name = "production_plan_id", length = 191)
    private String productionPlanId;

    @Column(name = "product_type_id", length = 100)
    private String productTypeId;

    @Column(name = "process_category", length = 200)
    private String processCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private Status status;

    @Column(name = "original_output_quantity", precision = 12, scale = 2)
    private BigDecimal originalOutputQuantity;
    @Column(name = "proposed_output_quantity", precision = 12, scale = 2)
    private BigDecimal proposedOutputQuantity;
    @Column(name = "original_good_quantity", precision = 12, scale = 2)
    private BigDecimal originalGoodQuantity;
    @Column(name = "proposed_good_quantity", precision = 12, scale = 2)
    private BigDecimal proposedGoodQuantity;
    @Column(name = "original_defect_quantity", precision = 12, scale = 2)
    private BigDecimal originalDefectQuantity;
    @Column(name = "proposed_defect_quantity", precision = 12, scale = 2)
    private BigDecimal proposedDefectQuantity;
    @Column(name = "original_total_workers")
    private Integer originalTotalWorkers;
    @Column(name = "proposed_total_workers")
    private Integer proposedTotalWorkers;
    @Column(name = "original_total_work_minutes")
    private Integer originalTotalWorkMinutes;
    @Column(name = "proposed_total_work_minutes")
    private Integer proposedTotalWorkMinutes;
    @Column(name = "original_notes", length = 500)
    private String originalNotes;
    @Column(name = "proposed_notes", length = 500)
    private String proposedNotes;

    @Column(name = "correction_reason", nullable = false, length = 500)
    private String correctionReason;
    @Column(name = "submitted_by", nullable = false)
    private Long submittedBy;
    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;
    @Column(name = "workflow_instance_id", length = 36)
    private String workflowInstanceId;
    @Column(name = "decided_by")
    private Long decidedBy;
    @Column(name = "decided_at")
    private LocalDateTime decidedAt;
    @Column(name = "decision_notes", length = 500)
    private String decisionNotes;
    @Column(name = "applied_at")
    private LocalDateTime appliedAt;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        if (submittedAt == null) submittedAt = now;
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
