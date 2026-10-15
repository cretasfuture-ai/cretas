package com.cretas.aims.service.contractmaterialdraft;

import com.cretas.aims.blueprint.capabilityadapter.FactoryCapabilityAdapterContracts;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.CapabilityView;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintService;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.contractmaterialdraft.ContractMaterialPolicyDraft;
import com.cretas.aims.entity.contractmaterialdraft.ContractMaterialPolicyDraftEvidence;
import com.cretas.aims.entity.contractmaterialdraft.ContractMaterialPolicyDraftItem;
import com.cretas.aims.entity.supplierreceiving.SupplierReceivingPolicyDraft;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.contractmaterialdraft.ContractMaterialPolicyDraftEvidenceRepository;
import com.cretas.aims.repository.contractmaterialdraft.ContractMaterialPolicyDraftItemRepository;
import com.cretas.aims.repository.contractmaterialdraft.ContractMaterialPolicyDraftRepository;
import com.cretas.aims.repository.supplierreceiving.SupplierReceivingPolicyDraftRepository;
import com.cretas.aims.service.contractmaterialdraft.ContractMaterialPolicyDraftModels.AccountingTreatment;
import com.cretas.aims.service.contractmaterialdraft.ContractMaterialPolicyDraftModels.AuthenticatedOperator;
import com.cretas.aims.service.contractmaterialdraft.ContractMaterialPolicyDraftModels.DerivedPolicy;
import com.cretas.aims.service.contractmaterialdraft.ContractMaterialPolicyDraftModels.DraftIssue;
import com.cretas.aims.service.contractmaterialdraft.ContractMaterialPolicyDraftModels.DraftPreview;
import com.cretas.aims.service.contractmaterialdraft.ContractMaterialPolicyDraftModels.DraftReadback;
import com.cretas.aims.service.contractmaterialdraft.ContractMaterialPolicyDraftModels.DraftReceipt;
import com.cretas.aims.service.contractmaterialdraft.ContractMaterialPolicyDraftModels.DraftRequest;
import com.cretas.aims.service.contractmaterialdraft.ContractMaterialPolicyDraftModels.EvidenceType;
import com.cretas.aims.service.contractmaterialdraft.ContractMaterialPolicyDraftModels.IssuancePolicy;
import com.cretas.aims.service.contractmaterialdraft.ContractMaterialPolicyDraftModels.OwnershipType;
import com.cretas.aims.service.contractmaterialdraft.ContractMaterialPolicyDraftModels.PolicyInput;
import com.cretas.aims.service.contractmaterialdraft.ContractMaterialPolicyDraftModels.RecoveryPlan;
import com.cretas.aims.service.contractmaterialdraft.ContractMaterialPolicyDraftModels.ResponsibleRole;
import com.cretas.aims.service.contractmaterialdraft.ContractMaterialPolicyDraftModels.ReturnPolicy;
import com.cretas.aims.service.contractmaterialdraft.ContractMaterialPolicyDraftModels.SegregationRequirement;
import com.cretas.aims.service.contractmaterialdraft.ContractMaterialPolicyDraftModels.SettlementPolicy;
import com.cretas.aims.service.contractmaterialdraft.ContractMaterialPolicyDraftModels.SupplierMaterialRef;
import com.cretas.aims.service.contractmaterialdraft.ContractMaterialPolicyDraftModels.SupplierReceivingPin;
import com.cretas.aims.service.supplierreceivingdraft.SupplierReceivingPolicyDraftCommand;
import com.cretas.aims.service.supplierreceivingdraft.SupplierReceivingPolicyDraftModels;
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
 * Module-owned customer-supplied / contract-manufacturing material policy DRAFT command.
 *
 * <p>The immutable capability catalog v2 dependency closure is {@code materials +
 * supplier-receiving}. Every policy row pins one resolved policy of a same-factory, same-lineage,
 * readback-ready B1 supplier-receiving DRAFT and inherits the material and supplier identity from
 * that pin, so this command never resolves material master data itself.</p>
 *
 * <p>{@code contract-material-policy/v1} derives the accounting treatment and the four permission
 * flags. Customer-owned material is off-book: no AP posting, no inventory valuation, no purchase
 * order, and it must be returned or disposed of on completion. Cross-customer issuance is never
 * allowed for either ownership type.</p>
 *
 * <p>Purchase orders, receiving documents, inventory and batch movements, accounts-payable entries
 * and formal material records are never written, and no customer master record is read.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class ContractMaterialPolicyDraftCommand {
    public static final String CAPABILITY_ID = "contract-material";
    public static final String REQUIRED_PERMISSION = "contract-material:policy:draft";
    private static final String AUTHORITY_CODE = "CONTRACT_MATERIAL_CATALOG_AUTHORITY";
    private static final List<String> DEPENDENCIES =
            FactoryCapabilityAdapterContracts.dependenciesOf(CAPABILITY_ID);
    private static final int MAX_POLICIES = 500;
    private static final BigDecimal MAX_TOLERANCE = new BigDecimal("100");

    private final ContractMaterialPolicyDraftRepository drafts;
    private final ContractMaterialPolicyDraftItemRepository items;
    private final ContractMaterialPolicyDraftEvidenceRepository evidence;
    private final SupplierReceivingPolicyDraftRepository supplierReceivingDrafts;
    private final SupplierReceivingPolicyDraftCommand supplierReceivingCommand;
    private final CanonicalFactoryBlueprintService canonicalBlueprints;
    private final UserRepository users;

    public ContractMaterialPolicyDraftCommand(
            ContractMaterialPolicyDraftRepository drafts,
            ContractMaterialPolicyDraftItemRepository items,
            ContractMaterialPolicyDraftEvidenceRepository evidence,
            SupplierReceivingPolicyDraftRepository supplierReceivingDrafts,
            SupplierReceivingPolicyDraftCommand supplierReceivingCommand,
            CanonicalFactoryBlueprintService canonicalBlueprints,
            UserRepository users) {
        this.drafts = drafts;
        this.items = items;
        this.evidence = evidence;
        this.supplierReceivingDrafts = supplierReceivingDrafts;
        this.supplierReceivingCommand = supplierReceivingCommand;
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
        Optional<ContractMaterialPolicyDraft> prior = drafts
                .findByFactoryIdAndIdempotencyKey(request.factoryId(), request.idempotencyKey());
        if (prior.isPresent()) {
            if (!requestDigest.equals(prior.get().getRequestDigest())) {
                throw invalid(409, "幂等键对应不同客供料策略请求",
                        "CONTRACT_MATERIAL_IDEMPOTENCY_CONFLICT");
            }
            readBack(request.factoryId(), prior.get().getId());
            return receipt(prior.get(), true, 0, 0);
        }

        Evaluation evaluation = evaluate(request);
        DraftPreview plan = evaluation.preview();
        if (!"READY".equals(plan.status())) {
            throw invalid(409, "客供料策略预览被阻断", "CONTRACT_MATERIAL_PREVIEW_BLOCKED");
        }
        if (!request.confirmedPreviewDigest().equals(plan.previewDigest())) {
            throw invalid(409, "客供料策略确认摘要已漂移", "CONTRACT_MATERIAL_PREVIEW_DRIFT");
        }

        BlueprintAuthority authority = evaluation.authority();
        SupplierReceivingAuthority supply = evaluation.supply();
        ContractMaterialPolicyDraft parent = ContractMaterialPolicyDraft.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(request.factoryId())
                .blueprintId(request.blueprintId())
                .blueprintVersion(request.blueprintVersion())
                .sourceDigest(request.sourceDigest())
                .sourceSnapshotDigest(plan.sourceSnapshotDigest())
                .supplierReceivingDraftId(supply.draftId())
                .supplierReceivingDesiredDigest(supply.desiredDigest())
                .supplierReceivingSnapshotDigest(supply.snapshotDigest())
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
                .dependencyBasis(ContractMaterialPolicyDraftModels.DEPENDENCY_BASIS)
                .algorithmVersion(ContractMaterialPolicyDraftModels.ALGORITHM_VERSION)
                .dependencyClosureComplete(true)
                .status("DRAFT")
                .build();
        ContractMaterialPolicyDraft saved;
        try {
            saved = drafts.saveAndFlush(parent);
        } catch (DataIntegrityViolationException exception) {
            if (!isIdempotencyRace(exception)) throw exception;
            throw invalid(409, "客供料策略幂等键发生并发竞争",
                    "CONTRACT_MATERIAL_IDEMPOTENCY_RACE");
        }

        List<ContractMaterialPolicyDraftItem> rows = plan.policies().stream()
                .map(policy -> toRow(saved, policy)).toList();
        items.saveAllAndFlush(rows);
        evidence.saveAndFlush(ContractMaterialPolicyDraftEvidence.builder()
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
            throw invalid(409, "客供料策略回读摘要不一致",
                    "CONTRACT_MATERIAL_READBACK_MISMATCH");
        }
        return receipt(saved, false, 1, 2 + rows.size());
    }

    @Transactional(readOnly = true)
    public DraftReadback readBack(String factoryId, String draftId) {
        ContractMaterialPolicyDraft parent = drafts.findByIdAndFactoryId(draftId, factoryId)
                .orElseThrow(() -> invalid(404, "当前工厂没有该客供料策略草稿",
                        "CONTRACT_MATERIAL_NOT_FOUND"));
        List<ContractMaterialPolicyDraftItem> rows = items
                .findByDraftIdAndFactoryIdOrderByOrdinal(draftId, factoryId);
        List<ContractMaterialPolicyDraftEvidence> evidenceRows = evidence
                .findByDraftIdAndFactoryIdOrderByAuthorityCode(draftId, factoryId);
        if (!"DRAFT".equals(parent.getStatus())
                || !Boolean.TRUE.equals(parent.getDependencyClosureComplete())
                || !ContractMaterialPolicyDraftModels.DEPENDENCY_BASIS
                        .equals(parent.getDependencyBasis())
                || !ContractMaterialPolicyDraftModels.ALGORITHM_VERSION
                        .equals(parent.getAlgorithmVersion())
                || parent.getPolicyCount() == null || parent.getPolicyCount() != rows.size()
                || parent.getEvidenceCount() == null || parent.getEvidenceCount() != 1
                || evidenceRows.size() != 1) {
            throw invalid(409, "客供料策略父子行或依赖闭包不完整",
                    "CONTRACT_MATERIAL_READBACK_INVARIANT_BROKEN");
        }

        List<DraftIssue> issues = new ArrayList<>();
        BlueprintAuthority authority = resolveBlueprint(factoryId, parent.getBlueprintId(),
                parent.getBlueprintVersion(), issues);
        SupplierReceivingAuthority supply = resolveStoredSupply(parent, issues);
        if (!issues.isEmpty()
                || !parent.getAuthoritySnapshotDigest().equals(authority.authoritySnapshotDigest())
                || !parent.getCapabilityCatalogVersion().equals(authority.catalogVersion())
                || !parent.getCapabilityCatalogDigest().equals(authority.catalogDigest())
                || !parent.getSupplierReceivingSnapshotDigest().equals(supply.snapshotDigest())) {
            throw invalid(409, "客供料策略上游 Blueprint 或来料策略草稿已漂移",
                    "CONTRACT_MATERIAL_UPSTREAM_DRIFT");
        }
        String currentSourceSnapshot = sourceSnapshot(authority, supply);
        if (!currentSourceSnapshot.equals(parent.getSourceSnapshotDigest())) {
            throw invalid(409, "客供料策略源快照摘要已漂移",
                    "CONTRACT_MATERIAL_SOURCE_SNAPSHOT_DRIFT");
        }
        if (!sameEvidence(evidenceRows.get(0), authority)) {
            throw invalid(409, "客供料策略目录依据摘要不一致",
                    "CONTRACT_MATERIAL_EVIDENCE_MISMATCH");
        }

        List<DerivedPolicy> policies = new ArrayList<>();
        for (ContractMaterialPolicyDraftItem row : rows) {
            DerivedPolicy policy = fromRow(row);
            SupplyTarget target = supply.targets().get(supplyKey(policy.supplierId(),
                    policy.materialRefType(), policy.materialRefId()));
            if (target == null || target.ordinal() != policy.supplierReceivingItemOrdinal()
                    || !Objects.equals(target.materialSnapshotDigest(),
                            policy.materialSnapshotDigest())
                    || !Objects.equals(target.supplierSnapshotDigest(),
                            policy.supplierSnapshotDigest())
                    || !Objects.equals(target.acceptanceMode(), policy.acceptanceMode())) {
                throw invalid(409, "客供料策略引用的来料策略快照已漂移",
                        "CONTRACT_MATERIAL_SUPPLY_TARGET_DRIFT");
            }
            if (policy.accountingTreatment() != ContractMaterialPolicyDraftModels
                        .accountingTreatment(policy.ownershipType())
                    || policy.apPostingAllowed() != ContractMaterialPolicyDraftModels
                        .apPostingAllowed(policy.ownershipType())
                    || policy.inventoryValuationIncluded() != ContractMaterialPolicyDraftModels
                        .inventoryValuationIncluded(policy.ownershipType())
                    || policy.purchaseOrderAllowed() != ContractMaterialPolicyDraftModels
                        .purchaseOrderAllowed(policy.ownershipType())
                    || policy.crossCustomerIssueAllowed() != ContractMaterialPolicyDraftModels
                        .crossCustomerIssueAllowed(policy.ownershipType())
                    || policy.returnOnCompletionRequired() != ContractMaterialPolicyDraftModels
                        .returnOnCompletionRequired(policy.ownershipType())) {
                throw invalid(409, "客供料所有权派生结论与服务端算法不一致",
                        "CONTRACT_MATERIAL_POLICY_TAMPERED");
            }
            policies.add(policy);
        }
        if (!desiredDigest(policies).equals(parent.getDesiredDigest())) {
            throw invalid(409, "客供料策略逐字段回读摘要不一致",
                    "CONTRACT_MATERIAL_READBACK_MISMATCH");
        }
        return new DraftReadback(ContractMaterialPolicyDraftModels.SCHEMA_VERSION, factoryId,
                draftId, parent.getStatus(), policies, parent.getSourceSnapshotDigest(),
                parent.getDesiredDigest(), 0, 0, 0, 0, 0, 0, 0,
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    @Transactional(readOnly = true)
    public RecoveryPlan recoveryPlan(String factoryId, String draftId,
                                     String expectedDesiredDigest) {
        ContractMaterialPolicyDraft parent = drafts.findByIdAndFactoryId(draftId, factoryId)
                .orElseThrow(() -> invalid(404, "当前工厂没有该客供料策略草稿",
                        "CONTRACT_MATERIAL_NOT_FOUND"));
        if (!"DRAFT".equals(parent.getStatus()) || !isDigest(expectedDesiredDigest)
                || !expectedDesiredDigest.equals(parent.getDesiredDigest())) {
            throw invalid(409, "只能恢复摘要精确匹配的未发布客供料策略草稿",
                    "CONTRACT_MATERIAL_RECOVERY_NOT_ALLOWED");
        }
        return new RecoveryPlan(ContractMaterialPolicyDraftModels.SCHEMA_VERSION, factoryId,
                draftId, expectedDesiredDigest, "ARCHIVE_DRAFT_ONLY",
                "只归档未发布策略草稿；不创建采购单、收货单、库存或批次变动、应付账款或正式物料记录",
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    private Evaluation evaluate(DraftRequest request) {
        List<DraftIssue> issues = new ArrayList<>();
        BlueprintAuthority authority = resolveBlueprint(request.factoryId(), request.blueprintId(),
                request.blueprintVersion(), issues);
        SupplierReceivingAuthority supply = resolveSupply(request, issues);
        List<DerivedPolicy> derived = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        for (int ordinal = 0; ordinal < request.policies().size(); ordinal++) {
            PolicyInput input = request.policies().get(ordinal);
            String target = "policies[" + ordinal + "]";
            if (!validatePolicy(input, target, issues)) continue;
            SupplyTarget supplyTarget = supply.targets().get(supplyKey(
                    input.supplierMaterial().supplierId(),
                    input.supplierMaterial().materialRefType(),
                    input.supplierMaterial().materialRefId()));
            if (supplyTarget == null) {
                issues.add(issue("CONTRACT_MATERIAL_SUPPLY_TARGET_MISSING", target,
                        "客供料策略必须引用同一 readback-ready 来料策略草稿中的供应商与物料组合",
                        "重新选择来料策略草稿中已有的供应商与物料"));
                continue;
            }
            if (input.ownershipType() == OwnershipType.CUSTOMER_OWNED
                    && input.segregationRequirement()
                            == SegregationRequirement.LABELLED_SHARED_LOCATION
                    && "HIGH_RISK".equals(supplyTarget.acceptanceMode())) {
                issues.add(issue("CONTRACT_MATERIAL_HIGH_RISK_NEEDS_DEDICATED_SEGREGATION", target,
                        "高风险来料的客供料不能只靠标识共用库位",
                        "改为专用库位或专用仓库"));
                continue;
            }
            String identity = input.customerScopeCode() + " "
                    + input.supplierMaterial().supplierId() + " "
                    + input.supplierMaterial().materialRefType() + " "
                    + input.supplierMaterial().materialRefId();
            if (!identities.add(identity)) {
                issues.add(issue("CONTRACT_MATERIAL_DUPLICATE_POLICY", target,
                        "同一合同范围、供应商和物料不能重复配置策略", "合并重复策略"));
                continue;
            }
            derived.add(derive(ordinal, input, supplyTarget));
        }
        issues.sort(Comparator.comparing(DraftIssue::code).thenComparing(DraftIssue::target));

        String desired = desiredDigest(derived);
        String sourceSnapshot = sourceSnapshot(authority, supply);
        String status = issues.isEmpty() ? "READY" : "BLOCKED";
        String previewDigest = digest("CONTRACT_MATERIAL_PREVIEW",
                ContractMaterialPolicyDraftModels.SCHEMA_VERSION,
                ContractMaterialPolicyDraftModels.ALGORITHM_VERSION, status, sourceSnapshot,
                supply.snapshotDigest(), authority.authoritySnapshotDigest(), desired,
                issueDigest(issues));
        DraftPreview preview = new DraftPreview(
                ContractMaterialPolicyDraftModels.SCHEMA_VERSION,
                ContractMaterialPolicyDraftModels.ALGORITHM_VERSION, status, request.factoryId(),
                DEPENDENCIES, request, derived, sourceSnapshot, supply.snapshotDigest(),
                authority.authoritySnapshotDigest(), desired, previewDigest, issues,
                0, 0, 0, 0, 0, 0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED");
        return new Evaluation(preview, authority, supply);
    }

    private BlueprintAuthority resolveBlueprint(String factoryId, String blueprintId,
                                                long blueprintVersion, List<DraftIssue> issues) {
        BlueprintResponse response = canonicalBlueprints.current(factoryId);
        if (response == null || !blueprintId.equals(response.getId())
                || !Objects.equals(Math.toIntExact(blueprintVersion), response.getVersion())
                || !("DRAFT".equals(response.getLifecycleState())
                     || "VALIDATED".equals(response.getLifecycleState()))) {
            issues.add(issue("CONTRACT_MATERIAL_BLUEPRINT_LINEAGE_MISMATCH", "blueprintId",
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
            issues.add(issue("CONTRACT_MATERIAL_CAPABILITY_NOT_READY", "capability",
                    "contract-material 或 materials/supplier-receiving 目录依赖尚未就绪",
                    "补齐物料与来料策略证据后重新编译 Blueprint"));
        }
        Set<String> refs = capability == null || capability.getAuthorityRefs() == null
                ? Set.of() : Set.copyOf(capability.getAuthorityRefs());
        if (response.getCapabilityCatalogVersion() == null
                || response.getCapabilityCatalogVersion().isBlank()
                || !isDigest(response.getCapabilityCatalogDigest()) || refs.isEmpty()) {
            issues.add(issue("CONTRACT_MATERIAL_AUTHORITY_CATALOG_INVALID", "capabilityCatalog",
                    "服务端能力目录版本、摘要或委托加工依据无效",
                    "部署带 authority refs 的版本化能力目录"));
        }
        String authoritySnapshot = digest("CONTRACT_MATERIAL_AUTHORITY",
                response.getCapabilityCatalogVersion(), response.getCapabilityCatalogDigest(),
                refs.stream().sorted().reduce("", (left, right) -> left + "|" + right));
        return new BlueprintAuthority(response.getCapabilityCatalogVersion(),
                response.getCapabilityCatalogDigest(), refs, authoritySnapshot,
                digestBlueprint(response));
    }

    private SupplierReceivingAuthority resolveSupply(DraftRequest request,
                                                     List<DraftIssue> issues) {
        SupplierReceivingPin pin = request.supplierReceiving();
        if (pin == null || pin.draftId().isBlank() || !isDigest(pin.desiredDigest())) {
            issues.add(issue("CONTRACT_MATERIAL_SUPPLY_PIN_INVALID", "supplierReceiving",
                    "客供料策略必须锁定同一 Blueprint 的来料策略草稿",
                    "选择当前 Blueprint 的来料策略草稿并刷新摘要"));
            return SupplierReceivingAuthority.none();
        }
        SupplierReceivingPolicyDraft parent = supplierReceivingDrafts
                .findByIdAndFactoryId(pin.draftId(), request.factoryId()).orElse(null);
        if (parent == null || !"DRAFT".equals(parent.getStatus())
                || !Boolean.TRUE.equals(parent.getDependencyClosureComplete())
                || !request.blueprintId().equals(parent.getBlueprintId())
                || !Objects.equals(request.blueprintVersion(), parent.getBlueprintVersion())
                || !request.sourceDigest().equals(parent.getSourceDigest())
                || !pin.desiredDigest().equals(parent.getDesiredDigest())) {
            issues.add(issue("CONTRACT_MATERIAL_SUPPLY_DRIFT", "supplierReceiving",
                    "B1 来料策略草稿不属于当前 Blueprint 或摘要已变化",
                    "重新读取并选择当前来料策略草稿"));
            return SupplierReceivingAuthority.none();
        }
        try {
            return supplyAuthority(parent,
                    supplierReceivingCommand.readBack(request.factoryId(), pin.draftId()));
        } catch (RuntimeException exception) {
            issues.add(issue("CONTRACT_MATERIAL_SUPPLY_READBACK_FAILED", "supplierReceiving",
                    "B1 来料策略草稿逐字段回读失败", "修复来料策略草稿后重新预览"));
            return SupplierReceivingAuthority.none();
        }
    }

    private SupplierReceivingAuthority resolveStoredSupply(ContractMaterialPolicyDraft parent,
                                                           List<DraftIssue> issues) {
        SupplierReceivingPolicyDraft stored = supplierReceivingDrafts.findByIdAndFactoryId(
                parent.getSupplierReceivingDraftId(), parent.getFactoryId()).orElse(null);
        if (stored == null
                || !Objects.equals(parent.getSupplierReceivingDesiredDigest(),
                        stored.getDesiredDigest())
                || !parent.getBlueprintId().equals(stored.getBlueprintId())
                || !Objects.equals(parent.getBlueprintVersion(), stored.getBlueprintVersion())
                || !parent.getSourceDigest().equals(stored.getSourceDigest())) {
            issues.add(issue("CONTRACT_MATERIAL_SUPPLY_DRIFT", "supplierReceiving",
                    "已保存的 B1 来料策略 pin 已漂移", "重新生成客供料策略草稿"));
            return SupplierReceivingAuthority.none();
        }
        try {
            return supplyAuthority(stored,
                    supplierReceivingCommand.readBack(parent.getFactoryId(), stored.getId()));
        } catch (RuntimeException exception) {
            issues.add(issue("CONTRACT_MATERIAL_SUPPLY_READBACK_FAILED", "supplierReceiving",
                    "已保存的 B1 来料策略草稿逐字段回读失败", "修复来料策略草稿后重新生成草稿"));
            return SupplierReceivingAuthority.none();
        }
    }

    private SupplierReceivingAuthority supplyAuthority(
            SupplierReceivingPolicyDraft parent,
            SupplierReceivingPolicyDraftModels.DraftReadback readback) {
        Map<String, SupplyTarget> targets = new LinkedHashMap<>();
        List<String> values = new ArrayList<>(List.of(readback.schemaVersion(),
                readback.factoryId(), readback.draftId(), readback.status(),
                readback.desiredDigest()));
        for (int ordinal = 0; ordinal < readback.policies().size(); ordinal++) {
            SupplierReceivingPolicyDraftModels.ResolvedPolicy policy =
                    readback.policies().get(ordinal);
            String key = supplyKey(policy.supplierId(), policy.materialRefType().name(),
                    policy.materialRefId());
            targets.putIfAbsent(key, new SupplyTarget(ordinal, policy.supplierId(),
                    policy.materialRefType().name(), policy.materialRefId(),
                    policy.materialSnapshotDigest(), policy.supplierSnapshotDigest(),
                    policy.acceptanceMode().name()));
            values.add(ordinal + "|" + key + "|" + policy.materialSnapshotDigest() + "|"
                    + policy.supplierSnapshotDigest() + "|" + policy.acceptanceMode() + "|"
                    + policy.quarantineOnFailure() + "|" + policy.receivingEvidenceRequired());
        }
        return new SupplierReceivingAuthority(parent.getId(), parent.getDesiredDigest(),
                digest(values.toArray(String[]::new)), Map.copyOf(targets));
    }

    private boolean validatePolicy(PolicyInput input, String target, List<DraftIssue> issues) {
        if (input == null || input.supplierMaterial() == null
                || isBlankOrTooLong(input.customerScopeCode(), 60)
                || !input.customerScopeCode().matches("[A-Z0-9][A-Z0-9_.-]{0,59}")
                || isBlankOrTooLong(input.supplierMaterial().supplierId(), 191)
                || isBlankOrTooLong(input.supplierMaterial().materialRefId(), 191)
                || isBlankOrTooLong(input.supplierMaterial().materialRefType(), 30)
                || input.ownershipType() == null || input.segregationRequirement() == null
                || input.issuancePolicy() == null || input.returnPolicy() == null
                || input.settlementPolicy() == null || input.responsibleRole() == null
                || isBlankOrTooLong(input.changeReason(), 500)) {
            issues.add(issue("CONTRACT_MATERIAL_POLICY_FIELDS_REQUIRED", target,
                    "合同范围码、供应商与物料引用、所有权、隔离、领用、退料、核销、责任和变更原因均必填",
                    "补齐 typed 策略字段"));
            return false;
        }
        boolean valid = true;
        if (input.residualTolerancePercent() == null
                || input.residualTolerancePercent().signum() < 0
                || input.residualTolerancePercent().compareTo(MAX_TOLERANCE) > 0
                || input.residualTolerancePercent().precision() > 7
                || input.residualTolerancePercent().scale() > 4
                || input.retentionDays() == null || input.retentionDays() < 1
                || input.retentionDays() > 3650) {
            issues.add(issue("CONTRACT_MATERIAL_TOLERANCE_RETENTION_INVALID", target,
                    "余料容差必须为 0–100 的 typed 百分比，留存期必须为 1–3650 天",
                    "修正余料容差与留存期"));
            valid = false;
        }
        if (input.ownershipType() == OwnershipType.CUSTOMER_OWNED) {
            if (input.returnPolicy() == ReturnPolicy.RETAIN_FOR_NEXT_CONTRACT_ORDER) {
                issues.add(issue("CONTRACT_MATERIAL_CUSTOMER_OWNED_CANNOT_BE_RETAINED", target,
                        "客户所有的余料不能默认留用，必须退回或经客户批准处置",
                        "改为退回客户或经客户批准报废"));
                valid = false;
            }
            if (input.settlementPolicy() != SettlementPolicy.NO_SETTLEMENT_CUSTOMER_OWNED
                    && input.settlementPolicy() != SettlementPolicy.PROCESSING_FEE_ONLY) {
                issues.add(issue("CONTRACT_MATERIAL_CUSTOMER_OWNED_SETTLEMENT_INVALID", target,
                        "客户所有的物料不能按全额材料加工费核销",
                        "改为不核销材料或只收加工费"));
                valid = false;
            }
        } else if (input.settlementPolicy() == SettlementPolicy.NO_SETTLEMENT_CUSTOMER_OWNED) {
            issues.add(issue("CONTRACT_MATERIAL_FACTORY_OWNED_SETTLEMENT_INVALID", target,
                    "工厂自有的合同物料必须有核销口径",
                    "改为只收加工费或全额材料加工费"));
            valid = false;
        }
        if (input.returnPolicy() == ReturnPolicy.SCRAP_WITH_CUSTOMER_APPROVAL
                && !input.requiredEvidence().contains(EvidenceType.CUSTOMER_APPROVAL)) {
            issues.add(issue("CONTRACT_MATERIAL_SCRAP_NEEDS_CUSTOMER_APPROVAL", target,
                    "经客户批准报废必须留存客户批准证据", "增加客户批准留证"));
            valid = false;
        }
        if (input.returnPolicy() == ReturnPolicy.RETURN_TO_CUSTOMER
                && !input.requiredEvidence().contains(EvidenceType.WEIGHT_RECORD)) {
            issues.add(issue("CONTRACT_MATERIAL_RETURN_NEEDS_WEIGHT_RECORD", target,
                    "退回客户必须留存过磅或计数记录", "增加重量记录留证"));
            valid = false;
        }
        if (input.requiredEvidence().isEmpty() || input.requiredEvidence().size() > 5
                || new HashSet<>(input.requiredEvidence()).size()
                   != input.requiredEvidence().size()) {
            issues.add(issue("CONTRACT_MATERIAL_EVIDENCE_INVALID", target,
                    "留证类型必须为非空、不重复的封闭枚举", "选择有效留证组合"));
            valid = false;
        }
        return valid;
    }

    private DerivedPolicy derive(int ordinal, PolicyInput input, SupplyTarget supply) {
        List<EvidenceType> requiredEvidence = input.requiredEvidence().stream()
                .sorted(Comparator.comparing(Enum::name)).toList();
        String code = "CTM-" + digest(input.customerScopeCode(), supply.supplierId(),
                supply.materialRefType(), supply.materialRefId()).substring(0, 16)
                .toUpperCase(Locale.ROOT);
        OwnershipType ownership = input.ownershipType();
        return new DerivedPolicy(ordinal, code, input.customerScopeCode(), supply.supplierId(),
                supply.materialRefType(), supply.materialRefId(), supply.ordinal(),
                supply.materialSnapshotDigest(), supply.supplierSnapshotDigest(),
                supply.acceptanceMode(), ownership, input.segregationRequirement(),
                input.issuancePolicy(), input.returnPolicy(), input.settlementPolicy(),
                input.residualTolerancePercent(), input.retentionDays(), input.responsibleRole(),
                requiredEvidence,
                ContractMaterialPolicyDraftModels.accountingTreatment(ownership),
                ContractMaterialPolicyDraftModels.apPostingAllowed(ownership),
                ContractMaterialPolicyDraftModels.inventoryValuationIncluded(ownership),
                ContractMaterialPolicyDraftModels.purchaseOrderAllowed(ownership),
                ContractMaterialPolicyDraftModels.crossCustomerIssueAllowed(ownership),
                ContractMaterialPolicyDraftModels.returnOnCompletionRequired(ownership),
                input.changeReason());
    }

    private ContractMaterialPolicyDraftItem toRow(ContractMaterialPolicyDraft parent,
                                                   DerivedPolicy value) {
        return ContractMaterialPolicyDraftItem.builder()
                .id(UUID.randomUUID().toString()).draftId(parent.getId())
                .factoryId(parent.getFactoryId()).ordinal(value.ordinal())
                .policyCode(value.policyCode()).customerScopeCode(value.customerScopeCode())
                .supplierReceivingDraftId(parent.getSupplierReceivingDraftId())
                .supplierId(value.supplierId()).materialRefType(value.materialRefType())
                .materialRefId(value.materialRefId())
                .supplierReceivingItemOrdinal(value.supplierReceivingItemOrdinal())
                .materialSnapshotDigest(value.materialSnapshotDigest())
                .supplierSnapshotDigest(value.supplierSnapshotDigest())
                .acceptanceMode(value.acceptanceMode())
                .ownershipType(value.ownershipType().name())
                .segregationRequirement(value.segregationRequirement().name())
                .issuancePolicy(value.issuancePolicy().name())
                .returnPolicy(value.returnPolicy().name())
                .settlementPolicy(value.settlementPolicy().name())
                .residualTolerancePercent(value.residualTolerancePercent())
                .retentionDays(value.retentionDays())
                .responsibleRole(value.responsibleRole().name())
                .requiredEvidence(joinEnums(value.requiredEvidence()))
                .accountingTreatment(value.accountingTreatment().name())
                .apPostingAllowed(value.apPostingAllowed())
                .inventoryValuationIncluded(value.inventoryValuationIncluded())
                .purchaseOrderAllowed(value.purchaseOrderAllowed())
                .crossCustomerIssueAllowed(value.crossCustomerIssueAllowed())
                .returnOnCompletionRequired(value.returnOnCompletionRequired())
                .changeReason(value.changeReason()).build();
    }

    private DerivedPolicy fromRow(ContractMaterialPolicyDraftItem row) {
        return new DerivedPolicy(row.getOrdinal(), row.getPolicyCode(), row.getCustomerScopeCode(),
                row.getSupplierId(), row.getMaterialRefType(), row.getMaterialRefId(),
                row.getSupplierReceivingItemOrdinal(), row.getMaterialSnapshotDigest(),
                row.getSupplierSnapshotDigest(), row.getAcceptanceMode(),
                OwnershipType.valueOf(row.getOwnershipType()),
                SegregationRequirement.valueOf(row.getSegregationRequirement()),
                IssuancePolicy.valueOf(row.getIssuancePolicy()),
                ReturnPolicy.valueOf(row.getReturnPolicy()),
                SettlementPolicy.valueOf(row.getSettlementPolicy()),
                row.getResidualTolerancePercent(), row.getRetentionDays(),
                ResponsibleRole.valueOf(row.getResponsibleRole()),
                parseEnums(row.getRequiredEvidence(), EvidenceType::valueOf),
                AccountingTreatment.valueOf(row.getAccountingTreatment()),
                Boolean.TRUE.equals(row.getApPostingAllowed()),
                Boolean.TRUE.equals(row.getInventoryValuationIncluded()),
                Boolean.TRUE.equals(row.getPurchaseOrderAllowed()),
                Boolean.TRUE.equals(row.getCrossCustomerIssueAllowed()),
                Boolean.TRUE.equals(row.getReturnOnCompletionRequired()), row.getChangeReason());
    }

    private void authorize(DraftRequest request, AuthenticatedOperator operator) {
        if (operator == null || (operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER && operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.PLATFORM_ADMIN) || operator.actor().id() != request.operatorId()
                || !request.factoryId().equals(operator.factoryId())) {
            throw invalid(403, "操作人和工厂范围不一致",
                    "CONTRACT_MATERIAL_OPERATOR_SCOPE_MISMATCH");
        }
        if (!operator.permissions().contains(REQUIRED_PERMISSION)) {
            throw invalid(403, "缺少客供料策略草稿权限",
                    "CONTRACT_MATERIAL_PERMISSION_REQUIRED");
        }
        Optional<User> member = users.findByIdAndFactoryId(operator.operatorId(),
                operator.factoryId());
        if (member.filter(user -> Boolean.TRUE.equals(user.getIsActive())).isEmpty()) {
            throw invalid(403, "操作人不是当前工厂有效成员",
                    "CONTRACT_MATERIAL_MEMBER_REQUIRED");
        }
    }

    private void validateEnvelope(DraftRequest request, boolean materialize) {
        if (request == null || request.factoryId().isBlank() || request.factoryId().length() > 50
                || request.blueprintId().isBlank() || request.blueprintId().length() > 100
                || request.blueprintVersion() <= 0 || !isDigest(request.sourceDigest())
                || request.policies().isEmpty() || request.policies().size() > MAX_POLICIES) {
            throw invalid(400, "Blueprint envelope 或客供料策略数量无效",
                    "CONTRACT_MATERIAL_SCOPE_REQUIRED");
        }
        if (materialize && (request.operatorId() <= 0 || request.idempotencyKey().isBlank()
                || request.idempotencyKey().length() > 128
                || !isDigest(request.confirmedPreviewDigest()))) {
            throw invalid(400, "操作人、幂等键或确认摘要无效",
                    "CONTRACT_MATERIAL_MATERIALIZE_ENVELOPE_INVALID");
        }
    }

    private DraftReceipt receipt(ContractMaterialPolicyDraft row, boolean replayed,
                                 int moduleWrites, int persistedRows) {
        return new DraftReceipt(ContractMaterialPolicyDraftModels.SCHEMA_VERSION, row.getId(),
                row.getFactoryId(), row.getId(), row.getStatus(), row.getSourceSnapshotDigest(),
                row.getDesiredDigest(), row.getPreviewDigest(), row.getRequestDigest(),
                row.getPolicyCount(), row.getEvidenceCount(),
                row.getLockVersion() == null ? 0L : row.getLockVersion(), replayed,
                moduleWrites, replayed ? 1 : moduleWrites, persistedRows,
                0, 0, 0, 0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED", "ARCHIVE_DRAFT_ONLY");
    }

    private boolean sameEvidence(ContractMaterialPolicyDraftEvidence row,
                                 BlueprintAuthority authority) {
        return AUTHORITY_CODE.equals(row.getAuthorityCode())
                && authority.catalogVersion().equals(row.getCatalogVersion())
                && authority.catalogDigest().equals(row.getCatalogDigest())
                && authorityEvidenceDigest(authority).equals(row.getAuthoritySnapshotDigest());
    }

    private String sourceSnapshot(BlueprintAuthority authority, SupplierReceivingAuthority supply) {
        return digest("CONTRACT_MATERIAL_SOURCE", authority.blueprintSnapshotDigest(),
                supply.snapshotDigest(), authority.authoritySnapshotDigest());
    }

    private String desiredDigest(List<DerivedPolicy> policies) {
        List<String> values = new ArrayList<>(
                List.of(ContractMaterialPolicyDraftModels.SCHEMA_VERSION,
                        ContractMaterialPolicyDraftModels.ALGORITHM_VERSION));
        policies.stream().sorted(Comparator.comparingInt(DerivedPolicy::ordinal))
                .forEach(value -> values.add(value.ordinal() + "|" + value.policyCode() + "|"
                        + value.customerScopeCode() + "|" + value.supplierId() + "|"
                        + value.materialRefType() + "|" + value.materialRefId() + "|"
                        + value.supplierReceivingItemOrdinal() + "|"
                        + value.materialSnapshotDigest() + "|" + value.supplierSnapshotDigest()
                        + "|" + value.acceptanceMode() + "|" + value.ownershipType() + "|"
                        + value.segregationRequirement() + "|" + value.issuancePolicy() + "|"
                        + value.returnPolicy() + "|" + value.settlementPolicy() + "|"
                        + decimal(value.residualTolerancePercent()) + "|" + value.retentionDays()
                        + "|" + value.responsibleRole() + "|"
                        + joinEnums(value.requiredEvidence()) + "|" + value.accountingTreatment()
                        + "|" + value.apPostingAllowed() + "|"
                        + value.inventoryValuationIncluded() + "|" + value.purchaseOrderAllowed()
                        + "|" + value.crossCustomerIssueAllowed() + "|"
                        + value.returnOnCompletionRequired() + "|" + value.changeReason()));
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
                    .contains("uk_contract_material_draft_idempotency")) return true;
        }
        return false;
    }

    private boolean isBlankOrTooLong(String value, int max) {
        return value == null || value.isBlank() || value.length() > max;
    }

    private boolean isDigest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private String supplyKey(String supplierId, String materialRefType, String materialRefId) {
        return clean(supplierId) + " " + clean(materialRefType) + " " + clean(materialRefId);
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
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
                              SupplierReceivingAuthority supply) {
    }

    private record BlueprintAuthority(String catalogVersion, String catalogDigest,
                                      Set<String> authorityRefs, String authoritySnapshotDigest,
                                      String blueprintSnapshotDigest) {
        static BlueprintAuthority missing() {
            return new BlueprintAuthority("", digestStatic("MISSING_CATALOG"), Set.of(),
                    digestStatic("MISSING_AUTHORITY"), digestStatic("MISSING_BLUEPRINT"));
        }
    }

    private record SupplierReceivingAuthority(String draftId, String desiredDigest,
                                              String snapshotDigest,
                                              Map<String, SupplyTarget> targets) {
        static SupplierReceivingAuthority none() {
            return new SupplierReceivingAuthority(null, null,
                    digestStatic("NO_SUPPLIER_RECEIVING_DRAFT"), Map.of());
        }
    }

    private record SupplyTarget(int ordinal, String supplierId, String materialRefType,
                                String materialRefId, String materialSnapshotDigest,
                                String supplierSnapshotDigest, String acceptanceMode) {
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
