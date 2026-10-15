package com.cretas.aims.entity.roletrainingdraft;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

@Entity
@Table(name = "role_training_draft_positions",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_role_training_position_id_factory",
                        columnNames = {"id", "factory_id"}),
                @UniqueConstraint(name = "uk_role_training_position_parent_ordinal",
                        columnNames = {"draft_id", "factory_id", "ordinal"}),
                @UniqueConstraint(name = "uk_role_training_position_parent_code",
                        columnNames = {"draft_id", "factory_id", "position_code"})
        }, indexes = @Index(name = "idx_role_training_position_parent",
                columnList = "factory_id,draft_id,ordinal"))
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class RoleTrainingDraftPosition extends BaseEntity {
    @Id @Column(length = 36, nullable = false, updatable = false) private String id;
    @Column(name = "draft_id", length = 36, nullable = false, updatable = false) private String draftId;
    @Column(name = "factory_id", length = 50, nullable = false, updatable = false) private String factoryId;
    @Column(nullable = false) private Integer ordinal;
    @Column(name = "position_code", length = 60, nullable = false) private String positionCode;
    @Column(name = "position_title", length = 200, nullable = false) private String positionTitle;
    @Column(name = "department", length = 30, nullable = false) private String department;
    @Column(name = "platform_role", length = 40, nullable = false) private String platformRole;
    @Column(name = "minimum_headcount", nullable = false) private Integer minimumHeadcount;
    @Column(name = "required_competencies", length = 300, nullable = false) private String requiredCompetencies;
    @Column(name = "training_refresh_months", nullable = false) private Integer trainingRefreshMonths;
    @Column(name = "health_certificate_required", nullable = false) private Boolean healthCertificateRequired;
    @Column(name = "privileged_role", nullable = false) private Boolean privilegedRole;
    @Column(name = "segregation_of_duties_required", nullable = false) private Boolean segregationOfDutiesRequired;
    @Column(name = "access_review_interval_months", nullable = false) private Integer accessReviewIntervalMonths;
    @Column(name = "food_handling_position", nullable = false) private Boolean foodHandlingPosition;
    @Column(name = "change_reason", length = 500, nullable = false) private String changeReason;
}
