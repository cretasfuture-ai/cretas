package com.cretas.aims.service.equipmentplandraft;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class EquipmentCalibrationPlanDraftModels {
 public static final String SCHEMA_VERSION="equipment-plan-draft/v1", DEPENDENCY_BASIS="CAPABILITY_CONTRACT:processes";
 private EquipmentCalibrationPlanDraftModels(){}
 public enum PlanType { MAINTENANCE,CALIBRATION }
 public enum FrequencyKind { RUNNING_HOURS,CALENDAR_DAYS,CALENDAR_MONTHS,EVENT }
 public enum DuePolicy { NEXT_DUE_FROM_LAST_COMPLETION,NEXT_DUE_FROM_PLAN_EFFECTIVE_DATE }
 @JsonIgnoreProperties(ignoreUnknown = false)
 public record ProcessRef(String processId,String processDraftId,String processSnapshotDigest){ public ProcessRef { processId=clean(processId); processDraftId=clean(processDraftId); processSnapshotDigest=clean(processSnapshotDigest).toLowerCase(Locale.ROOT); } }
 @JsonIgnoreProperties(ignoreUnknown = false)
 public record PlanEntry(Long equipmentId,String equipmentSnapshotDigest,PlanType planType,FrequencyKind frequencyKind,Integer frequencyValue,DuePolicy duePolicy,String method,BigDecimal toleranceValue,String toleranceUnit,String responsibleRole,String requiredEvidence,String verificationMethod,Integer estimatedDurationMinutes,String changeReason,List<ProcessRef> processRefs){ public PlanEntry { equipmentSnapshotDigest=clean(equipmentSnapshotDigest).toLowerCase(Locale.ROOT); method=clean(method); toleranceUnit=clean(toleranceUnit); responsibleRole=clean(responsibleRole); requiredEvidence=clean(requiredEvidence); verificationMethod=clean(verificationMethod); changeReason=clean(changeReason); processRefs=processRefs==null?List.of():List.copyOf(processRefs); } }
 @JsonIgnoreProperties(ignoreUnknown = false)
 public record DraftRequest(String factoryId,String blueprintId,long blueprintVersion,String sourceDigest,List<PlanEntry> plans,String confirmedPreviewDigest,String idempotencyKey,long operatorId){ public DraftRequest { factoryId=clean(factoryId); blueprintId=clean(blueprintId); sourceDigest=clean(sourceDigest).toLowerCase(Locale.ROOT); plans=plans==null?List.of():List.copyOf(plans); confirmedPreviewDigest=clean(confirmedPreviewDigest).toLowerCase(Locale.ROOT); idempotencyKey=clean(idempotencyKey); } }
 public record AuthenticatedOperator(com.cretas.aims.security.ActorRef actor,String factoryId,Set<String> permissions){
  public AuthenticatedOperator { factoryId=clean(factoryId); permissions=permissions==null?Set.of():Set.copyOf(permissions); }
  public AuthenticatedOperator(long operatorId,String factoryId,Set<String> permissions){ this(new com.cretas.aims.security.ActorRef(com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER,operatorId),factoryId,permissions); }
  public long operatorId(){ return actor.id(); }
 }
 public record DraftIssue(String code,String target,String message,String recovery){}
 public record DraftPreview(String schemaVersion,String status,String factoryId,List<String> dependencies,List<PlanEntry> plans,String sourceSnapshotDigest,String desiredDigest,String previewDigest,List<DraftIssue> issues,int expectedBusinessWriteCount,int expectedPersistedRowCount,int equipmentWriteCount,int maintenanceWriteCount,int ioTWriteCount,int alertWriteCount,String publishState,String activationState){ public DraftPreview { schemaVersion=SCHEMA_VERSION; dependencies=List.of("processes"); plans=List.copyOf(plans); issues=List.copyOf(issues); expectedBusinessWriteCount="READY".equals(status)?1:0; expectedPersistedRowCount="READY".equals(status)?1+plans.size()+plans.stream().mapToInt(p->p.processRefs().size()).sum():0; equipmentWriteCount=0;maintenanceWriteCount=0;ioTWriteCount=0;alertWriteCount=0;publishState="NOT_PUBLISHED";activationState="NOT_ACTIVATED"; } }
 public record DraftReceipt(String schemaVersion,String receiptId,String factoryId,String draftId,String status,String sourceSnapshotDigest,String desiredDigest,String previewDigest,String requestDigest,int planCount,int processRefCount,long lockVersion,boolean replayed,int businessWriteCount,int originalBusinessWriteCount,int persistedRowCount,int equipmentWriteCount,int maintenanceWriteCount,int ioTWriteCount,int alertWriteCount,String publishState,String activationState,String recovery){ public DraftReceipt {schemaVersion=SCHEMA_VERSION;publishState="NOT_PUBLISHED";activationState="NOT_ACTIVATED";} }
 public record DraftReadback(String schemaVersion,String factoryId,String draftId,String status,List<PlanEntry> plans,int equipmentWriteCount,int maintenanceWriteCount,int ioTWriteCount,int alertWriteCount,String publishState,String activationState){ public DraftReadback {schemaVersion=SCHEMA_VERSION;plans=List.copyOf(plans);equipmentWriteCount=0;maintenanceWriteCount=0;ioTWriteCount=0;alertWriteCount=0;publishState="NOT_PUBLISHED";activationState="NOT_ACTIVATED";} }
 public record RecoveryPlan(String schemaVersion,String factoryId,String draftId,String expectedDesiredDigest,String action,String effect,String publishState,String activationState){ public RecoveryPlan {schemaVersion=SCHEMA_VERSION;publishState="NOT_PUBLISHED";activationState="NOT_ACTIVATED";} }
 static String clean(String v){return v==null?"":v.trim();}
}
