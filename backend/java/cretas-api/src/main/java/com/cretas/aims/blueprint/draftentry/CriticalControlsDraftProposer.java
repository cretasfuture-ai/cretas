package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest;
import com.cretas.aims.entity.hazardanalysisdraft.HazardAnalysisDraft;
import com.cretas.aims.entity.hazardanalysisdraft.HazardAnalysisDraftItem;
import com.cretas.aims.repository.hazardanalysisdraft.HazardAnalysisDraftItemRepository;
import com.cretas.aims.repository.hazardanalysisdraft.HazardAnalysisDraftRepository;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftCommand;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftModels.ControlItem;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftModels.DeviationDisposition;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftModels.DraftRequest;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftModels.FrequencyUnit;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftModels.HazardPin;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftModels.LimitKind;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftModels.MonitoringMethod;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftModels.ParameterType;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftModels.ResponsibleRole;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftModels.VerificationMethod;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDimension;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code critical-controls} proposal: one control item per <b>significant</b> hazard of the latest
 * same-lineage hazard-analysis DRAFT, with the interview's critical limits where a
 * {@code suggestedData.haccpCheckpoints} fact names the same step and hazard type.
 *
 * <p>The command consumes a hazard-analysis DRAFT ({@code HazardPin}) and requires every significant
 * hazard in it to be covered. Production blueprints have no hazard-analysis facts, so in practice
 * there is usually no such DRAFT and this proposal is {@code BLOCKED} — that is the honest answer;
 * a control item cannot reference a hazard that was never analysed.</p>
 *
 * <p>From the interview's checkpoint the proposal fills the limits (both bounds → RANGE, or EXACT
 * when equal; one bound → MINIMUM / MAXIMUM) and the monitoring procedure. The unit is filled only
 * when the platform's unit catalogue resolves it to an active temperature, time, mass or volume unit
 * — then the command's dimension rule forces the parameter type too. Everything else (parameter,
 * decision-tree answers, inclusivity, monitoring/verification plan, corrective action, retention,
 * authorities) the interview never asked and is left for the user.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class CriticalControlsDraftProposer implements CapabilityDraftProposer {

    /** Unit dimension → the only parameter type the command accepts for it. RATIO is ambiguous (pH or concentration). */
    private static final Map<UnitDimension, ParameterType> FORCED_PARAMETER = Map.of(
            UnitDimension.TEMPERATURE, ParameterType.TEMPERATURE,
            UnitDimension.TIME, ParameterType.TIME,
            UnitDimension.MASS, ParameterType.MASS,
            UnitDimension.VOLUME, ParameterType.VOLUME);

    /** The command's limits ({@code CRITICAL_CONTROL_ITEM_FIELDS_REQUIRED}, retention, authorities). */
    static final int MAX_PARAMETER_CODE = 64;
    static final int MAX_NAME = 200;
    static final int MAX_UNIT = 64;
    static final int MAX_PROCEDURE = 1000;
    static final int MAX_RETENTION_DAYS = 36500;
    static final int MAX_AUTHORITIES = 20;

    static final List<Option> LIMIT_KIND_OPTIONS = Option.ofEnum(LimitKind.class, Map.of(
            LimitKind.MINIMUM, "下限", LimitKind.MAXIMUM, "上限", LimitKind.RANGE, "区间", LimitKind.EXACT, "定值"));
    static final List<Option> PARAMETER_TYPE_OPTIONS = Option.ofEnum(ParameterType.class, Map.of(
            ParameterType.TEMPERATURE, "温度", ParameterType.TIME, "时间", ParameterType.MASS, "质量",
            ParameterType.VOLUME, "体积", ParameterType.COUNT, "计数", ParameterType.CONCENTRATION, "浓度",
            ParameterType.ACIDITY_PH, "酸度 pH"));
    static final List<Option> MONITORING_METHOD_OPTIONS = Option.ofEnum(MonitoringMethod.class, Map.of(
            MonitoringMethod.CONTINUOUS_SENSOR, "传感器连续监控", MonitoringMethod.MANUAL_INSTRUMENT, "人工仪器测量",
            MonitoringMethod.VISUAL_CHECK, "目视检查", MonitoringMethod.LAB_TEST, "实验室检测",
            MonitoringMethod.RECORD_REVIEW, "记录审核"));
    static final List<Option> VERIFICATION_METHOD_OPTIONS = Option.ofEnum(VerificationMethod.class, Map.of(
            VerificationMethod.RECORD_REVIEW, "记录审核", VerificationMethod.CALIBRATION_REVIEW, "校准审核",
            VerificationMethod.DIRECT_OBSERVATION, "现场观察", VerificationMethod.SAMPLE_TEST, "抽样检测",
            VerificationMethod.INTERNAL_AUDIT, "内部审核"));
    static final List<Option> RESPONSIBLE_OPTIONS = Option.ofEnum(ResponsibleRole.class, Map.of(
            ResponsibleRole.FACTORY_OWNER, "工厂负责人", ResponsibleRole.PRODUCTION_OWNER, "生产负责人",
            ResponsibleRole.QUALITY_OWNER, "质量负责人", ResponsibleRole.WAREHOUSE_OWNER, "仓储负责人",
            ResponsibleRole.FOOD_SAFETY_OWNER, "食品安全负责人", ResponsibleRole.LINE_LEAD, "产线组长",
            ResponsibleRole.QUALITY_INSPECTOR, "质检员"));
    static final List<Option> DISPOSITION_OPTIONS = Option.ofEnum(DeviationDisposition.class, Map.of(
            DeviationDisposition.HOLD_AFFECTED_PRODUCT, "扣留受影响产品", DeviationDisposition.REPROCESS, "返工",
            DeviationDisposition.DISPOSE, "销毁", DeviationDisposition.ESCALATE_AND_ASSESS, "上报并评估"));

    /** The command's schedule rule ({@code validateSchedule}): interval 1–10000; verification may not be CONTINUOUS. */
    static final int MAX_INTERVAL = 10000;
    static final List<Option> MONITORING_UNIT_OPTIONS = Option.ofEnum(FrequencyUnit.class, Map.of(
            FrequencyUnit.CONTINUOUS, "连续", FrequencyUnit.EVERY_BATCH, "每批", FrequencyUnit.EVENT_DRIVEN, "事件触发",
            FrequencyUnit.MINUTE, "分钟", FrequencyUnit.HOUR, "小时", FrequencyUnit.SHIFT, "班次",
            FrequencyUnit.DAY, "天", FrequencyUnit.WEEK, "周", FrequencyUnit.MONTH, "月"));
    static final List<Option> VERIFICATION_UNIT_OPTIONS = MONITORING_UNIT_OPTIONS.stream()
            .filter(option -> !option.value().equals(FrequencyUnit.CONTINUOUS.name())).toList();
    /** Every component of {@code DecisionAnswers}, asked with the decision tree's own questions. */
    static final List<RequiredInput> DECISION_FIELDS = List.of(
            RequiredInput.bool("controlMeasureAtStep", "这一步有控制措施吗", "建厂访谈没有做判定树", "是 / 否"),
            RequiredInput.bool("stepDesignedToEliminateOrReduce", "这一步是专门为消除或降低这个危害设计的吗",
                    "建厂访谈没有做判定树", "是 / 否"),
            RequiredInput.bool("hazardCouldOccurOrIncrease", "这个危害可能发生或加重到不可接受的程度吗",
                    "建厂访谈没有做判定树", "是 / 否"),
            RequiredInput.bool("laterStepEliminatesOrReduces", "后面的步骤能消除或降低这个危害吗",
                    "建厂访谈没有做判定树", "是 / 否"),
            RequiredInput.bool("operationalPrerequisiteAdequate", "操作性前提方案足以控制这个危害吗",
                    "建厂访谈没有做判定树", "是 / 否"));

    /** Every component of {@code Schedule}. */
    static List<RequiredInput> scheduleFields(String why, List<Option> units) {
        return List.of(
                RequiredInput.integer("interval", "间隔", why,
                        "1–" + MAX_INTERVAL + "；单位选连续、每批或事件触发时填 1", 1, MAX_INTERVAL),
                RequiredInput.oneOf("unit", "单位", why,
                        String.join("、", units.stream().map(Option::label).toList()) + " 之一", units));
    }

    private final HazardAnalysisDraftRepository hazardDrafts;
    private final HazardAnalysisDraftItemRepository hazardItems;
    private final UnitContractService units;

    public CriticalControlsDraftProposer(HazardAnalysisDraftRepository hazardDrafts,
                                         HazardAnalysisDraftItemRepository hazardItems, UnitContractService units) {
        this.hazardDrafts = hazardDrafts;
        this.hazardItems = hazardItems;
        this.units = units;
    }

    @Override
    public String capabilityId() {
        return CriticalControlDraftCommand.CAPABILITY_ID;
    }

    @Override
    public Proposed propose(Source source) {
        HazardAnalysisDraft hazard = UpstreamDraftPins.latestHazardAnalysis(hazardDrafts, source).orElse(null);
        if (hazard == null) {
            return Proposed.blocked(AlertsDraftProposer.UPSTREAM_MISSING,
                    "关键控制必须基于本蓝图同一版本的危害分析草稿，但还没有写危害分析草稿",
                    "先写危害分析草稿");
        }
        var blueprint = source.blueprint();
        ApplyAIInitRequest.SuggestedData data = blueprint.getSourcePayload().getSuggestedData();
        List<ApplyAIInitRequest.HaccpItem> facts = new ArrayList<>();
        if (data != null && data.getHaccpCheckpoints() != null) {
            data.getHaccpCheckpoints().stream()
                    .filter(fact -> fact != null && fact.getName() != null && !fact.getName().isBlank())
                    .forEach(facts::add);
        }
        List<String> authorities = source.capability().getAuthorityRefs() == null
                ? List.of() : source.capability().getAuthorityRefs();
        List<Option> authorityOptions = authorities.stream().distinct().map(code -> new Option(code, code)).toList();

        List<ControlItem> items = new ArrayList<>();
        List<RequiredInput> inputs = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>();
        evidence.add(new Evidence("hazard", hazard.getId() + " / " + hazard.getDesiredDigest()));
        Set<ApplyAIInitRequest.HaccpItem> used = new HashSet<>();
        String reasonPrefix = "依据建厂蓝图 v" + blueprint.getVersion() + " 与危害分析草稿 " + hazard.getId() + "：";

        for (HazardAnalysisDraftItem row : hazardItems.findByDraftIdAndFactoryIdOrderByOrdinal(hazard.getId(), source.factoryId())) {
            if (!Boolean.TRUE.equals(row.getSignificant())) continue;
            int index = items.size();
            String at = "items[" + index + "].";
            String step = row.getRouteNodeLabel() == null ? "" : row.getRouteNodeLabel().trim();
            String name = "“" + step + "（" + row.getHazardType() + "）”";
            evidence.add(new Evidence(at + "hazardItemId", row.getId() + " " + step + " " + row.getHazardType()
                    + " " + row.getHazardDescription()));

            List<ApplyAIInitRequest.HaccpItem> matches = facts.stream()
                    .filter(fact -> fact.getName().trim().equals(step)
                            && fact.getHazardType() != null && fact.getHazardType().trim().equalsIgnoreCase(row.getHazardType()))
                    .toList();
            used.addAll(matches);
            ApplyAIInitRequest.HaccpItem fact = matches.size() == 1 ? matches.get(0) : null;
            if (matches.size() > 1) {
                evidence.add(new Evidence(at + "limit", AlertsDraftProposer.NOT_CARRIED + "访谈里有 " + matches.size()
                        + " 个控制点对应这一危害，无法判断用哪个的限值"));
            }
            items.add(item(at, name, row, fact, source.factoryId(), authorityOptions, reasonPrefix, inputs, evidence));
        }

        for (int i = 0; i < facts.size(); i++) {
            if (!used.contains(facts.get(i))) {
                evidence.add(new Evidence("sourcePayload.suggestedData.haccpCheckpoints[" + i + "]",
                        AlertsDraftProposer.NOT_CARRIED + facts.get(i).getName() + "（" + facts.get(i).getHazardType()
                                + "）在危害分析草稿里没有对应的显著危害"));
            }
        }

        if (!items.isEmpty() && authorityOptions.isEmpty()) {
            // Every item must cite 1–20 of the capability's authorities; with none there is nothing to pick.
            return Proposed.blocked("BLUEPRINT_DRAFT_AUTHORITY_MISSING",
                    "关键控制必须引用本能力的法规或标准依据，但能力目录里这项能力没有可选依据",
                    "先部署带依据的能力目录，再回来预填");
        }
        DraftRequest request = new DraftRequest(source.factoryId(), blueprint.getId(), blueprint.getVersion(),
                source.sourceDigest(), new HazardPin(hazard.getId(), hazard.getDesiredDigest()), items, null, null, 0L);
        return Proposed.single(capabilityId(), "关键控制点", request, inputs, evidence);
    }

    private ControlItem item(String at, String name, HazardAnalysisDraftItem hazard, ApplyAIInitRequest.HaccpItem fact,
                             String factoryId, List<Option> authorities, String reasonPrefix,
                             List<RequiredInput> inputs, List<Evidence> evidence) {
        inputs.add(RequiredInput.text(at + "parameterCode", name + "监控的参数编码",
                "建厂访谈只给了控制点名称，没有给监控参数",
                "大写字母开头，2–" + MAX_PARAMETER_CODE + " 位大写字母、数字或下划线；同一危害内不重复", MAX_PARAMETER_CODE));
        inputs.add(RequiredInput.text(at + "parameterName", name + "监控的参数叫什么",
                "建厂访谈只给了控制点名称，没有给监控参数", "1–" + MAX_NAME + " 字", MAX_NAME));
        inputs.add(RequiredInput.object(at + "decision", name + "的判定树五个问题",
                "建厂访谈没有做判定树", "五个问题都要回答 是 或 否", DECISION_FIELDS));

        LimitKind kind = null;
        BigDecimal lower = null;
        BigDecimal upper = null;
        if (fact != null) {
            BigDecimal min = fact.getCriticalLimitMin();
            BigDecimal max = fact.getCriticalLimitMax();
            if (min != null && max != null && min.compareTo(max) <= 0) {
                kind = min.compareTo(max) == 0 ? LimitKind.EXACT : LimitKind.RANGE;
                lower = min;
                upper = max;
            } else if (min != null && max == null) {
                kind = LimitKind.MINIMUM;
                lower = min;
            } else if (min == null && max != null) {
                kind = LimitKind.MAXIMUM;
                upper = max;
            } else if (min != null) {
                evidence.add(new Evidence(at + "limit", AlertsDraftProposer.NOT_CARRIED + "访谈限值下限 " + min
                        + " 大于上限 " + max));
            }
            if (kind != null) {
                evidence.add(new Evidence(at + "limit", fact.getName() + " " + min + "~" + max + " " + fact.getUnit()));
            }
        }
        if (kind == null) {
            inputs.add(RequiredInput.oneOf(at + "limitKind", name + "的限值是下限、上限、区间还是定值",
                    fact == null ? "建厂访谈没有这个控制点的临界限值" : "建厂访谈给的临界限值不可用",
                    "MINIMUM、MAXIMUM、RANGE、EXACT 之一", LIMIT_KIND_OPTIONS));
            inputs.add(RequiredInput.decimal(at + "lowerLimit", name + "的下限",
                    "建厂访谈没有可用的临界限值", "MINIMUM/RANGE/EXACT 必填，MAXIMUM 留空；最多 19 位有效数字、6 位小数",
                    null, null).allowNull());
            inputs.add(RequiredInput.decimal(at + "upperLimit", name + "的上限",
                    "建厂访谈没有可用的临界限值", "MAXIMUM/RANGE/EXACT 必填，MINIMUM 留空；最多 19 位有效数字、6 位小数",
                    null, null).allowNull());
        }
        inputs.add(RequiredInput.bool(at + "lowerInclusive", name + "等于下限算不算合格",
                "建厂访谈没有问边界", "是 / 否（没有下限时填 否）"));
        inputs.add(RequiredInput.bool(at + "upperInclusive", name + "等于上限算不算合格",
                "建厂访谈没有问边界", "是 / 否（没有上限时填 否）"));

        String unit = null;
        ParameterType parameterType = null;
        String rawUnit = fact == null || fact.getUnit() == null ? "" : fact.getUnit().trim();
        if (!rawUnit.isEmpty()) {
            CanonicalUnit resolved = units.describe(factoryId, rawUnit).orElse(null);
            if (resolved != null && resolved.active() && FORCED_PARAMETER.containsKey(resolved.dimension())) {
                unit = resolved.code();
                parameterType = FORCED_PARAMETER.get(resolved.dimension());
            }
        }
        if (parameterType == null) {
            inputs.add(RequiredInput.oneOf(at + "parameterType", name + "监控的是什么量",
                    rawUnit.isEmpty() ? "建厂访谈没有给单位" : "访谈单位“" + rawUnit + "”不能唯一确定参数类型",
                    String.join("、", Arrays.stream(ParameterType.values()).map(Enum::name).toList()) + " 之一",
                    PARAMETER_TYPE_OPTIONS));
            inputs.add(RequiredInput.text(at + "unit", name + "限值的单位",
                    rawUnit.isEmpty() ? "建厂访谈没有给单位" : "访谈单位“" + rawUnit + "”在单位目录里不能确定",
                    "本厂单位目录里已启用的单位编码（最长 " + MAX_UNIT + " 位），量纲须与参数类型一致", MAX_UNIT));
        }

        String monitoring = fact == null || fact.getMonitoringProcedure() == null ? "" : fact.getMonitoringProcedure().trim();
        if (monitoring.isEmpty()) {
            inputs.add(RequiredInput.text(at + "monitoringProcedure", name + "怎么监控",
                    "建厂访谈没有写监控方法", "1–" + MAX_PROCEDURE + " 字", MAX_PROCEDURE));
        }
        inputs.add(RequiredInput.oneOf(at + "monitoringMethod", name + "用什么方式监控",
                "建厂访谈没有问", enumValues(MonitoringMethod.values()), MONITORING_METHOD_OPTIONS));
        // {interval, unit}: two coupled fields (CONTINUOUS / EVERY_BATCH / EVENT_DRIVEN fix the interval at 1).
        inputs.add(RequiredInput.object(at + "monitoringFrequency", name + "多久监控一次",
                "建厂访谈没有问监控频率", "间隔加单位；连续、每批、事件触发的间隔固定为 1",
                scheduleFields("建厂访谈没有问监控频率", MONITORING_UNIT_OPTIONS)));
        inputs.add(RequiredInput.oneOf(at + "responsibleRole", name + "由谁负责监控",
                "建厂访谈没有问", enumValues(ResponsibleRole.values()), RESPONSIBLE_OPTIONS));
        inputs.add(RequiredInput.oneOf(at + "deviationDisposition", name + "超限时产品怎么处置",
                "建厂访谈没有问", enumValues(DeviationDisposition.values()), DISPOSITION_OPTIONS));
        inputs.add(RequiredInput.text(at + "correctiveAction", name + "超限时的纠偏措施",
                "建厂访谈没有问", "1–" + MAX_PROCEDURE + " 字", MAX_PROCEDURE));
        inputs.add(RequiredInput.oneOf(at + "verificationMethod", name + "用什么方式验证",
                "建厂访谈没有问", enumValues(VerificationMethod.values()), VERIFICATION_METHOD_OPTIONS));
        inputs.add(RequiredInput.object(at + "verificationFrequency", name + "多久验证一次",
                "建厂访谈没有问验证频率", "间隔加单位（验证不能是连续的）；每批、事件触发的间隔固定为 1",
                scheduleFields("建厂访谈没有问验证频率", VERIFICATION_UNIT_OPTIONS)));
        inputs.add(RequiredInput.text(at + "verificationProcedure", name + "怎么验证",
                "建厂访谈没有问", "1–" + MAX_PROCEDURE + " 字", MAX_PROCEDURE));
        inputs.add(RequiredInput.integer(at + "recordRetentionDays", name + "记录保存多少天",
                "建厂访谈没有问", "1–" + MAX_RETENTION_DAYS + " 天", 1, MAX_RETENTION_DAYS));
        inputs.add(RequiredInput.manyOf(at + "authorityCodes", name + "依据哪些法规或标准",
                "建厂访谈没有为控制点选依据",
                "1–" + MAX_AUTHORITIES + " 个不重复，只能从本能力的依据中选："
                        + String.join("、", authorities.stream().map(Option::value).toList()),
                authorities, 1, MAX_AUTHORITIES));

        return new ControlItem(hazard.getId(), null, null, null, parameterType, kind, lower, upper,
                false, false, unit, null, null, null, monitoring.isEmpty() ? null : monitoring,
                null, null, null, null, null, 0, List.of(),
                reasonPrefix + "显著危害 " + hazard.getId() + (fact == null ? "" : "，访谈控制点“" + fact.getName().trim() + "”"));
    }

    private static String enumValues(Enum<?>[] values) {
        return String.join("、", Arrays.stream(values).map(Enum::name).toList()) + " 之一";
    }
}
