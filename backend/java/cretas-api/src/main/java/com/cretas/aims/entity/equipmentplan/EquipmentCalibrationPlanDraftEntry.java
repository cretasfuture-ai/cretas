package com.cretas.aims.entity.equipmentplan;
import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*; import lombok.*; import lombok.experimental.SuperBuilder; import org.hibernate.annotations.Where;
@Entity @Table(name="equipment_plan_draft_entries",
 uniqueConstraints={
  @UniqueConstraint(name="uk_equipment_plan_entry_id_factory",columnNames={"id","factory_id"}),
  @UniqueConstraint(name="uk_equipment_plan_entry_parent_ordinal",columnNames={"draft_id","factory_id","ordinal"})
 }, indexes=@Index(name="idx_equipment_plan_entry_parent", columnList="factory_id,draft_id,ordinal")) @Where(clause="deleted_at IS NULL")
@Getter @Setter @SuperBuilder @NoArgsConstructor @AllArgsConstructor
public class EquipmentCalibrationPlanDraftEntry extends BaseEntity {
 @Id @Column(length=36, nullable=false, updatable=false) private String id;
 @Column(name="draft_id",length=36,nullable=false,updatable=false) private String draftId;
 @Column(name="factory_id",length=50,nullable=false,updatable=false) private String factoryId;
 @Column(nullable=false) private Integer ordinal;
 @Column(name="equipment_id",nullable=false,updatable=false) private Long equipmentId;
 @Column(name="equipment_snapshot_digest",length=64,nullable=false,updatable=false) private String equipmentSnapshotDigest;
 @Column(name="plan_type",length=20,nullable=false) private String planType;
 @Column(name="frequency_kind",length=30,nullable=false) private String frequencyKind;
 @Column(name="frequency_value",nullable=false) private Integer frequencyValue;
 @Column(name="due_policy",length=50,nullable=false) private String duePolicy;
 @Column(length=500,nullable=false) private String method;
 @Column(name="tolerance_value",precision=18,scale=6) private java.math.BigDecimal toleranceValue;
 @Column(name="tolerance_unit",length=20) private String toleranceUnit;
 @Column(name="responsible_role",length=100,nullable=false) private String responsibleRole;
 @Column(name="required_evidence",length=500,nullable=false) private String requiredEvidence;
 @Column(name="verification_method",length=500,nullable=false) private String verificationMethod;
 @Column(name="estimated_duration_minutes") private Integer estimatedDurationMinutes;
 @Column(name="change_reason",length=500,nullable=false) private String changeReason;
}
