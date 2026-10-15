package com.cretas.aims.blueprint.draftentry;
import com.cretas.aims.service.hygieniczonedraft.HygienicZoneDraftCommand;
import com.cretas.aims.service.hygieniczonedraft.HygienicZoneDraftModels.DraftRequest;
import com.cretas.aims.service.hygieniczonedraft.HygienicZoneDraftModels.HygieneLevel;
import com.cretas.aims.service.hygieniczonedraft.HygienicZoneDraftModels.TemperatureClass;
import com.cretas.aims.service.hygieniczonedraft.HygienicZoneDraftModels.EntryPolicy;
import com.cretas.aims.service.hygieniczonedraft.HygienicZoneDraftModels.FlowType;
import com.cretas.aims.service.hygieniczonedraft.HygienicZoneDraftModels.FlowDirection;
import com.cretas.aims.service.hygieniczonedraft.HygienicZoneDraftModels.CrossingPolicy;
import com.cretas.aims.service.hygieniczonedraft.HygienicZoneDraftModels.ControlPoint;
import org.springframework.stereotype.Component;
import java.util.List;
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component public class HygienicZonesDraftProposer implements CapabilityDraftProposer {
 private static final String WHY_ZONE="蓝图没有卫生分区的完整边界、用途、卫生等级和进入规则";
 private static final String WHY_FLOW="蓝图没有人员、物料和空气流向的完整交叉控制事实";
 private static <E extends Enum<E>> List<Option> opts(Class<E> c){
  return java.util.Arrays.stream(c.getEnumConstants()).map(e -> new Option(e.name(), e.name())).toList();
 }
 private static <E extends Enum<E>> String enumConstraint(Class<E> c){return java.util.Arrays.stream(c.getEnumConstants()).map(Enum::name).collect(java.util.stream.Collectors.joining("、"))+" 之一";}
 private static List<RequiredInput> zoneFields(){return List.of(
  RequiredInput.text("zoneCode","分区编码",WHY_ZONE,"大写字母或数字开头，只含大写字母、数字、下划线、连字符，最长 64 位",64),
  RequiredInput.text("zoneName","分区名称",WHY_ZONE,"1–200 字",200),
  RequiredInput.oneOf("hygieneLevel","卫生等级",WHY_ZONE,enumConstraint(HygieneLevel.class),opts(HygieneLevel.class)),
  RequiredInput.text("purpose","用途",WHY_ZONE,"1–500 字",500),
  RequiredInput.oneOf("temperatureClass","温度类别",WHY_ZONE,enumConstraint(TemperatureClass.class),opts(TemperatureClass.class)),
  RequiredInput.oneOf("entryPolicy","进入规则",WHY_ZONE,enumConstraint(EntryPolicy.class),opts(EntryPolicy.class)),
  RequiredInput.text("changeReason","变更原因",WHY_ZONE,"1–500 字",500));}
 private static List<RequiredInput> flowFields(){return List.of(
  RequiredInput.oneOf("flowType","流向类型",WHY_FLOW,enumConstraint(FlowType.class),opts(FlowType.class)),
  RequiredInput.text("fromZoneCode","起始分区",WHY_FLOW,"已定义的分区编码，最长 64 位",64),
  RequiredInput.text("toZoneCode","目标分区",WHY_FLOW,"已定义的分区编码，最长 64 位",64),
  RequiredInput.oneOf("direction","方向",WHY_FLOW,enumConstraint(FlowDirection.class),opts(FlowDirection.class)),
  RequiredInput.oneOf("crossingPolicy","交叉规则",WHY_FLOW,enumConstraint(CrossingPolicy.class),opts(CrossingPolicy.class)),
  RequiredInput.oneOf("controlPoint","控制点",WHY_FLOW,enumConstraint(ControlPoint.class),opts(ControlPoint.class)),
  RequiredInput.text("rationale","设置理由",WHY_FLOW,"1–500 字",500));}
 public String capabilityId(){return HygienicZoneDraftCommand.CAPABILITY_ID;}
 public Proposed propose(Source s){return W2DraftProposerSupport.single(capabilityId(),"卫生分区",new DraftRequest(s.factoryId(),s.blueprint().getId(),s.blueprint().getVersion(),s.sourceDigest(),null,null,null,null,0),List.of(
  RequiredInput.list("zones","卫生分区",WHY_ZONE,"至少 1 个分区；编码不能重复","分区",1,null,zoneFields()),
  RequiredInput.list("flows","人员/物料/空气流向",WHY_FLOW,"至少 3 条，且必须分别覆盖 PERSONNEL、MATERIAL、AIR","流向",3,null,flowFields())),s);}
}
