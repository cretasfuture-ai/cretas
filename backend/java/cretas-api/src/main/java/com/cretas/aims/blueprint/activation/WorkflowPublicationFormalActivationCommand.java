package com.cretas.aims.blueprint.activation;

import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.*;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintService;
import com.cretas.aims.repository.ProductProcessWorkflowActivationRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.security.ActorRef;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;

/** Formal Workflow publication adapter over the existing publication service. */
public final class WorkflowPublicationFormalActivationCommand implements FactoryFormalModuleActivationCommand {
    private final AssemblyActivationActorResolver actors;
    private final BlueprintWorkflowPublicationService publication;
    private final CanonicalFactoryBlueprintService blueprints;
    private final ProductProcessWorkflowRepository workflows;
    private final ProductProcessWorkflowActivationRepository activations;
    private final com.cretas.aims.service.bom.BomRecipeService recipes;

    public WorkflowPublicationFormalActivationCommand(AssemblyActivationActorResolver actors,
            BlueprintWorkflowPublicationService publication, CanonicalFactoryBlueprintService blueprints,
            ProductProcessWorkflowRepository workflows, ProductProcessWorkflowActivationRepository activations,
            com.cretas.aims.service.bom.BomRecipeService recipes) {
        this.actors=Objects.requireNonNull(actors); this.publication=Objects.requireNonNull(publication);
        this.blueprints=Objects.requireNonNull(blueprints); this.workflows=Objects.requireNonNull(workflows);
        this.activations=Objects.requireNonNull(activations); this.recipes=Objects.requireNonNull(recipes);
    }
    public String capabilityId() { return "workflow"; }
    public String formalCommandName() { return "WorkflowPublicationFormalActivationCommand"; }
    public List<String> formalDependencies() { return List.of("routes", "bom", "critical-controls"); }

    public List<ActivationIssue> validateFormalPrerequisites(String factoryId, String draftId, ActorRef actor) {
        try { currentDraft(factoryId, draftId, actor); return List.of(); }
        catch (com.cretas.aims.exception.BusinessException e) {
            return List.of(new ActivationIssue(e.getErrorCode() == null ? "WORKFLOW_PREREQUISITES_BLOCKED" : e.getErrorCode(), "workflow", e.getMessage(), "补齐当前 Workflow、BOM 与关键控制点 DRAFT"));
        }
    }
    public CurrentDraft currentDraft(String factoryId, String draftId, ActorRef actor) {
        actors.authorize(actor, factoryId, java.util.Set.of("factory-blueprint:activation:workflow"));
        var selection = decode(draftId);
        var blueprint = blueprints.current(factoryId);
        if (blueprint == null) throw blocked("BLUEPRINT_NOT_CURRENT", "当前工厂没有可发布的蓝图", "刷新当前蓝图后重新预览");
        var snapshot = publication.preview(factoryId, blueprint.getId(), selection, actor);
        return new CurrentDraft(capabilityId(), draftId, Math.addExact(Objects.requireNonNullElse(snapshot.workflowLockVersion(), 0L), 1L),
                snapshot.digest(), "DRAFT");
    }
    public ModuleCommandReceipt activate(ModuleActivationCommandRequest request) {
        CurrentDraft current = currentDraft(request.factoryId(), request.draftId(), request.executedActor());
        if (current.revision() != request.expectedRevision() || !current.digest().equals(request.expectedDigest()))
            throw blocked("WORKFLOW_SNAPSHOT_DRIFTED", "Workflow 发布快照已漂移", "重新读取当前 Workflow 草稿后重新预览");
        var blueprint = blueprints.current(request.factoryId());
        var receipt = publication.publish(request.factoryId(), blueprint.getId(), decode(request.draftId()),
                request.executedActor(), request.expectedDigest());
        if (!"PUBLISHED".equals(receipt.status()) || !receipt.enabled() || receipt.activeBomRecipeId() == null)
            throw new IllegalStateException("Workflow publication did not reach enabled PUBLISHED runtime");
        String target = receipt.workflowId() + ":" + receipt.activeBomRecipeId();
        var workflow = workflows.findByIdAndFactoryId(receipt.workflowId(), request.factoryId()).orElseThrow();
        var recipe = recipes.findOwnedRecipe(request.factoryId(), receipt.activeBomRecipeId()).orElseThrow();
        return new ModuleCommandReceipt(capabilityId(), "workflow:" + receipt.workflowId(), target,
                Objects.requireNonNullElse(receipt.workflowRevision(), 0), runtimeDigest(request.factoryId(), workflow, recipe), 2);
    }
    public RuntimeReadback readBack(String factoryId, ModuleCommandReceipt receipt) {
        String[] ids = receipt.targetRuntimeId().split(":", 2);
        if (ids.length != 2) throw blocked("WORKFLOW_RUNTIME_TARGET_INVALID", "Workflow 运行态目标无效", "重新执行 Workflow 正式激活");
        long workflowId;
        try { workflowId = Long.parseLong(ids[0]); }
        catch (NumberFormatException e) { throw blocked("WORKFLOW_RUNTIME_TARGET_INVALID", "Workflow 编号无效", "重新执行 Workflow 正式激活"); }
        var workflow = workflows.findByIdAndFactoryId(workflowId, factoryId)
                .filter(row -> row.getStatus() == com.cretas.aims.entity.ProductProcessWorkflow.Status.PUBLISHED)
                .orElseThrow(() -> blocked("WORKFLOW_RUNTIME_NOT_PUBLISHED", "Workflow 尚未发布运行", "完成 Workflow 发布后重新回读"));
        var activation = activations.findByFactoryIdAndProductTypeId(factoryId, workflow.getProductTypeId())
                .filter(a -> Boolean.TRUE.equals(a.getEnabled()) && workflowId == a.getActiveWorkflowId())
                .orElseThrow(() -> blocked("WORKFLOW_RUNTIME_NOT_ACTIVE", "Workflow 运行绑定尚未 ACTIVE", "启用当前 Workflow 绑定后重新回读"));
        var recipe = recipes.findOwnedRecipe(factoryId, ids[1])
                .filter(r -> r.getStatus() == com.cretas.aims.entity.bom.BomRecipe.Status.ACTIVE
                        && Boolean.TRUE.equals(r.getIsCurrent()))
                .orElseThrow(() -> blocked("WORKFLOW_BOM_RUNTIME_MISSING", "当前 BOM 尚无 ACTIVE 运行数据", "发布当前 BOM 后重新回读"));
        return new RuntimeReadback(capabilityId(), receipt.targetRuntimeId(), activation.getActiveDefinitionVersion(),
                runtimeDigest(factoryId, workflow, recipe), "ACTIVE");
    }
    public String recovery(ModuleCommandReceipt receipt) { return "使用现有 Workflow 引用锁停用路径"; }

    private static BlueprintWorkflowPublicationService.Selection decode(String value) {
        if (value == null || !value.matches("w=[1-9][0-9]*;b=[^;]+(?:;c=[^;]+)?"))
            throw blocked("WORKFLOW_SELECTION_INVALID", "Workflow 发布选择编号格式不正确", "仅提交服务器返回的 Workflow、BOM、关键控制点草稿编号");
        String[] fields = value.split(";", -1);
        if (fields.length != 2 && fields.length != 3)
            throw blocked("WORKFLOW_SELECTION_INVALID", "Workflow 发布选择编号格式不正确", "仅提交服务器返回的 Workflow、BOM、关键控制点草稿编号");
        try {
            return new BlueprintWorkflowPublicationService.Selection(Long.valueOf(fields[0].substring(2)),
                    fields[1].substring(2), fields.length == 3 ? fields[2].substring(2) : null);
        } catch (RuntimeException e) {
            throw blocked("WORKFLOW_SELECTION_INVALID", "Workflow 发布选择编号格式不正确", "仅提交服务器返回的 Workflow、BOM、关键控制点草稿编号");
        }
    }
    private static String runtimeDigest(BlueprintWorkflowPublicationService.PublicationReceipt receipt) {
        return sha256(String.join("|", String.valueOf(receipt.workflowId()), String.valueOf(receipt.workflowRevision()),
                receipt.activeBomRecipeId(), receipt.bomFamilyDigest(), receipt.bomItemDigest()));
    }
    private String runtimeDigest(String factoryId, com.cretas.aims.entity.ProductProcessWorkflow workflow,
                                 com.cretas.aims.entity.bom.BomRecipe recipe) {
        String items = recipes.getRecipeItems(factoryId, recipe.getId()).stream()
                .map(item -> String.join("|", String.valueOf(item.getId()), String.valueOf(item.getSortOrder()),
                        String.valueOf(item.getMaterialTypeId()), String.valueOf(item.getStandardQuantity()),
                        String.valueOf(item.getUnit()), String.valueOf(item.getIsOptional()),
                        String.valueOf(item.getMaterialCategory()), String.valueOf(item.getPackagingRole()),
                        String.valueOf(item.getWorkflowMaterialNodeId()), String.valueOf(item.getWorkflowInputPortId()),
                        String.valueOf(item.getWorkflowEdgeId())))
                .collect(java.util.stream.Collectors.joining("\n"));
        return sha256(String.join("|", String.valueOf(workflow.getId()), String.valueOf(workflow.getSchemaVersion()),
                String.valueOf(workflow.getDefinitionVersion()), String.valueOf(workflow.getCurrentRevisionId()),
                String.valueOf(workflow.getCurrentRevisionHash()), workflow.getNodesJson(), workflow.getEdgesJson(),
                recipe.getId(), String.valueOf(recipe.getVersion()), String.valueOf(recipe.getStatus()),
                String.valueOf(recipe.getIsCurrent()), String.valueOf(recipe.getWorkflowId()),
                String.valueOf(recipe.getWorkflowRevisionId()), String.valueOf(recipe.getWorkflowRevisionHash()), items));
    }
    private static com.cretas.aims.exception.BusinessException blocked(String code, String message, String hint) {
        return new com.cretas.aims.exception.BusinessException(409, message).withCode(code).withHint(hint).withSeverity("BLOCKING");
    }
    private static String sha256(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
}
