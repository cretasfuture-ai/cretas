package com.cretas.aims.blueprint.draftentry;
import com.cretas.aims.service.supplierreceivingdraft.SupplierReceivingPolicyDraftCommand;
import com.cretas.aims.service.supplierreceivingdraft.SupplierReceivingPolicyDraftModels.DraftRequest;
import com.cretas.aims.service.supplierreceivingdraft.SupplierReceivingPolicyDraftModels.*;
import org.springframework.stereotype.Component;
import java.util.List;
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component public class SupplierReceivingDraftProposer implements CapabilityDraftProposer {
 private static final String WHY="蓝图没有供应商来料验收、抽样、隔离和资质要求的完整事实";
 private static <E extends Enum<E>> List<Option> opts(Class<E> c){return java.util.Arrays.stream(c.getEnumConstants()).map(e->new Option(e.name(),e.name())).toList();}
 private static <E extends Enum<E>> String ec(Class<E> c){return java.util.Arrays.stream(c.getEnumConstants()).map(Enum::name).collect(java.util.stream.Collectors.joining("、"))+" 之一";}
 private static List<RequiredInput> fields(){return List.of(
  RequiredInput.text("supplierId","供应商",WHY,"供应商标识，最长 128 位",128),
  RequiredInput.oneOf("materialRefType","物料引用类型",WHY,ec(MaterialRefType.class),opts(MaterialRefType.class)),
  RequiredInput.text("materialRefId","物料引用",WHY,"物料标识，最长 128 位",128),
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
 public Proposed propose(Source s){return W2DraftProposerSupport.single(capabilityId(),"供应商收货策略",new DraftRequest(s.factoryId(),s.blueprint().getId(),s.blueprint().getVersion(),s.sourceDigest(),0,null,null,null),List.of(RequiredInput.list("policies","收货策略",WHY,"至少 1 条策略，最多 200 条","策略",1,200,fields())),s);}
}
