package com.cretas.aims.service.hygieniczonedraft;

import com.cretas.aims.entity.hygieniczone.HygienicZoneDraft;
import com.cretas.aims.entity.hygieniczone.HygienicZoneDraftFlow;
import com.cretas.aims.entity.hygieniczone.HygienicZoneDraftZone;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.hygieniczone.HygienicZoneDraftFlowRepository;
import com.cretas.aims.repository.hygieniczone.HygienicZoneDraftRepository;
import com.cretas.aims.repository.hygieniczone.HygienicZoneDraftZoneRepository;
import com.cretas.aims.service.hygieniczonedraft.HygienicZoneDraftModels.ControlPoint;
import com.cretas.aims.service.hygieniczonedraft.HygienicZoneDraftModels.AuthenticatedOperator;
import com.cretas.aims.service.hygieniczonedraft.HygienicZoneDraftModels.CrossingPolicy;
import com.cretas.aims.service.hygieniczonedraft.HygienicZoneDraftModels.DraftIssue;
import com.cretas.aims.service.hygieniczonedraft.HygienicZoneDraftModels.DraftPreview;
import com.cretas.aims.service.hygieniczonedraft.HygienicZoneDraftModels.DraftReadback;
import com.cretas.aims.service.hygieniczonedraft.HygienicZoneDraftModels.DraftReceipt;
import com.cretas.aims.service.hygieniczonedraft.HygienicZoneDraftModels.DraftRequest;
import com.cretas.aims.service.hygieniczonedraft.HygienicZoneDraftModels.FlowDirection;
import com.cretas.aims.service.hygieniczonedraft.HygienicZoneDraftModels.FlowType;
import com.cretas.aims.service.hygieniczonedraft.HygienicZoneDraftModels.HygieneLevel;
import com.cretas.aims.service.hygieniczonedraft.HygienicZoneDraftModels.RecoveryPlan;
import com.cretas.aims.service.hygieniczonedraft.HygienicZoneDraftModels.RequestedFlow;
import com.cretas.aims.service.hygieniczonedraft.HygienicZoneDraftModels.RequestedZone;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Module-owned hygienic zoning DRAFT command.
 *
 * <p>The repository has no formal runtime hygienic-zone authority today. This command therefore
 * creates only isolated draft identities and explicit typed flow edges. It never calls HACCP,
 * SSOP, warehouse, equipment, HVAC or access-control writers.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class HygienicZoneDraftCommand {
    public static final String CAPABILITY_ID = "hygienic-zones";
    public static final String REQUIRED_PERMISSION = "hygiene-zone:draft:write";

    private final HygienicZoneDraftRepository draftRepository;
    private final HygienicZoneDraftZoneRepository zoneRepository;
    private final HygienicZoneDraftFlowRepository flowRepository;
    private final FactoryRepository factoryRepository;
    private final UserRepository userRepository;

    public HygienicZoneDraftCommand(
            HygienicZoneDraftRepository draftRepository,
            HygienicZoneDraftZoneRepository zoneRepository,
            HygienicZoneDraftFlowRepository flowRepository,
            FactoryRepository factoryRepository,
            UserRepository userRepository) {
        this.draftRepository = Objects.requireNonNull(draftRepository, "draftRepository");
        this.zoneRepository = Objects.requireNonNull(zoneRepository, "zoneRepository");
        this.flowRepository = Objects.requireNonNull(flowRepository, "flowRepository");
        this.factoryRepository = Objects.requireNonNull(factoryRepository, "factoryRepository");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
    }

    @Transactional(readOnly = true)
    public DraftPreview preview(DraftRequest request) {
        validateEnvelope(request, false);
        List<DraftIssue> issues = new ArrayList<>();
        Factory factory = factoryRepository.findById(request.factoryId()).orElse(null);
        if (factory == null) {
            issues.add(issue("HYGIENIC_ZONE_DRAFT_FACTORY_NOT_FOUND", "factoryId",
                    "当前工厂不存在或不可读取", "刷新当前租户工厂范围后重新预览"));
        } else if (!Boolean.TRUE.equals(factory.getIsActive())) {
            issues.add(issue("HYGIENIC_ZONE_DRAFT_FACTORY_INACTIVE", "factoryId",
                    "停用工厂不能建立新的卫生分区草稿", "由平台负责人确认工厂状态后重新预览"));
        }
        List<RequestedZone> zones = normalizeZones(request.zones(), issues);
        Map<String, RequestedZone> zonesByCode = new HashMap<>();
        zones.forEach(zone -> zonesByCode.put(zone.zoneCode(), zone));
        List<RequestedFlow> flows = normalizeFlows(request.flows(), zonesByCode, issues);
        requireExplicitFlowFamilies(flows, issues);
        issues.sort(Comparator.comparing(DraftIssue::code).thenComparing(DraftIssue::target));
        String sourceSnapshotDigest = factorySnapshot(factory);
        String desiredDigest = desiredDigest(zones, flows);
        String status = issues.isEmpty() ? "READY" : "BLOCKED";
        String previewDigest = digest(HygienicZoneDraftModels.SCHEMA_VERSION, request.factoryId(),
                request.blueprintId(), String.valueOf(request.blueprintVersion()), request.sourceDigest(),
                sourceSnapshotDigest, desiredDigest, status, issueDigest(issues));
        return new DraftPreview(HygienicZoneDraftModels.SCHEMA_VERSION, status, request.factoryId(),
                List.of("factory"), zones, flows, sourceSnapshotDigest, desiredDigest, previewDigest, issues,
                0, 0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    @Transactional
    public DraftReceipt materialize(DraftRequest request, AuthenticatedOperator operator) {
        validateEnvelope(request, true);
        if (operator == null || operator.operatorId() <= 0
                || operator.actor().id() != request.operatorId()
                || !request.factoryId().equals(operator.factoryId())) {
            throw invalid(403, "卫生分区草稿操作人身份与工厂范围不一致",
                    "HYGIENIC_ZONE_DRAFT_OPERATOR_SCOPE_MISMATCH",
                    "使用当前认证会话的 operatorId 与 factoryId 重新生成请求", "operator");
        }
        if (!operator.permissions().contains(REQUIRED_PERMISSION)) {
            throw invalid(403, "缺少卫生分区草稿权限", "HYGIENIC_ZONE_DRAFT_PERMISSION_REQUIRED",
                    "由卫生体系模块负责人授予 hygiene-zone:draft:write 后重试", "permission");
        }
        var member = userRepository.findByIdAndFactoryId(operator.operatorId(), operator.factoryId());
        if (member.isEmpty() || !Boolean.TRUE.equals(member.get().getIsActive())) {
            throw invalid(403, "操作人不是当前工厂的有效成员", "HYGIENIC_ZONE_DRAFT_OPERATOR_MEMBERSHIP_REQUIRED",
                    "由工厂管理员确认当前用户归属与启用状态后重试", "operator");
        }
        String requestDigest = requestDigest(request, operator);
        Optional<HygienicZoneDraft> prior = draftRepository.findByFactoryIdAndIdempotencyKey(
                request.factoryId(), request.idempotencyKey());
        if (prior.isPresent()) return replay(prior.get(), requestDigest);

        DraftPreview preview = preview(request);
        if (!"READY".equals(preview.status())) {
            DraftIssue first = preview.issues().get(0);
            throw invalid(409, "卫生分区草稿预览仍有阻断项", "HYGIENIC_ZONE_DRAFT_PREVIEW_BLOCKED",
                    first.recovery(), first.target());
        }
        if (!request.confirmedPreviewDigest().equals(preview.previewDigest())) {
            throw invalid(409, "卫生分区草稿预览摘要已变化", "HYGIENIC_ZONE_DRAFT_PREVIEW_DIGEST_CONFLICT",
                    "重新预览并确认当前分区与流向 ChangeSet", "confirmedPreviewDigest");
        }

        String draftId = UUID.randomUUID().toString();
        HygienicZoneDraft parent = HygienicZoneDraft.builder()
                .id(draftId).factoryId(request.factoryId()).blueprintId(request.blueprintId())
                .blueprintVersion(request.blueprintVersion()).sourceDigest(request.sourceDigest())
                .sourceSnapshotDigest(preview.sourceSnapshotDigest()).desiredDigest(preview.desiredDigest())
                .previewDigest(preview.previewDigest()).requestDigest(requestDigest)
                .idempotencyKey(request.idempotencyKey()).operatorId(operator.actor().factoryUserId()).actorType(operator.actor().type()).actorId(operator.actor().id())
                .zoneCount(preview.zones().size()).flowCount(preview.flows().size())
                .dependencyBasis(HygienicZoneDraftModels.DEPENDENCY_BASIS)
                .dependencyClosureComplete(true).status("DRAFT").build();
        try {
            parent = draftRepository.saveAndFlush(parent);
        } catch (DataIntegrityViolationException race) {
            if (!isScopedIdempotencyRace(race)) throw race;
            throw invalid(409, "卫生分区草稿幂等键发生并发竞争", "HYGIENIC_ZONE_DRAFT_IDEMPOTENCY_RACE",
                    "使用相同幂等键重试以读取胜出回执", "idempotencyKey");
        }

        List<HygienicZoneDraftZone> zoneRows = new ArrayList<>();
        for (int i = 0; i < preview.zones().size(); i++) {
            RequestedZone zone = preview.zones().get(i);
            zoneRows.add(HygienicZoneDraftZone.builder().id(UUID.randomUUID().toString()).draftId(draftId)
                    .factoryId(request.factoryId()).ordinal(i).zoneCode(zone.zoneCode()).zoneName(zone.zoneName())
                    .hygieneLevel(zone.hygieneLevel().name()).purpose(zone.purpose())
                    .temperatureClass(zone.temperatureClass().name()).entryPolicy(zone.entryPolicy().name())
                    .changeReason(zone.changeReason()).build());
        }
        List<HygienicZoneDraftFlow> flowRows = new ArrayList<>();
        for (int i = 0; i < preview.flows().size(); i++) {
            RequestedFlow flow = preview.flows().get(i);
            flowRows.add(HygienicZoneDraftFlow.builder().id(UUID.randomUUID().toString()).draftId(draftId)
                    .factoryId(request.factoryId()).ordinal(i).flowType(flow.flowType().name())
                    .fromZoneCode(flow.fromZoneCode()).toZoneCode(flow.toZoneCode())
                    .direction(flow.direction().name()).crossingPolicy(flow.crossingPolicy().name())
                    .controlPoint(flow.controlPoint().name()).rationale(flow.rationale()).build());
        }
        List<HygienicZoneDraftZone> savedZones = zoneRepository.saveAll(zoneRows);
        List<HygienicZoneDraftFlow> savedFlows = flowRepository.saveAll(flowRows);
        zoneRepository.flush();
        flowRepository.flush();
        requireExactReadback(parent, savedZones, savedFlows, preview);
        return receipt(parent, false, 1, 1 + savedZones.size() + savedFlows.size());
    }

    @Transactional(readOnly = true)
    public DraftReadback readBack(String factoryId, String draftId) {
        HygienicZoneDraft parent = draftRepository.findByIdAndFactoryId(clean(draftId), clean(factoryId))
                .orElseThrow(() -> invalid(404, "当前工厂没有该卫生分区草稿", "HYGIENIC_ZONE_DRAFT_NOT_FOUND",
                        "刷新当前工厂的卫生分区草稿回执", "draftId"));
        List<RequestedZone> zones = reconstructZones(zoneRepository
                .findByDraftIdAndFactoryIdOrderByOrdinal(parent.getId(), parent.getFactoryId()));
        List<RequestedFlow> flows = reconstructFlows(flowRepository
                .findByDraftIdAndFactoryIdOrderByOrdinal(parent.getId(), parent.getFactoryId()));
        if (zones.size() != parent.getZoneCount() || flows.size() != parent.getFlowCount()
                || !desiredDigest(zones, flows).equals(parent.getDesiredDigest())) throw readbackMismatch();
        return new DraftReadback(HygienicZoneDraftModels.SCHEMA_VERSION, parent.getFactoryId(), parent.getId(),
                parent.getStatus(), parent.getDesiredDigest(), zones, flows, 0, 0, 0,
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    @Transactional(readOnly = true)
    public RecoveryPlan recoveryPlan(String factoryId, String draftId) {
        HygienicZoneDraft row = draftRepository.findByIdAndFactoryId(clean(draftId), clean(factoryId))
                .orElseThrow(() -> invalid(404, "当前工厂没有该卫生分区草稿", "HYGIENIC_ZONE_DRAFT_NOT_FOUND",
                        "刷新当前工厂的卫生分区草稿回执", "draftId"));
        return new RecoveryPlan(HygienicZoneDraftModels.SCHEMA_VERSION, row.getFactoryId(), row.getId(),
                row.getDesiredDigest(), "SOFT_DELETE_DRAFT_AFTER_MODULE_OWNER_CONFIRMATION",
                "只移除未发布卫生分区草稿；不会回滚 HACCP、SSOP、仓库、设备或运行时设置",
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    private List<RequestedZone> normalizeZones(List<RequestedZone> requested, List<DraftIssue> issues) {
        if (requested.isEmpty()) {
            issues.add(issue("HYGIENIC_ZONE_DRAFT_ZONE_REQUIRED", "zones", "至少需要一个卫生分区",
                    "确认现场分区名称、等级与进入要求"));
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        List<RequestedZone> result = new ArrayList<>();
        for (int i = 0; i < requested.size(); i++) {
            RequestedZone zone = requested.get(i);
            String target = "zones[" + i + "]";
            if (zone == null) {
                issues.add(issue("HYGIENIC_ZONE_DRAFT_ZONE_ITEM_REQUIRED", target, "分区项不能为空", "删除空项或补齐分区"));
                continue;
            }
            if (!zone.zoneCode().matches("[A-Z0-9][A-Z0-9_-]{1,39}"))
                issues.add(issue("HYGIENIC_ZONE_DRAFT_ZONE_CODE_INVALID", target + ".zoneCode",
                        "分区编码必须为 2-40 位大写字母、数字、下划线或连字符", "填写稳定的工厂内分区编码"));
            else if (!seen.add(zone.zoneCode()))
                issues.add(issue("HYGIENIC_ZONE_DRAFT_ZONE_CODE_DUPLICATED", target + ".zoneCode",
                        "同一草稿不能重复分区编码", "每个分区编码只保留一项"));
            if (zone.zoneName().isBlank() || zone.zoneName().length() > 120)
                issues.add(issue("HYGIENIC_ZONE_DRAFT_ZONE_NAME_INVALID", target + ".zoneName",
                        "分区名称不能为空且不能超过 120 字符", "填写现场可识别的分区名称"));
            if (zone.hygieneLevel() == null || zone.temperatureClass() == null || zone.entryPolicy() == null)
                issues.add(issue("HYGIENIC_ZONE_DRAFT_ZONE_TYPE_REQUIRED", target,
                        "卫生等级、温度类别和进入要求必须明确", "从服务器封闭枚举中逐项选择"));
            if (zone.purpose().isBlank() || zone.purpose().length() > 500 || zone.changeReason().isBlank()
                    || zone.changeReason().length() > 500)
                issues.add(issue("HYGIENIC_ZONE_DRAFT_ZONE_BASIS_INVALID", target,
                        "分区用途和变更依据必填且各不超过 500 字符", "记录工厂确认的用途与依据"));
            if ((zone.hygieneLevel() == HygieneLevel.HIGH_CARE || zone.hygieneLevel() == HygieneLevel.HIGH_RISK)
                    && zone.entryPolicy() == HygienicZoneDraftModels.EntryPolicy.OPEN)
                issues.add(issue("HYGIENIC_ZONE_DRAFT_HIGH_CARE_ENTRY_OPEN", target + ".entryPolicy",
                        "高洁净或高风险分区不能声明开放进入", "明确更衣、洗手或消毒进入要求"));
            result.add(zone);
        }
        result.sort(Comparator.comparing(RequestedZone::zoneCode));
        return result;
    }

    private List<RequestedFlow> normalizeFlows(List<RequestedFlow> requested,
                                                Map<String, RequestedZone> zones,
                                                List<DraftIssue> issues) {
        Set<String> seen = new HashSet<>();
        List<RequestedFlow> result = new ArrayList<>();
        for (int i = 0; i < requested.size(); i++) {
            RequestedFlow flow = requested.get(i);
            String target = "flows[" + i + "]";
            if (flow == null) {
                issues.add(issue("HYGIENIC_ZONE_DRAFT_FLOW_ITEM_REQUIRED", target, "流向项不能为空", "删除空项或补齐流向"));
                continue;
            }
            if (flow.flowType() == null || flow.direction() == null || flow.crossingPolicy() == null
                    || flow.controlPoint() == null)
                issues.add(issue("HYGIENIC_ZONE_DRAFT_FLOW_TYPE_REQUIRED", target,
                        "流类型、方向、跨区策略与控制点必须明确", "从服务器封闭枚举中逐项选择"));
            if (!zones.containsKey(flow.fromZoneCode()) || !zones.containsKey(flow.toZoneCode()))
                issues.add(issue("HYGIENIC_ZONE_DRAFT_FLOW_ENDPOINT_UNKNOWN", target,
                        "流向端点必须引用本草稿中的卫生分区", "补齐分区或修正端点编码"));
            if (flow.fromZoneCode().equals(flow.toZoneCode()))
                issues.add(issue("HYGIENIC_ZONE_DRAFT_FLOW_SELF_LOOP", target,
                        "跨区流向不能从分区指向自身", "删除自环或选择真实相邻分区"));
            String key = flow.flowType() + "|" + flow.fromZoneCode() + "|" + flow.toZoneCode();
            if (!seen.add(key)) issues.add(issue("HYGIENIC_ZONE_DRAFT_FLOW_DUPLICATED", target,
                    "同类型同方向流向不能重复", "合并重复流向并保留一条依据"));
            if (flow.rationale().isBlank() || flow.rationale().length() > 500)
                issues.add(issue("HYGIENIC_ZONE_DRAFT_FLOW_RATIONALE_INVALID", target + ".rationale",
                        "流向依据必填且不能超过 500 字符", "记录现场动线、屏障或压差依据"));
            if (flow.crossingPolicy() == CrossingPolicy.CONTROLLED && flow.controlPoint() == ControlPoint.NONE)
                issues.add(issue("HYGIENIC_ZONE_DRAFT_CONTROL_POINT_REQUIRED", target + ".controlPoint",
                        "受控跨区必须声明控制点", "选择风闸、传递窗、消毒或更衣控制点"));
            if (flow.crossingPolicy() != CrossingPolicy.CONTROLLED && flow.controlPoint() != ControlPoint.NONE)
                issues.add(issue("HYGIENIC_ZONE_DRAFT_CONTROL_POINT_CONFLICT", target + ".controlPoint",
                        "只有受控跨区才能配置控制点", "将跨区策略改为 CONTROLLED 或移除控制点"));
            validateFlowSemantics(flow, zones, target, issues);
            result.add(flow);
        }
        result.sort(Comparator.comparing((RequestedFlow value) -> value.flowType() == null ? "" : value.flowType().name())
                .thenComparing(RequestedFlow::fromZoneCode).thenComparing(RequestedFlow::toZoneCode));
        return result;
    }

    private void validateFlowSemantics(RequestedFlow flow, Map<String, RequestedZone> zones,
                                       String target, List<DraftIssue> issues) {
        if (flow.flowType() == FlowType.AIR) {
            if (flow.direction() != FlowDirection.ONE_WAY)
                issues.add(issue("HYGIENIC_ZONE_DRAFT_AIR_DIRECTION_INVALID", target + ".direction",
                        "气流必须显式为单向", "按压差设计明确送风方向"));
            if (flow.crossingPolicy() != CrossingPolicy.CONTROLLED || flow.controlPoint() != ControlPoint.AIRLOCK)
                issues.add(issue("HYGIENIC_ZONE_DRAFT_AIR_CONTROL_INVALID", target,
                        "跨区气流必须由 AIRLOCK 受控", "将策略设为 CONTROLLED 并选择 AIRLOCK"));
            RequestedZone from = zones.get(flow.fromZoneCode());
            RequestedZone to = zones.get(flow.toZoneCode());
            if (from != null && to != null && hygieneRank(from.hygieneLevel()) < hygieneRank(to.hygieneLevel()))
                issues.add(issue("HYGIENIC_ZONE_DRAFT_AIR_TO_CLEANER_ZONE", target,
                        "气流不能从较低卫生等级指向较高卫生等级", "调整压差方向或重新确认卫生等级"));
        }
        RequestedZone from = zones.get(flow.fromZoneCode());
        RequestedZone to = zones.get(flow.toZoneCode());
        if (flow.flowType() != FlowType.AIR && from != null && to != null
                && from.hygieneLevel() != to.hygieneLevel()
                && flow.crossingPolicy() == CrossingPolicy.ALLOWED)
            issues.add(issue("HYGIENIC_ZONE_DRAFT_CROSS_LEVEL_UNCONTROLLED", target + ".crossingPolicy",
                    "人员或物料跨卫生等级不能无控制放行", "设为 CONTROLLED 并明确控制点，或设为 FORBIDDEN"));
    }

    private void requireExplicitFlowFamilies(List<RequestedFlow> flows, List<DraftIssue> issues) {
        for (FlowType type : FlowType.values()) {
            if (flows.stream().noneMatch(flow -> flow.flowType() == type))
                issues.add(issue("HYGIENIC_ZONE_DRAFT_FLOW_FAMILY_REQUIRED", "flows." + type.name(),
                        "人员流、物料流和气流都必须显式回答", "至少新增一条 " + type.name() + " 流向或禁行边界"));
        }
    }

    private int hygieneRank(HygieneLevel level) {
        if (level == HygieneLevel.HIGH_RISK) return 4;
        if (level == HygieneLevel.HIGH_CARE) return 3;
        if (level == HygieneLevel.PERSONNEL_HYGIENE) return 2;
        if (level == HygieneLevel.LOW_CARE) return 1;
        return 0;
    }

    private void requireExactReadback(HygienicZoneDraft parent,
                                      List<HygienicZoneDraftZone> zoneRows,
                                      List<HygienicZoneDraftFlow> flowRows,
                                      DraftPreview preview) {
        if (parent == null || !"DRAFT".equals(parent.getStatus()) || !preview.factoryId().equals(parent.getFactoryId())
                || !preview.sourceSnapshotDigest().equals(parent.getSourceSnapshotDigest())
                || !preview.desiredDigest().equals(parent.getDesiredDigest())
                || !preview.previewDigest().equals(parent.getPreviewDigest())
                || !Boolean.TRUE.equals(parent.getDependencyClosureComplete())) throw readbackMismatch();
        List<RequestedZone> zones = reconstructZones(zoneRows.stream()
                .sorted(Comparator.comparing(HygienicZoneDraftZone::getOrdinal)).toList());
        List<RequestedFlow> flows = reconstructFlows(flowRows.stream()
                .sorted(Comparator.comparing(HygienicZoneDraftFlow::getOrdinal)).toList());
        if (!zones.equals(preview.zones()) || !flows.equals(preview.flows())) throw readbackMismatch();
    }

    private List<RequestedZone> reconstructZones(List<HygienicZoneDraftZone> rows) {
        return rows.stream().map(row -> new RequestedZone(row.getZoneCode(), row.getZoneName(),
                HygieneLevel.valueOf(row.getHygieneLevel()), row.getPurpose(),
                HygienicZoneDraftModels.TemperatureClass.valueOf(row.getTemperatureClass()),
                HygienicZoneDraftModels.EntryPolicy.valueOf(row.getEntryPolicy()), row.getChangeReason())).toList();
    }

    private List<RequestedFlow> reconstructFlows(List<HygienicZoneDraftFlow> rows) {
        return rows.stream().map(row -> new RequestedFlow(FlowType.valueOf(row.getFlowType()),
                row.getFromZoneCode(), row.getToZoneCode(), FlowDirection.valueOf(row.getDirection()),
                CrossingPolicy.valueOf(row.getCrossingPolicy()), ControlPoint.valueOf(row.getControlPoint()),
                row.getRationale())).toList();
    }

    private DraftReceipt replay(HygienicZoneDraft row, String requestDigest) {
        if (!row.getRequestDigest().equals(requestDigest)) throw invalid(409,
                "该幂等键已绑定另一份卫生分区草稿载荷", "HYGIENIC_ZONE_DRAFT_IDEMPOTENCY_KEY_CONFLICT",
                "复用原请求或生成新幂等键", "idempotencyKey");
        return receipt(row, true, 0, 1 + row.getZoneCount() + row.getFlowCount());
    }

    private DraftReceipt receipt(HygienicZoneDraft row, boolean replayed, int writes, int persistedRows) {
        return new DraftReceipt(HygienicZoneDraftModels.SCHEMA_VERSION, row.getId(), row.getFactoryId(), row.getId(),
                row.getStatus(), row.getSourceSnapshotDigest(), row.getDesiredDigest(), row.getPreviewDigest(),
                row.getRequestDigest(), row.getZoneCount(), row.getFlowCount(),
                Objects.requireNonNullElse(row.getLockVersion(), 0L), replayed, writes, 1, persistedRows,
                0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED",
                "由卫生体系负责人确认后软删除整份 DRAFT；正式 HACCP、SSOP、仓库、设备与运行时设置不受影响");
    }

    private String desiredDigest(List<RequestedZone> zones, List<RequestedFlow> flows) {
        List<String> values = new ArrayList<>();
        zones.forEach(zone -> {
            values.add(zone.zoneCode()); values.add(zone.zoneName()); values.add(String.valueOf(zone.hygieneLevel()));
            values.add(zone.purpose()); values.add(String.valueOf(zone.temperatureClass()));
            values.add(String.valueOf(zone.entryPolicy())); values.add(zone.changeReason());
        });
        flows.forEach(flow -> {
            values.add(String.valueOf(flow.flowType())); values.add(flow.fromZoneCode()); values.add(flow.toZoneCode());
            values.add(String.valueOf(flow.direction())); values.add(String.valueOf(flow.crossingPolicy()));
            values.add(String.valueOf(flow.controlPoint())); values.add(flow.rationale());
        });
        return digest(values.toArray(String[]::new));
    }

    private String requestDigest(DraftRequest request, AuthenticatedOperator operator) {
        List<RequestedZone> zones = request.zones().stream().filter(Objects::nonNull)
                .sorted(Comparator.comparing(RequestedZone::zoneCode)).toList();
        List<RequestedFlow> flows = request.flows().stream().filter(Objects::nonNull)
                .sorted(Comparator.comparing((RequestedFlow flow) -> String.valueOf(flow.flowType()))
                        .thenComparing(RequestedFlow::fromZoneCode)
                        .thenComparing(RequestedFlow::toZoneCode)).toList();
        List<String> values = new ArrayList<>();
        values.add(HygienicZoneDraftModels.SCHEMA_VERSION);
        values.add(request.factoryId());
        values.add(request.blueprintId());
        values.add(String.valueOf(request.blueprintVersion()));
        values.add(request.sourceDigest());
        values.add(desiredDigest(zones, flows));
        values.add(request.confirmedPreviewDigest());
        values.add(request.idempotencyKey());
        values.add(operator.actor().type().name() + ":" + String.valueOf(operator.actor().id()));
        return digest(values.toArray(String[]::new));
    }

    private void validateEnvelope(DraftRequest request, boolean materialize) {
        if (request == null) throw invalid(400, "卫生分区草稿请求不能为空", "HYGIENIC_ZONE_DRAFT_REQUEST_REQUIRED",
                "从已确认的 hygienic-zones ChangeSet 生成请求", "request");
        if (request.factoryId().isBlank() || request.blueprintId().isBlank() || request.blueprintVersion() <= 0)
            throw invalid(400, "工厂与 Blueprint 版本不能为空", "HYGIENIC_ZONE_DRAFT_SCOPE_REQUIRED",
                    "锁定 factoryId、blueprintId 和 blueprintVersion", "blueprint");
        if (!request.sourceDigest().matches("[0-9a-f]{64}"))
            throw invalid(400, "Blueprint sourceDigest 必须是 SHA-256", "HYGIENIC_ZONE_DRAFT_SOURCE_INVALID",
                    "使用服务器编译工件的 sourceDigest", "sourceDigest");
        if (materialize) {
            if (request.operatorId() <= 0) throw invalid(400, "卫生分区草稿必须记录操作人",
                    "HYGIENIC_ZONE_DRAFT_OPERATOR_REQUIRED", "使用已认证卫生体系负责人身份", "operatorId");
            if (request.idempotencyKey().isBlank() || request.idempotencyKey().length() > 128)
                throw invalid(400, "卫生分区草稿幂等键不能为空且不能超过 128 字符",
                        "HYGIENIC_ZONE_DRAFT_IDEMPOTENCY_INVALID", "为本次确认生成稳定幂等键", "idempotencyKey");
            if (!request.confirmedPreviewDigest().matches("[0-9a-f]{64}"))
                throw invalid(400, "必须确认服务器生成的 previewDigest",
                        "HYGIENIC_ZONE_DRAFT_CONFIRMED_PREVIEW_REQUIRED", "重新预览并确认当前摘要",
                        "confirmedPreviewDigest");
        }
    }

    private String issueDigest(List<DraftIssue> issues) {
        return digest(issues.stream().map(value -> value.code() + "\u0000" + value.target() + "\u0000" + value.message())
                .toArray(String[]::new));
    }

    private String factorySnapshot(Factory factory) {
        if (factory == null) return digest("FACTORY", "ABSENT");
        return digest("FACTORY", factory.getId(), factory.getName(), String.valueOf(factory.getIsActive()),
                factory.getIndustryCode(), String.valueOf(factory.getUpdatedAt()),
                "NO_FORMAL_HYGIENIC_ZONE_AUTHORITY", HygienicZoneDraftModels.DEPENDENCY_BASIS);
    }

    private boolean isScopedIdempotencyRace(Throwable failure) {
        Throwable cursor = failure;
        while (cursor != null) {
            if (clean(cursor.getMessage()).toLowerCase(Locale.ROOT)
                    .contains("uk_hygienic_zone_draft_idempotency")) return true;
            cursor = cursor.getCause();
        }
        return false;
    }

    private BusinessException readbackMismatch() {
        return invalid(409, "卫生分区草稿回读与已确认 ChangeSet 不一致",
                "HYGIENIC_ZONE_DRAFT_READBACK_MISMATCH", "保留事务回滚并重新预览", "draft");
    }

    private String digest(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = clean(value).getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24)); digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8)); digest.update((byte) bytes.length); digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String clean(String value) { return HygienicZoneDraftModels.clean(value); }
    private static DraftIssue issue(String code, String target, String message, String recovery) {
        return new DraftIssue(code, target, message, recovery);
    }
    private static BusinessException invalid(int status, String message, String code, String recovery, String target) {
        return new BusinessException(status, message).withCode(code).withHint(recovery)
                .withHintTarget(target).withSeverity("warning");
    }
}
