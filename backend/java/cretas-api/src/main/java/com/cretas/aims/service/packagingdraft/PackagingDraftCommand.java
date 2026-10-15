package com.cretas.aims.service.packagingdraft;

import com.cretas.aims.entity.MaterialPackagingHierarchy;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.material.MaterialPackagingSpec;
import com.cretas.aims.entity.material.MaterialTypeDraft;
import com.cretas.aims.entity.packagingdraft.PackagingDraft;
import com.cretas.aims.entity.packagingdraft.PackagingDraftItem;
import com.cretas.aims.entity.product.ProductPackagingSpec;
import com.cretas.aims.entity.product.ProductTypeDraft;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialPackagingHierarchyRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.material.MaterialPackagingSpecRepository;
import com.cretas.aims.repository.material.MaterialTypeDraftRepository;
import com.cretas.aims.repository.packagingdraft.PackagingDraftItemRepository;
import com.cretas.aims.repository.packagingdraft.PackagingDraftRepository;
import com.cretas.aims.repository.product.ProductPackagingSpecRepository;
import com.cretas.aims.repository.product.ProductTypeDraftRepository;
import com.cretas.aims.service.packagingdraft.PackagingDraftModels.AuthenticatedOperator;
import com.cretas.aims.service.packagingdraft.PackagingDraftModels.DraftIssue;
import com.cretas.aims.service.packagingdraft.PackagingDraftModels.DraftPreview;
import com.cretas.aims.service.packagingdraft.PackagingDraftModels.DraftReadback;
import com.cretas.aims.service.packagingdraft.PackagingDraftModels.DraftReceipt;
import com.cretas.aims.service.packagingdraft.PackagingDraftModels.DraftRequest;
import com.cretas.aims.service.packagingdraft.PackagingDraftModels.LabelReference;
import com.cretas.aims.service.packagingdraft.PackagingDraftModels.LabelReferenceType;
import com.cretas.aims.service.packagingdraft.PackagingDraftModels.PackagingItem;
import com.cretas.aims.service.packagingdraft.PackagingDraftModels.PackagingRole;
import com.cretas.aims.service.packagingdraft.PackagingDraftModels.RecoveryPlan;
import com.cretas.aims.service.packagingdraft.PackagingDraftModels.TargetKind;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDimension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
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

@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class PackagingDraftCommand {
    public static final String CAPABILITY_ID = "packaging";
    public static final String REQUIRED_PERMISSION = "packaging:draft:write";
    private static final int MAX_ITEMS = 200;
    private static final BigDecimal MAX_FACTOR = new BigDecimal("1000000000000");
    private static final Set<UnitDimension> PACKAGE_UNIT_DIMENSIONS =
            Set.of(UnitDimension.PACKAGE, UnitDimension.COUNT);
    private static final Set<UnitDimension> CONTENT_UNIT_DIMENSIONS =
            Set.of(UnitDimension.MASS, UnitDimension.VOLUME, UnitDimension.COUNT, UnitDimension.PACKAGE);
    private static final Set<UnitDimension> NET_CONTENT_DIMENSIONS =
            Set.of(UnitDimension.MASS, UnitDimension.VOLUME, UnitDimension.COUNT);

    private final PackagingDraftRepository drafts;
    private final PackagingDraftItemRepository draftItems;
    private final ProductTypeRepository products;
    private final RawMaterialTypeRepository materials;
    private final ProductTypeDraftRepository productDrafts;
    private final MaterialTypeDraftRepository materialDrafts;
    private final ProductPackagingSpecRepository productPackaging;
    private final MaterialPackagingSpecRepository materialPackaging;
    private final MaterialPackagingHierarchyRepository materialHierarchies;
    private final UnitContractService units;
    private final UserRepository users;

    public PackagingDraftCommand(
            PackagingDraftRepository drafts,
            PackagingDraftItemRepository draftItems,
            ProductTypeRepository products,
            RawMaterialTypeRepository materials,
            ProductTypeDraftRepository productDrafts,
            MaterialTypeDraftRepository materialDrafts,
            ProductPackagingSpecRepository productPackaging,
            MaterialPackagingSpecRepository materialPackaging,
            MaterialPackagingHierarchyRepository materialHierarchies,
            UnitContractService units,
            UserRepository users) {
        this.drafts = drafts;
        this.draftItems = draftItems;
        this.products = products;
        this.materials = materials;
        this.productDrafts = productDrafts;
        this.materialDrafts = materialDrafts;
        this.productPackaging = productPackaging;
        this.materialPackaging = materialPackaging;
        this.materialHierarchies = materialHierarchies;
        this.units = units;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public DraftPreview preview(DraftRequest request) {
        validateEnvelope(request, false);
        List<DraftIssue> issues = new ArrayList<>();
        StringBuilder sourceSnapshot = new StringBuilder();
        Map<Integer, ResolvedItem> resolved = new HashMap<>();
        Map<String, List<Integer>> groups = new LinkedHashMap<>();

        for (int index = 0; index < request.items().size(); index++) {
            PackagingItem item = request.items().get(index);
            String target = "items[" + index + "]";
            validateItemShape(item, target, issues);
            if (item == null) {
                sourceSnapshot.append("missing-item|");
                continue;
            }
            if (item.target() == null || item.target().kind() == null
                    || item.target().targetId().isBlank()) {
                issues.add(issue("PACKAGING_TARGET_REQUIRED", target,
                        "包装必须指向一个产品、物料或同蓝图草稿", "选择当前工厂的 typed target"));
                sourceSnapshot.append("missing-target|");
                continue;
            }

            TargetResolution targetResolution = resolveTarget(request, item, target, issues);
            CanonicalUnit packageUnit = resolveUnit(request.factoryId(), item.packageUnit(),
                    PACKAGE_UNIT_DIMENSIONS, target + ".packageUnit", issues);
            CanonicalUnit containedUnit = resolveUnit(request.factoryId(), item.containedUnit(),
                    CONTENT_UNIT_DIMENSIONS, target + ".containedUnit", issues);
            CanonicalUnit netContentUnit = item.netContentUnit().isBlank() ? null
                    : resolveUnit(request.factoryId(), item.netContentUnit(), NET_CONTENT_DIMENSIONS,
                            target + ".netContentUnit", issues);
            validateLabel(item, targetResolution, target, issues);

            sourceSnapshot.append("target:").append(targetResolution.sourceDigest()).append('|');
            sourceSnapshot.append("package-unit:").append(unitDigest(packageUnit)).append('|');
            sourceSnapshot.append("contained-unit:").append(unitDigest(containedUnit)).append('|');
            sourceSnapshot.append("net-unit:").append(unitDigest(netContentUnit)).append('|');
            sourceSnapshot.append("label:")
                    .append(item.labelReference().type()).append(':')
                    .append(item.labelReference().referenceId()).append(':')
                    .append(item.labelReference().referenceDigest()).append('|');

            resolved.put(index, new ResolvedItem(item, targetResolution, packageUnit, containedUnit,
                    netContentUnit));
            groups.computeIfAbsent(targetIdentity(item), ignored -> new ArrayList<>()).add(index);
        }

        for (Map.Entry<String, List<Integer>> group : groups.entrySet()) {
            validateHierarchy(request.factoryId(), group.getValue(), resolved, issues);
        }

        issues.sort(Comparator.comparing(DraftIssue::code).thenComparing(DraftIssue::target));
        String sourceSnapshotDigest = digest(sourceSnapshot.toString());
        String desiredDigest = desiredDigest(request.items());
        String status = issues.isEmpty() ? "READY" : "BLOCKED";
        String previewDigest = digest(schemaVersion(), request.factoryId(), request.blueprintId(),
                String.valueOf(request.blueprintVersion()), request.sourceDigest(),
                sourceSnapshotDigest, desiredDigest, status);
        return new DraftPreview(schemaVersion(), status, request.factoryId(),
                List.of("products", "materials"), request.items(), sourceSnapshotDigest,
                desiredDigest, previewDigest, issues, 0, 0, 0, 0, 0, 0, 0, 0,
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    @Transactional
    public DraftReceipt materialize(DraftRequest request, AuthenticatedOperator operator) {
        validateEnvelope(request, true);
        authorize(request, operator);
        String requestDigest = requestDigest(request, operator);
        Optional<PackagingDraft> prior = drafts
                .findByFactoryIdAndIdempotencyKey(request.factoryId(), request.idempotencyKey());
        if (prior.isPresent()) {
            if (!requestDigest.equals(prior.get().getRequestDigest())) {
                throw invalid(409, "幂等键对应不同包装请求", "PACKAGING_IDEMPOTENCY_CONFLICT");
            }
            readBack(request.factoryId(), prior.get().getId());
            return receipt(prior.get(), true, 0, 0);
        }

        DraftPreview preview = preview(request);
        if (!"READY".equals(preview.status())) {
            throw invalid(409, "包装草稿预览被阻断", "PACKAGING_PREVIEW_BLOCKED");
        }
        if (!request.confirmedPreviewDigest().equals(preview.previewDigest())) {
            throw invalid(409, "包装预览摘要已变化", "PACKAGING_PREVIEW_DIGEST_CONFLICT");
        }

        int targetCount = (int) request.items().stream().map(this::targetIdentity).distinct().count();
        PackagingDraft parent = PackagingDraft.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(request.factoryId())
                .blueprintId(request.blueprintId())
                .blueprintVersion(request.blueprintVersion())
                .sourceDigest(request.sourceDigest())
                .sourceSnapshotDigest(preview.sourceSnapshotDigest())
                .desiredDigest(preview.desiredDigest())
                .previewDigest(preview.previewDigest())
                .requestDigest(requestDigest)
                .idempotencyKey(request.idempotencyKey())
                .operatorId(operator.actor().factoryUserId()).actorType(operator.actor().type()).actorId(operator.actor().id())
                .itemCount(request.items().size())
                .targetCount(targetCount)
                .dependencyBasis(PackagingDraftModels.DEPENDENCY_BASIS)
                .dependencyClosureComplete(true)
                .status("DRAFT")
                .build();
        PackagingDraft saved;
        try {
            saved = drafts.saveAndFlush(parent);
        } catch (DataIntegrityViolationException exception) {
            if (!isIdempotencyRace(exception)) throw exception;
            throw invalid(409, "包装草稿幂等键发生并发竞争", "PACKAGING_IDEMPOTENCY_RACE");
        }

        List<PackagingDraftItem> rows = new ArrayList<>();
        for (int index = 0; index < request.items().size(); index++) {
            rows.add(toRow(saved.getId(), request.factoryId(), index, request.items().get(index)));
        }
        draftItems.saveAllAndFlush(rows);
        DraftReadback readback = readBack(saved.getFactoryId(), saved.getId());
        if (!preview.desiredDigest().equals(desiredDigest(readback.items()))) {
            throw invalid(409, "包装草稿回读摘要不一致", "PACKAGING_READBACK_MISMATCH");
        }
        DraftPreview authorityReadback = preview(request);
        if (!"READY".equals(authorityReadback.status())
                || !preview.sourceSnapshotDigest().equals(authorityReadback.sourceSnapshotDigest())
                || !preview.previewDigest().equals(authorityReadback.previewDigest())) {
            throw invalid(409, "包装权威在草稿写入期间发生变化",
                    "PACKAGING_AUTHORITY_DRIFT_DURING_WRITE");
        }
        return receipt(saved, false, 1, 1 + rows.size());
    }

    /**
     * Read-only packaging-domain authority used when another draft references a packaging target.
     * The returned digest is the digest stored in {@link PackagingDraftModels.TargetRef}; it is
     * intentionally different from BOM's own product/material snapshot digest.
     */
    public Optional<TargetAuthority> currentTargetAuthority(String factoryId, String blueprintId,
            long blueprintVersion, String sourceDigest, TargetKind kind, String id) {
        if (factoryId == null || factoryId.isBlank() || kind == null || id == null || id.isBlank()) {
            return Optional.empty();
        }
        DraftRequest context = new DraftRequest(factoryId, blueprintId, blueprintVersion,
                sourceDigest, List.of(), null, null, 0L);
        List<DraftIssue> issues = new ArrayList<>();
        TargetResolution resolution = switch (kind) {
            case PRODUCT -> resolveProduct(context, id, "target", issues);
            case PRODUCT_DRAFT -> resolveProductDraft(context, id, "target", issues);
            case MATERIAL -> resolveMaterial(context, id, "target", issues);
            case MATERIAL_DRAFT -> resolveMaterialDraft(context, id, "target", issues);
        };
        if (!issues.isEmpty() || resolution.currentDigest().isBlank()
                || resolution.currentDigest().equals(resolution.sourceDigest())) {
            return Optional.empty();
        }
        return Optional.of(new TargetAuthority(kind, id, resolution.currentDigest(), resolution.baseUnit()));
    }

    @Transactional(readOnly = true)
    public DraftReadback readBack(String factoryId, String draftId) {
        PackagingDraft parent = drafts.findByIdAndFactoryId(draftId, factoryId)
                .orElseThrow(() -> invalid(404, "包装草稿不存在", "PACKAGING_NOT_FOUND"));
        if (!"DRAFT".equals(parent.getStatus())
                || !Boolean.TRUE.equals(parent.getDependencyClosureComplete())
                || !PackagingDraftModels.DEPENDENCY_BASIS.equals(parent.getDependencyBasis())) {
            throw invalid(409, "包装草稿依赖闭包或状态无效", "PACKAGING_READBACK_INVARIANT_BROKEN");
        }
        List<PackagingItem> items = draftItems
                .findByDraftIdAndFactoryIdOrderByOrdinal(draftId, factoryId)
                .stream().map(this::fromRow).toList();
        int targetCount = (int) items.stream().map(this::targetIdentity).distinct().count();
        if (items.size() != parent.getItemCount()
                || targetCount != parent.getTargetCount()
                || !desiredDigest(items).equals(parent.getDesiredDigest())) {
            throw invalid(409, "包装草稿回读不完整", "PACKAGING_READBACK_MISMATCH");
        }
        return new DraftReadback(schemaVersion(), factoryId, draftId, parent.getStatus(), items,
                0, 0, 0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    @Transactional(readOnly = true)
    public RecoveryPlan recoveryPlan(String factoryId, String draftId) {
        PackagingDraft parent = drafts.findByIdAndFactoryId(draftId, factoryId)
                .orElseThrow(() -> invalid(404, "包装草稿不存在", "PACKAGING_NOT_FOUND"));
        if (!"DRAFT".equals(parent.getStatus())) {
            throw invalid(409, "只有未发布包装草稿可恢复", "PACKAGING_RECOVERY_NOT_ALLOWED");
        }
        return new RecoveryPlan(schemaVersion(), factoryId, draftId, parent.getDesiredDigest(),
                "ARCHIVE_DRAFT_AFTER_PACKAGING_OWNER_CONFIRMATION",
                "只归档未发布包装草稿；不改变产品、物料、标签、库存、批次或 BOM",
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    private void authorize(DraftRequest request, AuthenticatedOperator operator) {
        if (operator == null || ((operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER && operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.PLATFORM_ADMIN) && operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.PLATFORM_ADMIN) || operator.actor().id() != request.operatorId()
                || !request.factoryId().equals(operator.factoryId())) {
            throw invalid(403, "操作人和工厂范围不一致", "PACKAGING_OPERATOR_SCOPE_MISMATCH");
        }
        if (!operator.permissions().contains(REQUIRED_PERMISSION)) {
            throw invalid(403, "缺少包装草稿权限", "PACKAGING_PERMISSION_REQUIRED");
        }
        if (operator.actor().type() == com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER
                && users.findByIdAndFactoryId(operator.actor().id(), operator.factoryId())
                .filter(user -> Boolean.TRUE.equals(user.getIsActive())).isEmpty()) {
            throw invalid(403, "操作人不是当前工厂有效成员",
                    "PACKAGING_OPERATOR_MEMBERSHIP_REQUIRED");
        }
    }

    private void validateEnvelope(DraftRequest request, boolean materialize) {
        if (request == null || request.factoryId().isBlank() || request.factoryId().length() > 50
                || request.blueprintId().isBlank() || request.blueprintId().length() > 100
                || request.blueprintVersion() <= 0 || !isDigest(request.sourceDigest())
                || request.items().isEmpty() || request.items().size() > MAX_ITEMS) {
            throw invalid(400, "Blueprint envelope 或包装条目数量无效", "PACKAGING_SCOPE_REQUIRED");
        }
        if (materialize && (request.operatorId() <= 0 || request.idempotencyKey().isBlank()
                || request.idempotencyKey().length() > 128
                || !isDigest(request.confirmedPreviewDigest()))) {
            throw invalid(400, "操作人、幂等键或确认摘要无效", "PACKAGING_OPERATOR_REQUIRED");
        }
    }

    private void validateItemShape(PackagingItem item, String target, List<DraftIssue> issues) {
        if (item == null) {
            issues.add(issue("PACKAGING_ITEM_REQUIRED", target, "包装条目不能为空", "补充 typed 包装条目"));
            return;
        }
        if (item.role() == null || isBlankOrTooLong(item.specName(), 80)
                || isBlankOrTooLong(item.packageUnit(), 20)
                || isBlankOrTooLong(item.containedUnit(), 20)
                || isBlankOrTooLong(item.changeReason(), 500)) {
            issues.add(issue("PACKAGING_ITEM_FIELDS_REQUIRED", target,
                    "包装角色、名称、单位和变更原因均必填", "补全包装字段"));
        }
        if (item.unitsPerPackage() == null || item.unitsPerPackage().signum() <= 0
                || item.unitsPerPackage().compareTo(MAX_FACTOR) > 0
                || item.unitsPerPackage().precision() > 20 || item.unitsPerPackage().scale() > 8) {
            issues.add(issue("PACKAGING_FACTOR_INVALID", target + ".unitsPerPackage",
                    "每包装包含数量必须为精确正数且不超过 8 位小数", "修正显式包装包含比例"));
        }
        boolean hasNetQuantity = item.netContentQuantity() != null;
        boolean hasNetUnit = !item.netContentUnit().isBlank();
        if (hasNetQuantity != hasNetUnit || (hasNetQuantity
                && (item.role() != PackagingRole.PRIMARY_CONTAINER
                || item.netContentQuantity().signum() <= 0
                || item.netContentQuantity().compareTo(MAX_FACTOR) > 0
                || item.netContentQuantity().precision() > 20
                || item.netContentQuantity().scale() > 8))) {
            issues.add(issue("PACKAGING_NET_CONTENT_INVALID", target,
                    "净含量必须成对提供且只属于一级包装", "修正净含量数量和单位"));
        }
        if (item.defaultSpec() && item.role() != PackagingRole.PRIMARY_CONTAINER) {
            issues.add(issue("PACKAGING_DEFAULT_ROLE_INVALID", target,
                    "只有一级包装可以标记为默认", "把默认标记放到一级包装"));
        }
        if (item.sortOrder() < 0 || item.sortOrder() > 10_000) {
            issues.add(issue("PACKAGING_SORT_ORDER_INVALID", target + ".sortOrder",
                    "排序值必须在 0 到 10000 之间", "修正排序值"));
        }
        if (item.packageUnit().equalsIgnoreCase(item.containedUnit())) {
            issues.add(issue("PACKAGING_SELF_CONTAINMENT_FORBIDDEN", target,
                    "包装单位不能包含自身", "选择明确的内层单位"));
        }
    }

    private TargetResolution resolveTarget(DraftRequest request, PackagingItem item, String target,
            List<DraftIssue> issues) {
        TargetKind kind = item.target().kind();
        String id = item.target().targetId();
        TargetResolution resolution;
        switch (kind) {
            case PRODUCT -> resolution = resolveProduct(request, id, target, issues);
            case PRODUCT_DRAFT -> resolution = resolveProductDraft(request, id, target, issues);
            case MATERIAL -> resolution = resolveMaterial(request, id, target, issues);
            case MATERIAL_DRAFT -> resolution = resolveMaterialDraft(request, id, target, issues);
            default -> throw new IllegalStateException("Unsupported target kind: " + kind);
        }
        if (!isDigest(item.target().targetSnapshotDigest())
                || !resolution.currentDigest().equals(item.target().targetSnapshotDigest())) {
            issues.add(issue("PACKAGING_TARGET_SNAPSHOT_CONFLICT", target + ".target",
                    "产品或物料快照已变化", "重新读取当前 packaging authority 并生成预览"));
        }
        return resolution;
    }

    private TargetResolution resolveProduct(DraftRequest request, String id, String target,
            List<DraftIssue> issues) {
        ProductType row = products.findByIdAndFactoryId(id, request.factoryId()).orElse(null);
        if (row == null) {
            issues.add(issue("PACKAGING_PRODUCT_NOT_FOUND", target,
                    "产品不属于当前工厂或不存在", "重新选择当前工厂产品"));
            return missingTarget("PRODUCT", id);
        }
        if (!Boolean.TRUE.equals(row.getIsActive())) {
            issues.add(issue("PACKAGING_PRODUCT_INACTIVE", target,
                    "停用产品不能建立新包装草稿", "选择有效产品"));
        }
        String authority = digest(productDigest(row), productPackagingDigest(
                productPackaging.findByFactoryIdAndProductTypeIdOrderBySortOrderAscCreatedAtAsc(
                        request.factoryId(), id)));
        return new TargetResolution(row.getUnit(), authority, digest("PRODUCT", id, authority));
    }

    private TargetResolution resolveProductDraft(DraftRequest request, String id, String target,
            List<DraftIssue> issues) {
        ProductTypeDraft row = productDrafts.findByIdAndFactoryId(id, request.factoryId()).orElse(null);
        if (row == null) {
            issues.add(issue("PACKAGING_PRODUCT_DRAFT_NOT_FOUND", target,
                    "产品草稿不属于当前工厂或不存在", "重新选择 A1 产品草稿"));
            return missingTarget("PRODUCT_DRAFT", id);
        }
        if (!"DRAFT".equals(row.getStatus())
                || !request.factoryId().equals(row.getFactoryId())
                || !Objects.equals(row.getBlueprintId(), request.blueprintId())
                || !Objects.equals(row.getBlueprintVersion(), request.blueprintVersion())
                || !Objects.equals(row.getSourceDigest(), request.sourceDigest())) {
            issues.add(issue("PACKAGING_PRODUCT_DRAFT_LINEAGE_CONFLICT", target,
                    "A1 产品草稿不属于同一 Blueprint lineage", "选择同一蓝图版本的产品草稿"));
        }
        String authority = digest("PRODUCT_DRAFT", row.getId(), row.getDesiredDigest(),
                row.getDesiredUnit(), row.getDesiredName(), row.getStatus(),
                String.valueOf(row.getLockVersion()), String.valueOf(row.getUpdatedAt()));
        return new TargetResolution(row.getDesiredUnit(), authority,
                digest("PRODUCT_DRAFT", id, authority));
    }

    private TargetResolution resolveMaterial(DraftRequest request, String id, String target,
            List<DraftIssue> issues) {
        RawMaterialType row = materials.findByIdAndFactoryId(id, request.factoryId()).orElse(null);
        if (row == null) {
            issues.add(issue("PACKAGING_MATERIAL_NOT_FOUND", target,
                    "物料不属于当前工厂或不存在", "重新选择当前工厂物料"));
            return missingTarget("MATERIAL", id);
        }
        if (!Boolean.TRUE.equals(row.getIsActive())) {
            issues.add(issue("PACKAGING_MATERIAL_INACTIVE", target,
                    "停用物料不能建立新包装草稿", "选择有效物料"));
        }
        MaterialPackagingHierarchy hierarchy = materialHierarchies.findByFactoryId(request.factoryId())
                .stream().filter(item -> id.equals(item.getMaterialTypeId())).findFirst().orElse(null);
        String authority = digest(materialDigest(row), materialPackagingDigest(
                materialPackaging.findByFactoryIdAndMaterialTypeIdOrderBySortOrderAscCreatedAtAsc(
                        request.factoryId(), id)), hierarchyDigest(hierarchy));
        return new TargetResolution(row.getUnit(), authority, digest("MATERIAL", id, authority));
    }

    private TargetResolution resolveMaterialDraft(DraftRequest request, String id, String target,
            List<DraftIssue> issues) {
        MaterialTypeDraft row = materialDrafts.findByIdAndFactoryId(id, request.factoryId()).orElse(null);
        if (row == null) {
            issues.add(issue("PACKAGING_MATERIAL_DRAFT_NOT_FOUND", target,
                    "物料草稿不属于当前工厂或不存在", "重新选择 A2 物料草稿"));
            return missingTarget("MATERIAL_DRAFT", id);
        }
        if (!"DRAFT".equals(row.getStatus())
                || !request.factoryId().equals(row.getFactoryId())
                || !Objects.equals(row.getBlueprintId(), request.blueprintId())
                || !Objects.equals(row.getBlueprintVersion(), request.blueprintVersion())
                || !Objects.equals(row.getSourceDigest(), request.sourceDigest())) {
            issues.add(issue("PACKAGING_MATERIAL_DRAFT_LINEAGE_CONFLICT", target,
                    "A2 物料草稿不属于同一 Blueprint lineage", "选择同一蓝图版本的物料草稿"));
        }
        String authority = digest("MATERIAL_DRAFT", row.getId(), row.getDesiredDigest(),
                row.getDesiredUnit(), row.getDesiredName(), row.getStatus(),
                String.valueOf(row.getLockVersion()), String.valueOf(row.getUpdatedAt()));
        return new TargetResolution(row.getDesiredUnit(), authority,
                digest("MATERIAL_DRAFT", id, authority));
    }

    private TargetResolution missingTarget(String kind, String id) {
        String missing = digest("MISSING", kind, id);
        return new TargetResolution("", missing, missing);
    }

    private CanonicalUnit resolveUnit(String factoryId, String rawUnit, Set<UnitDimension> allowed,
            String target, List<DraftIssue> issues) {
        if (rawUnit == null || rawUnit.isBlank()) return null;
        CanonicalUnit unit = units.describe(factoryId, rawUnit).orElse(null);
        if (unit == null || !unit.active()) {
            issues.add(issue("PACKAGING_UNIT_UNKNOWN", target,
                    "单位不在当前工厂有效单位目录", "选择当前工厂已登记单位"));
            return null;
        }
        if (!allowed.contains(unit.dimension())) {
            issues.add(issue("PACKAGING_UNIT_DIMENSION_INVALID", target,
                    "单位量纲不适用于该包装字段", "使用包装、数量、质量或容量的正确 typed 字段"));
        }
        return unit;
    }

    private void validateLabel(PackagingItem item, TargetResolution resolution, String target,
            List<DraftIssue> issues) {
        LabelReference label = item.labelReference();
        if (label.type() == null) {
            issues.add(issue("PACKAGING_LABEL_REFERENCE_TYPE_REQUIRED", target + ".labelReference",
                    "标签引用类型必须明确", "选择 NONE、当前目标快照或已确认证据"));
            return;
        }
        if (label.type() == LabelReferenceType.NONE) {
            if (!label.referenceId().isBlank() || !label.referenceDigest().isBlank()) {
                issues.add(issue("PACKAGING_LABEL_REFERENCE_FORBIDDEN", target + ".labelReference",
                        "NONE 标签引用不能携带 ID 或摘要", "清空标签引用字段"));
            }
            return;
        }
        if (label.referenceId().isBlank() || label.referenceId().length() > 200
                || !label.referenceId().matches("[A-Za-z0-9][A-Za-z0-9:/._-]{0,199}")
                || !isDigest(label.referenceDigest())) {
            issues.add(issue("PACKAGING_LABEL_REFERENCE_INVALID", target + ".labelReference",
                    "标签引用必须是 typed ID 和 64 位摘要", "重新选择已确认的当前工厂标签证据"));
            return;
        }
        if (label.type() == LabelReferenceType.CURRENT_TARGET_SNAPSHOT
                && (!label.referenceId().equals(item.target().targetId())
                || !label.referenceDigest().equals(resolution.currentDigest()))) {
            issues.add(issue("PACKAGING_LABEL_REFERENCE_DRIFT", target + ".labelReference",
                    "标签引用的目标快照已变化", "重新读取当前目标标签快照"));
        }
    }

    private void validateHierarchy(String factoryId, List<Integer> indexes,
            Map<Integer, ResolvedItem> resolved, List<DraftIssue> issues) {
        EnumMap<PackagingRole, Integer> byRole = new EnumMap<>(PackagingRole.class);
        Set<Integer> sortOrders = new HashSet<>();
        for (Integer index : indexes) {
            ResolvedItem item = resolved.get(index);
            if (item == null || item.item().role() == null) continue;
            Integer prior = byRole.putIfAbsent(item.item().role(), index);
            if (prior != null) {
                issues.add(issue("PACKAGING_ROLE_DUPLICATE", "items[" + index + "]",
                        "同一目标的包装层级角色不能重复", "合并重复层级"));
            }
            if (!sortOrders.add(item.item().sortOrder())) {
                issues.add(issue("PACKAGING_SORT_ORDER_DUPLICATE", "items[" + index + "]",
                        "同一目标的排序值不能重复", "为包装层级设置稳定顺序"));
            }
        }
        Integer primaryIndex = byRole.get(PackagingRole.PRIMARY_CONTAINER);
        if (primaryIndex == null) {
            issues.add(issue("PACKAGING_PRIMARY_REQUIRED", "items[" + indexes.get(0) + "]",
                    "每个目标必须从一级包装开始形成闭包", "补充 PRIMARY_CONTAINER"));
            return;
        }
        ResolvedItem primary = resolved.get(primaryIndex);
        validateContainedUnit(factoryId, primaryIndex, primary,
                primary == null ? "" : primary.target().baseUnit(),
                "PACKAGING_PRIMARY_BASE_UNIT_MISMATCH", issues);

        Integer secondaryIndex = byRole.get(PackagingRole.SECONDARY_CASE);
        Integer tertiaryIndex = byRole.get(PackagingRole.TERTIARY_PALLET);
        if (tertiaryIndex != null && secondaryIndex == null) {
            issues.add(issue("PACKAGING_HIERARCHY_GAP", "items[" + tertiaryIndex + "]",
                    "三级包装不能跳过二级包装", "补充 SECONDARY_CASE"));
        }
        if (secondaryIndex != null) {
            ResolvedItem secondary = resolved.get(secondaryIndex);
            validateContainedUnit(factoryId, secondaryIndex, secondary,
                    primary == null ? "" : primary.item().packageUnit(),
                    "PACKAGING_SECONDARY_CHAIN_MISMATCH", issues);
        }
        if (tertiaryIndex != null && secondaryIndex != null) {
            ResolvedItem tertiary = resolved.get(tertiaryIndex);
            ResolvedItem secondary = resolved.get(secondaryIndex);
            validateContainedUnit(factoryId, tertiaryIndex, tertiary,
                    secondary == null ? "" : secondary.item().packageUnit(),
                    "PACKAGING_TERTIARY_CHAIN_MISMATCH", issues);
        }

        BigDecimal conserved = BigDecimal.ONE;
        for (PackagingRole role : PackagingRole.values()) {
            Integer index = byRole.get(role);
            if (index == null) continue;
            BigDecimal factor = resolved.get(index).item().unitsPerPackage();
            if (factor != null && factor.signum() > 0) {
                conserved = conserved.multiply(factor, MathContext.DECIMAL128);
                if (conserved.compareTo(MAX_FACTOR) > 0) {
                    issues.add(issue("PACKAGING_CONSERVATION_OVERFLOW", "items[" + index + "]",
                            "包装层级累计包含数量超出安全范围", "缩小层级换算或拆分包装方案"));
                }
            }
        }
    }

    private void validateContainedUnit(String factoryId, int index, ResolvedItem item,
            String expectedUnit, String code, List<DraftIssue> issues) {
        if (item == null || item.containedUnit() == null || expectedUnit == null || expectedUnit.isBlank()) return;
        CanonicalUnit expected = units.describe(factoryId, expectedUnit).orElse(null);
        if (expected == null || !sameCanonical(item.containedUnit(), expected)) {
            issues.add(issue(code, "items[" + index + "].containedUnit",
                    "包装层级没有与内层单位形成守恒链", "让内层单位精确承接上一层或目标基础单位"));
        }
    }

    private boolean sameCanonical(CanonicalUnit left, CanonicalUnit right) {
        return left != null && right != null && left.dimension() == right.dimension()
                && Objects.equals(left.code(), right.code())
                && Objects.equals(left.baseCode(), right.baseCode())
                && Objects.equals(normalize(left.factorToBase()), normalize(right.factorToBase()));
    }

    private PackagingDraftItem toRow(String draftId, String factoryId, int ordinal,
            PackagingItem item) {
        LabelReference label = item.labelReference();
        return PackagingDraftItem.builder()
                .id(UUID.randomUUID().toString()).draftId(draftId).factoryId(factoryId).ordinal(ordinal)
                .targetKind(item.target().kind().name()).targetId(item.target().targetId())
                .targetSnapshotDigest(item.target().targetSnapshotDigest())
                .packagingRole(item.role().name()).specName(item.specName())
                .packageUnit(item.packageUnit()).containedUnit(item.containedUnit())
                .unitsPerPackage(item.unitsPerPackage()).netContentQuantity(item.netContentQuantity())
                .netContentUnit(blankToNull(item.netContentUnit())).defaultSpec(item.defaultSpec())
                .sortOrder(item.sortOrder()).labelReferenceType(label.type().name())
                .labelReferenceId(blankToNull(label.referenceId()))
                .labelReferenceDigest(blankToNull(label.referenceDigest()))
                .changeReason(item.changeReason()).build();
    }

    private PackagingItem fromRow(PackagingDraftItem row) {
        return new PackagingItem(
                new PackagingDraftModels.TargetRef(TargetKind.valueOf(row.getTargetKind()),
                        row.getTargetId(), row.getTargetSnapshotDigest()),
                PackagingRole.valueOf(row.getPackagingRole()), row.getSpecName(), row.getPackageUnit(),
                row.getContainedUnit(), row.getUnitsPerPackage(), row.getNetContentQuantity(),
                row.getNetContentUnit(), Boolean.TRUE.equals(row.getDefaultSpec()), row.getSortOrder(),
                new LabelReference(LabelReferenceType.valueOf(row.getLabelReferenceType()),
                        row.getLabelReferenceId(), row.getLabelReferenceDigest()),
                row.getChangeReason());
    }

    private DraftReceipt receipt(PackagingDraft parent, boolean replayed,
            int businessWriteCount, int persistedRows) {
        return new DraftReceipt(schemaVersion(), parent.getId(), parent.getFactoryId(), parent.getId(),
                parent.getStatus(), parent.getSourceSnapshotDigest(), parent.getDesiredDigest(),
                parent.getPreviewDigest(), parent.getRequestDigest(), parent.getItemCount(),
                parent.getTargetCount(), Objects.requireNonNullElse(parent.getLockVersion(), 0L),
                replayed, businessWriteCount, replayed ? 0 : 1, persistedRows,
                0, 0, 0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED",
                "ARCHIVE_DRAFT_AFTER_PACKAGING_OWNER_CONFIRMATION");
    }

    private String productDigest(ProductType row) {
        return digest(row.getId(), row.getFactoryId(), row.getCode(), row.getName(), row.getUnit(),
                String.valueOf(row.getIsActive()), row.getPackageSpec(), row.getSpecification(),
                row.getLevel1Unit(), decimal(row.getBoxConversionCoefficient()),
                decimal(row.getNetContentQuantity()), row.getNetContentUnit(),
                String.valueOf(row.getUpdatedAt()));
    }

    private String materialDigest(RawMaterialType row) {
        return digest(row.getId(), row.getFactoryId(), row.getCode(), row.getName(), row.getUnit(),
                row.getCategory(), String.valueOf(row.getIsActive()),
                decimal(row.getPackQtyPerProduct()), String.valueOf(row.getIsAbacaPackaging()),
                row.getAbacaUnitPerBox(), row.getAbacaDefaultUnit(), String.valueOf(row.getUpdatedAt()));
    }

    private String productPackagingDigest(List<ProductPackagingSpec> rows) {
        List<String> values = new ArrayList<>();
        for (ProductPackagingSpec row : rows == null ? List.<ProductPackagingSpec>of() : rows) {
            values.add(row.getId()); values.add(row.getFactoryId()); values.add(row.getProductTypeId());
            values.add(row.getName()); values.add(row.getPackageUnit()); values.add(row.getBaseUnit());
            values.add(decimal(row.getConversionFactor())); values.add(String.valueOf(row.getDefaultSpec()));
            values.add(String.valueOf(row.getActive())); values.add(String.valueOf(row.getSortOrder()));
            values.add(String.valueOf(row.getVersion())); values.add(String.valueOf(row.getUpdatedAt()));
        }
        return digest(values.toArray(String[]::new));
    }

    private String materialPackagingDigest(List<MaterialPackagingSpec> rows) {
        List<String> values = new ArrayList<>();
        for (MaterialPackagingSpec row : rows == null ? List.<MaterialPackagingSpec>of() : rows) {
            values.add(row.getId()); values.add(row.getFactoryId()); values.add(row.getMaterialTypeId());
            values.add(row.getName()); values.add(row.getPackageUnit()); values.add(row.getBaseUnit());
            values.add(decimal(row.getConversionFactor())); values.add(String.valueOf(row.getDefaultSpec()));
            values.add(String.valueOf(row.getActive())); values.add(String.valueOf(row.getSortOrder()));
            values.add(String.valueOf(row.getVersion())); values.add(String.valueOf(row.getUpdatedAt()));
        }
        return digest(values.toArray(String[]::new));
    }

    private String hierarchyDigest(MaterialPackagingHierarchy row) {
        if (row == null) return digest("NONE");
        return digest(row.getId(), row.getFactoryId(), row.getMaterialTypeId(), row.getLevel1Unit(),
                decimal(row.getLevel1PerLevel2()), row.getLevel2Unit(),
                decimal(row.getLevel2PerLevel3()), row.getLevel3Unit(), row.getNotes(),
                String.valueOf(row.getUpdatedAt()));
    }

    private String unitDigest(CanonicalUnit unit) {
        if (unit == null) return digest("MISSING");
        return digest(unit.code(), String.valueOf(unit.dimension()), unit.baseCode(),
                decimal(unit.factorToBase()), unit.displayName(), String.valueOf(unit.displayScale()),
                String.valueOf(unit.active()), unit.conversionFamily(),
                unit.usageScopes().stream().map(Enum::name).sorted().toList().toString());
    }

    private String desiredDigest(List<PackagingItem> items) {
        List<String> values = new ArrayList<>();
        values.add(schemaVersion());
        for (PackagingItem item : items) {
            if (item == null) {
                values.add("NULL_ITEM");
                continue;
            }
            values.add(item.target() == null ? "" : String.valueOf(item.target().kind()));
            values.add(item.target() == null ? "" : item.target().targetId());
            values.add(item.target() == null ? "" : item.target().targetSnapshotDigest());
            values.add(String.valueOf(item.role())); values.add(item.specName());
            values.add(item.packageUnit()); values.add(item.containedUnit());
            values.add(decimal(item.unitsPerPackage())); values.add(decimal(item.netContentQuantity()));
            values.add(item.netContentUnit()); values.add(String.valueOf(item.defaultSpec()));
            values.add(String.valueOf(item.sortOrder()));
            values.add(String.valueOf(item.labelReference().type()));
            values.add(item.labelReference().referenceId()); values.add(item.labelReference().referenceDigest());
            values.add(item.changeReason());
        }
        return digest(values.toArray(String[]::new));
    }

    private String requestDigest(DraftRequest request, AuthenticatedOperator operator) {
        return digest(request.factoryId(), request.blueprintId(),
                String.valueOf(request.blueprintVersion()), request.sourceDigest(),
                operator.actor().type().name() + ":" + String.valueOf(operator.actor().id()), request.idempotencyKey(),
                request.confirmedPreviewDigest(), desiredDigest(request.items()));
    }

    private String targetIdentity(PackagingItem item) {
        return item.target() == null ? "missing" : item.target().kind() + ":" + item.target().targetId();
    }

    private String normalize(BigDecimal value) { return value == null ? "" : value.stripTrailingZeros().toPlainString(); }
    private String decimal(BigDecimal value) { return normalize(value); }
    private boolean isBlankOrTooLong(String value, int max) { return value == null || value.isBlank() || value.length() > max; }
    private boolean isDigest(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }

    private boolean isIdempotencyRace(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (String.valueOf(current.getMessage()).toLowerCase(Locale.ROOT)
                    .contains("uk_packaging_draft_idempotency")) return true;
        }
        return false;
    }

    private String digest(String... values) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
                md.update((byte) (bytes.length >>> 24)); md.update((byte) (bytes.length >>> 16));
                md.update((byte) (bytes.length >>> 8)); md.update((byte) bytes.length); md.update(bytes);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private DraftIssue issue(String code, String target, String message, String recovery) {
        return new DraftIssue(code, target, message, recovery);
    }

    private String schemaVersion() { return PackagingDraftModels.SCHEMA_VERSION; }

    private BusinessException invalid(int status, String message, String code) {
        return new BusinessException(status, message).withCode(code);
    }

    private record TargetResolution(String baseUnit, String currentDigest, String sourceDigest) {}

    public record TargetAuthority(TargetKind kind, String targetId, String snapshotDigest, String baseUnit) {}

    private record ResolvedItem(PackagingItem item, TargetResolution target,
                                CanonicalUnit packageUnit, CanonicalUnit containedUnit,
                                CanonicalUnit netContentUnit) {}
}
