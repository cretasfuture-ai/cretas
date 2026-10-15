package com.cretas.aims.blueprint.canonical;

import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.CapabilityAcceptanceRequest;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.CapabilityCatalogView;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.CapabilityView;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.ConversionEvidence;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.DiffSummary;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.OperationRequest;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.ReceiptView;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.SaveDraftRequest;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.SimulationReport;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.ValidationIssue;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.ValidationReport;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.VersionDetailView;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.VersionView;
import com.cretas.aims.blueprint.canonical.FactoryBlueprintCapabilityCatalog.CatalogSnapshot;
import com.cretas.aims.blueprint.canonical.FactoryBlueprintCapabilityCatalog.FactoryTypeCatalogRef;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitResult;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.PlannedChange;
import com.cretas.aims.entity.config.BlueprintApplication;
import com.cretas.aims.entity.config.BlueprintVersionHistory;
import com.cretas.aims.entity.config.FactoryBlueprintBinding;
import com.cretas.aims.entity.config.FactoryTypeBlueprint;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.config.BlueprintApplicationRepository;
import com.cretas.aims.repository.config.BlueprintVersionHistoryRepository;
import com.cretas.aims.repository.config.FactoryBlueprintBindingRepository;
import com.cretas.aims.repository.config.FactoryTypeBlueprintRepository;
import com.cretas.aims.blueprint.interview.FactoryBuildApplyService;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.blueprint.interview.FactoryBuildPlanService;
import com.cretas.aims.blueprint.interview.FactoryBuildApplyValidator.BlueprintSemanticIssue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Factory-scoped authority over onboarding blueprints and their operation receipts. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
@RequiredArgsConstructor
public class CanonicalFactoryBlueprintService {

    private static final String SCOPE_FACTORY = "FACTORY";
    private static final String DRAFT = "DRAFT";
    private static final String VALIDATED = "VALIDATED";
    private static final String PUBLISHED = "PUBLISHED";
    private static final String APPLIED = "APPLIED";
    private static final String SUPERSEDED = "SUPERSEDED";

    private final FactoryTypeBlueprintRepository blueprintRepository;
    private final BlueprintVersionHistoryRepository historyRepository;
    private final FactoryBlueprintBindingRepository bindingRepository;
    private final BlueprintApplicationRepository applicationRepository;
    private final FactoryBuildPlanService planService;
    private final FactoryBuildApplyService applyService;
    private final FactoryBlueprintCapabilityCatalog capabilityCatalog;
    private final ObjectMapper objectMapper;
    @org.springframework.beans.factory.annotation.Autowired
    CanonicalBlueprintActorAuthority actorAuthority;
    @org.springframework.beans.factory.annotation.Autowired
    com.cretas.aims.repository.FactoryRepository factoryRepository;

    private void requireWriteAuthority(String factoryId, ActorRef actor) {
        if (actorAuthority == null) {
            throw new IllegalStateException("Canonical blueprint authority is not configured");
        }
        actorAuthority.requireAuthorized(factoryId, actor);
    }

    private static ActorRef legacyFactoryActor(Long id) {
        return id == null ? null : new ActorRef(AuthenticatedActorType.FACTORY_USER, id);
    }

    private static ActorRef actor(AuthenticatedActorType type, Long id) {
        return type == null || id == null ? null : new ActorRef(type, id);
    }

    /** Source-compatible adapter for pre-typed factory callers only. */
    @Transactional
    public BlueprintResponse saveDraft(String factoryId, SaveDraftRequest request, Long id) {
        return saveDraft(factoryId, request, legacyFactoryActor(id));
    }
    @Transactional
    public BlueprintResponse validate(String factoryId, String blueprintId, OperationRequest request, Long id) {
        return validate(factoryId, blueprintId, request, legacyFactoryActor(id));
    }
    @Transactional
    public BlueprintResponse simulate(String factoryId, String blueprintId, OperationRequest request, Long id) {
        return simulate(factoryId, blueprintId, request, legacyFactoryActor(id));
    }
    @Transactional
    public BlueprintResponse publish(String factoryId, String blueprintId, OperationRequest request, Long id) {
        return publish(factoryId, blueprintId, request, legacyFactoryActor(id));
    }
    @Transactional
    public BlueprintResponse apply(String factoryId, String blueprintId, OperationRequest request, Long id) {
        return apply(factoryId, blueprintId, request, legacyFactoryActor(id));
    }
    @Transactional
    public BlueprintResponse accept(String factoryId, String blueprintId, CapabilityAcceptanceRequest request, Long id) {
        return accept(factoryId, blueprintId, request, legacyFactoryActor(id));
    }
    @Transactional
    public BlueprintResponse recoveryPlan(String factoryId, String blueprintId, OperationRequest request, Long id) {
        return recoveryPlan(factoryId, blueprintId, request, legacyFactoryActor(id));
    }

    @Transactional
    public BlueprintResponse saveDraft(String factoryId, SaveDraftRequest request, ActorRef actor) {
        requireWriteAuthority(factoryId, actor);
        requireText(factoryId, "工厂 ID");
        requireText(request == null ? null : request.getIdempotencyKey(), "幂等键");
        if (request.getPayload() == null) {
            throw invalid("BLUEPRINT_SOURCE_REQUIRED", "缺少建厂配置，无法保存蓝图草稿");
        }

        // A factory row exists even before its first blueprint. Locking it also serializes
        // first saves across processes, when there is no current blueprint row to lock.
        factoryRepository.findByIdForUpdate(factoryId)
                .orElseThrow(() -> invalid("BLUEPRINT_FACTORY_NOT_FOUND", "目标工厂不存在，不能保存建厂草稿"));
        String requestDigest = saveRequestDigest(request, actor);
        Optional<BlueprintApplication> replay = applicationRepository
                .findByFactoryIdAndOperationTypeAndIdempotencyKeyAndDeletedAtIsNull(
                        factoryId, "SAVE_DRAFT", request.getIdempotencyKey().trim());
        if (replay.isPresent()) {
            Map<String, Object> result = readMap(replay.get().getOperationResult());
            if (result == null || !Objects.equals(requestDigest, result.get("requestDigest"))) {
                throw new BusinessException(409, "同一幂等键对应的建厂请求或操作人已变化，请使用新幂等键");
            }
            if (result.get("response") == null) {
                throw state("建厂保存回执缺少原始结果，不能安全重放");
            }
            return objectMapper.convertValue(result.get("response"), BlueprintResponse.class);
        }

        // Existing authoritative planner validates the exact payload and derives the only
        // accepted write plan. This call computes; it does not write business configuration.
        List<PlannedChange> changes = planService.preview(
                factoryId, request.getPayload(), request.getConversionEvidence());
        CatalogSnapshot catalog = capabilityCatalog.current();
        List<CapabilityView> capabilities = capabilityCatalog.derive(catalog, changes, request.getPayload());

        FactoryTypeBlueprint current = blueprintRepository.lockCurrentByFactoryId(factoryId)
                .orElse(null);
        FactoryTypeBlueprint target;
        if (current == null) {
            target = new FactoryTypeBlueprint();
            target.setVersion(1);
            target.setBlueprintCreatedActor(actor);
        } else {
            verifyExpectedVersion(current, request.getExpectedRowVersion());
            if (PUBLISHED.equals(current.getLifecycleState()) || APPLIED.equals(current.getLifecycleState())) {
                current.setIsCurrent(false);
                current.setLifecycleState(SUPERSEDED);
                current.setBlueprintUpdatedActor(actor);
                // Release the partial unique-current index before Hibernate queues the new version insert.
                // Both writes remain in this transaction, so a failed insert restores the previous current row.
                blueprintRepository.saveAndFlush(current);

                target = new FactoryTypeBlueprint();
                target.setVersion(current.getVersion() + 1);
                target.setBlueprintCreatedActor(actor);
            } else if (DRAFT.equals(current.getLifecycleState()) || VALIDATED.equals(current.getLifecycleState())) {
                target = current;
                if (target.getCapabilityCatalogVersion() != null
                        || target.getCapabilityCatalogDigest() != null) {
                    capabilityCatalog.verifyLockedCatalog(new FactoryTypeCatalogRef(
                            target.getCapabilityCatalogVersion(), target.getCapabilityCatalogDigest()), catalog);
                }
            } else {
                throw state("不能从 " + current.getLifecycleState() + " 状态保存新草稿");
            }
        }

        String factoryName = trimToNull(request.getFactoryName());
        target.setFactoryId(factoryId);
        target.setFactoryName(factoryName == null ? factoryId : factoryName);
        target.setName((factoryName == null ? factoryId : factoryName) + " · 建厂蓝图 v" + target.getVersion());
        target.setDescription("由真实 onboarding 问卷、Agent 对话与服务端写入计划生成");
        target.setIndustryType(trimToNull(request.getPayload().getIndustryCode()));
        target.setScopeType(SCOPE_FACTORY);
        target.setSchemaVersion(CanonicalFactoryBlueprintModels.SCHEMA_VERSION);
        target.setLifecycleState(DRAFT);
        target.setIsCurrent(true);
        target.setIsActive(true);
        target.setDraftIdempotencyKey(request.getIdempotencyKey().trim());
        target.setRequestDigest(requestDigest);
        target.setBlueprintUpdatedActor(actor);
        target.setSourceSnapshot(write(sourceEnvelope(request)));
        target.setDefaultConfig(write(request.getPayload()));
        target.setCapabilitySnapshot(write(capabilities));
        target.setCapabilityCatalogVersion(catalog.version());
        target.setCapabilityCatalogDigest(catalog.digest());
        target.setPlanSnapshot(write(changes));
        target.setValidationReport(null);
        target.setSimulationReport(null);
        target.setConfirmedAt(null);
        target.setPublishedAt(null);
        target.setPublishedBy(null);

        BlueprintResponse response = toResponse(blueprintRepository.saveAndFlush(target), null);
        receipt(target, "SAVE_DRAFT", request.getIdempotencyKey(), actor,
                BlueprintApplication.ApplicationStatus.COMPLETED,
                Map.of("requestDigest", requestDigest, "response", response), List.of(), null, 0);
        return response;
    }

    @Transactional(readOnly = true)
    public BlueprintResponse current(String factoryId) {
        return blueprintRepository.findByFactoryIdAndIsCurrentTrueAndDeletedAtIsNull(factoryId)
                .map(blueprint -> toResponse(blueprint, null))
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public CapabilityCatalogView catalog(String factoryId, String catalogVersion) {
        requireText(factoryId, "工厂 ID");
        CatalogSnapshot snapshot = catalogVersion == null || catalogVersion.isBlank()
                ? capabilityCatalog.current()
                : capabilityCatalog.load(catalogVersion);
        return capabilityCatalog.toView(snapshot);
    }

    @Transactional(readOnly = true)
    public List<VersionView> versions(String factoryId, String blueprintId) {
        requireBlueprint(factoryId, blueprintId);
        return historyRepository
                .findByFactoryIdAndDeletedAtIsNullOrderByVersionDesc(factoryId)
                .stream()
                .filter(history -> Boolean.TRUE.equals(history.getIsPublished()))
                .map(history -> VersionView.builder()
                        .blueprintId(history.getBlueprintId())
                        .factoryId(history.getFactoryId())
                        .version(history.getVersion())
                        .schemaVersion(history.getSchemaVersion())
                        .lifecycleState(history.getLifecycleState())
                        .published(Boolean.TRUE.equals(history.getIsPublished()))
                        .publishedAt(history.getPublishedAt())
                        .createdAt(history.getCreatedAt())
                        .build())
                .toList();
    }

    @Transactional(readOnly = true)
    public VersionDetailView version(String factoryId, String blueprintId, Integer version) {
        requireBlueprint(factoryId, blueprintId);
        BlueprintVersionHistory history = historyRepository
                .findByFactoryIdAndVersionAndDeletedAtIsNull(factoryId, version)
                .orElseThrow(() -> invalid("BLUEPRINT_VERSION_NOT_FOUND",
                        "工厂 " + factoryId + " 不存在已发布蓝图 v" + version));
        if (!Boolean.TRUE.equals(history.getIsPublished())) {
            throw invalid("BLUEPRINT_VERSION_NOT_PUBLISHED", "该蓝图版本尚未发布，不能作为历史快照读取");
        }
        Map<String, Object> snapshot = readMap(history.getSnapshotData());
        if (snapshot == null || snapshot.isEmpty()) {
            throw invalid("BLUEPRINT_VERSION_SNAPSHOT_MISSING", "该发布版本缺少不可变快照，禁止返回空历史数据");
        }
        return VersionDetailView.builder()
                .blueprintId(history.getBlueprintId())
                .factoryId(history.getFactoryId())
                .version(history.getVersion())
                .schemaVersion(history.getSchemaVersion())
                .lifecycleState(history.getLifecycleState())
                .published(true)
                .publishedAt(history.getPublishedAt())
                .publishedBy(history.getPublishedBy())
                .createdActor(actor(history.getCreatedActorType(), history.getCreatedActorId()))
                .publishedActor(actor(history.getPublishedActorType(), history.getPublishedActorId()))
                .createdAt(history.getCreatedAt())
                .snapshot(snapshot)
                .changeSummary(read(history.getChangeSummary(), Object.class))
                .build();
    }

    @Transactional
    public BlueprintResponse validate(String factoryId, String blueprintId,
                                      OperationRequest request, ActorRef actor) {
        requireWriteAuthority(factoryId, actor);
        FactoryTypeBlueprint blueprint = lockCurrent(factoryId, blueprintId);
        ReceiptView replay = replay(blueprint, "VALIDATE", request);
        if (replay != null) {
            return toResponse(blueprint, replay);
        }
        verifyOperation(blueprint, request, Set.of(DRAFT, VALIDATED));

        ApplyAIInitRequest payload = payload(blueprint);
        List<ConversionEvidence> conversionEvidence = conversionEvidence(blueprint);
        List<PlannedChange> changes = planService.preview(factoryId, payload, conversionEvidence);
        CatalogSnapshot catalog = lockedCatalog(blueprint);
        List<CapabilityView> capabilities = capabilityCatalog.derive(catalog, changes, payload);
        List<ValidationIssue> blockers = new ArrayList<>(capabilities.stream()
                .filter(capability -> "BLOCKED".equals(capability.getStatus()))
                .map(capability -> ValidationIssue.builder()
                        .code("MISSING_CAPABILITY_DEPENDENCY")
                        .capabilityId(capability.getId())
                        .message(capability.getLabel() + " 缺少依赖：" + String.join("、", capability.getMissingDependencies()))
                        .recovery("补齐依赖后重新保存草稿并验证")
                        .build())
                .toList());
        planService.blueprintSemanticIssues(
                        factoryId, payload, sourceFact(blueprint, "customerSupplied"), conversionEvidence)
                .stream()
                .map(this::validationIssue)
                .forEach(blockers::add);
        changes.stream()
                .filter(change -> change.getDetail() != null && change.getDetail().contains("未分类"))
                .map(change -> ValidationIssue.builder()
                        .code("MATERIAL_CLASSIFICATION_REQUIRED")
                        .capabilityId("materials")
                        .message("物料「" + change.getKey() + "」没有解析到本草稿或本厂的 L1 分类")
                        .recovery("在本草稿的编码分段中补齐原料/辅料/包材，或为物料明确选择已有 L1")
                        .build())
                .forEach(blockers::add);
        routeGraphs(blueprint).stream()
                .filter(graph -> !graph.validation().valid())
                .flatMap(graph -> graph.validation().issues().stream().map(issue ->
                        ValidationIssue.builder()
                                .code("ROUTE_" + issue.code())
                                .capabilityId("workflow")
                                .message("产品「" + graph.productName() + "」路线：" + issue.message()
                                        + routeIssueNodes(graph, issue))
                                .recovery("在产品路线中补齐可达起点、放行终点和正确连线后，重新保存草稿并验证")
                                .build()))
                .forEach(blockers::add);
        ValidationReport report = ValidationReport.builder()
                .valid(blockers.isEmpty())
                .blockingIssues(blockers)
                .warnings(List.of())
                .businessWriteCount(0)
                .calculatedAt(LocalDateTime.now())
                .build();

        blueprint.setCapabilitySnapshot(write(capabilities));
        blueprint.setValidationReport(write(report));
        blueprint.setSimulationReport(null);
        blueprint.setLifecycleState(report.isValid() ? VALIDATED : DRAFT);
        blueprint.setConfirmedAt(report.isValid() ? LocalDateTime.now() : null);
        blueprint.setBlueprintUpdatedActor(actor);
        blueprint = blueprintRepository.saveAndFlush(blueprint);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", report.isValid());
        result.put("blockingIssueCount", blockers.size());
        result.put("businessWriteCount", 0);
        ReceiptView receipt = receipt(blueprint, "VALIDATE", request.getIdempotencyKey(), actor,
                report.isValid() ? BlueprintApplication.ApplicationStatus.COMPLETED
                        : BlueprintApplication.ApplicationStatus.FAILED,
                result, List.of(), null, 0);
        return toResponse(blueprint, receipt);
    }

    private ValidationIssue validationIssue(BlueprintSemanticIssue issue) {
        return ValidationIssue.builder()
                .code(issue.code())
                .capabilityId(issue.capabilityId())
                .message(issue.message())
                .recovery(issue.recovery())
                .build();
    }

    private String routeIssueNodes(FactoryRouteGraphPrototype.RouteGraph graph,
                                   FactoryRouteGraphPrototype.RouteIssue issue) {
        if (issue.nodeIds().isEmpty()) return "";
        Map<String, String> labels = graph.nodes().stream().collect(Collectors.toMap(
                FactoryRouteGraphPrototype.RouteNode::id,
                FactoryRouteGraphPrototype.RouteNode::label,
                (left, right) -> left,
                LinkedHashMap::new));
        return "（节点：" + issue.nodeIds().stream()
                .map(id -> labels.containsKey(id) ? labels.get(id) + " [" + id + "]" : id)
                .collect(Collectors.joining("、")) + "）";
    }

    @Transactional
    public BlueprintResponse simulate(String factoryId, String blueprintId,
                                      OperationRequest request, ActorRef actor) {
        requireWriteAuthority(factoryId, actor);
        FactoryTypeBlueprint blueprint = lockCurrent(factoryId, blueprintId);
        ReceiptView replay = replay(blueprint, "SIMULATE", request);
        if (replay != null) {
            return toResponse(blueprint, replay);
        }
        verifyOperation(blueprint, request, Set.of(VALIDATED));
        CatalogSnapshot catalog = lockedCatalog(blueprint);

        ApplyAIInitRequest blueprintPayload = payload(blueprint);
        // preview() executes the exact validation and planning path used by apply, without
        // writing business configuration. A successful return is evidence, not a guess.
        List<PlannedChange> changes = planService.preview(
                factoryId, blueprintPayload, conversionEvidence(blueprint));
        List<CapabilityView> capabilities = capabilityCatalog.derive(catalog, changes, blueprintPayload);
        ValidationReport validation = read(blueprint.getValidationReport(), ValidationReport.class);
        List<ValidationIssue> simulationBlockers = new ArrayList<>();
        if (validation == null || !validation.isValid() || validation.getBusinessWriteCount() != 0) {
            simulationBlockers.add(ValidationIssue.builder()
                    .code("SIMULATION_REQUIRES_CURRENT_VALIDATION")
                    .message("模拟前缺少当前版本的零写验证结果")
                    .recovery("重新执行蓝图验证后再模拟")
                    .build());
        }
        capabilities.stream()
                .filter(capability -> "BLOCKED".equals(capability.getStatus()))
                .map(capability -> ValidationIssue.builder()
                        .code("MISSING_CAPABILITY_DEPENDENCY")
                        .capabilityId(capability.getId())
                        .message(capability.getLabel() + " 缺少依赖："
                                + String.join("、", capability.getMissingDependencies()))
                        .recovery("补齐依赖后重新保存、验证并模拟")
                        .build())
                .forEach(simulationBlockers::add);
        changes.stream()
                .filter(change -> "unclassified".equals(capabilityCatalog.capabilityId(catalog, change)))
                .map(change -> ValidationIssue.builder()
                        .code("UNCLASSIFIED_PLANNED_CHANGE")
                        .message("写入计划类别未被能力目录覆盖：" + Objects.toString(change.getGroup(), "未知类别"))
                        .recovery("先为该写入类型补充版本化能力定义，再重新保存蓝图")
                        .build())
                .forEach(simulationBlockers::add);
        boolean safe = simulationBlockers.isEmpty();
        List<String> affectedModules = changes.stream().map(PlannedChange::getGroup)
                .filter(Objects::nonNull).distinct().toList();
        List<DiffSummary> diff = changes.stream()
                .collect(Collectors.groupingBy(PlannedChange::getGroup, LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream()
                .map(entry -> DiffSummary.builder()
                        .group(entry.getKey())
                        .createCount(countAction(entry.getValue(), "新建"))
                        .overwriteCount(countAction(entry.getValue(), "覆盖"))
                        .skipCount(countAction(entry.getValue(), "跳过"))
                        .build())
                .toList();
        Map<String, Object> workflow = new LinkedHashMap<>();
        workflow.put("state", "NOT_PUBLISHED");
        workflow.put("simulationExecuted", false);
        workflow.put("businessWriteCount", 0);
        workflow.put("nextStep", "本次只完成配置应用预检；蓝图发布后仍需在 Workflow 画布单独仿真、核对与发布");
        Map<String, Boolean> checks = new LinkedHashMap<>();
        checks.put("catalogDigestLocked", true);
        checks.put("domainApplyValidationPassed", true);
        checks.put("capabilityDependenciesReady", capabilities.stream()
                .noneMatch(capability -> "BLOCKED".equals(capability.getStatus())));
        checks.put("plannedChangesClassified", changes.stream()
                .noneMatch(change -> "unclassified".equals(capabilityCatalog.capabilityId(catalog, change))));
        checks.put("businessWriteCountZero", true);
        SimulationReport report = SimulationReport.builder()
                .safe(safe)
                .safetyScope("CONFIGURATION_APPLY_PREFLIGHT")
                .checks(checks)
                .blockingIssues(simulationBlockers)
                .limitations(List.of("未执行 Workflow 引擎仿真，不能据此声称 Workflow 可发布或可运行"))
                .affectedModules(affectedModules)
                .changes(changes)
                .diff(diff)
                .workflow(workflow)
                .businessWriteCount(0)
                .calculatedAt(LocalDateTime.now())
                .build();
        blueprint.setCapabilitySnapshot(write(capabilities));
        blueprint.setSimulationReport(write(report));
        blueprint.setBlueprintUpdatedActor(actor);
        blueprint = blueprintRepository.saveAndFlush(blueprint);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("safe", safe);
        result.put("safetyScope", report.getSafetyScope());
        result.put("checks", checks);
        result.put("blockingIssueCount", simulationBlockers.size());
        result.put("affectedModuleCount", affectedModules.size());
        result.put("businessWriteCount", 0);
        ReceiptView receipt = receipt(blueprint, "SIMULATE", request.getIdempotencyKey(), actor,
                safe ? BlueprintApplication.ApplicationStatus.COMPLETED
                        : BlueprintApplication.ApplicationStatus.FAILED,
                result, modulePreview(catalog, changes), null, 0);
        return toResponse(blueprint, receipt);
    }

    @Transactional
    public BlueprintResponse publish(String factoryId, String blueprintId,
                                      OperationRequest request, ActorRef actor) {
        requireWriteAuthority(factoryId, actor);
        FactoryTypeBlueprint blueprint = lockCurrent(factoryId, blueprintId);
        ReceiptView replay = replay(blueprint, "PUBLISH", request);
        if (replay != null) {
            return toResponse(blueprint, replay);
        }
        verifyOperation(blueprint, request, Set.of(VALIDATED));
        lockedCatalog(blueprint);
        SimulationReport simulation = read(blueprint.getSimulationReport(), SimulationReport.class);
        if (simulation == null || !simulation.isSafe() || simulation.getBusinessWriteCount() != 0
                || !"CONFIGURATION_APPLY_PREFLIGHT".equals(simulation.getSafetyScope())
                || simulation.getChecks() == null
                || simulation.getChecks().values().stream().anyMatch(Boolean.FALSE::equals)) {
            throw state("发布前必须先完成当前版本的零业务写入配置预检");
        }

        BlueprintVersionHistory history = BlueprintVersionHistory.builder()
                .blueprintId(blueprint.getId())
                .factoryId(factoryId)
                .version(blueprint.getVersion())
                .schemaVersion(blueprint.getSchemaVersion())
                .lifecycleState(PUBLISHED)
                .idempotencyKey(request.getIdempotencyKey().trim())
                .changeType("PUBLISH")
                .changeDescription("发布经验证和模拟的工厂建厂蓝图")
                .snapshotData(write(publishedSnapshot(blueprint)))
                .changeSummary(write(simulation.getDiff()))
                .isPublished(true)
                .publishedAt(LocalDateTime.now())
                .publishedActorType(actor.type())
                .publishedActorId(actor.id())
                .publishedBy(actor.factoryUserId())
                .createdActorType(actor.type())
                .createdActorId(actor.id())
                .createdBy(actor.factoryUserId())
                .build();
        historyRepository.save(history);

        blueprint.setLifecycleState(PUBLISHED);
        blueprint.setPublishedAt(history.getPublishedAt());
        blueprint.setPublishedActor(actor);
        blueprint.setBlueprintUpdatedActor(actor);
        blueprint = blueprintRepository.saveAndFlush(blueprint);

        FactoryBlueprintBinding binding = bindingRepository.findByFactoryIdAndDeletedAtIsNull(factoryId)
                .orElseGet(FactoryBlueprintBinding::new);
        if (binding.getBlueprintId() != null && !blueprint.getId().equals(binding.getBlueprintId())) {
            binding.setAppliedVersion(null);
            binding.setLastAppliedAt(null);
        }
        binding.setFactoryId(factoryId);
        binding.setBlueprintId(blueprint.getId());
        binding.setPublishedVersion(blueprint.getVersion());
        binding.setLatestVersion(blueprint.getVersion());
        binding.setPendingVersion(blueprint.getVersion());
        binding.setNotificationStatus("PENDING");
        bindingRepository.save(binding);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("publishedVersion", blueprint.getVersion());
        result.put("snapshotImmutable", true);
        result.put("configurationApplied", false);
        result.put("workflowPublished", false);
        ReceiptView receipt = receipt(blueprint, "PUBLISH", request.getIdempotencyKey(), actor,
                BlueprintApplication.ApplicationStatus.COMPLETED,
                result, List.of(), null, 0);
        return toResponse(blueprint, receipt);
    }

    @Transactional
    public BlueprintResponse apply(String factoryId, String blueprintId,
                                   OperationRequest request, ActorRef actor) {
        requireWriteAuthority(factoryId, actor);
        FactoryTypeBlueprint blueprint = lockCurrent(factoryId, blueprintId);
        ReceiptView replay = replay(blueprint, "APPLY", request);
        if (replay != null) {
            return toResponse(blueprint, replay);
        }
        verifyOperation(blueprint, request, Set.of(PUBLISHED));
        CatalogSnapshot catalog = lockedCatalog(blueprint);

        ApplyAIInitResult applied = applyService.applyAsActor(factoryId, payload(blueprint), actor);
        Map<String, Object> appliedMap = objectMapper.convertValue(applied, new TypeReference<>() { });
        int writeCount = countWritten(appliedMap);
        boolean partialFailure = !applied.isSuccess()
                || !applied.getFailedPackagingConversions().isEmpty()
                || !applied.getFailedModuleConfigs().isEmpty();
        List<Map<String, Object>> modules = appliedModuleReceipts(catalog, applied);
        Map<String, Object> recovery = partialFailure ? recoveryPlan(blueprint, modules) : null;

        if (!partialFailure) {
            blueprint.setLifecycleState(APPLIED);
            blueprint.setBlueprintUpdatedActor(actor);
            blueprint = blueprintRepository.saveAndFlush(blueprint);
            int appliedVersion = blueprint.getVersion();
            bindingRepository.findByFactoryIdAndDeletedAtIsNull(factoryId).ifPresent(binding -> {
                binding.setAppliedVersion(appliedVersion);
                binding.setPendingVersion(null);
                binding.setLastAppliedAt(LocalDateTime.now());
                binding.setNotificationStatus("NONE");
                bindingRepository.save(binding);
            });
        }

        appliedMap.put("partialFailure", partialFailure);
        appliedMap.put("workflowPublished", false);
        ReceiptView receipt = receipt(blueprint, "APPLY", request.getIdempotencyKey(), actor,
                partialFailure ? BlueprintApplication.ApplicationStatus.PARTIAL_FAILED
                        : BlueprintApplication.ApplicationStatus.COMPLETED,
                appliedMap, modules, recovery, writeCount);
        return toResponse(blueprint, receipt);
    }

    @Transactional
    public BlueprintResponse accept(String factoryId, String blueprintId,
                                    CapabilityAcceptanceRequest request, ActorRef actor) {
        requireWriteAuthority(factoryId, actor);
        FactoryTypeBlueprint blueprint = lockCurrent(factoryId, blueprintId);
        List<Map<String, Object>> normalized = normalizeAcceptanceDecisions(request);
        String requestDigest = digest(normalized);
        ReceiptView replay = replay(blueprint, "ACCEPT", request);
        if (replay != null) {
            String replayDigest = replay.getResult() == null
                    ? null : Objects.toString(replay.getResult().get("requestDigest"), null);
            if (!Objects.equals(requestDigest, replayDigest)) {
                throw new BusinessException(409, "幂等键已用于不同的能力验收决定")
                        .withCode("BLUEPRINT_IDEMPOTENCY_PAYLOAD_MISMATCH")
                        .withHint("刷新验收状态后使用新的操作键")
                        .withSeverity("BLOCKING");
            }
            return toResponse(blueprint, replay);
        }
        verifyOperation(blueprint, request, Set.of(APPLIED));
        lockedCatalog(blueprint);
        if (actor == null) {
            throw invalid("BLUEPRINT_ACCEPTANCE_OPERATOR_REQUIRED", "能力验收必须记录当前登录操作者");
        }

        List<CapabilityView> capabilities = read(
                blueprint.getCapabilitySnapshot(), new TypeReference<List<CapabilityView>>() { });
        Map<String, CapabilityView> capabilityById = capabilities == null ? Map.of()
                : capabilities.stream().collect(Collectors.toMap(CapabilityView::getId, item -> item));
        ReceiptView appliedReceipt = applications(blueprint).stream()
                .filter(application -> "APPLY".equals(application.getOperationType()))
                .filter(application -> application.getStatus() == BlueprintApplication.ApplicationStatus.COMPLETED)
                .findFirst()
                .map(this::toReceipt)
                .orElseThrow(() -> state("当前版本没有完整成功的配置应用回执，不能提交人工验收"));
        LocalDateTime reviewedAt = LocalDateTime.now();
        List<Map<String, Object>> decisions = new ArrayList<>();
        for (Map<String, Object> item : normalized) {
            String capabilityId = Objects.toString(item.get("capabilityId"), "");
            CapabilityView capability = capabilityById.get(capabilityId);
            if (capability == null) {
                throw invalid("BLUEPRINT_ACCEPTANCE_CAPABILITY_UNKNOWN",
                        "蓝图中不存在能力 " + capabilityId);
            }
            if (!capability.isIncluded()) {
                throw invalid("BLUEPRINT_ACCEPTANCE_CAPABILITY_NOT_INCLUDED",
                        capability.getLabel() + " 未纳入本次配置计划，不能伪造验收");
            }
            if (!"READY".equals(capability.getStatus())) {
                throw invalid("BLUEPRINT_ACCEPTANCE_CAPABILITY_NOT_READY",
                        capability.getLabel() + " 的依赖状态未就绪，不能验收");
            }
            List<Map<String, Object>> configurationReceipts = configurationReceipts(
                    blueprint, appliedReceipt, capabilityId);
            if (configurationReceipts.isEmpty()
                    || configurationReceipts.stream().anyMatch(module ->
                            "FAILED".equals(module.get("status"))
                                    || "REVIEW_REQUIRED".equals(module.get("status")))) {
                throw invalid("BLUEPRINT_ACCEPTANCE_CONFIGURATION_NOT_READY",
                        capability.getLabel() + " 缺少完整成功的配置应用回执，不能验收");
            }
            Map<String, Object> decision = new LinkedHashMap<>(item);
            decision.put("capabilityLabel", capability.getLabel());
            decision.put("status", item.get("decision"));
            decision.put("sourceApplyReceiptId", appliedReceipt.getId());
            decision.put("reviewedBy", actor.id());
            decision.put("reviewedActorType", actor.type().name());
            decision.put("reviewedAt", reviewedAt);
            decisions.add(decision);
        }

        // An acceptance receipt is metadata, but it is still a concurrent decision on
        // the current blueprint. Touching the audited row advances @Version so a second
        // reviewer cannot silently submit against the same stale screen.
        blueprint.setBlueprintUpdatedActor(actor);
        blueprint.setUpdatedAt(reviewedAt);
        blueprint = blueprintRepository.saveAndFlush(blueprint);

        long acceptedCount = decisions.stream().filter(item -> "ACCEPTED".equals(item.get("decision"))).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestDigest", requestDigest);
        result.put("decisionCount", decisions.size());
        result.put("acceptedCount", acceptedCount);
        result.put("rejectedCount", decisions.size() - acceptedCount);
        result.put("configurationWriteCount", 0);
        result.put("workflowPublished", false);
        ReceiptView receipt = receipt(blueprint, "ACCEPT", request.getIdempotencyKey(), actor,
                BlueprintApplication.ApplicationStatus.COMPLETED,
                result, decisions, null, 0);
        return toResponse(blueprint, receipt);
    }

    @Transactional
    public BlueprintResponse recoveryPlan(String factoryId, String blueprintId,
                                          OperationRequest request, ActorRef actor) {
        requireWriteAuthority(factoryId, actor);
        FactoryTypeBlueprint blueprint = lockCurrent(factoryId, blueprintId);
        ReceiptView replay = replay(blueprint, "RECOVERY_PLAN", request);
        if (replay != null) {
            return toResponse(blueprint, replay);
        }
        verifyExpectedVersion(blueprint, request == null ? null : request.getExpectedRowVersion());
        BlueprintApplication failed = applicationRepository
                .findByFactoryIdAndBlueprintIdAndBlueprintVersionAndDeletedAtIsNullOrderByAppliedAtDesc(
                        factoryId, blueprintId, blueprint.getVersion())
                .stream()
                .filter(application -> application.getStatus() == BlueprintApplication.ApplicationStatus.PARTIAL_FAILED
                        || application.getStatus() == BlueprintApplication.ApplicationStatus.FAILED)
                .findFirst()
                .orElseThrow(() -> state("当前版本没有失败或部分失败回执，不需要恢复计划"));
        Map<String, Object> recovery = readMap(failed.getRecoveryPlan());
        if (recovery == null) {
            recovery = recoveryPlan(blueprint);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceReceiptId", failed.getId());
        result.put("automaticRollbackExecuted", false);
        result.put("lifecycleState", blueprint.getLifecycleState());
        ReceiptView receipt = receipt(blueprint, "RECOVERY_PLAN", request.getIdempotencyKey(), actor,
                BlueprintApplication.ApplicationStatus.COMPLETED,
                result, List.of(), recovery, 0);
        return toResponse(blueprint, receipt);
    }

    private FactoryTypeBlueprint lockCurrent(String factoryId, String blueprintId) {
        FactoryTypeBlueprint blueprint = blueprintRepository.lockCurrentByFactoryId(factoryId)
                .orElseThrow(() -> invalid("BLUEPRINT_NOT_FOUND", "当前工厂还没有权威蓝图"));
        if (!blueprint.getId().equals(blueprintId)) {
            throw invalid("BLUEPRINT_SCOPE_MISMATCH", "蓝图不属于当前工厂或已不是当前版本");
        }
        return blueprint;
    }

    private FactoryTypeBlueprint requireBlueprint(String factoryId, String blueprintId) {
        return blueprintRepository.findByFactoryIdAndIdAndDeletedAtIsNull(factoryId, blueprintId)
                .orElseThrow(() -> invalid("BLUEPRINT_SCOPE_MISMATCH", "蓝图不属于当前工厂"));
    }

    private void verifyOperation(FactoryTypeBlueprint blueprint, OperationRequest request,
                                 Set<String> allowedStates) {
        requireText(request == null ? null : request.getIdempotencyKey(), "幂等键");
        verifyExpectedVersion(blueprint, request.getExpectedRowVersion());
        if (!allowedStates.contains(blueprint.getLifecycleState())) {
            throw state("当前状态 " + blueprint.getLifecycleState() + " 不允许此操作");
        }
    }

    private void verifyExpectedVersion(FactoryTypeBlueprint blueprint, Long expected) {
        if (expected == null) {
            throw invalid("BLUEPRINT_VERSION_REQUIRED", "缺少当前行版本，请刷新后重试");
        }
        if (!Objects.equals(expected, blueprint.getRowVersion())) {
            throw new BusinessException(409, "蓝图已被其他操作更新，请刷新后核对差异")
                    .withCode("OPTIMISTIC_LOCK_CONFLICT")
                    .withHint("刷新当前蓝图后重试")
                    .withSeverity("BLOCKING");
        }
    }

    private ReceiptView replay(FactoryTypeBlueprint blueprint, String operationType, OperationRequest request) {
        requireText(request == null ? null : request.getIdempotencyKey(), "幂等键");
        Optional<BlueprintApplication> existing = applicationRepository
                .findByFactoryIdAndOperationTypeAndIdempotencyKeyAndDeletedAtIsNull(
                        blueprint.getFactoryId(), operationType, request.getIdempotencyKey().trim());
        if (existing.isEmpty()) {
            return null;
        }
        BlueprintApplication receipt = existing.get();
        if (!Objects.equals(blueprint.getId(), receipt.getBlueprintId())
                || !Objects.equals(blueprint.getVersion(), receipt.getBlueprintVersion())) {
            throw new BusinessException(409, "幂等键已用于其他蓝图版本，请刷新后使用新的操作键")
                    .withCode("BLUEPRINT_IDEMPOTENCY_SCOPE_MISMATCH")
                    .withHint("刷新当前蓝图后重新发起操作")
                    .withSeverity("BLOCKING");
        }
        return toReceipt(receipt);
    }

    private List<BlueprintApplication> applications(FactoryTypeBlueprint blueprint) {
        return applicationRepository
                .findByFactoryIdAndBlueprintIdAndBlueprintVersionAndDeletedAtIsNullOrderByAppliedAtDesc(
                        blueprint.getFactoryId(), blueprint.getId(), blueprint.getVersion());
    }

    private ReceiptView receipt(FactoryTypeBlueprint blueprint, String operationType,
                                String idempotencyKey, ActorRef actor,
                                BlueprintApplication.ApplicationStatus status,
                                Map<String, Object> result,
                                List<Map<String, Object>> modules,
                                Map<String, Object> recovery,
                                int businessWriteCount) {
        BlueprintApplication application = new BlueprintApplication();
        application.setBlueprintId(blueprint.getId());
        application.setFactoryId(blueprint.getFactoryId());
        application.setBlueprintVersion(blueprint.getVersion());
        application.setOperationType(operationType);
        application.setIdempotencyKey(idempotencyKey.trim());
        application.setAppliedActor(actor);
        application.setAppliedAt(LocalDateTime.now());
        application.setStatus(status);
        application.setBusinessWriteCount(businessWriteCount);
        application.setResultSummary(operationType + " " + status.name());
        application.setOperationResult(write(result));
        application.setModuleResults(write(modules));
        application.setRecoveryPlan(recovery == null ? null : write(recovery));
        return toReceipt(applicationRepository.saveAndFlush(application));
    }

    private ReceiptView toReceipt(BlueprintApplication application) {
        Map<String, Object> publicResult = readMap(application.getOperationResult());
        if ("SAVE_DRAFT".equals(application.getOperationType()) && publicResult != null) {
            publicResult = new LinkedHashMap<>(publicResult);
            publicResult.remove("response");
        }
        return ReceiptView.builder()
                .id(application.getId())
                .operationType(application.getOperationType())
                .status(application.getStatus() == null ? null : application.getStatus().name())
                .idempotencyKey(application.getIdempotencyKey())
                .blueprintVersion(application.getBlueprintVersion())
                .operatedBy(application.getAppliedBy())
                .operatedActor(actor(application.getAppliedActorType(), application.getAppliedActorId()))
                .businessWriteCount(application.getBusinessWriteCount() == null ? 0 : application.getBusinessWriteCount())
                .result(publicResult)
                .moduleResults(read(application.getModuleResults(), new TypeReference<List<Map<String, Object>>>() { }))
                .recoveryPlan(readMap(application.getRecoveryPlan()))
                .createdAt(application.getAppliedAt())
                .build();
    }

    private BlueprintResponse toResponse(FactoryTypeBlueprint blueprint, ReceiptView receipt) {
        Map<String, Object> source = readMap(blueprint.getSourceSnapshot());
        List<CapabilityView> capabilities = read(
                blueprint.getCapabilitySnapshot(), new TypeReference<List<CapabilityView>>() { });
        ValidationReport validation = read(blueprint.getValidationReport(), ValidationReport.class);
        List<ReceiptView> receipts = applications(blueprint).stream().map(this::toReceipt).toList();
        List<ReceiptView> responseReceipts = new ArrayList<>(receipts);
        if (receipt != null && responseReceipts.stream().noneMatch(item -> Objects.equals(item.getId(), receipt.getId()))) {
            responseReceipts.addFirst(receipt);
        }
        ReceiptView latest = receipt != null ? receipt : responseReceipts.stream().findFirst().orElse(null);
        ReceiptView latestApply = responseReceipts.stream()
                .filter(item -> "APPLY".equals(item.getOperationType()))
                .findFirst().orElse(null);
        List<Map<String, Object>> acceptanceReceipts = responseReceipts.stream()
                .filter(item -> "ACCEPT".equals(item.getOperationType()))
                .filter(item -> item.getModuleResults() != null)
                .flatMap(item -> item.getModuleResults().stream())
                .toList();
        List<CapabilityView> enriched = enrichCapabilities(
                blueprint, capabilities, validation, latestApply, acceptanceReceipts);
        return BlueprintResponse.builder()
                .id(blueprint.getId())
                .factoryId(blueprint.getFactoryId())
                .factoryName(blueprint.getFactoryName())
                .schemaVersion(blueprint.getSchemaVersion())
                .capabilityCatalogVersion(blueprint.getCapabilityCatalogVersion())
                .capabilityCatalogDigest(blueprint.getCapabilityCatalogDigest())
                .version(blueprint.getVersion())
                .rowVersion(blueprint.getRowVersion())
                .lifecycleState(blueprint.getLifecycleState())
                .scopeType(blueprint.getScopeType())
                .createdBy(blueprint.getBlueprintCreatedBy())
                .updatedBy(blueprint.getBlueprintUpdatedBy())
                .createdActor(actor(blueprint.getBlueprintCreatedActorType(), blueprint.getBlueprintCreatedActorId()))
                .updatedActor(actor(blueprint.getBlueprintUpdatedActorType(), blueprint.getBlueprintUpdatedActorId()))
                .publishedActor(actor(blueprint.getPublishedActorType(), blueprint.getPublishedActorId()))
                .source(CanonicalFactoryBlueprintModels.SOURCE)
                .sessionId(source == null ? null : Objects.toString(source.get("sessionId"), null))
                .customerSupplied(source == null ? null : Objects.toString(source.get("customerSupplied"), null))
                .conversionEvidence(conversionEvidence(blueprint))
                .sourcePayload(payload(blueprint))
                .capabilities(enriched)
                .plannedChanges(read(blueprint.getPlanSnapshot(), new TypeReference<List<PlannedChange>>() { }))
                .workflowLanes(workflowLanes(blueprint))
                .routeGraphs(routeGraphs(blueprint))
                .configurationState(APPLIED.equals(blueprint.getLifecycleState()) ? "APPLIED" : "NOT_APPLIED")
                .acceptanceState(acceptanceState(enriched))
                .workflowState("NOT_PUBLISHED")
                .validation(validation)
                .simulation(read(blueprint.getSimulationReport(), SimulationReport.class))
                .latestReceipt(latest)
                .createdAt(blueprint.getCreatedAt())
                .updatedAt(blueprint.getUpdatedAt())
                .publishedAt(blueprint.getPublishedAt())
                .build();
    }

    private ApplyAIInitRequest payload(FactoryTypeBlueprint blueprint) {
        Map<String, Object> source = readMap(blueprint.getSourceSnapshot());
        if (source == null || source.get("payload") == null) {
            throw invalid("BLUEPRINT_SOURCE_MISSING", "蓝图来源快照缺失，禁止模拟或应用");
        }
        return objectMapper.convertValue(source.get("payload"), ApplyAIInitRequest.class);
    }

    private String saveRequestDigest(SaveDraftRequest request, ActorRef actor) {
        Map<String, Object> source = sourceEnvelope(request);
        source.remove("capturedAt");
        source.put("factoryName", trimToNull(request.getFactoryName()));
        source.put("expectedRowVersion", request.getExpectedRowVersion());
        source.put("actor", actor);
        return digest(source);
    }

    private Map<String, Object> sourceEnvelope(SaveDraftRequest request) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("source", CanonicalFactoryBlueprintModels.SOURCE);
        source.put("schemaVersion", CanonicalFactoryBlueprintModels.SCHEMA_VERSION);
        source.put("sessionId", trimToNull(request.getSessionId()));
        source.put("questionnaireSessionRef", trimToNull(request.getSessionId()));
        source.put("customerSupplied", trimToNull(request.getCustomerSupplied()));
        source.put("conversionEvidence", request.getConversionEvidence());
        source.put("structuredComponentResult", "USER_REVIEWED_SERVER_PAYLOAD");
        source.put("hiddenReasoningStored", false);
        source.put("payload", request.getPayload());
        source.put("capturedAt", LocalDateTime.now());
        return source;
    }

    private String sourceFact(FactoryTypeBlueprint blueprint, String key) {
        Map<String, Object> source = readMap(blueprint.getSourceSnapshot());
        return source == null ? null : trimToNull(Objects.toString(source.get(key), null));
    }

    private List<ConversionEvidence> conversionEvidence(FactoryTypeBlueprint blueprint) {
        Map<String, Object> source = readMap(blueprint.getSourceSnapshot());
        if (source == null || source.get("conversionEvidence") == null) {
            return List.of();
        }
        return objectMapper.convertValue(
                source.get("conversionEvidence"), new TypeReference<List<ConversionEvidence>>() { });
    }

    private Map<String, Object> publishedSnapshot(FactoryTypeBlueprint blueprint) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("blueprintId", blueprint.getId());
        snapshot.put("factoryId", blueprint.getFactoryId());
        snapshot.put("version", blueprint.getVersion());
        snapshot.put("schemaVersion", blueprint.getSchemaVersion());
        snapshot.put("capabilityCatalogVersion", blueprint.getCapabilityCatalogVersion());
        snapshot.put("capabilityCatalogDigest", blueprint.getCapabilityCatalogDigest());
        snapshot.put("source", readMap(blueprint.getSourceSnapshot()));
        snapshot.put("capabilities", read(blueprint.getCapabilitySnapshot(), new TypeReference<List<CapabilityView>>() { }));
        snapshot.put("plannedChanges", read(blueprint.getPlanSnapshot(), new TypeReference<List<PlannedChange>>() { }));
        snapshot.put("validation", read(blueprint.getValidationReport(), ValidationReport.class));
        snapshot.put("simulation", read(blueprint.getSimulationReport(), SimulationReport.class));
        return snapshot;
    }

    private List<Map<String, Object>> workflowLanes(FactoryTypeBlueprint blueprint) {
        ApplyAIInitRequest request = payload(blueprint);
        if (request.getSuggestedData() == null || request.getSuggestedData().getProductProcesses() == null) {
            return List.of();
        }
        Map<String, String> productNames = request.getSuggestedData().getProductTypes() == null
                ? Map.of()
                : request.getSuggestedData().getProductTypes().stream()
                        .filter(item -> item.getCode() != null)
                        .collect(Collectors.toMap(
                                item -> item.getCode(),
                                item -> item.getName() == null ? item.getCode() : item.getName(),
                                (left, right) -> left,
                                LinkedHashMap::new));
        return request.getSuggestedData().getProductProcesses().stream().map(item -> {
            Map<String, Object> lane = new LinkedHashMap<>();
            lane.put("productCode", item.getProductCode());
            lane.put("productName", productNames.getOrDefault(item.getProductCode(), item.getProductCode()));
            lane.put("processNames", item.getProcessNames() == null ? List.of() : item.getProcessNames());
            return lane;
        }).toList();
    }

    private List<FactoryRouteGraphPrototype.RouteGraph> routeGraphs(FactoryTypeBlueprint blueprint) {
        ApplyAIInitRequest request = payload(blueprint);
        if (request.getSuggestedData() == null || request.getSuggestedData().getProductProcesses() == null) {
            return List.of();
        }
        Map<String, String> productNames = request.getSuggestedData().getProductTypes() == null
                ? Map.of()
                : request.getSuggestedData().getProductTypes().stream()
                        .filter(item -> item.getCode() != null)
                        .collect(Collectors.toMap(
                                item -> item.getCode(),
                                item -> item.getName() == null ? item.getCode() : item.getName(),
                                (left, right) -> left,
                                LinkedHashMap::new));
        return request.getSuggestedData().getProductProcesses().stream()
                .map(item -> {
                    String productName = productNames.getOrDefault(item.getProductCode(), item.getProductCode());
                    if (item.getRouteNodes() == null && item.getRouteEdges() == null) {
                        return FactoryRouteGraphPrototype.linear(
                                item.getProductCode(), productName, item.getProcessNames());
                    }
                    List<FactoryRouteGraphPrototype.RouteNode> nodes = item.getRouteNodes() == null
                            ? List.of()
                            : item.getRouteNodes().stream()
                                    .map(node -> FactoryRouteGraphPrototype.node(node.getId(), node.getLabel()))
                                    .toList();
                    List<FactoryRouteGraphPrototype.RouteEdge> edges = item.getRouteEdges() == null
                            ? List.of()
                            : item.getRouteEdges().stream()
                                    .map(edge -> FactoryRouteGraphPrototype.edge(
                                            edge.getFromNodeId(), edge.getToNodeId(), edge.getType()))
                                    .toList();
                    return FactoryRouteGraphPrototype.graph(item.getProductCode(), productName, nodes, edges);
                })
                .toList();
    }

    private List<Map<String, Object>> modulePreview(CatalogSnapshot catalog, List<PlannedChange> changes) {
        return changes.stream().map(change -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("capabilityId", capabilityCatalog.capabilityId(catalog, change));
            item.put("module", change.getGroup());
            item.put("key", change.getKey());
            item.put("action", change.getAction());
            item.put("status", "PLANNED");
            return item;
        }).toList();
    }

    private List<Map<String, Object>> appliedModuleReceipts(CatalogSnapshot catalog,
                                                             ApplyAIInitResult applied) {
        List<PlannedChange> changes = applied.getPlanned() == null ? List.of() : applied.getPlanned();
        Map<String, List<String>> failures = new LinkedHashMap<>();
        addFailureDetails(failures, "modules", applied.getFailedModuleConfigs());
        addFailureDetails(failures, "packaging", applied.getFailedPackagingConversions());

        List<Map<String, Object>> receipts = new ArrayList<>();
        for (PlannedChange change : changes) {
            String capabilityId = capabilityCatalog.capabilityId(catalog, change);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("capabilityId", capabilityId);
            item.put("module", change.getGroup());
            item.put("key", change.getKey());
            item.put("action", change.getAction());
            if ("跳过".equals(change.getAction())) {
                item.put("status", "SKIPPED");
            } else if (failures.containsKey(capabilityId)) {
                item.put("status", "FAILED");
                item.put("failureDetails", failures.get(capabilityId));
            } else {
                item.put("status", applied.isSuccess() ? "COMPLETED" : "REVIEW_REQUIRED");
            }
            receipts.add(item);
        }

        Set<String> representedCapabilities = receipts.stream()
                .map(item -> Objects.toString(item.get("capabilityId"), ""))
                .collect(Collectors.toSet());
        failures.forEach((capabilityId, details) -> {
            if (!representedCapabilities.contains(capabilityId)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("capabilityId", capabilityId);
                item.put("module", "modules".equals(capabilityId) ? "模块配置" : "包装换算");
                item.put("key", capabilityId);
                item.put("action", "写入");
                item.put("status", "FAILED");
                item.put("failureDetails", details);
                receipts.add(item);
            }
        });
        return receipts;
    }

    private void addFailureDetails(Map<String, List<String>> failures,
                                   String capabilityId,
                                   List<String> details) {
        if (details != null && !details.isEmpty()) {
            failures.put(capabilityId, List.copyOf(details));
        }
    }

    private List<CapabilityView> enrichCapabilities(FactoryTypeBlueprint blueprint,
                                                    List<CapabilityView> capabilities,
                                                    ValidationReport validation,
                                                    ReceiptView applyReceipt,
                                                    List<Map<String, Object>> acceptanceReceipts) {
        if (capabilities == null) {
            return List.of();
        }
        List<ValidationIssue> issues = validation == null || validation.getBlockingIssues() == null
                ? List.of() : validation.getBlockingIssues();
        boolean blueprintPublished = PUBLISHED.equals(blueprint.getLifecycleState())
                || APPLIED.equals(blueprint.getLifecycleState())
                || SUPERSEDED.equals(blueprint.getLifecycleState());
        boolean configurationApplied = APPLIED.equals(blueprint.getLifecycleState());
        for (CapabilityView capability : capabilities) {
            if (capability.getConfigurationRefs() == null) {
                capability.setConfigurationRefs(List.of());
            }
            if (capability.getConfigurationTypes() == null) {
                capability.setConfigurationTypes(List.of());
            }
            if (capability.getModuleSchemaCodes() == null) {
                capability.setModuleSchemaCodes(List.of());
            }
            capability.setSource(CanonicalFactoryBlueprintModels.SOURCE);
            capability.setSourceUpdatedAt(blueprint.getUpdatedAt());
            capability.setConfirmationState(blueprint.getConfirmedAt() == null ? "PENDING" : "CONFIRMED");
            capability.setBlueprintPublishState(blueprintPublished ? "PUBLISHED" : "NOT_PUBLISHED");
            capability.setConfigurationApplyState(configurationApplied ? "APPLIED" : "NOT_APPLIED");
            capability.setWorkflowPublishState("workflow".equals(capability.getId())
                    ? "NOT_PUBLISHED" : "NOT_APPLICABLE");
            capability.setValidationIssues(issues.stream()
                    .filter(issue -> Objects.equals(capability.getId(), issue.getCapabilityId()))
                    .toList());
            List<Map<String, Object>> configurationReceipts = configurationReceipts(
                    blueprint, applyReceipt, capability.getId());
            List<Map<String, Object>> reviews = acceptanceReceipts.stream()
                    .filter(review -> Objects.equals(capability.getId(), review.get("capabilityId")))
                    .toList();
            boolean acceptanceEligible = configurationApplied && capability.isIncluded()
                    && !configurationReceipts.isEmpty()
                    && configurationReceipts.stream().noneMatch(module ->
                            "FAILED".equals(module.get("status"))
                                    || "REVIEW_REQUIRED".equals(module.get("status")));
            String acceptance = !capability.isIncluded() ? "NOT_APPLICABLE"
                    : !acceptanceEligible ? "NOT_READY"
                    : reviews.isEmpty() ? "PENDING"
                    : Objects.toString(reviews.getFirst().get("decision"), "PENDING");
            capability.setConfigurationReceipts(configurationReceipts);
            capability.setAcceptanceReceipts(reviews);
            capability.setAcceptanceEligible(acceptanceEligible);
            capability.setAcceptanceState(acceptance);
            if ("ACCEPTED".equals(acceptance)) {
                capability.setNextStep("配置验收完成；Workflow 仍需在画布单独仿真、核对和发布");
            } else if ("REJECTED".equals(acceptance)) {
                capability.setNextStep("按验收说明修正来源配置并创建新的 Blueprint 版本");
            } else if ("PENDING".equals(acceptance)) {
                capability.setNextStep("完成逐屏复核后提交人工验收决定");
            }
        }
        return capabilities;
    }

    private List<Map<String, Object>> configurationReceipts(FactoryTypeBlueprint blueprint,
                                                             ReceiptView applyReceipt,
                                                             String capabilityId) {
        List<Map<String, Object>> moduleReceipts = applyReceipt == null || applyReceipt.getModuleResults() == null
                ? List.of() : applyReceipt.getModuleResults();
        List<Map<String, Object>> matched = moduleReceipts.stream()
                .filter(module -> Objects.equals(capabilityId, module.get("capabilityId")))
                .toList();
        if (!matched.isEmpty() || !"factory".equals(capabilityId)
                || applyReceipt == null || !"COMPLETED".equals(applyReceipt.getStatus())) {
            return matched;
        }
        Map<String, Object> factoryScope = new LinkedHashMap<>();
        factoryScope.put("capabilityId", "factory");
        factoryScope.put("module", "工厂作用域");
        factoryScope.put("key", blueprint.getFactoryId());
        factoryScope.put("action", "作用域确认");
        factoryScope.put("status", "COMPLETED");
        factoryScope.put("sourceApplyReceiptId", applyReceipt.getId());
        factoryScope.put("evidenceType", "DERIVED_FROM_SUCCESSFUL_FACTORY_SCOPED_APPLY");
        return List.of(factoryScope);
    }

    private String acceptanceState(List<CapabilityView> capabilities) {
        List<CapabilityView> included = capabilities.stream()
                .filter(CapabilityView::isIncluded)
                .toList();
        if (included.isEmpty()) {
            return "NOT_READY";
        }
        if (included.stream().anyMatch(capability -> "REJECTED".equals(capability.getAcceptanceState()))) {
            return "REJECTED";
        }
        if (included.stream().anyMatch(capability -> "NOT_READY".equals(capability.getAcceptanceState()))) {
            return "NOT_READY";
        }
        long accepted = included.stream()
                .filter(capability -> "ACCEPTED".equals(capability.getAcceptanceState()))
                .count();
        if (accepted == included.size()) {
            return "ACCEPTED";
        }
        return accepted == 0 ? "PENDING" : "PARTIAL";
    }

    private CatalogSnapshot lockedCatalog(FactoryTypeBlueprint blueprint) {
        CatalogSnapshot snapshot = capabilityCatalog.load(blueprint.getCapabilityCatalogVersion());
        capabilityCatalog.verifyLockedCatalog(new FactoryTypeCatalogRef(
                blueprint.getCapabilityCatalogVersion(), blueprint.getCapabilityCatalogDigest()), snapshot);
        return snapshot;
    }

    private Map<String, Object> recoveryPlan(FactoryTypeBlueprint blueprint) {
        return recoveryPlan(blueprint, List.of());
    }

    private Map<String, Object> recoveryPlan(FactoryTypeBlueprint blueprint,
                                             List<Map<String, Object>> moduleResults) {
        Map<String, Object> recovery = new LinkedHashMap<>();
        recovery.put("automaticRollbackAvailable", false);
        recovery.put("automaticRollbackExecuted", false);
        recovery.put("preservedPublishedVersion", blueprint.getVersion());
        recovery.put("lifecycleState", blueprint.getLifecycleState());
        recovery.put("failedCapabilities", capabilitiesWithStatus(moduleResults, "FAILED"));
        recovery.put("manualReviewCapabilities", capabilitiesWithStatus(moduleResults, "REVIEW_REQUIRED"));
        recovery.put("completedCapabilities", capabilitiesWithStatus(moduleResults, "COMPLETED"));
        recovery.put("skippedCapabilities", capabilitiesWithStatus(moduleResults, "SKIPPED"));
        recovery.put("steps", List.of(
                "按模块回执核对已完成与需人工复核的项目",
                "修正来源配置后创建新的蓝图版本，不改写已发布快照",
                "重新验证和零写模拟后，再由人工确认应用"));
        recovery.put("reason", "现有建厂应用跨多个业务模块，系统不会伪装成可自动回滚");
        return recovery;
    }

    private List<String> capabilitiesWithStatus(List<Map<String, Object>> moduleResults, String status) {
        return moduleResults.stream()
                .filter(item -> status.equals(item.get("status")))
                .map(item -> Objects.toString(item.get("capabilityId"), "unclassified"))
                .distinct()
                .toList();
    }

    private List<Map<String, Object>> normalizeAcceptanceDecisions(CapabilityAcceptanceRequest request) {
        requireText(request == null ? null : request.getIdempotencyKey(), "幂等键");
        if (request.getDecisions() == null || request.getDecisions().isEmpty()) {
            throw invalid("BLUEPRINT_ACCEPTANCE_DECISIONS_REQUIRED", "至少选择一项能力提交验收决定");
        }
        if (request.getDecisions().size() > 50) {
            throw invalid("BLUEPRINT_ACCEPTANCE_DECISIONS_INVALID", "单次验收决定不能超过 50 项");
        }
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> normalized = new ArrayList<>();
        request.getDecisions().forEach(decision -> {
            String capabilityId = trimToNull(decision == null ? null : decision.getCapabilityId());
            if (capabilityId == null || !seen.add(capabilityId)) {
                throw invalid("BLUEPRINT_ACCEPTANCE_DECISIONS_INVALID",
                        capabilityId == null ? "验收能力 ID 不能为空" : "验收能力重复：" + capabilityId);
            }
            if (capabilityId.length() > 100) {
                throw invalid("BLUEPRINT_ACCEPTANCE_DECISIONS_INVALID", "验收能力 ID 不能超过 100 个字符");
            }
            String value = trimToNull(decision.getDecision());
            if (!Set.of("ACCEPTED", "REJECTED").contains(value)) {
                throw invalid("BLUEPRINT_ACCEPTANCE_DECISIONS_INVALID",
                        capabilityId + " 的验收决定只能是 ACCEPTED 或 REJECTED");
            }
            String note = trimToNull(decision.getNote());
            if (note != null && note.length() > 2000) {
                throw invalid("BLUEPRINT_ACCEPTANCE_DECISIONS_INVALID",
                        capabilityId + " 的验收说明不能超过 2000 个字符");
            }
            if (decision.getEvidenceRefs() != null && decision.getEvidenceRefs().size() > 20) {
                throw invalid("BLUEPRINT_ACCEPTANCE_DECISIONS_INVALID",
                        capabilityId + " 的证据引用最多 20 条");
            }
            List<String> evidenceRefs = decision.getEvidenceRefs() == null ? List.of()
                    : decision.getEvidenceRefs().stream()
                            .map(this::trimToNull)
                            .filter(Objects::nonNull)
                            .distinct()
                            .toList();
            if (evidenceRefs.size() > 20 || evidenceRefs.stream().anyMatch(valueRef -> valueRef.length() > 500)) {
                throw invalid("BLUEPRINT_ACCEPTANCE_DECISIONS_INVALID",
                        capabilityId + " 的证据引用最多 20 条且每条不超过 500 个字符");
            }
            if ("REJECTED".equals(value) && note == null) {
                throw invalid("BLUEPRINT_ACCEPTANCE_REJECTION_NOTE_REQUIRED",
                        capabilityId + " 标记为需要调整时必须填写说明");
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("capabilityId", capabilityId);
            item.put("decision", value);
            item.put("note", note);
            item.put("evidenceRefs", evidenceRefs);
            normalized.add(item);
        });
        normalized.sort(Comparator.comparing(item -> Objects.toString(item.get("capabilityId"), "")));
        return normalized;
    }

    private String digest(Object value) {
        try {
            byte[] bytes = objectMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException | JsonProcessingException error) {
            throw new IllegalStateException("Factory Blueprint acceptance digest failed", error);
        }
    }

    private long countAction(List<PlannedChange> changes, String action) {
        return changes.stream().filter(change -> action.equals(change.getAction())).count();
    }

    private int countWritten(Map<String, Object> values) {
        int count = 0;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getKey().startsWith("written") && entry.getValue() instanceof Collection<?> collection) {
                count += collection.size();
            }
        }
        return count;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Factory Blueprint JSON serialization failed", error);
        }
    }

    private <T> T read(String value, Class<T> type) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Factory Blueprint JSON cannot be read", error);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Factory Blueprint JSON cannot be read", error);
        }
    }

    private Map<String, Object> readMap(String value) {
        return read(value, new TypeReference<Map<String, Object>>() { });
    }

    private void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw invalid("BLUEPRINT_REQUEST_INVALID", label + "不能为空");
        }
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BusinessException state(String message) {
        return new BusinessException(409, message)
                .withCode("BLUEPRINT_INVALID_STATE")
                .withHint("刷新蓝图并按当前状态继续")
                .withSeverity("BLOCKING");
    }

    private BusinessException invalid(String code, String message) {
        return new BusinessException(400, message).withCode(code).withSeverity("BLOCKING");
    }
}
