package com.cretas.aims.blueprint.workflowmaterialize;

import com.cretas.aims.blueprint.rule.UnitContractRuleUnitResolver;
import com.cretas.aims.blueprint.workflowmaterialize.FactoryWorkflowDraftMaterializationModels.BomDraftReadback;
import com.cretas.aims.blueprint.workflowmaterialize.FactoryWorkflowDraftMaterializationModels.BomPinTarget;
import com.cretas.aims.blueprint.workflowmaterialize.FactoryWorkflowDraftMaterializationModels.DraftMaterializationReceipt;
import com.cretas.aims.blueprint.workflowmaterialize.FactoryWorkflowDraftMaterializationModels.DraftMaterializationRequest;
import com.cretas.aims.blueprint.workflowmaterialize.FactoryWorkflowDraftMaterializationModels.DraftMode;
import com.cretas.aims.blueprint.workflowmaterialize.FactoryWorkflowDraftMaterializationModels.DraftOperationPreview;
import com.cretas.aims.blueprint.workflowmaterialize.FactoryWorkflowDraftMaterializationModels.OperationIssue;
import com.cretas.aims.blueprint.workflowmaterialize.FactoryWorkflowDraftMaterializationModels.WorkflowDraftReadback;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowDraftPreviewCompiler;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowPreviewModels.CompileRequest;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowPreviewModels.ExistingDraft;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowPreviewModels.WorkflowDraftPreview;
import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.workflow.FactoryWorkflowDraftMaterializationLedger;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.workflow.FactoryWorkflowDraftMaterializationLedgerRepository;
import com.cretas.aims.service.ProductProcessWorkflowService;
import com.cretas.aims.service.bom.BomWorkflowRevisionService;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * B24 DRAFT-only execution gateway.
 *
 * <p>The gateway recompiles from locked server inputs, compares the confirmation digest,
 * applies one explicit conflict mode, and verifies readback in the same transaction. It has
 * deliberately no public controller: a future orchestration API must construct this command
 * from authoritative Blueprint state rather than accept an arbitrary client-authored graph.
 * That API is {@code WorkflowDraftEntry}: its request carries only user choices, and
 * {@code WorkflowCompileRequestAssembler} builds the compile request from the blueprint and the
 * live tables.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class FactoryWorkflowDraftMaterializationService {

    private final ProductProcessWorkflowRepository workflowRepository;
    private final BomRecipeRepository bomRepository;
    private final ProductProcessWorkflowService workflowService;
    private final BomWorkflowRevisionService bomWorkflowRevisionService;
    private final FactoryWorkflowDraftMaterializationLedgerRepository ledgerRepository;
    private final ObjectMapper objectMapper;
    private final ObjectMapper canonicalMapper;
    private final FactoryWorkflowDraftPreviewCompiler compiler;

    /** 工艺保密 (写入侧): 实体化会改写已有产品的工艺图草稿并把它的配方改钉到新版本, 要编辑权。 */
    @Autowired(required = false)
    private com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard processSecretGuard;

    @Autowired
    public FactoryWorkflowDraftMaterializationService(
            ProductProcessWorkflowRepository workflowRepository,
            BomRecipeRepository bomRepository,
            ProductProcessWorkflowService workflowService,
            BomWorkflowRevisionService bomWorkflowRevisionService,
            FactoryWorkflowDraftMaterializationLedgerRepository ledgerRepository,
            ObjectMapper objectMapper,
            ProductProcessWorkflowValidator validator,
            UnitContractService unitContractService) {
        this(workflowRepository, bomRepository, workflowService, bomWorkflowRevisionService,
                ledgerRepository, objectMapper,
                new FactoryWorkflowDraftPreviewCompiler(
                        validator, new UnitContractRuleUnitResolver(unitContractService)));
    }

    FactoryWorkflowDraftMaterializationService(
            ProductProcessWorkflowRepository workflowRepository,
            BomRecipeRepository bomRepository,
            ProductProcessWorkflowService workflowService,
            BomWorkflowRevisionService bomWorkflowRevisionService,
            FactoryWorkflowDraftMaterializationLedgerRepository ledgerRepository,
            ObjectMapper objectMapper,
            FactoryWorkflowDraftPreviewCompiler compiler) {
        this.workflowRepository = workflowRepository;
        this.bomRepository = bomRepository;
        this.workflowService = workflowService;
        this.bomWorkflowRevisionService = bomWorkflowRevisionService;
        this.ledgerRepository = ledgerRepository;
        this.objectMapper = objectMapper;
        this.canonicalMapper = objectMapper.copy()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.compiler = compiler;
    }

    /** No-write operation preview used by the explicit confirmation surface. */
    @Transactional(readOnly = true)
    public DraftOperationPreview preview(DraftMaterializationRequest request) {
        return prepare(request, false).operationPreview();
    }

    /** Materialize only DRAFT state and return the exact persisted readback. */
    @Transactional
    public DraftMaterializationReceipt materialize(DraftMaterializationRequest request) {
        requireEnvelope(request);
        String factoryId = request.compileRequest().factoryId();
        String idempotencyKey = normalizeIdempotencyKey(request.idempotencyKey());
        String requestDigest = requestDigest(request);

        Optional<FactoryWorkflowDraftMaterializationLedger> prior =
                ledgerRepository.findByFactoryIdAndIdempotencyKey(factoryId, idempotencyKey);
        if (prior.isPresent()) {
            return replay(prior.get(), requestDigest);
        }

        Prepared prepared = prepare(request, true);
        DraftOperationPreview operation = prepared.operationPreview();
        if (!"READY".equals(operation.status())) {
            OperationIssue first = operation.issues().isEmpty()
                    ? new OperationIssue("WORKFLOW_DRAFT_OPERATION_BLOCKED", "workflow",
                    "Workflow 草稿实体化被阻断", "刷新事实、目录与草稿后重新预览")
                    : operation.issues().getFirst();
            throw invalid(409, first.message(), first.code(), first.recovery(), first.target());
        }
        if (request.confirmedPreviewDigest().isBlank()
                || !request.confirmedPreviewDigest().equals(operation.previewDigest())) {
            throw invalid(409, "确认后的 Workflow 预览摘要已变化",
                    "WORKFLOW_DRAFT_PREVIEW_DIGEST_CONFLICT",
                    "重新查看 ChangeSet 并确认当前摘要", "previewDigest");
        }
        if (request.confirmedOperationDigest().isBlank()
                || !request.confirmedOperationDigest().equals(operation.operationDigest())) {
            throw invalid(409, "确认后的草稿操作目标或冲突策略已变化",
                    "WORKFLOW_DRAFT_OPERATION_DIGEST_CONFLICT",
                    "重新查看 Workflow/BOM DRAFT 操作预览", "operationDigest");
        }
        // 工艺保密 (写入侧, Steve 2026-10-27): 在写回执与任何草稿之前判; 无权整单 403
        if (processSecretGuard != null) {
            processSecretGuard.requireProduct(factoryId, operation.targetProductTypeId(),
                    com.cretas.aims.entity.processsecret.ProcessSecretAction.EDIT,
                    "workflow draft materialize " + operation.targetProductTypeId());
            processSecretGuard.requireRecipes(factoryId,
                    prepared.lockedBoms().stream().map(LockedBom::recipe).toList(),
                    com.cretas.aims.entity.processsecret.ProcessSecretAction.EDIT,
                    "workflow draft materialize re-pin");
        }

        String receiptId = UUID.randomUUID().toString();
        FactoryWorkflowDraftMaterializationLedger ledger = FactoryWorkflowDraftMaterializationLedger.builder()
                .id(receiptId)
                .factoryId(factoryId)
                .productTypeId(operation.targetProductTypeId())
                .idempotencyKey(idempotencyKey)
                .operatorId(new com.cretas.aims.security.ActorRef(request.actorType(), request.operatorId()).factoryUserId())
                .actorType(request.actorType())
                .actorId(request.operatorId())
                .requestDigest(requestDigest)
                .previewDigest(operation.previewDigest())
                .operationDigest(operation.operationDigest())
                .draftMode(request.mode().name())
                .completionState("IN_PROGRESS")
                .build();
        try {
            ledgerRepository.saveAndFlush(ledger);
        } catch (DataIntegrityViolationException race) {
            throw invalid(409, "相同工厂与幂等键的草稿操作正在完成或已完成",
                    "WORKFLOW_DRAFT_IDEMPOTENCY_RACE",
                    "使用同一幂等键重试以读取既有回执", "idempotencyKey", race);
        }

        ProductProcessWorkflowDTO desired = deepCopy(prepared.preview().desiredDefinition());
        ProductProcessWorkflowDTO saved;
        boolean workflowMutated = prepared.preview().changeSet().expectedDraftMutationCount() > 0;
        if (!workflowMutated && prepared.currentDefinition() != null) {
            saved = prepared.currentDefinition();
        } else {
            if (request.mode() == DraftMode.SNAPSHOT_AND_REPLACE) {
                ProductProcessWorkflowDTO nextDraft = workflowService.snapshot(
                        factoryId, operation.targetProductTypeId(), prepared.currentDefinition().getLockVersion());
                desired.setId(nextDraft.getId());
                desired.setVersion(nextDraft.getVersion());
                desired.setLockVersion(nextDraft.getLockVersion());
            } else if (request.mode() == DraftMode.MERGE_EXISTING) {
                desired.setId(prepared.currentDefinition().getId());
                desired.setVersion(prepared.currentDefinition().getVersion());
                desired.setLockVersion(prepared.currentDefinition().getLockVersion());
            } else {
                desired.setLockVersion(null);
            }
            saved = workflowService.saveDraft(factoryId, operation.targetProductTypeId(), desired);
        }
        requireDraftReadback(saved, desired);

        List<BomDraftReadback> bomReadbacks = new ArrayList<>();
        int bomWrites = 0;
        for (LockedBom locked : prepared.lockedBoms()) {
            BomRecipe recipe = locked.recipe();
            boolean alreadyPinned = Objects.equals(recipe.getWorkflowId(), saved.getId())
                    && Objects.equals(recipe.getWorkflowRevisionId(), saved.getRevisionId())
                    && Objects.equals(recipe.getWorkflowRevisionHash(), saved.getRevisionHash());
            if (!alreadyPinned) {
                bomWorkflowRevisionService.bindExactRevision(factoryId, recipe, saved.getRevisionId());
                bomWrites++;
            }
            BomRecipe readback = bomRepository.findById(recipe.getId())
                    .filter(item -> factoryId.equals(item.getFactoryId()))
                    .orElseThrow(() -> invalid(409, "BOM DRAFT 回读失败",
                            "WORKFLOW_DRAFT_BOM_READBACK_MISSING",
                            "刷新 BOM 后重新发起实体化", recipe.getId()));
            requireExactBomReadback(readback, saved);
            bomReadbacks.add(toBomReadback(readback));
        }

        int businessWrites = (workflowMutated ? 1 : 0) + bomWrites;
        WorkflowDraftReadback workflowReadback = new WorkflowDraftReadback(
                saved.getId(), saved.getFactoryId(), saved.getProductTypeId(), saved.getStatus(),
                saved.getVersion(), saved.getLockVersion(), saved.getRevisionId(), saved.getRevisionHash(),
                operation.previewDigest(), saved.getNodes().size(), saved.getEdges().size());
        DraftMaterializationReceipt receipt = new DraftMaterializationReceipt(
                null, receiptId, "DRAFTED", requestDigest, operation.previewDigest(),
                operation.operationDigest(), request.mode(), workflowReadback, bomReadbacks,
                bomReadbacks.isEmpty() ? "NOT_REQUESTED" : "PINNED_EXACT_REVISION",
                editorDeepLink(saved.getProductTypeId()), false, businessWrites, businessWrites,
                null, null, null,
                request.mode() == DraftMode.SNAPSHOT_AND_REPLACE
                        ? "旧草稿已保留为 SNAPSHOT；可在版本历史中恢复"
                        : "草稿未生效；可在编辑器中继续修订或删除未发布草稿");

        ledger.setCompletionState("COMPLETED");
        ledger.setResultJson(writeJson(receipt));
        ledgerRepository.saveAndFlush(ledger);
        return receipt;
    }

    private Prepared prepare(DraftMaterializationRequest request, boolean lock) {
        requireEnvelope(request);
        CompileRequest source = request.compileRequest();
        String factoryId = source.factoryId();
        List<OperationIssue> issues = new ArrayList<>();

        Optional<ProductProcessWorkflow> currentEntity = workflowRepository
                .findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                        factoryId, resolveTargetProduct(source), ProductProcessWorkflow.Status.DRAFT);
        if (lock && currentEntity.isPresent()) {
            currentEntity = workflowRepository.lockByIdAndFactoryId(currentEntity.get().getId(), factoryId)
                    .filter(row -> row.getStatus() == ProductProcessWorkflow.Status.DRAFT);
        }
        ProductProcessWorkflowDTO currentDefinition = currentEntity
                .flatMap(row -> workflowService.getEditorDefinition(factoryId, row.getProductTypeId()))
                .filter(dto -> "DRAFT".equals(dto.getStatus()))
                .orElse(null);

        validateMode(request, currentDefinition, issues);
        CompileRequest authoritative = authoritativeRequest(source, currentDefinition);
        WorkflowDraftPreview compiled = compiler.compile(authoritative);
        if (!"PREVIEW_READY".equals(compiled.status())) {
            String code = "CONTROL_SEMANTICS_REQUIRED".equals(compiled.status())
                    ? "WORKFLOW_CONTROL_DRAFT_ADAPTER_REQUIRED"
                    : "WORKFLOW_DRAFT_RECOMPILE_BLOCKED";
            String message = "CONTROL_SEMANTICS_REQUIRED".equals(compiled.status())
                    ? "当前分支/合流/返工控制语义尚无可回读的模块 DRAFT 存储，不能只保存流程图"
                    : "服务器重新编译未得到可实体化的 Workflow DRAFT";
            issues.add(new OperationIssue(code, "workflow", message,
                    "返回编译/控制语义阶段补齐阻断项后重新预览"));
        }
        if (compiled.changeSet() == null || compiled.desiredDefinition() == null) {
            issues.add(new OperationIssue("WORKFLOW_DRAFT_CHANGESET_REQUIRED", "workflow",
                    "服务器重新编译没有产生可确认 ChangeSet", "修正编译阻断项后重试"));
        } else if (request.mode() != DraftMode.CREATE_NEW_ONLY
                && (!Objects.equals(compiled.changeSet().expectedLockVersion(), request.expectedLockVersion())
                || !Objects.equals(compiled.changeSet().expectedRevisionHash(), request.expectedRevisionHash()))) {
            issues.add(new OperationIssue("WORKFLOW_DRAFT_CHANGESET_VERSION_CONFLICT", "workflow",
                    "重新编译的 ChangeSet 与请求锁定的草稿版本不一致",
                    "刷新当前草稿并重新确认 ChangeSet"));
        }

        String targetProduct = compiled.changeSet() == null
                ? resolveTargetProduct(source) : compiled.changeSet().targetProductTypeId();
        List<LockedBom> boms = validateBoms(
                factoryId, request.bomTargets(), lock, issues);
        List<BomPinTarget> sortedTargets = request.bomTargets().stream()
                .sorted(Comparator.comparing(BomPinTarget::recipeId)).toList();
        String operationDigest = operationDigest(compiled, request.mode(), request.expectedLockVersion(),
                request.expectedRevisionHash(), sortedTargets);
        int workflowWrites = compiled.changeSet() == null
                ? 0 : compiled.changeSet().expectedDraftMutationCount();
        String status = issues.isEmpty() ? "READY"
                : issues.stream().anyMatch(issue -> issue.code().contains("CONFLICT")) ? "CONFLICT" : "BLOCKED";
        DraftOperationPreview operation = new DraftOperationPreview(
                null, status, compiled.sourceDigest(), compiled.businessDigest(), operationDigest,
                request.mode(), factoryId, targetProduct, request.expectedLockVersion(),
                request.expectedRevisionHash(), sortedTargets, workflowWrites,
                request.bomTargets().size(), issues, null, null, 0);
        return new Prepared(compiled, operation, currentDefinition, boms);
    }

    private void validateMode(
            DraftMaterializationRequest request,
            ProductProcessWorkflowDTO current,
            List<OperationIssue> issues) {
        if (request.mode() == DraftMode.CREATE_NEW_ONLY) {
            if (current != null) {
                issues.add(new OperationIssue("WORKFLOW_DRAFT_ALREADY_EXISTS_CONFLICT", "workflow",
                        "已有 Workflow DRAFT；默认不能覆盖", "显式选择 MERGE_EXISTING 或 SNAPSHOT_AND_REPLACE"));
            }
            if (request.expectedLockVersion() != null || !request.expectedRevisionHash().isBlank()) {
                issues.add(new OperationIssue("WORKFLOW_DRAFT_CREATE_EXPECTATION_CONFLICT", "workflow",
                        "新建草稿不能携带既有草稿版本", "刷新并使用无既有版本的 CREATE_NEW_ONLY 预览"));
            }
            return;
        }
        if (current == null) {
            issues.add(new OperationIssue("WORKFLOW_DRAFT_MISSING_CONFLICT", "workflow",
                    "所选合并/快照策略需要既有 Workflow DRAFT", "刷新后改用 CREATE_NEW_ONLY"));
            return;
        }
        if (!Objects.equals(request.expectedLockVersion(), current.getLockVersion())
                || !Objects.equals(request.expectedRevisionHash(), current.getRevisionHash())) {
            issues.add(new OperationIssue("WORKFLOW_DRAFT_VERSION_CONFLICT", "workflow",
                    "Workflow DRAFT 的 lockVersion 或 revisionHash 已变化",
                    "刷新当前草稿并重新编译、查看差异后确认"));
        }
    }

    private List<LockedBom> validateBoms(
            String factoryId,
            List<BomPinTarget> targets,
            boolean lock,
            List<OperationIssue> issues) {
        List<LockedBom> result = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (BomPinTarget target : targets.stream().sorted(Comparator.comparing(BomPinTarget::recipeId)).toList()) {
            if (target.recipeId().isBlank() || seen.contains(target.recipeId())) {
                issues.add(new OperationIssue("WORKFLOW_DRAFT_BOM_TARGET_INVALID", target.recipeId(),
                        "BOM DRAFT 目标为空或重复", "每个既有 BOM DRAFT 只选择一次"));
                continue;
            }
            seen.add(target.recipeId());
            Optional<BomRecipe> found = lock
                    ? bomRepository.lockByIdAndFactoryId(target.recipeId(), factoryId)
                    : bomRepository.findById(target.recipeId()).filter(row -> factoryId.equals(row.getFactoryId()));
            if (found.isEmpty()) {
                issues.add(new OperationIssue("WORKFLOW_DRAFT_BOM_NOT_FOUND", target.recipeId(),
                        "BOM 不存在于当前工厂", "刷新当前工厂 BOM 草稿列表"));
                continue;
            }
            BomRecipe recipe = found.get();
            if (recipe.getStatus() != BomRecipe.Status.DRAFT) {
                issues.add(new OperationIssue("WORKFLOW_DRAFT_BOM_READ_ONLY", target.recipeId(),
                        "只有 BOM DRAFT 可以固定 Workflow 修订", "新建或选择 BOM 草稿，不修改 ACTIVE/ARCHIVED"));
                continue;
            }
            String actualHash = Objects.requireNonNullElse(recipe.getWorkflowRevisionHash(), "");
            if (!Objects.equals(target.expectedWorkflowRevisionId(), recipe.getWorkflowRevisionId())
                    || !Objects.equals(target.expectedWorkflowRevisionHash(), actualHash)) {
                issues.add(new OperationIssue("WORKFLOW_DRAFT_BOM_VERSION_CONFLICT", target.recipeId(),
                        "BOM DRAFT 的当前 Workflow pin 已变化", "刷新 BOM DRAFT 后重新查看操作预览"));
                continue;
            }
            result.add(new LockedBom(target, recipe));
        }
        return List.copyOf(result);
    }

    private CompileRequest authoritativeRequest(
            CompileRequest source,
            ProductProcessWorkflowDTO current) {
        ExistingDraft existing = current == null ? null
                : new ExistingDraft(deepCopy(current), current.getLockVersion(), current.getRevisionHash());
        return new CompileRequest(source.factoryId(), source.sourceDigest(), source.catalogVersion(),
                source.catalogDigest(), source.unitCatalogDigest(), source.productLookupKey(),
                source.route(), source.catalog(), source.bomInputs(), source.wipBindings(), existing);
    }

    private String resolveTargetProduct(CompileRequest request) {
        if (request == null || request.catalog() == null) return "";
        return request.catalog().materials().stream()
                .filter(item -> item.id().equals(request.productLookupKey())
                        || item.name().equals(request.productLookupKey()))
                .filter(item -> item.factoryId().equals(request.factoryId()))
                .map(item -> item.id())
                .findFirst().orElse("");
    }

    private String operationDigest(
            WorkflowDraftPreview preview,
            DraftMode mode,
            Long expectedLockVersion,
            String expectedRevisionHash,
            List<BomPinTarget> targets) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", FactoryWorkflowDraftMaterializationModels.SCHEMA_VERSION);
        payload.put("previewDigest", preview.businessDigest());
        payload.put("mode", mode.name());
        payload.put("expectedLockVersion", expectedLockVersion);
        payload.put("expectedRevisionHash", expectedRevisionHash);
        payload.put("bomTargets", targets);
        return hash(payload);
    }

    private String requestDigest(DraftMaterializationRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", FactoryWorkflowDraftMaterializationModels.SCHEMA_VERSION);
        payload.put("idempotencyKey", request.idempotencyKey());
        payload.put("confirmedPreviewDigest", request.confirmedPreviewDigest());
        payload.put("confirmedOperationDigest", request.confirmedOperationDigest());
        payload.put("mode", request.mode().name());
        payload.put("expectedLockVersion", request.expectedLockVersion());
        payload.put("expectedRevisionHash", request.expectedRevisionHash());
        payload.put("operatorId", request.operatorId());
        payload.put("actorType", request.actorType() == null ? null : request.actorType().name());
        payload.put("compileRequest", request.compileRequest());
        payload.put("bomTargets", request.bomTargets().stream()
                .sorted(Comparator.comparing(BomPinTarget::recipeId)).toList());
        return hash(payload);
    }

    private DraftMaterializationReceipt replay(
            FactoryWorkflowDraftMaterializationLedger ledger,
            String requestDigest) {
        if (!Objects.equals(ledger.getRequestDigest(), requestDigest)) {
            throw invalid(409, "该幂等键已绑定到另一份草稿操作载荷",
                    "WORKFLOW_DRAFT_IDEMPOTENCY_KEY_CONFLICT",
                    "复用原始请求，或为新的已确认操作生成新幂等键", "idempotencyKey");
        }
        if (!"COMPLETED".equals(ledger.getCompletionState())
                || ledger.getResultJson() == null || ledger.getResultJson().isBlank()) {
            throw invalid(409, "该草稿操作尚无可证明完成的持久化回执",
                    "WORKFLOW_DRAFT_IDEMPOTENCY_INCOMPLETE",
                    "等待原事务结束后使用同一幂等键重试", "idempotencyKey");
        }
        DraftMaterializationReceipt original = readJson(
                ledger.getResultJson(), DraftMaterializationReceipt.class);
        return new DraftMaterializationReceipt(
                null, original.receiptId(), original.status(), original.requestDigest(),
                original.previewDigest(), original.operationDigest(), original.mode(),
                original.workflow(), original.bomDrafts(), original.bomReadiness(),
                original.editorDeepLink(), true, 0, original.originalBusinessWriteCount(),
                null, null, null, original.recovery());
    }

    private void requireEnvelope(DraftMaterializationRequest request) {
        if (request == null || request.compileRequest() == null
                || request.compileRequest().factoryId().isBlank()) {
            throw invalid(400, "草稿实体化请求与 factoryId 不能为空",
                    "WORKFLOW_DRAFT_MATERIALIZATION_REQUEST_REQUIRED",
                    "从已锁定的 Blueprint 编译输入生成请求", "request");
        }
        normalizeIdempotencyKey(request.idempotencyKey());
        if (request.operatorId() == null || request.operatorId() <= 0) {
            throw invalid(400, "草稿实体化必须记录有效操作人",
                    "WORKFLOW_DRAFT_OPERATOR_REQUIRED",
                    "使用已认证模块负责人身份重新发起", "operatorId");
        }
    }

    private String normalizeIdempotencyKey(String value) {
        String key = Objects.requireNonNullElse(value, "").trim();
        if (key.isBlank() || key.length() > 128) {
            throw invalid(400, "草稿实体化幂等键不能为空且不能超过 128 个字符",
                    "WORKFLOW_DRAFT_IDEMPOTENCY_KEY_INVALID",
                    "为本次确认生成新的稳定幂等键", "idempotencyKey");
        }
        return key;
    }

    private ProductProcessWorkflowDTO deepCopy(ProductProcessWorkflowDTO source) {
        return objectMapper.convertValue(source, ProductProcessWorkflowDTO.class);
    }

    private void requireDraftReadback(
            ProductProcessWorkflowDTO saved,
            ProductProcessWorkflowDTO desired) {
        if (saved == null || !"DRAFT".equals(saved.getStatus())
                || saved.getRevisionId() == null || saved.getRevisionHash() == null
                || saved.getRevisionHash().isBlank()) {
            throw invalid(409, "保存结果不能证明是带不可变修订的 Workflow DRAFT",
                    "WORKFLOW_DRAFT_READBACK_INVALID",
                    "刷新 Workflow 修订后重新执行", "workflow");
        }
        Map<String, Object> desiredGraph = graphIdentity(desired);
        Map<String, Object> savedGraph = graphIdentity(saved);
        if (!hash(desiredGraph).equals(hash(savedGraph))) {
            throw invalid(409, "Workflow DRAFT 回读图与已确认 ChangeSet 不一致",
                    "WORKFLOW_DRAFT_READBACK_GRAPH_MISMATCH",
                    "保留事务回滚并重新编译", "workflow");
        }
    }

    private Map<String, Object> graphIdentity(ProductProcessWorkflowDTO definition) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("factoryId", definition.getFactoryId());
        value.put("productTypeId", definition.getProductTypeId());
        value.put("schemaVersion", definition.getSchemaVersion());
        value.put("nodes", definition.getNodes());
        value.put("edges", definition.getEdges());
        value.put("viewport", definition.getViewport());
        return value;
    }

    private void requireExactBomReadback(BomRecipe recipe, ProductProcessWorkflowDTO workflow) {
        if (recipe.getStatus() != BomRecipe.Status.DRAFT
                || !Objects.equals(recipe.getWorkflowId(), workflow.getId())
                || !Objects.equals(recipe.getWorkflowRevisionId(), workflow.getRevisionId())
                || !Objects.equals(recipe.getWorkflowRevisionHash(), workflow.getRevisionHash())
                || !Objects.equals(recipe.getWorkflowDefinitionVersion(), workflow.getVersion())) {
            throw invalid(409, "BOM DRAFT 未精确固定到保存的 Workflow 修订",
                    "WORKFLOW_DRAFT_BOM_READBACK_MISMATCH",
                    "保留事务回滚并刷新 BOM/Workflow", recipe.getId());
        }
    }

    private BomDraftReadback toBomReadback(BomRecipe recipe) {
        return new BomDraftReadback(recipe.getId(), recipe.getProductTypeId(), recipe.getStatus().name(),
                recipe.getWorkflowId(), recipe.getWorkflowRevisionId(), recipe.getWorkflowRevisionHash(),
                recipe.getWorkflowDefinitionVersion());
    }

    private String editorDeepLink(String productTypeId) {
        return "/system/product-processes?productTypeId="
                + URLEncoder.encode(productTypeId, StandardCharsets.UTF_8);
    }

    private String hash(Object value) {
        try {
            byte[] canonical = canonicalMapper.writeValueAsBytes(value);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot canonicalize B24 operation", error);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot serialize B24 receipt", error);
        }
    }

    private <T> T readJson(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException error) {
            throw invalid(409, "持久化的草稿操作回执无法读取",
                    "WORKFLOW_DRAFT_RECEIPT_INVALID",
                    "保留账本并由平台管理员核对回执 JSON", "receipt", error);
        }
    }

    private static BusinessException invalid(
            int status,
            String message,
            String code,
            String recovery,
            String target) {
        return new BusinessException(status, message)
                .withCode(code).withHint(recovery).withHintTarget(target).withSeverity("warning");
    }

    private static BusinessException invalid(
            int status,
            String message,
            String code,
            String recovery,
            String target,
            Throwable cause) {
        return new BusinessException(status, message, cause)
                .withCode(code).withHint(recovery).withHintTarget(target).withSeverity("warning");
    }

    private record LockedBom(BomPinTarget target, BomRecipe recipe) { }

    private record Prepared(
            WorkflowDraftPreview preview,
            DraftOperationPreview operationPreview,
            ProductProcessWorkflowDTO currentDefinition,
            List<LockedBom> lockedBoms) { }
}
