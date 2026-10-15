package com.cretas.aims.service.environmentalmonitoringdraft;

import com.cretas.aims.blueprint.capabilityadapter.FactoryCapabilityAdapterContracts;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.CapabilityView;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintService;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.environmentalmonitoringdraft.EnvironmentalMonitoringTemplateDraft;
import com.cretas.aims.entity.environmentalmonitoringdraft.EnvironmentalMonitoringTemplateDraftEvidence;
import com.cretas.aims.entity.environmentalmonitoringdraft.EnvironmentalMonitoringTemplateDraftPoint;
import com.cretas.aims.entity.hygieniczone.HygienicZoneDraft;
import com.cretas.aims.entity.sanitationcipdraft.SanitationCipTemplateDraft;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.environmentalmonitoringdraft.EnvironmentalMonitoringTemplateDraftEvidenceRepository;
import com.cretas.aims.repository.environmentalmonitoringdraft.EnvironmentalMonitoringTemplateDraftPointRepository;
import com.cretas.aims.repository.environmentalmonitoringdraft.EnvironmentalMonitoringTemplateDraftRepository;
import com.cretas.aims.repository.hygieniczone.HygienicZoneDraftRepository;
import com.cretas.aims.repository.sanitationcipdraft.SanitationCipTemplateDraftRepository;
import com.cretas.aims.service.environmentalmonitoringdraft.EnvironmentalMonitoringDraftModels.AuthenticatedOperator;
import com.cretas.aims.service.environmentalmonitoringdraft.EnvironmentalMonitoringDraftModels.ContactClass;
import com.cretas.aims.service.environmentalmonitoringdraft.EnvironmentalMonitoringDraftModels.DerivedPoint;
import com.cretas.aims.service.environmentalmonitoringdraft.EnvironmentalMonitoringDraftModels.DraftIssue;
import com.cretas.aims.service.environmentalmonitoringdraft.EnvironmentalMonitoringDraftModels.DraftPreview;
import com.cretas.aims.service.environmentalmonitoringdraft.EnvironmentalMonitoringDraftModels.DraftReadback;
import com.cretas.aims.service.environmentalmonitoringdraft.EnvironmentalMonitoringDraftModels.DraftReceipt;
import com.cretas.aims.service.environmentalmonitoringdraft.EnvironmentalMonitoringDraftModels.DraftRequest;
import com.cretas.aims.service.environmentalmonitoringdraft.EnvironmentalMonitoringDraftModels.EscalationRole;
import com.cretas.aims.service.environmentalmonitoringdraft.EnvironmentalMonitoringDraftModels.EvidenceType;
import com.cretas.aims.service.environmentalmonitoringdraft.EnvironmentalMonitoringDraftModels.HygienicZonePin;
import com.cretas.aims.service.environmentalmonitoringdraft.EnvironmentalMonitoringDraftModels.MonitoringPointInput;
import com.cretas.aims.service.environmentalmonitoringdraft.EnvironmentalMonitoringDraftModels.RecoveryPlan;
import com.cretas.aims.service.environmentalmonitoringdraft.EnvironmentalMonitoringDraftModels.ResponseLevel;
import com.cretas.aims.service.environmentalmonitoringdraft.EnvironmentalMonitoringDraftModels.ResponsibleRole;
import com.cretas.aims.service.environmentalmonitoringdraft.EnvironmentalMonitoringDraftModels.ResultUnit;
import com.cretas.aims.service.environmentalmonitoringdraft.EnvironmentalMonitoringDraftModels.SampleMedium;
import com.cretas.aims.service.environmentalmonitoringdraft.EnvironmentalMonitoringDraftModels.SamplingFrequency;
import com.cretas.aims.service.environmentalmonitoringdraft.EnvironmentalMonitoringDraftModels.SamplingTiming;
import com.cretas.aims.service.environmentalmonitoringdraft.EnvironmentalMonitoringDraftModels.SanitationTemplatePin;
import com.cretas.aims.service.environmentalmonitoringdraft.EnvironmentalMonitoringDraftModels.TargetOrganism;
import com.cretas.aims.service.hygieniczonedraft.HygienicZoneDraftCommand;
import com.cretas.aims.service.hygieniczonedraft.HygienicZoneDraftModels;
import com.cretas.aims.service.sanitationcipdraft.SanitationCipDraftCommand;
import com.cretas.aims.service.sanitationcipdraft.SanitationCipDraftModels;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Module-owned environmental and pathogen monitoring TEMPLATE/DRAFT command.
 *
 * <p>The immutable capability catalog v2 dependency is {@code sanitation-cip}: every sampling point
 * must pin an item of a same-factory, same-lineage, readback-ready B10 sanitation/CIP DRAFT, and
 * every sanitation item that holds product until verification must be covered by a post-sanitation
 * point. {@code hygienic-zones} is an explicit safety pin only, so a point cannot name a zone that
 * no readback-ready zoning DRAFT owns.</p>
 *
 * <p>This command writes exactly three Blueprint-owned draft tables. It never records a sampling
 * result or a positive event, never holds production, never freezes quality, never opens a CAPA
 * case, never emits an alert or notification, never issues an equipment command, and never touches
 * production data. It exposes no generic JSON/JSONB writer, no arbitrary SQL and no expression
 * evaluation.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class EnvironmentalMonitoringDraftCommand {
    public static final String CAPABILITY_ID = "environmental-monitoring";
    public static final String REQUIRED_PERMISSION = "environment:template:draft";
    private static final String AUTHORITY_CODE = "ENVIRONMENTAL_MONITORING_CATALOG_AUTHORITY";
    private static final List<String> DEPENDENCIES =
            FactoryCapabilityAdapterContracts.dependenciesOf(CAPABILITY_ID);
    private static final int MAX_POINTS = 500;

    private final EnvironmentalMonitoringTemplateDraftRepository drafts;
    private final EnvironmentalMonitoringTemplateDraftPointRepository points;
    private final EnvironmentalMonitoringTemplateDraftEvidenceRepository evidence;
    private final SanitationCipTemplateDraftRepository sanitationDrafts;
    private final SanitationCipDraftCommand sanitationCommand;
    private final HygienicZoneDraftRepository zoneDrafts;
    private final HygienicZoneDraftCommand zoneCommand;
    private final CanonicalFactoryBlueprintService canonicalBlueprints;
    private final UserRepository users;

    public EnvironmentalMonitoringDraftCommand(
            EnvironmentalMonitoringTemplateDraftRepository drafts,
            EnvironmentalMonitoringTemplateDraftPointRepository points,
            EnvironmentalMonitoringTemplateDraftEvidenceRepository evidence,
            SanitationCipTemplateDraftRepository sanitationDrafts,
            SanitationCipDraftCommand sanitationCommand,
            HygienicZoneDraftRepository zoneDrafts,
            HygienicZoneDraftCommand zoneCommand,
            CanonicalFactoryBlueprintService canonicalBlueprints,
            UserRepository users) {
        this.drafts = drafts;
        this.points = points;
        this.evidence = evidence;
        this.sanitationDrafts = sanitationDrafts;
        this.sanitationCommand = sanitationCommand;
        this.zoneDrafts = zoneDrafts;
        this.zoneCommand = zoneCommand;
        this.canonicalBlueprints = canonicalBlueprints;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public DraftPreview preview(DraftRequest request) {
        validateEnvelope(request, false);
        return evaluate(request).preview();
    }

    @Transactional
    public DraftReceipt materialize(DraftRequest request, AuthenticatedOperator operator) {
        validateEnvelope(request, true);
        authorize(request, operator);
        String requestDigest = requestDigest(request, operator);
        Optional<EnvironmentalMonitoringTemplateDraft> prior = drafts
                .findByFactoryIdAndIdempotencyKey(request.factoryId(), request.idempotencyKey());
        if (prior.isPresent()) {
            if (!requestDigest.equals(prior.get().getRequestDigest())) {
                throw invalid(409, "幂等键对应不同环境监测模板请求",
                        "ENVIRONMENTAL_MONITORING_IDEMPOTENCY_CONFLICT");
            }
            readBack(request.factoryId(), prior.get().getId());
            return receipt(prior.get(), true, 0, 0);
        }

        Evaluation evaluation = evaluate(request);
        DraftPreview plan = evaluation.preview();
        if (!"READY".equals(plan.status())) {
            throw invalid(409, "环境监测模板预览被阻断",
                    "ENVIRONMENTAL_MONITORING_PREVIEW_BLOCKED");
        }
        if (!request.confirmedPreviewDigest().equals(plan.previewDigest())) {
            throw invalid(409, "环境监测模板确认摘要已漂移",
                    "ENVIRONMENTAL_MONITORING_PREVIEW_DRIFT");
        }

        BlueprintAuthority authority = evaluation.authority();
        SanitationAuthority sanitation = evaluation.sanitation();
        ZoneAuthority zones = evaluation.zones();
        EnvironmentalMonitoringTemplateDraft parent = EnvironmentalMonitoringTemplateDraft.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(request.factoryId())
                .blueprintId(request.blueprintId())
                .blueprintVersion(request.blueprintVersion())
                .sourceDigest(request.sourceDigest())
                .sourceSnapshotDigest(plan.sourceSnapshotDigest())
                .sanitationCipDraftId(sanitation.draftId())
                .sanitationCipDesiredDigest(sanitation.desiredDigest())
                .sanitationSnapshotDigest(sanitation.snapshotDigest())
                .hygienicZoneDraftId(zones.draftId())
                .hygienicZoneDesiredDigest(zones.desiredDigest())
                .hygienicZoneSnapshotDigest(zones.snapshotDigest())
                .capabilityCatalogVersion(authority.catalogVersion())
                .capabilityCatalogDigest(authority.catalogDigest())
                .authoritySnapshotDigest(authority.authoritySnapshotDigest())
                .desiredDigest(plan.desiredDigest())
                .previewDigest(plan.previewDigest())
                .requestDigest(requestDigest)
                .idempotencyKey(request.idempotencyKey())
                .operatorId(operator.actor().factoryUserId()).actorType(operator.actor().type()).actorId(operator.actor().id())
                .pointCount(plan.points().size())
                .evidenceCount(1)
                .dependencyBasis(EnvironmentalMonitoringDraftModels.DEPENDENCY_BASIS)
                .algorithmVersion(EnvironmentalMonitoringDraftModels.ALGORITHM_VERSION)
                .dependencyClosureComplete(true)
                .status("DRAFT")
                .build();
        EnvironmentalMonitoringTemplateDraft saved;
        try {
            saved = drafts.saveAndFlush(parent);
        } catch (DataIntegrityViolationException exception) {
            if (!isIdempotencyRace(exception)) throw exception;
            throw invalid(409, "环境监测模板幂等键发生并发竞争",
                    "ENVIRONMENTAL_MONITORING_IDEMPOTENCY_RACE");
        }

        List<EnvironmentalMonitoringTemplateDraftPoint> rows = plan.points().stream()
                .map(point -> toRow(saved, point)).toList();
        points.saveAllAndFlush(rows);
        evidence.saveAndFlush(EnvironmentalMonitoringTemplateDraftEvidence.builder()
                .id(UUID.randomUUID().toString())
                .draftId(saved.getId())
                .factoryId(saved.getFactoryId())
                .authorityCode(AUTHORITY_CODE)
                .catalogVersion(authority.catalogVersion())
                .catalogDigest(authority.catalogDigest())
                .authoritySnapshotDigest(authorityEvidenceDigest(authority))
                .build());

        DraftReadback readback = readBack(saved.getFactoryId(), saved.getId());
        if (!plan.desiredDigest().equals(readback.desiredDigest())) {
            throw invalid(409, "环境监测模板回读摘要不一致",
                    "ENVIRONMENTAL_MONITORING_READBACK_MISMATCH");
        }
        return receipt(saved, false, 1, 2 + rows.size());
    }

    @Transactional(readOnly = true)
    public DraftReadback readBack(String factoryId, String draftId) {
        EnvironmentalMonitoringTemplateDraft parent = drafts.findByIdAndFactoryId(draftId, factoryId)
                .orElseThrow(() -> invalid(404, "当前工厂没有该环境监测模板草稿",
                        "ENVIRONMENTAL_MONITORING_NOT_FOUND"));
        List<EnvironmentalMonitoringTemplateDraftPoint> rows = points
                .findByDraftIdAndFactoryIdOrderByOrdinal(draftId, factoryId);
        List<EnvironmentalMonitoringTemplateDraftEvidence> evidenceRows = evidence
                .findByDraftIdAndFactoryIdOrderByAuthorityCode(draftId, factoryId);
        if (!"DRAFT".equals(parent.getStatus())
                || !Boolean.TRUE.equals(parent.getDependencyClosureComplete())
                || !EnvironmentalMonitoringDraftModels.DEPENDENCY_BASIS
                        .equals(parent.getDependencyBasis())
                || !EnvironmentalMonitoringDraftModels.ALGORITHM_VERSION
                        .equals(parent.getAlgorithmVersion())
                || parent.getPointCount() == null || parent.getPointCount() != rows.size()
                || parent.getEvidenceCount() == null || parent.getEvidenceCount() != 1
                || evidenceRows.size() != 1) {
            throw invalid(409, "环境监测模板父子行或依赖闭包不完整",
                    "ENVIRONMENTAL_MONITORING_READBACK_INVARIANT_BROKEN");
        }

        List<DraftIssue> issues = new ArrayList<>();
        BlueprintAuthority authority = resolveBlueprint(factoryId, parent.getBlueprintId(),
                parent.getBlueprintVersion(), issues);
        SanitationAuthority sanitation = resolveStoredSanitation(parent, issues);
        ZoneAuthority zones = resolveStoredZones(parent, issues);
        if (!issues.isEmpty()
                || !parent.getAuthoritySnapshotDigest().equals(authority.authoritySnapshotDigest())
                || !parent.getCapabilityCatalogVersion().equals(authority.catalogVersion())
                || !parent.getCapabilityCatalogDigest().equals(authority.catalogDigest())
                || !parent.getSanitationSnapshotDigest().equals(sanitation.snapshotDigest())
                || !parent.getHygienicZoneSnapshotDigest().equals(zones.snapshotDigest())) {
            throw invalid(409, "环境监测模板上游 Blueprint、清洗消毒草稿或卫生分区草稿已漂移",
                    "ENVIRONMENTAL_MONITORING_UPSTREAM_DRIFT");
        }
        String currentSourceSnapshot = sourceSnapshot(authority, sanitation, zones);
        if (!currentSourceSnapshot.equals(parent.getSourceSnapshotDigest())) {
            throw invalid(409, "环境监测模板源快照摘要已漂移",
                    "ENVIRONMENTAL_MONITORING_SOURCE_SNAPSHOT_DRIFT");
        }
        if (!sameEvidence(evidenceRows.get(0), authority)) {
            throw invalid(409, "环境监测模板目录依据摘要不一致",
                    "ENVIRONMENTAL_MONITORING_EVIDENCE_MISMATCH");
        }

        List<DerivedPoint> derived = new ArrayList<>();
        for (EnvironmentalMonitoringTemplateDraftPoint row : rows) {
            DerivedPoint point = fromRow(row);
            SanitationTarget target = sanitation.targets().get(point.sanitationTemplateCode());
            ZoneTarget zone = zones.zones().get(point.zoneCode());
            if (target == null || zone == null
                    || target.ordinal() != point.sanitationItemOrdinal()
                    || !Objects.equals(target.productCode(), point.productCode())
                    || !Objects.equals(target.routeNodeId(), point.routeNodeId())
                    || !Objects.equals(target.routeNodeLabel(), point.routeNodeLabel())
                    || !targetSnapshot(target, zone).equals(point.targetSnapshotDigest())
                    || !Objects.equals(zone.zoneName(), point.zoneName())
                    || !Objects.equals(zone.hygieneLevel(), point.zoneHygieneLevel())) {
                throw invalid(409, "环境监测采样点的清洗模板或卫生分区快照已漂移",
                        "ENVIRONMENTAL_MONITORING_TARGET_DRIFT");
            }
            if (point.responseLevel() != EnvironmentalMonitoringDraftModels
                        .responseLevel(point.contactClass(), point.targetOrganism())
                    || point.escalationRole() != EnvironmentalMonitoringDraftModels
                        .escalationRole(point.responseLevel())
                    || point.pathogenTarget() != EnvironmentalMonitoringDraftModels
                        .pathogenTarget(point.targetOrganism())
                    || point.vectorSamplingRequired() != EnvironmentalMonitoringDraftModels
                        .vectorSamplingRequired(point.targetOrganism())
                    || point.resampleRequired() != EnvironmentalMonitoringDraftModels
                        .resampleRequired(point.responseLevel())) {
                throw invalid(409, "环境监测响应等级与服务端算法不一致",
                        "ENVIRONMENTAL_MONITORING_POLICY_TAMPERED");
            }
            derived.add(point);
        }
        if (!coverageGaps(derived, sanitation).isEmpty()) {
            throw invalid(409, "环境监测未覆盖需验证后放行的清洗项",
                    "ENVIRONMENTAL_MONITORING_SANITATION_HOLD_NOT_COVERED");
        }
        if (!desiredDigest(derived).equals(parent.getDesiredDigest())) {
            throw invalid(409, "环境监测模板逐字段回读摘要不一致",
                    "ENVIRONMENTAL_MONITORING_READBACK_MISMATCH");
        }
        return new DraftReadback(EnvironmentalMonitoringDraftModels.SCHEMA_VERSION, factoryId,
                draftId, parent.getStatus(), derived, parent.getSourceSnapshotDigest(),
                parent.getDesiredDigest(), 0, 0, 0, 0, 0, 0, 0, 0,
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    @Transactional(readOnly = true)
    public RecoveryPlan recoveryPlan(String factoryId, String draftId,
                                     String expectedDesiredDigest) {
        EnvironmentalMonitoringTemplateDraft parent = drafts.findByIdAndFactoryId(draftId, factoryId)
                .orElseThrow(() -> invalid(404, "当前工厂没有该环境监测模板草稿",
                        "ENVIRONMENTAL_MONITORING_NOT_FOUND"));
        if (!"DRAFT".equals(parent.getStatus())
                || !isDigest(expectedDesiredDigest)
                || !expectedDesiredDigest.equals(parent.getDesiredDigest())) {
            throw invalid(409, "只能恢复摘要精确匹配的未发布环境监测草稿",
                    "ENVIRONMENTAL_MONITORING_RECOVERY_NOT_ALLOWED");
        }
        return new RecoveryPlan(EnvironmentalMonitoringDraftModels.SCHEMA_VERSION, factoryId,
                draftId, expectedDesiredDigest, "ARCHIVE_DRAFT_ONLY",
                "只归档未发布模板；不创建采样结果、阳性事件、生产 hold、质量冻结、CAPA、告警、通知或设备命令",
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    private Evaluation evaluate(DraftRequest request) {
        List<DraftIssue> issues = new ArrayList<>();
        BlueprintAuthority authority = resolveBlueprint(request.factoryId(), request.blueprintId(),
                request.blueprintVersion(), issues);
        SanitationAuthority sanitation = resolveSanitation(request, issues);
        ZoneAuthority zones = resolveZones(request, issues);
        List<DerivedPoint> derived = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        for (int ordinal = 0; ordinal < request.points().size(); ordinal++) {
            MonitoringPointInput input = request.points().get(ordinal);
            String target = "points[" + ordinal + "]";
            if (!validatePoint(input, target, issues)) {
                continue;
            }
            SanitationTarget sanitationTarget = sanitation.targets().get(input.sanitationTemplateCode());
            if (sanitationTarget == null) {
                issues.add(issue("ENVIRONMENTAL_MONITORING_SANITATION_TEMPLATE_MISSING", target,
                        "采样点必须引用同一 readback-ready 清洗消毒草稿中的模板",
                        "重新选择当前清洗消毒草稿中的模板编码"));
                continue;
            }
            ZoneTarget zone = zones.zones().get(input.zoneCode());
            if (zone == null) {
                issues.add(issue("ENVIRONMENTAL_MONITORING_ZONE_MISSING", target,
                        "采样点必须引用同一 readback-ready 卫生分区草稿中的分区",
                        "重新选择当前卫生分区草稿中的分区编码"));
                continue;
            }
            String identity = input.zoneCode() + "\0" + input.sanitationTemplateCode()
                    + "\0" + input.contactClass() + "\0" + input.sampleMedium()
                    + "\0" + input.targetOrganism();
            if (!identities.add(identity)) {
                issues.add(issue("ENVIRONMENTAL_MONITORING_DUPLICATE_POINT", target,
                        "同一分区、清洗模板、接触等级、介质和目标微生物不能重复",
                        "合并重复采样点"));
                continue;
            }
            derived.add(derive(ordinal, input, sanitationTarget, zone));
        }
        for (String gap : coverageGaps(derived, sanitation)) {
            issues.add(issue("ENVIRONMENTAL_MONITORING_SANITATION_HOLD_NOT_COVERED",
                    "sanitationTemplate:" + gap,
                    "每条验证前 hold 的清洗模板必须至少有一个清洗后采样点",
                    "为该清洗模板补一个 POST_SANITATION 采样点"));
        }
        if (!derived.isEmpty() && derived.stream().noneMatch(point ->
                point.contactClass() == ContactClass.FOOD_CONTACT && point.pathogenTarget())) {
            issues.add(issue("ENVIRONMENTAL_MONITORING_FOOD_CONTACT_PATHOGEN_REQUIRED", "points",
                    "环境监测计划必须至少有一个食品接触面致病菌采样点",
                    "补一个食品接触面致病菌定性采样点"));
        }
        issues.sort(Comparator.comparing(DraftIssue::code).thenComparing(DraftIssue::target));

        String desired = desiredDigest(derived);
        String sourceSnapshot = sourceSnapshot(authority, sanitation, zones);
        String status = issues.isEmpty() ? "READY" : "BLOCKED";
        String previewDigest = digest("ENVIRONMENTAL_MONITORING_PREVIEW",
                EnvironmentalMonitoringDraftModels.SCHEMA_VERSION,
                EnvironmentalMonitoringDraftModels.ALGORITHM_VERSION, status, sourceSnapshot,
                sanitation.snapshotDigest(), zones.snapshotDigest(),
                authority.authoritySnapshotDigest(), desired, issueDigest(issues));
        DraftPreview preview = new DraftPreview(
                EnvironmentalMonitoringDraftModels.SCHEMA_VERSION,
                EnvironmentalMonitoringDraftModels.ALGORITHM_VERSION, status, request.factoryId(),
                DEPENDENCIES, request, derived, sourceSnapshot, sanitation.snapshotDigest(),
                zones.snapshotDigest(), authority.authoritySnapshotDigest(), desired, previewDigest,
                issues, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED");
        return new Evaluation(preview, authority, sanitation, zones);
    }

    private List<String> coverageGaps(List<DerivedPoint> derived, SanitationAuthority sanitation) {
        Set<String> covered = derived.stream()
                .filter(point -> point.samplingTiming() == SamplingTiming.POST_SANITATION)
                .map(DerivedPoint::sanitationTemplateCode).collect(java.util.stream.Collectors.toSet());
        return sanitation.targets().values().stream()
                .filter(SanitationTarget::holdUntilVerified)
                .map(SanitationTarget::templateCode)
                .filter(code -> !covered.contains(code))
                .sorted().toList();
    }

    private BlueprintAuthority resolveBlueprint(String factoryId, String blueprintId,
                                                long blueprintVersion, List<DraftIssue> issues) {
        BlueprintResponse response = canonicalBlueprints.current(factoryId);
        if (response == null || !blueprintId.equals(response.getId())
                || !Objects.equals(Math.toIntExact(blueprintVersion), response.getVersion())
                || !("DRAFT".equals(response.getLifecycleState())
                     || "VALIDATED".equals(response.getLifecycleState()))) {
            issues.add(issue("ENVIRONMENTAL_MONITORING_BLUEPRINT_LINEAGE_MISMATCH", "blueprintId",
                    "当前工厂 Blueprint DRAFT/VALIDATED 版本与请求不一致",
                    "刷新当前 Blueprint 后重新预览"));
            return BlueprintAuthority.missing();
        }
        CapabilityView capability = response.getCapabilities() == null ? null
                : response.getCapabilities().stream()
                .filter(item -> CAPABILITY_ID.equals(item.getId())).findFirst().orElse(null);
        Set<String> dependencies = capability == null || capability.getDependsOn() == null
                ? Set.of() : Set.copyOf(capability.getDependsOn());
        if (capability == null || !capability.isIncluded() || !"READY".equals(capability.getStatus())
                || !dependencies.equals(Set.copyOf(DEPENDENCIES))) {
            issues.add(issue("ENVIRONMENTAL_MONITORING_CAPABILITY_NOT_READY", "capability",
                    "environmental-monitoring 或 sanitation-cip 目录依赖尚未就绪",
                    "补齐清洗消毒证据后重新编译 Blueprint"));
        }
        Set<String> refs = capability == null || capability.getAuthorityRefs() == null
                ? Set.of() : Set.copyOf(capability.getAuthorityRefs());
        if (response.getCapabilityCatalogVersion() == null
                || response.getCapabilityCatalogVersion().isBlank()
                || !isDigest(response.getCapabilityCatalogDigest()) || refs.isEmpty()) {
            issues.add(issue("ENVIRONMENTAL_MONITORING_AUTHORITY_CATALOG_INVALID",
                    "capabilityCatalog", "服务端能力目录版本、摘要或卫生依据无效",
                    "部署带 authority refs 的版本化能力目录"));
        }
        String authoritySnapshot = digest("ENVIRONMENTAL_MONITORING_AUTHORITY",
                response.getCapabilityCatalogVersion(), response.getCapabilityCatalogDigest(),
                refs.stream().sorted().reduce("", (left, right) -> left + "|" + right));
        return new BlueprintAuthority(response.getCapabilityCatalogVersion(),
                response.getCapabilityCatalogDigest(), refs, authoritySnapshot,
                digestBlueprint(response));
    }

    private SanitationAuthority resolveSanitation(DraftRequest request, List<DraftIssue> issues) {
        SanitationTemplatePin pin = request.sanitationTemplate();
        if (pin == null || pin.draftId().isBlank() || !isDigest(pin.desiredDigest())) {
            issues.add(issue("ENVIRONMENTAL_MONITORING_SANITATION_PIN_INVALID", "sanitationTemplate",
                    "环境监测必须锁定同一 Blueprint 的清洗消毒草稿",
                    "选择当前 Blueprint 的清洗消毒草稿并刷新摘要"));
            return SanitationAuthority.none();
        }
        SanitationCipTemplateDraft parent = sanitationDrafts
                .findByIdAndFactoryId(pin.draftId(), request.factoryId()).orElse(null);
        if (!sameLineage(parent, request, pin.desiredDigest())) {
            issues.add(issue("ENVIRONMENTAL_MONITORING_SANITATION_DRIFT", "sanitationTemplate",
                    "B10 清洗消毒草稿不属于当前 Blueprint 或摘要已变化",
                    "重新读取并选择当前清洗消毒草稿"));
            return SanitationAuthority.none();
        }
        try {
            return sanitationAuthority(parent,
                    sanitationCommand.readBack(request.factoryId(), pin.draftId()));
        } catch (RuntimeException exception) {
            issues.add(issue("ENVIRONMENTAL_MONITORING_SANITATION_READBACK_FAILED",
                    "sanitationTemplate", "B10 清洗消毒草稿逐字段回读失败",
                    "修复清洗消毒草稿后重新预览"));
            return SanitationAuthority.none();
        }
    }

    private SanitationAuthority resolveStoredSanitation(EnvironmentalMonitoringTemplateDraft parent,
                                                        List<DraftIssue> issues) {
        SanitationCipTemplateDraft stored = sanitationDrafts.findByIdAndFactoryId(
                parent.getSanitationCipDraftId(), parent.getFactoryId()).orElse(null);
        if (stored == null
                || !Objects.equals(parent.getSanitationCipDesiredDigest(), stored.getDesiredDigest())
                || !parent.getBlueprintId().equals(stored.getBlueprintId())
                || !Objects.equals(parent.getBlueprintVersion(), stored.getBlueprintVersion())
                || !parent.getSourceDigest().equals(stored.getSourceDigest())) {
            issues.add(issue("ENVIRONMENTAL_MONITORING_SANITATION_DRIFT", "sanitationTemplate",
                    "已保存的 B10 清洗消毒 pin 已漂移", "重新生成环境监测模板草稿"));
            return SanitationAuthority.none();
        }
        try {
            return sanitationAuthority(stored,
                    sanitationCommand.readBack(parent.getFactoryId(), stored.getId()));
        } catch (RuntimeException exception) {
            issues.add(issue("ENVIRONMENTAL_MONITORING_SANITATION_READBACK_FAILED",
                    "sanitationTemplate", "已保存的 B10 清洗消毒草稿逐字段回读失败",
                    "修复清洗消毒草稿后重新生成草稿"));
            return SanitationAuthority.none();
        }
    }

    private SanitationAuthority sanitationAuthority(
            SanitationCipTemplateDraft parent,
            SanitationCipDraftModels.DraftReadback readback) {
        Map<String, SanitationTarget> targets = new LinkedHashMap<>();
        for (SanitationCipDraftModels.DerivedTemplate template : readback.templates()) {
            targets.putIfAbsent(template.templateCode(), new SanitationTarget(
                    template.ordinal(), template.templateCode(), template.productCode(),
                    template.routeNodeId(), template.routeNodeLabel(),
                    template.targetType().name(), template.targetSnapshotDigest(),
                    template.holdUntilVerified()));
        }
        return new SanitationAuthority(parent.getId(), parent.getDesiredDigest(),
                digestSanitation(readback), Map.copyOf(targets));
    }

    private ZoneAuthority resolveZones(DraftRequest request, List<DraftIssue> issues) {
        HygienicZonePin pin = request.hygienicZone();
        if (pin == null || pin.draftId().isBlank() || !isDigest(pin.desiredDigest())) {
            issues.add(issue("ENVIRONMENTAL_MONITORING_ZONE_PIN_INVALID", "hygienicZone",
                    "环境监测必须锁定同一 Blueprint 的卫生分区草稿作为安全 pin",
                    "选择当前 Blueprint 的卫生分区草稿并刷新摘要"));
            return ZoneAuthority.none();
        }
        HygienicZoneDraft parent = zoneDrafts
                .findByIdAndFactoryId(pin.draftId(), request.factoryId()).orElse(null);
        if (!sameZoneLineage(parent, request, pin.desiredDigest())) {
            issues.add(issue("ENVIRONMENTAL_MONITORING_ZONE_DRIFT", "hygienicZone",
                    "卫生分区草稿不属于当前 Blueprint 或摘要已变化",
                    "重新读取并选择当前卫生分区草稿"));
            return ZoneAuthority.none();
        }
        try {
            return zoneAuthority(parent, zoneCommand.readBack(request.factoryId(), pin.draftId()));
        } catch (RuntimeException exception) {
            issues.add(issue("ENVIRONMENTAL_MONITORING_ZONE_READBACK_FAILED", "hygienicZone",
                    "卫生分区草稿逐字段回读失败", "修复卫生分区草稿后重新预览"));
            return ZoneAuthority.none();
        }
    }

    private ZoneAuthority resolveStoredZones(EnvironmentalMonitoringTemplateDraft parent,
                                             List<DraftIssue> issues) {
        HygienicZoneDraft stored = zoneDrafts.findByIdAndFactoryId(
                parent.getHygienicZoneDraftId(), parent.getFactoryId()).orElse(null);
        if (stored == null
                || !Objects.equals(parent.getHygienicZoneDesiredDigest(), stored.getDesiredDigest())
                || !parent.getBlueprintId().equals(stored.getBlueprintId())
                || !Objects.equals(parent.getBlueprintVersion(), stored.getBlueprintVersion())
                || !parent.getSourceDigest().equals(stored.getSourceDigest())) {
            issues.add(issue("ENVIRONMENTAL_MONITORING_ZONE_DRIFT", "hygienicZone",
                    "已保存的卫生分区 pin 已漂移", "重新生成环境监测模板草稿"));
            return ZoneAuthority.none();
        }
        try {
            return zoneAuthority(stored,
                    zoneCommand.readBack(parent.getFactoryId(), stored.getId()));
        } catch (RuntimeException exception) {
            issues.add(issue("ENVIRONMENTAL_MONITORING_ZONE_READBACK_FAILED", "hygienicZone",
                    "已保存的卫生分区草稿逐字段回读失败", "修复卫生分区草稿后重新生成草稿"));
            return ZoneAuthority.none();
        }
    }

    private ZoneAuthority zoneAuthority(HygienicZoneDraft parent,
                                        HygienicZoneDraftModels.DraftReadback readback) {
        Map<String, ZoneTarget> zones = new LinkedHashMap<>();
        for (HygienicZoneDraftModels.RequestedZone zone : readback.zones()) {
            zones.putIfAbsent(zone.zoneCode(), new ZoneTarget(zone.zoneCode(), zone.zoneName(),
                    zone.hygieneLevel().name()));
        }
        return new ZoneAuthority(parent.getId(), parent.getDesiredDigest(),
                digestZones(readback), Map.copyOf(zones));
    }

    private boolean sameLineage(SanitationCipTemplateDraft parent, DraftRequest request,
                                String desiredDigest) {
        return parent != null && "DRAFT".equals(parent.getStatus())
                && Boolean.TRUE.equals(parent.getDependencyClosureComplete())
                && request.blueprintId().equals(parent.getBlueprintId())
                && Objects.equals(request.blueprintVersion(), parent.getBlueprintVersion())
                && request.sourceDigest().equals(parent.getSourceDigest())
                && desiredDigest.equals(parent.getDesiredDigest());
    }

    private boolean sameZoneLineage(HygienicZoneDraft parent, DraftRequest request,
                                    String desiredDigest) {
        return parent != null && "DRAFT".equals(parent.getStatus())
                && Boolean.TRUE.equals(parent.getDependencyClosureComplete())
                && request.blueprintId().equals(parent.getBlueprintId())
                && Objects.equals(request.blueprintVersion(), parent.getBlueprintVersion())
                && request.sourceDigest().equals(parent.getSourceDigest())
                && desiredDigest.equals(parent.getDesiredDigest());
    }

    private boolean validatePoint(MonitoringPointInput input, String target,
                                  List<DraftIssue> issues) {
        if (input == null || isBlankOrTooLong(input.zoneCode(), 40)
                || isBlankOrTooLong(input.sanitationTemplateCode(), 100)
                || input.contactClass() == null || input.sampleMedium() == null
                || input.targetOrganism() == null || input.samplingTiming() == null
                || input.samplingFrequency() == null || input.resultUnit() == null
                || input.responsibleRole() == null
                || isBlankOrTooLong(input.changeReason(), 500)) {
            issues.add(issue("ENVIRONMENTAL_MONITORING_POINT_FIELDS_REQUIRED", target,
                    "分区、清洗模板、接触等级、介质、目标微生物、时机、频率、单位、责任和变更原因均必填",
                    "补齐 typed 采样点字段"));
            return false;
        }
        boolean valid = true;
        if (input.sampleSizeCount() == null || input.sampleSizeCount() < 1
                || input.sampleSizeCount() > 50 || input.resultHoldHours() == null
                || input.resultHoldHours() < 1 || input.resultHoldHours() > 168) {
            issues.add(issue("ENVIRONMENTAL_MONITORING_SAMPLE_ENVELOPE_INVALID", target,
                    "采样点数必须为 1–50，出结果时长必须为 1–168 小时", "修正采样点数与出结果时长"));
            valid = false;
        }
        if (!allowedUnits(input.sampleMedium()).contains(input.resultUnit())) {
            issues.add(issue("ENVIRONMENTAL_MONITORING_MEDIUM_UNIT_MISMATCH", target,
                    "采样介质与结果单位量纲不匹配", "选择该介质允许的结果单位"));
            valid = false;
        }
        if ((input.targetOrganism() == TargetOrganism.ATP_RESIDUE)
                != (input.resultUnit() == ResultUnit.RLU)) {
            issues.add(issue("ENVIRONMENTAL_MONITORING_ATP_UNIT_MISMATCH", target,
                    "ATP 残留只能使用 RLU，RLU 也只能用于 ATP 残留", "修正目标微生物或结果单位"));
            valid = false;
        }
        if (!validateThresholds(input, target, issues)) {
            valid = false;
        }
        boolean pathogen = EnvironmentalMonitoringDraftModels.pathogenTarget(input.targetOrganism());
        if (pathogen && input.contactClass() == ContactClass.FOOD_CONTACT
                && input.resultUnit() != ResultUnit.PRESENCE_ABSENCE) {
            issues.add(issue("ENVIRONMENTAL_MONITORING_PATHOGEN_REQUIRES_PRESENCE_ABSENCE", target,
                    "食品接触面致病菌只能定性判定，不接受数值限值",
                    "把结果单位改为 PRESENCE_ABSENCE"));
            valid = false;
        }
        if (input.contactClass() == ContactClass.FOOD_CONTACT
                && input.samplingTiming() == SamplingTiming.DURING_OPERATION) {
            issues.add(issue("ENVIRONMENTAL_MONITORING_FOOD_CONTACT_TIMING_INVALID", target,
                    "食品接触面采样只能在清洗后或开机前进行",
                    "改为 POST_SANITATION 或 PRE_OPERATIONAL"));
            valid = false;
        }
        if (input.requiredEvidence().isEmpty() || input.requiredEvidence().size() > 4
                || new HashSet<>(input.requiredEvidence()).size() != input.requiredEvidence().size()
                || (pathogen && !input.requiredEvidence().contains(EvidenceType.LAB_REPORT))) {
            issues.add(issue("ENVIRONMENTAL_MONITORING_EVIDENCE_INVALID", target,
                    "留证类型必须为非空、不重复的封闭枚举，致病菌采样必须留实验室报告",
                    "选择有效留证组合"));
            valid = false;
        }
        return valid;
    }

    private boolean validateThresholds(MonitoringPointInput input, String target,
                                       List<DraftIssue> issues) {
        boolean qualitative = input.resultUnit() == ResultUnit.PRESENCE_ABSENCE;
        boolean bothNull = input.alertThreshold() == null && input.actionThreshold() == null;
        if (qualitative) {
            if (!bothNull) {
                issues.add(issue("ENVIRONMENTAL_MONITORING_THRESHOLD_FORBIDDEN", target,
                        "定性判定不能携带数值阈值", "删除告警与行动阈值"));
                return false;
            }
            return true;
        }
        if (!validDecimal(input.alertThreshold()) || !validDecimal(input.actionThreshold())
                || input.alertThreshold().signum() <= 0
                || input.alertThreshold().compareTo(input.actionThreshold()) > 0) {
            issues.add(issue("ENVIRONMENTAL_MONITORING_THRESHOLD_INVALID", target,
                    "定量判定必须提供正数且告警不高于行动的 typed 阈值",
                    "修正告警与行动阈值"));
            return false;
        }
        return true;
    }

    private Set<ResultUnit> allowedUnits(SampleMedium medium) {
        return switch (medium) {
            case SURFACE_SWAB, SPONGE_SWAB -> Set.of(ResultUnit.CFU_PER_SWAB,
                    ResultUnit.CFU_PER_CM2, ResultUnit.RLU, ResultUnit.PRESENCE_ABSENCE);
            case AIR_SETTLE_PLATE, AIR_ACTIVE_SAMPLER -> Set.of(ResultUnit.CFU_PER_PLATE);
            case WATER, CONDENSATE -> Set.of(ResultUnit.CFU_PER_ML, ResultUnit.PRESENCE_ABSENCE);
        };
    }

    private DerivedPoint derive(int ordinal, MonitoringPointInput input,
                                SanitationTarget sanitation, ZoneTarget zone) {
        List<EvidenceType> requiredEvidence = input.requiredEvidence().stream()
                .sorted(Comparator.comparing(Enum::name)).toList();
        ResponseLevel responseLevel = EnvironmentalMonitoringDraftModels
                .responseLevel(input.contactClass(), input.targetOrganism());
        String code = "EM-" + digest(zone.zoneCode(), sanitation.templateCode(),
                input.contactClass().name(), input.sampleMedium().name(),
                input.targetOrganism().name()).substring(0, 16).toUpperCase(Locale.ROOT);
        return new DerivedPoint(ordinal, code, zone.zoneCode(), zone.zoneName(),
                zone.hygieneLevel(), sanitation.templateCode(), sanitation.ordinal(),
                sanitation.productCode(), sanitation.routeNodeId(), sanitation.routeNodeLabel(),
                targetSnapshot(sanitation, zone), input.contactClass(), input.sampleMedium(),
                input.targetOrganism(), input.samplingTiming(), input.samplingFrequency(),
                input.sampleSizeCount(), input.resultUnit(), input.alertThreshold(),
                input.actionThreshold(), input.resultHoldHours(), input.responsibleRole(),
                requiredEvidence,
                EnvironmentalMonitoringDraftModels.pathogenTarget(input.targetOrganism()),
                responseLevel, EnvironmentalMonitoringDraftModels.escalationRole(responseLevel),
                EnvironmentalMonitoringDraftModels.vectorSamplingRequired(input.targetOrganism()),
                EnvironmentalMonitoringDraftModels.resampleRequired(responseLevel),
                input.changeReason());
    }

    private EnvironmentalMonitoringTemplateDraftPoint toRow(
            EnvironmentalMonitoringTemplateDraft parent, DerivedPoint value) {
        return EnvironmentalMonitoringTemplateDraftPoint.builder()
                .id(UUID.randomUUID().toString()).draftId(parent.getId())
                .factoryId(parent.getFactoryId()).ordinal(value.ordinal())
                .pointCode(value.pointCode())
                .sanitationCipDraftId(parent.getSanitationCipDraftId())
                .sanitationItemOrdinal(value.sanitationItemOrdinal())
                .sanitationTemplateCode(value.sanitationTemplateCode())
                .hygienicZoneDraftId(parent.getHygienicZoneDraftId())
                .zoneCode(value.zoneCode()).zoneName(value.zoneName())
                .zoneHygieneLevel(value.zoneHygieneLevel())
                .productCode(value.productCode()).routeNodeId(value.routeNodeId())
                .routeNodeLabel(value.routeNodeLabel())
                .targetSnapshotDigest(value.targetSnapshotDigest())
                .contactClass(value.contactClass().name())
                .sampleMedium(value.sampleMedium().name())
                .targetOrganism(value.targetOrganism().name())
                .samplingTiming(value.samplingTiming().name())
                .samplingFrequency(value.samplingFrequency().name())
                .sampleSizeCount(value.sampleSizeCount())
                .resultUnit(value.resultUnit().name())
                .alertThreshold(value.alertThreshold()).actionThreshold(value.actionThreshold())
                .resultHoldHours(value.resultHoldHours())
                .responsibleRole(value.responsibleRole().name())
                .requiredEvidence(joinEnums(value.requiredEvidence()))
                .pathogenTarget(value.pathogenTarget())
                .responseLevel(value.responseLevel().name())
                .escalationRole(value.escalationRole().name())
                .vectorSamplingRequired(value.vectorSamplingRequired())
                .resampleRequired(value.resampleRequired())
                .changeReason(value.changeReason()).build();
    }

    private DerivedPoint fromRow(EnvironmentalMonitoringTemplateDraftPoint row) {
        return new DerivedPoint(row.getOrdinal(), row.getPointCode(), row.getZoneCode(),
                row.getZoneName(), row.getZoneHygieneLevel(), row.getSanitationTemplateCode(),
                row.getSanitationItemOrdinal(), row.getProductCode(), row.getRouteNodeId(),
                row.getRouteNodeLabel(), row.getTargetSnapshotDigest(),
                ContactClass.valueOf(row.getContactClass()),
                SampleMedium.valueOf(row.getSampleMedium()),
                TargetOrganism.valueOf(row.getTargetOrganism()),
                SamplingTiming.valueOf(row.getSamplingTiming()),
                SamplingFrequency.valueOf(row.getSamplingFrequency()),
                row.getSampleSizeCount(), ResultUnit.valueOf(row.getResultUnit()),
                row.getAlertThreshold(), row.getActionThreshold(), row.getResultHoldHours(),
                ResponsibleRole.valueOf(row.getResponsibleRole()),
                parseEnums(row.getRequiredEvidence(), EvidenceType::valueOf),
                Boolean.TRUE.equals(row.getPathogenTarget()),
                ResponseLevel.valueOf(row.getResponseLevel()),
                EscalationRole.valueOf(row.getEscalationRole()),
                Boolean.TRUE.equals(row.getVectorSamplingRequired()),
                Boolean.TRUE.equals(row.getResampleRequired()), row.getChangeReason());
    }

    private void authorize(DraftRequest request, AuthenticatedOperator operator) {
        if (operator == null || (operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER && operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.PLATFORM_ADMIN) || operator.actor().id() != request.operatorId()
                || !request.factoryId().equals(operator.factoryId())) {
            throw invalid(403, "操作人和工厂范围不一致",
                    "ENVIRONMENTAL_MONITORING_OPERATOR_SCOPE_MISMATCH");
        }
        if (!operator.permissions().contains(REQUIRED_PERMISSION)) {
            throw invalid(403, "缺少环境监测模板草稿权限",
                    "ENVIRONMENTAL_MONITORING_PERMISSION_REQUIRED");
        }
        Optional<User> member = users.findByIdAndFactoryId(operator.operatorId(), operator.factoryId());
        if (member.filter(user -> Boolean.TRUE.equals(user.getIsActive())).isEmpty()) {
            throw invalid(403, "操作人不是当前工厂有效成员",
                    "ENVIRONMENTAL_MONITORING_MEMBER_REQUIRED");
        }
    }

    private void validateEnvelope(DraftRequest request, boolean materialize) {
        if (request == null || request.factoryId().isBlank() || request.factoryId().length() > 50
                || request.blueprintId().isBlank() || request.blueprintId().length() > 100
                || request.blueprintVersion() <= 0 || !isDigest(request.sourceDigest())
                || request.points().isEmpty() || request.points().size() > MAX_POINTS) {
            throw invalid(400, "Blueprint envelope 或环境采样点数量无效",
                    "ENVIRONMENTAL_MONITORING_SCOPE_REQUIRED");
        }
        if (materialize && (request.operatorId() <= 0 || request.idempotencyKey().isBlank()
                || request.idempotencyKey().length() > 128
                || !isDigest(request.confirmedPreviewDigest()))) {
            throw invalid(400, "操作人、幂等键或确认摘要无效",
                    "ENVIRONMENTAL_MONITORING_MATERIALIZE_ENVELOPE_INVALID");
        }
    }

    private DraftReceipt receipt(EnvironmentalMonitoringTemplateDraft row, boolean replayed,
                                 int moduleWrites, int persistedRows) {
        return new DraftReceipt(EnvironmentalMonitoringDraftModels.SCHEMA_VERSION, row.getId(),
                row.getFactoryId(), row.getId(), row.getStatus(), row.getSourceSnapshotDigest(),
                row.getDesiredDigest(), row.getPreviewDigest(), row.getRequestDigest(),
                row.getPointCount(), row.getEvidenceCount(),
                row.getLockVersion() == null ? 0L : row.getLockVersion(), replayed,
                moduleWrites, replayed ? 1 : moduleWrites, persistedRows,
                0, 0, 0, 0, 0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED", "ARCHIVE_DRAFT_ONLY");
    }

    private boolean sameEvidence(EnvironmentalMonitoringTemplateDraftEvidence row,
                                 BlueprintAuthority authority) {
        return AUTHORITY_CODE.equals(row.getAuthorityCode())
                && authority.catalogVersion().equals(row.getCatalogVersion())
                && authority.catalogDigest().equals(row.getCatalogDigest())
                && authorityEvidenceDigest(authority).equals(row.getAuthoritySnapshotDigest());
    }

    private String sourceSnapshot(BlueprintAuthority authority, SanitationAuthority sanitation,
                                  ZoneAuthority zones) {
        return digest("ENVIRONMENTAL_MONITORING_SOURCE", authority.blueprintSnapshotDigest(),
                sanitation.snapshotDigest(), zones.snapshotDigest(),
                authority.authoritySnapshotDigest());
    }

    private String digestSanitation(SanitationCipDraftModels.DraftReadback readback) {
        List<String> values = new ArrayList<>();
        values.add(readback.schemaVersion());
        values.add(readback.factoryId());
        values.add(readback.draftId());
        values.add(readback.status());
        values.add(readback.desiredDigest());
        for (SanitationCipDraftModels.DerivedTemplate template : readback.templates()) {
            values.add(template.ordinal() + "|" + template.templateCode() + "|"
                    + template.productCode() + "|" + template.routeNodeId() + "|"
                    + template.routeNodeLabel() + "|" + template.targetType() + "|"
                    + template.targetSnapshotDigest() + "|" + template.holdUntilVerified());
        }
        return digest(values.toArray(String[]::new));
    }

    private String digestZones(HygienicZoneDraftModels.DraftReadback readback) {
        List<String> values = new ArrayList<>();
        values.add(readback.schemaVersion());
        values.add(readback.factoryId());
        values.add(readback.draftId());
        values.add(readback.status());
        values.add(readback.desiredDigest());
        Set<String> ordered = new TreeSet<>();
        readback.zones().forEach(zone -> ordered.add("Z|" + zone.zoneCode() + "|"
                + zone.zoneName() + "|" + zone.hygieneLevel() + "|" + zone.temperatureClass()
                + "|" + zone.entryPolicy()));
        readback.flows().forEach(flow -> ordered.add("F|" + flow.flowType() + "|"
                + flow.fromZoneCode() + "|" + flow.toZoneCode() + "|" + flow.direction()
                + "|" + flow.crossingPolicy() + "|" + flow.controlPoint()));
        values.addAll(ordered);
        return digest(values.toArray(String[]::new));
    }

    private String targetSnapshot(SanitationTarget sanitation, ZoneTarget zone) {
        return digest("ENVIRONMENTAL_MONITORING_TARGET", sanitation.templateCode(),
                String.valueOf(sanitation.ordinal()), sanitation.productCode(),
                sanitation.routeNodeId(), sanitation.routeNodeLabel(), sanitation.targetType(),
                sanitation.targetSnapshotDigest(), zone.zoneCode(), zone.zoneName(),
                zone.hygieneLevel());
    }

    private String desiredDigest(List<DerivedPoint> derived) {
        List<String> values = new ArrayList<>(
                List.of(EnvironmentalMonitoringDraftModels.SCHEMA_VERSION,
                        EnvironmentalMonitoringDraftModels.ALGORITHM_VERSION));
        derived.stream().sorted(Comparator.comparingInt(DerivedPoint::ordinal))
                .forEach(value -> values.add(value.ordinal() + "|" + value.pointCode() + "|"
                        + value.zoneCode() + "|" + value.zoneName() + "|"
                        + value.zoneHygieneLevel() + "|" + value.sanitationTemplateCode() + "|"
                        + value.sanitationItemOrdinal() + "|" + value.productCode() + "|"
                        + value.routeNodeId() + "|" + value.routeNodeLabel() + "|"
                        + value.targetSnapshotDigest() + "|" + value.contactClass() + "|"
                        + value.sampleMedium() + "|" + value.targetOrganism() + "|"
                        + value.samplingTiming() + "|" + value.samplingFrequency() + "|"
                        + value.sampleSizeCount() + "|" + value.resultUnit() + "|"
                        + decimal(value.alertThreshold()) + "|" + decimal(value.actionThreshold())
                        + "|" + value.resultHoldHours() + "|" + value.responsibleRole() + "|"
                        + joinEnums(value.requiredEvidence()) + "|" + value.pathogenTarget() + "|"
                        + value.responseLevel() + "|" + value.escalationRole() + "|"
                        + value.vectorSamplingRequired() + "|" + value.resampleRequired() + "|"
                        + value.changeReason()));
        return digest(values.toArray(String[]::new));
    }

    private String requestDigest(DraftRequest request, AuthenticatedOperator operator) {
        DraftPreview preview = evaluate(request).preview();
        return digest(request.factoryId(), request.blueprintId(),
                String.valueOf(request.blueprintVersion()), request.sourceDigest(),
                operator.actor().type().name() + ":" + String.valueOf(operator.actor().id()), request.idempotencyKey(),
                request.confirmedPreviewDigest(), preview.desiredDigest());
    }

    private String digestBlueprint(BlueprintResponse response) {
        return digest("BLUEPRINT", response.getFactoryId(), response.getId(),
                String.valueOf(response.getVersion()), String.valueOf(response.getRowVersion()),
                response.getLifecycleState(), response.getSchemaVersion(),
                response.getCapabilityCatalogVersion(), response.getCapabilityCatalogDigest(),
                String.valueOf(response.getUpdatedAt()));
    }

    private String authorityEvidenceDigest(BlueprintAuthority authority) {
        return digest("CATALOG_AUTHORITY", authority.catalogVersion(), authority.catalogDigest(),
                AUTHORITY_CODE, authority.authoritySnapshotDigest());
    }

    private String issueDigest(List<DraftIssue> issues) {
        return digest(issues.stream().map(issue -> issue.code() + "|" + issue.target() + "|"
                + issue.message() + "|" + issue.recovery()).toArray(String[]::new));
    }

    private boolean isIdempotencyRace(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (String.valueOf(current.getMessage()).toLowerCase(Locale.ROOT)
                    .contains("uk_env_monitoring_draft_idempotency")) return true;
        }
        return false;
    }

    /**
     * Does this value survive a round trip through {@code NUMERIC(12,4)}?
     *
     * <p>The obvious check -- precision <= 12 and scale <= 4 -- is wrong, and wrong in the
     * direction that matters: {@code NUMERIC(P,S)} stores at most {@code P - S} digits before
     * the point, so it accepts 8 integer digits, not 12. A value the preview called READY was
     * then refused by the column at materialize time, surfacing as a bare 500 rather than a
     * named issue, and breaking the rule this platform is built on -- that preview and the
     * database judge the same request the same way.</p>
     *
     * <p>Negative scale is the second hole: {@code 1E+20} arrives from JSON with precision 1 and
     * scale -20, so it passed both halves of the old check while being a 21-digit integer.</p>
     */
    private boolean validDecimal(BigDecimal value) {
        if (value == null || value.scale() > 4) {
            return false;
        }
        BigDecimal plain = value.stripTrailingZeros();
        if (plain.scale() < 0) {
            plain = plain.setScale(0);
        }
        return plain.precision() - plain.scale() <= 8;
    }

    private boolean isBlankOrTooLong(String value, int max) {
        return value == null || value.isBlank() || value.length() > max;
    }

    private boolean isDigest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private String decimal(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private String joinEnums(List<? extends Enum<?>> values) {
        return String.join("|", values.stream().map(Enum::name).toList());
    }

    private <E> List<E> parseEnums(String value, java.util.function.Function<String, E> parser) {
        if (value == null || value.isBlank()) return List.of();
        return List.of(value.split("\\|", -1)).stream().map(parser).toList();
    }

    private String digest(String... values) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
                md.update((byte) (bytes.length >>> 24));
                md.update((byte) (bytes.length >>> 16));
                md.update((byte) (bytes.length >>> 8));
                md.update((byte) bytes.length);
                md.update(bytes);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private DraftIssue issue(String code, String target, String message, String recovery) {
        return new DraftIssue(code, target, message, recovery);
    }

    private BusinessException invalid(int status, String message, String code) {
        return new BusinessException(status, message).withCode(code);
    }

    private record Evaluation(DraftPreview preview, BlueprintAuthority authority,
                              SanitationAuthority sanitation, ZoneAuthority zones) {
    }

    private record BlueprintAuthority(String catalogVersion, String catalogDigest,
                                      Set<String> authorityRefs, String authoritySnapshotDigest,
                                      String blueprintSnapshotDigest) {
        static BlueprintAuthority missing() {
            return new BlueprintAuthority("", digestStatic("MISSING_CATALOG"), Set.of(),
                    digestStatic("MISSING_AUTHORITY"), digestStatic("MISSING_BLUEPRINT"));
        }
    }

    private record SanitationAuthority(String draftId, String desiredDigest, String snapshotDigest,
                                       Map<String, SanitationTarget> targets) {
        static SanitationAuthority none() {
            return new SanitationAuthority(null, null, digestStatic("NO_SANITATION_DRAFT"),
                    Map.of());
        }
    }

    private record ZoneAuthority(String draftId, String desiredDigest, String snapshotDigest,
                                 Map<String, ZoneTarget> zones) {
        static ZoneAuthority none() {
            return new ZoneAuthority(null, null, digestStatic("NO_HYGIENIC_ZONE_DRAFT"), Map.of());
        }
    }

    private record SanitationTarget(int ordinal, String templateCode, String productCode,
                                    String routeNodeId, String routeNodeLabel, String targetType,
                                    String targetSnapshotDigest, boolean holdUntilVerified) {
    }

    private record ZoneTarget(String zoneCode, String zoneName, String hygieneLevel) {
    }

    private static String digestStatic(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
