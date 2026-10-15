package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintService;
import com.cretas.aims.blueprint.canonical.FactoryRouteGraphPrototype.RouteGraph;
import com.cretas.aims.blueprint.capabilityadapter.FactoryCapabilityAdapterContracts;
import com.cretas.aims.blueprint.draftentry.BlueprintDraftOperatorResolver.DraftOperator;
import com.cretas.aims.blueprint.rule.UnitContractRuleUnitResolver;
import com.cretas.aims.blueprint.workflowassembly.WorkflowAssemblyModels.Assembly;
import com.cretas.aims.blueprint.workflowassembly.WorkflowAssemblyModels.AssemblyBlocker;
import com.cretas.aims.blueprint.workflowassembly.WorkflowCompileRequestAssembler;
import com.cretas.aims.blueprint.workflowmaterialize.FactoryWorkflowDraftMaterializationModels.DraftMaterializationRequest;
import com.cretas.aims.blueprint.workflowmaterialize.FactoryWorkflowDraftMaterializationModels.DraftOperationPreview;
import com.cretas.aims.blueprint.workflowmaterialize.FactoryWorkflowDraftMaterializationService;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowDraftPreviewCompiler;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowPreviewModels.CompileRequest;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowPreviewModels.ExistingDraft;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowPreviewModels.WorkflowDraftPreview;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.workflow.FactoryWorkflowDraftMaterializationLedger;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.workflow.FactoryWorkflowDraftMaterializationLedgerRepository;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code workflow}: Workflow DRAFT → {@link FactoryWorkflowDraftMaterializationService}, with the compile
 * request built on the server by {@link WorkflowCompileRequestAssembler}.
 *
 * <p>Trust boundary: the body ({@link WorkflowDraftRequest}) carries only the blueprint lineage, which
 * route, the user's choices (entry raw materials, one semi-finished product per non-terminal process) and
 * the confirmation fields. The route graph, the resolution catalog, the BOM input slots, the WIP bindings
 * and every digest come from the current blueprint and the live tables. #136 opened an entry that took
 * the compile request from the body and was withdrawn; this one cannot, because its request type has no
 * such field ({@code DraftEntryRequestsNeverCarryAClientGraphTest}).</p>
 *
 * <p>Blueprint check: the body's {@code blueprintId} (compared with the URL by the controller), version
 * and {@code sourceDigest} must be the factory's current DRAFT/VALIDATED blueprint and its
 * {@link BlueprintSourceDigest}. The capability's catalog status is not checked here: the catalog row of
 * {@code workflow} is human <em>publication</em> (after BOM and critical controls), not this DRAFT write
 * (see {@link FactoryCapabilityAdapterContracts#b25Defaults()}).</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class WorkflowDraftEntry extends TypedCapabilityDraftEntry<WorkflowDraftRequest> {

    public static final String CAPABILITY_ID = "workflow";
    private static final Set<String> CURRENT_BLUEPRINT_STATES = Set.of("DRAFT", "VALIDATED");
    /**
     * {@link FactoryWorkflowDraftMaterializationService#preview} validates the same envelope as materialize
     * (a non-blank idempotency key, a positive operator) although preview neither writes nor puts either
     * value into a digest. Preview has no operator yet, so these stand in; materialize always uses the
     * request's key and the server-signed operator.
     */
    static final String PREVIEW_IDEMPOTENCY_KEY = "workflow-draft-preview";
    static final long PREVIEW_OPERATOR = Long.MAX_VALUE;

    /**
     * What preview answers: the assembler's blockers, or the compiled graph plus the service's operation preview.
     *
     * <p>{@code previewDigest} and {@code confirmation} follow the drafts panel's contract: the client sends
     * {@code previewDigest} back as {@code confirmedPreviewDigest}, and merges {@code confirmation} (request
     * field → value) into the materialize body. Workflow needs a second confirmation, the operation digest,
     * which the generic panel cannot know about otherwise.</p>
     */
    public record WorkflowDraftPreviewView(String status, String routeProductCode, String targetProductTypeId,
                                           List<AssemblyBlocker> blockers, WorkflowDraftPreview compilation,
                                           DraftOperationPreview operation, String previewDigest,
                                           java.util.Map<String, String> confirmation) {
    }

    private final CanonicalFactoryBlueprintService blueprints;
    private final WorkflowCompileRequestAssembler assembler;
    private final FactoryWorkflowDraftMaterializationService service;
    private final FactoryWorkflowDraftPreviewCompiler compiler;
    private final ProductProcessWorkflowRepository workflows;
    private final FactoryWorkflowDraftMaterializationLedgerRepository ledger;

    public WorkflowDraftEntry(ObjectMapper mapper, CanonicalFactoryBlueprintService blueprints,
                              WorkflowCompileRequestAssembler assembler,
                              FactoryWorkflowDraftMaterializationService service,
                              ProductProcessWorkflowValidator validator, UnitContractService unitContractService,
                              ProductProcessWorkflowRepository workflows,
                              FactoryWorkflowDraftMaterializationLedgerRepository ledger) {
        super(mapper, WorkflowDraftRequest.class);
        this.blueprints = blueprints;
        this.assembler = assembler;
        this.service = service;
        // The same construction as the service's own compiler, so the graph shown here is the one it recompiles.
        this.compiler = new FactoryWorkflowDraftPreviewCompiler(validator, new UnitContractRuleUnitResolver(unitContractService));
        this.workflows = workflows;
        this.ledger = ledger;
    }

    @Override
    public String capabilityId() {
        return CAPABILITY_ID;
    }

    @Override
    public String requiredPermission() {
        return FactoryCapabilityAdapterContracts.b25Defaults().require(CAPABILITY_ID).requiredPermission();
    }

    @Override
    protected Object preview(WorkflowDraftRequest request) {
        BlueprintResponse blueprint = requireCurrentBlueprint(request);
        Assembly assembly = assembler.assemble(request.factoryId(), blueprint, request.routeProductCode(),
                request.entryInputs(), request.extraInputs(), request.wipOutputs());
        String productId = assembly.facts().product() == null ? null : assembly.facts().product().getId();
        if (assembly.blocked()) {
            return new WorkflowDraftPreviewView("BLOCKED", request.routeProductCode(), productId,
                    assembly.blockers(), null, null, null, java.util.Map.of());
        }
        CompileRequest compile = assembly.request();
        // Shown with the server's current DRAFT, as the service will compile it; not sent to the service.
        ExistingDraft existing = assembler.currentDraft(request.factoryId(), productId)
                .map(dto -> new ExistingDraft(dto, dto.getLockVersion(), dto.getRevisionHash())).orElse(null);
        WorkflowDraftPreview compilation = compiler.compile(new CompileRequest(compile.factoryId(), compile.sourceDigest(),
                compile.catalogVersion(), compile.catalogDigest(), compile.unitCatalogDigest(), compile.productLookupKey(),
                compile.route(), compile.catalog(), compile.bomInputs(), compile.wipBindings(), existing));
        DraftOperationPreview operation = service.preview(new DraftMaterializationRequest(PREVIEW_IDEMPOTENCY_KEY,
                "", "", request.mode(), request.expectedLockVersion(), request.expectedRevisionHash(),
                PREVIEW_OPERATOR, compile, List.of()));
        return new WorkflowDraftPreviewView(operation.status(), request.routeProductCode(), productId, List.of(),
                compilation, operation, operation.previewDigest(),
                operation.operationDigest() == null ? java.util.Map.of()
                        : java.util.Map.of("confirmedOperationDigest", operation.operationDigest()));
    }

    @Override
    protected Object materialize(WorkflowDraftRequest request, DraftOperator operator) {
        BlueprintResponse blueprint = requireCurrentBlueprint(request);
        Assembly assembly = assembler.assemble(request.factoryId(), blueprint, request.routeProductCode(),
                request.entryInputs(), request.extraInputs(), request.wipOutputs());
        if (assembly.blocked()) {
            AssemblyBlocker first = assembly.blockers().getFirst();
            throw new BusinessException(409, assembly.blockers().stream().map(AssemblyBlocker::message)
                    .collect(Collectors.joining("；")))
                    .withCode(first.code()).withHint(first.nextStep()).withHintTarget("routeProductCode");
        }
        // bomTargets stay empty: a BOM DRAFT pins this Workflow afterwards through its own entry.
        return service.materialize(new DraftMaterializationRequest(request.idempotencyKey(),
                request.confirmedPreviewDigest(), request.confirmedOperationDigest(), request.mode(),
                request.expectedLockVersion(), request.expectedRevisionHash(), operator.userId(),
                operator.actor().type(),
                assembly.request(), List.of()));
    }

    @Override
    public Object readBack(String factoryId, String draftId) {
        // FactoryWorkflowDraftMaterializationService has no readback; the receipt of materialize carries
        // the persisted revision, and the editor reads the DRAFT through its own API.
        throw new BusinessException(404, "这类建厂草稿暂不提供回读")
                .withCode("BLUEPRINT_DRAFT_READBACK_UNSUPPORTED");
    }

    /**
     * The current Workflow DRAFT of every product route of the scope's blueprint whose product has a
     * completed materialization in the service's idempotency ledger.
     *
     * <p>⚠️ Scope semantics differ from the other capabilities: the ledger
     * ({@code factory_workflow_draft_materialization_ledger}) has no blueprint/version/source-digest
     * columns, and the DRAFT lives in the live {@code product_process_workflows} table. So "written in
     * this lineage" is approximated as: the scope is the factory's current blueprint (otherwise nothing is
     * listed), the product is one of its routes, the product has a current DRAFT, and the ledger shows a
     * completed materialization for that product. A DRAFT written from an earlier blueprint version for the
     * same product is listed too, and an edit in the Workflow editor after the write is not distinguished.
     * {@code desiredDigest} is the preview digest of the product's latest completed materialization.</p>
     */
    @Override
    public Optional<List<WrittenDraft>> written(WrittenScope scope) {
        BlueprintResponse blueprint = blueprints.current(scope.factoryId());
        if (blueprint == null || !Objects.equals(scope.blueprintId(), blueprint.getId())
                || blueprint.getVersion() == null || scope.blueprintVersion() != blueprint.getVersion()
                || blueprint.getSourcePayload() == null
                || !Objects.equals(scope.sourceDigest(), BlueprintSourceDigest.of(blueprint))) {
            return Optional.of(List.of());
        }
        List<WrittenDraft> rows = new ArrayList<>();
        List<RouteGraph> routes = blueprint.getRouteGraphs() == null ? List.of() : blueprint.getRouteGraphs();
        for (RouteGraph route : routes) {
            if (route == null || route.productCode() == null || route.productCode().isBlank()) continue;
            ProductType product = assembler.resolve(scope.factoryId(), blueprint, route.productCode()).product();
            if (product == null) continue;
            Optional<FactoryWorkflowDraftMaterializationLedger> completed = ledger
                    .findByFactoryIdAndProductTypeIdAndCompletionStateOrderByCreatedAtDesc(
                            scope.factoryId(), product.getId(), "COMPLETED")
                    .stream().findFirst();
            Optional<ProductProcessWorkflow> draft = workflows.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                    scope.factoryId(), product.getId(), ProductProcessWorkflow.Status.DRAFT);
            if (completed.isEmpty() || draft.isEmpty()) continue;
            rows.add(new WrittenDraft(String.valueOf(draft.get().getId()), route.productCode(),
                    "Workflow 草稿 " + product.getName() + "（第 " + draft.get().getDefinitionVersion() + " 版）",
                    draft.get().getStatus().name(), completed.get().getPreviewDigest(), draft.get().getCreatedAt()));
        }
        rows.sort(Comparator.comparing(WrittenDraft::createdAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(WrittenDraft::draftId, Comparator.reverseOrder()));
        return Optional.of(List.copyOf(rows));
    }

    /** The body must name the factory's current DRAFT/VALIDATED blueprint, version and source digest. */
    private BlueprintResponse requireCurrentBlueprint(WorkflowDraftRequest request) {
        BlueprintResponse blueprint = blueprints.current(request.factoryId());
        if (blueprint == null || !Objects.equals(request.blueprintId(), blueprint.getId())
                || blueprint.getVersion() == null || request.blueprintVersion() != blueprint.getVersion()
                || !CURRENT_BLUEPRINT_STATES.contains(blueprint.getLifecycleState())
                || blueprint.getSourcePayload() == null) {
            throw new BusinessException(409, "请求里的蓝图不是本厂当前处于草稿或已校验状态的蓝图版本，请刷新蓝图后重新预填")
                    .withCode("WORKFLOW_DRAFT_BLUEPRINT_NOT_CURRENT").withHintTarget("blueprintVersion").withHint("请刷新建厂预览，核对草稿版本后重新确认操作");
        }
        if (!Objects.equals(request.sourceDigest(), BlueprintSourceDigest.of(blueprint))) {
            throw new BusinessException(409, "请求的蓝图血缘(sourceDigest)与当前蓝图不一致，蓝图内容已变化，请重新预填")
                    .withCode("WORKFLOW_DRAFT_SOURCE_DIGEST_MISMATCH").withHintTarget("sourceDigest").withHint("请刷新建厂预览，核对草稿版本后重新确认操作");
        }
        return blueprint;
    }
}
