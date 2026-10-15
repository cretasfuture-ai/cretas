package com.cretas.aims.blueprint.workflowpreview;

import com.cretas.aims.blueprint.canonical.FactoryRouteGraphPrototype.EdgeType;
import com.cretas.aims.blueprint.canonical.FactoryRouteGraphPrototype.RouteEdge;
import com.cretas.aims.blueprint.canonical.FactoryRouteGraphPrototype.RouteNode;
import com.cretas.aims.blueprint.rule.FactoryRuleUnitResolver;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowPreviewModels.BomInputSlot;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowPreviewModels.BusinessEdge;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowPreviewModels.BusinessNode;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowPreviewModels.BusinessSnapshot;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowPreviewModels.CompileRequest;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowPreviewModels.DiffAction;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowPreviewModels.EntityDiff;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowPreviewModels.MaterialKind;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowPreviewModels.MaterialRef;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowPreviewModels.PreviewIssue;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowPreviewModels.ProcessRef;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowPreviewModels.Severity;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowPreviewModels.ValidationReceipt;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowPreviewModels.WipBinding;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowPreviewModels.WorkflowChangeSet;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowPreviewModels.WorkflowDraftPreview;
import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Pure B19 route to formal Workflow bipartite graph compiler. It never persists a draft. */
public final class FactoryWorkflowDraftPreviewCompiler {

    private final ProductProcessWorkflowValidator formalValidator;
    private final FactoryRuleUnitResolver units;
    private final ObjectMapper canonicalMapper;

    public FactoryWorkflowDraftPreviewCompiler(
            ProductProcessWorkflowValidator formalValidator,
            FactoryRuleUnitResolver units) {
        this.formalValidator = formalValidator;
        this.units = units;
        this.canonicalMapper = new ObjectMapper().findAndRegisterModules()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public WorkflowDraftPreview compile(CompileRequest request) {
        List<PreviewIssue> issues = new ArrayList<>();
        validateEnvelope(request, issues);
        if (request == null || request.route() == null || request.catalog() == null) {
            return blocked(request, issues, null, null, null);
        }
        if (hasBlocking(issues)) return blocked(request, issues, null, null, null);

        var route = request.route();
        List<RouteNode> routeNodes = route.graph() == null ? List.of() : route.graph().nodes();
        List<RouteEdge> materialEdges = route.graph() == null ? List.of() : route.graph().edges().stream()
                .filter(edge -> edge.type() != EdgeType.REWORK)
                .sorted(Comparator.comparing(RouteEdge::fromNodeId)
                        .thenComparing(RouteEdge::toNodeId).thenComparing(edge -> edge.type().name()))
                .toList();
        Map<String, RouteNode> routeById = routeNodes.stream().collect(Collectors.toMap(
                RouteNode::id, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        validateRouteShape(routeNodes, route.graph() == null ? List.of() : route.graph().edges(), issues);

        Map<String, ProcessRef> processes = new LinkedHashMap<>();
        for (RouteNode node : routeNodes) {
            ProcessRef resolved = resolveProcess(request, node, issues);
            if (resolved != null) processes.put(node.id(), resolved);
        }
        MaterialRef product = resolveMaterial(request, request.productLookupKey(),
                MaterialKind.FINISHED_GOOD, "Resolve", "product", issues);
        if (product != null) compatibleUnit(request.factoryId(), product.canonicalUnit(), product, issues, "product");
        if (product != null && !route.productName().equals(product.name())) issues.add(block(
                "PRODUCT_ROUTE_MISMATCH", "Resolve", "product", "路线产品名称与目标 SKU 不一致",
                "重新选择与路线产品一致的成品 SKU"));

        Map<String, List<RouteEdge>> incoming = group(materialEdges, RouteEdge::toNodeId);
        Map<String, List<RouteEdge>> outgoing = group(materialEdges, RouteEdge::fromNodeId);
        List<String> entries = routeNodes.stream().map(RouteNode::id)
                .filter(id -> incoming.getOrDefault(id, List.of()).isEmpty()).sorted().toList();
        List<String> terminals = routeNodes.stream().map(RouteNode::id)
                .filter(id -> outgoing.getOrDefault(id, List.of()).isEmpty()).sorted().toList();
        if (entries.size() != 1) issues.add(block("ENTRY_COUNT_INVALID", "Normalize", "route",
                "路线必须恰好有一个物料流入口，当前为 " + entries.size(), "修正 branch/merge 连线后重试"));
        if (terminals.size() != 1) issues.add(block("TERMINAL_COUNT_INVALID", "Normalize", "route",
                "路线必须恰好有一个物料流终点，当前为 " + terminals.size(), "修正 branch/merge 连线后重试"));

        Map<String, List<ResolvedMaterial>> bomByProcess = resolveBom(request, routeById, entries, issues);
        Map<String, ResolvedMaterial> wipByProducer = resolveWips(request, routeById, outgoing, issues);
        validateExistingDraftScope(request, product, issues);
        if (hasBlocking(issues)) return blocked(request, issues, null, null, null);

        Map<String, Integer> ranks = topologicalRanks(routeNodes, materialEdges, issues);
        if (hasBlocking(issues)) return blocked(request, issues, null, null, null);
        ProductProcessWorkflowDTO definition = buildDefinition(
                request, routeNodes, materialEdges, processes, product, bomByProcess,
                wipByProducer, incoming, outgoing, ranks);

        boolean formalValid = true;
        try {
            formalValidator.validateStructureComplete(definition);
        } catch (BusinessException invalid) {
            formalValid = false;
            issues.add(block("FORMAL_WORKFLOW_VALIDATION_FAILED", "Validate", "workflow",
                    invalid.getMessage(), "修正物料、端口、单位或连线后重新编译"));
        }
        ValidationReceipt validation = new ValidationReceipt(formalValid,
                ProductProcessWorkflowValidator.class.getSimpleName() + ".validateStructureComplete",
                List.of("material/process bipartite", "all ports uniquely connected", "acyclic definition",
                        "raw input and finished output present"), 0);
        if (!formalValid) return blocked(request, issues, definition, validation, null);

        String businessDigest = hash(Map.of(
                "compilerVersion", FactoryWorkflowPreviewModels.COMPILER_VERSION,
                "sourceDigest", request.sourceDigest(),
                "catalogDigest", request.catalogDigest(),
                "unitCatalogDigest", request.unitCatalogDigest(),
                "snapshot", businessSnapshot(definition)));
        String layoutDigest = hash(layoutSnapshot(definition));

        addControlIssues(route, issues);
        boolean controlBlocked = issues.stream().anyMatch(item -> item.code().startsWith("B23_"));
        WorkflowChangeSet changeSet = diff(request, definition, product.id(), businessDigest, controlBlocked);
        String status = controlBlocked ? "CONTROL_SEMANTICS_REQUIRED" : "PREVIEW_READY";
        return new WorkflowDraftPreview(null, null, status, request.sourceDigest(), businessDigest,
                null, layoutDigest, definition, changeSet, validation, issues,
                null, null, null, 0);
    }

    private void validateEnvelope(CompileRequest request, List<PreviewIssue> issues) {
        if (request == null) {
            issues.add(block("REQUEST_REQUIRED", "Resolve", "request", "编译请求不能为空", "提供锁定版本的请求"));
            return;
        }
        if (request.factoryId().isBlank() || request.sourceDigest().isBlank()) issues.add(block(
                "SOURCE_LOCK_REQUIRED", "Resolve", "request", "factoryId 和 sourceDigest 必须锁定", "回读当前 Blueprint 工件"));
        if (request.catalog() == null) {
            issues.add(block("CATALOG_REQUIRED", "Resolve", "catalog", "解析目录不能为空", "提供只读目录快照"));
        } else {
            if (!request.factoryId().equals(request.catalog().factoryId())) issues.add(block(
                    "CROSS_FACTORY_CATALOG", "Resolve", "catalog", "解析目录不属于目标工厂", "重新读取当前工厂目录"));
            if (!request.catalogVersion().equals(request.catalog().version())
                    || !request.catalogDigest().equals(request.catalog().digest())) issues.add(block(
                    "CATALOG_DRIFT", "Resolve", "catalog", "请求锁定的解析目录版本/摘要已漂移", "刷新并重新编译"));
        }
        if (request.unitCatalogDigest().isBlank()
                || !com.cretas.aims.blueprint.FactoryBlueprintSnapshotEquality.sameConfirmedValue(request.unitCatalogDigest(), units.catalogDigest(request.factoryId()))) issues.add(block(
                "UNIT_CATALOG_DRIFT", "Normalize", "units", "单位目录摘要为空或已漂移", "刷新单位目录并重新编译"));
        if (request.route() == null || !"READY".equals(request.route().status())
                || !request.route().topologyComplete() || request.route().graph() == null) issues.add(block(
                "ROUTE_NOT_READY", "Resolve", "route", "B19 路线必须是 READY 的完整拓扑", "回到路线确认卡补齐拓扑"));
    }

    private void validateRouteShape(
            List<RouteNode> nodes, List<RouteEdge> edges, List<PreviewIssue> issues) {
        if (nodes.isEmpty()) issues.add(block("ROUTE_NODES_REQUIRED", "Normalize", "route.nodes",
                "路线没有工序节点", "先确认至少一道工序"));
        Set<String> ids = new LinkedHashSet<>();
        for (RouteNode node : nodes) {
            if (node.id().isBlank() || node.label().isBlank() || !ids.add(node.id())) issues.add(block(
                    "ROUTE_NODE_INVALID", "Normalize", node.id(), "工序节点 ID/名称为空或重复", "修正路线节点"));
        }
        Set<String> edgeKeys = new LinkedHashSet<>();
        for (RouteEdge edge : edges) {
            String target = edge.fromNodeId() + "->" + edge.toNodeId();
            if (!ids.contains(edge.fromNodeId()) || !ids.contains(edge.toNodeId())) issues.add(block(
                    "ROUTE_EDGE_UNKNOWN_NODE", "Normalize", target, "路线连线引用未知工序", "修正路线连线"));
            if (edge.fromNodeId().equals(edge.toNodeId())) issues.add(block(
                    "ROUTE_SELF_EDGE", "Normalize", target, "路线不能包含自环", "修正下一工序或返工目标"));
            if (!edgeKeys.add(target + "|" + edge.type())) issues.add(block(
                    "ROUTE_EDGE_DUPLICATE", "Normalize", target, "路线包含重复连线", "删除重复连线"));
        }
    }

    private ProcessRef resolveProcess(CompileRequest request, RouteNode node, List<PreviewIssue> issues) {
        List<ProcessRef> matches = request.catalog().processes().stream()
                .filter(item -> item.id().equals(node.label()) || item.name().equals(node.label())).toList();
        List<ProcessRef> local = matches.stream().filter(ProcessRef::active)
                .filter(item -> request.factoryId().equals(item.factoryId())).toList();
        if (!matches.isEmpty() && local.isEmpty()) issues.add(block("PROCESS_CROSS_FACTORY_OR_INACTIVE", "Resolve", node.id(),
                "工序“" + node.label() + "”不属于当前工厂或已停用", "选择当前工厂活动工序"));
        else if (local.isEmpty()) issues.add(block("PROCESS_NOT_FOUND", "Resolve", node.id(),
                "未解析到工序“" + node.label() + "”", "在工序目录中创建或明确选择"));
        else if (local.size() > 1) issues.add(block("PROCESS_AMBIGUOUS", "Resolve", node.id(),
                "工序“" + node.label() + "”有多个候选", "人工选择唯一工序 ID"));
        return local.size() == 1 ? local.getFirst() : null;
    }

    private MaterialRef resolveMaterial(
            CompileRequest request, String lookup, MaterialKind expectedKind,
            String phase, String target, List<PreviewIssue> issues) {
        List<MaterialRef> matches = request.catalog().materials().stream()
                .filter(item -> item.id().equals(lookup) || item.name().equals(lookup)).toList();
        List<MaterialRef> local = matches.stream().filter(MaterialRef::active)
                .filter(item -> request.factoryId().equals(item.factoryId())).toList();
        if (lookup.isBlank()) issues.add(block("MATERIAL_LOOKUP_REQUIRED", phase, target,
                "物料/SKU 解析键不能为空", "选择明确物料"));
        else if (!matches.isEmpty() && local.isEmpty()) issues.add(block("MATERIAL_CROSS_FACTORY_OR_INACTIVE", phase, target,
                "物料“" + lookup + "”不属于当前工厂或已停用", "选择当前工厂活动物料"));
        else if (local.isEmpty()) issues.add(block("MATERIAL_NOT_FOUND", phase, target,
                "未解析到物料“" + lookup + "”", "先创建/选择权威物料或 SKU"));
        else if (local.size() > 1) issues.add(block("MATERIAL_AMBIGUOUS", phase, target,
                "物料“" + lookup + "”有多个候选", "人工选择唯一物料 ID"));
        else if (local.getFirst().kind() != expectedKind) issues.add(block("MATERIAL_KIND_MISMATCH", phase, target,
                "物料“" + lookup + "”类型不是 " + expectedKind, "选择正确物料类型"));
        return local.size() == 1 && local.getFirst().kind() == expectedKind ? local.getFirst() : null;
    }

    private Map<String, List<ResolvedMaterial>> resolveBom(
            CompileRequest request, Map<String, RouteNode> routeById,
            List<String> entries, List<PreviewIssue> issues) {
        Map<String, List<ResolvedMaterial>> result = new LinkedHashMap<>();
        Set<String> slotKeys = new LinkedHashSet<>();
        for (BomInputSlot slot : request.bomInputs()) {
            if (!routeById.containsKey(slot.processRouteNodeId())) {
                issues.add(block("BOM_SLOT_UNKNOWN_PROCESS", "Bind", slot.processRouteNodeId(),
                        "BOM 槽引用未知工序", "重新选择工序"));
                continue;
            }
            if (!slotKeys.add(slot.processRouteNodeId() + "|" + slot.materialLookupKey())) issues.add(block(
                    "DUPLICATE_BOM_SLOT", "Bind", slot.processRouteNodeId(), "同一工序重复绑定相同 BOM 物料", "只保留一个权威投入槽"));
            MaterialRef material = resolveMaterial(request, slot.materialLookupKey(),
                    MaterialKind.RAW_MATERIAL, "Resolve", slot.processRouteNodeId(), issues);
            if (slot.evidenceRefs().isEmpty()) issues.add(block("BOM_SLOT_EVIDENCE_REQUIRED", "Bind", slot.processRouteNodeId(),
                    "BOM 投入槽缺少确认依据", "绑定访谈/文件/负责人确认引用"));
            if (material != null && compatibleUnit(request.factoryId(), slot.requestedUnit(), material, issues,
                    "bom:" + slot.processRouteNodeId())) result.computeIfAbsent(slot.processRouteNodeId(), ignored -> new ArrayList<>())
                    .add(new ResolvedMaterial(material, material.canonicalUnit()));
        }
        for (String entry : entries) if (result.getOrDefault(entry, List.of()).isEmpty()) issues.add(block(
                "ENTRY_BOM_INPUT_REQUIRED", "Bind", entry, "入口工序必须至少有一个已解析 BOM 原料投入", "补齐入口 BOM 槽"));
        result.replaceAll((key, value) -> value.stream().sorted(Comparator.comparing(item -> item.material().id())).toList());
        return result;
    }

    private Map<String, ResolvedMaterial> resolveWips(
            CompileRequest request, Map<String, RouteNode> routeById,
            Map<String, List<RouteEdge>> outgoing, List<PreviewIssue> issues) {
        Map<String, WipBinding> bindings = new LinkedHashMap<>();
        for (WipBinding binding : request.wipBindings()) {
            if (bindings.putIfAbsent(binding.producerRouteNodeId(), binding) != null) issues.add(block(
                    "DUPLICATE_WIP_BINDING", "Bind", binding.producerRouteNodeId(), "同一工序有重复 WIP 绑定", "只保留一个权威绑定"));
            if (!routeById.containsKey(binding.producerRouteNodeId())) issues.add(block(
                    "WIP_UNKNOWN_PROCESS", "Bind", binding.producerRouteNodeId(), "WIP 绑定引用未知工序", "重新选择生产工序"));
        }
        Map<String, ResolvedMaterial> result = new LinkedHashMap<>();
        for (String producer : routeById.keySet()) {
            if (outgoing.getOrDefault(producer, List.of()).isEmpty()) continue;
            WipBinding binding = bindings.get(producer);
            if (binding == null) {
                issues.add(block("WIP_BINDING_REQUIRED", "Expand", producer,
                        "非终点工序必须绑定已确认的半成品 WIP SKU", "选择半成品 SKU；待创建草稿在主数据 adapter 就绪前保持阻断"));
                continue;
            }
            MaterialRef material = resolveMaterial(request, binding.materialLookupKey(),
                    MaterialKind.SEMI_FINISHED, "Resolve", producer, issues);
            if (binding.evidenceRefs().isEmpty()) issues.add(block("WIP_EVIDENCE_REQUIRED", "Bind", producer,
                    "WIP 绑定缺少确认依据", "绑定用户确认或现有配置引用"));
            if (material != null && compatibleUnit(request.factoryId(), binding.requestedUnit(), material, issues,
                    "wip:" + producer)) result.put(producer, new ResolvedMaterial(material, material.canonicalUnit()));
        }
        return result;
    }

    private boolean compatibleUnit(
            String factoryId, String requestedUnit, MaterialRef material,
            List<PreviewIssue> issues, String target) {
        var requested = units.describe(factoryId, requestedUnit);
        var canonical = units.describe(factoryId, material.canonicalUnit());
        if (requestedUnit.isBlank() || requested.isEmpty() || canonical.isEmpty()) {
            issues.add(block("UNIT_NOT_RESOLVED", "Normalize", target, "投入单位或物料 canonical unit 未注册", "选择权威活动单位"));
            return false;
        }
        if (requested.get().dimension() != canonical.get().dimension()) {
            issues.add(block("UNIT_DIMENSION_MISMATCH", "Normalize", target, "投入单位与物料单位量纲不一致", "改用同量纲单位"));
            return false;
        }
        return true;
    }

    private void validateExistingDraftScope(
            CompileRequest request, MaterialRef product, List<PreviewIssue> issues) {
        if (request.existingDraft() == null || request.existingDraft().definition() == null || product == null) return;
        var existing = request.existingDraft().definition();
        if (!request.factoryId().equals(existing.getFactoryId()) || !product.id().equals(existing.getProductTypeId())) issues.add(block(
                "EXISTING_DRAFT_SCOPE_MISMATCH", "Diff", "existingDraft", "既有草稿不属于目标工厂/SKU", "重新读取正确草稿"));
        if (request.existingDraft().lockVersion() == null || request.existingDraft().revisionHash().isBlank()) issues.add(block(
                "EXISTING_DRAFT_VERSION_REQUIRED", "Diff", "existingDraft", "既有草稿必须锁 lockVersion/revisionHash", "刷新草稿版本"));
    }

    private ProductProcessWorkflowDTO buildDefinition(
            CompileRequest request,
            List<RouteNode> routeNodes,
            List<RouteEdge> materialEdges,
            Map<String, ProcessRef> processes,
            MaterialRef product,
            Map<String, List<ResolvedMaterial>> bomByProcess,
            Map<String, ResolvedMaterial> wipByProducer,
            Map<String, List<RouteEdge>> incoming,
            Map<String, List<RouteEdge>> outgoing,
            Map<String, Integer> ranks) {
        ProductProcessWorkflowDTO dto = new ProductProcessWorkflowDTO();
        dto.setFactoryId(request.factoryId()); dto.setProductTypeId(product.id());
        dto.setSchemaVersion(1); dto.setStatus("DRAFT_PREVIEW");
        dto.setViewport(new ProductProcessWorkflowDTO.Viewport(0D, 0D, 1D));

        Map<String, ProductProcessWorkflowDTO.Node> materialNodes = new LinkedHashMap<>();
        Map<String, ProductProcessWorkflowDTO.Node> processNodes = new LinkedHashMap<>();
        Map<String, List<PortSpec>> inputsByProcess = new LinkedHashMap<>();
        Map<String, List<PortSpec>> outputsByProcess = new LinkedHashMap<>();

        for (RouteNode routeNode : routeNodes.stream().sorted(Comparator.comparing(RouteNode::id)).toList()) {
            ProcessRef process = processes.get(routeNode.id());
            String processId = stableId("process", request.factoryId(), product.id(), process.id());
            int rank = ranks.getOrDefault(routeNode.id(), 0);
            var node = new ProductProcessWorkflowDTO.Node(processId, "PROCESS",
                    new ProductProcessWorkflowDTO.Position(280D + rank * 560D, lane(routeNode.id(), routeNodes)),
                    new LinkedHashMap<>());
            node.getData().put("processName", process.name());
            node.getData().put("workProcessId", process.id());
            node.getData().put("sourceRouteNodeId", routeNode.id());
            processNodes.put(routeNode.id(), node);
        }

        for (Map.Entry<String, List<ResolvedMaterial>> entry : bomByProcess.entrySet()) {
            for (ResolvedMaterial resolved : entry.getValue()) {
                var materialNode = materialNode(materialNodes, request, resolved.material(), 0D,
                        lane(entry.getKey(), routeNodes) - 60D, "BOM_INPUT");
                inputsByProcess.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>())
                        .add(new PortSpec(materialNode, resolved.unit()));
            }
        }
        for (RouteEdge edge : materialEdges) {
            ResolvedMaterial resolved = wipByProducer.get(edge.fromNodeId());
            int producerRank = ranks.getOrDefault(edge.fromNodeId(), 0);
            var materialNode = materialNode(materialNodes, request, resolved.material(),
                    560D + producerRank * 560D, lane(edge.fromNodeId(), routeNodes), "ROUTE_WIP");
            inputsByProcess.computeIfAbsent(edge.toNodeId(), ignored -> new ArrayList<>())
                    .add(new PortSpec(materialNode, resolved.unit()));
        }
        for (RouteNode routeNode : routeNodes) {
            if (!outgoing.getOrDefault(routeNode.id(), List.of()).isEmpty()) {
                ResolvedMaterial resolved = wipByProducer.get(routeNode.id());
                var materialNode = materialNodes.get(materialKey(resolved.material()));
                outputsByProcess.computeIfAbsent(routeNode.id(), ignored -> new ArrayList<>())
                        .add(new PortSpec(materialNode, resolved.unit()));
            } else {
                int rank = ranks.getOrDefault(routeNode.id(), 0);
                var materialNode = materialNode(materialNodes, request, product,
                        560D + rank * 560D, lane(routeNode.id(), routeNodes), "FINISHED_OUTPUT");
                outputsByProcess.computeIfAbsent(routeNode.id(), ignored -> new ArrayList<>())
                        .add(new PortSpec(materialNode, product.canonicalUnit()));
            }
        }

        List<ProductProcessWorkflowDTO.Edge> edges = new ArrayList<>();
        for (RouteNode routeNode : routeNodes.stream().sorted(Comparator.comparing(RouteNode::id)).toList()) {
            ProductProcessWorkflowDTO.Node process = processNodes.get(routeNode.id());
            List<Map<String, Object>> ports = new ArrayList<>();
            List<PortSpec> inputSpecs = distinctPorts(inputsByProcess.getOrDefault(routeNode.id(), List.of()));
            for (int ordinal = 0; ordinal < inputSpecs.size(); ordinal++) {
                PortSpec spec = inputSpecs.get(ordinal);
                String portId = stableId("port-in", process.getId(), spec.material().getId());
                ports.add(port(portId, "INPUT", spec, ordinal));
                edges.add(new ProductProcessWorkflowDTO.Edge(
                        stableId("edge", spec.material().getId(), process.getId(), portId),
                        spec.material().getId(), "output", process.getId(), portId));
            }
            List<PortSpec> outputSpecs = distinctPorts(outputsByProcess.getOrDefault(routeNode.id(), List.of()));
            for (int ordinal = 0; ordinal < outputSpecs.size(); ordinal++) {
                PortSpec spec = outputSpecs.get(ordinal);
                String portId = stableId("port-out", process.getId(), spec.material().getId());
                ports.add(port(portId, "OUTPUT", spec, ordinal));
                edges.add(new ProductProcessWorkflowDTO.Edge(
                        stableId("edge", process.getId(), spec.material().getId(), portId),
                        process.getId(), portId, spec.material().getId(), "input"));
            }
            process.getData().put("ports", ports);
            if (!inputSpecs.isEmpty()) process.getData().put("inputUnit", inputSpecs.get(0).unit());
            if (!outputSpecs.isEmpty()) process.getData().put("outputUnit", outputSpecs.get(0).unit());
        }
        List<ProductProcessWorkflowDTO.Node> nodes = new ArrayList<>(materialNodes.values());
        nodes.addAll(processNodes.values());
        nodes.sort(Comparator.comparing(ProductProcessWorkflowDTO.Node::getId));
        edges.sort(Comparator.comparing(ProductProcessWorkflowDTO.Edge::getId));
        dto.setNodes(nodes); dto.setEdges(edges);
        return dto;
    }

    private ProductProcessWorkflowDTO.Node materialNode(
            Map<String, ProductProcessWorkflowDTO.Node> nodes,
            CompileRequest request, MaterialRef material, double x, double y, String source) {
        return nodes.computeIfAbsent(materialKey(material), ignored -> {
            String id = stableId("material", request.factoryId(), material.kind().name(), material.id());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("name", material.name()); data.put("skuId", material.id());
            data.put("baseUnit", material.canonicalUnit());
            data.put("unit", material.canonicalUnit()); data.put("source", source);
            return new ProductProcessWorkflowDTO.Node(id, material.kind().name(),
                    new ProductProcessWorkflowDTO.Position(x, y), data);
        });
    }

    private Map<String, Object> port(String id, String direction, PortSpec spec, int ordinal) {
        Map<String, Object> port = new LinkedHashMap<>();
        port.put("id", id); port.put("direction", direction); port.put("unit", spec.unit());
        port.put("materialNodeId", spec.material().getId());
        port.put("materialKind", spec.material().getKind()); port.put("ordinal", ordinal);
        return port;
    }

    private List<PortSpec> distinctPorts(List<PortSpec> values) {
        return values.stream().collect(Collectors.toMap(item -> item.material().getId(), Function.identity(),
                (left, right) -> left, TreeMap::new)).values().stream().toList();
    }

    private Map<String, Integer> topologicalRanks(
            List<RouteNode> nodes, List<RouteEdge> edges, List<PreviewIssue> issues) {
        Map<String, Integer> indegree = nodes.stream().collect(Collectors.toMap(RouteNode::id, ignored -> 0));
        Map<String, List<String>> next = new HashMap<>();
        for (RouteEdge edge : edges) {
            indegree.computeIfPresent(edge.toNodeId(), (ignored, value) -> value + 1);
            next.computeIfAbsent(edge.fromNodeId(), ignored -> new ArrayList<>()).add(edge.toNodeId());
        }
        ArrayDeque<String> queue = new ArrayDeque<>(indegree.entrySet().stream()
                .filter(entry -> entry.getValue() == 0).map(Map.Entry::getKey).sorted().toList());
        Map<String, Integer> ranks = new HashMap<>();
        int visited = 0;
        while (!queue.isEmpty()) {
            String current = queue.removeFirst(); visited++;
            for (String target : next.getOrDefault(current, List.of()).stream().sorted().toList()) {
                ranks.merge(target, ranks.getOrDefault(current, 0) + 1, Math::max);
                int remaining = indegree.computeIfPresent(target, (ignored, value) -> value - 1);
                if (remaining == 0) queue.addLast(target);
            }
        }
        if (visited != nodes.size()) issues.add(block("MATERIAL_FLOW_CYCLE", "Expand", "route",
                "去除返工边后物料流仍有循环，不能生成正式 Workflow DAG", "修正路线或把异常回路建模为 B23 ReworkPolicy"));
        return ranks;
    }

    private void addControlIssues(
            com.cretas.aims.blueprint.canonical.FactoryRouteIntentCompiler.RouteCompilation route,
            List<PreviewIssue> issues) {
        Set<EdgeType> controls = route.graph().edges().stream().map(RouteEdge::type)
                .filter(type -> type != EdgeType.FORWARD).collect(Collectors.toCollection(LinkedHashSet::new));
        for (EdgeType type : controls) {
            String message = type == EdgeType.REWORK
                    ? "返工不能写成正式 Workflow 定义环，必须编译为运行时 ReworkPolicy"
                    : type + " 已展开为二部物料图，但 guard/selection/join 运行语义尚未锁定";
            issues.add(block("B23_" + type.name() + "_SEMANTICS_REQUIRED", "CompileControl", type.name(),
                    message, "完成 B23 受控语义和隔离模拟后才能物化"));
        }
    }

    private WorkflowChangeSet diff(
            CompileRequest request, ProductProcessWorkflowDTO desired,
            String productId, String previewDigest, boolean unsupported) {
        ProductProcessWorkflowDTO before = request.existingDraft() == null ? null : request.existingDraft().definition();
        Map<String, Object> beforeEntities = entities(before);
        Map<String, Object> afterEntities = entities(desired);
        List<EntityDiff> diffs = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>(); keys.addAll(beforeEntities.keySet()); keys.addAll(afterEntities.keySet());
        for (String key : keys.stream().sorted().toList()) {
            Object oldValue = beforeEntities.get(key); Object newValue = afterEntities.get(key);
            DiffAction action = oldValue == null ? DiffAction.CREATE : newValue == null ? DiffAction.UPDATE
                    : hash(oldValue).equals(hash(newValue)) ? DiffAction.KEEP : DiffAction.UPDATE;
            diffs.add(new EntityDiff(key.startsWith("node:") ? "NODE" : "EDGE", key.substring(key.indexOf(':') + 1),
                    action, oldValue == null ? "" : hash(oldValue), newValue == null ? "" : hash(newValue),
                    action == DiffAction.KEEP ? List.of() : List.of(newValue == null ? "removed" : "businessFields")));
        }
        boolean allKeep = !diffs.isEmpty() && diffs.stream().allMatch(item -> item.action() == DiffAction.KEEP);
        DiffAction action = unsupported ? DiffAction.UNSUPPORTED : before == null ? DiffAction.CREATE
                : allKeep ? DiffAction.KEEP : DiffAction.UPDATE;
        int expected = unsupported || action == DiffAction.KEEP ? 0 : 1;
        return new WorkflowChangeSet(request.factoryId(), productId, action,
                request.existingDraft() == null ? null : request.existingDraft().lockVersion(),
                request.existingDraft() == null ? "" : request.existingDraft().revisionHash(),
                previewDigest, diffs, expected, 0, null, 0);
    }

    private Map<String, Object> entities(ProductProcessWorkflowDTO definition) {
        if (definition == null) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        for (BusinessNode node : businessSnapshot(definition).nodes()) result.put("node:" + node.id(), node);
        for (BusinessEdge edge : businessSnapshot(definition).edges()) result.put("edge:" + edge.id(), edge);
        return result;
    }

    private BusinessSnapshot businessSnapshot(ProductProcessWorkflowDTO definition) {
        List<BusinessNode> nodes = definition.getNodes().stream()
                .map(node -> new BusinessNode(node.getId(), node.getKind(), deepSorted(node.getData())))
                .sorted(Comparator.comparing(BusinessNode::id)).toList();
        List<BusinessEdge> edges = definition.getEdges().stream()
                .map(edge -> new BusinessEdge(edge.getId(), edge.getSource(), edge.getSourceHandle(),
                        edge.getTarget(), edge.getTargetHandle()))
                .sorted(Comparator.comparing(BusinessEdge::id)).toList();
        return new BusinessSnapshot(definition.getFactoryId(), definition.getProductTypeId(),
                definition.getSchemaVersion(), nodes, edges);
    }

    private Map<String, Object> layoutSnapshot(ProductProcessWorkflowDTO definition) {
        Map<String, Object> positions = new TreeMap<>();
        for (var node : definition.getNodes()) positions.put(node.getId(), Map.of(
                "x", node.getPosition().getX(), "y", node.getPosition().getY()));
        return Map.of("layoutVersion", FactoryWorkflowPreviewModels.LAYOUT_VERSION,
                "positions", positions, "viewport", Map.of(
                        "x", definition.getViewport().getX(), "y", definition.getViewport().getY(),
                        "zoom", definition.getViewport().getZoom()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepSorted(Map<String, Object> value) {
        return value == null ? Map.of() : value.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> canonicalize(entry.getValue()),
                        (left, right) -> left, LinkedHashMap::new));
    }

    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), canonicalize(item)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(this::canonicalize).toList();
        return value;
    }

    private WorkflowDraftPreview blocked(
            CompileRequest request, List<PreviewIssue> issues,
            ProductProcessWorkflowDTO definition, ValidationReceipt validation, WorkflowChangeSet changeSet) {
        return new WorkflowDraftPreview(null, null, "BLOCKED",
                request == null ? "" : request.sourceDigest(), "", null, "",
                definition, changeSet, validation, issues, null, null, null, 0);
    }

    private String hash(Object value) {
        try {
            byte[] serialized = canonicalMapper.writeValueAsBytes(value);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(serialized));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        } catch (Exception invalid) {
            throw new IllegalStateException("Workflow preview contract cannot be serialized", invalid);
        }
    }

    private String stableId(String namespace, String... parts) {
        String joined = namespace + "|" + String.join("|", parts);
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(joined.getBytes(StandardCharsets.UTF_8)));
            return namespace + "-" + digest.substring(0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private double lane(String nodeId, List<RouteNode> nodes) {
        List<String> ordered = nodes.stream().map(RouteNode::id).sorted().toList();
        return 80D + ordered.indexOf(nodeId) * 180D;
    }

    private String materialKey(MaterialRef material) { return material.kind() + "|" + material.id(); }

    private Map<String, List<RouteEdge>> group(
            List<RouteEdge> edges, Function<RouteEdge, String> key) {
        return edges.stream().collect(Collectors.groupingBy(key, LinkedHashMap::new, Collectors.toList()));
    }

    private boolean hasBlocking(List<PreviewIssue> issues) {
        return issues.stream().anyMatch(item -> item.severity() == Severity.BLOCKING);
    }

    private PreviewIssue block(
            String code, String phase, String target, String message, String recovery) {
        return new PreviewIssue(code, Severity.BLOCKING, phase, target, message, recovery);
    }

    private record ResolvedMaterial(MaterialRef material, String unit) {}

    private record PortSpec(ProductProcessWorkflowDTO.Node material, String unit) {}
}
