package com.cretas.aims.blueprint.canonical;

import com.cretas.aims.blueprint.activation.FactoryFormalRuntimeReadback;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.CapabilityView;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest;
import com.cretas.aims.controller.dto.indicator.IndicatorApplicabilityRequest.ProductSpec;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowRevision;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRevisionRepository;
import com.cretas.aims.repository.ProductProcessWorkflowActivationRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.config.FactoryBlueprintBindingRepository;
import com.cretas.aims.service.indicator.FactoryIndicatorApplicabilityService;
import com.cretas.aims.service.workflow.ProductWorkflowResolutionService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true")
public class FactoryReadinessService {
    private final CanonicalFactoryBlueprintService blueprints;
    private final FactoryFormalRuntimeReadback committed;
    private final ProductTypeRepository products;
    private final ProductProcessWorkflowActivationRepository activations;
    private final ProductProcessWorkflowRepository workflows;
    private final ProductProcessWorkflowRevisionRepository revisions;
    private final BomRecipeRepository recipes;
    private final FactoryBlueprintBindingRepository bindings;
    private final FactoryIndicatorApplicabilityService indicators;
    private final ProductWorkflowResolutionService resolution;
    private final FactoryBusinessReadinessReader business;

    public FactoryReadinessReport read(String factoryId, String blueprintId) {
        return committed.read(() -> {
            BlueprintResponse blueprint = blueprints.current(factoryId);
            if (blueprint == null || !Objects.equals(factoryId, blueprint.getFactoryId())
                    || !Objects.equals(blueprintId, blueprint.getId())) {
                throw new BusinessException(409, "蓝图版本已变化，请刷新当前工厂后再核对")
                        .withCode("FACTORY_READINESS_BLUEPRINT_DRIFT")
                        .withHint("请刷新当前工厂的建厂验收页面，再核对候选版本与已应用版本");
            }
            var applied = bindings.findByFactoryIdAndDeletedAtIsNull(factoryId);
            var appliedView = applied.map(binding -> new FactoryReadinessReport.AppliedVersion(
                    binding.getBlueprintId(), binding.getAppliedVersion(),
                    !Objects.equals(binding.getBlueprintId(), blueprint.getId())
                            || !Objects.equals(binding.getAppliedVersion(), blueprint.getVersion()),
                    "当前候选配置与已应用版本分别记录；预览或新草稿不会替换既有工艺与历史单据"))
                    .orElse(new FactoryReadinessReport.AppliedVersion(null, null, true,
                            "未找到已应用版本绑定，不能把候选蓝图当作运行配置"));
            var runtime = runtime(factoryId);
            var domains = business.read(factoryId, blueprint.getCapabilities(), runtime);
            return new FactoryReadinessReport("factory-readiness/v1", factoryId, blueprintId,
                    blueprint.getVersion(), blueprint.getRowVersion(), blueprint.getLifecycleState(),
                    safe(blueprint.getCapabilities()).stream().map(FactoryReadinessService::capability).toList(),
                    runtime, appliedView,
                    indicators.assessPlanned(factoryId, plannedProducts(blueprint)), indicators.assess(factoryId),
                    "UNVERIFIED", 0, LocalDateTime.now(),
                    stages(blueprint, appliedView, runtime, domains), domains);
        });
    }

    /**
     * Five separately evidenced stages. Each reads its own fact; none is inferred from another
     * (a READY catalog, an applied blueprint or an HTTP 200 never marks a later stage done).
     */
    static List<FactoryReadinessReport.Stage> stages(BlueprintResponse blueprint,
            FactoryReadinessReport.AppliedVersion applied, List<FactoryReadinessReport.ProductRuntime> runtime,
            List<FactoryReadinessReport.BusinessDomain> domains) {
        var capabilities = safe(blueprint.getCapabilities());
        String confirmation = capabilities.isEmpty() ? "UNKNOWN"
                : capabilities.stream().allMatch(row -> "CONFIRMED".equals(row.getConfirmationState())) ? "DONE"
                : "PENDING";
        String lifecycle = blueprint.getLifecycleState();
        String published = List.of("PUBLISHED", "APPLIED", "SUPERSEDED").contains(lifecycle) ? "DONE"
                : List.of("DRAFT", "VALIDATED").contains(lifecycle) ? "PENDING" : "UNKNOWN";
        boolean appliedThis = applied.version() != null && !applied.candidateDiffers();
        long live = runtime.stream().filter(row -> "PUBLISHED".equals(row.state())).count();
        String formal = !runtime.isEmpty() && live == runtime.size() ? "DONE" : "PENDING";
        var applicable = domains.stream().filter(row -> !"NOT_APPLICABLE".equals(row.applicability())).toList();
        String ready = applicable.isEmpty() ? "NOT_APPLICABLE"
                : applicable.stream().anyMatch(row -> "UNKNOWN".equals(row.state())) ? "UNKNOWN"
                : applicable.stream().allMatch(row -> "READY".equals(row.state())) ? "DONE" : "PENDING";
        String first = applicable.isEmpty() ? "NOT_APPLICABLE"
                : applicable.stream().anyMatch(row -> "UNKNOWN".equals(row.firstRecord().state())) ? "UNKNOWN"
                // Only a verified terminal document completes it; drafts or any other existing document do not.
                : applicable.stream().allMatch(row -> "COMPLETED".equals(row.firstRecord().state())) ? "DONE"
                : "PENDING";
        return List.of(
                new FactoryReadinessReport.Stage("CONFIRMATION", "资料确认", confirmation,
                        capabilities.isEmpty() ? "蓝图没有能力清单" : "蓝图确认记录"),
                new FactoryReadinessReport.Stage("BLUEPRINT_PUBLISHED", "蓝图发布", published,
                        "蓝图状态 " + Objects.toString(lifecycle, "未知")),
                new FactoryReadinessReport.Stage("CONFIGURATION_APPLIED", "配置应用到本厂", appliedThis ? "DONE" : "PENDING",
                        applied.version() == null ? "没有已应用版本绑定"
                                : appliedThis ? "已应用第" + applied.version() + "版"
                                : "已应用的是第" + applied.version() + "版，不是当前候选"),
                new FactoryReadinessReport.Stage("FORMAL_RUNTIME", "工艺与BOM正式生效", formal,
                        runtime.isEmpty() ? "尚无在用成品" : live + "/" + runtime.size() + " 个在用成品已生效并匹配"),
                new FactoryReadinessReport.Stage("BUSINESS_READY", "采购/生产/发货就绪", ready,
                        applicable.stream().map(row -> row.label() + "：" + READY_WORDS.getOrDefault(row.state(), row.state()))
                                .collect(Collectors.joining("；"))),
                new FactoryReadinessReport.Stage("FIRST_BUSINESS", "首笔业务", first,
                        applicable.stream().map(row -> row.label() + "：" + FIRST_WORDS.getOrDefault(
                                row.firstRecord().state(), row.firstRecord().state())).collect(Collectors.joining("；"))));
    }

    private static final Map<String, String> READY_WORDS = Map.of(
            "READY", "可以开展", "MISSING", "还差资料", "UNKNOWN", "无法判断", "NOT_APPLICABLE", "本次不适用");
    private static final Map<String, String> FIRST_WORDS = Map.of(
            "COMPLETED", "已有完成的单据", "RECORDED", "有单据但尚未完成（草稿等不算完成）",
            "NONE", "尚无单据", "UNKNOWN", "无法判断");

    static FactoryReadinessReport.Capability capability(CapabilityView capability) {
        List<Map<String, Object>> receipts = safe(capability.getConfigurationReceipts());
        String stage;
        if (!capability.isIncluded()) stage = "NOT_APPLICABLE";
        else if ("REJECTED".equals(capability.getAcceptanceState())
                || !safe(capability.getValidationIssues()).isEmpty()
                || receipts.stream().anyMatch(row -> List.of("FAILED", "REVIEW_REQUIRED")
                        .contains(Objects.toString(row.get("status"), "")))) stage = "BLOCKED";
        else if (!"APPLIED".equals(capability.getConfigurationApplyState())
                || receipts.isEmpty()) stage = "WAITING_CONFIGURATION";
        else if (!"ACCEPTED".equals(capability.getAcceptanceState())) stage = "WAITING_REVIEW";
        else stage = "CONFIGURATION_ACCEPTED";
        return new FactoryReadinessReport.Capability(capability.getId(), capability.getLabel(),
                capability.getConfigurationApplyState(), capability.getAcceptanceState(), stage,
                capability.getNextStep(), receipts.size());
    }

    private List<FactoryReadinessReport.ProductRuntime> runtime(String factoryId) {
        return products.findVisibleByFactoryIdAndIsActiveTrue(factoryId).stream()
                .filter(FactoryIndicatorApplicabilityService::eligibleProduct)
                .map(product -> {
                    var path = resolution.resolveProcessPath(factoryId, product.getId());
                    var activation = path.filter(row -> Objects.equals(row.terminalProductTypeId(), product.getId()))
                            .flatMap(row -> activations.findByFactoryIdAndProductTypeId(factoryId, row.ownerId()));
                    var workflow = activation.filter(row -> Boolean.TRUE.equals(row.getEnabled()))
                            .flatMap(row -> workflows.findByIdAndFactoryId(row.getActiveWorkflowId(), factoryId));
                    var recipe = recipes.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                            factoryId, product.getId(), BomRecipe.Status.ACTIVE);
                    var revision = workflow.filter(row -> row.getCurrentRevisionId() != null)
                            .flatMap(row -> revisions.findByIdAndFactoryId(row.getCurrentRevisionId(), factoryId));
                    boolean coherent = workflow.isPresent() && recipe.isPresent()
                            && revision.isPresent()
                            && Objects.equals(factoryId, workflow.get().getFactoryId())
                            && path.isPresent()
                            && Objects.equals(path.get().ownerId(), workflow.get().getProductTypeId())
                            && path.get().workflowId() == workflow.get().getId()
                            && path.get().definitionVersion() == workflow.get().getDefinitionVersion()
                            && workflow.get().getStatus() == ProductProcessWorkflow.Status.PUBLISHED
                            && Objects.equals(activation.orElseThrow().getActiveDefinitionVersion(),
                                    workflow.get().getDefinitionVersion())
                            && Objects.equals(recipe.get().getWorkflowId(), workflow.get().getId())
                            && workflow.get().getCurrentRevisionId() != null
                            && Objects.equals(recipe.get().getWorkflowRevisionId(), workflow.get().getCurrentRevisionId())
                            && revision.get().getStatus() == ProductProcessWorkflowRevision.Status.PUBLISHED
                            && Objects.equals(revision.get().getFactoryId(), factoryId)
                            && Objects.equals(revision.get().getProductTypeId(), path.get().ownerId())
                            && Objects.equals(revision.get().getWorkflowId(), workflow.get().getId())
                            && Objects.equals(revision.get().getDefinitionVersion(), workflow.get().getDefinitionVersion())
                            && revision.get().getRevisionHash() != null
                            && !revision.get().getRevisionHash().isBlank()
                            && Objects.equals(revision.get().getRevisionHash(), workflow.get().getCurrentRevisionHash())
                            && Objects.equals(recipe.get().getWorkflowDefinitionVersion(), workflow.get().getDefinitionVersion())
                            && Objects.equals(recipe.get().getWorkflowRevisionHash(), revision.get().getRevisionHash());
                    return new FactoryReadinessReport.ProductRuntime(product.getId(), product.getName(),
                            coherent ? "PUBLISHED" : "NEEDS_REVIEW",
                            workflow.map(ProductProcessWorkflow::getId).orElse(null),
                            workflow.map(ProductProcessWorkflow::getDefinitionVersion).orElse(null),
                            recipe.map(BomRecipe::getId).orElse(null), recipe.map(BomRecipe::getVersion).orElse(null),
                            coherent ? "当前工艺及BOM已生效并匹配；本次业务验收仍需单独确认"
                                    : "工艺、启用绑定或BOM版本尚未完整匹配，请在现有工艺发布入口核对");
                }).toList();
    }

    static List<ProductSpec> plannedProducts(BlueprintResponse blueprint) {
        ApplyAIInitRequest payload = blueprint.getSourcePayload();
        if (payload == null || payload.getSuggestedData() == null) return List.of();
        var data = payload.getSuggestedData();
        Map<String, ApplyAIInitRequest.WorkProcessItem> processes = safe(data.getWorkProcesses()).stream()
                .filter(row -> row.getName() != null)
                .collect(Collectors.toMap(ApplyAIInitRequest.WorkProcessItem::getName,
                        Function.identity(), (left, right) -> left));
        return safe(data.getProductTypes()).stream().map(product -> {
            var route = safe(blueprint.getRouteGraphs()).stream()
                    .filter(row -> Objects.equals(row.productCode(), product.getCode())).findFirst();
            String inputUnit = route.filter(row -> row.validation() != null && row.validation().valid()
                            && row.entryNodeIds().size() == 1)
                    .flatMap(row -> row.nodes().stream().filter(node -> row.entryNodeIds().contains(node.id())).findFirst())
                    .map(node -> processes.get(node.label())).map(ApplyAIInitRequest.WorkProcessItem::getUnit).orElse(null);
            Boolean multiOutput = route.filter(row -> row.validation() != null && row.validation().valid())
                    .map(row -> {
                        if (row.terminalNodeIds().size() > 1) return Boolean.TRUE;
                        var steps = row.nodes().stream().map(node -> processes.get(node.label())).toList();
                        if (steps.stream().anyMatch(Objects::isNull)) return null;
                        if (steps.stream().anyMatch(step -> step.getByproducts() != null && !step.getByproducts().isEmpty())) return Boolean.TRUE;
                        // Omitted by-product facts remain unknown, rather than an implicit "none".
                        return steps.stream().allMatch(step -> step.getByproducts() != null) ? Boolean.FALSE : null;
                    }).orElse(null);
            return new ProductSpec(product.getCode(), product.getName(), product.getUnit(), inputUnit, null, multiOutput);
        }).toList();
    }

    private static <T> List<T> safe(List<T> rows) { return rows == null ? List.of() : rows; }
}
