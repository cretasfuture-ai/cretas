package com.cretas.aims.service.validation;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.ProductProcessWorkflowDTO.Edge;
import com.cretas.aims.dto.ProductProcessWorkflowDTO.Node;
import com.cretas.aims.dto.workflow.WorkflowCompletenessIssue;
import com.cretas.aims.dto.workflow.WorkflowUnitIssueDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitConversionContext;
import com.cretas.aims.service.unit.UnitDimension;
import com.cretas.aims.service.unit.UnitUsageScene;
import com.cretas.aims.service.workflow.ProductProcessWorkflowRuntimeCompiler;
import com.cretas.aims.service.workflow.WorkflowActualIoSemantics;
import com.cretas.aims.service.workflow.WorkflowPendingConfirmations;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only feedback for an in-progress canvas. The existing save/publish gates remain authoritative. */
@Component
@RequiredArgsConstructor
public class ProductProcessWorkflowCompletenessChecker {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final Pattern FIXED_RATIO =
            Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s+[^=]+?\\s*=\\s*(\\d+(?:\\.\\d+)?)\\s+[^=]+?\\s*$");

    private final ProductProcessWorkflowValidator structure;
    private final ProductProcessWorkflowCatalogValidator catalog;
    private final ProductProcessWorkflowUnitValidator units;
    private final ProductProcessWorkflowRuntimeCompiler compiler;
    private final UnitContractService unitContracts;
    private final ProductTypeRepository products;
    private final ProductWorkProcessRepository productProcesses;

    public List<WorkflowCompletenessIssue> check(
            String factoryId, String productTypeId, ProductProcessWorkflowDTO submitted) {
        List<WorkflowCompletenessIssue> issues = new ArrayList<>();
        ProductProcessWorkflowDTO graph = inspectionGraph(factoryId, productTypeId, submitted);
        List<Node> nodes = graph.getNodes().stream().filter(Objects::nonNull).toList();
        List<Edge> edges = graph.getEdges().stream().filter(Objects::nonNull).toList();
        Map<String, Node> byId = new LinkedHashMap<>();
        nodes.forEach(node -> byId.putIfAbsent(node.getId(), node));
        Map<String, List<Edge>> outgoing = new HashMap<>();
        Map<String, List<Edge>> incoming = new HashMap<>();
        for (Edge edge : edges) {
            outgoing.computeIfAbsent(edge.getSource(), key -> new ArrayList<>()).add(edge);
            incoming.computeIfAbsent(edge.getTarget(), key -> new ArrayList<>()).add(edge);
        }
        List<String> skuIds = nodes.stream()
                .filter(node -> !kind(node, "PROCESS") && !kind(node, "RAW_MATERIAL"))
                .map(node -> text(data(node).get("skuId"))).filter(Objects::nonNull).distinct().toList();
        Map<String, ProductType> skuById = new HashMap<>();
        if (!skuIds.isEmpty()) {
            for (ProductType product : products.findByIdIn(skuIds)) {
                if (factoryId.equals(product.getFactoryId())) skuById.put(product.getId(), product);
            }
        }
        Map<String, BigDecimal> yieldByProcess = new HashMap<>();
        if (nodes.stream().anyMatch(node -> kind(node, "PROCESS"))) {
            for (ProductWorkProcess assignment : productProcesses
                    .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(factoryId, productTypeId)) {
                if (assignment.getStandardYieldRate() != null) {
                    yieldByProcess.putIfAbsent(assignment.getWorkProcessId(), assignment.getStandardYieldRate());
                }
            }
        }
        LocalDateTime now = LocalDateTime.now();
        boolean hasFinished = false;
        for (Node node : nodes) {
            List<Edge> from = outgoing.getOrDefault(node.getId(), List.of());
            List<Edge> to = incoming.getOrDefault(node.getId(), List.of());
            if (kind(node, "FINISHED_GOOD") && from.isEmpty()
                    && to.stream().anyMatch(edge -> kind(byId.get(edge.getSource()), "PROCESS"))) {
                hasFinished = true;
            }
            if (kind(node, "PROCESS")) {
                checkProcess(factoryId, node, byId, from, to, outgoing, skuById, yieldByProcess, now, issues);
            }
            if (!kind(node, "PROCESS")) {
                checkMaterial(node, from, to, byId, issues);
            }
        }
        if (!hasFinished) {
            Node target = nodes.stream().filter(node -> kind(node, "PROCESS")).reduce((a, b) -> b).orElse(null);
            add(issues, "FINISHED_OUTPUT_REQUIRED", target, null,
                    "整张图还没有成品出口", "在最后一道工序添加并连接成品");
        }
        if (nodes.stream().noneMatch(node -> kind(node, "RAW_MATERIAL"))) {
            Node target = nodes.stream().filter(node -> kind(node, "PROCESS")).findFirst().orElse(null);
            add(issues, "RAW_INPUT_REQUIRED", target, null,
                    "整张图还没有原料入口", "添加原料并接到第一道工序");
        }
        appendPublishChecks(factoryId, productTypeId, graph, byId, issues);
        return List.copyOf(issues);
    }

    private static ProductProcessWorkflowDTO inspectionGraph(
            String factoryId, String productTypeId, ProductProcessWorkflowDTO submitted) {
        ProductProcessWorkflowDTO graph = new ProductProcessWorkflowDTO();
        graph.setId(submitted.getId());
        graph.setFactoryId(factoryId);
        graph.setProductTypeId(productTypeId);
        graph.setSchemaVersion(submitted.getSchemaVersion());
        graph.setStatus(submitted.getStatus());
        graph.setVersion(submitted.getVersion());
        graph.setViewport(submitted.getViewport());
        graph.setEdges(submitted.getEdges());
        graph.setNodes(submitted.getNodes().stream()
                .map(node -> node == null ? null : new Node(node.getId(), node.getKind(), node.getPosition(), node.getData()))
                .toList());
        // Use the same normalized view as draft persistence, leaving the submitted/saved graph intact.
        WorkflowActualIoSemantics.normalizeDraft(graph);
        return graph;
    }

    private void checkProcess(String factoryId, Node node, Map<String, Node> nodes,
                              List<Edge> from, List<Edge> to, Map<String, List<Edge>> outgoing,
                              Map<String, ProductType> productsById, Map<String, BigDecimal> yieldByProcess,
                              LocalDateTime now, List<WorkflowCompletenessIssue> issues) {
        Map<String, Object> data = data(node);
        if (text(data.get("processCategory")) == null) {
            add(issues, "PROCESS_CATEGORY_REQUIRED", node, null,
                    "还没选工序类型", "打开「编辑工序」，选择工序类别");
        }
        List<Map<?, ?>> ports = rows(data.get("ports"));
        List<Map<?, ?>> inputs = direction(ports, "INPUT");
        List<Map<?, ?>> outputs = direction(ports, "OUTPUT");
        if (inputs.isEmpty() || to.isEmpty()) {
            add(issues, "INPUT_REQUIRED", node, null, "还没有投入", "把原料或上游产出接到这道工序");
        }
        if (outputs.isEmpty()) {
            add(issues, "OUTPUT_REQUIRED", node, null, "还没有产出", "在这道工序添加半成品或成品");
        }
        for (Map<?, ?> input : inputs) {
            String portId = text(input.get("id"));
            if (text(input.get("unit")) == null) {
                add(issues, "INPUT_UNIT_REQUIRED", node, portId,
                        item(input, "这项投入") + "还没填单位", "选择投入的计量单位");
            }
            BigDecimal share = number(input.get("inputShareRatio"));
            if (input.get("inputShareRatio") != null && !inRange(share, HUNDRED)) {
                add(issues, "INPUT_RATIO_INVALID", node, portId,
                        item(input, "这项投入") + "的配比不在 0% 到 100% 之间", "填写大于 0% 且不超过 100% 的配比");
            }
        }
        Map<?, ?> rule = data.get("conversionRule") instanceof Map<?, ?> value ? value : Map.of();
        boolean fixedMode = "FIXED_RATIO".equals(rule.get("mode"));
        boolean fixedRatio = fixedMode && hasFixedRatio(rule, inputs, outputs);
        if (fixedMode && !fixedRatio) {
            add(issues, "FIXED_RATIO_REQUIRED", node, null,
                    "固定配比还没填完整", "在换算规则中填写大于 0 的投入量和产出量");
        }
        List<String> inputUnits = inputs.stream().map(port -> text(port.get("unit")))
                .filter(Objects::nonNull).distinct().toList();
        for (Map<?, ?> port : outputs) {
            String portId = text(port.get("id"));
            Node material = nodes.get(text(port.get("materialNodeId")));
            String output = item(port, "这项产出");
            boolean connected = from.stream().anyMatch(edge -> Objects.equals(portId, edge.getSourceHandle())
                    && material != null && Objects.equals(material.getId(), edge.getTarget()));
            if (!connected || (!kind(material, "FINISHED_GOOD") && !specialOutput(material)
                    && outgoing.getOrDefault(material.getId(), List.of()).stream()
                    .noneMatch(edge -> kind(nodes.get(edge.getTarget()), "PROCESS")))) {
                add(issues, "OUTPUT_UNCONNECTED", node, portId,
                        output + "还没接到下一道工序或成品出口", "连接下游工序，或把这项产出设为成品");
            }
            String outputUnit = text(port.get("unit"));
            if (outputUnit == null) {
                add(issues, "OUTPUT_UNIT_REQUIRED", node, portId,
                        output + "还没填单位", "选择产出的计量单位");
                continue;
            }
            String sku = material == null ? null : text(data(material).get("skuId"));
            boolean counted = dimension(factoryId, outputUnit)
                    .filter(value -> value == UnitDimension.COUNT || value == UnitDimension.PACKAGE).isPresent();
            boolean pieceWeight = counted && (hasNetWeight(factoryId, productsById.get(sku), outputUnit)
                    || (sku != null && canConvert(factoryId, sku, outputUnit, "g", now)));
            if (counted && "ACTUAL_WEIGHT".equals(rule.get("mode")) && !pieceWeight) {
                add(issues, "COUNT_WEIGHT_REQUIRED", node, portId,
                        output + "按件数计，但按实际重量换算", "在产出档案补每件净重，或改用固定配比");
            }
            boolean formula = "FORMULA".equals(rule.get("mode")) && text(rule.get("expression")) != null;
            if (!fixedRatio && !formula && inputUnits.stream().anyMatch(inputUnit ->
                    !(pieceWeight && dimension(factoryId, inputUnit).orElse(null) == UnitDimension.MASS)
                            && !canConvert(factoryId, sku, inputUnit, outputUnit, now))) {
                add(issues, "CONVERSION_REQUIRED", node, portId,
                        output + "的单位和投入不同，还缺换算", "选择现有换算关系，或填写固定配比");
            }
        }
        BigDecimal yield = number(data.get("standardYieldRate"));
        if (data.get("standardYieldRate") == null) {
            yield = yieldByProcess.get(text(data.get("workProcessId")));
        }
        if (!inRange(yield, BigDecimal.ONE) && !(yield == null && fixedRatio)) {
            add(issues, "STANDARD_YIELD_INVALID", node, null,
                    yield == null || yield.signum() == 0 ? "还没填标准出成率" : "标准出成率不在 0% 到 100% 之间",
                    "在产品的工序配置填写大于 0% 且不超过 100% 的标准出成率");
        }
        checkUsage(node, rows(data.get("materialBindings")), "dosagePerKgG",
                "AUX_USAGE_REQUIRED", "这项辅料", "打开辅料配置，填写每公斤投入用量", issues);
        ProductProcessWorkflowDTO singleNode = new ProductProcessWorkflowDTO();
        singleNode.setNodes(List.of(node));
        for (String pending : WorkflowPendingConfirmations.blockers(singleNode)) {
            issues.add(new WorkflowCompletenessIssue("PENDING_CONFIRMATION", node.getId(), null,
                    pending, "打开工序，补齐标有「待确认」的项目后再应用"));
        }
    }

    private void checkMaterial(Node node, List<Edge> from, List<Edge> to, Map<String, Node> nodes,
                               List<WorkflowCompletenessIssue> issues) {
        if (from.isEmpty() && to.isEmpty()) {
            add(issues, "MATERIAL_UNCONNECTED", node, null, "还没有连到工序", "将物料与相应工序连起来");
        }
        if (text(data(node).get("skuId")) == null) {
            add(issues, "MATERIAL_BINDING_REQUIRED", node, null,
                    "还没绑定物料档案", "打开物料卡片，选择对应的物料或成品");
        }
        Edge producer = to.stream().filter(edge -> kind(nodes.get(edge.getSource()), "PROCESS"))
                .findFirst().orElse(null);
        Node target = producer == null ? node : nodes.get(producer.getSource());
        checkUsage(target, rows(data(node).get("packagingBindings")), "standardQuantity",
                "PACKAGING_USAGE_REQUIRED", "这项包材", "打开成品的包材配置，填写每份成品用量", issues);
    }

    private void appendPublishChecks(String factoryId, String productTypeId, ProductProcessWorkflowDTO graph,
                                     Map<String, Node> nodes, List<WorkflowCompletenessIssue> issues) {
        try {
            structure.validateForDraft(graph);
        } catch (BusinessException error) {
            add(issues, "DRAFT_GRAPH_INVALID", relatedNode(error, nodes), null,
                    "画布中的物料和工序连线有冲突", "检查投入与产出的连线，删除无效或成环的连线");
            return; // The catalog and compiler expect a structurally valid draft.
        }
        var unitResult = units.validate(factoryId, graph);
        for (WorkflowUnitIssueDTO issue : unitResult.errors()) {
            if ("WORKFLOW_MATERIAL_SKU_MISSING".equals(issue.code())
                    || issues.stream().anyMatch(existing -> Objects.equals(existing.nodeId(), issue.nodeId())
                    && Objects.equals(existing.portId(), issue.portId())
                    && ("INPUT_UNIT_REQUIRED".equals(existing.code()) || "OUTPUT_UNIT_REQUIRED".equals(existing.code())))) {
                continue;
            }
            Node target = nodes.get(issue.nodeId());
            String message = switch (issue.code()) {
                case "SKU_UNIT_UNKNOWN" -> "绑定的物料档案找不到，或档案没有单位";
                case "WORKFLOW_UNIT_UNKNOWN" -> "计量单位无法识别";
                case "WORKFLOW_MATERIAL_UNIT_STALE" -> "单位与物料档案里的单位不一致";
                case "WORKFLOW_PROCESS_UNIT_STALE" -> "工序单位与主要投入或产出单位不一致";
                case "WORKFLOW_CONVERSION_REQUIRED" -> "当前单位与档案单位之间缺少换算关系";
                case "WORKFLOW_CONVERSION_STALE" -> "之前选择的单位换算已失效";
                case "WORKFLOW_CONVERSION_GRAPH_INVALID" -> "产品档案里的单位换算关系有冲突";
                default -> "计量单位需要重新核对";
            };
            add(issues, issue.code(), target, issue.portId(), message,
                    "核对当前工厂的物料档案，重新选择单位或有效换算关系");
        }
        try {
            structure.validateStructureComplete(graph);
        } catch (BusinessException error) {
            // Boundary, orphaned nodes, missing bindings and missing units are already listed
            // individually by the checks above; the validator still rejects them on publish.
            if (issues.stream().noneMatch(issue -> List.of("RAW_INPUT_REQUIRED", "FINISHED_OUTPUT_REQUIRED",
                    "MATERIAL_UNCONNECTED", "MATERIAL_BINDING_REQUIRED", "INPUT_REQUIRED", "OUTPUT_REQUIRED",
                    "INPUT_UNIT_REQUIRED", "OUTPUT_UNIT_REQUIRED", "OUTPUT_UNCONNECTED").contains(issue.code()))) {
                add(issues, "PUBLISH_STRUCTURE_INVALID", null, null,
                        "工艺路线还没配完整", "核对原料入口、工序投入与产出、成品出口");
            }
        }
        try {
            // The normal editor publish path synchronizes the BOM before checking active BOM.
            catalog.validateForBomConfiguration(factoryId, productTypeId, graph);
        } catch (BusinessException error) {
            Node target = relatedNode(error, nodes);
            boolean unbound = target != null && !kind(target, "PROCESS") && text(data(target).get("skuId")) == null;
            if (!unbound) {
                add(issues, "PUBLISH_CATALOG_INVALID", target, null,
                        "工序或物料与当前工厂的档案不匹配", "重新选择工序、投入原料或产出成品");
            }
        }
        if (issues.stream().anyMatch(issue -> List.of("PENDING_CONFIRMATION", "MATERIAL_BINDING_REQUIRED",
                "INPUT_REQUIRED", "OUTPUT_REQUIRED", "INPUT_UNIT_REQUIRED", "OUTPUT_UNIT_REQUIRED",
                "RAW_INPUT_REQUIRED", "FINISHED_OUTPUT_REQUIRED").contains(issue.code()))) return;
        try {
            compiler.compile(graph);
        } catch (BusinessException error) {
            add(issues, "PUBLISH_RUNTIME_INVALID", relatedNode(error, nodes), null,
                    "工序的报工设置还没配完整", "核对各工序的投入、产出及固定分摊比例");
        }
    }

    private static Node relatedNode(BusinessException error, Map<String, Node> nodes) {
        String message = error.getMessage();
        if (message == null) return null;
        return nodes.entrySet().stream()
                .filter(entry -> entry.getKey() != null && message.contains(entry.getKey()))
                .max(java.util.Comparator.comparingInt(entry -> entry.getKey().length()))
                .map(Map.Entry::getValue).orElse(null);
    }

    private void checkUsage(Node target, List<Map<?, ?>> bindings, String field, String code,
                            String label, String action, List<WorkflowCompletenessIssue> issues) {
        for (Map<?, ?> row : bindings) {
            if (text(row.get("materialTypeId")) != null && !positive(number(row.get(field)))) {
                add(issues, code, target, null, item(row, label) + "已经绑定，但还没填用量", action);
            }
        }
    }

    private boolean hasNetWeight(String factoryId, ProductType product, String unit) {
        return product != null && positive(product.getNetContentQuantity())
                && dimension(factoryId, product.getNetContentUnit()).orElse(null) == UnitDimension.MASS
                && (Objects.equals(unit, product.getUnit()) || unitContracts.areEquivalent(factoryId, unit, product.getUnit()));
    }

    private Optional<UnitDimension> dimension(String factoryId, String unit) {
        return unit == null ? Optional.empty() : unitContracts.describe(factoryId, unit).map(CanonicalUnit::dimension);
    }

    private boolean canConvert(String factoryId, String sku, String from, String to, LocalDateTime at) {
        if (from.equals(to) || unitContracts.areEquivalent(factoryId, from, to)) return true;
        var result = unitContracts.convert(new UnitConversionContext(
                factoryId, sku, from, to, at, UnitUsageScene.PRODUCTION, null, null));
        return result != null && result.succeeded();
    }

    private static boolean hasFixedRatio(Map<?, ?> rule, List<Map<?, ?>> inputs, List<Map<?, ?>> outputs) {
        String expression = text(rule.get("expression"));
        if (expression != null) {
            Matcher match = FIXED_RATIO.matcher(expression);
            if (match.matches() && positive(number(match.group(1))) && positive(number(match.group(2)))) return true;
        }
        return !inputs.isEmpty() && !outputs.isEmpty()
                && List.of(inputs, outputs).stream().flatMap(Collection::stream)
                .allMatch(port -> positive(number(port.get("standardQuantity"))));
    }

    private static boolean specialOutput(Node node) {
        if (node == null) return false;
        return List.of("isByproduct", "isRework", "isDefective").stream()
                .anyMatch(key -> Boolean.TRUE.equals(data(node).get(key)));
    }

    private static List<Map<?, ?>> rows(Object value) {
        List<Map<?, ?>> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object row : list) if (row instanceof Map<?, ?> map) result.add(map);
        }
        return result;
    }

    private static List<Map<?, ?>> direction(List<Map<?, ?>> ports, String direction) {
        return ports.stream().filter(port -> direction.equals(port.get("direction"))).toList();
    }

    private static Map<String, Object> data(Node node) {
        return node.getData() == null ? Map.of() : node.getData();
    }

    private static boolean kind(Node node, String kind) {
        return node != null && kind.equals(node.getKind());
    }

    private static String text(Object value) {
        return value instanceof String string && !string.isBlank() ? string.trim() : null;
    }

    private static BigDecimal number(Object value) {
        try {
            return value instanceof Number || value instanceof String ? new BigDecimal(value.toString()) : null;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static boolean inRange(BigDecimal value, BigDecimal max) {
        return positive(value) && value.compareTo(max) <= 0;
    }

    private static String item(Map<?, ?> row, String fallback) {
        String name = text(row.get("materialName"));
        return name == null ? fallback : "「" + name + "」";
    }

    private static void add(List<WorkflowCompletenessIssue> issues, String code, Node node, String portId,
                            String message, String action) {
        String label = node == null ? null : text(data(node).get(kind(node, "PROCESS") ? "processName" : "name"));
        issues.add(new WorkflowCompletenessIssue(code, node == null ? null : node.getId(), portId,
                label == null ? message : "「" + label + "」" + message, action));
    }
}
