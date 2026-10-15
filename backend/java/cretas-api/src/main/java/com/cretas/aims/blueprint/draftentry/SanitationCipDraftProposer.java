package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.service.sanitationcipdraft.SanitationCipDraftCommand;
import com.cretas.aims.service.sanitationcipdraft.SanitationCipDraftModels.*;
import com.cretas.aims.entity.equipmentplan.EquipmentCalibrationPlanDraft;
import com.cretas.aims.repository.equipmentplan.EquipmentCalibrationPlanDraftRepository;
import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.List;

@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class SanitationCipDraftProposer implements CapabilityDraftProposer {
    private static final String WHY="蓝图没有清洁方式、化学浓度、温度、时间、频率、验证和责任人的完整事实";
    private static List<Option> opts(Class<? extends Enum<?>> type){return Arrays.stream(type.getEnumConstants()).map(e->new Option(e.name(),e.name())).toList();}
    private static String ec(Class<? extends Enum<?>> type){return String.join("、",opts(type).stream().map(Option::value).toList())+" 之一";}
    private static List<RequiredInput> fields(){return List.of(
            RequiredInput.text("productCode","产品编码",WHY,"最长 100 位",100),
            RequiredInput.text("routeNodeId","路线节点",WHY,"最长 128 位",128),
            RequiredInput.oneOf("targetType","目标类型",WHY,ec(TargetType.class),opts(TargetType.class)),
            RequiredInput.integer("equipmentId","设备 ID",WHY,"设备目标必填，正整数",1,Integer.MAX_VALUE).allowNull(),
            RequiredInput.oneOf("method","清洁方式",WHY,ec(SanitationMethod.class),opts(SanitationMethod.class)),
            RequiredInput.oneOf("chemicalType","化学剂类型",WHY,ec(ChemicalType.class),opts(ChemicalType.class)),
            RequiredInput.decimal("concentrationMin","浓度下限",WHY,"非负，最多 100000",java.math.BigDecimal.ZERO,java.math.BigDecimal.valueOf(100000)),
            RequiredInput.decimal("concentrationTarget","目标浓度",WHY,"非负，最多 100000",java.math.BigDecimal.ZERO,java.math.BigDecimal.valueOf(100000)),
            RequiredInput.decimal("concentrationMax","浓度上限",WHY,"非负，最多 100000",java.math.BigDecimal.ZERO,java.math.BigDecimal.valueOf(100000)),
            RequiredInput.oneOf("concentrationUnit","浓度单位",WHY,ec(ConcentrationUnit.class),opts(ConcentrationUnit.class)),
            RequiredInput.decimal("temperatureMinC","温度下限",WHY,"摄氏度范围",java.math.BigDecimal.valueOf(-50),java.math.BigDecimal.valueOf(200)),
            RequiredInput.decimal("temperatureTargetC","目标温度",WHY,"摄氏度范围",java.math.BigDecimal.valueOf(-50),java.math.BigDecimal.valueOf(200)),
            RequiredInput.decimal("temperatureMaxC","温度上限",WHY,"摄氏度范围",java.math.BigDecimal.valueOf(-50),java.math.BigDecimal.valueOf(200)),
            RequiredInput.integer("contactTimeSeconds","接触时间秒数",WHY,"0–86400",0,86400),
            RequiredInput.decimal("flowRateLitersPerMinute","流量 L/min",WHY,"非负，最多 10000",java.math.BigDecimal.ZERO,java.math.BigDecimal.valueOf(10000)).allowNull(),
            RequiredInput.integer("preRinseSeconds","预冲洗秒数",WHY,"0–86400",0,86400),
            RequiredInput.integer("finalRinseSeconds","终冲洗秒数",WHY,"0–86400",0,86400),
            RequiredInput.oneOf("frequency","频率",WHY,ec(Frequency.class),opts(Frequency.class)),
            RequiredInput.values("verificationMethods","验证方法",WHY,"至少 1 项，最多 7 项","方法",1,7,RequiredInput.oneOf("verificationMethods","验证方法",WHY,ec(VerificationMethod.class),opts(VerificationMethod.class))),
            RequiredInput.oneOf("responsibleRole","责任角色",WHY,ec(ResponsibleRole.class),opts(ResponsibleRole.class)),
            RequiredInput.values("requiredEvidence","要求证据",WHY,"至少 1 项，最多 4 项","证据",1,4,RequiredInput.oneOf("requiredEvidence","要求证据",WHY,ec(EvidenceType.class),opts(EvidenceType.class))),
            RequiredInput.bool("holdUntilVerified","验证前保持隔离",WHY,"是 / 否"),
            RequiredInput.text("changeReason","变更原因",WHY,"1–500 字",500));}
    public String capabilityId(){return SanitationCipDraftCommand.CAPABILITY_ID;}
    private final EquipmentCalibrationPlanDraftRepository equipmentPlans;
    public SanitationCipDraftProposer(EquipmentCalibrationPlanDraftRepository equipmentPlans){this.equipmentPlans=equipmentPlans;}
    public Proposed propose(Source source){
        EquipmentCalibrationPlanDraft plan=UpstreamDraftPins.latestEquipmentPlan(equipmentPlans,source).orElse(null);
        if(plan==null)return Proposed.blocked("UPSTREAM_MISSING","清洁消毒必须绑定本蓝图同一版本且依赖闭包完整的设备校准草稿","先完成设备校准计划");
        DraftRequest request=new DraftRequest(source.factoryId(),source.blueprint().getId(),source.blueprint().getVersion(),source.sourceDigest(),new EquipmentPlanPin(plan.getId(),plan.getDesiredDigest()),List.of(),null,null,0L);
        return Proposed.single(capabilityId(),"清洁消毒计划",request,List.of(
                RequiredInput.list("templates","清洁消毒模板",WHY,"至少 1 条，最多 100 条", "模板",1,100,fields())),
                List.of(new Evidence("equipmentPlan",plan.getId()+" / "+plan.getDesiredDigest()),new Evidence("sourceDigest",source.sourceDigest())));
    }
}
