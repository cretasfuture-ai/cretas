package com.cretas.aims.service.supplierreceivingdraft;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.SupplierMaterial;
import com.cretas.aims.entity.foodsafety.SupplierQualification;
import com.cretas.aims.entity.material.MaterialTypeDraft;
import com.cretas.aims.entity.supplier.SupplierAdmissionRuleConfig;
import com.cretas.aims.entity.supplierreceiving.SupplierReceivingPolicyDraft;
import com.cretas.aims.entity.supplierreceiving.SupplierReceivingPolicyDraftItem;
import com.cretas.aims.entity.supplierreceiving.SupplierReceivingPolicyDraftQualification;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.foodsafety.SupplierQualificationRepository;
import com.cretas.aims.repository.material.MaterialTypeDraftRepository;
import com.cretas.aims.repository.supplier.SupplierAdmissionRuleConfigRepository;
import com.cretas.aims.repository.supplierreceiving.SupplierReceivingPolicyDraftItemRepository;
import com.cretas.aims.repository.supplierreceiving.SupplierReceivingPolicyDraftQualificationRepository;
import com.cretas.aims.repository.supplierreceiving.SupplierReceivingPolicyDraftRepository;
import com.cretas.aims.service.supplierreceivingdraft.SupplierReceivingPolicyDraftModels.AcceptanceMode;
import com.cretas.aims.service.supplierreceivingdraft.SupplierReceivingPolicyDraftModels.AuthenticatedOperator;
import com.cretas.aims.service.supplierreceivingdraft.SupplierReceivingPolicyDraftModels.DraftIssue;
import com.cretas.aims.service.supplierreceivingdraft.SupplierReceivingPolicyDraftModels.DraftPreview;
import com.cretas.aims.service.supplierreceivingdraft.SupplierReceivingPolicyDraftModels.DraftReadback;
import com.cretas.aims.service.supplierreceivingdraft.SupplierReceivingPolicyDraftModels.DraftReceipt;
import com.cretas.aims.service.supplierreceivingdraft.SupplierReceivingPolicyDraftModels.DraftRequest;
import com.cretas.aims.service.supplierreceivingdraft.SupplierReceivingPolicyDraftModels.MaterialRefType;
import com.cretas.aims.service.supplierreceivingdraft.SupplierReceivingPolicyDraftModels.QualificationType;
import com.cretas.aims.service.supplierreceivingdraft.SupplierReceivingPolicyDraftModels.RecoveryPlan;
import com.cretas.aims.service.supplierreceivingdraft.SupplierReceivingPolicyDraftModels.RequestedPolicy;
import com.cretas.aims.service.supplierreceivingdraft.SupplierReceivingPolicyDraftModels.ResolvedPolicy;
import com.cretas.aims.service.supplierreceivingdraft.SupplierReceivingPolicyDraftModels.StoredQualification;
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

/** Typed, module-owned supplier receiving TEMPLATE command. It never writes live supplier or receipt data. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class SupplierReceivingPolicyDraftCommand {
    public static final String CAPABILITY_ID = "supplier-receiving";
    public static final String REQUIRED_PERMISSION = "supplier:policy:draft";

    private final SupplierReceivingPolicyDraftRepository draftRepository;
    private final SupplierReceivingPolicyDraftItemRepository itemRepository;
    private final SupplierReceivingPolicyDraftQualificationRepository qualificationDraftRepository;
    private final FactoryRepository factoryRepository;
    private final UserRepository userRepository;
    private final SupplierRepository supplierRepository;
    private final SupplierMaterialRepository supplierMaterialRepository;
    private final SupplierQualificationRepository supplierQualificationRepository;
    private final RawMaterialTypeRepository materialRepository;
    private final MaterialTypeDraftRepository materialDraftRepository;
    private final SupplierAdmissionRuleConfigRepository admissionConfigRepository;

    public SupplierReceivingPolicyDraftCommand(
            SupplierReceivingPolicyDraftRepository draftRepository,
            SupplierReceivingPolicyDraftItemRepository itemRepository,
            SupplierReceivingPolicyDraftQualificationRepository qualificationDraftRepository,
            FactoryRepository factoryRepository,
            UserRepository userRepository,
            SupplierRepository supplierRepository,
            SupplierMaterialRepository supplierMaterialRepository,
            SupplierQualificationRepository supplierQualificationRepository,
            RawMaterialTypeRepository materialRepository,
            MaterialTypeDraftRepository materialDraftRepository,
            SupplierAdmissionRuleConfigRepository admissionConfigRepository) {
        this.draftRepository = Objects.requireNonNull(draftRepository);
        this.itemRepository = Objects.requireNonNull(itemRepository);
        this.qualificationDraftRepository = Objects.requireNonNull(qualificationDraftRepository);
        this.factoryRepository = Objects.requireNonNull(factoryRepository);
        this.userRepository = Objects.requireNonNull(userRepository);
        this.supplierRepository = Objects.requireNonNull(supplierRepository);
        this.supplierMaterialRepository = Objects.requireNonNull(supplierMaterialRepository);
        this.supplierQualificationRepository = Objects.requireNonNull(supplierQualificationRepository);
        this.materialRepository = Objects.requireNonNull(materialRepository);
        this.materialDraftRepository = Objects.requireNonNull(materialDraftRepository);
        this.admissionConfigRepository = Objects.requireNonNull(admissionConfigRepository);
    }

    @Transactional(readOnly = true)
    public DraftPreview preview(DraftRequest request) {
        validateEnvelope(request, false);
        List<DraftIssue> issues = new ArrayList<>();
        Factory factory = factoryRepository.findById(request.factoryId()).orElse(null);
        if (factory == null) {
            issues.add(issue("SUPPLIER_RECEIVING_FACTORY_NOT_FOUND", "factoryId",
                    "当前工厂不存在或不可读取", "刷新当前租户工厂范围后重新预览"));
        } else if (!Boolean.TRUE.equals(factory.getIsActive())) {
            issues.add(issue("SUPPLIER_RECEIVING_FACTORY_INACTIVE", "factoryId",
                    "停用工厂不能建立新的来料策略模板", "由平台负责人确认工厂状态后重试"));
        }

        String admissionConfigDigest = admissionConfigSnapshot(request.factoryId());
        List<ResolvedPolicy> resolved = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        for (int i = 0; i < request.policies().size(); i++) {
            resolved.add(resolve(request, request.policies().get(i), i, identities, issues));
        }
        issues.sort(Comparator.comparing(DraftIssue::code).thenComparing(DraftIssue::target));
        String desiredDigest = desiredDigest(resolved);
        String sourceSnapshotDigest = sourceSnapshot(factory, admissionConfigDigest, resolved);
        String status = issues.isEmpty() ? "READY" : "BLOCKED";
        String previewDigest = digest(SupplierReceivingPolicyDraftModels.SCHEMA_VERSION,
                request.factoryId(), request.blueprintId(), String.valueOf(request.blueprintVersion()),
                request.sourceDigest(), sourceSnapshotDigest, desiredDigest, status, issueDigest(issues));
        return new DraftPreview(SupplierReceivingPolicyDraftModels.SCHEMA_VERSION, status, request.factoryId(),
                List.of("materials"), List.copyOf(resolved), sourceSnapshotDigest, desiredDigest, previewDigest,
                List.copyOf(issues), 0, 0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    @Transactional
    public DraftReceipt materialize(DraftRequest request, AuthenticatedOperator operator) {
        validateEnvelope(request, true);
        authorize(request, operator);
        String requestDigest = requestDigest(request, operator);
        Optional<SupplierReceivingPolicyDraft> prior = draftRepository.findByFactoryIdAndIdempotencyKey(
                request.factoryId(), request.idempotencyKey());
        if (prior.isPresent()) return replay(prior.get(), requestDigest);

        DraftPreview preview = preview(request);
        if (!"READY".equals(preview.status())) {
            DraftIssue first = preview.issues().isEmpty() ? null : preview.issues().get(0);
            throw invalid(409, "来料策略模板预览仍有阻断项", "SUPPLIER_RECEIVING_PREVIEW_BLOCKED",
                    first == null ? "重新生成预览" : first.recovery(), first == null ? "request" : first.target());
        }
        if (!request.confirmedPreviewDigest().equals(preview.previewDigest())) {
            throw invalid(409, "来料策略模板预览摘要已变化", "SUPPLIER_RECEIVING_PREVIEW_DIGEST_CONFLICT",
                    "刷新供应商、物料、资质和现有准入规则后重新确认", "confirmedPreviewDigest");
        }

        String draftId = UUID.randomUUID().toString();
        int qualificationCount = preview.policies().stream()
                .mapToInt(policy -> policy.requiredQualifications().size()).sum();
        SupplierReceivingPolicyDraft parent = SupplierReceivingPolicyDraft.builder()
                .id(draftId).factoryId(request.factoryId()).blueprintId(request.blueprintId())
                .blueprintVersion(request.blueprintVersion()).sourceDigest(request.sourceDigest())
                .sourceSnapshotDigest(preview.sourceSnapshotDigest()).desiredDigest(preview.desiredDigest())
                .previewDigest(preview.previewDigest()).requestDigest(requestDigest)
                .idempotencyKey(request.idempotencyKey()).operatorId(operator.actor().factoryUserId()).actorType(operator.actor().type()).actorId(operator.actor().id()).status("DRAFT")
                .dependencyBasis(SupplierReceivingPolicyDraftModels.DEPENDENCY_BASIS)
                .dependencyClosureComplete(true).policyCount(preview.policies().size())
                .qualificationCount(qualificationCount).build();
        SupplierReceivingPolicyDraft saved;
        try {
            saved = draftRepository.saveAndFlush(parent);
        } catch (DataIntegrityViolationException race) {
            if (!isScopedIdempotencyRace(race)) throw race;
            throw invalid(409, "来料策略模板幂等键发生并发竞争", "SUPPLIER_RECEIVING_IDEMPOTENCY_RACE",
                    "使用相同幂等键在新事务中重试", "idempotencyKey");
        }

        List<SupplierReceivingPolicyDraftItem> items = new ArrayList<>();
        List<SupplierReceivingPolicyDraftQualification> qualifications = new ArrayList<>();
        for (int i = 0; i < preview.policies().size(); i++) {
            ResolvedPolicy policy = preview.policies().get(i);
            String itemId = UUID.randomUUID().toString();
            items.add(SupplierReceivingPolicyDraftItem.builder()
                    .id(itemId).draftId(draftId).factoryId(request.factoryId()).ordinal(i)
                    .supplierId(policy.supplierId()).materialRefType(policy.materialRefType().name())
                    .materialRefId(policy.materialRefId()).materialSnapshotDigest(policy.materialSnapshotDigest())
                    .supplierSnapshotDigest(policy.supplierSnapshotDigest())
                    .relationshipSnapshotDigest(policy.relationshipSnapshotDigest())
                    .acceptanceMode(policy.acceptanceMode().name())
                    .qualificationExpiryPolicy(policy.qualificationExpiryPolicy().name())
                    .samplePercentage(policy.samplePercentage()).minSampleSize(policy.minSampleSize())
                    .maxSampleSize(policy.maxSampleSize()).quarantineOnFailure(policy.quarantineOnFailure())
                    .receivingEvidenceRequired(policy.receivingEvidenceRequired()).policyBasis(policy.policyBasis())
                    .qualificationCount(policy.requiredQualifications().size()).build());
            for (int q = 0; q < policy.requiredQualifications().size(); q++) {
                StoredQualification qualification = policy.requiredQualifications().get(q);
                qualifications.add(SupplierReceivingPolicyDraftQualification.builder()
                        .id(UUID.randomUUID().toString()).draftId(draftId).itemId(itemId)
                        .factoryId(request.factoryId()).ordinal(q)
                        .qualificationType(qualification.qualificationType().name())
                        .currentEvidenceDigest(qualification.currentEvidenceDigest()).build());
            }
        }
        itemRepository.saveAll(items);
        itemRepository.flush();
        qualificationDraftRepository.saveAll(qualifications);
        qualificationDraftRepository.flush();
        requireExactReadback(saved, preview);
        return receipt(saved, false, 1, 1 + items.size() + qualifications.size());
    }

    @Transactional(readOnly = true)
    public DraftReadback readBack(String factoryId, String draftId) {
        SupplierReceivingPolicyDraft parent = draftRepository.findByIdAndFactoryId(clean(draftId), clean(factoryId))
                .orElseThrow(() -> invalid(404, "当前工厂没有该来料策略模板", "SUPPLIER_RECEIVING_DRAFT_NOT_FOUND",
                        "刷新当前工厂模板回执", "draftId"));
        List<SupplierReceivingPolicyDraftItem> items = itemRepository
                .findByDraftIdAndFactoryIdOrderByOrdinal(parent.getId(), parent.getFactoryId());
        List<SupplierReceivingPolicyDraftQualification> qualifications = qualificationDraftRepository
                .findByDraftIdAndFactoryIdOrderByItemIdAscOrdinalAsc(parent.getId(), parent.getFactoryId());
        Map<String, List<SupplierReceivingPolicyDraftQualification>> byItem = new HashMap<>();
        for (var qualification : qualifications) byItem.computeIfAbsent(qualification.getItemId(), key -> new ArrayList<>())
                .add(qualification);
        List<ResolvedPolicy> policies = new ArrayList<>();
        for (var item : items) {
            List<StoredQualification> stored = byItem.getOrDefault(item.getId(), List.of()).stream()
                    .sorted(Comparator.comparing(SupplierReceivingPolicyDraftQualification::getOrdinal))
                    .map(row -> new StoredQualification(QualificationType.valueOf(row.getQualificationType()),
                            row.getCurrentEvidenceDigest())).toList();
            policies.add(new ResolvedPolicy(item.getSupplierId(), MaterialRefType.valueOf(item.getMaterialRefType()),
                    item.getMaterialRefId(), item.getMaterialSnapshotDigest(), item.getSupplierSnapshotDigest(),
                    item.getRelationshipSnapshotDigest(), AcceptanceMode.valueOf(item.getAcceptanceMode()),
                    SupplierReceivingPolicyDraftModels.QualificationExpiryPolicy.valueOf(item.getQualificationExpiryPolicy()),
                    item.getSamplePercentage(), item.getMinSampleSize(), item.getMaxSampleSize(),
                    item.getQuarantineOnFailure(), item.getReceivingEvidenceRequired(), item.getPolicyBasis(), stored));
        }
        if (items.size() != parent.getPolicyCount() || qualifications.size() != parent.getQualificationCount()
                || items.stream().anyMatch(item -> byItem.getOrDefault(item.getId(), List.of()).size() != item.getQualificationCount())
                || !desiredDigest(policies).equals(parent.getDesiredDigest())) throw readbackMismatch();
        return new DraftReadback(SupplierReceivingPolicyDraftModels.SCHEMA_VERSION, parent.getFactoryId(), parent.getId(),
                parent.getStatus(), parent.getDesiredDigest(), List.copyOf(policies), 0, 0, 0, 0, 0,
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    @Transactional(readOnly = true)
    public RecoveryPlan recoveryPlan(String factoryId, String draftId) {
        SupplierReceivingPolicyDraft row = draftRepository.findByIdAndFactoryId(clean(draftId), clean(factoryId))
                .orElseThrow(() -> invalid(404, "当前工厂没有该来料策略模板", "SUPPLIER_RECEIVING_DRAFT_NOT_FOUND",
                        "刷新当前工厂模板回执", "draftId"));
        return new RecoveryPlan(SupplierReceivingPolicyDraftModels.SCHEMA_VERSION, row.getFactoryId(), row.getId(),
                row.getDesiredDigest(), "ARCHIVE_UNPUBLISHED_TEMPLATE_AFTER_MODULE_OWNER_CONFIRMATION",
                "只归档未发布来料策略模板；不删除供应商、资质、采购单、收货单、批次或库存",
                "SUPPLY_AND_QUALITY_OWNER_CONFIRMATION", 0);
    }

    private ResolvedPolicy resolve(DraftRequest request, RequestedPolicy requested, int index, Set<String> identities,
                                   List<DraftIssue> issues) {
        String factoryId = request.factoryId();
        String target = "policies[" + index + "]";
        if (requested == null) {
            issues.add(issue("SUPPLIER_RECEIVING_POLICY_REQUIRED", target, "来料策略不能为空", "补充 typed 来料策略"));
            return emptyPolicy();
        }
        String supplierId = clean(requested.supplierId());
        String materialRefId = clean(requested.materialRefId());
        String identity = supplierId + "|" + requested.materialRefType() + "|" + materialRefId;
        if (!identities.add(identity)) issues.add(issue("SUPPLIER_RECEIVING_POLICY_DUPLICATE", target,
                "同一供应商与物料只能有一条策略", "合并重复策略后重新预览"));

        Supplier supplier = supplierRepository.findByIdAndFactoryId(supplierId, factoryId).orElse(null);
        if (supplier == null) issues.add(issue("SUPPLIER_RECEIVING_SUPPLIER_NOT_FOUND", target + ".supplierId",
                "当前工厂没有该供应商", "选择当前工厂供应商"));
        else {
            if (!Boolean.TRUE.equals(supplier.getIsActive())) issues.add(issue("SUPPLIER_RECEIVING_SUPPLIER_INACTIVE",
                    target + ".supplierId", "停用供应商不能进入新来料策略", "先完成供应商治理"));
            String admission = clean(supplier.getAdmissionStatus()).toUpperCase(Locale.ROOT);
            boolean allowed = "APPROVED".equals(admission)
                    || (requested.acceptanceMode() == AcceptanceMode.NEW_SUPPLIER && "PENDING".equals(admission));
            if (!allowed) issues.add(issue("SUPPLIER_RECEIVING_SUPPLIER_ADMISSION_BLOCKED", target + ".supplierId",
                    "供应商准入状态不允许该验收模式", "选择已批准供应商或将待审供应商标记为 NEW_SUPPLIER"));
        }
        String supplierSnapshot = supplierSnapshot(supplier);

        String materialSnapshot;
        String relationshipSnapshot;
        if (requested.materialRefType() == MaterialRefType.LIVE_MATERIAL) {
            RawMaterialType material = materialRepository.findByIdAndFactoryId(materialRefId, factoryId).orElse(null);
            if (material == null) issues.add(issue("SUPPLIER_RECEIVING_MATERIAL_NOT_FOUND", target + ".materialRefId",
                    "当前工厂没有该正式物料", "选择当前工厂正式物料或 A2 物料草稿"));
            else if (!Boolean.TRUE.equals(material.getIsActive())) issues.add(issue("SUPPLIER_RECEIVING_MATERIAL_INACTIVE",
                    target + ".materialRefId", "停用物料不能进入新来料策略", "选择启用物料"));
            materialSnapshot = materialSnapshot(material);
            SupplierMaterial relation = supplierMaterialRepository
                    .findByFactoryIdAndSupplierIdAndMaterialTypeId(factoryId, supplierId, materialRefId).orElse(null);
            if (relation == null || !Boolean.TRUE.equals(relation.getActive())) {
                issues.add(issue("SUPPLIER_RECEIVING_RELATIONSHIP_REQUIRED", target + ".materialRefId",
                        "正式物料必须已有同工厂有效供货关系", "由供应商主数据流程确认供货关系后重新预览"));
            }
            relationshipSnapshot = relationshipSnapshot(relation);
        } else if (requested.materialRefType() == MaterialRefType.MATERIAL_DRAFT) {
            MaterialTypeDraft materialDraft = materialDraftRepository.findByIdAndFactoryId(materialRefId, factoryId).orElse(null);
            if (materialDraft == null || !"DRAFT".equals(materialDraft.getStatus())) {
                issues.add(issue("SUPPLIER_RECEIVING_MATERIAL_DRAFT_REQUIRED", target + ".materialRefId",
                        "A2 物料草稿不存在或状态不是 DRAFT", "选择当前工厂可回读的 A2 物料草稿"));
            } else if (!Objects.equals(request.blueprintId(), materialDraft.getBlueprintId())
                    || request.blueprintVersion() != Objects.requireNonNullElse(materialDraft.getBlueprintVersion(), -1L)) {
                issues.add(issue("SUPPLIER_RECEIVING_MATERIAL_DRAFT_LINEAGE_MISMATCH", target + ".materialRefId",
                        "A2 物料草稿不属于当前 Blueprint 版本", "选择同一 Blueprint 版本生成的 A2 物料草稿"));
            }
            materialSnapshot = materialDraftSnapshot(materialDraft);
            relationshipSnapshot = digest("PROPOSED_AFTER_MATERIAL_ACTIVATION", factoryId, supplierId, materialRefId);
        } else {
            issues.add(issue("SUPPLIER_RECEIVING_MATERIAL_REF_TYPE_REQUIRED", target + ".materialRefType",
                    "物料引用类型不能为空", "选择 LIVE_MATERIAL 或 MATERIAL_DRAFT"));
            materialSnapshot = digest("MATERIAL", "INVALID");
            relationshipSnapshot = digest("RELATIONSHIP", "INVALID");
        }

        validatePolicy(requested, target, issues);
        List<QualificationType> qualificationTypes = requested.requiredQualifications() == null
                ? List.of() : requested.requiredQualifications().stream().filter(Objects::nonNull).distinct().sorted().toList();
        if (qualificationTypes.isEmpty()) issues.add(issue("SUPPLIER_RECEIVING_QUALIFICATION_REQUIRED",
                target + ".requiredQualifications", "至少声明一种所需资质", "按供应商与物料风险声明资质"));
        if (!qualificationTypes.contains(QualificationType.BUSINESS_LICENSE)) issues.add(issue(
                "SUPPLIER_RECEIVING_BUSINESS_LICENSE_REQUIRED", target + ".requiredQualifications",
                "每条供应商策略必须要求营业执照", "加入 BUSINESS_LICENSE"));
        List<SupplierQualification> current = supplier == null ? List.of()
                : supplierQualificationRepository.findByFactoryIdAndSupplierIdAndStatusIn(factoryId, supplierId,
                List.of("VALID", "EXPIRING", "EXPIRED", "REVOKED"));
        List<StoredQualification> qualifications = qualificationTypes.stream()
                .map(type -> new StoredQualification(type, qualificationEvidence(type, current))).toList();
        return new ResolvedPolicy(supplierId, requested.materialRefType(), materialRefId, materialSnapshot,
                supplierSnapshot, relationshipSnapshot, requested.acceptanceMode(), requested.qualificationExpiryPolicy(),
                requested.samplePercentage(), requested.minSampleSize(), requested.maxSampleSize(),
                requested.quarantineOnFailure(), requested.receivingEvidenceRequired(), clean(requested.policyBasis()),
                qualifications);
    }

    private void validatePolicy(RequestedPolicy policy, String target, List<DraftIssue> issues) {
        if (policy.acceptanceMode() == null) issues.add(issue("SUPPLIER_RECEIVING_ACCEPTANCE_MODE_REQUIRED",
                target + ".acceptanceMode", "验收模式不能为空", "选择 STANDARD/HIGH_RISK/NEW_SUPPLIER"));
        if (policy.qualificationExpiryPolicy() == null) issues.add(issue("SUPPLIER_RECEIVING_EXPIRY_POLICY_REQUIRED",
                target + ".qualificationExpiryPolicy", "资质到期策略不能为空", "选择封闭的资质到期策略"));
        if (policy.samplePercentage() == null || policy.samplePercentage().signum() <= 0
                || policy.samplePercentage().compareTo(new java.math.BigDecimal("100")) > 0) {
            issues.add(issue("SUPPLIER_RECEIVING_SAMPLE_PERCENT_INVALID", target + ".samplePercentage",
                    "抽样比例必须大于 0 且不超过 100", "填写明确百分比"));
        }
        if (policy.minSampleSize() < 1 || policy.maxSampleSize() < policy.minSampleSize()) {
            issues.add(issue("SUPPLIER_RECEIVING_SAMPLE_SIZE_INVALID", target + ".minSampleSize",
                    "样本上下限必须满足 1 <= min <= max", "修正样本上下限"));
        }
        if (!policy.quarantineOnFailure()) issues.add(issue("SUPPLIER_RECEIVING_QUARANTINE_REQUIRED",
                target + ".quarantineOnFailure", "不合格来料必须进入隔离处置", "确认 quarantineOnFailure=true"));
        if (!policy.receivingEvidenceRequired()) issues.add(issue("SUPPLIER_RECEIVING_EVIDENCE_REQUIRED",
                target + ".receivingEvidenceRequired", "收货验收必须留存证据", "确认 receivingEvidenceRequired=true"));
        if (clean(policy.policyBasis()).isBlank()) issues.add(issue("SUPPLIER_RECEIVING_POLICY_BASIS_REQUIRED",
                target + ".policyBasis", "策略依据不能为空", "由供应链与质量负责人确认依据"));
    }

    private void authorize(DraftRequest request, AuthenticatedOperator operator) {
        if (operator == null || operator.operatorId() <= 0 || operator.actor().id() != request.operatorId()
                || !request.factoryId().equals(clean(operator.factoryId()))) {
            throw invalid(403, "来料策略操作人身份与工厂范围不一致", "SUPPLIER_RECEIVING_OPERATOR_SCOPE_MISMATCH",
                    "从当前认证会话构造 operatorId 与 factoryId", "operator");
        }
        if (operator.permissions() == null || !operator.permissions().contains(REQUIRED_PERMISSION)) {
            throw invalid(403, "缺少来料策略模板权限", "SUPPLIER_RECEIVING_PERMISSION_REQUIRED",
                    "由供应链/质量负责人授予 supplier:policy:draft", "permission");
        }
        var member = userRepository.findByIdAndFactoryId(operator.operatorId(), operator.factoryId());
        if (member.isEmpty() || !Boolean.TRUE.equals(member.get().getIsActive())) {
            throw invalid(403, "操作人不是当前工厂有效成员", "SUPPLIER_RECEIVING_OPERATOR_MEMBERSHIP_REQUIRED",
                    "刷新认证会话或恢复有效成员关系", "operator");
        }
    }

    private String supplierSnapshot(Supplier supplier) {
        if (supplier == null) return digest("SUPPLIER", "ABSENT");
        return digest("SUPPLIER", supplier.getId(), supplier.getFactoryId(), supplier.getSupplierCode(),
                supplier.getCode(), supplier.getName(), supplier.getShortName(), String.valueOf(supplier.getIsActive()),
                supplier.getAdmissionStatus(), String.valueOf(supplier.getAdmissionReviewedAt()),
                String.valueOf(supplier.getAdmissionReviewerId()), String.valueOf(supplier.getVersion()),
                String.valueOf(supplier.getUpdatedAt()));
    }

    private String materialSnapshot(RawMaterialType material) {
        if (material == null) return digest("LIVE_MATERIAL", "ABSENT");
        return digest("LIVE_MATERIAL", material.getId(), material.getFactoryId(), material.getCode(),
                material.getCode(), material.getName(), material.getCategory(), material.getUnit(),
                String.valueOf(material.getIsActive()), String.valueOf(material.getUpdatedAt()));
    }

    private String materialDraftSnapshot(MaterialTypeDraft draft) {
        if (draft == null) return digest("MATERIAL_DRAFT", "ABSENT");
        return digest("MATERIAL_DRAFT", draft.getId(), draft.getFactoryId(), draft.getBlueprintId(),
                String.valueOf(draft.getBlueprintVersion()), draft.getSourceDigest(), draft.getSourceSnapshotDigest(),
                draft.getNaturalKey(), draft.getDesiredDigest(), draft.getPreviewDigest(), draft.getStatus(),
                String.valueOf(draft.getLockVersion()));
    }

    private String relationshipSnapshot(SupplierMaterial relation) {
        if (relation == null) return digest("SUPPLIER_MATERIAL", "ABSENT");
        return digest("SUPPLIER_MATERIAL", relation.getId(), relation.getFactoryId(), relation.getSupplierId(),
                relation.getMaterialTypeId(), relation.getPurchaseUnit(), String.valueOf(relation.getActive()),
                String.valueOf(relation.getPreferred()), String.valueOf(relation.getVersion()),
                String.valueOf(relation.getUpdatedAt()));
    }

    private String qualificationEvidence(QualificationType type, List<SupplierQualification> rows) {
        List<String> values = rows.stream().filter(row -> type.name().equals(row.getQualificationType()))
                .sorted(Comparator.comparing(SupplierQualification::getCertificateNumber)
                        .thenComparing(row -> String.valueOf(row.getId())))
                .map(row -> digest(row.getQualificationType(), row.getCertificateNumber(), row.getIssuingAuthority(),
                        String.valueOf(row.getIssueDate()), String.valueOf(row.getExpiryDate()), row.getStatus(),
                        String.valueOf(row.getLastReviewedAt()), String.valueOf(row.getLastReviewedByUserId()),
                        digest("ATTACHMENT_REF", clean(row.getAttachmentOssUrl())))).toList();
        List<String> aggregate = new ArrayList<>();
        aggregate.add(type.name()); aggregate.addAll(values);
        return digest(aggregate.toArray(String[]::new));
    }

    private String admissionConfigSnapshot(String factoryId) {
        SupplierAdmissionRuleConfig config = admissionConfigRepository.findByFactoryId(factoryId).orElse(null);
        if (config == null) return digest("DEFAULT_CONFIG_FALLBACK", factoryId);
        return digest("PERSISTED_CONFIG", config.getId(), config.getFactoryId(),
                String.valueOf(config.getConfigVersion()), String.valueOf(config.isEnabled()),
                canonicalMap(config.getAdmissionRules()), canonicalMap(config.getAcceptanceRules()),
                String.valueOf(config.getUpdatedAt()));
    }

    private String canonicalMap(Map<String, Object> source) {
        return canonicalValue(source == null ? Map.of() : source);
    }

    private String canonicalValue(Object value) {
        if (value == null) return "N";
        if (value instanceof Map<?, ?> map) {
            List<? extends Map.Entry<String, Object>> entries = map.entrySet().stream()
                    .map(entry -> new java.util.AbstractMap.SimpleImmutableEntry<>(
                            String.valueOf(entry.getKey()), entry.getValue()))
                    .sorted(Map.Entry.comparingByKey()).toList();
            StringBuilder result = new StringBuilder("M{");
            entries.forEach(entry -> result.append(token(entry.getKey())).append(canonicalValue(entry.getValue())));
            return result.append('}').toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder result = new StringBuilder("L[");
            iterable.forEach(item -> result.append(canonicalValue(item)));
            return result.append(']').toString();
        }
        if (value instanceof Number number) {
            return "D" + token(new java.math.BigDecimal(number.toString()).stripTrailingZeros().toPlainString());
        }
        if (value instanceof Boolean bool) return bool ? "B1" : "B0";
        return "S" + token(String.valueOf(value));
    }

    private String token(String value) {
        String safe = Objects.requireNonNullElse(value, "");
        return safe.length() + ":" + safe;
    }

    private String sourceSnapshot(Factory factory, String configDigest, List<ResolvedPolicy> policies) {
        List<String> values = new ArrayList<>();
        values.add("FACTORY");
        values.add(factory == null ? "ABSENT" : factory.getId());
        values.add(factory == null ? "" : factory.getName());
        values.add(factory == null ? "" : String.valueOf(factory.getIsActive()));
        values.add(factory == null ? "" : factory.getIndustryCode());
        values.add(factory == null ? "" : String.valueOf(factory.getUpdatedAt()));
        values.add(configDigest);
        policies.forEach(policy -> {
            values.add(policy.supplierSnapshotDigest()); values.add(policy.materialSnapshotDigest());
            values.add(policy.relationshipSnapshotDigest());
            policy.requiredQualifications().forEach(q -> values.add(q.currentEvidenceDigest()));
        });
        return digest(values.toArray(String[]::new));
    }

    private String desiredDigest(List<ResolvedPolicy> policies) {
        List<String> values = new ArrayList<>();
        values.add(SupplierReceivingPolicyDraftModels.SCHEMA_VERSION);
        for (ResolvedPolicy policy : policies) {
            values.add(policy.supplierId()); values.add(String.valueOf(policy.materialRefType()));
            values.add(policy.materialRefId()); values.add(String.valueOf(policy.acceptanceMode()));
            values.add(String.valueOf(policy.qualificationExpiryPolicy()));
            values.add(policy.samplePercentage() == null ? "" : policy.samplePercentage().stripTrailingZeros().toPlainString());
            values.add(String.valueOf(policy.minSampleSize())); values.add(String.valueOf(policy.maxSampleSize()));
            values.add(String.valueOf(policy.quarantineOnFailure()));
            values.add(String.valueOf(policy.receivingEvidenceRequired())); values.add(policy.policyBasis());
            policy.requiredQualifications().forEach(q -> values.add(q.qualificationType().name()));
        }
        return digest(values.toArray(String[]::new));
    }

    private String requestDigest(DraftRequest request, AuthenticatedOperator operator) {
        DraftPreview preview = preview(request);
        return digest(request.factoryId(), request.blueprintId(), String.valueOf(request.blueprintVersion()),
                request.sourceDigest(), operator.actor().type().name() + ":" + String.valueOf(operator.actor().id()), request.idempotencyKey(),
                request.confirmedPreviewDigest(), preview.desiredDigest());
    }

    private void requireExactReadback(SupplierReceivingPolicyDraft parent, DraftPreview preview) {
        DraftReadback readback = readBack(parent.getFactoryId(), parent.getId());
        if (!"DRAFT".equals(readback.status()) || !preview.desiredDigest().equals(readback.desiredDigest())
                || readback.policies().size() != preview.policies().size()) throw readbackMismatch();
    }

    private DraftReceipt replay(SupplierReceivingPolicyDraft row, String requestDigest) {
        if (!requestDigest.equals(row.getRequestDigest())) throw invalid(409,
                "相同幂等键已用于不同来料策略载荷", "SUPPLIER_RECEIVING_IDEMPOTENCY_PAYLOAD_CONFLICT",
                "生成新的幂等键或恢复原请求", "idempotencyKey");
        readBack(row.getFactoryId(), row.getId());
        return receipt(row, true, 0, 0);
    }

    private DraftReceipt receipt(SupplierReceivingPolicyDraft row, boolean replayed, int writes, int persistedRows) {
        return new DraftReceipt(SupplierReceivingPolicyDraftModels.SCHEMA_VERSION, row.getId(), row.getFactoryId(),
                row.getStatus(), row.getSourceSnapshotDigest(), row.getDesiredDigest(), row.getPreviewDigest(),
                row.getRequestDigest(), row.getPolicyCount(), row.getQualificationCount(),
                Objects.requireNonNullElse(row.getLockVersion(), 0L), replayed, writes, 1, persistedRows,
                0, 0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    private void validateEnvelope(DraftRequest request, boolean requireConfirmation) {
        if (request == null) throw invalid(400, "来料策略请求不能为空", "SUPPLIER_RECEIVING_REQUEST_REQUIRED",
                "从已确认的 supplier-receiving ChangeSet 生成请求", "request");
        if (clean(request.factoryId()).isBlank() || clean(request.blueprintId()).isBlank() || request.blueprintVersion() <= 0)
            throw invalid(400, "工厂与 Blueprint 版本不能为空", "SUPPLIER_RECEIVING_SCOPE_REQUIRED",
                    "锁定 factoryId、blueprintId 和 blueprintVersion", "blueprint");
        if (!clean(request.sourceDigest()).matches("[0-9a-f]{64}")) throw invalid(400,
                "Blueprint sourceDigest 必须是 SHA-256", "SUPPLIER_RECEIVING_SOURCE_INVALID",
                "使用 typed compiler 产物摘要", "sourceDigest");
        if (requireConfirmation && request.operatorId() <= 0) throw invalid(400, "操作人不能为空", "SUPPLIER_RECEIVING_OPERATOR_REQUIRED",
                "从认证会话填充 operatorId", "operatorId");
        if (requireConfirmation && (clean(request.idempotencyKey()).isBlank() || clean(request.idempotencyKey()).length() > 128))
            throw invalid(400, "幂等键无效", "SUPPLIER_RECEIVING_IDEMPOTENCY_KEY_INVALID",
                    "提供 1-128 字符稳定幂等键", "idempotencyKey");
        if (request.policies() == null || request.policies().isEmpty() || request.policies().size() > 200)
            throw invalid(400, "来料策略必须包含 1-200 项", "SUPPLIER_RECEIVING_POLICIES_REQUIRED",
                    "按供应商与物料明确策略", "policies");
        if (requireConfirmation && !clean(request.confirmedPreviewDigest()).matches("[0-9a-f]{64}"))
            throw invalid(400, "必须确认有效预览摘要", "SUPPLIER_RECEIVING_PREVIEW_CONFIRMATION_REQUIRED",
                    "先预览并确认 ChangeSet", "confirmedPreviewDigest");
    }

    private String issueDigest(List<DraftIssue> issues) {
        List<String> values = new ArrayList<>();
        issues.forEach(issue -> { values.add(issue.code()); values.add(issue.target()); values.add(issue.message()); });
        return digest(values.toArray(String[]::new));
    }

    private boolean isScopedIdempotencyRace(Throwable failure) {
        Throwable cursor = failure;
        while (cursor != null) {
            if (clean(cursor.getMessage()).toLowerCase(Locale.ROOT).contains("uk_supplier_receiving_draft_idempotency")) return true;
            cursor = cursor.getCause();
        }
        return false;
    }

    private ResolvedPolicy emptyPolicy() {
        return new ResolvedPolicy("", MaterialRefType.LIVE_MATERIAL, "", digest("MATERIAL", "ABSENT"),
                digest("SUPPLIER", "ABSENT"), digest("RELATIONSHIP", "ABSENT"), AcceptanceMode.STANDARD,
                SupplierReceivingPolicyDraftModels.QualificationExpiryPolicy.VALID_ONLY, null, 0, 0,
                false, false, "", List.of());
    }

    private BusinessException readbackMismatch() {
        return invalid(409, "来料策略模板回读与已确认 ChangeSet 不一致", "SUPPLIER_RECEIVING_READBACK_MISMATCH",
                "保留事务回滚并重新预览", "draft");
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

    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static DraftIssue issue(String code, String target, String message, String recovery) {
        return new DraftIssue(code, target, message, recovery);
    }
    private static BusinessException invalid(int status, String message, String code, String recovery, String target) {
        return new BusinessException(status, message).withCode(code).withHint(recovery)
                .withHintTarget(target).withSeverity("warning");
    }
}
