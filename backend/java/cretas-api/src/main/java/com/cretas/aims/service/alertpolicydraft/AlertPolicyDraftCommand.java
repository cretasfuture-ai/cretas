package com.cretas.aims.service.alertpolicydraft;

import com.cretas.aims.blueprint.capabilityadapter.FactoryCapabilityAdapterContracts;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.CapabilityView;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintService;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.alertpolicydraft.AlertPolicyDraft;
import com.cretas.aims.entity.alertpolicydraft.AlertPolicyDraftEvidence;
import com.cretas.aims.entity.alertpolicydraft.AlertPolicyDraftItem;
import com.cretas.aims.entity.moduleconfig.ModuleConfigDraft;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.alertpolicydraft.AlertPolicyDraftEvidenceRepository;
import com.cretas.aims.repository.alertpolicydraft.AlertPolicyDraftItemRepository;
import com.cretas.aims.repository.alertpolicydraft.AlertPolicyDraftRepository;
import com.cretas.aims.repository.moduleconfig.ModuleConfigDraftRepository;
import com.cretas.aims.service.alertpolicydraft.AlertPolicyDraftModels.AuthenticatedOperator;
import com.cretas.aims.service.alertpolicydraft.AlertPolicyDraftModels.Comparison;
import com.cretas.aims.service.alertpolicydraft.AlertPolicyDraftModels.DerivedPolicy;
import com.cretas.aims.service.alertpolicydraft.AlertPolicyDraftModels.DraftIssue;
import com.cretas.aims.service.alertpolicydraft.AlertPolicyDraftModels.DraftPreview;
import com.cretas.aims.service.alertpolicydraft.AlertPolicyDraftModels.DraftReadback;
import com.cretas.aims.service.alertpolicydraft.AlertPolicyDraftModels.DraftReceipt;
import com.cretas.aims.service.alertpolicydraft.AlertPolicyDraftModels.DraftRequest;
import com.cretas.aims.service.alertpolicydraft.AlertPolicyDraftModels.EscalationRole;
import com.cretas.aims.service.alertpolicydraft.AlertPolicyDraftModels.Metric;
import com.cretas.aims.service.alertpolicydraft.AlertPolicyDraftModels.MetricDimension;
import com.cretas.aims.service.alertpolicydraft.AlertPolicyDraftModels.ModuleConfigPin;
import com.cretas.aims.service.alertpolicydraft.AlertPolicyDraftModels.PolicyInput;
import com.cretas.aims.service.alertpolicydraft.AlertPolicyDraftModels.RecipientRole;
import com.cretas.aims.service.alertpolicydraft.AlertPolicyDraftModels.RecoveryPlan;
import com.cretas.aims.service.alertpolicydraft.AlertPolicyDraftModels.Severity;
import com.cretas.aims.service.moduleconfigdraft.ModuleConfigDraftCommand;
import com.cretas.aims.service.moduleconfigdraft.ModuleConfigDraftModels;
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
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Module-owned alert policy DRAFT command.
 *
 * <p>The immutable capability catalog v2 dependency is {@code modules}: every policy binds to a
 * module that a same-lineage, readback-ready module-config DRAFT actually enables.</p>
 *
 * <p>The legacy {@code AlertRule} model stores a free TEXT SpEL trigger and JSONB notify channels
 * and roles, and its engine creates {@code AlertEvent} rows and sends notifications. It is
 * deliberately unreachable from here: the trigger is a closed metric plus a closed comparison and
 * typed decimal bounds, recipients are typed roles, and the counters for alert rules, alert events,
 * notifications and SpEL expressions are all fixed at zero.</p>
 *
 * <p>{@code alert-policy/v1} derives the escalation role, acknowledgement SLA, auto-escalation
 * delay and two-person acknowledgement from severity alone.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class AlertPolicyDraftCommand {
    public static final String CAPABILITY_ID = "alerts";
    public static final String REQUIRED_PERMISSION = "alert:policy:draft";
    private static final String AUTHORITY_CODE = "ALERT_POLICY_CATALOG_AUTHORITY";
    private static final List<String> DEPENDENCIES =
            FactoryCapabilityAdapterContracts.dependenciesOf(CAPABILITY_ID);
    private static final int MAX_POLICIES = 300;

    private final AlertPolicyDraftRepository drafts;
    private final AlertPolicyDraftItemRepository items;
    private final AlertPolicyDraftEvidenceRepository evidence;
    private final ModuleConfigDraftRepository moduleDrafts;
    private final ModuleConfigDraftCommand moduleCommand;
    private final CanonicalFactoryBlueprintService canonicalBlueprints;
    private final UserRepository users;

    public AlertPolicyDraftCommand(
            AlertPolicyDraftRepository drafts,
            AlertPolicyDraftItemRepository items,
            AlertPolicyDraftEvidenceRepository evidence,
            ModuleConfigDraftRepository moduleDrafts,
            ModuleConfigDraftCommand moduleCommand,
            CanonicalFactoryBlueprintService canonicalBlueprints,
            UserRepository users) {
        this.drafts = drafts;
        this.items = items;
        this.evidence = evidence;
        this.moduleDrafts = moduleDrafts;
        this.moduleCommand = moduleCommand;
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
        Optional<AlertPolicyDraft> prior = drafts
                .findByFactoryIdAndIdempotencyKey(request.factoryId(), request.idempotencyKey());
        if (prior.isPresent()) {
            if (!requestDigest.equals(prior.get().getRequestDigest())) {
                throw invalid(409, "幂等键对应不同告警策略请求",
                        "ALERT_POLICY_IDEMPOTENCY_CONFLICT");
            }
            readBack(request.factoryId(), prior.get().getId());
            return receipt(prior.get(), true, 0, 0);
        }

        Evaluation evaluation = evaluate(request);
        DraftPreview plan = evaluation.preview();
        if (!"READY".equals(plan.status())) {
            throw invalid(409, "告警策略预览被阻断", "ALERT_POLICY_PREVIEW_BLOCKED");
        }
        if (!request.confirmedPreviewDigest().equals(plan.previewDigest())) {
            throw invalid(409, "告警策略确认摘要已漂移", "ALERT_POLICY_PREVIEW_DRIFT");
        }

        BlueprintAuthority authority = evaluation.authority();
        ModuleAuthority modules = evaluation.modules();
        AlertPolicyDraft parent = AlertPolicyDraft.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(request.factoryId())
                .blueprintId(request.blueprintId())
                .blueprintVersion(request.blueprintVersion())
                .sourceDigest(request.sourceDigest())
                .sourceSnapshotDigest(plan.sourceSnapshotDigest())
                .moduleConfigDraftId(modules.draftId())
                .moduleConfigDesiredDigest(modules.desiredDigest())
                .moduleSnapshotDigest(modules.snapshotDigest())
                .capabilityCatalogVersion(authority.catalogVersion())
                .capabilityCatalogDigest(authority.catalogDigest())
                .authoritySnapshotDigest(authority.authoritySnapshotDigest())
                .desiredDigest(plan.desiredDigest())
                .previewDigest(plan.previewDigest())
                .requestDigest(requestDigest)
                .idempotencyKey(request.idempotencyKey())
                .operatorId(operator.actor().factoryUserId()).actorType(operator.actor().type()).actorId(operator.actor().id())
                .policyCount(plan.policies().size())
                .evidenceCount(1)
                .dependencyBasis(AlertPolicyDraftModels.DEPENDENCY_BASIS)
                .algorithmVersion(AlertPolicyDraftModels.ALGORITHM_VERSION)
                .dependencyClosureComplete(true)
                .status("DRAFT")
                .build();
        AlertPolicyDraft saved;
        try {
            saved = drafts.saveAndFlush(parent);
        } catch (DataIntegrityViolationException exception) {
            if (!isIdempotencyRace(exception)) throw exception;
            throw invalid(409, "告警策略幂等键发生并发竞争", "ALERT_POLICY_IDEMPOTENCY_RACE");
        }

        List<AlertPolicyDraftItem> rows = plan.policies().stream()
                .map(policy -> toRow(saved, policy)).toList();
        items.saveAllAndFlush(rows);
        evidence.saveAndFlush(AlertPolicyDraftEvidence.builder()
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
            throw invalid(409, "告警策略回读摘要不一致", "ALERT_POLICY_READBACK_MISMATCH");
        }
        return receipt(saved, false, 1, 2 + rows.size());
    }

    @Transactional(readOnly = true)
    public DraftReadback readBack(String factoryId, String draftId) {
        AlertPolicyDraft parent = drafts.findByIdAndFactoryId(draftId, factoryId)
                .orElseThrow(() -> invalid(404, "当前工厂没有该告警策略草稿",
                        "ALERT_POLICY_NOT_FOUND"));
        List<AlertPolicyDraftItem> rows = items
                .findByDraftIdAndFactoryIdOrderByOrdinal(draftId, factoryId);
        List<AlertPolicyDraftEvidence> evidenceRows = evidence
                .findByDraftIdAndFactoryIdOrderByAuthorityCode(draftId, factoryId);
        if (!"DRAFT".equals(parent.getStatus())
                || !Boolean.TRUE.equals(parent.getDependencyClosureComplete())
                || !AlertPolicyDraftModels.DEPENDENCY_BASIS.equals(parent.getDependencyBasis())
                || !AlertPolicyDraftModels.ALGORITHM_VERSION.equals(parent.getAlgorithmVersion())
                || parent.getPolicyCount() == null || parent.getPolicyCount() != rows.size()
                || parent.getEvidenceCount() == null || parent.getEvidenceCount() != 1
                || evidenceRows.size() != 1) {
            throw invalid(409, "告警策略父子行或依赖闭包不完整",
                    "ALERT_POLICY_READBACK_INVARIANT_BROKEN");
        }

        List<DraftIssue> issues = new ArrayList<>();
        BlueprintAuthority authority = resolveBlueprint(factoryId, parent.getBlueprintId(),
                parent.getBlueprintVersion(), issues);
        ModuleAuthority modules = resolveStoredModules(parent, issues);
        if (!issues.isEmpty()
                || !parent.getAuthoritySnapshotDigest().equals(authority.authoritySnapshotDigest())
                || !parent.getCapabilityCatalogVersion().equals(authority.catalogVersion())
                || !parent.getCapabilityCatalogDigest().equals(authority.catalogDigest())
                || !parent.getModuleSnapshotDigest().equals(modules.snapshotDigest())) {
            throw invalid(409, "告警策略上游 Blueprint 或模块配置草稿已漂移",
                    "ALERT_POLICY_UPSTREAM_DRIFT");
        }
        String currentSourceSnapshot = sourceSnapshot(authority, modules);
        if (!currentSourceSnapshot.equals(parent.getSourceSnapshotDigest())) {
            throw invalid(409, "告警策略源快照摘要已漂移",
                    "ALERT_POLICY_SOURCE_SNAPSHOT_DRIFT");
        }
        if (!sameEvidence(evidenceRows.get(0), authority)) {
            throw invalid(409, "告警策略目录依据摘要不一致", "ALERT_POLICY_EVIDENCE_MISMATCH");
        }

        List<DerivedPolicy> policies = new ArrayList<>();
        for (AlertPolicyDraftItem row : rows) {
            DerivedPolicy policy = fromRow(row);
            ModuleTarget module = modules.modules().get(policy.moduleId());
            if (module == null || !module.enabled()
                    || !Objects.equals(module.label(), policy.moduleLabel())
                    || !moduleSnapshot(module).equals(policy.moduleSnapshotDigest())) {
                throw invalid(409, "告警策略绑定的模块已停用或快照已漂移",
                        "ALERT_POLICY_MODULE_DRIFT");
            }
            if (policy.metricDimension() != AlertPolicyDraftModels.dimension(policy.metric())
                    || !AlertPolicyDraftModels.canonicalUnit(policy.metricDimension())
                            .equals(policy.canonicalUnit())
                    || policy.escalationRole() != AlertPolicyDraftModels
                            .escalationRole(policy.severity())
                    || policy.acknowledgeSlaMinutes() != AlertPolicyDraftModels
                            .acknowledgeSlaMinutes(policy.severity())
                    || policy.autoEscalateAfterMinutes() != AlertPolicyDraftModels
                            .autoEscalateAfterMinutes(policy.severity())
                    || policy.twoPersonAcknowledgeRequired() != AlertPolicyDraftModels
                            .twoPersonAcknowledgeRequired(policy.severity())) {
                throw invalid(409, "告警响应派生结论与服务端算法不一致",
                        "ALERT_POLICY_POLICY_TAMPERED");
            }
            policies.add(policy);
        }
        if (!desiredDigest(policies).equals(parent.getDesiredDigest())) {
            throw invalid(409, "告警策略逐字段回读摘要不一致", "ALERT_POLICY_READBACK_MISMATCH");
        }
        return new DraftReadback(AlertPolicyDraftModels.SCHEMA_VERSION, factoryId, draftId,
                parent.getStatus(), policies, parent.getSourceSnapshotDigest(),
                parent.getDesiredDigest(), 0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    @Transactional(readOnly = true)
    public RecoveryPlan recoveryPlan(String factoryId, String draftId,
                                     String expectedDesiredDigest) {
        AlertPolicyDraft parent = drafts.findByIdAndFactoryId(draftId, factoryId)
                .orElseThrow(() -> invalid(404, "当前工厂没有该告警策略草稿",
                        "ALERT_POLICY_NOT_FOUND"));
        if (!"DRAFT".equals(parent.getStatus()) || !isDigest(expectedDesiredDigest)
                || !expectedDesiredDigest.equals(parent.getDesiredDigest())) {
            throw invalid(409, "只能恢复摘要精确匹配的未发布告警策略草稿",
                    "ALERT_POLICY_RECOVERY_NOT_ALLOWED");
        }
        return new RecoveryPlan(AlertPolicyDraftModels.SCHEMA_VERSION, factoryId, draftId,
                expectedDesiredDigest, "ARCHIVE_DRAFT_ONLY",
                "只归档未发布告警策略草稿；不创建正式告警规则、告警事件或通知，也不执行任何表达式",
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    private Evaluation evaluate(DraftRequest request) {
        List<DraftIssue> issues = new ArrayList<>();
        BlueprintAuthority authority = resolveBlueprint(request.factoryId(), request.blueprintId(),
                request.blueprintVersion(), issues);
        ModuleAuthority modules = resolveModules(request, issues);
        List<DerivedPolicy> derived = new ArrayList<>();
        Set<String> policyCodes = new HashSet<>();
        for (int ordinal = 0; ordinal < request.policies().size(); ordinal++) {
            PolicyInput input = request.policies().get(ordinal);
            String target = "policies[" + ordinal + "]";
            if (!validatePolicy(input, target, issues)) continue;
            ModuleTarget module = modules.modules().get(input.moduleId());
            if (module == null) {
                issues.add(issue("ALERT_POLICY_MODULE_MISSING", target,
                        "告警策略必须绑定同一 readback-ready 模块配置草稿中的模块",
                        "重新选择模块配置草稿中已有的模块"));
                continue;
            }
            if (!module.enabled()) {
                issues.add(issue("ALERT_POLICY_MODULE_DISABLED", target,
                        "不能为该 Blueprint 中要停用的模块建立告警策略",
                        "先启用该模块或改绑其他模块"));
                continue;
            }
            if (!policyCodes.add(input.policyCode())) {
                issues.add(issue("ALERT_POLICY_DUPLICATE_CODE", target,
                        "同一草稿内策略编码不能重复", "合并重复策略"));
                continue;
            }
            derived.add(derive(ordinal, input, module));
        }
        issues.sort(Comparator.comparing(DraftIssue::code).thenComparing(DraftIssue::target));

        String desired = desiredDigest(derived);
        String sourceSnapshot = sourceSnapshot(authority, modules);
        String status = issues.isEmpty() ? "READY" : "BLOCKED";
        String previewDigest = digest("ALERT_POLICY_PREVIEW", AlertPolicyDraftModels.SCHEMA_VERSION,
                AlertPolicyDraftModels.ALGORITHM_VERSION, status, sourceSnapshot,
                modules.snapshotDigest(), authority.authoritySnapshotDigest(), desired,
                issueDigest(issues));
        DraftPreview preview = new DraftPreview(AlertPolicyDraftModels.SCHEMA_VERSION,
                AlertPolicyDraftModels.ALGORITHM_VERSION, status, request.factoryId(),
                DEPENDENCIES, request, derived, sourceSnapshot, modules.snapshotDigest(),
                authority.authoritySnapshotDigest(), desired, previewDigest, issues,
                0, 0, 0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED");
        return new Evaluation(preview, authority, modules);
    }

    private BlueprintAuthority resolveBlueprint(String factoryId, String blueprintId,
                                                long blueprintVersion, List<DraftIssue> issues) {
        BlueprintResponse response = canonicalBlueprints.current(factoryId);
        if (response == null || !blueprintId.equals(response.getId())
                || !Objects.equals(Math.toIntExact(blueprintVersion), response.getVersion())
                || !("DRAFT".equals(response.getLifecycleState())
                     || "VALIDATED".equals(response.getLifecycleState()))) {
            issues.add(issue("ALERT_POLICY_BLUEPRINT_LINEAGE_MISMATCH", "blueprintId",
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
            issues.add(issue("ALERT_POLICY_CAPABILITY_NOT_READY", "capability",
                    "alerts 或 modules 目录依赖尚未就绪",
                    "补齐模块配置证据后重新编译 Blueprint"));
        }
        Set<String> refs = capability == null || capability.getAuthorityRefs() == null
                ? Set.of() : Set.copyOf(capability.getAuthorityRefs());
        if (response.getCapabilityCatalogVersion() == null
                || response.getCapabilityCatalogVersion().isBlank()
                || !isDigest(response.getCapabilityCatalogDigest()) || refs.isEmpty()) {
            issues.add(issue("ALERT_POLICY_AUTHORITY_CATALOG_INVALID", "capabilityCatalog",
                    "服务端能力目录版本、摘要或告警依据无效",
                    "部署带 authority refs 的版本化能力目录"));
        }
        String authoritySnapshot = digest("ALERT_POLICY_AUTHORITY",
                response.getCapabilityCatalogVersion(), response.getCapabilityCatalogDigest(),
                refs.stream().sorted().reduce("", (left, right) -> left + "|" + right));
        return new BlueprintAuthority(response.getCapabilityCatalogVersion(),
                response.getCapabilityCatalogDigest(), refs, authoritySnapshot,
                digestBlueprint(response));
    }

    private ModuleAuthority resolveModules(DraftRequest request, List<DraftIssue> issues) {
        ModuleConfigPin pin = request.moduleConfig();
        if (pin == null || pin.draftId().isBlank() || !isDigest(pin.desiredDigest())) {
            issues.add(issue("ALERT_POLICY_MODULE_PIN_INVALID", "moduleConfig",
                    "告警策略必须锁定同一 Blueprint 的模块配置草稿",
                    "选择当前 Blueprint 的模块配置草稿并刷新摘要"));
            return ModuleAuthority.none();
        }
        ModuleConfigDraft parent = moduleDrafts
                .findByIdAndFactoryId(pin.draftId(), request.factoryId()).orElse(null);
        if (!sameLineage(parent, request, pin.desiredDigest())) {
            issues.add(issue("ALERT_POLICY_MODULE_CONFIG_DRIFT", "moduleConfig",
                    "模块配置草稿不属于当前 Blueprint 或摘要已变化",
                    "重新读取并选择当前模块配置草稿"));
            return ModuleAuthority.none();
        }
        try {
            return moduleAuthority(parent,
                    moduleCommand.readBack(request.factoryId(), pin.draftId()));
        } catch (RuntimeException exception) {
            issues.add(issue("ALERT_POLICY_MODULE_READBACK_FAILED", "moduleConfig",
                    "模块配置草稿逐字段回读失败", "修复模块配置草稿后重新预览"));
            return ModuleAuthority.none();
        }
    }

    private ModuleAuthority resolveStoredModules(AlertPolicyDraft parent, List<DraftIssue> issues) {
        ModuleConfigDraft stored = moduleDrafts.findByIdAndFactoryId(
                parent.getModuleConfigDraftId(), parent.getFactoryId()).orElse(null);
        if (stored == null
                || !Objects.equals(parent.getModuleConfigDesiredDigest(), stored.getDesiredDigest())
                || !parent.getBlueprintId().equals(stored.getBlueprintId())
                || !Objects.equals(parent.getBlueprintVersion(), stored.getBlueprintVersion())
                || !parent.getSourceDigest().equals(stored.getSourceDigest())) {
            issues.add(issue("ALERT_POLICY_MODULE_CONFIG_DRIFT", "moduleConfig",
                    "已保存的模块配置 pin 已漂移", "重新生成告警策略草稿"));
            return ModuleAuthority.none();
        }
        try {
            return moduleAuthority(stored,
                    moduleCommand.readBack(parent.getFactoryId(), stored.getId()));
        } catch (RuntimeException exception) {
            issues.add(issue("ALERT_POLICY_MODULE_READBACK_FAILED", "moduleConfig",
                    "已保存的模块配置草稿逐字段回读失败", "修复模块配置草稿后重新生成草稿"));
            return ModuleAuthority.none();
        }
    }

    private boolean sameLineage(ModuleConfigDraft parent, DraftRequest request,
                                String desiredDigest) {
        return parent != null && "DRAFT".equals(parent.getStatus())
                && Boolean.TRUE.equals(parent.getDependencyClosureComplete())
                && request.blueprintId().equals(parent.getBlueprintId())
                && Objects.equals(request.blueprintVersion(), parent.getBlueprintVersion())
                && request.sourceDigest().equals(parent.getSourceDigest())
                && desiredDigest.equals(parent.getDesiredDigest());
    }

    private ModuleAuthority moduleAuthority(ModuleConfigDraft parent,
                                            ModuleConfigDraftModels.DraftReadback readback) {
        Map<String, ModuleTarget> modules = new LinkedHashMap<>();
        List<String> values = new ArrayList<>(List.of(readback.schemaVersion(),
                readback.factoryId(), readback.draftId(), readback.status(),
                parent.getDesiredDigest()));
        for (ModuleConfigDraftModels.ResolvedModule module : readback.modules()) {
            modules.putIfAbsent(module.moduleId(), new ModuleTarget(module.moduleId(),
                    module.label(), module.desiredEnabled()));
            values.add(module.moduleId() + "|" + module.label() + "|" + module.desiredEnabled());
        }
        return new ModuleAuthority(parent.getId(), parent.getDesiredDigest(),
                digest(values.toArray(String[]::new)), Map.copyOf(modules));
    }

    private boolean validatePolicy(PolicyInput input, String target, List<DraftIssue> issues) {
        if (input == null || isBlankOrTooLong(input.policyCode(), 60)
                || !input.policyCode().matches("[A-Z0-9][A-Z0-9_.-]{0,59}")
                || isBlankOrTooLong(input.title(), 200)
                || isBlankOrTooLong(input.moduleId(), 60)
                || input.metric() == null || input.comparison() == null
                || input.severity() == null
                || isBlankOrTooLong(input.changeReason(), 500)) {
            issues.add(issue("ALERT_POLICY_FIELDS_REQUIRED", target,
                    "策略编码、标题、模块、指标、比较方式、严重度和变更原因均必填",
                    "补齐 typed 策略字段"));
            return false;
        }
        boolean valid = true;
        boolean range = input.comparison() == Comparison.OUTSIDE_RANGE;
        if (!validDecimal(input.thresholdValue())) {
            issues.add(issue("ALERT_POLICY_THRESHOLD_INVALID", target,
                    "阈值必须为 typed decimal", "修正阈值"));
            valid = false;
        }
        if (range != (input.upperThresholdValue() != null)) {
            issues.add(issue("ALERT_POLICY_RANGE_SHAPE_INVALID", target,
                    "只有区间比较可以并且必须提供上界", "修正比较方式或上界"));
            valid = false;
        } else if (range && (!validDecimal(input.upperThresholdValue())
                || input.thresholdValue() == null
                || input.thresholdValue().compareTo(input.upperThresholdValue()) >= 0)) {
            issues.add(issue("ALERT_POLICY_RANGE_SHAPE_INVALID", target,
                    "区间下界必须严格小于上界", "修正区间边界"));
            valid = false;
        }
        if (input.evaluationWindowMinutes() == null || input.evaluationWindowMinutes() < 1
                || input.evaluationWindowMinutes() > 1440
                || input.consecutiveBreaches() == null || input.consecutiveBreaches() < 1
                || input.consecutiveBreaches() > 20
                || input.suppressionWindowMinutes() == null
                || input.suppressionWindowMinutes() < 0
                || input.suppressionWindowMinutes() > 1440) {
            issues.add(issue("ALERT_POLICY_WINDOW_INVALID", target,
                    "评估窗口必须为 1–1440 分钟，连续次数 1–20，抑制窗口 0–1440 分钟",
                    "修正窗口与次数"));
            valid = false;
        }
        if (input.severity() == Severity.CRITICAL && input.suppressionWindowMinutes() != null
                && input.suppressionWindowMinutes() > 0) {
            issues.add(issue("ALERT_POLICY_CRITICAL_CANNOT_BE_SUPPRESSED", target,
                    "关键告警不能设置抑制窗口", "把抑制窗口改为 0 或降低严重度"));
            valid = false;
        }
        if (input.recipientRoles().isEmpty() || input.recipientRoles().size() > 4
                || new HashSet<>(input.recipientRoles()).size() != input.recipientRoles().size()) {
            issues.add(issue("ALERT_POLICY_RECIPIENTS_INVALID", target,
                    "接收角色必须为非空、不重复且不超过 4 个的封闭枚举",
                    "选择有效接收角色"));
            valid = false;
        } else if (input.severity() == Severity.CRITICAL
                && !input.recipientRoles().contains(RecipientRole.QUALITY_MANAGER)) {
            issues.add(issue("ALERT_POLICY_CRITICAL_NEEDS_QUALITY_MANAGER", target,
                    "关键告警的接收角色必须包含质量负责人", "增加质量负责人接收角色"));
            valid = false;
        }
        return valid;
    }

    private DerivedPolicy derive(int ordinal, PolicyInput input, ModuleTarget module) {
        List<RecipientRole> recipients = input.recipientRoles().stream()
                .sorted(Comparator.comparing(Enum::name)).toList();
        Severity severity = input.severity();
        MetricDimension dimension = AlertPolicyDraftModels.dimension(input.metric());
        return new DerivedPolicy(ordinal, input.policyCode(), input.title(), module.moduleId(),
                module.label(), moduleSnapshot(module), input.metric(), dimension,
                AlertPolicyDraftModels.canonicalUnit(dimension), input.comparison(),
                input.thresholdValue(), input.upperThresholdValue(),
                input.evaluationWindowMinutes(), input.consecutiveBreaches(), severity,
                recipients, input.suppressionWindowMinutes(),
                AlertPolicyDraftModels.escalationRole(severity),
                AlertPolicyDraftModels.acknowledgeSlaMinutes(severity),
                AlertPolicyDraftModels.autoEscalateAfterMinutes(severity),
                AlertPolicyDraftModels.twoPersonAcknowledgeRequired(severity),
                input.changeReason());
    }

    private AlertPolicyDraftItem toRow(AlertPolicyDraft parent, DerivedPolicy value) {
        return AlertPolicyDraftItem.builder()
                .id(UUID.randomUUID().toString()).draftId(parent.getId())
                .factoryId(parent.getFactoryId()).ordinal(value.ordinal())
                .policyCode(value.policyCode()).title(value.title()).moduleId(value.moduleId())
                .moduleLabel(value.moduleLabel())
                .moduleSnapshotDigest(value.moduleSnapshotDigest())
                .metric(value.metric().name())
                .metricDimension(value.metricDimension().name())
                .canonicalUnit(value.canonicalUnit()).comparison(value.comparison().name())
                .thresholdValue(value.thresholdValue())
                .upperThresholdValue(value.upperThresholdValue())
                .evaluationWindowMinutes(value.evaluationWindowMinutes())
                .consecutiveBreaches(value.consecutiveBreaches())
                .severity(value.severity().name())
                .recipientRoles(joinEnums(value.recipientRoles()))
                .suppressionWindowMinutes(value.suppressionWindowMinutes())
                .escalationRole(value.escalationRole().name())
                .acknowledgeSlaMinutes(value.acknowledgeSlaMinutes())
                .autoEscalateAfterMinutes(value.autoEscalateAfterMinutes())
                .twoPersonAcknowledgeRequired(value.twoPersonAcknowledgeRequired())
                .changeReason(value.changeReason()).build();
    }

    private DerivedPolicy fromRow(AlertPolicyDraftItem row) {
        return new DerivedPolicy(row.getOrdinal(), row.getPolicyCode(), row.getTitle(),
                row.getModuleId(), row.getModuleLabel(), row.getModuleSnapshotDigest(),
                Metric.valueOf(row.getMetric()),
                MetricDimension.valueOf(row.getMetricDimension()), row.getCanonicalUnit(),
                Comparison.valueOf(row.getComparison()), row.getThresholdValue(),
                row.getUpperThresholdValue(), row.getEvaluationWindowMinutes(),
                row.getConsecutiveBreaches(), Severity.valueOf(row.getSeverity()),
                parseEnums(row.getRecipientRoles(), RecipientRole::valueOf),
                row.getSuppressionWindowMinutes(),
                EscalationRole.valueOf(row.getEscalationRole()),
                row.getAcknowledgeSlaMinutes(), row.getAutoEscalateAfterMinutes(),
                Boolean.TRUE.equals(row.getTwoPersonAcknowledgeRequired()),
                row.getChangeReason());
    }

    private void authorize(DraftRequest request, AuthenticatedOperator operator) {
        if (operator == null || (operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER && operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.PLATFORM_ADMIN) || operator.actor().id() != request.operatorId()
                || !request.factoryId().equals(operator.factoryId())) {
            throw invalid(403, "操作人和工厂范围不一致",
                    "ALERT_POLICY_OPERATOR_SCOPE_MISMATCH");
        }
        if (!operator.permissions().contains(REQUIRED_PERMISSION)) {
            throw invalid(403, "缺少告警策略草稿权限", "ALERT_POLICY_PERMISSION_REQUIRED");
        }
        Optional<User> member = users.findByIdAndFactoryId(operator.operatorId(),
                operator.factoryId());
        if (member.filter(user -> Boolean.TRUE.equals(user.getIsActive())).isEmpty()) {
            throw invalid(403, "操作人不是当前工厂有效成员", "ALERT_POLICY_MEMBER_REQUIRED");
        }
    }

    private void validateEnvelope(DraftRequest request, boolean materialize) {
        if (request == null || request.factoryId().isBlank() || request.factoryId().length() > 50
                || request.blueprintId().isBlank() || request.blueprintId().length() > 100
                || request.blueprintVersion() <= 0 || !isDigest(request.sourceDigest())
                || request.policies().isEmpty() || request.policies().size() > MAX_POLICIES) {
            throw invalid(400, "Blueprint envelope 或告警策略数量无效",
                    "ALERT_POLICY_SCOPE_REQUIRED");
        }
        if (materialize && (request.operatorId() <= 0 || request.idempotencyKey().isBlank()
                || request.idempotencyKey().length() > 128
                || !isDigest(request.confirmedPreviewDigest()))) {
            throw invalid(400, "操作人、幂等键或确认摘要无效",
                    "ALERT_POLICY_MATERIALIZE_ENVELOPE_INVALID");
        }
    }

    private DraftReceipt receipt(AlertPolicyDraft row, boolean replayed, int moduleWrites,
                                 int persistedRows) {
        return new DraftReceipt(AlertPolicyDraftModels.SCHEMA_VERSION, row.getId(),
                row.getFactoryId(), row.getId(), row.getStatus(), row.getSourceSnapshotDigest(),
                row.getDesiredDigest(), row.getPreviewDigest(), row.getRequestDigest(),
                row.getPolicyCount(), row.getEvidenceCount(),
                row.getLockVersion() == null ? 0L : row.getLockVersion(), replayed,
                moduleWrites, replayed ? 1 : moduleWrites, persistedRows,
                0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED", "ARCHIVE_DRAFT_ONLY");
    }

    private boolean sameEvidence(AlertPolicyDraftEvidence row, BlueprintAuthority authority) {
        return AUTHORITY_CODE.equals(row.getAuthorityCode())
                && authority.catalogVersion().equals(row.getCatalogVersion())
                && authority.catalogDigest().equals(row.getCatalogDigest())
                && authorityEvidenceDigest(authority).equals(row.getAuthoritySnapshotDigest());
    }

    private String sourceSnapshot(BlueprintAuthority authority, ModuleAuthority modules) {
        return digest("ALERT_POLICY_SOURCE", authority.blueprintSnapshotDigest(),
                modules.snapshotDigest(), authority.authoritySnapshotDigest());
    }

    private String moduleSnapshot(ModuleTarget module) {
        return digest("ALERT_POLICY_MODULE", module.moduleId(), module.label(),
                String.valueOf(module.enabled()));
    }

    private String desiredDigest(List<DerivedPolicy> policies) {
        List<String> values = new ArrayList<>(List.of(AlertPolicyDraftModels.SCHEMA_VERSION,
                AlertPolicyDraftModels.ALGORITHM_VERSION));
        policies.stream().sorted(Comparator.comparingInt(DerivedPolicy::ordinal))
                .forEach(value -> values.add(value.ordinal() + "|" + value.policyCode() + "|"
                        + value.title() + "|" + value.moduleId() + "|" + value.moduleLabel() + "|"
                        + value.moduleSnapshotDigest() + "|" + value.metric() + "|"
                        + value.metricDimension() + "|" + value.canonicalUnit() + "|"
                        + value.comparison() + "|" + decimal(value.thresholdValue()) + "|"
                        + decimal(value.upperThresholdValue()) + "|"
                        + value.evaluationWindowMinutes() + "|" + value.consecutiveBreaches()
                        + "|" + value.severity() + "|" + joinEnums(value.recipientRoles()) + "|"
                        + value.suppressionWindowMinutes() + "|" + value.escalationRole() + "|"
                        + value.acknowledgeSlaMinutes() + "|" + value.autoEscalateAfterMinutes()
                        + "|" + value.twoPersonAcknowledgeRequired() + "|"
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
                    .contains("uk_alert_policy_draft_idempotency")) return true;
        }
        return false;
    }

    /**
     * Does this value survive a round trip through {@code NUMERIC(18,6)}?
     *
     * <p>The obvious check -- precision <= 18 and scale <= 6 -- is wrong, and wrong in the
     * direction that matters: {@code NUMERIC(P,S)} stores at most {@code P - S} digits before
     * the point, so it accepts 12 integer digits, not 18. A value the preview called READY was
     * then refused by the column at materialize time, surfacing as a bare 500 rather than a
     * named issue, and breaking the rule this platform is built on -- that preview and the
     * database judge the same request the same way.</p>
     *
     * <p>Negative scale is the second hole: {@code 1E+20} arrives from JSON with precision 1 and
     * scale -20, so it passed both halves of the old check while being a 21-digit integer.</p>
     */
    private boolean validDecimal(BigDecimal value) {
        if (value == null || value.scale() > 6) {
            return false;
        }
        BigDecimal plain = value.stripTrailingZeros();
        if (plain.scale() < 0) {
            plain = plain.setScale(0);
        }
        return plain.precision() - plain.scale() <= 12;
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
                              ModuleAuthority modules) {
    }

    private record BlueprintAuthority(String catalogVersion, String catalogDigest,
                                      Set<String> authorityRefs, String authoritySnapshotDigest,
                                      String blueprintSnapshotDigest) {
        static BlueprintAuthority missing() {
            return new BlueprintAuthority("", digestStatic("MISSING_CATALOG"), Set.of(),
                    digestStatic("MISSING_AUTHORITY"), digestStatic("MISSING_BLUEPRINT"));
        }
    }

    private record ModuleAuthority(String draftId, String desiredDigest, String snapshotDigest,
                                   Map<String, ModuleTarget> modules) {
        static ModuleAuthority none() {
            return new ModuleAuthority(null, null, digestStatic("NO_MODULE_CONFIG_DRAFT"),
                    Map.of());
        }
    }

    private record ModuleTarget(String moduleId, String label, boolean enabled) {
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
