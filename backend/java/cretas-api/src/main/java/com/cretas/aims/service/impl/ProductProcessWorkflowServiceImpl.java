package com.cretas.aims.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.ProductProcessWorkflowVersionSummaryDTO;
import com.cretas.aims.dto.workflow.ProductProcessWorkflowActivationDTO;
import com.cretas.aims.dto.workflow.WorkflowBomSyncPreflightResponse;
import com.cretas.aims.dto.workflow.WorkflowPublishAndActivateResponse;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowActivation;
import com.cretas.aims.entity.ProductProcessWorkflowRevision;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.ProductCategory;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowActivationRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRevisionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.ProductProcessWorkflowService;
import com.cretas.aims.service.ProductionPlanService;
import com.cretas.aims.service.bom.BomWorkflowRevisionService;
import com.cretas.aims.service.bom.WorkflowBomSynchronizationService;
import com.cretas.aims.service.validation.ProductProcessWorkflowCatalogValidator;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.cretas.aims.service.validation.ProductProcessWorkflowUnitValidator;
import com.cretas.aims.service.workflow.WorkflowRevisionSnapshotService;
import com.cretas.aims.service.workflow.WorkflowActualIoSemantics;
import com.cretas.aims.service.workflow.ProductProcessWorkflowActivationService;
import com.cretas.aims.service.material.MaterialReferenceWriteGuard;
import com.cretas.aims.blueprint.activation.AssemblyActivationActorResolver;
import com.cretas.aims.dto.workflow.WorkflowPlanRebindSummaryDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ProductProcessWorkflowServiceImpl implements ProductProcessWorkflowService {

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AssemblyActivationActorResolver actorResolver;

    private final ProductProcessWorkflowRepository repository;
    private final ProductProcessWorkflowRevisionRepository revisionRepository;
    private final WorkflowRevisionSnapshotService revisionSnapshotService;
    private final BomWorkflowRevisionService bomWorkflowRevisionService;
    private final WorkflowBomSynchronizationService workflowBomSynchronizationService;
    private final ProductProcessWorkflowActivationService workflowActivationService;
    private final ProductProcessWorkflowActivationRepository activationRepository;
    private final ProductionPlanService productionPlanService;
    private final ObjectMapper objectMapper;
    private final ProductProcessWorkflowValidator validator;
    private final ProductProcessWorkflowCatalogValidator catalogValidator;
    private final ProductProcessWorkflowUnitValidator unitValidator;
    private final ProductTypeRepository productTypeRepository;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;
    private final MaterialReferenceWriteGuard materialReferenceWriteGuard;

    @org.springframework.beans.factory.annotation.Autowired
    public ProductProcessWorkflowServiceImpl(
            ProductProcessWorkflowRepository repository,
            ProductProcessWorkflowRevisionRepository revisionRepository,
            WorkflowRevisionSnapshotService revisionSnapshotService,
            BomWorkflowRevisionService bomWorkflowRevisionService,
            WorkflowBomSynchronizationService workflowBomSynchronizationService,
            ProductProcessWorkflowActivationService workflowActivationService,
            ProductProcessWorkflowActivationRepository activationRepository,
            ProductionPlanService productionPlanService,
            ObjectMapper objectMapper,
            ProductProcessWorkflowValidator validator,
            ProductProcessWorkflowCatalogValidator catalogValidator,
            ProductProcessWorkflowUnitValidator unitValidator,
            ProductTypeRepository productTypeRepository,
            RawMaterialTypeRepository rawMaterialTypeRepository,
            MaterialReferenceWriteGuard materialReferenceWriteGuard) {
        this.repository = repository;
        this.revisionRepository = revisionRepository;
        this.revisionSnapshotService = revisionSnapshotService;
        this.bomWorkflowRevisionService = bomWorkflowRevisionService;
        this.workflowBomSynchronizationService = workflowBomSynchronizationService;
        this.workflowActivationService = workflowActivationService;
        this.activationRepository = activationRepository;
        this.productionPlanService = productionPlanService;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.catalogValidator = catalogValidator;
        this.unitValidator = unitValidator;
        this.productTypeRepository = productTypeRepository;
        this.rawMaterialTypeRepository = rawMaterialTypeRepository;
        this.materialReferenceWriteGuard = materialReferenceWriteGuard;
    }

    /** Compatibility constructor for narrow legacy unit tests without Spring wiring. */
    public ProductProcessWorkflowServiceImpl(
            ProductProcessWorkflowRepository repository,
            ProductProcessWorkflowRevisionRepository revisionRepository,
            WorkflowRevisionSnapshotService revisionSnapshotService,
            BomWorkflowRevisionService bomWorkflowRevisionService,
            WorkflowBomSynchronizationService workflowBomSynchronizationService,
            ProductProcessWorkflowActivationService workflowActivationService,
            ProductProcessWorkflowActivationRepository activationRepository,
            ProductionPlanService productionPlanService,
            ObjectMapper objectMapper,
            ProductProcessWorkflowValidator validator,
            ProductProcessWorkflowCatalogValidator catalogValidator,
            ProductProcessWorkflowUnitValidator unitValidator,
            ProductTypeRepository productTypeRepository,
            RawMaterialTypeRepository rawMaterialTypeRepository) {
        this(repository, revisionRepository, revisionSnapshotService, bomWorkflowRevisionService,
                workflowBomSynchronizationService, workflowActivationService, activationRepository,
                productionPlanService, objectMapper, validator, catalogValidator, unitValidator,
                productTypeRepository, rawMaterialTypeRepository,
                new MaterialReferenceWriteGuard(rawMaterialTypeRepository));
    }

    /**
     * 工艺保密 (写入侧): 路由闸只看所属产品和已存的图; 一张新草稿把受保护产品画进来 (多产出锚点的图),
     * 要在这里按请求里的图判 (独立审查 V2.1 复核)。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard processSecretGuard;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.workflow.WorkflowCcpMaterializer ccpMaterializer;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.workflow.WorkflowProcessParamMaterializer processParamMaterializer;

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductProcessWorkflowDTO> getEditorDefinition(
            String factoryId,
            String productTypeId) {
        Optional<ProductProcessWorkflow> draft = find(factoryId, productTypeId, ProductProcessWorkflow.Status.DRAFT);
        if (draft.isPresent()) {
            return draft.map(this::toDTOWithUnitWarnings);
        }
        return find(factoryId, productTypeId, ProductProcessWorkflow.Status.PUBLISHED).map(this::toDTOWithUnitWarnings);
    }

    @Override
    @Transactional
    public ProductProcessWorkflowDTO saveDraft(
            String factoryId,
            String productTypeId,
            ProductProcessWorkflowDTO definition) {
        requireWorkflowOwner(factoryId, productTypeId);
        if (processSecretGuard != null) {
            processSecretGuard.requireProtectedAmong(factoryId,
                    com.cretas.aims.service.processsecret.ProcessSecretWorkflowOutputs.productsInGraph(definition),
                    com.cretas.aims.entity.processsecret.ProcessSecretAction.EDIT,
                    "workflow draft save " + productTypeId);
        }
        lockWorkflowOwner(factoryId, productTypeId);
        WorkflowActualIoSemantics.normalizeDraft(definition);
        validator.validateForDraft(definition);
        var unitValidation = unitValidator.validate(factoryId, definition);
        definition.setUnitWarnings(java.util.stream.Stream.concat(
                        unitValidation.errors().stream(), unitValidation.warnings().stream())
                .toList());
        Optional<ProductProcessWorkflow> existingDraft = find(
                factoryId, productTypeId, ProductProcessWorkflow.Status.DRAFT);
        if (existingDraft.isPresent()) {
            ProductProcessWorkflow lockedDraft = repository
                    .lockByIdAndFactoryId(existingDraft.get().getId(), factoryId)
                    .filter(row -> row.getStatus() == ProductProcessWorkflow.Status.DRAFT)
                    .orElseThrow(() -> new BusinessException(409, "Workflow 草稿状态已变化，请刷新后重试")
                            .withCode("PRODUCT_PROCESS_WORKFLOW_CONFLICT")
                            .withHint("请刷新当前工厂的工艺草稿后重新操作")
                            .withSeverity("warning"));
            existingDraft = Optional.of(lockedDraft);
        }
        // Owner/workflow lock above is followed by one deterministic raw lock.
        lockActiveMaterials(factoryId, rawMaterialIds(factoryId, productTypeId, definition));

        ProductProcessWorkflow entity;
        boolean autoForked = false;
        Long forkedFromWorkflowId = null;
        Integer forkedFromVersion = null;
        if (existingDraft.isPresent()) {
            ProductProcessWorkflow currentDraft = existingDraft.get();
            assertCurrentIdentity(definition, currentDraft);
            if (hasStartedProductionReference(factoryId, currentDraft)) {
                autoForked = true;
                forkedFromWorkflowId = currentDraft.getId();
                forkedFromVersion = currentDraft.getDefinitionVersion();
                entity = forkReferencedDraft(factoryId, currentDraft);
            } else {
                entity = currentDraft;
            }
        } else {
            Optional<ProductProcessWorkflow> published = find(
                    factoryId, productTypeId, ProductProcessWorkflow.Status.PUBLISHED);
            if (published.isPresent()) {
                ProductProcessWorkflow currentPublished = published.get();
                if (sameBusinessGraph(currentPublished, definition)) {
                    ProductProcessWorkflowDTO unchanged = toDTO(currentPublished);
                    unchanged.setUnitWarnings(definition.getUnitWarnings());
                    return unchanged;
                }
                // A delayed identical autosave is harmless, but a different graph must
                // originate from the exact currently published identity. Otherwise a
                // stale browser could recreate its obsolete graph as the next draft.
                assertCurrentIdentity(definition, currentPublished);
            }
            entity = new ProductProcessWorkflow();
            entity.setFactoryId(factoryId);
            entity.setProductTypeId(productTypeId);
            entity.setStatus(ProductProcessWorkflow.Status.DRAFT);
            // 历史行也占用版本号；从全部状态取最大值，避免草稿被异常重建时
            // 与保留的生产历史复用同一个 definitionVersion。
            int nextVersion = repository.findMaxDefinitionVersion(factoryId, productTypeId).orElse(0) + 1;
            entity.setDefinitionVersion(nextVersion);
        }

        entity.setSchemaVersion(definition.getSchemaVersion());
        stampProcessParamConfirmations(factoryId, productTypeId, definition);
        entity.setNodesJson(writeJson(definition.getNodes()));
        entity.setEdgesJson(writeJson(definition.getEdges()));
        entity.setViewportJson(writeJson(definition.getViewport()));
        entity = repository.saveAndFlush(entity);
        ProductProcessWorkflowRevision revision = revisionSnapshotService.captureDraft(entity);
        entity.setCurrentRevisionId(revision.getId());
        entity.setCurrentRevisionHash(revision.getRevisionHash());
        ProductProcessWorkflowDTO saved = toDTO(repository.saveAndFlush(entity));
        saved.setUnitWarnings(definition.getUnitWarnings());
        saved.setAutoForked(autoForked);
        saved.setForkedFromWorkflowId(forkedFromWorkflowId);
        saved.setForkedFromVersion(forkedFromVersion);
        return saved;
    }

    /**
     * 工艺参数「确认覆盖」只在一次发布内有效: 保存草稿时, 服务端给每个带勾却还没有 confirmedBase 的参数盖上
     * 「当前已发布版本号」。发布时 materializer 要求它等于上一版已发布版本, 所以从历史版本恢复回来的旧勾
     * (章是当年的版本号)、别人在中间又发布过一版之后的旧勾, 都对不上, 必须重新勾。
     * 不在发布时抹掉勾: revision 快照与 revisionHash(BOM 配方按它钉版本)必须和已保存草稿逐字一致。
     * 勾被取消/没勾时清掉 confirmedBase, 重新勾选就会重新盖章。
     */
    private void stampProcessParamConfirmations(
            String factoryId, String productTypeId, ProductProcessWorkflowDTO definition) {
        if (definition.getNodes() == null) return;
        int base = find(factoryId, productTypeId, ProductProcessWorkflow.Status.PUBLISHED)
                .map(ProductProcessWorkflow::getDefinitionVersion).orElse(0);
        for (ProductProcessWorkflowDTO.Node node : definition.getNodes()) {
            if (node == null || node.getData() == null
                    || !(node.getData().get("processParams") instanceof List<?> params)) {
                continue;
            }
            List<Object> stamped = new java.util.ArrayList<>();
            for (Object raw : params) {
                if (raw instanceof Map<?, ?> m) {
                    Map<Object, Object> copy = new java.util.LinkedHashMap<>(m);
                    if (Boolean.TRUE.equals(copy.get("overwriteConfirmed"))) {
                        copy.putIfAbsent("confirmedBase", base);
                    } else {
                        copy.remove("confirmedBase");
                    }
                    stamped.add(copy);
                } else {
                    stamped.add(raw);
                }
            }
            Map<String, Object> data = new java.util.LinkedHashMap<>(node.getData());
            data.put("processParams", stamped);
            node.setData(data);
        }
    }

    /**
     * A workflow row is part of the identity copied into plans and batches. Only a plan that
     * has actually started reporting makes that identity historical. Freeze that row as a
     * SNAPSHOT and continue the user's current-configuration edit in a fresh DRAFT row.
     */
    private boolean hasStartedProductionReference(String factoryId, ProductProcessWorkflow workflow) {
        if (workflow.getId() == null) return false;
        return productionPlanService.listWorkflowPlanBindings(factoryId, List.of(workflow.getId()))
                .getOrDefault(workflow.getId(), List.of()).stream()
                .anyMatch(binding -> binding.isProductionStarted());
    }

    private ProductProcessWorkflow forkReferencedDraft(
            String factoryId, ProductProcessWorkflow currentDraft) {
        ProductProcessWorkflowRevision frozenRevision = revisionSnapshotService.capture(currentDraft);
        currentDraft.setCurrentRevisionId(frozenRevision.getId());
        currentDraft.setCurrentRevisionHash(frozenRevision.getRevisionHash());
        currentDraft.setStatus(ProductProcessWorkflow.Status.SNAPSHOT);
        repository.saveAndFlush(currentDraft);

        ProductProcessWorkflow nextDraft = new ProductProcessWorkflow();
        nextDraft.setFactoryId(factoryId);
        nextDraft.setProductTypeId(currentDraft.getProductTypeId());
        nextDraft.setStatus(ProductProcessWorkflow.Status.DRAFT);
        nextDraft.setDefinitionVersion(
                repository.findMaxDefinitionVersion(factoryId, currentDraft.getProductTypeId()).orElse(0) + 1);
        return nextDraft;
    }

    @Override
    @Transactional
    public ProductProcessWorkflowDTO publish(
            String factoryId,
            String productTypeId,
            Long lockVersion,
        Long operatorId) {
        requireWorkflowOwner(factoryId, productTypeId);
        lockWorkflowOwner(factoryId, productTypeId);
        repository.lockByFactoryId(factoryId);
        List<Long> previousWorkflowIds = lineageWorkflowIds(factoryId, productTypeId);
        ProductProcessWorkflowDTO published =
                publishInternal(factoryId, productTypeId, lockVersion, operatorId, true, null);
        workflowActivationService.activate(factoryId, published.getId(), operatorId);
        // A published revision's owner participates in its content hash and historic pins.
        // Topology recommendations must never rewrite that persisted identity.
        WorkflowPlanRebindSummaryDTO planRebind = rebindUnstartedPlans(
                factoryId, previousWorkflowIds, published.getId(), operatorId);
        logPlanRebind(factoryId, published, planRebind);
        return published;
    }

    @Override
    @Transactional
    public WorkflowBomSyncPreflightResponse bomSyncPreflight(
            String factoryId,
            String productTypeId) {
        Optional<ProductProcessWorkflow> workflow =
                find(factoryId, productTypeId, ProductProcessWorkflow.Status.DRAFT);
        if (workflow.isEmpty()) {
            workflow = find(factoryId, productTypeId, ProductProcessWorkflow.Status.PUBLISHED);
        }
        ProductProcessWorkflowRevision target = workflow
                .map(row -> {
                    ProductProcessWorkflowRevision revision = readCurrentRevision(row);
                    return row.getStatus() == ProductProcessWorkflow.Status.DRAFT
                            ? bomWorkflowRevisionService.repairCurrentDraftRevisionIfNeeded(
                                    factoryId, revision)
                            : revision;
                })
                .orElse(null);
        return workflowBomSynchronizationService.preflight(
                factoryId, productTypeId, target);
    }

    @Override
    @Transactional
    public WorkflowPublishAndActivateResponse publishAndActivateAsActor(
            String factoryId, String productTypeId, Long lockVersion, String idempotencyKey,
            Long revisionId, String revisionHash, Integer definitionVersion,
            com.cretas.aims.security.ActorRef actor) {
        java.util.Objects.requireNonNull(actor, "Actor identity is required");
        if (actorResolver == null) {
            throw new BusinessException(403, "平台建厂工艺发布服务未启用").withCode("ASSEMBLY_ACTIVATION_FORBIDDEN");
        }
        actorResolver.authorize(actor, factoryId, java.util.Set.of("factory-blueprint:activation:workflow"));
        // Keep the legacy user FK null for platform actors. The typed actor is persisted
        // on the activation row immediately after the existing transactional workflow path.
        WorkflowPublishAndActivateResponse result = publishAndActivate(
                factoryId, productTypeId, lockVersion, idempotencyKey,
                revisionId, revisionHash, definitionVersion, actor.factoryUserId());
        activationRepository.findByFactoryIdAndProductTypeId(factoryId, productTypeId)
                .ifPresent(row -> {
                    ProductProcessWorkflowActivationDTO activation = result.getActivation();
                    boolean exactActivation = activation != null
                            && java.util.Objects.equals(row.getId(), activation.getId())
                            && java.util.Objects.equals(row.getActiveWorkflowId(), activation.getActiveWorkflowId())
                            && java.util.Objects.equals(row.getActiveDefinitionVersion(), activation.getActiveDefinitionVersion());
                    if (!result.isReplayed() && exactActivation
                            && row.getActorType() == null && row.getActorId() == null) {
                        row.setActor(actor);
                        activationRepository.save(row);
                    }
                });
        return result;
    }

    @Override
    @Transactional
    public ProductProcessWorkflowDTO snapshot(String factoryId, String productTypeId, Long lockVersion) {
        requireWorkflowOwner(factoryId, productTypeId);
        ProductProcessWorkflow draft = find(factoryId, productTypeId, ProductProcessWorkflow.Status.DRAFT)
                .map(row -> repository.lockByIdAndFactoryId(row.getId(), factoryId)
                        .filter(r -> r.getStatus() == ProductProcessWorkflow.Status.DRAFT)
                        .orElseThrow(() -> new BusinessException(409, "Workflow 草稿状态已变化，请刷新后重试")
                                .withHint("请重新加载当前工厂的工艺草稿")))
                .orElseThrow(() -> new BusinessException(409, "没有可另存的 Workflow 草稿")
                        .withCode("PRODUCT_PROCESS_WORKFLOW_DRAFT_MISSING")
                        .withHint("请先保存当前工厂的工艺草稿，再另存为新版本"));
        assertCurrentVersion(lockVersion, draft);
        ProductProcessWorkflowDTO definition = toDTO(draft);
        validator.validateForDraft(definition);
        unitValidator.validate(factoryId, definition);

        ProductProcessWorkflow frozen = new ProductProcessWorkflow();
        frozen.setFactoryId(factoryId);
        frozen.setProductTypeId(productTypeId);
        frozen.setSchemaVersion(draft.getSchemaVersion());
        frozen.setStatus(ProductProcessWorkflow.Status.SNAPSHOT);
        frozen.setDefinitionVersion(draft.getDefinitionVersion());
        frozen.setNodesJson(draft.getNodesJson());
        frozen.setEdgesJson(draft.getEdgesJson());
        frozen.setViewportJson(draft.getViewportJson());
        frozen.setUnitReviewRequired(draft.getUnitReviewRequired());
        frozen = repository.saveAndFlush(frozen);
        ProductProcessWorkflowRevision revision = revisionSnapshotService.capture(frozen);
        frozen.setCurrentRevisionId(revision.getId());
        frozen.setCurrentRevisionHash(revision.getRevisionHash());
        repository.saveAndFlush(frozen);

        draft.setDefinitionVersion(draft.getDefinitionVersion() + 1);
        draft.setCurrentRevisionId(null);
        draft.setCurrentRevisionHash(null);
        return toDTO(repository.saveAndFlush(draft));
    }

    @Override
    @Transactional
    public WorkflowPublishAndActivateResponse publishAndActivate(
            String factoryId,
            String productTypeId,
            Long lockVersion,
            String idempotencyKey,
            Long revisionId,
            String revisionHash,
            Integer definitionVersion,
            Long operatorId) {
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        requireWorkflowOwner(factoryId, productTypeId);
        lockWorkflowOwner(factoryId, productTypeId);
        // The key is factory-scoped. Reuse the existing factory serialization point
        // so two concurrent products cannot both claim the same key before the
        // partial unique index becomes visible to the second transaction.
        repository.lockByFactoryId(factoryId);

        Optional<ProductProcessWorkflow> keyedPublish =
                repository.findByFactoryIdAndLastPublishIdempotencyKey(
                        factoryId, normalizedKey);
        if (keyedPublish.isPresent()) {
            return replayCompletedPublish(
                    factoryId,
                    productTypeId,
                    normalizedKey,
                    revisionId,
                    revisionHash,
                    definitionVersion,
                    keyedPublish.get());
        }

        Optional<ProductProcessWorkflow> draft =
                find(factoryId, productTypeId, ProductProcessWorkflow.Status.DRAFT);
        if (draft.isEmpty()) {
            ProductProcessWorkflow publishedWorkflow =
                    find(factoryId, productTypeId, ProductProcessWorkflow.Status.PUBLISHED)
                            .orElseThrow(() -> new BusinessException(
                                            409, "没有可发布或重放的 Workflow")
                                    .withCode("PRODUCT_PROCESS_WORKFLOW_DRAFT_MISSING")
                                    .withHint("请先保存 Workflow 草稿")
                                    .withSeverity("warning"));
            ProductProcessWorkflowRevision publishedRevision =
                    readCurrentRevision(publishedWorkflow);
            requireRequestedRevisionIdentity(
                    publishedRevision, revisionId, revisionHash, definitionVersion);
            throw new BusinessException(409, "该 Workflow 修订已由另一幂等键完成发布")
                    .withCode("WORKFLOW_PUBLISH_IDEMPOTENCY_KEY_MISMATCH")
                    .withHint(publishedWorkflow.getLastPublishIdempotencyKey() == null
                            ? "该历史发布没有可重放的请求键，请保存新草稿后再发布"
                            : "请复用首次发布返回的幂等键")
                    .withHintTarget("idempotencyKey")
                    .withSeverity("warning");
        }

        ProductProcessWorkflowRevision targetRevision =
                readCurrentRevision(draft.get());
        requireRequestedRevisionIdentity(
                targetRevision, revisionId, revisionHash, definitionVersion);
        WorkflowBomSyncPreflightResponse preflight =
                workflowBomSynchronizationService.preflight(
                        factoryId, productTypeId, targetRevision);
        if (!preflight.isCanCompleteAutomatically()) {
            WorkflowBomSyncPreflightResponse.SyncIssue firstIssue =
                    !preflight.getConflicts().isEmpty()
                            ? preflight.getConflicts().getFirst()
                            : preflight.getMissingItems().isEmpty()
                            ? null
                            : preflight.getMissingItems().getFirst();
            throw new BusinessException(409,
                    firstIssue == null ? "BOM 与 Workflow 当前状态不能自动同步"
                            : firstIssue.getMessage())
                    .withCode("WORKFLOW_BOM_SYNC_" + preflight.getClassification().name())
                    .withHint(firstIssue == null ? "请刷新 BOM 与 Workflow 后重试"
                            : firstIssue.getAction())
                    .withHintTarget(firstIssue == null ? "bom" : firstIssue.getField())
                    .withSeverity("warning");
        }

        List<Long> previousWorkflowIds = lineageWorkflowIds(factoryId, productTypeId);
        ProductProcessWorkflowDTO published =
                publishInternal(
                        factoryId, productTypeId, lockVersion, operatorId, true, normalizedKey);
        ProductProcessWorkflowActivationDTO activation =
                workflowActivationService.activate(factoryId, published.getId(), operatorId);
        // A published revision's owner participates in its content hash and historic pins.
        // Topology recommendations must never rewrite that persisted identity.
        WorkflowPlanRebindSummaryDTO planRebind = rebindUnstartedPlans(
                factoryId, previousWorkflowIds, published.getId(), operatorId);
        return WorkflowPublishAndActivateResponse.builder()
                .workflow(published)
                .activation(activation)
                .bomSync(preflight)
                .planRebind(planRebind)
                .idempotencyKey(normalizedKey)
                .replayed(false)
                .build();
    }

    private WorkflowPublishAndActivateResponse replayCompletedPublish(
            String factoryId,
            String productTypeId,
            String idempotencyKey,
            Long revisionId,
            String revisionHash,
            Integer definitionVersion,
            ProductProcessWorkflow publishedWorkflow) {
        if (!Objects.equals(productTypeId, publishedWorkflow.getProductTypeId())
                || publishedWorkflow.getStatus() != ProductProcessWorkflow.Status.PUBLISHED
                || !Objects.equals(revisionId, publishedWorkflow.getLastPublishRevisionId())
                || !Objects.equals(revisionHash, publishedWorkflow.getLastPublishRevisionHash())
                || !Objects.equals(
                        definitionVersion,
                        publishedWorkflow.getLastPublishDefinitionVersion())) {
            throw new BusinessException(409, "该幂等键已绑定到另一条 Workflow 发布请求")
                    .withCode("WORKFLOW_PUBLISH_IDEMPOTENCY_KEY_CONFLICT")
                    .withHint("请勿复用其他产品或修订的发布幂等键")
                    .withHintTarget("idempotencyKey")
                    .withSeverity("warning");
        }
        ProductProcessWorkflowRevision targetRevision =
                readCurrentRevision(publishedWorkflow);
        requireRequestedRevisionIdentity(
                targetRevision, revisionId, revisionHash, definitionVersion);
        WorkflowBomSyncPreflightResponse preflight =
                workflowBomSynchronizationService.preflight(
                        factoryId, productTypeId, targetRevision);
        ProductProcessWorkflowActivationDTO activation =
                workflowActivationService.get(factoryId, productTypeId);
        if (preflight.getClassification()
                        != WorkflowBomSyncPreflightResponse.Classification.READY
                || activation == null
                || !Boolean.TRUE.equals(activation.getEnabled())
                || !Objects.equals(
                        publishedWorkflow.getId(), activation.getActiveWorkflowId())
                || !Objects.equals(
                        publishedWorkflow.getDefinitionVersion(),
                        activation.getActiveDefinitionVersion())) {
            throw replayConflict();
        }
        return WorkflowPublishAndActivateResponse.builder()
                .workflow(toDTOWithUnitWarnings(publishedWorkflow))
                .activation(activation)
                .bomSync(preflight)
                .planRebind(WorkflowPlanRebindSummaryDTO.empty())
                .idempotencyKey(idempotencyKey)
                .replayed(true)
                .build();
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(400, "自动同步并发布必须提供幂等键")
                    .withCode("WORKFLOW_PUBLISH_IDEMPOTENCY_KEY_REQUIRED")
                    .withHint("请刷新页面后重试")
                    .withHintTarget("idempotencyKey")
                    .withSeverity("warning");
        }
        String normalized = idempotencyKey.trim();
        if (normalized.length() > 128) {
            throw new BusinessException(400, "发布幂等键长度不能超过 128 个字符")
                    .withCode("WORKFLOW_PUBLISH_IDEMPOTENCY_KEY_INVALID")
                    .withHint("请刷新页面生成新的发布请求")
                    .withHintTarget("idempotencyKey")
                    .withSeverity("warning");
        }
        return normalized;
    }

    private BusinessException replayConflict() {
        return new BusinessException(409,
                "当前状态不能证明这是同一次 Workflow 发布请求")
                .withCode("WORKFLOW_PUBLISH_REPLAY_CONFLICT")
                .withHint("请刷新页面并基于当前草稿重新发起发布")
                .withHintTarget("workflow")
                .withSeverity("warning");
    }

    private void requireRequestedRevisionIdentity(
            ProductProcessWorkflowRevision actual,
            Long revisionId,
            String revisionHash,
            Integer definitionVersion) {
        if (revisionId == null
                || revisionHash == null
                || revisionHash.isBlank()
                || definitionVersion == null
                || !Objects.equals(actual.getId(), revisionId)
                || !Objects.equals(actual.getRevisionHash(), revisionHash)
                || !Objects.equals(actual.getDefinitionVersion(), definitionVersion)) {
            throw new BusinessException(409, "Workflow 保存修订已变化，不能重放旧发布请求")
                    .withCode("WORKFLOW_PUBLISH_REVISION_IDENTITY_CONFLICT")
                    .withHint("请刷新页面并使用当前保存修订重新发布")
                    .withHintTarget("workflow")
                    .withSeverity("warning");
        }
    }

    private ProductProcessWorkflowDTO publishInternal(
            String factoryId,
            String productTypeId,
            Long lockVersion,
            Long operatorId,
            boolean synchronizeBom,
            String requestedIdempotencyKey) {
        ProductProcessWorkflow candidate = find(factoryId, productTypeId, ProductProcessWorkflow.Status.DRAFT)
                .orElseThrow(() -> new BusinessException(409, "没有可发布的 Workflow 草稿")
                        .withCode("PRODUCT_PROCESS_WORKFLOW_DRAFT_MISSING")
                        .withHint("请先保存草稿，再发布版本")
                        .withSeverity("warning"));
        ProductProcessWorkflow draft = repository.lockByIdAndFactoryId(candidate.getId(), factoryId)
                .filter(row -> row.getStatus() == ProductProcessWorkflow.Status.DRAFT)
                .orElseThrow(() -> new BusinessException(409, "Workflow 草稿状态已变化，请刷新后重试")
                        .withCode("PRODUCT_PROCESS_WORKFLOW_CONFLICT")
                        .withSeverity("warning"));
        assertCurrentVersion(lockVersion, draft);
        ProductProcessWorkflowDTO definition = toDTO(draft);
        // publishInternal already owns the workflow row; raw references are the
        // second and final lock class in the stable workflow -> raw order.
        lockActiveMaterials(factoryId, rawMaterialIds(factoryId, productTypeId, definition));
        validator.validateForPublish(definition);
        // 🔴 2026-08-08: 这里【必须】按 synchronizeBom 分叉, 否则 3-3 的 BOM 投影是空转的。
        //
        // catalogValidator.validateForPublish 内含 validateFinishedOutputBoms ——
        // 要求每个成品 Cell 都已有 ACTIVE BOM。而 BOM 同步/投影发生在下面几行之后。
        // 闸在投影前面 ⇒ 投影永远跑不到 ⇒ 「没有生效 BOM 也能发布」根本不成立。
        // (2026-08-08 真机实测发现: 3-3 部署后仍被这道闸拦下, 报的是另一个错误码,
        //  所以第一眼看不出是同一条规则的第三个承载点。)
        //
        // synchronizeBom=true 时先只做结构/目录校验, BOM 那半交给同步; 同步完成后
        // 再跑一次完整校验兜底 —— 见下方。
        if (synchronizeBom) {
            catalogValidator.validateForBomConfiguration(factoryId, productTypeId, definition);
        } else {
            catalogValidator.validateForPublish(factoryId, productTypeId, definition);
        }
        unitValidator.validateForPublish(factoryId, definition);
        ProductProcessWorkflowRevision revision = requireCurrentRevision(draft);
        if (!Boolean.TRUE.equals(revision.getStructurallyComplete())) {
            throw new BusinessException(409, "当前 Workflow 保存修订结构不完整")
                    .withCode("PRODUCT_PROCESS_WORKFLOW_REVISION_INCOMPLETE")
                    .withHint(revision.getValidationMessage());
        }
        if (synchronizeBom) {
            workflowBomSynchronizationService.synchronizeForPublish(
                    factoryId, productTypeId, revision, operatorId);
            // ⛔ 兜底, 不能省: 同步/投影之后 BOM 必须真的到位。少了这一句, 投影失败
            //    会被静默放过 —— 发布出一个没有 BOM 的 Workflow, 报工时无料可扣。
            catalogValidator.validateForPublish(factoryId, productTypeId, definition);
        }
        bomWorkflowRevisionService.requireActiveBomPinsRevision(factoryId, productTypeId, revision);
        // 画布工序上的工艺标准参数(AI 听来的油温范围等) → 工序 customFieldSchema; 只在发布(=人已确认)时写。
        // 放在 CCP 台账之前: 它可能因「要覆盖已有标准但未确认」409, 先判先抛, 不留下任何半截写入
        // (整个方法在事务里, 抛异常会连同 CCP 台账与草稿状态一起回滚)。
        if (processParamMaterializer != null) {
            processParamMaterializer.materialize(factoryId, productTypeId, definition,
                    find(factoryId, productTypeId, ProductProcessWorkflow.Status.PUBLISHED)
                            .map(this::toDTO).orElse(null), operatorId);
        }
        // 画布工序上的 CCP 控制点 → 台账(待确认已在 validateForPublish 拦住, 到这里限值一定齐全)
        if (ccpMaterializer != null) {
            ccpMaterializer.materialize(factoryId, productTypeId, definition);
        }
        draft.setStatus(ProductProcessWorkflow.Status.PUBLISHED);
        draft.setUnitReviewRequired(false);
        draft.setLastPublishIdempotencyKey(
                requestedIdempotencyKey == null
                        ? legacyPublishKey(revision)
                        : requestedIdempotencyKey);
        draft.setLastPublishRevisionId(revision.getId());
        draft.setLastPublishRevisionHash(revision.getRevisionHash());
        draft.setLastPublishDefinitionVersion(revision.getDefinitionVersion());
        revision.setStatus(ProductProcessWorkflowRevision.Status.PUBLISHED);
        revisionRepository.save(revision);
        return toDTO(repository.saveAndFlush(draft));
    }

    private String legacyPublishKey(ProductProcessWorkflowRevision revision) {
        return "legacy:" + revision.getId() + ":" + revision.getRevisionHash();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductProcessWorkflowVersionSummaryDTO> listVersions(String factoryId, String productTypeId) {
        requireWorkflowOwner(factoryId, productTypeId);
        Optional<ProductProcessWorkflowActivation> activation = activationRepository
                .findByFactoryIdAndProductTypeId(factoryId, productTypeId);
        List<ProductProcessWorkflowRepository.VersionSummaryProjection> rows = repository
                .findVersionSummaries(factoryId, productTypeId);
        var bindings = productionPlanService.listWorkflowPlanBindings(
                factoryId,
                rows.stream().map(ProductProcessWorkflowRepository.VersionSummaryProjection::getId).toList());
        return rows.stream()
                .map(row -> {
                    boolean active = activation
                            .filter(a -> Boolean.TRUE.equals(a.getEnabled()))
                            .map(a -> row.getId().equals(a.getActiveWorkflowId()))
                            .orElse(false);
                    List<com.cretas.aims.dto.workflow.WorkflowPlanBindingDTO> visibleBindings =
                            active
                                    ? bindings.getOrDefault(row.getId(), List.of())
                                    : bindings.getOrDefault(row.getId(), List.of()).stream()
                                            .filter(com.cretas.aims.dto.workflow.WorkflowPlanBindingDTO::isProductionStarted)
                                            .toList();
                    return toVersionSummaryDTO(row, active, visibleBindings);
                })
                .filter(row -> row.isActive() || !row.getProductionPlans().isEmpty())
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public ProductProcessWorkflowDTO getVersion(String factoryId, String productTypeId, Integer definitionVersion) {
        requireWorkflowOwner(factoryId, productTypeId);
        ProductProcessWorkflow row = repository
                .findFirstByFactoryIdAndProductTypeIdAndDefinitionVersion(factoryId, productTypeId, definitionVersion)
                .orElseThrow(() -> new BusinessException(
                                404, "该产品不存在 Workflow 版本: " + definitionVersion)
                        .withCode("PRODUCT_PROCESS_WORKFLOW_VERSION_NOT_FOUND")
                        .withHint("请从版本历史列表中选择一个存在的版本号")
                        .withSeverity("warning"));
        return toDTO(row);
    }

    private ProductProcessWorkflowVersionSummaryDTO toVersionSummaryDTO(
            ProductProcessWorkflowRepository.VersionSummaryProjection row,
            boolean active,
            List<com.cretas.aims.dto.workflow.WorkflowPlanBindingDTO> productionPlans) {
        return new ProductProcessWorkflowVersionSummaryDTO(
                row.getDefinitionVersion(),
                row.getStatus().name(),
                row.getUpdatedAt(),
                active,
                productionPlans);
    }

    private List<Long> lineageWorkflowIds(String factoryId, String productTypeId) {
        return repository.findByFactoryIdAndProductTypeIdOrderByDefinitionVersionDesc(factoryId, productTypeId)
                .stream()
                .map(ProductProcessWorkflow::getId)
                .filter(Objects::nonNull)
                .toList();
    }

    private WorkflowPlanRebindSummaryDTO rebindUnstartedPlans(
            String factoryId,
            List<Long> lineageWorkflowIds,
            Long activeWorkflowId,
            Long operatorId) {
        List<Long> previousIds = lineageWorkflowIds.stream()
                .filter(id -> !Objects.equals(id, activeWorkflowId))
                .toList();
        return productionPlanService.repinUnstartedPlansToCurrentAuthority(
                factoryId, previousIds, operatorId);
    }

    private void logPlanRebind(
            String factoryId,
            ProductProcessWorkflowDTO published,
            WorkflowPlanRebindSummaryDTO summary) {
        log.info("[Workflow] 新版本应用到生产计划: factoryId={}, workflowId={}, version={}, rebound={}, preservedStarted={}",
                factoryId,
                published.getId(),
                published.getVersion(),
                summary.getReboundPlanCount(),
                summary.getPreservedStartedPlanCount());
    }

    private Optional<ProductProcessWorkflow> find(
            String factoryId,
            String productTypeId,
            ProductProcessWorkflow.Status status) {
        return repository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                factoryId, productTypeId, status);
    }

    private List<String> rawMaterialIds(
            String factoryId, String ownerId, ProductProcessWorkflowDTO definition) {
        java.util.Set<String> ids = new java.util.TreeSet<>();
        if (ProductCategory.RAW_MATERIAL.equals(
                productTypeRepository.findByIdAndFactoryId(ownerId, factoryId)
                        .map(ProductType::getProductCategory).orElse(null))) {
            ids.add(ownerId);
        }
        if (definition == null || definition.getNodes() == null) return List.copyOf(ids);

        java.util.Map<String, ProductProcessWorkflowDTO.Node> nodesById = definition.getNodes().stream()
                .filter(Objects::nonNull)
                .filter(node -> node.getId() != null)
                .collect(java.util.stream.Collectors.toMap(
                        ProductProcessWorkflowDTO.Node::getId, node -> node, (left, right) -> left));
        for (ProductProcessWorkflowDTO.Node node : definition.getNodes()) {
            if (node == null || node.getData() == null) continue;
            java.util.Map<String, Object> data = node.getData();
            if ("RAW_MATERIAL".equals(node.getKind())) {
                addMaterialId(ids, data.get("skuId"));
            }
            addMaterialBindings(ids, data.get("materialBindings"));
            if (data.get("materialTypeId") != null) {
                addMaterialId(ids, data.get("materialTypeId"));
            }
            if (data.get("ports") instanceof List<?> ports) {
                for (Object portValue : ports) {
                    if (!(portValue instanceof java.util.Map<?, ?> port)) continue;
                    Object materialNodeId = port.get("materialNodeId");
                    ProductProcessWorkflowDTO.Node materialNode = nodesById.get(
                            materialNodeId == null ? null : materialNodeId.toString());
                    if (materialNode != null && "RAW_MATERIAL".equals(materialNode.getKind())) {
                        addMaterialId(ids, port.get("skuId"));
                    }
                }
            }
        }
        return List.copyOf(ids);
    }

    private void addMaterialBindings(java.util.Set<String> ids, Object rawBindings) {
        if (!(rawBindings instanceof List<?> bindings)) return;
        for (Object bindingValue : bindings) {
            if (bindingValue instanceof java.util.Map<?, ?> binding) {
                addMaterialId(ids, binding.get("materialTypeId"));
            }
        }
    }

    private void addMaterialId(java.util.Set<String> ids, Object value) {
        if (value != null && !value.toString().isBlank()) ids.add(value.toString());
    }

    private void lockActiveMaterials(String factoryId, List<String> materialIds) {
        materialReferenceWriteGuard.lockActive(factoryId, materialIds);
    }


    private void requireWorkflowOwner(String factoryId, String ownerId) {
        Optional<ProductType> productOwner = productTypeRepository.findByIdAndFactoryId(ownerId, factoryId);
        if (productOwner.isPresent()) {
            // Raw-owner workflows use an inactive ProductType anchor with the
            // same id. Validate the underlying raw row when that anchor exists.
            if (ProductCategory.RAW_MATERIAL.equals(productOwner.get().getProductCategory())) {
                rawMaterialTypeRepository.findByIdAndFactoryId(ownerId, factoryId)
                        .ifPresent(raw -> requireActiveRawOwner(raw));
            }
            return;
        }

        RawMaterialType rawOwner = rawMaterialTypeRepository.findByIdAndFactoryId(ownerId, factoryId)
                .orElseThrow(() -> new BusinessException(400, "Workflow 所属成品或原料不存在于当前工厂")
                        .withCode("PRODUCT_PROCESS_WORKFLOW_OWNER_INVALID")
                        .withHint("请重新选择当前工厂内的有效成品或入口原料")
                        .withSeverity("warning"));
        requireActiveRawOwner(rawOwner);

        // 运行时与历史外键仍以 product_type_id 作为通用 owner key。为真正的 RawMaterialType
        // 创建一个 inactive 内部锚点，前端产品目录会过滤 RAW_MATERIAL，不再把原料伪装成 SKU。
        ProductType anchor = new ProductType();
        anchor.setId(rawOwner.getId());
        anchor.setFactoryId(factoryId);
        anchor.setCode(rawOwnerAnchorCode(rawOwner));
        anchor.setName("__RAW_WORKFLOW_OWNER__" + rawOwner.getId());
        anchor.setCategory(ProductCategory.RAW_MATERIAL);
        anchor.setProductCategory(ProductCategory.RAW_MATERIAL);
        anchor.setUnit(rawOwner.getUnit() == null || rawOwner.getUnit().isBlank() ? "kg" : rawOwner.getUnit());
        anchor.setIsActive(false);
        // product_types.created_by is NOT NULL. The internal anchor belongs to the
        // same master-data lineage as its raw material, so inherit its creator;
        // legacy raw rows without creator use the repository's established system user.
        anchor.setCreatedBy(rawOwner.getCreatedBy() == null ? 1L : rawOwner.getCreatedBy());
        try {
            productTypeRepository.saveAndFlush(anchor);
        } catch (DataIntegrityViolationException race) {
            if (productTypeRepository.findByIdAndFactoryId(ownerId, factoryId).isEmpty()) throw race;
        }
    }

    private void requireActiveRawOwner(RawMaterialType rawOwner) {
        if (!Boolean.TRUE.equals(rawOwner.getIsActive()) || rawOwner.getMergedIntoId() != null) {
            throw new BusinessException(409, "Workflow 所属原料已停用或已合并，不能继续编辑")
                    .withCode("MATERIAL_REFERENCE_CONFLICT")
                    .withHint("请改用当前有效的原料主数据");
        }
    }

    /** Product-row lock serializes the first draft as well, when no Workflow row exists yet. */
    private void lockWorkflowOwner(String factoryId, String ownerId) {
        ProductType owner = productTypeRepository.findByIdAndFactoryIdForUpdate(ownerId, factoryId)
                .orElseThrow(() -> new BusinessException(409, "Workflow 所属对象正在变化，请刷新后重试")
                        .withCode("PRODUCT_PROCESS_WORKFLOW_CONFLICT")
                        .withHint("请重新加载后再次保存")
                        .withSeverity("warning"));
    }

    private String rawOwnerAnchorCode(RawMaterialType rawOwner) {
        String source = rawOwner.getCode() == null || rawOwner.getCode().isBlank()
                ? rawOwner.getId() : rawOwner.getCode();
        String normalized = source.replaceAll("[^A-Za-z0-9_-]", "");
        String code = "WFRAW-" + (normalized.isBlank() ? Integer.toUnsignedString(source.hashCode()) : normalized);
        return code.length() <= 50 ? code : code.substring(0, 50);
    }

    private void assertCurrentIdentity(ProductProcessWorkflowDTO request,
                                      ProductProcessWorkflow entity) {
        if (request.getId() == null
                || request.getVersion() == null
                || request.getLockVersion() == null
                || !request.getId().equals(entity.getId())
                || !request.getVersion().equals(entity.getDefinitionVersion())
                || !request.getLockVersion().equals(entity.getLockVersion())) {
            throw new BusinessException(409, "该 Workflow 已被其他人更新")
                    .withCode("PRODUCT_PROCESS_WORKFLOW_CONFLICT")
                    .withHint("请重新加载最新版本；如需保留当前内容，可复制当前草稿 JSON")
                    .withSeverity("warning");
        }
    }

    private void assertCurrentVersion(Long requestVersion, ProductProcessWorkflow entity) {
        if (requestVersion == null || !requestVersion.equals(entity.getLockVersion())) {
            throw new BusinessException(409, "该 Workflow 已被其他人更新")
                    .withCode("PRODUCT_PROCESS_WORKFLOW_CONFLICT")
                    .withHint("请重新加载最新版本；如需保留当前内容，可复制当前草稿 JSON")
                    .withSeverity("warning");
        }
    }

    private ProductProcessWorkflowDTO toDTO(ProductProcessWorkflow entity) {
        ProductProcessWorkflowDTO dto = new ProductProcessWorkflowDTO();
        dto.setId(entity.getId());
        dto.setFactoryId(entity.getFactoryId());
        dto.setProductTypeId(entity.getProductTypeId());
        dto.setSchemaVersion(entity.getSchemaVersion());
        dto.setStatus(entity.getStatus().name());
        dto.setVersion(entity.getDefinitionVersion());
        dto.setLockVersion(entity.getLockVersion());
        dto.setRevisionId(entity.getCurrentRevisionId());
        dto.setRevisionHash(entity.getCurrentRevisionHash());
        dto.setUnitReviewRequired(entity.getUnitReviewRequired());
        dto.setNodes(readJson(
                entity.getNodesJson(),
                new TypeReference<List<ProductProcessWorkflowDTO.Node>>() {},
                "nodes"));
        dto.setEdges(readJson(
                entity.getEdgesJson(),
                new TypeReference<List<ProductProcessWorkflowDTO.Edge>>() {},
                "edges"));
        dto.setViewport(readJson(
                entity.getViewportJson(),
                new TypeReference<ProductProcessWorkflowDTO.Viewport>() {},
                "viewport"));
        return dto;
    }

    private ProductProcessWorkflowRevision requireCurrentRevision(ProductProcessWorkflow workflow) {
        ProductProcessWorkflowRevision revision = revisionSnapshotService.captureDraft(workflow);
        if (!revision.getId().equals(workflow.getCurrentRevisionId())
                || !revision.getRevisionHash().equals(workflow.getCurrentRevisionHash())) {
            workflow.setCurrentRevisionId(revision.getId());
            workflow.setCurrentRevisionHash(revision.getRevisionHash());
        }
        return revision;
    }

    private ProductProcessWorkflowRevision readCurrentRevision(ProductProcessWorkflow workflow) {
        if (workflow.getCurrentRevisionId() == null
                || workflow.getCurrentRevisionHash() == null) {
            throw new BusinessException(409, "Workflow 缺少当前保存修订")
                    .withCode("PRODUCT_PROCESS_WORKFLOW_REVISION_MISSING")
                    .withHint("请先保存 Workflow 草稿后重试")
                    .withSeverity("warning");
        }
        return revisionRepository
                .findByIdAndFactoryId(workflow.getCurrentRevisionId(), workflow.getFactoryId())
                .filter(revision -> workflow.getId().equals(revision.getWorkflowId()))
                .filter(revision -> workflow.getCurrentRevisionHash()
                        .equals(revision.getRevisionHash()))
                .orElseThrow(() -> new BusinessException(409, "Workflow 当前修订身份不一致")
                        .withCode("WORKFLOW_REVISION_IDENTITY_MISMATCH")
                        .withHint("请刷新并重新保存 Workflow")
                        .withSeverity("warning"));
    }

    private ProductProcessWorkflowDTO toDTOWithUnitWarnings(ProductProcessWorkflow entity) {
        ProductProcessWorkflowDTO dto = toDTO(entity);
        dto.setUnitWarnings(unitValidator.validate(entity.getFactoryId(), dto).errors());
        return dto;
    }

    /**
     * A delayed autosave may arrive after the only draft has already been published. Compare the
     * incoming business graph against that published row using the revision service's canonical
     * hash. Definition version and viewport are pinned to the published row: version allocation
     * and a late camera pan/zoom are not Workflow business changes, while schema, nodes and edges
     * remain authoritative. Keep this server-side guard after the Web autosave barrier until every
     * supported client has an equivalent publish generation barrier and late-save telemetry stays
     * at zero; only then is the no-op compatibility path removable.
     */
    private boolean sameBusinessGraph(
            ProductProcessWorkflow published,
            ProductProcessWorkflowDTO definition) {
        ProductProcessWorkflow candidate = new ProductProcessWorkflow();
        candidate.setFactoryId(published.getFactoryId());
        candidate.setProductTypeId(published.getProductTypeId());
        candidate.setDefinitionVersion(published.getDefinitionVersion());
        candidate.setSchemaVersion(definition.getSchemaVersion());
        candidate.setNodesJson(writeJson(definition.getNodes()));
        candidate.setEdgesJson(writeJson(definition.getEdges()));
        candidate.setViewportJson(published.getViewportJson());
        return Objects.equals(
                revisionSnapshotService.hash(published),
                revisionSnapshotService.hash(candidate));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw invalidStoredJson("Workflow 数据无法序列化", error);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type, String field) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException error) {
            throw invalidStoredJson("Workflow " + field + " 数据损坏", error);
        }
    }

    private BusinessException invalidStoredJson(String message, JsonProcessingException cause) {
        return (BusinessException) new BusinessException(500, message, cause)
                .withCode("PRODUCT_PROCESS_WORKFLOW_DATA_INVALID")
                .withHint("请联系管理员检查该产品的 Workflow 图定义")
                .withSeverity("error");
    }
}
