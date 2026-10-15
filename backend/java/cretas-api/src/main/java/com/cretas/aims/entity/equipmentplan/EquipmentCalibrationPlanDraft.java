package com.cretas.aims.entity.equipmentplan;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

@Entity
@Table(name="equipment_plan_drafts", uniqueConstraints={
        @UniqueConstraint(name="uk_equipment_plan_draft_id_factory", columnNames={"id","factory_id"}),
        @UniqueConstraint(name="uk_equipment_plan_draft_idempotency", columnNames={"factory_id","idempotency_key"})
})
@Where(clause="deleted_at IS NULL")
@Getter @Setter @SuperBuilder @NoArgsConstructor @AllArgsConstructor
public class EquipmentCalibrationPlanDraft extends BaseEntity {
    @Id @Column(length=36, nullable=false, updatable=false) private String id;
    @Column(name="factory_id", length=50, nullable=false, updatable=false) private String factoryId;
    @Column(name="blueprint_id", length=100, nullable=false, updatable=false) private String blueprintId;
    @Column(name="blueprint_version", nullable=false, updatable=false) private Long blueprintVersion;
    @Column(name="source_digest", length=64, nullable=false, updatable=false) private String sourceDigest;
    @Column(name="source_snapshot_digest", length=64, nullable=false, updatable=false) private String sourceSnapshotDigest;
    @Column(name="desired_digest", length=64, nullable=false, updatable=false) private String desiredDigest;
    @Column(name="preview_digest", length=64, nullable=false, updatable=false) private String previewDigest;
    @Column(name="request_digest", length=64, nullable=false, updatable=false) private String requestDigest;
    @Column(name="idempotency_key", length=128, nullable=false, updatable=false) private String idempotencyKey;
    @Column(name="operator_id", updatable=false) private Long operatorId;

    /** Typed draft identity; NULL pair preserves historical rows whose actor namespace is unknown. */
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @jakarta.persistence.Column(name = "actor_type", length = 32)
    private com.cretas.aims.security.AuthenticatedActorType actorType;

    @jakarta.persistence.Column(name = "actor_id")
    private Long actorId;
    @Column(name="plan_count", nullable=false) private Integer planCount;
    @Column(name="process_ref_count", nullable=false) private Integer processRefCount;
    @Column(name="dependency_basis", length=80, nullable=false) private String dependencyBasis;
    @Column(name="dependency_closure_complete", nullable=false) private Boolean dependencyClosureComplete;
    @Column(name="status", length=20, nullable=false) private String status;
    @Version @Column(name="lock_version", nullable=false) private Long lockVersion;
}
