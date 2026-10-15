package com.cretas.aims.service.materialdraft;

import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintService;
import com.cretas.aims.blueprint.draftentry.BlueprintSourceDigest;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.material.MaterialCodeSegment;
import com.cretas.aims.entity.material.MaterialTypeDraft;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.material.MaterialCodeSegmentRepository;
import com.cretas.aims.repository.material.MaterialTypeDraftRepository;
import com.cretas.aims.service.materialdraft.MaterialTypeDraftModels.DraftIssue;
import com.cretas.aims.service.materialdraft.MaterialTypeDraftModels.DraftPreview;
import com.cretas.aims.service.materialdraft.MaterialTypeDraftModels.DraftReceipt;
import com.cretas.aims.service.materialdraft.MaterialTypeDraftModels.DraftRequest;
import com.cretas.aims.service.materialdraft.MaterialTypeDraftModels.MaterialSnapshot;
import com.cretas.aims.service.materialdraft.MaterialTypeDraftModels.RecoveryPlan;
import com.cretas.aims.service.materialdraft.MaterialTypeDraftModels.RequestedMaterial;
import com.cretas.aims.service.materialdraft.MaterialTypeDraftModels.ResolvedMaterial;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import com.cretas.aims.service.unit.UnitUsageScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Module-owned Factory Blueprint MaterialType DRAFT command.
 *
 * <p>It only persists {@link MaterialTypeDraft}. Classification and inventory unit resolution are
 * read-only; the command never calls the live RawMaterialType writer or allocates a live code.</p>
 *
 * <p>An L1 category may also be <b>planned</b>: listed in the current blueprint's {@code codeSegments}
 * but not yet in {@code material_code_segments}. The command never creates it; the DRAFT row carries it
 * in the existing columns (primary code = the planned code, category = the blueprint label, packaging =
 * the user's answer) and the master-data promotion creates it before the first material under it.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class MaterialTypeDraftCommand {

    public static final String CAPABILITY_ID = "materials";
    public static final String REQUIRED_PERMISSION = "material:draft:write";
    /** Blueprint states whose {@code codeSegments} may plan a category (the states a DRAFT can be proposed from). */
    static final Set<String> PLANNING_BLUEPRINT_STATES = Set.of("DRAFT", "VALIDATED");
    /** {@code material_code_segments.segment_label} and {@code material_type_drafts.desired_category}. */
    public static final int MAX_CATEGORY_LABEL = 100;

    private final MaterialTypeDraftRepository draftRepository;
    private final RawMaterialTypeRepository materialTypeRepository;
    private final MaterialCodeSegmentRepository segmentRepository;
    private final UnitContractService unitContractService;
    /** Source of planned L1 categories; {@code null} => only the segment table is a source. */
    private final CanonicalFactoryBlueprintService blueprints;

    /**
     * Without a blueprint authority: a category that is not in the segment table is refused as before
     * (MATERIAL_DRAFT_CLASSIFICATION_NOT_AVAILABLE), never guessed.
     */
    public MaterialTypeDraftCommand(
            MaterialTypeDraftRepository draftRepository,
            RawMaterialTypeRepository materialTypeRepository,
            MaterialCodeSegmentRepository segmentRepository,
            UnitContractService unitContractService) {
        this(draftRepository, materialTypeRepository, segmentRepository, unitContractService, null);
    }

    @Autowired
    public MaterialTypeDraftCommand(
            MaterialTypeDraftRepository draftRepository,
            RawMaterialTypeRepository materialTypeRepository,
            MaterialCodeSegmentRepository segmentRepository,
            UnitContractService unitContractService,
            CanonicalFactoryBlueprintService blueprints) {
        this.draftRepository = Objects.requireNonNull(draftRepository, "draftRepository");
        this.materialTypeRepository = Objects.requireNonNull(materialTypeRepository, "materialTypeRepository");
        this.segmentRepository = Objects.requireNonNull(segmentRepository, "segmentRepository");
        this.unitContractService = Objects.requireNonNull(unitContractService, "unitContractService");
        this.blueprints = blueprints;
    }

    @Transactional(readOnly = true)
    public DraftPreview preview(DraftRequest request) {
        validateEnvelope(request, false);
        List<DraftIssue> issues = new ArrayList<>();
        ResolvedMaterial desired = resolveDesired(request, issues);
        RawMaterialType current = resolveCurrent(request, desired, issues);
        MaterialSnapshot snapshot = snapshot(current);
        String sourceSnapshotDigest = sourceSnapshotDigest(current);
        String desiredDigest = desiredDigest(desired);
        issues.sort(Comparator.comparing(DraftIssue::code).thenComparing(DraftIssue::target));
        String status = issues.isEmpty() ? "READY" : "BLOCKED";
        String previewDigest = digest(MaterialTypeDraftModels.SCHEMA_VERSION,
                request.factoryId(), request.blueprintId(), String.valueOf(request.blueprintVersion()),
                request.sourceDigest(), request.targetMaterialTypeId(), sourceSnapshotDigest,
                desiredDigest, status, issueDigest(issues));
        return new DraftPreview(MaterialTypeDraftModels.SCHEMA_VERSION, status, request.factoryId(),
                snapshot, desired, sourceSnapshotDigest, desiredDigest, previewDigest, issues,
                0, "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    /** The same live-source pin is checked when a material UPDATE draft is promoted. */
    public static String sourceSnapshotDigest(RawMaterialType current) {
        MaterialSnapshot snapshot = snapshot(current);
        return digest(
                snapshot == null ? "ABSENT" : "PRESENT",
                snapshot == null ? "" : snapshot.materialTypeId(),
                snapshot == null ? "" : snapshot.code(),
                snapshot == null ? "" : snapshot.businessCode(),
                snapshot == null ? "" : snapshot.name(),
                snapshot == null ? "" : snapshot.primaryCategoryCode(),
                snapshot == null ? "" : snapshot.category(),
                snapshot == null ? "" : snapshot.unit(),
                snapshot == null ? "" : snapshot.storageType(),
                snapshot == null || snapshot.shelfLifeDays() == null ? "" : snapshot.shelfLifeDays().toString(),
                snapshot == null ? "" : String.valueOf(snapshot.byproduct()),
                snapshot == null ? "" : snapshot.updatedAt());
    }

    @Transactional
    public DraftReceipt materialize(DraftRequest request, com.cretas.aims.service.materialdraft.MaterialTypeDraftModels.AuthenticatedOperator operator) {
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
            throw invalid(403, "缺少物料草稿权限", "MATERIAL_DRAFT_PERMISSION_REQUIRED",
                    "由物料主数据负责人授予 material:draft:write 后重试", "permission");
        }
        String requestDigest = requestDigest(request, actor);
        Optional<MaterialTypeDraft> prior = draftRepository.findByFactoryIdAndIdempotencyKey(
                request.factoryId(), request.idempotencyKey());
        if (prior.isPresent()) return replay(prior.get(), requestDigest);

        DraftPreview preview = preview(request);
        if (!"READY".equals(preview.status())) {
            throw invalid(409, "物料草稿预览仍有阻断项", "MATERIAL_DRAFT_PREVIEW_BLOCKED",
                    preview.issues().isEmpty() ? "重新生成预览" : preview.issues().get(0).recovery(),
                    preview.issues().isEmpty() ? "request" : preview.issues().get(0).target());
        }
        if (!request.confirmedPreviewDigest().equals(preview.previewDigest())) {
            throw invalid(409, "物料草稿预览摘要已变化", "MATERIAL_DRAFT_PREVIEW_DIGEST_CONFLICT",
                    "刷新当前物料、分类树与单位目录后重新查看并确认 ChangeSet", "confirmedPreviewDigest");
        }

        ResolvedMaterial desired = preview.desired();
        MaterialTypeDraft row = MaterialTypeDraft.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(request.factoryId())
                .blueprintId(request.blueprintId())
                .blueprintVersion(request.blueprintVersion())
                .sourceDigest(request.sourceDigest())
                .targetMaterialTypeId(preview.current() == null ? null : preview.current().materialTypeId())
                .naturalKey(desired.naturalKey())
                .desiredName(desired.name())
                .desiredClassificationSegmentCode(desired.classificationSegmentCode())
                .desiredPrimaryCategoryCode(desired.primaryCategoryCode())
                .desiredCategory(desired.category())
                .desiredUnit(desired.unit())
                .desiredStorageMode(desired.storageMode() == null ? null : desired.storageMode().name())
                .desiredShelfLifeDays(desired.shelfLifeDays())
                .desiredIsByproduct(desired.byproduct())
                .desiredPackagingCategory(desired.packagingCategory())
                .sourceSnapshotDigest(preview.sourceSnapshotDigest())
                .desiredDigest(preview.desiredDigest())
                .previewDigest(preview.previewDigest())
                .requestDigest(requestDigest)
                .idempotencyKey(request.idempotencyKey())
                .operatorId(actor.factoryUserId()).actorType(actor.type()).actorId(actor.id())
                .status("DRAFT")
                .build();
        MaterialTypeDraft saved;
        try {
            saved = draftRepository.saveAndFlush(row);
        } catch (DataIntegrityViolationException race) {
            if (!isScopedIdempotencyRace(race)) throw race;
            throw invalid(409, "物料草稿幂等键发生并发竞争", "MATERIAL_DRAFT_IDEMPOTENCY_RACE",
                    "使用相同幂等键重试以读取胜出回执", "idempotencyKey");
        }
        requireExactReadback(saved, preview);
        return receipt(saved, false, 1);
    }

    @Transactional(readOnly = true)
    public RecoveryPlan recoveryPlan(String factoryId, String draftId) {
        MaterialTypeDraft row = draftRepository.findByIdAndFactoryId(clean(draftId), clean(factoryId))
                .orElseThrow(() -> invalid(404, "当前工厂没有该物料草稿", "MATERIAL_DRAFT_NOT_FOUND",
                        "刷新当前工厂的物料草稿回执", "draftId"));
        return new RecoveryPlan(MaterialTypeDraftModels.SCHEMA_VERSION, row.getFactoryId(), row.getId(),
                row.getDesiredDigest(), "SOFT_DELETE_DRAFT_AFTER_MODULE_OWNER_CONFIRMATION",
                "只移除未发布物料草稿；不会修改 raw_material_types、库存、采购、BOM 或批次",
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    private RawMaterialType resolveCurrent(
            DraftRequest request, ResolvedMaterial desired, List<DraftIssue> issues) {
        if (request.targetMaterialTypeId().isBlank()) {
            if (!desired.name().isBlank()
                    && materialTypeRepository.existsByFactoryIdAndNormalizedName(
                    request.factoryId(), desired.name())) {
                issues.add(issue("MATERIAL_DRAFT_TARGET_REQUIRED", "targetMaterialTypeId",
                        "当前工厂已有同名物料，不能把更新伪装成新建", "选择现有物料后重新预览"));
            }
            return null;
        }
        Optional<RawMaterialType> found = materialTypeRepository.findByIdAndFactoryId(
                request.targetMaterialTypeId(), request.factoryId());
        if (found.isEmpty()) {
            issues.add(issue("MATERIAL_DRAFT_TARGET_NOT_FOUND", "targetMaterialTypeId",
                    "当前工厂没有可更新的目标物料", "刷新当前工厂物料目录并重新选择"));
            return null;
        }
        RawMaterialType current = found.get();
        if (!desired.name().isBlank() && !desired.name().equals(clean(current.getName()))
                && materialTypeRepository.existsByFactoryIdAndNormalizedNameExcludingId(
                request.factoryId(), desired.name(), current.getId())) {
            issues.add(issue("MATERIAL_DRAFT_NAME_CONFLICT", "name",
                    "当前工厂已有其他同名物料", "使用经业务确认的不同名称"));
        }
        return current;
    }

    private ResolvedMaterial resolveDesired(DraftRequest request, List<DraftIssue> issues) {
        String factoryId = request.factoryId();
        String targetMaterialTypeId = request.targetMaterialTypeId();
        RequestedMaterial requested = request.desired();
        if (requested == null) {
            issues.add(issue("MATERIAL_DRAFT_DESIRED_REQUIRED", "desired",
                    "物料草稿目标不能为空", "补齐物料名称、分类、库存单位与储存信息"));
            return emptyResolved(targetMaterialTypeId);
        }
        String name = clean(requested.name());
        if (name.isBlank()) issues.add(required("name", "物料名称"));
        if (name.length() > 200) {
            issues.add(issue("MATERIAL_DRAFT_NAME_TOO_LONG", "name",
                    "物料名称不能超过 200 字符", "缩短物料业务名称"));
        }

        ResolvedClassification classification = resolveClassification(request, issues);
        String unit = resolveUnit(factoryId, requested.unit(), issues);
        MaterialTypeDraftModels.StorageMode storageMode = requested.storageMode();
        if (classification.packagingCategory() && storageMode != null) {
            issues.add(issue("MATERIAL_DRAFT_PACKAGING_STORAGE_NOT_APPLICABLE", "storageMode",
                    "包材大类不在物料主数据中配置食品储存方式", "清空储存方式；包材环境控制在后续专用配置中确认"));
            storageMode = null;
        }
        if (!classification.packagingCategory() && storageMode == null) {
            issues.add(required("storageMode", "非包材储存方式"));
        }
        if (requested.shelfLifeDays() != null && requested.shelfLifeDays() <= 0) {
            issues.add(issue("MATERIAL_DRAFT_SHELF_LIFE_INVALID", "shelfLifeDays",
                    "保质期天数必须大于 0", "填写正整数，未知或不适用时留空并由模块负责人确认"));
        }
        String naturalKey = clean(targetMaterialTypeId).isBlank()
                ? "NEW:" + normalizeIdentity(name)
                : "EXISTING:" + clean(targetMaterialTypeId);
        return new ResolvedMaterial(naturalKey, name, classification.selectedCode(),
                classification.primaryCode(), classification.category(), unit, storageMode,
                requested.shelfLifeDays(), requested.byproduct(), classification.packagingCategory(),
                classification.source());
    }

    /**
     * Two sources, in this order:
     * <ol>
     *   <li>the factory's segment table: an active L1 (3 digits) or L3 (10 digits). A code that exists
     *       there in any state is decided by the table — an inactive one is refused, it is not
     *       "re-planned" from the blueprint (the promotion would collide with it);</li>
     *   <li>a 3-digit code that is not in the table but is in the <b>current</b> blueprint's
     *       {@code codeSegments} (checked against the request's blueprint id, version and source digest):
     *       a planned L1, created on promotion. Its label is the blueprint's; whether it is a packaging
     *       category is the request's {@code classificationPackagingCategory} (no blueprint fact).</li>
     * </ol>
     */
    private ResolvedClassification resolveClassification(DraftRequest request, List<DraftIssue> issues) {
        String factoryId = request.factoryId();
        RequestedMaterial requested = request.desired();
        String code = clean(requested.classificationSegmentCode());
        Boolean requestedPackaging = requested.classificationPackagingCategory();
        if (code.startsWith("category:")) {
            String category = code.substring("category:".length());
            if (!Set.of("原料", "辅料", "包材").contains(category)) {
                issues.add(issue("MATERIAL_DRAFT_BASIC_CATEGORY_INVALID", "classificationSegmentCode",
                        "物料基本类型必须明确选择原料、辅料或包材", "重新选择物料基本类型"));
                return ResolvedClassification.unresolved(code);
            }
            return requirePackagingAgrees(new ResolvedClassification("", "", category,
                    "包材".equals(category), "BASIC_CATEGORY"), requestedPackaging, issues);
        }

        if (!code.matches("[0-9]+")) {
            issues.add(issue("MATERIAL_DRAFT_CLASSIFICATION_INVALID", "classificationSegmentCode",
                    "物料分类必须选择本工厂启用的 L1 大类或 L3 小类，或当前蓝图里计划新建的大类", "从当前工厂分类树重新选择"));
            return ResolvedClassification.unresolved(code);
        }
        Optional<MaterialCodeSegment> selectedFound = findById(factoryId, code);
        if (selectedFound.isEmpty() || !Boolean.TRUE.equals(selectedFound.get().getIsActive())) {
            issues.add(issue("MATERIAL_DRAFT_CLASSIFICATION_NOT_AVAILABLE", "classificationSegmentCode",
                    "所选物料分类不存在、不属于当前工厂或已停用", "刷新当前工厂分类树后重新选择"));
            return ResolvedClassification.unresolved(code);
        }
        MaterialCodeSegment selected = selectedFound.get();
        if (selected.getLevel() != null && selected.getLevel() == 1) {
            issues.add(issue("MATERIAL_DRAFT_CLASSIFICATION_L1_UNSUPPORTED", "classificationSegmentCode",
                    "正式物料接口的可选分类只接受三级节点 ID，一级节点不能作为物料 classificationId",
                    "选择该一级分类下的启用三级分类后重新预览"));
            return ResolvedClassification.unresolved(code);
        }
        if (selected.getLevel() == null || selected.getLevel() != 3) {
            issues.add(issue("MATERIAL_DRAFT_CLASSIFICATION_LEVEL_INVALID", "classificationSegmentCode",
                    "所选编码不是可用的 L1 大类或 L3 小类", "选择完整分类叶子或明确的大类"));
            return ResolvedClassification.unresolved(code);
        }
        MaterialCodeSegment l2 = activeSegment(factoryId, selected.getParentId());
        MaterialCodeSegment l1 = l2 == null ? null : activeSegment(factoryId, l2.getParentId());
        if (l2 == null || l2.getLevel() == null || l2.getLevel() != 2
                || l1 == null || l1.getLevel() == null || l1.getLevel() != 1
                || !Objects.equals(selected.getParentId(), l2.getId())
                || !Objects.equals(l2.getParentId(), l1.getId())) {
            issues.add(issue("MATERIAL_DRAFT_CLASSIFICATION_CHAIN_INVALID", "classificationSegmentCode",
                    "物料分类父链不完整或不一致", "由主数据负责人修复 L1/L2/L3 分类树后重试"));
            return ResolvedClassification.unresolved(code);
        }
        try {
            com.cretas.aims.entity.material.MaterialMainCategory.require(l1.getSegmentLabel());
        } catch (BusinessException invalid) {
            issues.add(issue("MATERIAL_DRAFT_CATEGORY_UNSUPPORTED", "classificationSegmentCode",
                    invalid.getMessage(), "选择正式物料接口已登记的基本类型"));
            return ResolvedClassification.unresolved(code);
        }
        return requirePackagingAgrees(resolved(l1, code), requestedPackaging, issues);
    }

    /**
     * The planned category is created once, by the first promotion; every DRAFT of the same blueprint
     * lineage that names it must describe it the same way, or the second promotion would contradict the
     * first. Label comes from the blueprint (same lineage => same label); the packaging answer is the user's.
     */
    private void requireLineageAgreement(DraftRequest request, String code, String label, boolean packaging,
                                         List<DraftIssue> issues) {
        MaterialTypeDraft probe = new MaterialTypeDraft();
        probe.setFactoryId(request.factoryId());
        probe.setBlueprintId(request.blueprintId());
        probe.setBlueprintVersion(request.blueprintVersion());
        probe.setSourceDigest(request.sourceDigest());
        probe.setDesiredPrimaryCategoryCode(code);
        for (MaterialTypeDraft sibling : draftRepository.findAll(Example.of(probe))) {
            if (!label.equals(clean(sibling.getDesiredCategory()))
                    || packaging != Boolean.TRUE.equals(sibling.getDesiredPackagingCategory())) {
                issues.add(issue("MATERIAL_DRAFT_PLANNED_CLASSIFICATION_INCONSISTENT", "classificationPackagingCategory",
                        "同一份蓝图下的物料草稿“" + clean(sibling.getDesiredName()) + "”把计划大类 " + code + " 记成了“"
                                + clean(sibling.getDesiredCategory()) + "”、"
                                + (Boolean.TRUE.equals(sibling.getDesiredPackagingCategory()) ? "按包材管理" : "不按包材管理")
                                + "，与这次不一致",
                        "与已写入的那份草稿保持一致，或先移除那份草稿"));
                return;
            }
        }
    }

    /** For a category the table decides, an explicit packaging answer must agree with the table. */
    private static ResolvedClassification requirePackagingAgrees(ResolvedClassification resolved, Boolean requested,
                                                                 List<DraftIssue> issues) {
        if (requested != null && requested != resolved.packagingCategory()) {
            issues.add(issue("MATERIAL_DRAFT_CLASSIFICATION_PACKAGING_MISMATCH", "classificationPackagingCategory",
                    "所选大类“" + resolved.category() + "”在本厂分类树里" + (resolved.packagingCategory() ? "按" : "不按")
                            + "包材管理，与请求里的回答不一致", "选本厂已有大类时把“是否按包材管理”留空"));
        }
        return resolved;
    }

    private MaterialCodeSegment activeSegment(String factoryId, Long id) {
        if (id == null) return null;
        return segmentRepository.findByIdAndFactoryId(id, factoryId)
                .filter(item -> Boolean.TRUE.equals(item.getIsActive())).orElse(null);
    }

    private ResolvedClassification resolved(MaterialCodeSegment l1, String selectedCode) {
        // Packaging is a material role carried by RawMaterialType.category in the current contract;
        // taxonomy nodes themselves have no packaging flag.
        return new ResolvedClassification(selectedCode, String.valueOf(l1.getId()),
                clean(l1.getSegmentLabel()), isPackagingLabel(l1.getSegmentLabel()),
                MaterialTypeDraftModels.CLASSIFICATION_SOURCE_FACTORY);
    }

    private static boolean isPackagingLabel(String label) {
        return "包材".equals(clean(label)) || "PACKAGING".equalsIgnoreCase(clean(label))
                || "PACKAGE".equalsIgnoreCase(clean(label));
    }

    private Optional<MaterialCodeSegment> findById(String factoryId, String rawId) {
        try {
            return segmentRepository.findByIdAndFactoryId(Long.valueOf(rawId), factoryId);
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private String resolveUnit(String factoryId, String requestedUnit, List<DraftIssue> issues) {
        String rawUnit = clean(requestedUnit);
        if (rawUnit.isBlank()) {
            issues.add(required("unit", "库存基本单位"));
            return "";
        }
        try {
            UnitNormalizationResult normalized = unitContractService.normalize(factoryId, rawUnit);
            if (!normalized.recognized()
                    || !unitContractService.supportsUsage(
                    factoryId, normalized.code(), UnitUsageScope.INVENTORY_QUANTITY)) {
                issues.add(issue("MATERIAL_DRAFT_UNIT_NOT_RESOLVED", "unit",
                        "该单位未登记或不能用于库存数量", "选择质量、体积、计数或已授权包装单位"));
                return "";
            }
            String storageUnit = clean(unitContractService.storageUnit(factoryId, rawUnit));
            if (storageUnit.isBlank()) {
                issues.add(issue("MATERIAL_DRAFT_UNIT_NOT_RESOLVED", "unit",
                        "权威单位目录未返回可落库单位", "修正单位目录后重试"));
            }
            return storageUnit;
        } catch (RuntimeException failure) {
            issues.add(issue("MATERIAL_DRAFT_UNIT_RESOLUTION_FAILED", "unit",
                    "物料单位无法由权威单位目录解析", "修正单位目录或选择已确认单位后重试"));
            return "";
        }
    }

    private ResolvedMaterial emptyResolved(String targetMaterialTypeId) {
        return new ResolvedMaterial(clean(targetMaterialTypeId).isBlank() ? "NEW:" : "EXISTING:" + clean(targetMaterialTypeId),
                "", "", "", "", "", null, null, false, false, "");
    }

    private static MaterialSnapshot snapshot(RawMaterialType value) {
        if (value == null) return null;
        LocalDateTime updatedAt = value.getUpdatedAt();
        return new MaterialSnapshot(value.getId(), clean(value.getCode()), "",
                clean(value.getName()), value.getClassificationSegmentId() == null ? "" : String.valueOf(value.getClassificationSegmentId()), clean(value.getCategory()),
                clean(value.getUnit()), clean(value.getStorageType()), value.getShelfLifeDays(),
                Boolean.TRUE.equals(value.getIsByproduct()), updatedAt == null ? "" : updatedAt.toString());
    }

    private void validateEnvelope(DraftRequest request, boolean materialize) {
        if (request == null) throw invalid(400, "物料草稿请求不能为空", "MATERIAL_DRAFT_REQUEST_REQUIRED",
                "从已确认的 materials ChangeSet 生成请求", "request");
        if (request.factoryId().isBlank() || request.blueprintId().isBlank() || request.blueprintVersion() <= 0) {
            throw invalid(400, "工厂与 Blueprint 版本不能为空", "MATERIAL_DRAFT_SCOPE_REQUIRED",
                    "锁定 factoryId、blueprintId 和 blueprintVersion", "blueprint");
        }
        if (!request.sourceDigest().matches("[0-9a-f]{64}")) {
            throw invalid(400, "Blueprint sourceDigest 必须是 SHA-256", "MATERIAL_DRAFT_SOURCE_DIGEST_INVALID",
                    "使用服务器编译工件的 sourceDigest", "sourceDigest");
        }
        if (materialize) {
            if (request.operatorId() <= 0) throw invalid(400, "物料草稿必须记录操作人",
                    "MATERIAL_DRAFT_OPERATOR_REQUIRED", "使用已认证模块负责人身份", "operatorId");
            if (request.idempotencyKey().isBlank() || request.idempotencyKey().length() > 128) {
                throw invalid(400, "物料草稿幂等键不能为空且不能超过 128 字符",
                        "MATERIAL_DRAFT_IDEMPOTENCY_KEY_INVALID", "为本次确认生成稳定幂等键", "idempotencyKey");
            }
            if (!request.confirmedPreviewDigest().matches("[0-9a-f]{64}")) {
                throw invalid(400, "必须确认服务器生成的 previewDigest",
                        "MATERIAL_DRAFT_CONFIRMED_PREVIEW_REQUIRED", "重新预览并确认当前摘要",
                        "confirmedPreviewDigest");
            }
        }
    }

    private DraftReceipt replay(MaterialTypeDraft row, String requestDigest) {
        if (!row.getRequestDigest().equals(requestDigest)) {
            throw invalid(409, "该幂等键已绑定另一份物料草稿载荷",
                    "MATERIAL_DRAFT_IDEMPOTENCY_KEY_CONFLICT", "复用原请求或生成新幂等键", "idempotencyKey");
        }
        return receipt(row, true, 0);
    }

    private DraftReceipt receipt(MaterialTypeDraft row, boolean replayed, int writes) {
        return new DraftReceipt(MaterialTypeDraftModels.SCHEMA_VERSION, row.getId(), row.getFactoryId(),
                row.getId(), row.getStatus(), row.getNaturalKey(), clean(row.getTargetMaterialTypeId()),
                row.getDesiredDigest(), row.getPreviewDigest(), row.getRequestDigest(),
                Objects.requireNonNullElse(row.getLockVersion(), 0L), replayed, writes, 1,
                "NOT_PUBLISHED", "NOT_ACTIVATED",
                "由物料模块负责人确认后软删除该 DRAFT；正式物料、库存、采购、BOM 与批次不受影响");
    }

    private void requireExactReadback(MaterialTypeDraft row, DraftPreview preview) {
        if (row == null || !"DRAFT".equals(row.getStatus())
                || !preview.factoryId().equals(row.getFactoryId())
                || !preview.desired().naturalKey().equals(row.getNaturalKey())
                || !preview.desired().classificationSegmentCode().equals(row.getDesiredClassificationSegmentCode())
                || !preview.desired().primaryCategoryCode().equals(row.getDesiredPrimaryCategoryCode())
                || !preview.desired().category().equals(row.getDesiredCategory())
                || !preview.desired().unit().equals(row.getDesiredUnit())
                || !preview.desiredDigest().equals(row.getDesiredDigest())
                || !preview.previewDigest().equals(row.getPreviewDigest())) {
            throw invalid(409, "物料草稿回读与已确认 ChangeSet 不一致",
                    "MATERIAL_DRAFT_READBACK_MISMATCH", "保留事务回滚并重新预览", "draft");
        }
    }

    private String requestDigest(DraftRequest request, com.cretas.aims.security.ActorRef actor) {
        RequestedMaterial desired = request.desired();
        List<String> values = new ArrayList<>(List.of(MaterialTypeDraftModels.SCHEMA_VERSION, request.factoryId(),
                request.blueprintId(), String.valueOf(request.blueprintVersion()), request.sourceDigest(),
                request.targetMaterialTypeId(),
                desired == null ? "" : desired.name(),
                desired == null ? "" : desired.classificationSegmentCode(),
                desired == null ? "" : desired.unit(),
                desired == null || desired.storageMode() == null ? "" : desired.storageMode().name(),
                desired == null || desired.shelfLifeDays() == null ? "" : desired.shelfLifeDays().toString(),
                desired == null ? "" : String.valueOf(desired.byproduct()),
                request.confirmedPreviewDigest(), request.idempotencyKey(), actor.type().name() + ":" + String.valueOf(actor.id())));
        // Appended only when answered, so the digest of every request that never carried the field (all
        // DRAFTs written before planned categories existed) is unchanged and their replays still match.
        if (desired != null && desired.classificationPackagingCategory() != null) {
            values.add("classificationPackagingCategory=" + desired.classificationPackagingCategory());
        }
        return digest(values.toArray(String[]::new));
    }

    private String desiredDigest(ResolvedMaterial desired) {
        List<String> values = new ArrayList<>(List.of(desired.naturalKey(), desired.name(),
                desired.classificationSegmentCode(), desired.primaryCategoryCode(), desired.category(), desired.unit(),
                desired.storageMode() == null ? "" : desired.storageMode().name(),
                desired.shelfLifeDays() == null ? "" : desired.shelfLifeDays().toString(),
                String.valueOf(desired.byproduct()), String.valueOf(desired.packagingCategory())));
        // Same reasoning: a category from the segment table digests exactly as before.
        if (MaterialTypeDraftModels.CLASSIFICATION_SOURCE_BLUEPRINT_PLANNED.equals(desired.classificationSource())) {
            values.add("classificationSource=" + desired.classificationSource());
        }
        return digest(values.toArray(String[]::new));
    }

    private String issueDigest(List<DraftIssue> issues) {
        return digest(issues.stream()
                .map(issue -> issue.code() + "\u0000" + issue.target() + "\u0000" + issue.message())
                .toArray(String[]::new));
    }

    private boolean isScopedIdempotencyRace(Throwable failure) {
        Throwable cursor = failure;
        while (cursor != null) {
            if (clean(cursor.getMessage()).toLowerCase(Locale.ROOT)
                    .contains("uk_material_type_draft_idempotency")) return true;
            cursor = cursor.getCause();
        }
        return false;
    }

    private static String digest(String... values) {
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

    private static String normalizeIdentity(String value) {
        return Normalizer.normalize(clean(value), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static DraftIssue required(String target, String label) {
        return issue("MATERIAL_DRAFT_FIELD_REQUIRED", target, label + "不能为空", "补齐并确认该字段");
    }

    private static DraftIssue issue(String code, String target, String message, String recovery) {
        return new DraftIssue(code, target, message, recovery);
    }

    private static String clean(String value) {
        return MaterialTypeDraftModels.clean(value);
    }

    private static BusinessException invalid(
            int status, String message, String code, String recovery, String target) {
        return new BusinessException(status, message).withCode(code).withHint(recovery)
                .withHintTarget(target).withSeverity("warning");
    }

    private record ResolvedClassification(
            String selectedCode, String primaryCode, String category, boolean packagingCategory, String source) {
        static ResolvedClassification unresolved(String code) {
            return new ResolvedClassification(code, "", "", false, "");
        }
    }
}
