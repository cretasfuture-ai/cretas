package com.cretas.aims.service.productdraft;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.product.ProductTypeDraft;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.product.ProductTypeDraftRepository;
import com.cretas.aims.service.productdraft.ProductTypeDraftModels.DesiredProduct;
import com.cretas.aims.service.productdraft.ProductTypeDraftModels.DraftIssue;
import com.cretas.aims.service.productdraft.ProductTypeDraftModels.DraftPreview;
import com.cretas.aims.service.productdraft.ProductTypeDraftModels.DraftReceipt;
import com.cretas.aims.service.productdraft.ProductTypeDraftModels.DraftRequest;
import com.cretas.aims.service.productdraft.ProductTypeDraftModels.ProductSnapshot;
import com.cretas.aims.service.productdraft.ProductTypeDraftModels.RecoveryPlan;
import com.cretas.aims.service.unit.UnitContractService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * First module-owned Factory Blueprint DRAFT command.
 *
 * <p>It only persists {@link ProductTypeDraft}; it never calls the live ProductType writer and it
 * exposes no publish or activation operation.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class ProductTypeDraftCommand {

    /**
     * Product categories a factory product DRAFT may carry: the {@code ProductCategory} values the product
     * table and the Workflow compiler understand for a factory, minus {@code RAW_MATERIAL} (raw materials go
     * through the materials capability) and the restaurant-only DISH / COMBO / ADD_ON. Free text such as an
     * interview's 「熟食」 is refused: it would reach the live table as a category nothing downstream
     * recognises (the Workflow assembler then cannot treat the SKU as a finished good).
     */
    public static final java.util.Set<String> FACTORY_PRODUCT_CATEGORIES = java.util.Set.of(
            com.cretas.aims.entity.enums.ProductCategory.FINISHED_PRODUCT,
            com.cretas.aims.entity.enums.ProductCategory.PACKAGING,
            com.cretas.aims.entity.enums.ProductCategory.SEASONING,
            com.cretas.aims.entity.enums.ProductCategory.CUSTOMER_MATERIAL,
            com.cretas.aims.entity.enums.ProductCategory.CONTRACT_MANUFACTURING,
            com.cretas.aims.entity.enums.ProductCategory.SEMI_FINISHED);


    public static final String CAPABILITY_ID = "products";
    public static final String REQUIRED_PERMISSION = "product:draft:write";

    private final ProductTypeDraftRepository draftRepository;
    private final ProductTypeRepository productTypeRepository;
    private final UnitContractService unitContractService;

    public ProductTypeDraftCommand(
            ProductTypeDraftRepository draftRepository,
            ProductTypeRepository productTypeRepository,
            UnitContractService unitContractService) {
        this.draftRepository = Objects.requireNonNull(draftRepository, "draftRepository");
        this.productTypeRepository = Objects.requireNonNull(productTypeRepository, "productTypeRepository");
        this.unitContractService = Objects.requireNonNull(unitContractService, "unitContractService");
    }

    @Transactional(readOnly = true)
    public DraftPreview preview(DraftRequest request) {
        validateEnvelope(request, false);
        List<DraftIssue> issues = new ArrayList<>();
        DesiredProduct desired = normalizeDesired(request.factoryId(), request.desired(), issues);
        ProductType current = resolveCurrent(request, desired, issues);
        ProductSnapshot snapshot = snapshot(current);
        String sourceSnapshotDigest = digest(
                snapshot == null ? "ABSENT" : "PRESENT",
                snapshot == null ? "" : snapshot.productTypeId(),
                snapshot == null ? "" : snapshot.code(),
                snapshot == null ? "" : snapshot.name(),
                snapshot == null ? "" : snapshot.unit(),
                snapshot == null ? "" : snapshot.productCategory(),
                snapshot == null ? "" : snapshot.updatedAt());
        String desiredDigest = digest(desired.code(), desired.name(), desired.unit(), desired.productCategory());
        issues.sort(Comparator.comparing(DraftIssue::code).thenComparing(DraftIssue::target));
        String status = issues.isEmpty() ? "READY" : "BLOCKED";
        String previewDigest = digest(
                ProductTypeDraftModels.SCHEMA_VERSION,
                request.factoryId(), request.blueprintId(), String.valueOf(request.blueprintVersion()),
                request.sourceDigest(), request.targetProductTypeId(), sourceSnapshotDigest,
                desiredDigest, status, issueDigest(issues));
        return new DraftPreview(ProductTypeDraftModels.SCHEMA_VERSION, status, request.factoryId(),
                snapshot, desired, sourceSnapshotDigest, desiredDigest, previewDigest, issues,
                0, "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    @Transactional
    public DraftReceipt materialize(DraftRequest request, com.cretas.aims.service.productdraft.ProductTypeDraftModels.AuthenticatedOperator operator) {
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
            throw invalid(403, "缺少产品草稿权限", "PRODUCT_DRAFT_PERMISSION_REQUIRED",
                    "由产品主数据负责人授予 product:draft:write 后重试", "permission");
        }
        String requestDigest = requestDigest(request, actor);
        Optional<ProductTypeDraft> prior = draftRepository.findByFactoryIdAndIdempotencyKey(
                request.factoryId(), request.idempotencyKey());
        if (prior.isPresent()) return replay(prior.get(), requestDigest);

        DraftPreview preview = preview(request);
        if (!"READY".equals(preview.status())) {
            throw invalid(409, "产品草稿预览仍有阻断项", "PRODUCT_DRAFT_PREVIEW_BLOCKED",
                    preview.issues().isEmpty() ? "重新生成预览" : preview.issues().get(0).recovery(),
                    preview.issues().isEmpty() ? "request" : preview.issues().get(0).target());
        }
        if (!request.confirmedPreviewDigest().equals(preview.previewDigest())) {
            throw invalid(409, "产品草稿预览摘要已变化", "PRODUCT_DRAFT_PREVIEW_DIGEST_CONFLICT",
                    "刷新当前产品与单位目录后重新查看并确认 ChangeSet", "confirmedPreviewDigest");
        }

        ProductTypeDraft row = ProductTypeDraft.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(request.factoryId())
                .blueprintId(request.blueprintId())
                .blueprintVersion(request.blueprintVersion())
                .sourceDigest(request.sourceDigest())
                .targetProductTypeId(preview.current() == null ? null : preview.current().productTypeId())
                .naturalKey(preview.desired().code())
                .desiredName(preview.desired().name())
                .desiredUnit(preview.desired().unit())
                .desiredProductCategory(preview.desired().productCategory())
                .sourceSnapshotDigest(preview.sourceSnapshotDigest())
                .desiredDigest(preview.desiredDigest())
                .previewDigest(preview.previewDigest())
                .requestDigest(requestDigest)
                .idempotencyKey(request.idempotencyKey())
                .operatorId(actor.factoryUserId()).actorType(actor.type()).actorId(actor.id())
                .status("DRAFT")
                .build();
        ProductTypeDraft saved;
        try {
            saved = draftRepository.saveAndFlush(row);
        } catch (DataIntegrityViolationException race) {
            if (!isScopedIdempotencyRace(race)) throw race;
            // PostgreSQL marks the transaction rollback-only after a unique violation. Do not
            // query for the winner inside this failed transaction; the stable-key retry path
            // above will read and validate the winning receipt in a fresh transaction.
            throw invalid(409, "产品草稿幂等键发生并发竞争",
                    "PRODUCT_DRAFT_IDEMPOTENCY_RACE", "使用相同幂等键重试以读取胜出回执", "idempotencyKey");
        }
        requireExactReadback(saved, preview);
        return receipt(saved, false, 1);
    }

    @Transactional(readOnly = true)
    public RecoveryPlan recoveryPlan(String factoryId, String draftId) {
        ProductTypeDraft row = draftRepository.findByIdAndFactoryId(clean(draftId), clean(factoryId))
                .orElseThrow(() -> invalid(404, "当前工厂没有该产品草稿", "PRODUCT_DRAFT_NOT_FOUND",
                        "刷新当前工厂的产品草稿回执", "draftId"));
        return new RecoveryPlan(ProductTypeDraftModels.SCHEMA_VERSION, row.getFactoryId(), row.getId(),
                row.getDesiredDigest(), "SOFT_DELETE_DRAFT_AFTER_MODULE_OWNER_CONFIRMATION",
                "只移除未发布产品草稿；不会修改 product_types 或任何运行态引用",
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    private ProductType resolveCurrent(DraftRequest request, DesiredProduct desired, List<DraftIssue> issues) {
        if (request.targetProductTypeId().isBlank()) {
            productTypeRepository.findByFactoryIdAndCode(request.factoryId(), desired.code()).ifPresent(existing ->
                    issues.add(issue("PRODUCT_DRAFT_TARGET_REQUIRED", "targetProductTypeId",
                            "产品自然键已存在，不能把更新伪装成新建", "选择当前工厂的现有产品后重新预览")));
            if (!desired.name().isBlank()
                    && productTypeRepository.existsByFactoryIdAndNormalizedName(request.factoryId(), desired.name())) {
                issues.add(issue("PRODUCT_DRAFT_NAME_CONFLICT", "name",
                        "当前工厂已有同名产品", "选择现有产品或使用经业务确认的不同名称"));
            }
            return null;
        }
        Optional<ProductType> found = productTypeRepository.findByIdAndFactoryId(
                request.targetProductTypeId(), request.factoryId());
        if (found.isEmpty()) {
            issues.add(issue("PRODUCT_DRAFT_TARGET_NOT_FOUND", "targetProductTypeId",
                    "当前工厂没有可更新的目标产品", "刷新当前工厂产品目录并重新选择"));
            return null;
        }
        ProductType current = found.get();
        if (!clean(current.getCode()).equals(desired.code())) {
            issues.add(issue("PRODUCT_DRAFT_NATURAL_KEY_CONFLICT", "code",
                    "既有产品草稿不能修改产品自然键", "保持现有产品编号，另建产品需使用新编号"));
        }
        if (!desired.name().isBlank() && !desired.name().equals(clean(current.getName()))
                && productTypeRepository.existsByFactoryIdAndNormalizedNameExcludingId(
                request.factoryId(), desired.name(), current.getId())) {
            issues.add(issue("PRODUCT_DRAFT_NAME_CONFLICT", "name",
                    "当前工厂已有其他同名产品", "使用经业务确认的不同名称"));
        }
        return current;
    }

    private DesiredProduct normalizeDesired(String factoryId, DesiredProduct requested, List<DraftIssue> issues) {
        if (requested == null) {
            issues.add(issue("PRODUCT_DRAFT_DESIRED_REQUIRED", "desired",
                    "产品草稿目标不能为空", "补齐产品编号、名称、单位和产品类别"));
            return new DesiredProduct("", "", "", "");
        }
        String unit = requested.unit();
        if (!unit.isBlank()) {
            try {
                unit = clean(unitContractService.storageUnit(factoryId, unit));
            } catch (RuntimeException failure) {
                issues.add(issue("PRODUCT_DRAFT_UNIT_RESOLUTION_FAILED", "unit",
                        "产品单位无法由权威单位目录解析", "修正单位目录或选择已确认单位后重试"));
            }
        }
        DesiredProduct desired = new DesiredProduct(
                requested.code(), requested.name(), unit, requested.productCategory());
        if (desired.code().isBlank()) issues.add(required("code", "产品编号"));
        if (desired.name().isBlank()) issues.add(required("name", "产品名称"));
        if (desired.unit().isBlank()) issues.add(required("unit", "产品单位"));
        if (desired.productCategory().isBlank()) issues.add(required("productCategory", "产品类别"));
        if ("RAW_MATERIAL".equalsIgnoreCase(desired.productCategory())) {
            issues.add(issue("PRODUCT_DRAFT_CAPABILITY_MISMATCH", "productCategory",
                    "原料不能通过 products capability 建草稿", "改走 MaterialTypeDraftCommand"));
        } else if (!desired.productCategory().isBlank()
                && !FACTORY_PRODUCT_CATEGORIES.contains(desired.productCategory())) {
            issues.add(issue("PRODUCT_DRAFT_CATEGORY_INVALID", "productCategory",
                    "产品类别「" + desired.productCategory() + "」不是系统的产品类别",
                    "从成品、包材、调料、客供料、代工、半成品里选一个"));
        }
        return desired;
    }

    private void validateEnvelope(DraftRequest request, boolean materialize) {
        if (request == null) throw invalid(400, "产品草稿请求不能为空", "PRODUCT_DRAFT_REQUEST_REQUIRED",
                "从已确认的 products ChangeSet 生成请求", "request");
        if (request.factoryId().isBlank() || request.blueprintId().isBlank() || request.blueprintVersion() <= 0) {
            throw invalid(400, "工厂与 Blueprint 版本不能为空", "PRODUCT_DRAFT_SCOPE_REQUIRED",
                    "锁定 factoryId、blueprintId 和 blueprintVersion", "blueprint");
        }
        if (!request.sourceDigest().matches("[0-9a-f]{64}")) {
            throw invalid(400, "Blueprint sourceDigest 必须是 SHA-256", "PRODUCT_DRAFT_SOURCE_DIGEST_INVALID",
                    "使用服务器编译工件的 sourceDigest", "sourceDigest");
        }
        if (materialize) {
            if (request.operatorId() <= 0) throw invalid(400, "产品草稿必须记录操作人",
                    "PRODUCT_DRAFT_OPERATOR_REQUIRED", "使用已认证模块负责人身份", "operatorId");
            if (request.idempotencyKey().isBlank() || request.idempotencyKey().length() > 128) {
                throw invalid(400, "产品草稿幂等键不能为空且不能超过 128 字符",
                        "PRODUCT_DRAFT_IDEMPOTENCY_KEY_INVALID", "为本次确认生成稳定幂等键", "idempotencyKey");
            }
            if (!request.confirmedPreviewDigest().matches("[0-9a-f]{64}")) {
                throw invalid(400, "必须确认服务器生成的 previewDigest",
                        "PRODUCT_DRAFT_CONFIRMED_PREVIEW_REQUIRED", "重新预览并确认当前摘要",
                        "confirmedPreviewDigest");
            }
        }
    }

    private DraftReceipt replay(ProductTypeDraft row, String requestDigest) {
        if (!row.getRequestDigest().equals(requestDigest)) {
            throw invalid(409, "该幂等键已绑定另一份产品草稿载荷",
                    "PRODUCT_DRAFT_IDEMPOTENCY_KEY_CONFLICT", "复用原请求或生成新幂等键", "idempotencyKey");
        }
        return receipt(row, true, 0);
    }

    private DraftReceipt receipt(ProductTypeDraft row, boolean replayed, int writes) {
        return new DraftReceipt(ProductTypeDraftModels.SCHEMA_VERSION, row.getId(), row.getFactoryId(),
                row.getId(), row.getStatus(), row.getNaturalKey(), clean(row.getTargetProductTypeId()),
                row.getDesiredDigest(), row.getPreviewDigest(), row.getRequestDigest(),
                Objects.requireNonNullElse(row.getLockVersion(), 0L), replayed, writes, 1,
                "NOT_PUBLISHED", "NOT_ACTIVATED",
                "由产品模块负责人确认后软删除该 DRAFT；product_types 不受影响");
    }

    private void requireExactReadback(ProductTypeDraft row, DraftPreview preview) {
        if (row == null || !"DRAFT".equals(row.getStatus())
                || !preview.factoryId().equals(row.getFactoryId())
                || !preview.desired().code().equals(row.getNaturalKey())
                || !preview.desiredDigest().equals(row.getDesiredDigest())
                || !preview.previewDigest().equals(row.getPreviewDigest())) {
            throw invalid(409, "产品草稿回读与已确认 ChangeSet 不一致",
                    "PRODUCT_DRAFT_READBACK_MISMATCH", "保留事务回滚并重新预览", "draft");
        }
    }

    private ProductSnapshot snapshot(ProductType value) {
        if (value == null) return null;
        LocalDateTime updatedAt = value.getUpdatedAt();
        return new ProductSnapshot(value.getId(), clean(value.getCode()), clean(value.getName()),
                clean(value.getUnit()), clean(value.getProductCategory()),
                updatedAt == null ? "" : updatedAt.toString());
    }

    private String requestDigest(DraftRequest request, com.cretas.aims.security.ActorRef actor) {
        return digest(ProductTypeDraftModels.SCHEMA_VERSION, request.factoryId(), request.blueprintId(),
                String.valueOf(request.blueprintVersion()), request.sourceDigest(), request.targetProductTypeId(),
                request.desired() == null ? "" : request.desired().code(),
                request.desired() == null ? "" : request.desired().name(),
                request.desired() == null ? "" : request.desired().unit(),
                request.desired() == null ? "" : request.desired().productCategory(),
                request.confirmedPreviewDigest(), request.idempotencyKey(), actor.type().name() + ":" + String.valueOf(actor.id()));
    }

    private String issueDigest(List<DraftIssue> issues) {
        return digest(issues.stream()
                .map(issue -> issue.code() + "\u0000" + issue.target() + "\u0000" + issue.message())
                .toArray(String[]::new));
    }

    private boolean isScopedIdempotencyRace(Throwable failure) {
        Throwable cursor = failure;
        while (cursor != null) {
            String message = clean(cursor.getMessage()).toLowerCase();
            if (message.contains("uk_product_type_draft_idempotency")) return true;
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

    private static DraftIssue required(String target, String label) {
        return issue("PRODUCT_DRAFT_FIELD_REQUIRED", target, label + "不能为空", "补齐并确认该字段");
    }

    private static DraftIssue issue(String code, String target, String message, String recovery) {
        return new DraftIssue(code, target, message, recovery);
    }

    private static String clean(String value) {
        return ProductTypeDraftModels.clean(value);
    }

    private static BusinessException invalid(
            int status, String message, String code, String recovery, String target) {
        return new BusinessException(status, message).withCode(code).withHint(recovery)
                .withHintTarget(target).withSeverity("warning");
    }
}
