package com.cretas.aims.service.moduleconfigdraft;

import com.cretas.aims.entity.FactoryFeatureConfig;
import com.cretas.aims.entity.moduleconfig.ModuleConfigDraft;
import com.cretas.aims.entity.moduleconfig.ModuleConfigDraftGate;
import com.cretas.aims.entity.moduleconfig.ModuleConfigDraftItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactoryFeatureConfigRepository;
import com.cretas.aims.repository.moduleconfig.ModuleConfigDraftGateRepository;
import com.cretas.aims.repository.moduleconfig.ModuleConfigDraftItemRepository;
import com.cretas.aims.repository.moduleconfig.ModuleConfigDraftRepository;
import com.cretas.aims.service.config.FactoryConfigService;
import com.cretas.aims.service.config.ModuleGateStatus;
import com.cretas.aims.blueprint.interview.InterviewModuleConfigCatalog;
import com.cretas.aims.service.module.ModuleSwitchState;
import com.cretas.aims.service.moduleconfigdraft.ModuleConfigDraftModels.DraftIssue;
import com.cretas.aims.service.moduleconfigdraft.ModuleConfigDraftModels.DraftPreview;
import com.cretas.aims.service.moduleconfigdraft.ModuleConfigDraftModels.DraftReadback;
import com.cretas.aims.service.moduleconfigdraft.ModuleConfigDraftModels.DraftReceipt;
import com.cretas.aims.service.moduleconfigdraft.ModuleConfigDraftModels.DraftRequest;
import com.cretas.aims.service.moduleconfigdraft.ModuleConfigDraftModels.GateLayerSnapshot;
import com.cretas.aims.service.moduleconfigdraft.ModuleConfigDraftModels.RecoveryPlan;
import com.cretas.aims.service.moduleconfigdraft.ModuleConfigDraftModels.RequestedModule;
import com.cretas.aims.service.moduleconfigdraft.ModuleConfigDraftModels.ResolvedModule;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Module-owned Factory Blueprint module-configuration DRAFT command.
 *
 * <p>The request accepts only the closed customer-facing module vocabulary. The command resolves
 * that vocabulary through {@link InterviewModuleConfigCatalog}, expands it to typed gate rows and
 * writes only the three isolated DRAFT tables. It never calls a live switch, configuration writer,
 * publish, activation, controller or runtime command.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class ModuleConfigDraftCommand {

    public static final String CAPABILITY_ID = "modules";
    public static final String REQUIRED_PERMISSION = "module:config:draft";

    private final ModuleConfigDraftRepository draftRepository;
    private final ModuleConfigDraftItemRepository itemRepository;
    private final ModuleConfigDraftGateRepository gateRepository;
    private final FactoryFeatureConfigRepository featureConfigRepository;
    private final FactoryConfigService factoryConfigService;

    public ModuleConfigDraftCommand(
            ModuleConfigDraftRepository draftRepository,
            ModuleConfigDraftItemRepository itemRepository,
            ModuleConfigDraftGateRepository gateRepository,
            FactoryFeatureConfigRepository featureConfigRepository,
            FactoryConfigService factoryConfigService) {
        this.draftRepository = Objects.requireNonNull(draftRepository, "draftRepository");
        this.itemRepository = Objects.requireNonNull(itemRepository, "itemRepository");
        this.gateRepository = Objects.requireNonNull(gateRepository, "gateRepository");
        this.featureConfigRepository = Objects.requireNonNull(featureConfigRepository, "featureConfigRepository");
        this.factoryConfigService = Objects.requireNonNull(factoryConfigService, "factoryConfigService");
    }

    /** Recomputes the live catalog/source snapshot and desired projection before activation. */
    @Transactional(readOnly = true)
    public void assertCurrentAuthority(String factoryId, String draftId) {
        ModuleConfigDraft parent = draftRepository.findByIdAndFactoryId(clean(draftId), clean(factoryId))
                .orElseThrow(() -> invalid(404, "当前工厂没有该模块配置草稿", "MODULE_CONFIG_DRAFT_NOT_FOUND",
                        "刷新当前工厂的模块配置草稿回执", "draftId"));
        DraftReadback readback = readBack(factoryId, draftId);
        List<RequestedModule> requested = readback.modules().stream()
                .map(module -> new RequestedModule(module.moduleId(), module.desiredEnabled(), module.rationale()))
                .toList();
        Long authorityActorId = parent.getActorId() != null ? parent.getActorId() : parent.getOperatorId();
        if (authorityActorId == null) {
            throw invalid(409, "草稿缺少 typed actor 身份，无法校验权威状态", "DRAFT_ACTOR_IDENTITY_MISSING",
                    "保留草稿并由管理员重新生成 typed 草稿", "draft");
        }
        DraftPreview current = preview(new DraftRequest(parent.getFactoryId(), parent.getBlueprintId(),
                parent.getBlueprintVersion(), parent.getSourceDigest(), requested, "", "authority-readback",
                authorityActorId));
        if (!catalogDigest().equals(parent.getCatalogDigest())
                || !current.sourceSnapshotDigest().equals(parent.getSourceSnapshotDigest())
                || !current.desiredDigest().equals(parent.getDesiredDigest())) {
            throw invalid(409, "模块配置草稿的权威目录或当前开关已变化", "MODULE_CONFIG_AUTHORITY_DRIFT",
                    "重新预览当前 blueprint、目录与工厂开关后再激活", "draft");
        }
    }

    @Transactional(readOnly = true)
    public DraftPreview preview(DraftRequest request) {
        validateEnvelope(request, false);
        List<DraftIssue> issues = new ArrayList<>();
        Map<String, RequestedModule> requested = indexRequested(request.modules(), issues);
        GateLayerSnapshot gateLayer = gateLayerSnapshot(factoryConfigService.describeModuleGate(request.factoryId()));
        List<ResolvedModule> resolved = new ArrayList<>();
        Set<String> expandedGateCodes = new HashSet<>();

        int ordinal = 0;
        for (InterviewModuleConfigCatalog.Entry entry : InterviewModuleConfigCatalog.all()) {
            RequestedModule choice = requested.get(entry.moduleId());
            if (choice == null) {
                issues.add(issue("MODULE_CONFIG_SELECTION_MISSING", "modules." + entry.moduleId(),
                        "模块配置必须明确回答“" + entry.label() + "”是否启用",
                        "由工厂确认启用或暂不开，并填写业务理由"));
                ordinal++;
                continue;
            }
            if (choice.enabled() == null) {
                issues.add(issue("MODULE_CONFIG_ENABLED_REQUIRED", "modules." + entry.moduleId() + ".enabled",
                        "模块启用状态不能为空", "明确选择启用或暂不开"));
            }
            if (choice.rationale().isBlank()) {
                issues.add(issue("MODULE_CONFIG_RATIONALE_REQUIRED", "modules." + entry.moduleId() + ".rationale",
                        "模块选择必须保留工厂确认理由", "填写适用范围、当前需求或暂不开原因"));
            } else if (choice.rationale().length() > 500) {
                issues.add(issue("MODULE_CONFIG_RATIONALE_TOO_LONG", "modules." + entry.moduleId() + ".rationale",
                        "模块选择理由不能超过 500 字符", "保留可审核的关键业务依据"));
            }
            boolean frontendVisible = featureConfigRepository
                    .findByFactoryIdAndModuleIdAndDeletedAtIsNull(request.factoryId(), entry.moduleId())
                    .map(value -> Boolean.TRUE.equals(value.getEnabled())).orElse(true);
            boolean backendEnabled = backendEnabled(request.factoryId(), entry);
            boolean desiredEnabled = Boolean.TRUE.equals(choice.enabled());
            resolved.add(new ResolvedModule(ordinal, entry.moduleId(), entry.label(), entry.detail(), entry.carrier(),
                    desiredEnabled, choice.rationale(), frontendVisible, backendEnabled,
                    ModuleSwitchState.of(frontendVisible, backendEnabled).name(), !entry.gateCodes().isEmpty(),
                    entry.gateCodes(), ModuleConfigDraftModels.HISTORICAL_READ_POLICY));
            for (String gateCode : entry.gateCodes()) {
                if (!expandedGateCodes.add(gateCode)) {
                    issues.add(issue("MODULE_CONFIG_GATE_DUPLICATED", "catalog." + gateCode,
                            "同一底层门禁码被多个客户模块重复拥有", "先修复权威模块目录再生成草稿"));
                }
            }
            ordinal++;
        }

        Set<String> catalogGateCodes = InterviewModuleConfigCatalog.allGateCodes();
        if (!expandedGateCodes.equals(catalogGateCodes)) {
            issues.add(issue("MODULE_CONFIG_GATE_COVERAGE_INCOMPLETE", "catalog.gateCodes",
                    "模块草稿没有完整覆盖当前权威门禁目录", "刷新模块目录并重新预览"));
        }
        boolean dependencyClosureComplete = resolved.size() == InterviewModuleConfigCatalog.all().size()
                && expandedGateCodes.equals(catalogGateCodes);
        String catalogDigest = catalogDigest();
        String sourceSnapshotDigest = sourceSnapshotDigest(gateLayer, resolved);
        String desiredDigest = desiredDigest(resolved);
        issues.sort(Comparator.comparing(DraftIssue::code).thenComparing(DraftIssue::target));
        String status = issues.isEmpty() && dependencyClosureComplete ? "READY" : "BLOCKED";
        String previewDigest = digest(ModuleConfigDraftModels.SCHEMA_VERSION, request.factoryId(),
                request.blueprintId(), String.valueOf(request.blueprintVersion()), request.sourceDigest(),
                catalogDigest, sourceSnapshotDigest, desiredDigest, String.valueOf(dependencyClosureComplete),
                status, issueDigest(issues));
        return new DraftPreview(ModuleConfigDraftModels.SCHEMA_VERSION, status, request.factoryId(),
                List.of("factory"), dependencyClosureComplete, ModuleConfigDraftModels.DEPENDENCY_BASIS,
                catalogDigest, gateLayer, resolved, resolved.size(), expandedGateCodes.size(),
                sourceSnapshotDigest, desiredDigest, previewDigest, issues, 0, 0,
                ModuleConfigDraftModels.HISTORICAL_READ_POLICY, "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    @Transactional
    public DraftReceipt materialize(DraftRequest request, com.cretas.aims.service.moduleconfigdraft.ModuleConfigDraftModels.AuthenticatedOperator operator) {
        if (operator == null
                || (operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER && operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.PLATFORM_ADMIN)
                || operator.actor().id() != request.operatorId()
                || !request.factoryId().equals(operator.factoryId())) {
            throw invalid(403, "建厂草稿操作人身份与工厂范围不匹配", "DRAFT_OPERATOR_SCOPE_MISMATCH",
                    "使用当前工厂的有效工厂用户身份重试", "operatorId");
        }
        return materialize(request, operator.permissions(), operator.actor());
    }

    @Transactional
    public DraftReceipt materialize(DraftRequest request, Set<String> operatorPermissions) {
        return materialize(request, operatorPermissions, new com.cretas.aims.security.ActorRef(
                com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER, request.operatorId()));
    }

    private DraftReceipt materialize(DraftRequest request, Set<String> operatorPermissions,
            com.cretas.aims.security.ActorRef actor) {
        validateEnvelope(request, true);
        if (operatorPermissions == null || !operatorPermissions.contains(REQUIRED_PERMISSION)) {
            throw invalid(403, "缺少模块配置草稿权限", "MODULE_CONFIG_DRAFT_PERMISSION_REQUIRED",
                    "由平台配置负责人授予 module:config:draft 后重试", "permission");
        }
        String requestDigest = requestDigest(request, actor);
        Optional<ModuleConfigDraft> prior = draftRepository.findByFactoryIdAndIdempotencyKey(
                request.factoryId(), request.idempotencyKey());
        if (prior.isPresent()) return replay(prior.get(), requestDigest);

        DraftPreview preview = preview(request);
        if (!"READY".equals(preview.status())) {
            throw invalid(409, "模块配置草稿预览仍有阻断项", "MODULE_CONFIG_DRAFT_PREVIEW_BLOCKED",
                    preview.issues().isEmpty() ? "重新生成完整预览" : preview.issues().get(0).recovery(),
                    preview.issues().isEmpty() ? "request" : preview.issues().get(0).target());
        }
        if (!request.confirmedPreviewDigest().equals(preview.previewDigest())) {
            throw invalid(409, "模块配置草稿预览摘要已变化", "MODULE_CONFIG_DRAFT_PREVIEW_DIGEST_CONFLICT",
                    "刷新当前模块开关与权威目录后重新查看并确认 ChangeSet", "confirmedPreviewDigest");
        }

        String draftId = UUID.randomUUID().toString();
        ModuleConfigDraft parent = ModuleConfigDraft.builder()
                .id(draftId).factoryId(request.factoryId()).blueprintId(request.blueprintId())
                .blueprintVersion(request.blueprintVersion()).sourceDigest(request.sourceDigest())
                .catalogDigest(preview.catalogDigest()).sourceSnapshotDigest(preview.sourceSnapshotDigest())
                .desiredDigest(preview.desiredDigest()).previewDigest(preview.previewDigest())
                .requestDigest(requestDigest).idempotencyKey(request.idempotencyKey())
                .operatorId(actor.factoryUserId()).actorType(actor.type()).actorId(actor.id()).moduleCount(preview.moduleCount())
                .gateCodeCount(preview.gateCodeCount()).dependencyBasis(ModuleConfigDraftModels.DEPENDENCY_BASIS)
                .dependencyClosureComplete(true)
                .historicalReadPolicy(ModuleConfigDraftModels.HISTORICAL_READ_POLICY)
                .status("DRAFT").build();
        ModuleConfigDraft saved;
        try {
            saved = draftRepository.saveAndFlush(parent);
        } catch (DataIntegrityViolationException race) {
            if (!isScopedIdempotencyRace(race)) throw race;
            throw invalid(409, "模块配置草稿幂等键发生并发竞争", "MODULE_CONFIG_DRAFT_IDEMPOTENCY_RACE",
                    "使用相同幂等键重试以读取胜出回执", "idempotencyKey");
        }

        List<ModuleConfigDraftItem> items = new ArrayList<>();
        List<ModuleConfigDraftGate> gates = new ArrayList<>();
        for (ResolvedModule module : preview.modules()) {
            String itemId = UUID.randomUUID().toString();
            items.add(ModuleConfigDraftItem.builder()
                    .id(itemId).draftId(draftId).factoryId(request.factoryId()).ordinal(module.ordinal())
                    .moduleId(module.moduleId()).moduleLabel(module.label()).moduleDetail(module.detail())
                    .carrier(module.carrier())
                    .desiredEnabled(module.desiredEnabled()).rationale(module.rationale())
                    .currentFrontendVisible(module.currentFrontendVisible())
                    .currentBackendEnabled(module.currentBackendEnabled()).currentState(module.currentState())
                    .backendGated(module.backendGated()).gateCodeCount(module.gateCodes().size())
                    .historicalReadPolicy(ModuleConfigDraftModels.HISTORICAL_READ_POLICY).build());
            int gateOrdinal = 0;
            for (String gateCode : module.gateCodes()) {
                gates.add(ModuleConfigDraftGate.builder()
                        .id(UUID.randomUUID().toString()).draftId(draftId).draftItemId(itemId)
                        .factoryId(request.factoryId()).moduleId(module.moduleId()).gateOrdinal(gateOrdinal++)
                        .gateCode(gateCode).desiredEnabled(module.desiredEnabled()).build());
            }
        }
        List<ModuleConfigDraftItem> savedItems = itemRepository.saveAllAndFlush(items);
        List<ModuleConfigDraftGate> savedGates = gateRepository.saveAllAndFlush(gates);
        requireExactReadback(saved, savedItems, savedGates, preview);
        return receipt(saved, false, 1, 1 + savedItems.size() + savedGates.size());
    }

    @Transactional(readOnly = true)
    public DraftReadback readBack(String factoryId, String draftId) {
        ModuleConfigDraft parent = draftRepository.findByIdAndFactoryId(clean(draftId), clean(factoryId))
                .orElseThrow(() -> invalid(404, "当前工厂没有该模块配置草稿", "MODULE_CONFIG_DRAFT_NOT_FOUND",
                        "刷新当前工厂的模块配置草稿回执", "draftId"));
        List<ModuleConfigDraftItem> items = itemRepository
                .findByDraftIdAndFactoryIdOrderByOrdinal(parent.getId(), parent.getFactoryId());
        List<ModuleConfigDraftGate> gates = gateRepository
                .findByDraftIdAndFactoryIdOrderByModuleIdAscGateOrdinalAsc(parent.getId(), parent.getFactoryId());
        List<ResolvedModule> modules = reconstruct(items, gates);
        if (modules.size() != parent.getModuleCount() || gates.size() != parent.getGateCodeCount()) {
            throw invalid(409, "模块配置草稿回读行数不完整", "MODULE_CONFIG_DRAFT_READBACK_MISMATCH",
                    "保留草稿并由模块负责人检查持久化完整性", "draftId");
        }
        return new DraftReadback(ModuleConfigDraftModels.SCHEMA_VERSION, parent.getFactoryId(), parent.getId(),
                parent.getStatus(), modules, gates.size(), Boolean.TRUE.equals(parent.getDependencyClosureComplete()),
                parent.getHistoricalReadPolicy(), "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    @Transactional(readOnly = true)
    public RecoveryPlan recoveryPlan(String factoryId, String draftId) {
        ModuleConfigDraft row = draftRepository.findByIdAndFactoryId(clean(draftId), clean(factoryId))
                .orElseThrow(() -> invalid(404, "当前工厂没有该模块配置草稿", "MODULE_CONFIG_DRAFT_NOT_FOUND",
                        "刷新当前工厂的模块配置草稿回执", "draftId"));
        return new RecoveryPlan(ModuleConfigDraftModels.SCHEMA_VERSION, row.getFactoryId(), row.getId(),
                row.getDesiredDigest(), "SOFT_DELETE_DRAFT_AFTER_MODULE_OWNER_CONFIRMATION",
                "只移除未发布模块配置草稿；不会改变界面可见性、接口门禁、权限或任何历史记录读取",
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    private Map<String, RequestedModule> indexRequested(List<RequestedModule> modules, List<DraftIssue> issues) {
        Map<String, RequestedModule> indexed = new LinkedHashMap<>();
        for (int i = 0; i < modules.size(); i++) {
            RequestedModule value = modules.get(i);
            if (value == null || value.moduleId().isBlank()) {
                issues.add(issue("MODULE_CONFIG_MODULE_ID_REQUIRED", "modules[" + i + "].moduleId",
                        "模块标识不能为空", "从服务器返回的模块目录中选择"));
                continue;
            }
            if (InterviewModuleConfigCatalog.find(value.moduleId()) == null) {
                issues.add(issue("MODULE_CONFIG_MODULE_UNKNOWN", "modules[" + i + "].moduleId",
                        "未知客户模块: " + value.moduleId(), "刷新权威模块目录，不要提交底层 gate code"));
                continue;
            }
            if (indexed.putIfAbsent(value.moduleId(), value) != null) {
                issues.add(issue("MODULE_CONFIG_MODULE_DUPLICATED", "modules." + value.moduleId(),
                        "同一模块不能重复选择", "每个模块只保留一个确认结论"));
            }
        }
        return indexed;
    }

    private boolean backendEnabled(String factoryId, InterviewModuleConfigCatalog.Entry entry) {
        if (entry.gateCodes().isEmpty()) return true;
        return entry.gateCodes().stream().anyMatch(code -> factoryConfigService.isModuleEnabled(factoryId, code));
    }

    private GateLayerSnapshot gateLayerSnapshot(ModuleGateStatus status) {
        if (status == null) {
            throw invalid(409, "无法读取当前模块门禁状态", "MODULE_CONFIG_GATE_STATUS_UNAVAILABLE",
                    "恢复当前工厂模块门禁只读服务后重试", "factoryId");
        }
        List<String> stale = sorted(status.staleVersionModuleCodes());
        List<String> pending = sorted(status.pendingPublishModuleCodes());
        return new GateLayerSnapshot(status.effective(), status.reason().name(), status.publishedVersion(),
                status.moduleRowsAtPublishedVersion(), stale, clean(status.pendingWorkflowStatus()), pending);
    }

    private List<ResolvedModule> reconstruct(
            List<ModuleConfigDraftItem> items, List<ModuleConfigDraftGate> gates) {
        Map<String, List<String>> byItem = new HashMap<>();
        Map<String, ModuleConfigDraftItem> itemById = new HashMap<>();
        for (ModuleConfigDraftItem item : items) {
            if (itemById.putIfAbsent(item.getId(), item) != null) throw readbackMismatch();
        }
        for (ModuleConfigDraftGate gate : gates) {
            ModuleConfigDraftItem owner = itemById.get(gate.getDraftItemId());
            List<String> ownerGates = byItem.computeIfAbsent(gate.getDraftItemId(), ignored -> new ArrayList<>());
            if (owner == null || !Objects.equals(owner.getDraftId(), gate.getDraftId())
                    || !Objects.equals(owner.getFactoryId(), gate.getFactoryId())
                    || !Objects.equals(owner.getModuleId(), gate.getModuleId())
                    || !Objects.equals(owner.getDesiredEnabled(), gate.getDesiredEnabled())
                    || !Objects.equals(gate.getGateOrdinal(), ownerGates.size())) {
                throw readbackMismatch();
            }
            ownerGates.add(gate.getGateCode());
        }
        List<ResolvedModule> out = new ArrayList<>();
        for (ModuleConfigDraftItem item : items) {
            InterviewModuleConfigCatalog.Entry entry = InterviewModuleConfigCatalog.find(item.getModuleId());
            if (entry == null) {
                throw invalid(409, "模块配置草稿引用的目录项已不存在", "MODULE_CONFIG_CATALOG_DRIFT",
                        "保留旧草稿只读并基于当前目录重新生成", "moduleId");
            }
            if (byItem.getOrDefault(item.getId(), List.of()).size() != item.getGateCodeCount()) {
                throw readbackMismatch();
            }
            out.add(new ResolvedModule(item.getOrdinal(), item.getModuleId(), item.getModuleLabel(), item.getModuleDetail(),
                    item.getCarrier(), Boolean.TRUE.equals(item.getDesiredEnabled()), item.getRationale(),
                    Boolean.TRUE.equals(item.getCurrentFrontendVisible()),
                    Boolean.TRUE.equals(item.getCurrentBackendEnabled()), item.getCurrentState(),
                    Boolean.TRUE.equals(item.getBackendGated()), byItem.getOrDefault(item.getId(), List.of()),
                    item.getHistoricalReadPolicy()));
        }
        return out;
    }

    private void requireExactReadback(
            ModuleConfigDraft parent,
            List<ModuleConfigDraftItem> items,
            List<ModuleConfigDraftGate> gates,
            DraftPreview preview) {
        if (parent == null || !"DRAFT".equals(parent.getStatus())
                || !preview.factoryId().equals(parent.getFactoryId())
                || !preview.catalogDigest().equals(parent.getCatalogDigest())
                || !preview.sourceSnapshotDigest().equals(parent.getSourceSnapshotDigest())
                || !preview.desiredDigest().equals(parent.getDesiredDigest())
                || !preview.previewDigest().equals(parent.getPreviewDigest())
                || !Boolean.TRUE.equals(parent.getDependencyClosureComplete())
                || !ModuleConfigDraftModels.HISTORICAL_READ_POLICY.equals(parent.getHistoricalReadPolicy())
                || items.size() != preview.moduleCount() || gates.size() != preview.gateCodeCount()) {
            throw readbackMismatch();
        }
        List<ResolvedModule> actual = reconstruct(items.stream()
                .sorted(Comparator.comparing(ModuleConfigDraftItem::getOrdinal)).toList(), gates);
        if (actual.size() != preview.modules().size()) throw readbackMismatch();
        for (int i = 0; i < actual.size(); i++) {
            ResolvedModule expected = preview.modules().get(i);
            ResolvedModule found = actual.get(i);
            if (expected.ordinal() != found.ordinal()
                    || !expected.moduleId().equals(found.moduleId())
                    || !expected.label().equals(found.label())
                    || !expected.detail().equals(found.detail())
                    || !expected.carrier().equals(found.carrier())
                    || expected.desiredEnabled() != found.desiredEnabled()
                    || !expected.rationale().equals(found.rationale())
                    || expected.currentFrontendVisible() != found.currentFrontendVisible()
                    || expected.currentBackendEnabled() != found.currentBackendEnabled()
                    || !expected.currentState().equals(found.currentState())
                    || expected.backendGated() != found.backendGated()
                    || !expected.gateCodes().equals(found.gateCodes())
                    || !ModuleConfigDraftModels.HISTORICAL_READ_POLICY.equals(found.historicalReadPolicy())) {
                throw readbackMismatch();
            }
        }
    }

    private BusinessException readbackMismatch() {
        return invalid(409, "模块配置草稿回读与已确认 ChangeSet 不一致",
                "MODULE_CONFIG_DRAFT_READBACK_MISMATCH", "保留事务回滚并重新预览", "draft");
    }

    private void validateEnvelope(DraftRequest request, boolean materialize) {
        if (request == null) throw invalid(400, "模块配置草稿请求不能为空", "MODULE_CONFIG_DRAFT_REQUEST_REQUIRED",
                "从已确认的 modules ChangeSet 生成请求", "request");
        if (request.factoryId().isBlank() || request.blueprintId().isBlank() || request.blueprintVersion() <= 0) {
            throw invalid(400, "工厂与 Blueprint 版本不能为空", "MODULE_CONFIG_DRAFT_SCOPE_REQUIRED",
                    "锁定 factoryId、blueprintId 和 blueprintVersion", "blueprint");
        }
        if (!request.sourceDigest().matches("[0-9a-f]{64}")) {
            throw invalid(400, "Blueprint sourceDigest 必须是 SHA-256", "MODULE_CONFIG_DRAFT_SOURCE_DIGEST_INVALID",
                    "使用服务器编译工件的 sourceDigest", "sourceDigest");
        }
        if (materialize) {
            if (request.operatorId() <= 0) throw invalid(400, "模块配置草稿必须记录操作人",
                    "MODULE_CONFIG_DRAFT_OPERATOR_REQUIRED", "使用已认证平台配置负责人身份", "operatorId");
            if (request.idempotencyKey().isBlank() || request.idempotencyKey().length() > 128) {
                throw invalid(400, "模块配置草稿幂等键不能为空且不能超过 128 字符",
                        "MODULE_CONFIG_DRAFT_IDEMPOTENCY_KEY_INVALID", "为本次确认生成稳定幂等键", "idempotencyKey");
            }
            if (!request.confirmedPreviewDigest().matches("[0-9a-f]{64}")) {
                throw invalid(400, "必须确认服务器生成的 previewDigest",
                        "MODULE_CONFIG_DRAFT_CONFIRMED_PREVIEW_REQUIRED", "重新预览并确认当前摘要",
                        "confirmedPreviewDigest");
            }
        }
    }

    private DraftReceipt replay(ModuleConfigDraft row, String requestDigest) {
        if (!row.getRequestDigest().equals(requestDigest)) {
            throw invalid(409, "该幂等键已绑定另一份模块配置草稿载荷",
                    "MODULE_CONFIG_DRAFT_IDEMPOTENCY_KEY_CONFLICT", "复用原请求或生成新幂等键", "idempotencyKey");
        }
        return receipt(row, true, 0, 1 + row.getModuleCount() + row.getGateCodeCount());
    }

    private DraftReceipt receipt(ModuleConfigDraft row, boolean replayed, int writes, int persistedRows) {
        return new DraftReceipt(ModuleConfigDraftModels.SCHEMA_VERSION, row.getId(), row.getFactoryId(), row.getId(),
                row.getStatus(), row.getCatalogDigest(), row.getDesiredDigest(), row.getPreviewDigest(),
                row.getRequestDigest(), row.getModuleCount(), row.getGateCodeCount(),
                Boolean.TRUE.equals(row.getDependencyClosureComplete()), row.getHistoricalReadPolicy(),
                Objects.requireNonNullElse(row.getLockVersion(), 0L), replayed, writes, 1, persistedRows,
                "NOT_PUBLISHED", "NOT_ACTIVATED",
                "由模块配置负责人确认后软删除整份 DRAFT；现有模块开关、权限与历史记录读取不受影响");
    }

    private String catalogDigest() {
        List<String> values = new ArrayList<>();
        int ordinal = 0;
        for (InterviewModuleConfigCatalog.Entry entry : InterviewModuleConfigCatalog.all()) {
            values.add(String.valueOf(ordinal++));
            values.add(entry.moduleId());
            values.add(entry.label());
            values.add(entry.detail());
            values.add(entry.carrier());
            values.addAll(entry.gateCodes());
        }
        return digest(values.toArray(String[]::new));
    }

    private String sourceSnapshotDigest(GateLayerSnapshot gateLayer, List<ResolvedModule> modules) {
        List<String> values = new ArrayList<>(List.of(String.valueOf(gateLayer.effective()), gateLayer.reason(),
                integer(gateLayer.publishedVersion()), String.valueOf(gateLayer.rowsAtPublishedVersion()),
                gateLayer.pendingWorkflowStatus()));
        values.addAll(gateLayer.staleGateCodes());
        values.addAll(gateLayer.pendingPublishGateCodes());
        for (ResolvedModule module : modules) {
            values.add(module.moduleId());
            values.add(String.valueOf(module.currentFrontendVisible()));
            values.add(String.valueOf(module.currentBackendEnabled()));
            values.add(module.currentState());
        }
        return digest(values.toArray(String[]::new));
    }

    private String desiredDigest(List<ResolvedModule> modules) {
        List<String> values = new ArrayList<>();
        for (ResolvedModule module : modules) {
            values.add(module.moduleId());
            values.add(String.valueOf(module.desiredEnabled()));
            values.add(module.rationale());
            values.addAll(module.gateCodes());
            values.add(module.historicalReadPolicy());
        }
        return digest(values.toArray(String[]::new));
    }

    private String requestDigest(DraftRequest request, com.cretas.aims.security.ActorRef actor) {
        List<String> values = new ArrayList<>(List.of(ModuleConfigDraftModels.SCHEMA_VERSION,
                request.factoryId(), request.blueprintId(), String.valueOf(request.blueprintVersion()),
                request.sourceDigest()));
        request.modules().stream().filter(Objects::nonNull)
                .sorted(Comparator.comparing(RequestedModule::moduleId)
                        .thenComparing(value -> String.valueOf(value.enabled()))
                        .thenComparing(RequestedModule::rationale))
                .forEach(value -> {
                    values.add(value.moduleId());
                    values.add(String.valueOf(value.enabled()));
                    values.add(value.rationale());
                });
        values.add(request.confirmedPreviewDigest());
        values.add(request.idempotencyKey());
        values.add(actor.type().name() + ":" + String.valueOf(actor.id()));
        return digest(values.toArray(String[]::new));
    }

    private String issueDigest(List<DraftIssue> issues) {
        return digest(issues.stream().map(issue -> issue.code() + "\u0000" + issue.target()
                + "\u0000" + issue.message()).toArray(String[]::new));
    }

    private boolean isScopedIdempotencyRace(Throwable failure) {
        Throwable cursor = failure;
        while (cursor != null) {
            if (clean(cursor.getMessage()).toLowerCase(Locale.ROOT)
                    .contains("uk_module_config_draft_idempotency")) return true;
            cursor = cursor.getCause();
        }
        return false;
    }

    private String digest(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = clean(value).getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static List<String> sorted(List<String> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).sorted().toList();
    }

    private static String integer(Integer value) {
        return value == null ? "" : value.toString();
    }

    private static DraftIssue issue(String code, String target, String message, String recovery) {
        return new DraftIssue(code, target, message, recovery);
    }

    private static String clean(String value) {
        return ModuleConfigDraftModels.clean(value);
    }

    private static BusinessException invalid(
            int status, String message, String code, String recovery, String target) {
        return new BusinessException(status, message).withCode(code).withHint(recovery)
                .withHintTarget(target).withSeverity("warning");
    }
}
