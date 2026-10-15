package com.cretas.aims.service.formschemadraft;

import com.cretas.aims.blueprint.capabilityadapter.FactoryCapabilityAdapterContracts;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.CapabilityView;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintService;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.formschemadraft.FormSchemaDraft;
import com.cretas.aims.entity.formschemadraft.FormSchemaDraftEvidence;
import com.cretas.aims.entity.formschemadraft.FormSchemaDraftField;
import com.cretas.aims.entity.formschemadraft.FormSchemaDraftForm;
import com.cretas.aims.entity.moduleconfig.ModuleConfigDraft;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.formschemadraft.FormSchemaDraftEvidenceRepository;
import com.cretas.aims.repository.formschemadraft.FormSchemaDraftFieldRepository;
import com.cretas.aims.repository.formschemadraft.FormSchemaDraftFormRepository;
import com.cretas.aims.repository.formschemadraft.FormSchemaDraftRepository;
import com.cretas.aims.repository.moduleconfig.ModuleConfigDraftRepository;
import com.cretas.aims.service.formschemadraft.FormSchemaDraftModels.AuthenticatedOperator;
import com.cretas.aims.service.formschemadraft.FormSchemaDraftModels.DerivedField;
import com.cretas.aims.service.formschemadraft.FormSchemaDraftModels.DerivedForm;
import com.cretas.aims.service.formschemadraft.FormSchemaDraftModels.DraftIssue;
import com.cretas.aims.service.formschemadraft.FormSchemaDraftModels.DraftPreview;
import com.cretas.aims.service.formschemadraft.FormSchemaDraftModels.DraftReadback;
import com.cretas.aims.service.formschemadraft.FormSchemaDraftModels.DraftReceipt;
import com.cretas.aims.service.formschemadraft.FormSchemaDraftModels.DraftRequest;
import com.cretas.aims.service.formschemadraft.FormSchemaDraftModels.FieldInput;
import com.cretas.aims.service.formschemadraft.FormSchemaDraftModels.FieldType;
import com.cretas.aims.service.formschemadraft.FormSchemaDraftModels.FormInput;
import com.cretas.aims.service.formschemadraft.FormSchemaDraftModels.ModuleConfigPin;
import com.cretas.aims.service.formschemadraft.FormSchemaDraftModels.RecoveryPlan;
import com.cretas.aims.service.formschemadraft.FormSchemaDraftModels.ResponsibleRole;
import com.cretas.aims.service.formschemadraft.FormSchemaDraftModels.RetentionClass;
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
 * Module-owned dynamic form schema DRAFT command.
 *
 * <p>The immutable capability catalog v2 dependency is {@code modules}: every form binds to a
 * module that a same-factory, same-lineage, readback-ready module-config DRAFT actually enables.
 * A form on a module the factory is switching off is blocked.</p>
 *
 * <p>The legacy {@code FormTemplate} / {@code FormTemplateVersion} model stores free TEXT
 * {@code schema_json} and {@code ui_schema_json} and its service writes formal templates. It is
 * deliberately unreachable from here. Fields are typed rows with per-type constraints instead, and
 * {@code form-schema-policy/v1} derives retention days, audit-trail requirement and post-submit
 * immutability from the retention class alone.</p>
 *
 * <p>Formal form templates, template versions, submissions and records are never written.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class FormSchemaDraftCommand {
    public static final String CAPABILITY_ID = "forms";
    public static final String REQUIRED_PERMISSION = "form:schema:draft";
    private static final String AUTHORITY_CODE = "FORM_SCHEMA_CATALOG_AUTHORITY";
    private static final List<String> DEPENDENCIES =
            FactoryCapabilityAdapterContracts.dependenciesOf(CAPABILITY_ID);
    private static final int MAX_FORMS = 200;
    private static final int MAX_FIELDS = 200;
    private static final int MAX_OPTIONS = 50;
    /**
     * Width of form_schema_draft_fields.option_codes (V20261103_22), which stores the codes
     * pipe-joined. MAX_OPTIONS alone does not bound it: 50 codes of the 40 characters the code
     * pattern allows, plus 49 separators, is 2049 characters into a 2000-character column -- a
     * preview that says READY and a materialize that fails on the column.
     */
    private static final int MAX_OPTION_CODES_WIDTH = 2000;
    private static final Set<FieldType> NUMERIC_TYPES =
            Set.of(FieldType.INTEGER, FieldType.DECIMAL, FieldType.QUANTITY);
    private static final Set<FieldType> SELECT_TYPES =
            Set.of(FieldType.SINGLE_SELECT, FieldType.MULTI_SELECT);

    private final FormSchemaDraftRepository drafts;
    private final FormSchemaDraftFormRepository forms;
    private final FormSchemaDraftFieldRepository fields;
    private final FormSchemaDraftEvidenceRepository evidence;
    private final ModuleConfigDraftRepository moduleDrafts;
    private final ModuleConfigDraftCommand moduleCommand;
    private final CanonicalFactoryBlueprintService canonicalBlueprints;
    private final UserRepository users;

    public FormSchemaDraftCommand(
            FormSchemaDraftRepository drafts,
            FormSchemaDraftFormRepository forms,
            FormSchemaDraftFieldRepository fields,
            FormSchemaDraftEvidenceRepository evidence,
            ModuleConfigDraftRepository moduleDrafts,
            ModuleConfigDraftCommand moduleCommand,
            CanonicalFactoryBlueprintService canonicalBlueprints,
            UserRepository users) {
        this.drafts = drafts;
        this.forms = forms;
        this.fields = fields;
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
        Optional<FormSchemaDraft> prior = drafts
                .findByFactoryIdAndIdempotencyKey(request.factoryId(), request.idempotencyKey());
        if (prior.isPresent()) {
            if (!requestDigest.equals(prior.get().getRequestDigest())) {
                throw invalid(409, "幂等键对应不同表单模式请求",
                        "FORM_SCHEMA_IDEMPOTENCY_CONFLICT");
            }
            readBack(request.factoryId(), prior.get().getId());
            return receipt(prior.get(), true, 0, 0);
        }

        Evaluation evaluation = evaluate(request);
        DraftPreview plan = evaluation.preview();
        if (!"READY".equals(plan.status())) {
            throw invalid(409, "表单模式预览被阻断", "FORM_SCHEMA_PREVIEW_BLOCKED");
        }
        if (!request.confirmedPreviewDigest().equals(plan.previewDigest())) {
            throw invalid(409, "表单模式确认摘要已漂移", "FORM_SCHEMA_PREVIEW_DRIFT");
        }

        BlueprintAuthority authority = evaluation.authority();
        ModuleAuthority modules = evaluation.modules();
        int fieldTotal = plan.forms().stream().mapToInt(form -> form.fields().size()).sum();
        FormSchemaDraft parent = FormSchemaDraft.builder()
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
                .formCount(plan.forms().size())
                .fieldCount(fieldTotal)
                .evidenceCount(1)
                .dependencyBasis(FormSchemaDraftModels.DEPENDENCY_BASIS)
                .algorithmVersion(FormSchemaDraftModels.ALGORITHM_VERSION)
                .dependencyClosureComplete(true)
                .status("DRAFT")
                .build();
        FormSchemaDraft saved;
        try {
            saved = drafts.saveAndFlush(parent);
        } catch (DataIntegrityViolationException exception) {
            if (!isIdempotencyRace(exception)) throw exception;
            throw invalid(409, "表单模式幂等键发生并发竞争", "FORM_SCHEMA_IDEMPOTENCY_RACE");
        }

        List<FormSchemaDraftForm> formRows = new ArrayList<>();
        List<FormSchemaDraftField> fieldRows = new ArrayList<>();
        for (DerivedForm form : plan.forms()) {
            String formId = UUID.randomUUID().toString();
            formRows.add(toFormRow(saved, formId, form));
            for (DerivedField field : form.fields()) {
                fieldRows.add(toFieldRow(saved, formId, field));
            }
        }
        forms.saveAllAndFlush(formRows);
        fields.saveAllAndFlush(fieldRows);
        evidence.saveAndFlush(FormSchemaDraftEvidence.builder()
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
            throw invalid(409, "表单模式回读摘要不一致", "FORM_SCHEMA_READBACK_MISMATCH");
        }
        return receipt(saved, false, 1, 2 + formRows.size() + fieldRows.size());
    }

    @Transactional(readOnly = true)
    public DraftReadback readBack(String factoryId, String draftId) {
        FormSchemaDraft parent = drafts.findByIdAndFactoryId(draftId, factoryId)
                .orElseThrow(() -> invalid(404, "当前工厂没有该表单模式草稿",
                        "FORM_SCHEMA_NOT_FOUND"));
        List<FormSchemaDraftForm> formRows = forms
                .findByDraftIdAndFactoryIdOrderByOrdinal(draftId, factoryId);
        List<FormSchemaDraftField> fieldRows = fields
                .findByDraftIdAndFactoryIdOrderByFormIdAscOrdinalAsc(draftId, factoryId);
        List<FormSchemaDraftEvidence> evidenceRows = evidence
                .findByDraftIdAndFactoryIdOrderByAuthorityCode(draftId, factoryId);
        if (!"DRAFT".equals(parent.getStatus())
                || !Boolean.TRUE.equals(parent.getDependencyClosureComplete())
                || !FormSchemaDraftModels.DEPENDENCY_BASIS.equals(parent.getDependencyBasis())
                || !FormSchemaDraftModels.ALGORITHM_VERSION.equals(parent.getAlgorithmVersion())
                || parent.getFormCount() == null || parent.getFormCount() != formRows.size()
                || parent.getFieldCount() == null || parent.getFieldCount() != fieldRows.size()
                || parent.getEvidenceCount() == null || parent.getEvidenceCount() != 1
                || evidenceRows.size() != 1) {
            throw invalid(409, "表单模式父子行或依赖闭包不完整",
                    "FORM_SCHEMA_READBACK_INVARIANT_BROKEN");
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
            throw invalid(409, "表单模式上游 Blueprint 或模块配置草稿已漂移",
                    "FORM_SCHEMA_UPSTREAM_DRIFT");
        }
        String currentSourceSnapshot = sourceSnapshot(authority, modules);
        if (!currentSourceSnapshot.equals(parent.getSourceSnapshotDigest())) {
            throw invalid(409, "表单模式源快照摘要已漂移",
                    "FORM_SCHEMA_SOURCE_SNAPSHOT_DRIFT");
        }
        if (!sameEvidence(evidenceRows.get(0), authority)) {
            throw invalid(409, "表单模式目录依据摘要不一致", "FORM_SCHEMA_EVIDENCE_MISMATCH");
        }

        Map<String, List<FormSchemaDraftField>> byForm = new LinkedHashMap<>();
        for (FormSchemaDraftField row : fieldRows) {
            byForm.computeIfAbsent(row.getFormId(), key -> new ArrayList<>()).add(row);
        }
        List<DerivedForm> derived = new ArrayList<>();
        for (FormSchemaDraftForm row : formRows) {
            List<FormSchemaDraftField> ownFields = byForm.getOrDefault(row.getId(), List.of())
                    .stream().sorted(Comparator.comparing(FormSchemaDraftField::getOrdinal))
                    .toList();
            if (row.getFieldCount() == null || row.getFieldCount() != ownFields.size()) {
                throw invalid(409, "表单字段数量与父行不一致",
                        "FORM_SCHEMA_READBACK_INVARIANT_BROKEN");
            }
            ModuleTarget module = modules.modules().get(row.getModuleId());
            if (module == null || !module.enabled()
                    || !Objects.equals(module.label(), row.getModuleLabel())
                    || !moduleSnapshot(module).equals(row.getModuleSnapshotDigest())) {
                throw invalid(409, "表单绑定的模块已停用或快照已漂移",
                        "FORM_SCHEMA_MODULE_DRIFT");
            }
            RetentionClass retentionClass = RetentionClass.valueOf(row.getRetentionClass());
            if (row.getRetentionDays() == null
                    || row.getRetentionDays() != FormSchemaDraftModels.retentionDays(retentionClass)
                    || !Objects.equals(row.getAuditTrailRequired(),
                            FormSchemaDraftModels.auditTrailRequired(retentionClass))
                    || !Objects.equals(row.getImmutableAfterSubmit(),
                            FormSchemaDraftModels.immutableAfterSubmit(retentionClass))) {
                throw invalid(409, "表单留存与审计派生结论与服务端算法不一致",
                        "FORM_SCHEMA_POLICY_TAMPERED");
            }
            List<DerivedField> derivedFields = ownFields.stream().map(this::fromFieldRow).toList();
            if (!fieldIdentityDigest(derivedFields).equals(row.getFieldIdentityDigest())) {
                throw invalid(409, "表单字段身份摘要已漂移",
                        "FORM_SCHEMA_FIELD_IDENTITY_DRIFT");
            }
            derived.add(new DerivedForm(row.getOrdinal(), row.getFormCode(), row.getTitle(),
                    row.getModuleId(), row.getModuleLabel(), row.getModuleSnapshotDigest(),
                    retentionClass, row.getRetentionDays(),
                    Boolean.TRUE.equals(row.getAuditTrailRequired()),
                    Boolean.TRUE.equals(row.getImmutableAfterSubmit()),
                    ResponsibleRole.valueOf(row.getResponsibleRole()),
                    row.getFieldIdentityDigest(), derivedFields, row.getChangeReason()));
        }
        if (!desiredDigest(derived).equals(parent.getDesiredDigest())) {
            throw invalid(409, "表单模式逐字段回读摘要不一致", "FORM_SCHEMA_READBACK_MISMATCH");
        }
        return new DraftReadback(FormSchemaDraftModels.SCHEMA_VERSION, factoryId, draftId,
                parent.getStatus(), derived, parent.getSourceSnapshotDigest(),
                parent.getDesiredDigest(), 0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    @Transactional(readOnly = true)
    public RecoveryPlan recoveryPlan(String factoryId, String draftId,
                                     String expectedDesiredDigest) {
        FormSchemaDraft parent = drafts.findByIdAndFactoryId(draftId, factoryId)
                .orElseThrow(() -> invalid(404, "当前工厂没有该表单模式草稿",
                        "FORM_SCHEMA_NOT_FOUND"));
        if (!"DRAFT".equals(parent.getStatus()) || !isDigest(expectedDesiredDigest)
                || !expectedDesiredDigest.equals(parent.getDesiredDigest())) {
            throw invalid(409, "只能恢复摘要精确匹配的未发布表单模式草稿",
                    "FORM_SCHEMA_RECOVERY_NOT_ALLOWED");
        }
        return new RecoveryPlan(FormSchemaDraftModels.SCHEMA_VERSION, factoryId, draftId,
                expectedDesiredDigest, "ARCHIVE_DRAFT_ONLY",
                "只归档未发布表单模式草稿；不创建或修改正式表单模板、模板版本、提交记录或业务记录",
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    private Evaluation evaluate(DraftRequest request) {
        List<DraftIssue> issues = new ArrayList<>();
        BlueprintAuthority authority = resolveBlueprint(request.factoryId(), request.blueprintId(),
                request.blueprintVersion(), issues);
        ModuleAuthority modules = resolveModules(request, issues);
        List<DerivedForm> derived = new ArrayList<>();
        Set<String> formCodes = new HashSet<>();
        for (int ordinal = 0; ordinal < request.forms().size(); ordinal++) {
            FormInput input = request.forms().get(ordinal);
            String target = "forms[" + ordinal + "]";
            if (!validateForm(input, target, issues)) continue;
            ModuleTarget module = modules.modules().get(input.moduleId());
            if (module == null) {
                issues.add(issue("FORM_SCHEMA_MODULE_MISSING", target,
                        "表单必须绑定同一 readback-ready 模块配置草稿中的模块",
                        "重新选择模块配置草稿中已有的模块"));
                continue;
            }
            if (!module.enabled()) {
                issues.add(issue("FORM_SCHEMA_MODULE_DISABLED", target,
                        "不能为该 Blueprint 中要停用的模块建立表单",
                        "先启用该模块或改绑其他模块"));
                continue;
            }
            if (!formCodes.add(input.formCode())) {
                issues.add(issue("FORM_SCHEMA_DUPLICATE_FORM_CODE", target,
                        "同一草稿内表单编码不能重复", "合并重复表单"));
                continue;
            }
            List<DerivedField> derivedFields = new ArrayList<>();
            boolean fieldsValid = true;
            Set<String> fieldKeys = new HashSet<>();
            for (int index = 0; index < input.fields().size(); index++) {
                FieldInput field = input.fields().get(index);
                String fieldTarget = target + ".fields[" + index + "]";
                if (!validateField(field, input.retentionClass(), fieldTarget, issues)) {
                    fieldsValid = false;
                    continue;
                }
                if (!fieldKeys.add(field.fieldKey())) {
                    issues.add(issue("FORM_SCHEMA_DUPLICATE_FIELD_KEY", fieldTarget,
                            "同一表单内字段键不能重复", "合并重复字段"));
                    fieldsValid = false;
                    continue;
                }
                derivedFields.add(deriveField(index, field));
            }
            if (!fieldsValid) continue;
            if (input.retentionClass() == RetentionClass.FOOD_SAFETY_CRITICAL
                    && (derivedFields.stream().noneMatch(f -> f.fieldType() == FieldType.SIGNATURE)
                        || derivedFields.stream()
                                .noneMatch(f -> f.fieldType() == FieldType.DATETIME))) {
                issues.add(issue("FORM_SCHEMA_CRITICAL_FORM_NEEDS_SIGNATURE_AND_TIME", target,
                        "食品安全关键记录必须同时有签名字段和记录时间字段",
                        "补齐签名与记录时间字段"));
                continue;
            }
            derived.add(deriveForm(ordinal, input, module, derivedFields));
        }
        issues.sort(Comparator.comparing(DraftIssue::code).thenComparing(DraftIssue::target));

        String desired = desiredDigest(derived);
        String sourceSnapshot = sourceSnapshot(authority, modules);
        String status = issues.isEmpty() ? "READY" : "BLOCKED";
        String previewDigest = digest("FORM_SCHEMA_PREVIEW", FormSchemaDraftModels.SCHEMA_VERSION,
                FormSchemaDraftModels.ALGORITHM_VERSION, status, sourceSnapshot,
                modules.snapshotDigest(), authority.authoritySnapshotDigest(), desired,
                issueDigest(issues));
        DraftPreview preview = new DraftPreview(FormSchemaDraftModels.SCHEMA_VERSION,
                FormSchemaDraftModels.ALGORITHM_VERSION, status, request.factoryId(),
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
            issues.add(issue("FORM_SCHEMA_BLUEPRINT_LINEAGE_MISMATCH", "blueprintId",
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
            issues.add(issue("FORM_SCHEMA_CAPABILITY_NOT_READY", "capability",
                    "forms 或 modules 目录依赖尚未就绪",
                    "补齐模块配置证据后重新编译 Blueprint"));
        }
        Set<String> refs = capability == null || capability.getAuthorityRefs() == null
                ? Set.of() : Set.copyOf(capability.getAuthorityRefs());
        if (response.getCapabilityCatalogVersion() == null
                || response.getCapabilityCatalogVersion().isBlank()
                || !isDigest(response.getCapabilityCatalogDigest()) || refs.isEmpty()) {
            issues.add(issue("FORM_SCHEMA_AUTHORITY_CATALOG_INVALID", "capabilityCatalog",
                    "服务端能力目录版本、摘要或记录依据无效",
                    "部署带 authority refs 的版本化能力目录"));
        }
        String authoritySnapshot = digest("FORM_SCHEMA_AUTHORITY",
                response.getCapabilityCatalogVersion(), response.getCapabilityCatalogDigest(),
                refs.stream().sorted().reduce("", (left, right) -> left + "|" + right));
        return new BlueprintAuthority(response.getCapabilityCatalogVersion(),
                response.getCapabilityCatalogDigest(), refs, authoritySnapshot,
                digestBlueprint(response));
    }

    private ModuleAuthority resolveModules(DraftRequest request, List<DraftIssue> issues) {
        ModuleConfigPin pin = request.moduleConfig();
        if (pin == null || pin.draftId().isBlank() || !isDigest(pin.desiredDigest())) {
            issues.add(issue("FORM_SCHEMA_MODULE_PIN_INVALID", "moduleConfig",
                    "表单模式必须锁定同一 Blueprint 的模块配置草稿",
                    "选择当前 Blueprint 的模块配置草稿并刷新摘要"));
            return ModuleAuthority.none();
        }
        ModuleConfigDraft parent = moduleDrafts
                .findByIdAndFactoryId(pin.draftId(), request.factoryId()).orElse(null);
        if (!sameLineage(parent, request, pin.desiredDigest())) {
            issues.add(issue("FORM_SCHEMA_MODULE_CONFIG_DRIFT", "moduleConfig",
                    "模块配置草稿不属于当前 Blueprint 或摘要已变化",
                    "重新读取并选择当前模块配置草稿"));
            return ModuleAuthority.none();
        }
        try {
            return moduleAuthority(parent,
                    moduleCommand.readBack(request.factoryId(), pin.draftId()));
        } catch (RuntimeException exception) {
            issues.add(issue("FORM_SCHEMA_MODULE_READBACK_FAILED", "moduleConfig",
                    "模块配置草稿逐字段回读失败", "修复模块配置草稿后重新预览"));
            return ModuleAuthority.none();
        }
    }

    private ModuleAuthority resolveStoredModules(FormSchemaDraft parent, List<DraftIssue> issues) {
        ModuleConfigDraft stored = moduleDrafts.findByIdAndFactoryId(
                parent.getModuleConfigDraftId(), parent.getFactoryId()).orElse(null);
        if (stored == null
                || !Objects.equals(parent.getModuleConfigDesiredDigest(), stored.getDesiredDigest())
                || !parent.getBlueprintId().equals(stored.getBlueprintId())
                || !Objects.equals(parent.getBlueprintVersion(), stored.getBlueprintVersion())
                || !parent.getSourceDigest().equals(stored.getSourceDigest())) {
            issues.add(issue("FORM_SCHEMA_MODULE_CONFIG_DRIFT", "moduleConfig",
                    "已保存的模块配置 pin 已漂移", "重新生成表单模式草稿"));
            return ModuleAuthority.none();
        }
        try {
            return moduleAuthority(stored,
                    moduleCommand.readBack(parent.getFactoryId(), stored.getId()));
        } catch (RuntimeException exception) {
            issues.add(issue("FORM_SCHEMA_MODULE_READBACK_FAILED", "moduleConfig",
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

    private boolean validateForm(FormInput input, String target, List<DraftIssue> issues) {
        if (input == null || isBlankOrTooLong(input.formCode(), 60)
                || !input.formCode().matches("[A-Z0-9][A-Z0-9_.-]{0,59}")
                || isBlankOrTooLong(input.title(), 200)
                || isBlankOrTooLong(input.moduleId(), 60)
                || input.retentionClass() == null || input.responsibleRole() == null
                || input.fields().isEmpty() || input.fields().size() > MAX_FIELDS
                || isBlankOrTooLong(input.changeReason(), 500)) {
            issues.add(issue("FORM_SCHEMA_FORM_FIELDS_REQUIRED", target,
                    "表单编码、标题、模块、留存类别、责任角色、字段和变更原因均必填且字段数量有限",
                    "补齐 typed 表单字段"));
            return false;
        }
        return true;
    }

    private boolean validateField(FieldInput input, RetentionClass retentionClass, String target,
                                  List<DraftIssue> issues) {
        if (input == null || isBlankOrTooLong(input.fieldKey(), 60)
                || !input.fieldKey().matches("[a-z][a-z0-9_]{0,59}")
                || isBlankOrTooLong(input.label(), 200) || input.fieldType() == null
                || (input.helpText() != null && input.helpText().length() > 500)) {
            issues.add(issue("FORM_SCHEMA_FIELD_FIELDS_REQUIRED", target,
                    "字段键必须为小写蛇形标识，标签和类型必填",
                    "补齐 typed 字段定义"));
            return false;
        }
        FieldType type = input.fieldType();
        boolean numeric = NUMERIC_TYPES.contains(type);
        boolean select = SELECT_TYPES.contains(type);
        boolean valid = true;
        if ((type == FieldType.TEXT) != (input.maxLength() != null)) {
            issues.add(issue("FORM_SCHEMA_MAX_LENGTH_SHAPE_INVALID", target,
                    "只有文本字段可以并且必须声明最大长度", "修正最大长度"));
            valid = false;
        } else if (type == FieldType.TEXT
                && (input.maxLength() < 1 || input.maxLength() > 2000)) {
            issues.add(issue("FORM_SCHEMA_MAX_LENGTH_SHAPE_INVALID", target,
                    "文本最大长度必须为 1–2000", "修正最大长度"));
            valid = false;
        }
        if (numeric) {
            if (input.minValue() == null || input.maxValue() == null
                    || !validDecimal(input.minValue()) || !validDecimal(input.maxValue())
                    || input.minValue().compareTo(input.maxValue()) > 0) {
                issues.add(issue("FORM_SCHEMA_NUMERIC_RANGE_INVALID", target,
                        "数值字段必须声明有序的最小值和最大值", "修正数值范围"));
                valid = false;
            }
            boolean scaleRequired = type != FieldType.INTEGER;
            if (scaleRequired != (input.decimalScale() != null)
                    || (input.decimalScale() != null
                        && (input.decimalScale() < 0 || input.decimalScale() > 6))) {
                issues.add(issue("FORM_SCHEMA_DECIMAL_SCALE_INVALID", target,
                        "整数字段不能带小数位，小数与数量字段必须声明 0–6 位小数",
                        "修正小数位"));
                valid = false;
            }
        } else if (input.minValue() != null || input.maxValue() != null
                || input.decimalScale() != null) {
            issues.add(issue("FORM_SCHEMA_NUMERIC_SHAPE_FORBIDDEN", target,
                    "非数值字段不能携带数值范围或小数位", "删除数值约束"));
            valid = false;
        }
        if ((type == FieldType.QUANTITY) != !input.unitCode().isBlank()) {
            issues.add(issue("FORM_SCHEMA_UNIT_SHAPE_INVALID", target,
                    "只有数量字段可以并且必须声明单位编码", "修正单位编码"));
            valid = false;
        } else if (type == FieldType.QUANTITY
                && !input.unitCode().matches("[A-Za-z][A-Za-z0-9_/%-]{0,29}")) {
            issues.add(issue("FORM_SCHEMA_UNIT_SHAPE_INVALID", target,
                    "单位编码格式无效", "修正单位编码"));
            valid = false;
        }
        if (select != !input.optionCodes().isEmpty()) {
            issues.add(issue("FORM_SCHEMA_OPTION_SHAPE_INVALID", target,
                    "只有单选和多选字段可以并且必须声明选项编码", "修正选项编码"));
            valid = false;
        } else if (select && (input.optionCodes().size() > MAX_OPTIONS
                || String.join("|", input.optionCodes()).length() > MAX_OPTION_CODES_WIDTH
                || new HashSet<>(input.optionCodes()).size() != input.optionCodes().size()
                || input.optionCodes().stream()
                        .anyMatch(code -> !code.matches("[A-Z0-9][A-Z0-9_]{0,39}")))) {
            issues.add(issue("FORM_SCHEMA_OPTION_SHAPE_INVALID", target,
                    "选项编码必须为不重复的大写编码且不超过 50 个", "修正选项编码"));
            valid = false;
        }
        if (type == FieldType.SIGNATURE
                && !FormSchemaDraftModels.auditTrailRequired(retentionClass)) {
            issues.add(issue("FORM_SCHEMA_SIGNATURE_NEEDS_AUDIT_TRAIL", target,
                    "无审计轨迹的临时工作表不能采集签名", "改用需要审计轨迹的留存类别"));
            valid = false;
        }
        return valid;
    }

    private DerivedField deriveField(int ordinal, FieldInput input) {
        return new DerivedField(ordinal, input.fieldKey(), input.label(), input.fieldType(),
                input.required(), input.maxLength(), input.minValue(), input.maxValue(),
                input.decimalScale(), input.unitCode().isBlank() ? null : input.unitCode(),
                input.optionCodes(), input.helpText().isBlank() ? null : input.helpText());
    }

    private DerivedForm deriveForm(int ordinal, FormInput input, ModuleTarget module,
                                   List<DerivedField> derivedFields) {
        RetentionClass retentionClass = input.retentionClass();
        return new DerivedForm(ordinal, input.formCode(), input.title(), module.moduleId(),
                module.label(), moduleSnapshot(module), retentionClass,
                FormSchemaDraftModels.retentionDays(retentionClass),
                FormSchemaDraftModels.auditTrailRequired(retentionClass),
                FormSchemaDraftModels.immutableAfterSubmit(retentionClass),
                input.responsibleRole(), fieldIdentityDigest(derivedFields), derivedFields,
                input.changeReason());
    }

    private FormSchemaDraftForm toFormRow(FormSchemaDraft parent, String formId,
                                          DerivedForm value) {
        return FormSchemaDraftForm.builder().id(formId).draftId(parent.getId())
                .factoryId(parent.getFactoryId()).ordinal(value.ordinal())
                .formCode(value.formCode()).title(value.title()).moduleId(value.moduleId())
                .moduleLabel(value.moduleLabel())
                .moduleSnapshotDigest(value.moduleSnapshotDigest())
                .retentionClass(value.retentionClass().name())
                .retentionDays(value.retentionDays())
                .auditTrailRequired(value.auditTrailRequired())
                .immutableAfterSubmit(value.immutableAfterSubmit())
                .responsibleRole(value.responsibleRole().name())
                .fieldIdentityDigest(value.fieldIdentityDigest())
                .fieldCount(value.fields().size())
                .changeReason(value.changeReason()).build();
    }

    private FormSchemaDraftField toFieldRow(FormSchemaDraft parent, String formId,
                                            DerivedField value) {
        return FormSchemaDraftField.builder().id(UUID.randomUUID().toString()).formId(formId)
                .draftId(parent.getId()).factoryId(parent.getFactoryId())
                .ordinal(value.ordinal()).fieldKey(value.fieldKey()).label(value.label())
                .fieldType(value.fieldType().name()).required(value.required())
                .maxLength(value.maxLength()).minValue(value.minValue())
                .maxValue(value.maxValue()).decimalScale(value.decimalScale())
                .unitCode(value.unitCode())
                .optionCodes(value.optionCodes().isEmpty()
                        ? null : String.join("|", value.optionCodes()))
                .helpText(value.helpText()).build();
    }

    private DerivedField fromFieldRow(FormSchemaDraftField row) {
        return new DerivedField(row.getOrdinal(), row.getFieldKey(), row.getLabel(),
                FieldType.valueOf(row.getFieldType()), Boolean.TRUE.equals(row.getRequired()),
                row.getMaxLength(), row.getMinValue(), row.getMaxValue(), row.getDecimalScale(),
                row.getUnitCode(),
                row.getOptionCodes() == null || row.getOptionCodes().isBlank()
                        ? List.of() : List.of(row.getOptionCodes().split("\\|", -1)),
                row.getHelpText());
    }

    private void authorize(DraftRequest request, AuthenticatedOperator operator) {
        if (operator == null || (operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER && operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.PLATFORM_ADMIN) || operator.actor().id() != request.operatorId()
                || !request.factoryId().equals(operator.factoryId())) {
            throw invalid(403, "操作人和工厂范围不一致",
                    "FORM_SCHEMA_OPERATOR_SCOPE_MISMATCH");
        }
        if (!operator.permissions().contains(REQUIRED_PERMISSION)) {
            throw invalid(403, "缺少表单模式草稿权限", "FORM_SCHEMA_PERMISSION_REQUIRED");
        }
        Optional<User> member = users.findByIdAndFactoryId(operator.operatorId(),
                operator.factoryId());
        if (member.filter(user -> Boolean.TRUE.equals(user.getIsActive())).isEmpty()) {
            throw invalid(403, "操作人不是当前工厂有效成员", "FORM_SCHEMA_MEMBER_REQUIRED");
        }
    }

    private void validateEnvelope(DraftRequest request, boolean materialize) {
        if (request == null || request.factoryId().isBlank() || request.factoryId().length() > 50
                || request.blueprintId().isBlank() || request.blueprintId().length() > 100
                || request.blueprintVersion() <= 0 || !isDigest(request.sourceDigest())
                || request.forms().isEmpty() || request.forms().size() > MAX_FORMS) {
            throw invalid(400, "Blueprint envelope 或表单数量无效",
                    "FORM_SCHEMA_SCOPE_REQUIRED");
        }
        if (materialize && (request.operatorId() <= 0 || request.idempotencyKey().isBlank()
                || request.idempotencyKey().length() > 128
                || !isDigest(request.confirmedPreviewDigest()))) {
            throw invalid(400, "操作人、幂等键或确认摘要无效",
                    "FORM_SCHEMA_MATERIALIZE_ENVELOPE_INVALID");
        }
    }

    private DraftReceipt receipt(FormSchemaDraft row, boolean replayed, int moduleWrites,
                                 int persistedRows) {
        return new DraftReceipt(FormSchemaDraftModels.SCHEMA_VERSION, row.getId(),
                row.getFactoryId(), row.getId(), row.getStatus(), row.getSourceSnapshotDigest(),
                row.getDesiredDigest(), row.getPreviewDigest(), row.getRequestDigest(),
                row.getFormCount(), row.getFieldCount(), row.getEvidenceCount(),
                row.getLockVersion() == null ? 0L : row.getLockVersion(), replayed,
                moduleWrites, replayed ? 1 : moduleWrites, persistedRows,
                0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED", "ARCHIVE_DRAFT_ONLY");
    }

    private boolean sameEvidence(FormSchemaDraftEvidence row, BlueprintAuthority authority) {
        return AUTHORITY_CODE.equals(row.getAuthorityCode())
                && authority.catalogVersion().equals(row.getCatalogVersion())
                && authority.catalogDigest().equals(row.getCatalogDigest())
                && authorityEvidenceDigest(authority).equals(row.getAuthoritySnapshotDigest());
    }

    private String sourceSnapshot(BlueprintAuthority authority, ModuleAuthority modules) {
        return digest("FORM_SCHEMA_SOURCE", authority.blueprintSnapshotDigest(),
                modules.snapshotDigest(), authority.authoritySnapshotDigest());
    }

    private String moduleSnapshot(ModuleTarget module) {
        return digest("FORM_SCHEMA_MODULE", module.moduleId(), module.label(),
                String.valueOf(module.enabled()));
    }

    private String fieldIdentityDigest(List<DerivedField> derivedFields) {
        List<String> values = new ArrayList<>();
        derivedFields.stream().sorted(Comparator.comparingInt(DerivedField::ordinal))
                .forEach(value -> values.add(value.ordinal() + "|" + value.fieldKey() + "|"
                        + value.label() + "|" + value.fieldType() + "|" + value.required() + "|"
                        + value.maxLength() + "|" + decimal(value.minValue()) + "|"
                        + decimal(value.maxValue()) + "|" + value.decimalScale() + "|"
                        + value.unitCode() + "|" + String.join(",", value.optionCodes()) + "|"
                        + value.helpText()));
        return digest(values.toArray(String[]::new));
    }

    private String desiredDigest(List<DerivedForm> derived) {
        List<String> values = new ArrayList<>(List.of(FormSchemaDraftModels.SCHEMA_VERSION,
                FormSchemaDraftModels.ALGORITHM_VERSION));
        derived.stream().sorted(Comparator.comparingInt(DerivedForm::ordinal))
                .forEach(value -> values.add(value.ordinal() + "|" + value.formCode() + "|"
                        + value.title() + "|" + value.moduleId() + "|" + value.moduleLabel() + "|"
                        + value.moduleSnapshotDigest() + "|" + value.retentionClass() + "|"
                        + value.retentionDays() + "|" + value.auditTrailRequired() + "|"
                        + value.immutableAfterSubmit() + "|" + value.responsibleRole() + "|"
                        + value.fieldIdentityDigest() + "|" + value.changeReason()));
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
                    .contains("uk_form_schema_draft_idempotency")) return true;
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
