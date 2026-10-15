package com.cretas.aims.service.allergenlabeldraft;

import com.cretas.aims.blueprint.capabilityadapter.FactoryCapabilityAdapterContracts;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.CapabilityView;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintService;
import com.cretas.aims.blueprint.canonical.FactoryRouteGraphPrototype.RouteGraph;
import com.cretas.aims.blueprint.canonical.FactoryRouteGraphPrototype.RouteNode;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.allergenlabeldraft.AllergenLabelDraft;
import com.cretas.aims.entity.allergenlabeldraft.AllergenLabelDraftChangeover;
import com.cretas.aims.entity.allergenlabeldraft.AllergenLabelDraftDeclaration;
import com.cretas.aims.entity.allergenlabeldraft.AllergenLabelDraftEvidence;
import com.cretas.aims.entity.material.MaterialTypeDraft;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.product.ProductTypeDraft;
import com.cretas.aims.entity.sanitationcipdraft.SanitationCipTemplateDraft;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.allergenlabeldraft.AllergenLabelDraftChangeoverRepository;
import com.cretas.aims.repository.allergenlabeldraft.AllergenLabelDraftDeclarationRepository;
import com.cretas.aims.repository.allergenlabeldraft.AllergenLabelDraftEvidenceRepository;
import com.cretas.aims.repository.allergenlabeldraft.AllergenLabelDraftRepository;
import com.cretas.aims.repository.material.MaterialTypeDraftRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.product.ProductTypeDraftRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.sanitationcipdraft.SanitationCipTemplateDraftRepository;
import com.cretas.aims.service.allergenlabeldraft.AllergenLabelDraftModels.Allergen;
import com.cretas.aims.service.allergenlabeldraft.AllergenLabelDraftModels.AuthenticatedOperator;
import com.cretas.aims.service.allergenlabeldraft.AllergenLabelDraftModels.ChangeoverInput;
import com.cretas.aims.service.allergenlabeldraft.AllergenLabelDraftModels.ChangeoverMethod;
import com.cretas.aims.service.allergenlabeldraft.AllergenLabelDraftModels.ChangeoverVerification;
import com.cretas.aims.service.allergenlabeldraft.AllergenLabelDraftModels.DeclarationBasis;
import com.cretas.aims.service.allergenlabeldraft.AllergenLabelDraftModels.DeclarationInput;
import com.cretas.aims.service.allergenlabeldraft.AllergenLabelDraftModels.DerivedChangeover;
import com.cretas.aims.service.allergenlabeldraft.AllergenLabelDraftModels.DerivedDeclaration;
import com.cretas.aims.service.allergenlabeldraft.AllergenLabelDraftModels.DraftIssue;
import com.cretas.aims.service.allergenlabeldraft.AllergenLabelDraftModels.DraftPreview;
import com.cretas.aims.service.allergenlabeldraft.AllergenLabelDraftModels.DraftReadback;
import com.cretas.aims.service.allergenlabeldraft.AllergenLabelDraftModels.DraftReceipt;
import com.cretas.aims.service.allergenlabeldraft.AllergenLabelDraftModels.DraftRequest;
import com.cretas.aims.service.allergenlabeldraft.AllergenLabelDraftModels.EvidenceSource;
import com.cretas.aims.service.allergenlabeldraft.AllergenLabelDraftModels.EvidenceType;
import com.cretas.aims.service.allergenlabeldraft.AllergenLabelDraftModels.LabelStatement;
import com.cretas.aims.service.allergenlabeldraft.AllergenLabelDraftModels.PresenceKind;
import com.cretas.aims.service.allergenlabeldraft.AllergenLabelDraftModels.RecoveryPlan;
import com.cretas.aims.service.allergenlabeldraft.AllergenLabelDraftModels.ResponsibleRole;
import com.cretas.aims.service.allergenlabeldraft.AllergenLabelDraftModels.SanitationTemplatePin;
import com.cretas.aims.service.allergenlabeldraft.AllergenLabelDraftModels.SubjectKind;
import com.cretas.aims.service.allergenlabeldraft.AllergenLabelDraftModels.SubjectRef;
import com.cretas.aims.service.sanitationcipdraft.SanitationCipDraftCommand;
import com.cretas.aims.service.sanitationcipdraft.SanitationCipDraftModels;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
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
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Module-owned allergen matrix and label DRAFT command.
 *
 * <p>The immutable capability catalog v2 dependency closure is
 * {@code products + materials + routes}. Material declarations resolve against same-factory
 * products/materials or same-lineage A1/A2 drafts; changeover rules resolve against the current
 * Blueprint route graph. {@code sanitation-cip} is used only as an explicit safety pin so a
 * changeover rule can name the cleaning template that backs it.</p>
 *
 * <p>`allergen-label-policy/v1` derives the declaration basis and the statement a label is allowed
 * to carry: an allergen backed by a CONTAINS material must be declared in the ingredient list and
 * can never be downgraded to a precautionary statement. Product masters, material masters, label
 * publication, inventory, batches, BOM, production data, quality results, alerts and notifications
 * are never written.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class AllergenLabelDraftCommand {
    public static final String CAPABILITY_ID = "allergen-label";
    public static final String REQUIRED_PERMISSION = "label:draft:write";
    private static final String AUTHORITY_CODE = "ALLERGEN_LABEL_CATALOG_AUTHORITY";
    private static final List<String> DEPENDENCIES =
            FactoryCapabilityAdapterContracts.dependenciesOf(CAPABILITY_ID);
    private static final int MAX_DECLARATIONS = 500;
    private static final int MAX_CHANGEOVERS = 500;
    private static final Set<SubjectKind> MATERIAL_KINDS =
            Set.of(SubjectKind.MATERIAL, SubjectKind.MATERIAL_DRAFT);
    private static final Set<SubjectKind> PRODUCT_KINDS =
            Set.of(SubjectKind.PRODUCT, SubjectKind.PRODUCT_DRAFT);

    private final AllergenLabelDraftRepository drafts;
    private final AllergenLabelDraftDeclarationRepository declarations;
    private final AllergenLabelDraftChangeoverRepository changeovers;
    private final AllergenLabelDraftEvidenceRepository evidence;
    private final ProductTypeRepository products;
    private final RawMaterialTypeRepository materials;
    private final ProductTypeDraftRepository productDrafts;
    private final MaterialTypeDraftRepository materialDrafts;
    private final SanitationCipTemplateDraftRepository sanitationDrafts;
    private final SanitationCipDraftCommand sanitationCommand;
    private final CanonicalFactoryBlueprintService canonicalBlueprints;
    private final UserRepository users;

    public AllergenLabelDraftCommand(
            AllergenLabelDraftRepository drafts,
            AllergenLabelDraftDeclarationRepository declarations,
            AllergenLabelDraftChangeoverRepository changeovers,
            AllergenLabelDraftEvidenceRepository evidence,
            ProductTypeRepository products,
            RawMaterialTypeRepository materials,
            ProductTypeDraftRepository productDrafts,
            MaterialTypeDraftRepository materialDrafts,
            SanitationCipTemplateDraftRepository sanitationDrafts,
            SanitationCipDraftCommand sanitationCommand,
            CanonicalFactoryBlueprintService canonicalBlueprints,
            UserRepository users) {
        this.drafts = drafts;
        this.declarations = declarations;
        this.changeovers = changeovers;
        this.evidence = evidence;
        this.products = products;
        this.materials = materials;
        this.productDrafts = productDrafts;
        this.materialDrafts = materialDrafts;
        this.sanitationDrafts = sanitationDrafts;
        this.sanitationCommand = sanitationCommand;
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
        Optional<AllergenLabelDraft> prior = drafts
                .findByFactoryIdAndIdempotencyKey(request.factoryId(), request.idempotencyKey());
        if (prior.isPresent()) {
            if (!requestDigest.equals(prior.get().getRequestDigest())) {
                throw invalid(409, "幂等键对应不同过敏原标签请求",
                        "ALLERGEN_LABEL_IDEMPOTENCY_CONFLICT");
            }
            readBack(request.factoryId(), prior.get().getId());
            return receipt(prior.get(), true, 0, 0);
        }

        Evaluation evaluation = evaluate(request);
        DraftPreview plan = evaluation.preview();
        if (!"READY".equals(plan.status())) {
            throw invalid(409, "过敏原标签预览被阻断", "ALLERGEN_LABEL_PREVIEW_BLOCKED");
        }
        if (!request.confirmedPreviewDigest().equals(plan.previewDigest())) {
            throw invalid(409, "过敏原标签确认摘要已漂移", "ALLERGEN_LABEL_PREVIEW_DRIFT");
        }

        BlueprintAuthority authority = evaluation.authority();
        SanitationAuthority sanitation = evaluation.sanitation();
        AllergenLabelDraft parent = AllergenLabelDraft.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(request.factoryId())
                .blueprintId(request.blueprintId())
                .blueprintVersion(request.blueprintVersion())
                .sourceDigest(request.sourceDigest())
                .sourceSnapshotDigest(plan.sourceSnapshotDigest())
                .subjectSnapshotDigest(plan.subjectSnapshotDigest())
                .routeSnapshotDigest(authority.routeSnapshotDigest())
                .sanitationCipDraftId(sanitation.draftId())
                .sanitationCipDesiredDigest(sanitation.desiredDigest())
                .sanitationSnapshotDigest(sanitation.snapshotDigest())
                .capabilityCatalogVersion(authority.catalogVersion())
                .capabilityCatalogDigest(authority.catalogDigest())
                .authoritySnapshotDigest(authority.authoritySnapshotDigest())
                .desiredDigest(plan.desiredDigest())
                .previewDigest(plan.previewDigest())
                .requestDigest(requestDigest)
                .idempotencyKey(request.idempotencyKey())
                .operatorId(operator.actor().factoryUserId()).actorType(operator.actor().type()).actorId(operator.actor().id())
                .declarationCount(plan.declarations().size())
                .changeoverCount(plan.changeovers().size())
                .evidenceCount(1)
                .dependencyBasis(AllergenLabelDraftModels.DEPENDENCY_BASIS)
                .algorithmVersion(AllergenLabelDraftModels.ALGORITHM_VERSION)
                .dependencyClosureComplete(true)
                .status("DRAFT")
                .build();
        AllergenLabelDraft saved;
        try {
            saved = drafts.saveAndFlush(parent);
        } catch (DataIntegrityViolationException exception) {
            if (!isIdempotencyRace(exception)) throw exception;
            throw invalid(409, "过敏原标签幂等键发生并发竞争",
                    "ALLERGEN_LABEL_IDEMPOTENCY_RACE");
        }

        List<AllergenLabelDraftDeclaration> declarationRows = plan.declarations().stream()
                .map(value -> toDeclarationRow(saved, value)).toList();
        declarations.saveAllAndFlush(declarationRows);
        List<AllergenLabelDraftChangeover> changeoverRows = plan.changeovers().stream()
                .map(value -> toChangeoverRow(saved, value)).toList();
        changeovers.saveAllAndFlush(changeoverRows);
        evidence.saveAndFlush(AllergenLabelDraftEvidence.builder()
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
            throw invalid(409, "过敏原标签回读摘要不一致", "ALLERGEN_LABEL_READBACK_MISMATCH");
        }
        return receipt(saved, false, 1,
                2 + declarationRows.size() + changeoverRows.size());
    }

    @Transactional(readOnly = true)
    public DraftReadback readBack(String factoryId, String draftId) {
        AllergenLabelDraft parent = drafts.findByIdAndFactoryId(draftId, factoryId)
                .orElseThrow(() -> invalid(404, "当前工厂没有该过敏原标签草稿",
                        "ALLERGEN_LABEL_NOT_FOUND"));
        List<AllergenLabelDraftDeclaration> declarationRows = declarations
                .findByDraftIdAndFactoryIdOrderByOrdinal(draftId, factoryId);
        List<AllergenLabelDraftChangeover> changeoverRows = changeovers
                .findByDraftIdAndFactoryIdOrderByOrdinal(draftId, factoryId);
        List<AllergenLabelDraftEvidence> evidenceRows = evidence
                .findByDraftIdAndFactoryIdOrderByAuthorityCode(draftId, factoryId);
        if (!"DRAFT".equals(parent.getStatus())
                || !Boolean.TRUE.equals(parent.getDependencyClosureComplete())
                || !AllergenLabelDraftModels.DEPENDENCY_BASIS.equals(parent.getDependencyBasis())
                || !AllergenLabelDraftModels.ALGORITHM_VERSION.equals(parent.getAlgorithmVersion())
                || parent.getDeclarationCount() == null
                || parent.getDeclarationCount() != declarationRows.size()
                || parent.getChangeoverCount() == null
                || parent.getChangeoverCount() != changeoverRows.size()
                || parent.getEvidenceCount() == null || parent.getEvidenceCount() != 1
                || evidenceRows.size() != 1) {
            throw invalid(409, "过敏原标签父子行或依赖闭包不完整",
                    "ALLERGEN_LABEL_READBACK_INVARIANT_BROKEN");
        }

        List<DraftIssue> issues = new ArrayList<>();
        BlueprintAuthority authority = resolveBlueprint(factoryId, parent.getBlueprintId(),
                parent.getBlueprintVersion(), issues);
        SanitationAuthority sanitation = resolveStoredSanitation(parent, issues);
        if (!issues.isEmpty()
                || !parent.getAuthoritySnapshotDigest().equals(authority.authoritySnapshotDigest())
                || !parent.getCapabilityCatalogVersion().equals(authority.catalogVersion())
                || !parent.getCapabilityCatalogDigest().equals(authority.catalogDigest())
                || !parent.getRouteSnapshotDigest().equals(authority.routeSnapshotDigest())
                || !parent.getSanitationSnapshotDigest().equals(sanitation.snapshotDigest())) {
            throw invalid(409, "过敏原标签上游 Blueprint、路线或清洗消毒草稿已漂移",
                    "ALLERGEN_LABEL_UPSTREAM_DRIFT");
        }

        List<DraftIssue> subjectIssues = new ArrayList<>();
        List<DerivedDeclaration> derivedDeclarations = new ArrayList<>();
        Map<Allergen, DeclarationBasis> matrix = new LinkedHashMap<>();
        List<String> subjectSnapshots = new ArrayList<>();
        for (AllergenLabelDraftDeclaration row : declarationRows) {
            SubjectKind kind = SubjectKind.valueOf(row.getSubjectKind());
            SubjectResolution resolution = resolveSubject(factoryId, parent.getBlueprintId(),
                    parent.getBlueprintVersion(), parent.getSourceDigest(),
                    new SubjectRef(kind, row.getTargetId(), row.getTargetSnapshotDigest()),
                    "declaration", subjectIssues);
            if (!subjectIssues.isEmpty()
                    || !resolution.snapshotDigest().equals(row.getTargetSnapshotDigest())
                    || !Objects.equals(resolution.code(), row.getSubjectCode())
                    || !Objects.equals(resolution.name(), row.getSubjectName())) {
                throw invalid(409, "过敏原声明的产品或物料快照已漂移",
                        "ALLERGEN_LABEL_SUBJECT_DRIFT");
            }
            subjectSnapshots.add(kind.name() + "|" + row.getTargetId() + "|"
                    + resolution.snapshotDigest());
            if (MATERIAL_KINDS.contains(kind)) {
                accumulate(matrix, Allergen.valueOf(row.getAllergen()),
                        PresenceKind.valueOf(row.getPresenceKind()));
            }
            derivedDeclarations.add(fromDeclarationRow(row));
        }
        if (!subjectSnapshotDigest(subjectSnapshots).equals(parent.getSubjectSnapshotDigest())) {
            throw invalid(409, "过敏原标签主体快照摘要已漂移",
                    "ALLERGEN_LABEL_SUBJECT_SNAPSHOT_DRIFT");
        }

        Map<String, RouteTarget> routeTargets = routeTargets(authority.routeGraphs());
        List<DerivedChangeover> derivedChangeovers = new ArrayList<>();
        for (AllergenLabelDraftChangeover row : changeoverRows) {
            DerivedChangeover value = fromChangeoverRow(row);
            RouteTarget route = routeTargets.get(routeKey(value.productCode(), value.routeNodeId()));
            if (route == null || !Objects.equals(route.label(), value.routeNodeLabel())
                    || !authority.routeSnapshotDigest().equals(value.routeSnapshotDigest())) {
                throw invalid(409, "换线清场规则的路线节点快照已漂移",
                        "ALLERGEN_LABEL_ROUTE_DRIFT");
            }
            if (value.sanitationTemplateCode() != null
                    && !value.sanitationTemplateCode().isBlank()) {
                SanitationTarget target = sanitation.targets().get(value.sanitationTemplateCode());
                if (target == null
                        || !Objects.equals(target.ordinal(), value.sanitationItemOrdinal())) {
                    throw invalid(409, "换线清场引用的清洗模板已漂移",
                            "ALLERGEN_LABEL_SANITATION_DRIFT");
                }
            }
            if (value.objectiveVerification() != value.verifications().stream()
                    .anyMatch(AllergenLabelDraftModels::objectiveVerification)) {
                throw invalid(409, "换线清场客观验证判定与服务端算法不一致",
                        "ALLERGEN_LABEL_POLICY_TAMPERED");
            }
            derivedChangeovers.add(value);
        }

        for (DerivedDeclaration value : derivedDeclarations) {
            if (!PRODUCT_KINDS.contains(value.subjectKind())) continue;
            DeclarationBasis basis = matrix.get(value.allergen());
            if (basis == null || value.declarationBasis() != basis
                    || value.labelStatement() != AllergenLabelDraftModels.requiredStatement(basis)
                    || value.changeoverRequired()
                            != AllergenLabelDraftModels.changeoverRequired(basis)) {
                throw invalid(409, "标签声明与服务端派生的过敏原矩阵不一致",
                        "ALLERGEN_LABEL_POLICY_TAMPERED");
            }
        }
        if (!coverageGaps(derivedDeclarations, derivedChangeovers, matrix).isEmpty()) {
            throw invalid(409, "过敏原换线清场覆盖不完整",
                    "ALLERGEN_LABEL_CHANGEOVER_NOT_COVERED");
        }
        if (!sameEvidence(evidenceRows.get(0), authority)) {
            throw invalid(409, "过敏原标签目录依据摘要不一致",
                    "ALLERGEN_LABEL_EVIDENCE_MISMATCH");
        }
        String currentSourceSnapshot = sourceSnapshot(authority, sanitation,
                parent.getSubjectSnapshotDigest());
        if (!currentSourceSnapshot.equals(parent.getSourceSnapshotDigest())) {
            throw invalid(409, "过敏原标签源快照摘要已漂移",
                    "ALLERGEN_LABEL_SOURCE_SNAPSHOT_DRIFT");
        }
        if (!desiredDigest(derivedDeclarations, derivedChangeovers)
                .equals(parent.getDesiredDigest())) {
            throw invalid(409, "过敏原标签逐字段回读摘要不一致",
                    "ALLERGEN_LABEL_READBACK_MISMATCH");
        }
        return new DraftReadback(AllergenLabelDraftModels.SCHEMA_VERSION, factoryId, draftId,
                parent.getStatus(), derivedDeclarations, derivedChangeovers,
                parent.getSourceSnapshotDigest(), parent.getDesiredDigest(),
                0, 0, 0, 0, 0, 0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    @Transactional(readOnly = true)
    public RecoveryPlan recoveryPlan(String factoryId, String draftId,
                                     String expectedDesiredDigest) {
        AllergenLabelDraft parent = drafts.findByIdAndFactoryId(draftId, factoryId)
                .orElseThrow(() -> invalid(404, "当前工厂没有该过敏原标签草稿",
                        "ALLERGEN_LABEL_NOT_FOUND"));
        if (!"DRAFT".equals(parent.getStatus()) || !isDigest(expectedDesiredDigest)
                || !expectedDesiredDigest.equals(parent.getDesiredDigest())) {
            throw invalid(409, "只能恢复摘要精确匹配的未发布过敏原标签草稿",
                    "ALLERGEN_LABEL_RECOVERY_NOT_ALLOWED");
        }
        return new RecoveryPlan(AllergenLabelDraftModels.SCHEMA_VERSION, factoryId, draftId,
                expectedDesiredDigest, "ARCHIVE_DRAFT_ONLY",
                "只归档未发布草稿；不修改产品或物料主数据，不发布标签，不写库存、批次、BOM、生产、质量结果、告警或通知",
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    private Evaluation evaluate(DraftRequest request) {
        List<DraftIssue> issues = new ArrayList<>();
        BlueprintAuthority authority = resolveBlueprint(request.factoryId(), request.blueprintId(),
                request.blueprintVersion(), issues);
        SanitationAuthority sanitation = resolveSanitation(request, issues);

        List<DerivedDeclaration> derivedDeclarations = new ArrayList<>();
        Map<Allergen, DeclarationBasis> matrix = new LinkedHashMap<>();
        List<String> subjectSnapshots = new ArrayList<>();
        Set<String> declarationIdentities = new HashSet<>();
        List<PendingProduct> pendingProducts = new ArrayList<>();
        for (int ordinal = 0; ordinal < request.declarations().size(); ordinal++) {
            DeclarationInput input = request.declarations().get(ordinal);
            String target = "declarations[" + ordinal + "]";
            if (!validateDeclaration(input, target, issues)) continue;
            SubjectResolution resolution = resolveSubject(request.factoryId(),
                    request.blueprintId(), request.blueprintVersion(), request.sourceDigest(),
                    input.subject(), target, issues);
            if (resolution == null) continue;
            String identity = input.subject().kind() + "|" + input.subject().targetId()
                    + "|" + input.allergen();
            if (!declarationIdentities.add(identity)) {
                issues.add(issue("ALLERGEN_LABEL_DUPLICATE_DECLARATION", target,
                        "同一主体和过敏原不能重复声明", "合并重复声明"));
                continue;
            }
            subjectSnapshots.add(input.subject().kind().name() + "|"
                    + input.subject().targetId() + "|" + resolution.snapshotDigest());
            if (MATERIAL_KINDS.contains(input.subject().kind())) {
                accumulate(matrix, input.allergen(), input.presence());
                derivedDeclarations.add(materialDeclaration(ordinal, input, resolution));
            } else {
                pendingProducts.add(new PendingProduct(ordinal, input, resolution, target));
            }
        }
        for (PendingProduct pending : pendingProducts) {
            DeclarationBasis basis = matrix.get(pending.input().allergen());
            if (basis == null) {
                issues.add(issue("ALLERGEN_LABEL_UNSUPPORTED_LABEL_DECLARATION", pending.target(),
                        "标签声明的过敏原必须在同一草稿的物料矩阵中有依据",
                        "补一条对应物料的过敏原声明"));
                continue;
            }
            LabelStatement required = AllergenLabelDraftModels.requiredStatement(basis);
            if (pending.input().labelStatement() != required) {
                issues.add(issue("ALLERGEN_LABEL_STATEMENT_WEAKER_THAN_MATRIX", pending.target(),
                        "标签声明不得弱于物料矩阵：含有必须进配料表，仅交叉接触才可标示可能含有",
                        "把标签声明改为 " + required));
                continue;
            }
            derivedDeclarations.add(productDeclaration(pending.ordinal(), pending.input(),
                    pending.resolution(), basis));
        }
        derivedDeclarations.sort(Comparator.comparingInt(DerivedDeclaration::ordinal));

        Map<String, RouteTarget> routeTargets = routeTargets(authority.routeGraphs());
        List<DerivedChangeover> derivedChangeovers = new ArrayList<>();
        Set<String> changeoverIdentities = new HashSet<>();
        for (int ordinal = 0; ordinal < request.changeovers().size(); ordinal++) {
            ChangeoverInput input = request.changeovers().get(ordinal);
            String target = "changeovers[" + ordinal + "]";
            if (!validateChangeover(input, target, issues)) continue;
            RouteTarget route = routeTargets.get(routeKey(input.productCode(), input.routeNodeId()));
            if (route == null) {
                issues.add(issue("ALLERGEN_LABEL_ROUTE_TARGET_MISSING", target,
                        "换线清场必须引用当前 Blueprint 的确定路线节点",
                        "重新选择当前产品路线节点"));
                continue;
            }
            SanitationTarget sanitationTarget = null;
            if (!input.sanitationTemplateCode().isBlank()) {
                sanitationTarget = sanitation.targets().get(input.sanitationTemplateCode());
                if (sanitationTarget == null) {
                    issues.add(issue("ALLERGEN_LABEL_SANITATION_TEMPLATE_MISSING", target,
                            "换线清场引用的清洗模板必须存在于同厂 readback-ready 清洗草稿",
                            "选择清洗消毒草稿中已有的模板编码"));
                    continue;
                }
            } else if (input.changeoverMethod() == ChangeoverMethod.FULL_WET_CLEAN) {
                issues.add(issue("ALLERGEN_LABEL_SANITATION_TEMPLATE_REQUIRED", target,
                        "湿式全清洗换线必须锁定清洗消毒草稿中的模板",
                        "选择对应的清洗模板或改用其他换线方式"));
                continue;
            }
            String identity = routeKey(input.productCode(), input.routeNodeId()) + "|"
                    + input.allergen();
            if (!changeoverIdentities.add(identity)) {
                issues.add(issue("ALLERGEN_LABEL_DUPLICATE_CHANGEOVER", target,
                        "同一产品路线节点和过敏原不能重复配置换线规则", "合并重复换线规则"));
                continue;
            }
            derivedChangeovers.add(deriveChangeover(ordinal, input, route,
                    authority.routeSnapshotDigest(), sanitationTarget));
        }

        for (String gap : coverageGaps(derivedDeclarations, derivedChangeovers, matrix)) {
            issues.add(issue("ALLERGEN_LABEL_CHANGEOVER_NOT_COVERED", "allergen:" + gap,
                    "含有该过敏原的产线必须有换线清场规则，且交叉接触声明必须有客观验证",
                    "为该过敏原补一条带客观验证的换线清场规则"));
        }
        if (derivedDeclarations.stream().noneMatch(value ->
                MATERIAL_KINDS.contains(value.subjectKind()))
                || derivedDeclarations.stream().noneMatch(value ->
                PRODUCT_KINDS.contains(value.subjectKind()))) {
            issues.add(issue("ALLERGEN_LABEL_MATRIX_INCOMPLETE", "declarations",
                    "过敏原草稿必须同时包含物料矩阵和产品标签声明",
                    "补齐物料侧或产品侧声明"));
        }
        issues.sort(Comparator.comparing(DraftIssue::code).thenComparing(DraftIssue::target));

        String subjectSnapshot = subjectSnapshotDigest(subjectSnapshots);
        String desired = desiredDigest(derivedDeclarations, derivedChangeovers);
        String sourceSnapshot = sourceSnapshot(authority, sanitation, subjectSnapshot);
        String status = issues.isEmpty() ? "READY" : "BLOCKED";
        String previewDigest = digest("ALLERGEN_LABEL_PREVIEW",
                AllergenLabelDraftModels.SCHEMA_VERSION, AllergenLabelDraftModels.ALGORITHM_VERSION,
                status, sourceSnapshot, subjectSnapshot, authority.routeSnapshotDigest(),
                sanitation.snapshotDigest(), authority.authoritySnapshotDigest(), desired,
                issueDigest(issues));
        DraftPreview preview = new DraftPreview(AllergenLabelDraftModels.SCHEMA_VERSION,
                AllergenLabelDraftModels.ALGORITHM_VERSION, status, request.factoryId(),
                DEPENDENCIES, request, derivedDeclarations, derivedChangeovers, sourceSnapshot,
                subjectSnapshot, authority.routeSnapshotDigest(), sanitation.snapshotDigest(),
                authority.authoritySnapshotDigest(), desired, previewDigest, issues,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED");
        return new Evaluation(preview, authority, sanitation);
    }

    private void accumulate(Map<Allergen, DeclarationBasis> matrix, Allergen allergen,
                            PresenceKind presence) {
        DeclarationBasis candidate = presence == PresenceKind.CONTAINS
                ? DeclarationBasis.MATERIAL_CONTAINS : DeclarationBasis.MATERIAL_CROSS_CONTACT;
        DeclarationBasis current = matrix.get(allergen);
        if (current == DeclarationBasis.MATERIAL_CONTAINS) return;
        matrix.put(allergen, candidate);
    }

    private List<String> coverageGaps(List<DerivedDeclaration> derivedDeclarations,
                                      List<DerivedChangeover> derivedChangeovers,
                                      Map<Allergen, DeclarationBasis> matrix) {
        Set<Allergen> covered = EnumSet.noneOf(Allergen.class);
        Set<Allergen> objectivelyCovered = EnumSet.noneOf(Allergen.class);
        for (DerivedChangeover value : derivedChangeovers) {
            covered.add(value.allergen());
            if (value.objectiveVerification()) objectivelyCovered.add(value.allergen());
        }
        Set<String> gaps = new TreeSet<>();
        for (Map.Entry<Allergen, DeclarationBasis> entry : matrix.entrySet()) {
            if (entry.getValue() == DeclarationBasis.MATERIAL_CONTAINS
                    && !covered.contains(entry.getKey())) {
                gaps.add(entry.getKey().name());
            }
        }
        for (DerivedDeclaration value : derivedDeclarations) {
            if (PRODUCT_KINDS.contains(value.subjectKind())
                    && value.labelStatement() == LabelStatement.PRECAUTIONARY_MAY_CONTAIN
                    && !objectivelyCovered.contains(value.allergen())) {
                gaps.add(value.allergen().name());
            }
        }
        return List.copyOf(gaps);
    }

    private BlueprintAuthority resolveBlueprint(String factoryId, String blueprintId,
                                                long blueprintVersion, List<DraftIssue> issues) {
        BlueprintResponse response = canonicalBlueprints.current(factoryId);
        if (response == null || !blueprintId.equals(response.getId())
                || !Objects.equals(Math.toIntExact(blueprintVersion), response.getVersion())
                || !("DRAFT".equals(response.getLifecycleState())
                     || "VALIDATED".equals(response.getLifecycleState()))) {
            issues.add(issue("ALLERGEN_LABEL_BLUEPRINT_LINEAGE_MISMATCH", "blueprintId",
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
            issues.add(issue("ALLERGEN_LABEL_CAPABILITY_NOT_READY", "capability",
                    "allergen-label 或 products/materials/routes 目录依赖尚未就绪",
                    "补齐产品、物料与路线证据后重新编译 Blueprint"));
        }
        Set<String> refs = capability == null || capability.getAuthorityRefs() == null
                ? Set.of() : Set.copyOf(capability.getAuthorityRefs());
        if (response.getCapabilityCatalogVersion() == null
                || response.getCapabilityCatalogVersion().isBlank()
                || !isDigest(response.getCapabilityCatalogDigest()) || refs.isEmpty()) {
            issues.add(issue("ALLERGEN_LABEL_AUTHORITY_CATALOG_INVALID", "capabilityCatalog",
                    "服务端能力目录版本、摘要或标签依据无效",
                    "部署带 authority refs 的版本化能力目录"));
        }
        List<RouteGraph> graphs = response.getRouteGraphs() == null
                ? List.of() : response.getRouteGraphs();
        if (graphs.isEmpty() || graphs.stream().anyMatch(graph -> graph == null
                || graph.validation() == null || !graph.validation().valid()
                || graph.nodes().isEmpty())) {
            issues.add(issue("ALLERGEN_LABEL_ROUTE_GRAPH_INVALID", "routes",
                    "当前 Blueprint 没有完整可验证的路线图", "修复路线节点和拓扑"));
        }
        String authoritySnapshot = digest("ALLERGEN_LABEL_AUTHORITY",
                response.getCapabilityCatalogVersion(), response.getCapabilityCatalogDigest(),
                refs.stream().sorted().reduce("", (left, right) -> left + "|" + right));
        return new BlueprintAuthority(response.getCapabilityCatalogVersion(),
                response.getCapabilityCatalogDigest(), refs, authoritySnapshot,
                routeSnapshot(graphs), digestBlueprint(response), List.copyOf(graphs));
    }

    private SanitationAuthority resolveSanitation(DraftRequest request, List<DraftIssue> issues) {
        boolean required = request.changeovers().stream().filter(Objects::nonNull)
                .anyMatch(value -> !value.sanitationTemplateCode().isBlank()
                        || value.changeoverMethod() == ChangeoverMethod.FULL_WET_CLEAN);
        SanitationTemplatePin pin = request.sanitationTemplate();
        if (pin == null) {
            if (required) {
                issues.add(issue("ALLERGEN_LABEL_SANITATION_PIN_REQUIRED", "sanitationTemplate",
                        "引用清洗模板或湿式全清洗的换线必须锁定清洗消毒草稿",
                        "选择同一 Blueprint 的清洗消毒草稿"));
            }
            return SanitationAuthority.none();
        }
        if (pin.draftId().isBlank() || !isDigest(pin.desiredDigest())) {
            issues.add(issue("ALLERGEN_LABEL_SANITATION_PIN_INVALID", "sanitationTemplate",
                    "清洗消毒草稿 ID 或摘要格式无效", "刷新清洗消毒 pin"));
            return SanitationAuthority.none();
        }
        SanitationCipTemplateDraft parent = sanitationDrafts
                .findByIdAndFactoryId(pin.draftId(), request.factoryId()).orElse(null);
        if (parent == null || !"DRAFT".equals(parent.getStatus())
                || !Boolean.TRUE.equals(parent.getDependencyClosureComplete())
                || !request.blueprintId().equals(parent.getBlueprintId())
                || !Objects.equals(request.blueprintVersion(), parent.getBlueprintVersion())
                || !request.sourceDigest().equals(parent.getSourceDigest())
                || !pin.desiredDigest().equals(parent.getDesiredDigest())) {
            issues.add(issue("ALLERGEN_LABEL_SANITATION_DRIFT", "sanitationTemplate",
                    "清洗消毒草稿不属于当前 Blueprint 或摘要已变化",
                    "重新读取并选择当前清洗消毒草稿"));
            return SanitationAuthority.none();
        }
        try {
            return sanitationAuthority(parent,
                    sanitationCommand.readBack(request.factoryId(), pin.draftId()));
        } catch (RuntimeException exception) {
            issues.add(issue("ALLERGEN_LABEL_SANITATION_READBACK_FAILED", "sanitationTemplate",
                    "清洗消毒草稿逐字段回读失败", "修复清洗消毒草稿后重新预览"));
            return SanitationAuthority.none();
        }
    }

    private SanitationAuthority resolveStoredSanitation(AllergenLabelDraft parent,
                                                        List<DraftIssue> issues) {
        if (parent.getSanitationCipDraftId() == null) return SanitationAuthority.none();
        SanitationCipTemplateDraft stored = sanitationDrafts.findByIdAndFactoryId(
                parent.getSanitationCipDraftId(), parent.getFactoryId()).orElse(null);
        if (stored == null
                || !Objects.equals(parent.getSanitationCipDesiredDigest(), stored.getDesiredDigest())
                || !parent.getBlueprintId().equals(stored.getBlueprintId())
                || !Objects.equals(parent.getBlueprintVersion(), stored.getBlueprintVersion())
                || !parent.getSourceDigest().equals(stored.getSourceDigest())) {
            issues.add(issue("ALLERGEN_LABEL_SANITATION_DRIFT", "sanitationTemplate",
                    "已保存的清洗消毒 pin 已漂移", "重新生成过敏原标签草稿"));
            return SanitationAuthority.none();
        }
        try {
            return sanitationAuthority(stored,
                    sanitationCommand.readBack(parent.getFactoryId(), stored.getId()));
        } catch (RuntimeException exception) {
            issues.add(issue("ALLERGEN_LABEL_SANITATION_READBACK_FAILED", "sanitationTemplate",
                    "已保存的清洗消毒草稿逐字段回读失败", "修复清洗消毒草稿后重新生成草稿"));
            return SanitationAuthority.none();
        }
    }

    private SanitationAuthority sanitationAuthority(
            SanitationCipTemplateDraft parent, SanitationCipDraftModels.DraftReadback readback) {
        Map<String, SanitationTarget> targets = new LinkedHashMap<>();
        for (SanitationCipDraftModels.DerivedTemplate template : readback.templates()) {
            targets.putIfAbsent(template.templateCode(),
                    new SanitationTarget(template.ordinal(), template.templateCode()));
        }
        List<String> values = new ArrayList<>(List.of(readback.schemaVersion(),
                readback.factoryId(), readback.draftId(), readback.status(),
                readback.desiredDigest()));
        readback.templates().forEach(template -> values.add(template.ordinal() + "|"
                + template.templateCode() + "|" + template.targetSnapshotDigest()));
        return new SanitationAuthority(parent.getId(), parent.getDesiredDigest(),
                digest(values.toArray(String[]::new)), Map.copyOf(targets));
    }

    private boolean validateDeclaration(DeclarationInput input, String target,
                                        List<DraftIssue> issues) {
        if (input == null || input.subject() == null || input.subject().kind() == null
                || isBlankOrTooLong(input.subject().targetId(), 64)
                || !isDigest(input.subject().targetSnapshotDigest())
                || input.allergen() == null || isBlankOrTooLong(input.changeReason(), 500)) {
            issues.add(issue("ALLERGEN_LABEL_DECLARATION_FIELDS_REQUIRED", target,
                    "主体、快照摘要、过敏原和变更原因均必填", "补齐 typed 声明字段"));
            return false;
        }
        boolean material = MATERIAL_KINDS.contains(input.subject().kind());
        if (material) {
            if (input.presence() == null || input.evidenceSource() == null
                    || input.labelStatement() != null || !input.labelVersionCode().isBlank()) {
                issues.add(issue("ALLERGEN_LABEL_MATERIAL_SHAPE_INVALID", target,
                        "物料声明必须提供存在方式和依据来源，且不得携带标签声明或标签版本",
                        "修正物料侧字段"));
                return false;
            }
            return true;
        }
        if (input.labelStatement() == null
                || isBlankOrTooLong(input.labelVersionCode(), 60)
                || !input.labelVersionCode().matches("[A-Z0-9][A-Z0-9_.-]{0,59}")
                || input.presence() != null || input.evidenceSource() != null) {
            issues.add(issue("ALLERGEN_LABEL_PRODUCT_SHAPE_INVALID", target,
                    "标签声明必须提供声明方式和标签版本号，且不得携带物料存在方式或依据来源",
                    "修正产品侧字段"));
            return false;
        }
        return true;
    }

    private boolean validateChangeover(ChangeoverInput input, String target,
                                       List<DraftIssue> issues) {
        if (input == null || isBlankOrTooLong(input.productCode(), 100)
                || isBlankOrTooLong(input.routeNodeId(), 100) || input.allergen() == null
                || input.changeoverMethod() == null || input.responsibleRole() == null
                || isBlankOrTooLong(input.changeReason(), 500)) {
            issues.add(issue("ALLERGEN_LABEL_CHANGEOVER_FIELDS_REQUIRED", target,
                    "产品路线、过敏原、换线方式、责任角色和变更原因均必填",
                    "补齐 typed 换线字段"));
            return false;
        }
        boolean valid = true;
        if (input.verifications().isEmpty() || input.verifications().size() > 5
                || new HashSet<>(input.verifications()).size() != input.verifications().size()
                || input.requiredEvidence().isEmpty() || input.requiredEvidence().size() > 4
                || new HashSet<>(input.requiredEvidence()).size()
                   != input.requiredEvidence().size()) {
            issues.add(issue("ALLERGEN_LABEL_VERIFICATION_EVIDENCE_INVALID", target,
                    "验证方法和留证类型必须为非空、不重复的封闭枚举",
                    "选择有效验证与留证组合"));
            valid = false;
        }
        if (input.holdUntilVerified() && input.verifications().stream()
                .noneMatch(AllergenLabelDraftModels::objectiveVerification)) {
            issues.add(issue("ALLERGEN_LABEL_HOLD_NEEDS_OBJECTIVE_VERIFICATION", target,
                    "换线 hold 不能只依赖目视或清单",
                    "增加过敏原快检、蛋白拭子或冲洗水检测"));
            valid = false;
        }
        if (input.changeoverMethod() == ChangeoverMethod.DEDICATED_LINE
                && !input.sanitationTemplateCode().isBlank()) {
            issues.add(issue("ALLERGEN_LABEL_DEDICATED_LINE_HAS_NO_CHANGEOVER_CLEAN", target,
                    "专线生产不进行换线清洗，不能引用清洗模板",
                    "删除清洗模板引用或改用其他换线方式"));
            valid = false;
        }
        return valid;
    }

    private SubjectResolution resolveSubject(String factoryId, String blueprintId,
                                             long blueprintVersion, String sourceDigest,
                                             SubjectRef subject, String target,
                                             List<DraftIssue> issues) {
        SubjectResolution resolution = switch (subject.kind()) {
            case PRODUCT -> {
                ProductType row = products.findByIdAndFactoryId(subject.targetId(), factoryId)
                        .orElse(null);
                if (row == null) {
                    issues.add(issue("ALLERGEN_LABEL_PRODUCT_NOT_FOUND", target,
                            "产品不属于当前工厂或不存在", "重新选择当前工厂产品"));
                    yield null;
                }
                if (!Boolean.TRUE.equals(row.getIsActive())) {
                    issues.add(issue("ALLERGEN_LABEL_PRODUCT_INACTIVE", target,
                            "停用产品不能建立标签声明", "选择有效产品"));
                }
                String authority = digest("PRODUCT", row.getId(), row.getFactoryId(),
                        row.getCode(), row.getName(), row.getUnit(),
                        String.valueOf(row.getIsActive()), String.valueOf(row.getUpdatedAt()));
                yield new SubjectResolution(row.getCode(), row.getName(), authority);
            }
            case PRODUCT_DRAFT -> {
                ProductTypeDraft row = productDrafts
                        .findByIdAndFactoryId(subject.targetId(), factoryId).orElse(null);
                if (row == null) {
                    issues.add(issue("ALLERGEN_LABEL_PRODUCT_DRAFT_NOT_FOUND", target,
                            "产品草稿不属于当前工厂或不存在", "重新选择 A1 产品草稿"));
                    yield null;
                }
                if (!"DRAFT".equals(row.getStatus())
                        || !Objects.equals(row.getBlueprintId(), blueprintId)
                        || !Objects.equals(row.getBlueprintVersion(), blueprintVersion)
                        || !Objects.equals(row.getSourceDigest(), sourceDigest)) {
                    issues.add(issue("ALLERGEN_LABEL_PRODUCT_DRAFT_LINEAGE_CONFLICT", target,
                            "A1 产品草稿不属于同一 Blueprint lineage",
                            "选择同一蓝图版本的产品草稿"));
                }
                String authority = digest("PRODUCT_DRAFT", row.getId(), row.getDesiredDigest(),
                        row.getDesiredName(), row.getDesiredUnit(), row.getStatus(),
                        String.valueOf(row.getLockVersion()), String.valueOf(row.getUpdatedAt()));
                yield new SubjectResolution(row.getNaturalKey(), row.getDesiredName(), authority);
            }
            case MATERIAL -> {
                RawMaterialType row = materials.findByIdAndFactoryId(subject.targetId(), factoryId)
                        .orElse(null);
                if (row == null) {
                    issues.add(issue("ALLERGEN_LABEL_MATERIAL_NOT_FOUND", target,
                            "物料不属于当前工厂或不存在", "重新选择当前工厂物料"));
                    yield null;
                }
                if (!Boolean.TRUE.equals(row.getIsActive())) {
                    issues.add(issue("ALLERGEN_LABEL_MATERIAL_INACTIVE", target,
                            "停用物料不能建立过敏原声明", "选择有效物料"));
                }
                String authority = digest("MATERIAL", row.getId(), row.getFactoryId(),
                        row.getCode(), row.getName(), row.getUnit(), row.getCategory(),
                        String.valueOf(row.getIsActive()), String.valueOf(row.getUpdatedAt()));
                yield new SubjectResolution(row.getCode(), row.getName(), authority);
            }
            case MATERIAL_DRAFT -> {
                MaterialTypeDraft row = materialDrafts
                        .findByIdAndFactoryId(subject.targetId(), factoryId).orElse(null);
                if (row == null) {
                    issues.add(issue("ALLERGEN_LABEL_MATERIAL_DRAFT_NOT_FOUND", target,
                            "物料草稿不属于当前工厂或不存在", "重新选择 A2 物料草稿"));
                    yield null;
                }
                if (!"DRAFT".equals(row.getStatus())
                        || !Objects.equals(row.getBlueprintId(), blueprintId)
                        || !Objects.equals(row.getBlueprintVersion(), blueprintVersion)
                        || !Objects.equals(row.getSourceDigest(), sourceDigest)) {
                    issues.add(issue("ALLERGEN_LABEL_MATERIAL_DRAFT_LINEAGE_CONFLICT", target,
                            "A2 物料草稿不属于同一 Blueprint lineage",
                            "选择同一蓝图版本的物料草稿"));
                }
                String authority = digest("MATERIAL_DRAFT", row.getId(), row.getDesiredDigest(),
                        row.getDesiredName(), row.getDesiredUnit(), row.getStatus(),
                        String.valueOf(row.getLockVersion()), String.valueOf(row.getUpdatedAt()));
                yield new SubjectResolution(row.getNaturalKey(), row.getDesiredName(), authority);
            }
        };
        if (resolution == null) return null;
        String snapshot = digest(subject.kind().name(), subject.targetId(), resolution.authority());
        if (!snapshot.equals(subject.targetSnapshotDigest())) {
            issues.add(issue("ALLERGEN_LABEL_SUBJECT_SNAPSHOT_CONFLICT", target,
                    "产品或物料快照已变化", "重新读取当前主体并生成预览"));
        }
        return new SubjectResolution(resolution.code(), resolution.name(), snapshot);
    }

    private DerivedDeclaration materialDeclaration(int ordinal, DeclarationInput input,
                                                   SubjectResolution resolution) {
        return new DerivedDeclaration(ordinal, declarationCode(input), input.subject().kind(),
                input.subject().targetId(), resolution.snapshotDigest(), resolution.code(),
                resolution.name(), input.allergen(), input.presence(), input.evidenceSource(),
                null, null, null, false, input.changeReason());
    }

    private DerivedDeclaration productDeclaration(int ordinal, DeclarationInput input,
                                                  SubjectResolution resolution,
                                                  DeclarationBasis basis) {
        return new DerivedDeclaration(ordinal, declarationCode(input), input.subject().kind(),
                input.subject().targetId(), resolution.snapshotDigest(), resolution.code(),
                resolution.name(), input.allergen(), null, null, input.labelStatement(),
                input.labelVersionCode(), basis,
                AllergenLabelDraftModels.changeoverRequired(basis), input.changeReason());
    }

    private String declarationCode(DeclarationInput input) {
        return "ALG-" + digest(input.subject().kind().name(), input.subject().targetId(),
                input.allergen().name()).substring(0, 16).toUpperCase(Locale.ROOT);
    }

    private DerivedChangeover deriveChangeover(int ordinal, ChangeoverInput input,
                                               RouteTarget route, String routeSnapshotDigest,
                                               SanitationTarget sanitation) {
        List<ChangeoverVerification> verifications = input.verifications().stream()
                .sorted(Comparator.comparing(Enum::name)).toList();
        List<EvidenceType> requiredEvidence = input.requiredEvidence().stream()
                .sorted(Comparator.comparing(Enum::name)).toList();
        String code = "CHG-" + digest(input.productCode(), input.routeNodeId(),
                input.allergen().name()).substring(0, 16).toUpperCase(Locale.ROOT);
        return new DerivedChangeover(ordinal, code, input.productCode(), input.routeNodeId(),
                route.label(), routeSnapshotDigest, input.allergen(), input.changeoverMethod(),
                verifications, input.responsibleRole(), requiredEvidence,
                input.holdUntilVerified(),
                verifications.stream().anyMatch(AllergenLabelDraftModels::objectiveVerification),
                sanitation == null ? null : sanitation.templateCode(),
                sanitation == null ? null : sanitation.ordinal(), input.changeReason());
    }

    private AllergenLabelDraftDeclaration toDeclarationRow(AllergenLabelDraft parent,
                                                           DerivedDeclaration value) {
        return AllergenLabelDraftDeclaration.builder()
                .id(UUID.randomUUID().toString()).draftId(parent.getId())
                .factoryId(parent.getFactoryId()).ordinal(value.ordinal())
                .declarationCode(value.declarationCode())
                .subjectKind(value.subjectKind().name()).targetId(value.targetId())
                .targetSnapshotDigest(value.targetSnapshotDigest())
                .subjectCode(value.subjectCode()).subjectName(value.subjectName())
                .allergen(value.allergen().name())
                .presenceKind(value.presence() == null ? null : value.presence().name())
                .evidenceSource(value.evidenceSource() == null
                        ? null : value.evidenceSource().name())
                .labelStatement(value.labelStatement() == null
                        ? null : value.labelStatement().name())
                .labelVersionCode(value.labelVersionCode())
                .declarationBasis(value.declarationBasis() == null
                        ? null : value.declarationBasis().name())
                .changeoverRequired(value.changeoverRequired())
                .changeReason(value.changeReason()).build();
    }

    private AllergenLabelDraftChangeover toChangeoverRow(AllergenLabelDraft parent,
                                                          DerivedChangeover value) {
        return AllergenLabelDraftChangeover.builder()
                .id(UUID.randomUUID().toString()).draftId(parent.getId())
                .factoryId(parent.getFactoryId()).ordinal(value.ordinal())
                .changeoverCode(value.changeoverCode()).productCode(value.productCode())
                .routeNodeId(value.routeNodeId()).routeNodeLabel(value.routeNodeLabel())
                .routeSnapshotDigest(value.routeSnapshotDigest())
                .allergen(value.allergen().name())
                .changeoverMethod(value.changeoverMethod().name())
                .verifications(joinEnums(value.verifications()))
                .responsibleRole(value.responsibleRole().name())
                .requiredEvidence(joinEnums(value.requiredEvidence()))
                .holdUntilVerified(value.holdUntilVerified())
                .objectiveVerification(value.objectiveVerification())
                .sanitationCipDraftId(value.sanitationTemplateCode() == null
                        ? null : parent.getSanitationCipDraftId())
                .sanitationTemplateCode(value.sanitationTemplateCode())
                .sanitationItemOrdinal(value.sanitationItemOrdinal())
                .changeReason(value.changeReason()).build();
    }

    private DerivedDeclaration fromDeclarationRow(AllergenLabelDraftDeclaration row) {
        return new DerivedDeclaration(row.getOrdinal(), row.getDeclarationCode(),
                SubjectKind.valueOf(row.getSubjectKind()), row.getTargetId(),
                row.getTargetSnapshotDigest(), row.getSubjectCode(), row.getSubjectName(),
                Allergen.valueOf(row.getAllergen()),
                row.getPresenceKind() == null ? null : PresenceKind.valueOf(row.getPresenceKind()),
                row.getEvidenceSource() == null
                        ? null : EvidenceSource.valueOf(row.getEvidenceSource()),
                row.getLabelStatement() == null
                        ? null : LabelStatement.valueOf(row.getLabelStatement()),
                row.getLabelVersionCode(),
                row.getDeclarationBasis() == null
                        ? null : DeclarationBasis.valueOf(row.getDeclarationBasis()),
                Boolean.TRUE.equals(row.getChangeoverRequired()), row.getChangeReason());
    }

    private DerivedChangeover fromChangeoverRow(AllergenLabelDraftChangeover row) {
        return new DerivedChangeover(row.getOrdinal(), row.getChangeoverCode(),
                row.getProductCode(), row.getRouteNodeId(), row.getRouteNodeLabel(),
                row.getRouteSnapshotDigest(), Allergen.valueOf(row.getAllergen()),
                ChangeoverMethod.valueOf(row.getChangeoverMethod()),
                parseEnums(row.getVerifications(), ChangeoverVerification::valueOf),
                ResponsibleRole.valueOf(row.getResponsibleRole()),
                parseEnums(row.getRequiredEvidence(), EvidenceType::valueOf),
                Boolean.TRUE.equals(row.getHoldUntilVerified()),
                Boolean.TRUE.equals(row.getObjectiveVerification()),
                row.getSanitationTemplateCode(), row.getSanitationItemOrdinal(),
                row.getChangeReason());
    }

    private void authorize(DraftRequest request, AuthenticatedOperator operator) {
        if (operator == null || (operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER && operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.PLATFORM_ADMIN) || operator.actor().id() != request.operatorId()
                || !request.factoryId().equals(operator.factoryId())) {
            throw invalid(403, "操作人和工厂范围不一致",
                    "ALLERGEN_LABEL_OPERATOR_SCOPE_MISMATCH");
        }
        if (!operator.permissions().contains(REQUIRED_PERMISSION)) {
            throw invalid(403, "缺少过敏原标签草稿权限", "ALLERGEN_LABEL_PERMISSION_REQUIRED");
        }
        Optional<User> member = users.findByIdAndFactoryId(operator.operatorId(),
                operator.factoryId());
        if (member.filter(user -> Boolean.TRUE.equals(user.getIsActive())).isEmpty()) {
            throw invalid(403, "操作人不是当前工厂有效成员", "ALLERGEN_LABEL_MEMBER_REQUIRED");
        }
    }

    private void validateEnvelope(DraftRequest request, boolean materialize) {
        if (request == null || request.factoryId().isBlank() || request.factoryId().length() > 50
                || request.blueprintId().isBlank() || request.blueprintId().length() > 100
                || request.blueprintVersion() <= 0 || !isDigest(request.sourceDigest())
                || request.declarations().isEmpty()
                || request.declarations().size() > MAX_DECLARATIONS
                || request.changeovers().size() > MAX_CHANGEOVERS) {
            throw invalid(400, "Blueprint envelope 或过敏原声明数量无效",
                    "ALLERGEN_LABEL_SCOPE_REQUIRED");
        }
        if (materialize && (request.operatorId() <= 0 || request.idempotencyKey().isBlank()
                || request.idempotencyKey().length() > 128
                || !isDigest(request.confirmedPreviewDigest()))) {
            throw invalid(400, "操作人、幂等键或确认摘要无效",
                    "ALLERGEN_LABEL_MATERIALIZE_ENVELOPE_INVALID");
        }
    }

    private DraftReceipt receipt(AllergenLabelDraft row, boolean replayed, int moduleWrites,
                                 int persistedRows) {
        return new DraftReceipt(AllergenLabelDraftModels.SCHEMA_VERSION, row.getId(),
                row.getFactoryId(), row.getId(), row.getStatus(), row.getSourceSnapshotDigest(),
                row.getDesiredDigest(), row.getPreviewDigest(), row.getRequestDigest(),
                row.getDeclarationCount(), row.getChangeoverCount(), row.getEvidenceCount(),
                row.getLockVersion() == null ? 0L : row.getLockVersion(), replayed,
                moduleWrites, replayed ? 1 : moduleWrites, persistedRows,
                0, 0, 0, 0, 0, 0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED", "ARCHIVE_DRAFT_ONLY");
    }

    private boolean sameEvidence(AllergenLabelDraftEvidence row, BlueprintAuthority authority) {
        return AUTHORITY_CODE.equals(row.getAuthorityCode())
                && authority.catalogVersion().equals(row.getCatalogVersion())
                && authority.catalogDigest().equals(row.getCatalogDigest())
                && authorityEvidenceDigest(authority).equals(row.getAuthoritySnapshotDigest());
    }

    private String sourceSnapshot(BlueprintAuthority authority, SanitationAuthority sanitation,
                                  String subjectSnapshot) {
        return digest("ALLERGEN_LABEL_SOURCE", authority.blueprintSnapshotDigest(),
                subjectSnapshot, authority.routeSnapshotDigest(), sanitation.snapshotDigest(),
                authority.authoritySnapshotDigest());
    }

    private String subjectSnapshotDigest(List<String> values) {
        return digest(new TreeSet<>(values).toArray(String[]::new));
    }

    private Map<String, RouteTarget> routeTargets(List<RouteGraph> graphs) {
        Map<String, RouteTarget> result = new HashMap<>();
        for (RouteGraph graph : graphs) {
            if (graph == null) continue;
            for (RouteNode node : graph.nodes()) {
                result.put(routeKey(clean(graph.productCode()), clean(node.id())),
                        new RouteTarget(clean(graph.productCode()), clean(node.id()),
                                clean(node.label())));
            }
        }
        return result;
    }

    private String routeSnapshot(List<RouteGraph> graphs) {
        List<String> values = new ArrayList<>();
        graphs.stream().sorted(Comparator.comparing(graph -> clean(graph.productCode())))
                .forEach(graph -> {
                    values.add("G|" + clean(graph.productCode()) + "|" + clean(graph.productName()));
                    graph.nodes().stream().sorted(Comparator.comparing(RouteNode::id))
                            .forEach(node -> values.add("N|" + node.id() + "|" + node.label()));
                    graph.edges().stream().sorted(Comparator.comparing(edge -> edge.fromNodeId()
                                    + "|" + edge.toNodeId() + "|" + edge.type()))
                            .forEach(edge -> values.add("E|" + edge.fromNodeId() + "|"
                                    + edge.toNodeId() + "|" + edge.type()));
                });
        return digest(values.toArray(String[]::new));
    }

    private String desiredDigest(List<DerivedDeclaration> derivedDeclarations,
                                 List<DerivedChangeover> derivedChangeovers) {
        List<String> values = new ArrayList<>(List.of(AllergenLabelDraftModels.SCHEMA_VERSION,
                AllergenLabelDraftModels.ALGORITHM_VERSION));
        derivedDeclarations.stream().sorted(Comparator.comparingInt(DerivedDeclaration::ordinal))
                .forEach(value -> values.add("D|" + value.ordinal() + "|"
                        + value.declarationCode() + "|" + value.subjectKind() + "|"
                        + value.targetId() + "|" + value.targetSnapshotDigest() + "|"
                        + value.subjectCode() + "|" + value.subjectName() + "|"
                        + value.allergen() + "|" + value.presence() + "|"
                        + value.evidenceSource() + "|" + value.labelStatement() + "|"
                        + value.labelVersionCode() + "|" + value.declarationBasis() + "|"
                        + value.changeoverRequired() + "|" + value.changeReason()));
        derivedChangeovers.stream().sorted(Comparator.comparingInt(DerivedChangeover::ordinal))
                .forEach(value -> values.add("C|" + value.ordinal() + "|"
                        + value.changeoverCode() + "|" + value.productCode() + "|"
                        + value.routeNodeId() + "|" + value.routeNodeLabel() + "|"
                        + value.routeSnapshotDigest() + "|" + value.allergen() + "|"
                        + value.changeoverMethod() + "|" + joinEnums(value.verifications()) + "|"
                        + value.responsibleRole() + "|" + joinEnums(value.requiredEvidence()) + "|"
                        + value.holdUntilVerified() + "|" + value.objectiveVerification() + "|"
                        + value.sanitationTemplateCode() + "|" + value.sanitationItemOrdinal()
                        + "|" + value.changeReason()));
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
                    .contains("uk_allergen_label_draft_idempotency")) return true;
        }
        return false;
    }

    private boolean isBlankOrTooLong(String value, int max) {
        return value == null || value.isBlank() || value.length() > max;
    }

    private boolean isDigest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String routeKey(String productCode, String nodeId) {
        return clean(productCode) + "\0" + clean(nodeId);
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
                              SanitationAuthority sanitation) {
    }

    private record PendingProduct(int ordinal, DeclarationInput input,
                                  SubjectResolution resolution, String target) {
    }

    private record SubjectResolution(String code, String name, String snapshotDigest) {
        String authority() {
            return snapshotDigest;
        }
    }

    private record BlueprintAuthority(String catalogVersion, String catalogDigest,
                                      Set<String> authorityRefs, String authoritySnapshotDigest,
                                      String routeSnapshotDigest, String blueprintSnapshotDigest,
                                      List<RouteGraph> routeGraphs) {
        static BlueprintAuthority missing() {
            return new BlueprintAuthority("", digestStatic("MISSING_CATALOG"), Set.of(),
                    digestStatic("MISSING_AUTHORITY"), digestStatic("MISSING_ROUTES"),
                    digestStatic("MISSING_BLUEPRINT"), List.of());
        }
    }

    private record SanitationAuthority(String draftId, String desiredDigest, String snapshotDigest,
                                       Map<String, SanitationTarget> targets) {
        static SanitationAuthority none() {
            return new SanitationAuthority(null, null, digestStatic("NO_SANITATION_DRAFT"),
                    Map.of());
        }
    }

    private record SanitationTarget(int ordinal, String templateCode) {
    }

    private record RouteTarget(String productCode, String nodeId, String label) {
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
