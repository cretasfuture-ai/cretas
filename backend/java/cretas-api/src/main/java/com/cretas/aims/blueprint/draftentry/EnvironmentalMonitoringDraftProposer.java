package com.cretas.aims.blueprint.draftentry;
import com.cretas.aims.repository.sanitationcipdraft.SanitationCipTemplateDraftRepository;
import com.cretas.aims.repository.hygieniczone.HygienicZoneDraftRepository;
import com.cretas.aims.service.environmentalmonitoringdraft.EnvironmentalMonitoringDraftCommand;
import com.cretas.aims.service.environmentalmonitoringdraft.EnvironmentalMonitoringDraftModels.*;
import org.springframework.stereotype.Component;
import java.util.Arrays; import java.util.List;
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component public class EnvironmentalMonitoringDraftProposer implements CapabilityDraftProposer {
 private static final String WHY="蓝图没有环境采样点、目标微生物、频率、阈值、验证和证据的完整事实";
 private static List<Option> opts(Class<? extends Enum<?>> c){return Arrays.stream(c.getEnumConstants()).map(e->new Option(e.name(),e.name())).toList();}
 private static String ec(Class<? extends Enum<?>> c){return String.join("、",opts(c).stream().map(Option::value).toList())+" 之一";}
 private static List<RequiredInput> fields(){return List.of(
  RequiredInput.text("zoneCode","卫生分区",WHY,"最长 64 位",64), RequiredInput.text("sanitationTemplateCode","清洁模板",WHY,"最长 128 位",128),
  RequiredInput.oneOf("contactClass","接触类别",WHY,ec(ContactClass.class),opts(ContactClass.class)), RequiredInput.oneOf("sampleMedium","样本介质",WHY,ec(SampleMedium.class),opts(SampleMedium.class)),
  RequiredInput.oneOf("targetOrganism","目标微生物",WHY,ec(TargetOrganism.class),opts(TargetOrganism.class)), RequiredInput.oneOf("samplingTiming","采样时机",WHY,ec(SamplingTiming.class),opts(SamplingTiming.class)),
  RequiredInput.oneOf("samplingFrequency","采样频率",WHY,ec(SamplingFrequency.class),opts(SamplingFrequency.class)), RequiredInput.integer("sampleSizeCount","样本数量",WHY,"1–1000",1,1000),
  RequiredInput.oneOf("resultUnit","结果单位",WHY,ec(ResultUnit.class),opts(ResultUnit.class)), RequiredInput.decimal("alertThreshold","警戒阈值",WHY,"非负",java.math.BigDecimal.ZERO,java.math.BigDecimal.valueOf(100000)).allowNull(),
  RequiredInput.decimal("actionThreshold","行动阈值",WHY,"非负",java.math.BigDecimal.ZERO,java.math.BigDecimal.valueOf(100000)).allowNull(), RequiredInput.integer("resultHoldHours","结果保持小时",WHY,"0–720",0,720),
  RequiredInput.oneOf("responsibleRole","责任角色",WHY,ec(ResponsibleRole.class),opts(ResponsibleRole.class)), RequiredInput.values("requiredEvidence","要求证据",WHY,"至少 1 项，最多 4 项","证据",1,4,RequiredInput.oneOf("requiredEvidence","证据",WHY,ec(EvidenceType.class),opts(EvidenceType.class))),
  RequiredInput.text("changeReason","变更原因",WHY,"1–500 字",500));}
 private final SanitationCipTemplateDraftRepository sanitation; private final HygienicZoneDraftRepository zones;
 public EnvironmentalMonitoringDraftProposer(SanitationCipTemplateDraftRepository sanitation,HygienicZoneDraftRepository zones){this.sanitation=sanitation;this.zones=zones;}
 public String capabilityId(){return EnvironmentalMonitoringDraftCommand.CAPABILITY_ID;}
 public Proposed propose(Source source){var san=UpstreamDraftPins.latestSanitation(sanitation,source).orElse(null);var zone=UpstreamDraftPins.latestHygienicZone(zones,source).orElse(null);if(san==null||zone==null)return Proposed.blocked("UPSTREAM_MISSING","环境监测必须绑定本蓝图同一版本的清洁消毒和卫生分区草稿","先完成清洁消毒和卫生分区草稿");
  var request=new DraftRequest(source.factoryId(),source.blueprint().getId(),source.blueprint().getVersion(),source.sourceDigest(),new SanitationTemplatePin(san.getId(),san.getDesiredDigest()),new HygienicZonePin(zone.getId(),zone.getDesiredDigest()),List.of(),null,null,0L);
  return Proposed.single(capabilityId(),"环境监测计划",request,List.of(RequiredInput.list("points","环境监测点",WHY,"至少 1 条，最多 200 条","监测点",1,200,fields())),List.of(new Evidence("sanitation",san.getId()+" / "+san.getDesiredDigest()),new Evidence("hygienicZone",zone.getId()+" / "+zone.getDesiredDigest())));}
}
