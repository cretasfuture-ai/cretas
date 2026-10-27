package com.cretas.aims.blueprint.activation;

import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintService;
import com.cretas.aims.blueprint.canonical.FactoryBlueprintCapabilityCatalog;
import com.cretas.aims.service.bomdraft.BomRecipeDraftPromotionAuthority;
import com.cretas.aims.blueprint.draftentry.BlueprintSourceDigest;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bomdraft.BomRecipeDraft;
import com.cretas.aims.entity.criticalcontroldraft.CriticalControlDraft;
import com.cretas.aims.entity.hazardanalysisdraft.HazardAnalysisDraft;
import com.cretas.aims.entity.promotion.MasterDataDraftPromotion;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.bomdraft.BomRecipeDraftRepository;
import com.cretas.aims.repository.bomdraft.BomRecipeDraftItemRepository;
import com.cretas.aims.repository.config.FactoryTypeBlueprintRepository;
import com.cretas.aims.repository.criticalcontroldraft.CriticalControlDraftRepository;
import com.cretas.aims.repository.hazardanalysisdraft.HazardAnalysisDraftRepository;
import com.cretas.aims.repository.promotion.MasterDataDraftPromotionRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.service.ProductProcessWorkflowService;
import com.cretas.aims.service.bom.BomRecipeService;
import com.cretas.aims.service.bomdraft.BomRecipeDraftCommand;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftCommand;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftModels;
import com.cretas.aims.service.hazardanalysisdraft.HazardAnalysisDraftCommand;
import com.cretas.aims.service.hazardanalysisdraft.HazardAnalysisDraftModels;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/**
 * Server-owned boundary for the final Workflow publication step.
 *
 * <p>The request contains only the three selected draft identities. Every other
 * pin is re-read from the factory-owned rows immediately before preview and
 * again before a write.</p>
 */
public class BlueprintWorkflowPublicationService {
    private static final String BOM_CAPABILITY = "bom";
    private static final String PUBLICATION_PERMISSION = "workflow:publish";

    private final AssemblyActivationActorResolver actors;
    private final CanonicalFactoryBlueprintService blueprints;
    private final FactoryTypeBlueprintRepository blueprintRows;
    private final ProductProcessWorkflowRepository workflows;
    private final BomRecipeDraftRepository bomDrafts;
    private final BomRecipeDraftItemRepository bomDraftItems;
    private final CriticalControlDraftRepository criticalControls;
    private final HazardAnalysisDraftRepository hazards;
    private final MasterDataDraftPromotionRepository promotions;
    private final BomRecipeService recipes;
    private final ProductProcessWorkflowService workflowService;
    private final BomRecipeDraftCommand bomDraftCommand;
    private final HazardAnalysisDraftCommand hazardCommand;
    private final CriticalControlDraftCommand criticalCommand;
    private final BomRecipeDraftPromotionAuthority promotionAuthority;

    /** 工艺保密 (写入侧): 发布会激活已有产品的配方并发布它的工艺图, 要编辑权。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard processSecretGuard;

    public BlueprintWorkflowPublicationService(
            AssemblyActivationActorResolver actors,
            CanonicalFactoryBlueprintService blueprints,
            FactoryTypeBlueprintRepository blueprintRows,
            ProductProcessWorkflowRepository workflows,
            BomRecipeDraftRepository bomDrafts,
            BomRecipeDraftItemRepository bomDraftItems,
            CriticalControlDraftRepository criticalControls,
            HazardAnalysisDraftRepository hazards,
            MasterDataDraftPromotionRepository promotions,
            BomRecipeService recipes,
            ProductProcessWorkflowService workflowService,
            BomRecipeDraftCommand bomDraftCommand,
            HazardAnalysisDraftCommand hazardCommand,
            CriticalControlDraftCommand criticalCommand,
            BomRecipeDraftPromotionAuthority promotionAuthority) {
        this.actors = actors;
        this.blueprints = blueprints;
        this.blueprintRows = blueprintRows;
        this.workflows = workflows;
        this.bomDrafts = bomDrafts;
        this.bomDraftItems = bomDraftItems;
        this.criticalControls = criticalControls;
        this.hazards = hazards;
        this.promotions = promotions;
        this.recipes = recipes;
        this.workflowService = workflowService;
        this.bomDraftCommand = bomDraftCommand;
        this.hazardCommand = hazardCommand;
        this.criticalCommand = criticalCommand;
        this.promotionAuthority = promotionAuthority;
    }

    public record Selection(Long workflowDraftId, String bomDraftId, String criticalControlsDraftId) {
        public Selection {
            if (workflowDraftId == null || workflowDraftId <= 0
                    || bomDraftId == null || bomDraftId.isBlank()
                    || (criticalControlsDraftId != null && criticalControlsDraftId.isBlank())) {
                throw invalid("PUBLICATION_SELECTION_INVALID", "发布选择必须包含 Workflow、BOM 草稿；所选关键控制点编号不得为空");
            }
        }
    }

    public record Snapshot(String factoryId, String blueprintId, long blueprintVersion,
                           String sourceDigest, Long workflowDraftId, Long workflowLockVersion,
                           Long workflowRevisionId, String workflowRevisionHash,
                           Integer workflowDefinitionVersion, String bomDraftId,
                           String criticalControlsDraftId, String formalRecipeId,
                           String digest) {}

    public record PublicationReceipt(String status, boolean enabled, Long workflowId,
                                    Integer workflowRevision, String activeBomRecipeId,
                                    String bomFamilyDigest, String bomItemDigest,
                                    String confirmedDigest, boolean replayed) {}

    @Transactional(readOnly = true)
    public Snapshot preview(String factoryId, String blueprintId, Selection selection, ActorRef actor) {
        authorize(factoryId, actor);
        return readSnapshot(factoryId, blueprintId, selection);
    }

    @Transactional
    public PublicationReceipt publish(String factoryId, String blueprintId, Selection selection,
                                      ActorRef actor, String confirmedDigest) {
        authorize(factoryId, actor);
        Snapshot first = readSnapshot(factoryId, blueprintId, selection);
        if (confirmedDigest == null || !confirmedDigest.equals(first.digest())) {
            throw conflict("PUBLICATION_CONFIRMATION_STALE", "发布确认摘要已过期，请重新预览");
        }
        Snapshot second = readSnapshot(factoryId, blueprintId, selection);
        if (!confirmedDigest.equals(second.digest())) {
            throw conflict("PUBLICATION_SNAPSHOT_CHANGED", "两次确认之间 Workflow 或其依赖已变化");
        }
        String owner = workflowOwner(factoryId, second.workflowDraftId());
        if (processSecretGuard != null) {
            // 工艺保密 (写入侧, Steve 2026-10-27): 这条路绕过了配方 / 工艺图路由上的编辑闸, 在这里补判;
            // 工艺图挂在原料锚点上时 (多产出), 配方所属产品就是被保护的那一个
            processSecretGuard.requireRecipe(factoryId, second.formalRecipeId(),
                    com.cretas.aims.entity.processsecret.ProcessSecretAction.EDIT,
                    "blueprint workflow publish recipe " + second.formalRecipeId());
            processSecretGuard.requireProduct(factoryId, owner,
                    com.cretas.aims.entity.processsecret.ProcessSecretAction.EDIT,
                    "blueprint workflow publish " + second.workflowDraftId());
            workflows.findByIdAndFactoryId(second.workflowDraftId(), factoryId).ifPresent(draft ->
                    com.cretas.aims.service.processsecret.ProcessSecretWorkflowOutputs
                            .terminalOutputs(draft.getNodesJson(), draft.getEdgesJson())
                            .forEach(output -> processSecretGuard.requireProduct(factoryId, output,
                                    com.cretas.aims.entity.processsecret.ProcessSecretAction.EDIT,
                                    "blueprint workflow publish output " + output)));
        }
        BomRecipe activated = recipes.activateRecipeAsActor(factoryId, second.formalRecipeId(), actor);
        var result = workflowService.publishAndActivateAsActor(
                factoryId, owner,
                second.workflowLockVersion(), "blueprint-workflow:" + confirmedDigest,
                second.workflowRevisionId(), second.workflowRevisionHash(),
                second.workflowDefinitionVersion(), actor);
        var workflow = result.getWorkflow();
        var activation = result.getActivation();
        return new PublicationReceipt(
                workflow.getStatus(), Boolean.TRUE.equals(activation.getEnabled()),
                workflow.getId(), workflow.getVersion(), activated.getId(),
                digestRecipe(activated), digestItems(factoryId, activated.getId()),
                confirmedDigest, result.isReplayed());
    }

    private void authorize(String factoryId, ActorRef actor) {
        actors.authorize(actor, factoryId, Set.of(PUBLICATION_PERMISSION));
    }

    private Snapshot readSnapshot(String factoryId, String blueprintId, Selection selection) {
        BlueprintResponse blueprint = blueprints.current(factoryId);
        if (blueprint == null || !Objects.equals(blueprintId, blueprint.getId())
                || !Objects.equals(factoryId, blueprint.getFactoryId())) {
            throw conflict("BLUEPRINT_NOT_CURRENT", "目标蓝图不是当前工厂蓝图");
        }
        var requirement = FactoryBlueprintCapabilityCatalog.criticalControlsRequirement(blueprint.getSourcePayload());
        if (requirement == FactoryBlueprintCapabilityCatalog.CriticalControlsRequirement.UNKNOWN) {
            throw conflict("BLUEPRINT_SOURCE_UNREADABLE", "当前蓝图源不能判定关键控制点依赖");
        }
        if (requirement == FactoryBlueprintCapabilityCatalog.CriticalControlsRequirement.REQUIRED
                && selection.criticalControlsDraftId() == null) {
            throw conflict("CRITICAL_CONTROLS_REQUIRED", "当前蓝图声明了关键控制点，必须选择已就绪草稿");
        }
        String source = BlueprintSourceDigest.of(blueprint);
        var persistedBlueprint = blueprintRows.findByFactoryIdAndIdAndDeletedAtIsNull(factoryId, blueprintId)
                .orElseThrow(() -> conflict("BLUEPRINT_SOURCE_UNREADABLE", "当前蓝图源记录不可验证"));
        if (!Boolean.TRUE.equals(persistedBlueprint.getIsCurrent())
                || !Boolean.TRUE.equals(persistedBlueprint.getIsActive())
                || !Objects.equals(persistedBlueprint.getVersion(), blueprint.getVersion())
                || !Objects.equals(persistedBlueprint.getFactoryId(), factoryId)) {
            throw conflict("BLUEPRINT_SOURCE_DRIFT", "当前蓝图持久化版本不是 canonical current");
        }
        ProductProcessWorkflow workflow = workflows.findByIdAndFactoryId(
                selection.workflowDraftId(), factoryId)
                .filter(row -> row.getStatus() == ProductProcessWorkflow.Status.DRAFT)
                .orElseThrow(() -> conflict("WORKFLOW_DRAFT_NOT_FOUND", "Workflow 草稿不存在或已不是 DRAFT"));
        BomRecipeDraft bom = bomDrafts.findByIdAndFactoryId(selection.bomDraftId(), factoryId)
                .filter(row -> "DRAFT".equals(row.getStatus()))
                .orElseThrow(() -> conflict("BOM_DRAFT_NOT_FOUND", "BOM 草稿不存在或已不是 DRAFT"));
        requireLineage(blueprint, source, workflow, bom);
        CriticalControlDraft critical = null;
        HazardAnalysisDraft hazard = null;
        if (selection.criticalControlsDraftId() != null) {
            critical = criticalControls.findByIdAndFactoryId(
                    selection.criticalControlsDraftId(), factoryId)
                    .filter(row -> "DRAFT".equals(row.getStatus()))
                    .orElseThrow(() -> conflict("CRITICAL_CONTROLS_NOT_READY", "关键控制点必须是当前工厂的 DRAFT"));
            hazard = hazards.findByIdAndFactoryId(critical.getHazardDraftId(), factoryId)
                    .filter(row -> "DRAFT".equals(row.getStatus()))
                    .orElseThrow(() -> conflict("HAZARD_NOT_READY", "关键控制点引用的危害分析必须是 DRAFT"));
            if (!Objects.equals(hazard.getBomDraftId(), bom.getId())
                    || !Objects.equals(critical.getBlueprintId(), blueprintId)
                    || !Objects.equals(hazard.getBlueprintId(), blueprintId)
                    || !Objects.equals(critical.getBlueprintVersion(), blueprint.getVersion().longValue())
                    || !Objects.equals(hazard.getBlueprintVersion(), blueprint.getVersion().longValue())
                    || !Objects.equals(critical.getSourceDigest(), source)
                    || !Objects.equals(hazard.getSourceDigest(), source)) {
                throw conflict("CRITICAL_CONTROLS_LINEAGE_DRIFT", "关键控制点、危害分析与本次 BOM/蓝图 lineage 不一致");
            }
            CriticalControlDraftModels.DraftReadback criticalReadback =
                    criticalCommand.readBack(factoryId, critical.getId());
            if (criticalReadback == null || criticalReadback.desired() == null
                    || criticalReadback.desired().hazard() == null
                    || !Objects.equals(criticalReadback.desired().hazard().draftId(), hazard.getId())) {
                throw conflict("CRITICAL_HAZARD_PIN_DRIFT", "关键控制点 readBack 的危害草稿不是当前选择");
            }
            HazardAnalysisDraftModels.DraftReadback hazardReadback =
                    hazardCommand.readBack(factoryId, hazard.getId());
            if (hazardReadback == null || hazardReadback.desired() == null
                    || hazardReadback.desired().bom() == null
                    || !Objects.equals(hazardReadback.desired().bom().draftId(), bom.getId())) {
                throw conflict("HAZARD_BOM_PIN_DRIFT", "危害分析 readBack 的 BOM 草稿不是当前选择");
            }
        }
        BomRecipeDraftModels.DraftReadback bomReadback = bomDraftCommand.readBack(factoryId, bom.getId());
        if (bomReadback == null || bomReadback.desired() == null
                || bomReadback.desired().workflow() == null
                || !Objects.equals(bomReadback.desired().workflow().workflowId(), workflow.getId())
                || !Objects.equals(bomReadback.desired().workflow().revisionId(), workflow.getCurrentRevisionId())
                || !Objects.equals(bomReadback.desired().workflow().revisionHash(), workflow.getCurrentRevisionHash())
                || !Objects.equals(bomReadback.desired().workflow().definitionVersion(), workflow.getDefinitionVersion())
                || !Objects.equals(bomReadback.desired().workflow().schemaVersion(), workflow.getSchemaVersion())) {
            throw conflict("BOM_WORKFLOW_READBACK_DRIFT", "BOM readBack 的 Workflow pin 不是当前选定 revision");
        }
        String recipeId = promotions.findByFactoryIdAndBlueprintIdAndBlueprintVersionAndSourceDigestOrderByCreatedAtDescIdDesc(
                        factoryId, blueprintId, blueprint.getVersion().longValue(), source).stream()
                .filter(row -> BOM_CAPABILITY.equals(row.getCapabilityId()))
                .filter(row -> Objects.equals(row.getDraftId(), bom.getId()))
                .filter(row -> MasterDataDraftPromotion.STATUS_PROMOTED.equals(row.getStatus()))
                .map(MasterDataDraftPromotion::getLiveTargetId).filter(Objects::nonNull).distinct()
                .reduce((a, b) -> { throw conflict("BOM_PROMOTION_AMBIGUOUS", "BOM 转正对应多个正式配方"); })
                .orElseThrow(() -> conflict("BOM_FORMAL_RECIPE_MISSING", "BOM 草稿尚未转成正式配方"));
        BomRecipe recipe = recipes.findOwnedRecipe(factoryId, recipeId)
                .filter(row -> row.getStatus() == BomRecipe.Status.DRAFT)
                .orElseThrow(() -> conflict("BOM_FORMAL_RECIPE_NOT_DRAFT", "正式配方必须保持 DRAFT 才能随 Workflow 生效"));
        if (!Objects.equals(recipe.getWorkflowRevisionHash(), bom.getWorkflowRevisionHash())
                || !Objects.equals(recipe.getWorkflowId(), bom.getWorkflowId())
                || !Objects.equals(recipe.getWorkflowRevisionId(), bom.getWorkflowRevisionId())
                || !Objects.equals(recipe.getProductTypeId(), bom.getTargetProductRefId())
                || !Objects.equals(recipe.getWorkflowDefinitionVersion(), bom.getWorkflowDefinitionVersion())
                || !Objects.equals(recipe.getWorkflowSchemaVersion(), bom.getWorkflowSchemaVersion())) {
            throw conflict("BOM_FORMAL_RECIPE_DRIFT", "正式配方与 BOM 草稿 Workflow pin 不一致");
        }
        var proof = promotionAuthority.verifiedFormalRecipeId(bom)
                .filter(value -> recipeId.equals(value.id()))
                .filter(value -> value.receiptProofDigest() != null && !value.receiptProofDigest().isBlank())
                .orElseThrow(() -> conflict("BOM_PROMOTION_PROOF_DRIFT", "正式配方与原转正确认回执不一致"));
        if (!promotionAuthority.matchesCurrent(proof, recipe)) {
            throw conflict("BOM_PROMOTION_PROOF_DRIFT", "正式配方头信息已变化");
        }
        String digest = digestSnapshot(factoryId, blueprintId, blueprint.getVersion(), source,
                workflow, bom, critical, hazard, recipe, proof.receiptProofDigest());
        return new Snapshot(factoryId, blueprintId, blueprint.getVersion(), source,
                workflow.getId(), workflow.getLockVersion(), workflow.getCurrentRevisionId(),
                workflow.getCurrentRevisionHash(), workflow.getDefinitionVersion(), bom.getId(),
                critical == null ? null : critical.getId(), recipeId, digest);
    }

    private void requireLineage(BlueprintResponse blueprint, String source,
                                ProductProcessWorkflow workflow, BomRecipeDraft bom) {
        if (!Objects.equals(bom.getBlueprintId(), blueprint.getId())
                || !Objects.equals(bom.getBlueprintVersion(), blueprint.getVersion().longValue())
                || !Objects.equals(bom.getSourceDigest(), source)
                || !Objects.equals(bom.getWorkflowId(), workflow.getId())
                || !Objects.equals(bom.getWorkflowRevisionId(), workflow.getCurrentRevisionId())
                || !Objects.equals(bom.getWorkflowRevisionHash(), workflow.getCurrentRevisionHash())) {
            throw conflict("WORKFLOW_BOM_LINEAGE_DRIFT", "Workflow 与 BOM 当前 revision/source 不一致");
        }
    }

    private String workflowOwner(String factoryId, Long workflowId) {
        return workflows.findByIdAndFactoryId(workflowId, factoryId)
                .map(ProductProcessWorkflow::getProductTypeId)
                .orElseThrow(() -> conflict("WORKFLOW_OWNER_MISSING", "Workflow 所属产品不存在"));
    }

    private String digestSnapshot(String factoryId, String blueprintId, Integer version, String source,
                                  ProductProcessWorkflow workflow, BomRecipeDraft bom,
                                  CriticalControlDraft critical, HazardAnalysisDraft hazard,
                                  BomRecipe recipe, String receiptProofDigest) {
        return sha256(String.join("|", factoryId, blueprintId, String.valueOf(version), source,
                String.valueOf(workflow.getId()), String.valueOf(workflow.getLockVersion()),
                String.valueOf(workflow.getCurrentRevisionId()), String.valueOf(workflow.getCurrentRevisionHash()),
                String.valueOf(bom.getId()), String.valueOf(bom.getDesiredDigest()),
                critical == null ? "critical:none" : String.valueOf(critical.getId()),
                critical == null ? "critical:none" : String.valueOf(critical.getDesiredDigest()),
                hazard == null ? "hazard:none" : String.valueOf(hazard.getId()),
                hazard == null ? "hazard:none" : String.valueOf(hazard.getDesiredDigest()),
                recipe.getId(), receiptProofDigest));
    }

    private String digestRecipe(BomRecipe recipe) {
        return sha256(String.join("|", recipe.getId(), String.valueOf(recipe.getVersion()),
                String.valueOf(recipe.getStatus()), String.valueOf(recipe.getProductTypeId())));
    }

    private String digestItems(String factoryId, String recipeId) {
        String material = recipes.getRecipeItems(factoryId, recipeId).stream()
                .map(item -> String.join("|",
                        String.valueOf(item.getId()), String.valueOf(item.getSortOrder()),
                        String.valueOf(item.getMaterialTypeId()), String.valueOf(item.getMaterialCategory()),
                        String.valueOf(item.getPackagingRole()),
                        String.valueOf(item.getStandardQuantity()), String.valueOf(item.getUnit()),
                        String.valueOf(item.getIsOptional()), String.valueOf(item.getWorkflowMaterialNodeId()),
                        String.valueOf(item.getWorkflowInputPortId()), String.valueOf(item.getWorkflowEdgeId())))
                .collect(java.util.stream.Collectors.joining("\n"));
        return sha256(recipeId + "|" + material);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("publication digest failed", error);
        }
    }

    private static BusinessException invalid(String code, String message) {
        return new BusinessException(400, message).withCode(code);
    }

    private static BusinessException conflict(String code, String message) {
        return new BusinessException(409, message).withCode(code).withHint("请刷新建厂预览，核对草稿版本后重新确认操作");
    }
}
