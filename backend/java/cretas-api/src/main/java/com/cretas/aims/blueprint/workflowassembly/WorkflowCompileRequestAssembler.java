package com.cretas.aims.blueprint.workflowassembly;

import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.FactoryRouteGraphPrototype.EdgeType;
import com.cretas.aims.blueprint.canonical.FactoryRouteGraphPrototype.RouteEdge;
import com.cretas.aims.blueprint.canonical.FactoryRouteGraphPrototype.RouteGraph;
import com.cretas.aims.blueprint.canonical.FactoryRouteGraphPrototype.RouteNode;
import com.cretas.aims.blueprint.canonical.FactoryRouteIntentCompiler.RouteCompilation;
import com.cretas.aims.blueprint.canonical.FactoryRouteIntentCompiler.RouteEdgeIntent;
import com.cretas.aims.blueprint.draftentry.BlueprintSourceDigest;
import com.cretas.aims.blueprint.rule.UnitContractRuleUnitResolver;
import com.cretas.aims.blueprint.workflowassembly.WorkflowAssemblyModels.Assembly;
import com.cretas.aims.blueprint.workflowassembly.WorkflowAssemblyModels.AssemblyBlocker;
import com.cretas.aims.blueprint.workflowassembly.WorkflowAssemblyModels.EntryInputChoice;
import com.cretas.aims.blueprint.workflowassembly.WorkflowAssemblyModels.RouteFacts;
import com.cretas.aims.blueprint.workflowassembly.WorkflowAssemblyModels.WipOutputChoice;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowPreviewModels.BomInputSlot;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowPreviewModels.CompileRequest;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowPreviewModels.MaterialKind;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowPreviewModels.MaterialRef;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowPreviewModels.ProcessRef;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowPreviewModels.ResolutionCatalog;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowPreviewModels.WipBinding;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest.ConversionItem;
import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.enums.ProductCategory;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.service.ProductProcessWorkflowService;
import com.cretas.aims.service.unit.UnitContractService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the {@link CompileRequest} of a Workflow DRAFT on the server — the step
 * {@code FactoryWorkflowDraftMaterializationService}'s javadoc requires ("construct this command from
 * authoritative Blueprint state rather than accept an arbitrary client-authored graph").
 *
 * <p>Sources, field by field — nothing comes from the request body except the user's two choices:</p>
 * <ul>
 *   <li>route: the current blueprint's {@code routeGraphs[productCode]}; only a pure FORWARD chain is
 *       assembled (first phase), anything else is {@code WORKFLOW_ROUTE_NOT_LINEAR};</li>
 *   <li>product: the live {@code product_types} row of this factory whose code is the route's product code
 *       and whose category is a finished good; its name must equal the route's product name verbatim;</li>
 *   <li>processes: for every route node, the one active, unmerged live work process whose name equals
 *       the node label verbatim;</li>
 *   <li>materials: the raw materials and semi-finished products the user chose, re-read from the live
 *       tables of this factory (an id the user sends is a <em>key</em>, never a fact);</li>
 *   <li>catalog: only the processes and materials this request uses, every lookup key is the row id,
 *       version {@link #CATALOG_VERSION}, digest = SHA-256 of the sorted content — two assemblies of the
 *       same facts give the same digest, any change to a used row changes it;</li>
 *   <li>sourceDigest: {@link BlueprintSourceDigest}; unitCatalogDigest: the live unit catalog.</li>
 * </ul>
 *
 * <p>⚠️ Promotion records ({@code master_data_draft_promotions}) live in cretas-api, which this module
 * cannot reach; the product is therefore found by code in the live table alone, and no
 * {@code promotion:} evidence pointer is written. A live row that was created some other way is
 * accepted the same — what the compiler needs is a live row, not its history.</p>
 *
 * <p>The existing DRAFT is deliberately <b>not</b> put into the request: the materialization service
 * replaces it with its own read, and it is part of that service's idempotency digest — a request that
 * carried it would no longer replay after its own write.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class WorkflowCompileRequestAssembler {

    public static final String CATALOG_VERSION = "factory-workflow-catalog/blueprint-v1";

    /** product_types categories that are a finished good for the Workflow compiler. */
    public static final Set<String> FINISHED_GOOD_CATEGORIES = Set.of(
            ProductCategory.FINISHED_PRODUCT, ProductCategory.CONTRACT_MANUFACTURING,
            ProductCategory.CUSTOMER_MATERIAL, ProductCategory.DISH, ProductCategory.COMBO);

    private static final JsonMapper CANONICAL = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private final WorkProcessRepository processes;
    private final RawMaterialTypeRepository rawMaterials;
    private final ProductTypeRepository products;
    private final ProductProcessWorkflowRepository workflows;
    private final ProductProcessWorkflowService workflowService;
    private final UnitContractService unitContractService;

    public WorkflowCompileRequestAssembler(WorkProcessRepository processes, RawMaterialTypeRepository rawMaterials,
                                           ProductTypeRepository products, ProductProcessWorkflowRepository workflows,
                                           ProductProcessWorkflowService workflowService,
                                           UnitContractService unitContractService) {
        this.processes = processes;
        this.rawMaterials = rawMaterials;
        this.products = products;
        this.workflows = workflows;
        this.workflowService = workflowService;
        this.unitContractService = unitContractService;
    }

    // ------------------------------------------------------------------ facts

    /**
     * Resolves one blueprint route against the live tables. Blockers are collected, not short-circuited,
     * so the user sees every missing precondition at once.
     *
     * @throws BusinessException 400 when the blueprint has no route for {@code routeProductCode}
     */
    public RouteFacts resolve(String factoryId, BlueprintResponse blueprint, String routeProductCode) {
        List<RouteGraph> routes = blueprint.getRouteGraphs() == null ? List.of() : blueprint.getRouteGraphs();
        String code = routeProductCode == null ? "" : routeProductCode;
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < routes.size(); i++) {
            if (routes.get(i) != null && code.equals(routes.get(i).productCode())) indexes.add(i);
        }
        if (code.isBlank() || indexes.isEmpty()) {
            throw choice("routeProductCode", "蓝图里没有产品“" + code + "”的工艺路线，请按预填结果选择蓝图里的产品");
        }
        int routeIndex = indexes.getFirst();
        RouteGraph graph = routes.get(routeIndex);
        List<AssemblyBlocker> blockers = new ArrayList<>();
        if (indexes.size() > 1) {
            blockers.add(new AssemblyBlocker("WORKFLOW_ROUTE_AMBIGUOUS",
                    "蓝图里产品“" + code + "”有 " + indexes.size() + " 条工艺路线，无法确定按哪一条写 Workflow 草稿",
                    "回到建厂访谈，每个产品只保留一条工艺路线"));
        }

        ProductType product = resolveProduct(factoryId, code, graph, blockers);
        Map<String, WorkProcess> processByNode = resolveProcesses(factoryId, graph, blockers);
        List<String> chain = linearChain(graph);
        if (chain.isEmpty()) {
            blockers.add(new AssemblyBlocker("WORKFLOW_ROUTE_NOT_LINEAR",
                    "产品“" + graph.productName() + "”的路线不是纯顺序路线（有分支、合流、返工连线，或路线本身不完整）；"
                            + "首期只支持一道接一道的纯顺序路线写 Workflow 草稿",
                    "把路线改成纯顺序后重新预填；分支/合流/返工路线要等受控语义上线后再写"));
        }
        return new RouteFacts(code, routeIndex, graph, product, processByNode, chain, blockers);
    }

    private ProductType resolveProduct(String factoryId, String code, RouteGraph graph, List<AssemblyBlocker> blockers) {
        List<ProductType> sameCode = products.findVisibleByFactoryIdAndIsActiveTrue(factoryId).stream()
                .filter(row -> code.equals(row.getCode())).toList();
        List<ProductType> finished = sameCode.stream()
                .filter(row -> FINISHED_GOOD_CATEGORIES.contains(text(row.getProductCategory()))).toList();
        if (finished.isEmpty()) {
            String seen = sameCode.isEmpty() ? "本厂正式产品表里没有编码为“" + code + "”的启用产品"
                    : "本厂正式产品表里编码“" + code + "”的产品类别是 "
                    + sameCode.stream().map(row -> text(row.getProductCategory())).distinct().collect(Collectors.joining("、"))
                    + "，不是成品";
            blockers.add(new AssemblyBlocker("WORKFLOW_PRODUCT_NOT_LIVE",
                    seen + "，Workflow 草稿只能挂在正式成品上",
                    "先在产品草稿里确认产品“" + graph.productName() + "”（类别选成品）并转正，再回来写 Workflow 草稿"));
            return null;
        }
        if (finished.size() > 1) {
            blockers.add(new AssemblyBlocker("WORKFLOW_PRODUCT_AMBIGUOUS",
                    "本厂正式产品表里编码“" + code + "”有 " + finished.size() + " 个启用的成品，无法确定挂在哪一个上",
                    "先在产品管理里停用或改码重复的成品"));
            return null;
        }
        ProductType product = finished.getFirst();
        if (!Objects.equals(product.getName(), graph.productName())) {
            blockers.add(new AssemblyBlocker("WORKFLOW_PRODUCT_NAME_MISMATCH",
                    "正式产品“" + code + "”的名称是“" + product.getName() + "”，蓝图路线上的产品名称是“"
                            + graph.productName() + "”，两边必须逐字一致",
                    "改正式产品名称或改蓝图里的产品名称，使两边一致后重新预填"));
        }
        return product;
    }

    private Map<String, WorkProcess> resolveProcesses(String factoryId, RouteGraph graph, List<AssemblyBlocker> blockers) {
        Map<String, List<WorkProcess>> byName = new HashMap<>();
        for (WorkProcess process : processes.findByFactoryIdAndIsActiveTrueAndMergedIntoIdIsNullOrderByProcessNameAsc(factoryId)) {
            byName.computeIfAbsent(process.getProcessName(), ignored -> new ArrayList<>()).add(process);
        }
        Map<String, WorkProcess> result = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        List<String> ambiguous = new ArrayList<>();
        for (RouteNode node : graph.nodes()) {
            List<WorkProcess> matches = byName.getOrDefault(node.label(), List.of());
            if (matches.size() == 1) {
                result.put(node.id(), matches.getFirst());
            } else if (matches.isEmpty()) {
                missing.add(node.id() + "（" + node.label() + "）");
            } else {
                ambiguous.add(node.id() + "（" + node.label() + "，" + matches.size() + " 道同名）");
            }
        }
        if (!missing.isEmpty()) {
            blockers.add(new AssemblyBlocker("WORKFLOW_PROCESS_NOT_LIVE",
                    "路线节点 " + String.join("、", missing) + " 在本厂正式工序里找不到名称逐字相同、启用且未归并的工序",
                    "先在工序草稿里确认这些工序并转正，再回来写 Workflow 草稿"));
        }
        if (!ambiguous.isEmpty()) {
            blockers.add(new AssemblyBlocker("WORKFLOW_PROCESS_AMBIGUOUS",
                    "路线节点 " + String.join("、", ambiguous) + " 在本厂正式工序里有多道同名工序，无法确定用哪一道",
                    "先在工序管理里合并同名工序"));
        }
        return result;
    }

    /**
     * Node ids in flow order when the route is a pure FORWARD chain (one entry, one terminal, every node at
     * most one in and one out edge, all nodes reached); otherwise empty.
     */
    static List<String> linearChain(RouteGraph graph) {
        if (graph == null || graph.validation() == null || !graph.validation().valid() || graph.nodes().isEmpty()
                || graph.edges().stream().anyMatch(edge -> edge.type() != EdgeType.FORWARD)
                || graph.edges().size() != graph.nodes().size() - 1
                || graph.entryNodeIds().size() != 1 || graph.terminalNodeIds().size() != 1) {
            return List.of();
        }
        Map<String, String> next = new HashMap<>();
        Map<String, Integer> incoming = new HashMap<>();
        for (RouteEdge edge : graph.edges()) {
            if (next.putIfAbsent(edge.fromNodeId(), edge.toNodeId()) != null) return List.of();
            if (incoming.merge(edge.toNodeId(), 1, Integer::sum) > 1) return List.of();
        }
        List<String> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String current = graph.entryNodeIds().getFirst(); current != null; current = next.get(current)) {
            if (!seen.add(current)) return List.of();
            chain.add(current);
        }
        return chain.size() == graph.nodes().size() ? chain : List.of();
    }

    /** Live raw materials of the factory, by name then id (the options a user may choose from). */
    public List<RawMaterialType> liveRawMaterials(String factoryId) {
        return rawMaterials.findByFactoryIdAndIsActive(factoryId, true).stream()
                .sorted(Comparator.comparing((RawMaterialType row) -> text(row.getName())).thenComparing(RawMaterialType::getId))
                .toList();
    }

    /** Live semi-finished products of the factory, by name then id. */
    public List<ProductType> liveSemiFinished(String factoryId) {
        return products.findVisibleByFactoryIdAndIsActiveTrue(factoryId).stream()
                .filter(row -> ProductCategory.SEMI_FINISHED.equals(text(row.getProductCategory())))
                .sorted(Comparator.comparing((ProductType row) -> text(row.getName())).thenComparing(ProductType::getId))
                .toList();
    }

    /**
     * The product's current Workflow DRAFT, read the same way the materialization service reads it
     * (latest DRAFT row, then its editor definition), so a lock version / revision hash taken from here is
     * the one the service will compare against.
     */
    public Optional<ProductProcessWorkflowDTO> currentDraft(String factoryId, String productTypeId) {
        return workflows.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                        factoryId, productTypeId, ProductProcessWorkflow.Status.DRAFT)
                .flatMap(row -> workflowService.getEditorDefinition(factoryId, row.getProductTypeId()))
                .filter(dto -> "DRAFT".equals(dto.getStatus()));
    }

    // ------------------------------------------------------------------ assembly

    /**
     * @return the route's blockers, or the server-built compile request
     * @throws BusinessException 400 {@code WORKFLOW_DRAFT_CHOICE_INVALID} with the field path in
     *                           {@code hintTarget} when a choice is not one the route and live tables allow
     */
    public Assembly assemble(String factoryId, BlueprintResponse blueprint, String routeProductCode,
                             List<EntryInputChoice> entryInputs, List<WipOutputChoice> wipOutputs) {
        return assemble(factoryId, blueprint, routeProductCode, entryInputs, List.of(), wipOutputs);
    }

    /**
     * @param extraInputs raw materials a process after the entry also takes (seasoning, packaging, ...); the
     *                    entry's own rows stay in {@code entryInputs}
     */
    public Assembly assemble(String factoryId, BlueprintResponse blueprint, String routeProductCode,
                             List<EntryInputChoice> entryInputs, List<EntryInputChoice> extraInputs,
                             List<WipOutputChoice> wipOutputs) {
        RouteFacts facts = resolve(factoryId, blueprint, routeProductCode);
        if (!facts.ready()) {
            return new Assembly(facts, null);
        }
        Map<String, RawMaterialType> raws = liveRawMaterials(factoryId).stream()
                .collect(Collectors.toMap(RawMaterialType::getId, row -> row, (left, right) -> left));
        Map<String, ProductType> semis = liveSemiFinished(factoryId).stream()
                .collect(Collectors.toMap(ProductType::getId, row -> row, (left, right) -> left));
        List<EntryInputChoice> entries = entryInputs == null ? List.of() : entryInputs;
        List<WipOutputChoice> wips = wipOutputs == null ? List.of() : wipOutputs;

        String routeRef = "blueprint:" + blueprint.getId() + ":v" + blueprint.getVersion()
                + ":routeGraphs[" + facts.productCode() + "]";
        List<ConversionItem> conversions = blueprint.getSourcePayload() == null
                || blueprint.getSourcePayload().getSuggestedData() == null
                || blueprint.getSourcePayload().getSuggestedData().getConversionRates() == null
                ? List.of() : blueprint.getSourcePayload().getSuggestedData().getConversionRates();

        // ---- entry inputs: the entry node, a live raw material, no row twice
        if (entries.isEmpty()) {
            throw choice("entryInputs", "至少要选一种投入第一道工序“" + facts.processName(facts.entryNodeId()) + "”的原料");
        }
        List<BomInputSlot> bomInputs = new ArrayList<>();
        Map<String, RawMaterialType> usedRaws = new LinkedHashMap<>();
        Set<String> seenEntries = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            EntryInputChoice choice = entries.get(i);
            String at = "entryInputs[" + i + "]";
            if (choice == null) throw choice(at, "这一行是空的，请删掉或补齐");
            if (!facts.entryNodeId().equals(choice.routeNodeId())) {
                throw choice(at + ".routeNodeId", "原料只能投入入口工序“" + facts.processName(facts.entryNodeId())
                        + "”（节点 " + facts.entryNodeId() + "）");
            }
            RawMaterialType raw = raws.get(choice.rawMaterialId());
            if (raw == null) {
                throw choice(at + ".rawMaterialId", "所选原料不是本厂已启用的正式原料，请从预填给出的原料里选");
            }
            if (!seenEntries.add(choice.routeNodeId() + "|" + raw.getId())) {
                throw choice(at, "同一种原料选了两次");
            }
            usedRaws.putIfAbsent(raw.getId(), raw);
            List<String> evidence = new ArrayList<>(List.of(
                    routeRef + ".nodes[" + choice.routeNodeId() + "]", "user-choice:" + at));
            for (int j = 0; j < conversions.size(); j++) {
                ConversionItem conversion = conversions.get(j);
                if (conversion != null && facts.productCode().equals(conversion.getToCode())
                        && Objects.equals(raw.getCode(), conversion.getFromCode())) {
                    evidence.add("blueprint:" + blueprint.getId() + ":v" + blueprint.getVersion()
                            + ":sourcePayload.suggestedData.conversionRates[" + j + "]");
                }
            }
            bomInputs.add(new BomInputSlot(choice.routeNodeId(), raw.getId(), raw.getUnit(), evidence));
        }

        // ---- extra inputs: a node after the entry, a live raw material, no (node, material) twice
        List<EntryInputChoice> extras = extraInputs == null ? List.of() : extraInputs;
        for (int i = 0; i < extras.size(); i++) {
            EntryInputChoice choice = extras.get(i);
            String at = "extraInputs[" + i + "]";
            if (choice == null) throw choice(at, "这一行是空的，请删掉或补齐");
            if (!facts.chain().contains(choice.routeNodeId()) || facts.entryNodeId().equals(choice.routeNodeId())) {
                throw choice(at + ".routeNodeId", "追加的原料只能投入入口以外的工序，可选节点："
                        + facts.chain().stream().skip(1).map(id -> id + "（" + facts.processName(id) + "）")
                        .collect(Collectors.joining("、")) + "；入口工序的原料请在入口原料里选");
            }
            RawMaterialType raw = raws.get(choice.rawMaterialId());
            if (raw == null) {
                throw choice(at + ".rawMaterialId", "所选原料不是本厂已启用的正式原料，请从预填给出的原料里选");
            }
            if (!seenEntries.add(choice.routeNodeId() + "|" + raw.getId())) {
                throw choice(at, "工序“" + facts.processName(choice.routeNodeId()) + "”已经投入了“" + raw.getName() + "”");
            }
            usedRaws.putIfAbsent(raw.getId(), raw);
            bomInputs.add(new BomInputSlot(choice.routeNodeId(), raw.getId(), raw.getUnit(), List.of(
                    routeRef + ".nodes[" + choice.routeNodeId() + "]", "user-choice:" + at)));
        }

        // ---- WIP outputs: exactly one per non-terminal node, live semi-finished, all different
        List<String> producers = facts.nonTerminalNodeIds();
        List<WipBinding> wipBindings = new ArrayList<>();
        Map<String, ProductType> usedSemis = new LinkedHashMap<>();
        Set<String> coveredNodes = new HashSet<>();
        for (int i = 0; i < wips.size(); i++) {
            WipOutputChoice choice = wips.get(i);
            String at = "wipOutputs[" + i + "]";
            if (choice == null) throw choice(at, "这一行是空的，请删掉或补齐");
            if (!producers.contains(choice.routeNodeId())) {
                throw choice(at + ".routeNodeId", "只有非末道工序产出半成品，可选节点："
                        + producers.stream().map(id -> id + "（" + facts.processName(id) + "）").collect(Collectors.joining("、")));
            }
            if (!coveredNodes.add(choice.routeNodeId())) {
                throw choice(at + ".routeNodeId", "工序“" + facts.processName(choice.routeNodeId()) + "”的半成品选了两次");
            }
            ProductType semi = semis.get(choice.semiFinishedProductId());
            if (semi == null) {
                throw choice(at + ".semiFinishedProductId", "所选半成品不是本厂已启用的正式半成品，请从预填给出的半成品里选");
            }
            if (usedSemis.putIfAbsent(semi.getId(), semi) != null) {
                throw choice(at + ".semiFinishedProductId", "每道工序的半成品必须各不相同，“" + semi.getName() + "”已经被另一道工序用了");
            }
            wipBindings.add(new WipBinding(choice.routeNodeId(), semi.getId(), semi.getUnit(), List.of(
                    routeRef + ".nodes[" + choice.routeNodeId() + "]", "user-choice:" + at)));
        }
        List<String> uncovered = producers.stream().filter(id -> !coveredNodes.contains(id)).toList();
        if (!uncovered.isEmpty()) {
            throw choice("wipOutputs", "这些工序还没选产出的半成品："
                    + uncovered.stream().map(id -> id + "（" + facts.processName(id) + "）").collect(Collectors.joining("、")));
        }

        // ---- catalog: only what this request uses, id as the lookup key
        ProductType product = facts.product();
        List<ProcessRef> processRefs = facts.processByNode().values().stream()
                .map(process -> new ProcessRef(process.getId(), factoryId, process.getProcessName(), true))
                .distinct()
                .sorted(Comparator.comparing(ProcessRef::id)).toList();
        List<MaterialRef> materialRefs = new ArrayList<>();
        materialRefs.add(new MaterialRef(product.getId(), factoryId, product.getName(), MaterialKind.FINISHED_GOOD,
                product.getUnit(), true));
        usedRaws.values().forEach(raw -> materialRefs.add(new MaterialRef(raw.getId(), factoryId, raw.getName(),
                MaterialKind.RAW_MATERIAL, raw.getUnit(), true)));
        usedSemis.values().forEach(semi -> materialRefs.add(new MaterialRef(semi.getId(), factoryId, semi.getName(),
                MaterialKind.SEMI_FINISHED, semi.getUnit(), true)));
        materialRefs.sort(Comparator.comparing((MaterialRef ref) -> ref.kind().name()).thenComparing(MaterialRef::id));
        String catalogDigest = catalogDigest(factoryId, processRefs, materialRefs);
        ResolutionCatalog catalog = new ResolutionCatalog(factoryId, CATALOG_VERSION, catalogDigest, processRefs, materialRefs);

        CompileRequest request = new CompileRequest(factoryId, BlueprintSourceDigest.of(blueprint), CATALOG_VERSION,
                catalogDigest, new UnitContractRuleUnitResolver(unitContractService).catalogDigest(factoryId),
                product.getId(), route(facts, routeRef), catalog, bomInputs, wipBindings, null);
        return new Assembly(facts, request);
    }

    /** Only built for a pure FORWARD chain, so READY is what the route compiler itself would say. */
    private static RouteCompilation route(RouteFacts facts, String routeRef) {
        RouteGraph graph = facts.graph();
        Map<String, String> labels = graph.nodes().stream()
                .collect(Collectors.toMap(RouteNode::id, RouteNode::label, (left, right) -> left));
        List<RouteEdgeIntent> intents = graph.edges().stream()
                .map(edge -> new RouteEdgeIntent(edge.fromNodeId(), labels.get(edge.fromNodeId()), edge.toNodeId(),
                        labels.get(edge.toNodeId()), edge.type(), "", ""))
                .toList();
        return new RouteCompilation(facts.routeIndex() + 1, graph.productName(), List.of("仅顺序"), "", "READY",
                "COMPILED_LINEAR", "NOT_PUBLISHED", true, graph, intents, List.of(), List.of(routeRef));
    }

    static String catalogDigest(String factoryId, List<ProcessRef> processRefs, List<MaterialRef> materialRefs) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("version", CATALOG_VERSION);
        content.put("factoryId", factoryId);
        content.put("processes", processRefs);
        content.put("materials", materialRefs);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(CANONICAL.writeValueAsString(content).getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("catalog is not serializable", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BusinessException choice(String path, String message) {
        return new BusinessException(400, "字段 " + path + "：" + message)
                .withCode("WORKFLOW_DRAFT_CHOICE_INVALID").withHintTarget(path);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
