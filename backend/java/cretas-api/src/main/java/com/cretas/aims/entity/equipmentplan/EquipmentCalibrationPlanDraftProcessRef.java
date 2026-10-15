package com.cretas.aims.entity.equipmentplan;
import com.cretas.aims.entity.BaseEntity; import jakarta.persistence.*; import lombok.*; import lombok.experimental.SuperBuilder; import org.hibernate.annotations.Where;
@Entity @Table(name="equipment_plan_draft_process_refs",
 uniqueConstraints=@UniqueConstraint(name="uk_equipment_plan_process_parent_ordinal",columnNames={"draft_id","factory_id","ordinal"}),
 indexes={
  @Index(name="idx_equipment_plan_process_ref", columnList="factory_id,draft_id,ordinal"),
  @Index(name="idx_equipment_plan_process_live_identity",columnList="draft_id,factory_id,equipment_entry_ordinal,process_id"),
  @Index(name="idx_equipment_plan_process_draft_identity",columnList="draft_id,factory_id,equipment_entry_ordinal,process_draft_id")
 }) @Where(clause="deleted_at IS NULL")
@Getter @Setter @SuperBuilder @NoArgsConstructor @AllArgsConstructor
public class EquipmentCalibrationPlanDraftProcessRef extends BaseEntity {
 @Id @Column(length=36,nullable=false,updatable=false) private String id;
 @Column(name="draft_id",length=36,nullable=false,updatable=false) private String draftId;
 @Column(name="factory_id",length=50,nullable=false,updatable=false) private String factoryId;
 @Column(nullable=false) private Integer ordinal;
 @Column(name="equipment_entry_ordinal",nullable=false) private Integer equipmentEntryOrdinal;
 @Column(name="process_id",length=64) private String processId;
 @Column(name="process_draft_id",length=36) private String processDraftId;
 @Column(name="process_snapshot_digest",length=64,nullable=false) private String processSnapshotDigest;
}
