package com.cretas.aims.blueprint.draftentry;
import com.cretas.aims.service.supplierreceivingdraft.SupplierReceivingPolicyDraftCommand;
import com.cretas.aims.service.supplierreceivingdraft.SupplierReceivingPolicyDraftModels.DraftRequest;
import com.cretas.aims.service.supplierreceivingdraft.SupplierReceivingPolicyDraftModels.*;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.material.MaterialTypeDraftRepository;
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component public class SupplierReceivingDraftProposer implements CapabilityDraftProposer {
 private static final String WHY="蓝图没有供应商来料验收、抽样、隔离和资质要求的完整事实";
 private static <E extends Enum<E>> List<Option> opts(Class<E> c){return java.util.Arrays.stream(c.getEnumConstants()).map(e->new Option(e.name(),DraftEnumLabels.label(e))).toList();}
 private static <E extends Enum<E>> String ec(Class<E> c){return java.util.Arrays.stream(c.getEnumConstants()).map(DraftEnumLabels::label).collect(java.util.stream.Collectors.joining("、"))+" 之一";}
 private final MaterialTypeDraftRepository materialDrafts; private final RawMaterialTypeRepository liveMaterials; private final SupplierRepository suppliers;
 public SupplierReceivingDraftProposer(MaterialTypeDraftRepository materialDrafts, RawMaterialTypeRepository liveMaterials, SupplierRepository suppliers){this.materialDrafts=materialDrafts;this.liveMaterials=liveMaterials;this.suppliers=suppliers;}
 /** 供应商：本厂启用的供应商做成下拉；一个都没有时才退回手填，并说明原因。 */
 private RequiredInput supplierInput(Source s){
  List<Option> options=new ArrayList<>();
  suppliers.findByFactoryIdAndIsActive(s.factoryId(),true).forEach(x->options.add(new Option(x.getId(),(x.getName()==null||x.getName().isBlank()?x.getId():x.getName()))));
  if(options.isEmpty())return RequiredInput.text("supplierId","供应商",WHY,"本厂还没有启用的供应商，请先在供应商档案里建好；这里填供应商标识，最长 128 位",128);
  return RequiredInput.oneOf("supplierId","供应商",WHY,"从本厂已启用的供应商里选",options);}
 /** 物料：本蓝图版本的待转正物料草稿 + 本厂启用的正式物料，让人选而不是手敲标识。 */
 private RequiredInput materialInput(Source s){
  List<Option> options=new ArrayList<>();
  materialDrafts.findByFactoryIdAndBlueprintIdAndBlueprintVersionAndStatusOrderByDesiredNameAsc(s.factoryId(),s.blueprint().getId(),s.blueprint().getVersion()==null?null:s.blueprint().getVersion().longValue(),"DRAFT")
    .forEach(d->options.add(new Option(d.getId(),"草稿 · "+d.getDesiredName()+(d.getNaturalKey()==null?"":"（"+d.getNaturalKey()+"）"))));
  liveMaterials.findByFactoryIdAndIsActive(s.factoryId(),true)
    .forEach(m->options.add(new Option(m.getId(),"正式 · "+m.getName()+(m.getCode()==null?"":"（"+m.getCode()+"）"))));
  String rule="选「草稿 · …」是还没转正的物料；选「正式 · …」的物料，该供应商必须已有这个物料的有效供货关系";
  if(options.isEmpty())return RequiredInput.text("materialRefId","物料引用",WHY,"本厂还没有物料草稿或正式物料，请先写入「物料」草稿；"+rule,128);
  return RequiredInput.oneOf("materialRefId","物料引用",WHY,rule,options);}
 private List<RequiredInput> fields(Source s){return List.of(
  supplierInput(s),
  materialInput(s),
  RequiredInput.oneOf("acceptanceMode","验收模式",WHY,ec(AcceptanceMode.class),opts(AcceptanceMode.class)),
  RequiredInput.oneOf("qualificationExpiryPolicy","资质到期规则",WHY,ec(QualificationExpiryPolicy.class),opts(QualificationExpiryPolicy.class)),
  RequiredInput.decimal("samplePercentage","抽样百分比",WHY,"0–100",java.math.BigDecimal.ZERO,java.math.BigDecimal.valueOf(100)),
  RequiredInput.integer("minSampleSize","最小样本数",WHY,"0–100000",0,100000),
  RequiredInput.integer("maxSampleSize","最大样本数",WHY,"0–100000",0,100000),
  RequiredInput.bool("quarantineOnFailure","失败后隔离",WHY,"是 / 否"),
  RequiredInput.bool("receivingEvidenceRequired","必须收货证据",WHY,"是 / 否"),
  RequiredInput.text("policyBasis","政策依据",WHY,"1–500 字",500),
  RequiredInput.values("requiredQualifications","要求的资质",WHY,"至少 1 项资质，最多 8 项","资质",1,8,
          RequiredInput.oneOf("requiredQualifications","资质",WHY,ec(QualificationType.class),opts(QualificationType.class))));}
 public String capabilityId(){return SupplierReceivingPolicyDraftCommand.CAPABILITY_ID;}
 public Proposed propose(Source s){return W2DraftProposerSupport.single(capabilityId(),"供应商收货策略",new DraftRequest(s.factoryId(),s.blueprint().getId(),s.blueprint().getVersion(),s.sourceDigest(),0,null,null,null),List.of(RequiredInput.list("policies","收货策略",WHY,"至少 1 条策略，最多 200 条","策略",1,200,fields(s))),s);}
}
