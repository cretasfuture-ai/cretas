package com.cretas.aims.blueprint.canonical;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only compiler from confirmed interview facts to a route preview.
 *
 * <p>The compiler never reads suggestions, calls an AI provider, persists a graph or publishes a
 * Workflow. Plain ordered routes remain deterministic. Complex routes compile only from explicit,
 * user-confirmed entry, terminal and edge rows; prose remains evidence and is never guessed into
 * topology.</p>
 */
public final class FactoryRouteIntentCompiler {

    private static final Pattern PRODUCT_FIELD = Pattern.compile("^product(\\d+)\\.");
    private static final Pattern TOPOLOGY_FIELD = Pattern.compile("^product(\\d+)\\.routeTopology$");
    private static final Pattern FIRST_INTEGER = Pattern.compile("\\d+");
    private static final Pattern STEP_SEPARATOR = Pattern.compile("\\s*(?:→|＞|->|>)\\s*");
    private static final Pattern VALUE_SEPARATOR = Pattern.compile("[、,，;；]");
    private static final Set<String> NO_DETAILS = Set.of("没有", "无", "无例外", "没有例外", "不涉及");
    private static final Set<String> NO_TOPOLOGY = Set.of("没有", "无", "按顺序自动确定");
    private static final String SEQUENTIAL = "仅顺序";

    private FactoryRouteIntentCompiler() {
    }

    public record CompilationIssue(String code, String message, String followUpQuestion) {
        public CompilationIssue {
            code = clean(code);
            message = clean(message);
            followUpQuestion = clean(followUpQuestion);
        }
    }

    /** Conditions and rework exits stay preview metadata; the canonical graph carries edge type. */
    public record RouteEdgeIntent(
            String fromNodeId,
            String fromLabel,
            String toNodeId,
            String toLabel,
            FactoryRouteGraphPrototype.EdgeType type,
            String condition,
            String reworkExit) {
        public RouteEdgeIntent {
            fromNodeId = clean(fromNodeId);
            fromLabel = clean(fromLabel);
            toNodeId = clean(toNodeId);
            toLabel = clean(toLabel);
            type = type == null ? FactoryRouteGraphPrototype.EdgeType.FORWARD : type;
            condition = clean(condition);
            reworkExit = clean(reworkExit);
        }
    }

    public record RouteCompilation(
            int productIndex,
            String productName,
            List<String> routeShapes,
            String routeDetails,
            String status,
            String previewKind,
            String publishState,
            boolean topologyComplete,
            FactoryRouteGraphPrototype.RouteGraph graph,
            List<RouteEdgeIntent> edgeIntents,
            List<CompilationIssue> issues,
            List<String> sourceFields) {
        public RouteCompilation {
            productName = clean(productName);
            routeShapes = routeShapes == null ? List.of() : List.copyOf(routeShapes);
            routeDetails = clean(routeDetails);
            status = Objects.requireNonNullElse(status, "INCOMPLETE");
            previewKind = Objects.requireNonNullElse(previewKind, "ORDER_SKELETON");
            publishState = "NOT_PUBLISHED";
            edgeIntents = edgeIntents == null ? List.of() : List.copyOf(edgeIntents);
            issues = issues == null ? List.of() : List.copyOf(issues);
            sourceFields = sourceFields == null ? List.of() : List.copyOf(sourceFields);
        }
    }

    private record ExplicitTopology(
            FactoryRouteGraphPrototype.RouteGraph graph,
            List<RouteEdgeIntent> edgeIntents,
            List<CompilationIssue> issues) {
    }

    public static List<RouteCompilation> compile(Map<String, String> collected) {
        Map<String, String> facts = collected == null ? Map.of() : collected;
        int productCount = productCount(facts);
        List<RouteCompilation> result = new ArrayList<>();
        for (int index = 1; index <= productCount; index++) result.add(compileProduct(index, facts));
        return List.copyOf(result);
    }

    /** Validate before mutating a session, so one bad topology row rejects the whole card. */
    public static List<CompilationIssue> validateTopologySubmission(
            Map<String, String> candidateFacts, Set<String> submittedKeys) {
        if (candidateFacts == null || submittedKeys == null) return List.of();
        LinkedHashSet<Integer> products = new LinkedHashSet<>();
        for (String key : submittedKeys) {
            Matcher matcher = TOPOLOGY_FIELD.matcher(key);
            if (matcher.matches()) products.add(Integer.parseInt(matcher.group(1)));
        }
        List<CompilationIssue> issues = new ArrayList<>();
        for (int product : products) {
            RouteCompilation compiled = compileProduct(product, candidateFacts);
            if (!"READY".equals(compiled.status())) issues.addAll(compiled.issues());
        }
        return List.copyOf(issues);
    }

    static RouteCompilation compileProduct(int index, Map<String, String> facts) {
        String prefix = "product" + index + ".";
        String name = value(facts, prefix + "name");
        String details = value(facts, prefix + "routeDetails");
        List<String> shapes = splitValues(value(facts, prefix + "routeShape"));
        List<String> steps = splitSteps(value(facts, prefix + "steps"));
        FactoryRouteGraphPrototype.RouteGraph graph = FactoryRouteGraphPrototype.linear(
                "product-" + index, name.isBlank() ? "第" + index + "个成品" : name, steps);
        List<RouteEdgeIntent> edgeIntents = List.of();
        List<CompilationIssue> issues = new ArrayList<>();

        if (name.isBlank()) issues.add(issue("MISSING_PRODUCT_NAME", "缺少成品名称", "第" + index + "个成品叫什么？"));
        if (steps.isEmpty()) issues.add(issue("MISSING_PROCESS_STEPS", "缺少可拆分的工序顺序",
                "请按“解冻→加工→检验→包装”这样的顺序说出主要工序。"));
        if (shapes.isEmpty()) issues.add(issue("MISSING_ROUTE_SHAPE", "还没有确认路线形态",
                "这条路线只是顺序执行，还是包含并行、条件分支、合流、返工或不合格处置？"));

        boolean sequential = shapes.size() == 1 && SEQUENTIAL.equals(shapes.get(0));
        boolean contradictory = shapes.contains(SEQUENTIAL) && shapes.size() > 1;
        if (contradictory) issues.add(issue("CONTRADICTORY_ROUTE_SHAPE", "“仅顺序”不能与复杂路线形态同时成立",
                "请保留“仅顺序”，或删除它并选择真实存在的复杂路线形态。"));

        if (!shapes.isEmpty() && !sequential && !contradictory) {
            if (details.isBlank() || NO_DETAILS.contains(details)) {
                issues.add(issue("ROUTE_DETAILS_REQUIRED", "复杂路线缺少现场事实说明",
                        "请说明在哪道工序分开、按什么条件、去哪里，以及何时合流或返工。"));
            }
            String entry = value(facts, prefix + "routeEntry");
            String terminal = value(facts, prefix + "routeTerminal");
            String topology = value(facts, prefix + "routeTopology");
            boolean hasExplicitFacts = !entry.isBlank() || !terminal.isBlank()
                    || (!topology.isBlank() && !NO_TOPOLOGY.contains(topology));
            if (!hasExplicitFacts) {
                addComplexShapeQuestions(shapes, issues);
                issues.add(issue("EXPLICIT_TOPOLOGY_REQUIRED",
                        "当前文字只保留为用户事实，系统不会据此猜测复杂连线",
                        "请逐条确认路线起点、终点，以及每条连线的起点、终点、类型和触发条件。"));
            } else {
                ExplicitTopology explicit = compileExplicitTopology(
                        index, name, steps, entry, terminal, topology, shapes);
                graph = explicit.graph();
                edgeIntents = explicit.edgeIntents();
                issues.addAll(explicit.issues());
            }
        }

        if (sequential && !details.isBlank() && !NO_DETAILS.contains(details)) {
            issues.add(issue("SEQUENTIAL_DETAILS_CONFLICT",
                    "路线标为“仅顺序”，但例外说明里仍有额外流转事实",
                    "请确认这些说明只是工艺备注，还是应改选分支、合流、返工或不合格处置。"));
        }
        if (!graph.validation().valid() && !steps.isEmpty()) {
            graph.validation().issues().forEach(routeIssue -> issues.add(issue(
                    "GRAPH_" + routeIssue.code(), routeIssue.message(), "请补齐或修正对应工序连线。")));
        }

        boolean missingCore = name.isBlank() || steps.isEmpty() || shapes.isEmpty();
        String status = missingCore ? "INCOMPLETE"
                : (issues.isEmpty() && graph.validation().valid() ? "READY" : "BLOCKED");
        boolean complete = "READY".equals(status);
        String previewKind = complete
                ? (sequential ? "COMPILED_LINEAR" : "COMPILED_EXPLICIT")
                : "ORDER_SKELETON";
        List<String> sources = List.of(
                        "name", "steps", "routeShape", "routeDetails",
                        "routeEntry", "routeTerminal", "routeTopology").stream()
                .map(field -> prefix + field).filter(facts::containsKey).toList();
        return new RouteCompilation(index, name, shapes, details, status, previewKind,
                "NOT_PUBLISHED", complete, graph, edgeIntents, issues, sources);
    }

    private static ExplicitTopology compileExplicitTopology(
            int index, String productName, List<String> steps, String declaredEntry,
            String declaredTerminal, String rawTopology, List<String> shapes) {
        List<CompilationIssue> issues = new ArrayList<>();
        if (declaredEntry.isBlank()) issues.add(issue("MISSING_ROUTE_ENTRY", "复杂路线缺少明确起点", "请选择整条路线从哪道工序开始。"));
        if (declaredTerminal.isBlank()) issues.add(issue("MISSING_ROUTE_TERMINAL", "复杂路线缺少明确终点", "请选择整条路线在哪道工序完成。"));
        if (rawTopology.isBlank() || NO_TOPOLOGY.contains(rawTopology)) issues.add(issue(
                "MISSING_ROUTE_EDGES", "复杂路线还没有确认任何连线", "请逐条确认每条连线的起点、终点、类型和触发条件。"));

        List<FactoryRouteGraphPrototype.RouteNode> nodes = new ArrayList<>();
        Map<String, String> nodeIdByLabel = new LinkedHashMap<>();
        Set<String> duplicateLabels = new LinkedHashSet<>();
        for (int i = 0; i < steps.size(); i++) {
            String id = "process-" + (i + 1);
            String label = steps.get(i);
            nodes.add(FactoryRouteGraphPrototype.node(id, label));
            if (nodeIdByLabel.putIfAbsent(label, id) != null) duplicateLabels.add(label);
        }
        if (!duplicateLabels.isEmpty()) issues.add(issue("DUPLICATE_PROCESS_NAME",
                "复杂路线中存在同名工序，无法唯一定位: " + String.join("、", duplicateLabels),
                "请给同名工序补充能区分的名称。"));
        if (!declaredEntry.isBlank() && !nodeIdByLabel.containsKey(declaredEntry)) issues.add(issue(
                "UNKNOWN_ROUTE_ENTRY", "路线起点不在已确认工序中: " + declaredEntry, "请从已确认的工序名称中选择起点。"));
        if (!declaredTerminal.isBlank() && !nodeIdByLabel.containsKey(declaredTerminal)) issues.add(issue(
                "UNKNOWN_ROUTE_TERMINAL", "路线终点不在已确认工序中: " + declaredTerminal, "请从已确认的工序名称中选择终点。"));

        List<FactoryRouteGraphPrototype.RouteEdge> edges = new ArrayList<>();
        List<RouteEdgeIntent> intents = new ArrayList<>();
        Set<String> edgeKeys = new LinkedHashSet<>();
        if (!rawTopology.isBlank() && !NO_TOPOLOGY.contains(rawTopology)) {
            String[] lines = rawTopology.split("\\R", -1);
            for (int row = 0; row < lines.length; row++) {
                if (lines[row].isBlank()) continue;
                String[] fields = lines[row].split("\\t", -1);
                // The collected-value boundary trims trailing whitespace, so optional blank
                // condition/exit columns on the final row may arrive as 3 or 4 columns.
                if (fields.length < 3 || fields.length > 5) {
                    issues.add(issue("MALFORMED_ROUTE_EDGE", "第 " + (row + 1) + " 条连线格式不完整", "请在拓扑确认卡中重新确认这一行。"));
                    continue;
                }
                String from = clean(fields[0]);
                String to = clean(fields[1]);
                String condition = fields.length >= 4 ? clean(fields[3]) : "";
                String exit = fields.length >= 5 ? clean(fields[4]) : "";
                FactoryRouteGraphPrototype.EdgeType type;
                try {
                    type = FactoryRouteGraphPrototype.EdgeType.valueOf(clean(fields[2]));
                } catch (IllegalArgumentException ex) {
                    issues.add(issue("UNKNOWN_EDGE_TYPE", "第 " + (row + 1) + " 条连线类型无效", "请选择顺序、分支、合流或返工。"));
                    continue;
                }
                String fromId = nodeIdByLabel.get(from);
                String toId = nodeIdByLabel.get(to);
                if (fromId == null || toId == null) {
                    issues.add(issue("UNKNOWN_EDGE_NODE", "第 " + (row + 1) + " 条连线引用了未确认工序", "请从已确认工序中选择起点和终点。"));
                    continue;
                }
                if (fromId.equals(toId)) {
                    issues.add(issue("SELF_EDGE", "第 " + (row + 1) + " 条连线起点和终点相同", "请选择真实的下一道或返工返回工序。"));
                    continue;
                }
                String edgeKey = fromId + "|" + toId + "|" + type;
                if (!edgeKeys.add(edgeKey)) {
                    issues.add(issue("DUPLICATE_EDGE", "重复连线: " + from + " → " + to, "请删除重复行，只保留一条。"));
                    continue;
                }
                if (type == FactoryRouteGraphPrototype.EdgeType.BRANCH && condition.isBlank()) {
                    issues.add(issue("BRANCH_CONDITION_REQUIRED", "分支连线缺少触发条件: " + from + " → " + to, "请写清什么情况下进入这条支路。"));
                }
                if (type == FactoryRouteGraphPrototype.EdgeType.REWORK) {
                    if (condition.isBlank()) issues.add(issue("REWORK_CONDITION_REQUIRED",
                            "返工连线缺少触发条件: " + from + " → " + to, "请写清什么判定会触发返工。"));
                    if (exit.isBlank()) issues.add(issue("REWORK_EXIT_REQUIRED",
                            "返工连线缺少放行出口: " + from + " → " + to, "请确认返工复检通过后从哪道工序放行。"));
                    else if (!nodeIdByLabel.containsKey(exit)) issues.add(issue("UNKNOWN_REWORK_EXIT",
                            "返工放行出口不在已确认工序中: " + exit, "请从已确认工序中选择放行出口。"));
                }
                edges.add(FactoryRouteGraphPrototype.edge(fromId, toId, type));
                intents.add(new RouteEdgeIntent(fromId, from, toId, to, type, condition, exit));
            }
        }

        FactoryRouteGraphPrototype.RouteGraph graph = FactoryRouteGraphPrototype.graph(
                "product-" + index, productName.isBlank() ? "第" + index + "个成品" : productName, nodes, edges);
        String entryId = nodeIdByLabel.get(declaredEntry);
        String terminalId = nodeIdByLabel.get(declaredTerminal);
        if (entryId != null && !graph.entryNodeIds().equals(List.of(entryId))) issues.add(issue(
                "ENTRY_MISMATCH", "连线推导出的路线起点与用户确认不一致", "请检查是否漏了进入起点的连线，或重新选择路线起点。"));
        if (terminalId != null && !graph.terminalNodeIds().equals(List.of(terminalId))) issues.add(issue(
                "TERMINAL_MISMATCH", "连线推导出的路线终点与用户确认不一致", "请检查是否漏了离开终点的连线，或重新选择路线终点。"));
        addShapeContractIssues(shapes, intents, issues);
        return new ExplicitTopology(graph, List.copyOf(intents), List.copyOf(issues));
    }

    private static void addShapeContractIssues(
            List<String> shapes, List<RouteEdgeIntent> intents, List<CompilationIssue> issues) {
        Map<String, Integer> branchesBySource = new LinkedHashMap<>();
        Map<String, Integer> mergesByTarget = new LinkedHashMap<>();
        for (RouteEdgeIntent edge : intents) {
            if (edge.type() == FactoryRouteGraphPrototype.EdgeType.BRANCH) branchesBySource.merge(edge.fromNodeId(), 1, Integer::sum);
            if (edge.type() == FactoryRouteGraphPrototype.EdgeType.MERGE) mergesByTarget.merge(edge.toNodeId(), 1, Integer::sum);
        }
        boolean hasBranchPair = branchesBySource.values().stream().anyMatch(count -> count >= 2);
        boolean hasMergePair = mergesByTarget.values().stream().anyMatch(count -> count >= 2);
        boolean hasRework = intents.stream().anyMatch(edge -> edge.type() == FactoryRouteGraphPrototype.EdgeType.REWORK);
        boolean hasDisposition = intents.stream().anyMatch(edge ->
                (edge.type() == FactoryRouteGraphPrototype.EdgeType.BRANCH
                        || edge.type() == FactoryRouteGraphPrototype.EdgeType.REWORK) && !edge.condition().isBlank());
        if ((shapes.contains("有并行") || shapes.contains("有条件分支")) && !hasBranchPair) issues.add(issue(
                "BRANCH_ALTERNATIVES_REQUIRED", "已选择分支/并行，但没有同一起点的至少两条支路", "请从同一道工序确认至少两条不同去向。"));
        if (shapes.contains("有合流") && !hasMergePair) issues.add(issue(
                "MERGE_SOURCES_REQUIRED", "已选择合流，但没有至少两条支路汇入同一工序", "请确认至少两条合流连线，并让它们指向同一工序。"));
        if (shapes.contains("有返工") && !hasRework) issues.add(issue(
                "REWORK_EDGE_REQUIRED", "已选择返工，但没有返工连线", "请补充返工触发点、返回点、触发条件和放行出口。"));
        if (shapes.contains("有质检/不合格处置") && !hasDisposition) issues.add(issue(
                "DISPOSITION_EDGE_REQUIRED", "已选择不合格处置，但没有带判定条件的分支或返工连线", "请确认合格与不合格分别去哪里。"));
    }

    private static void addComplexShapeQuestions(List<String> shapes, List<CompilationIssue> issues) {
        if (shapes.contains("有并行")) issues.add(issue("NEED_PARALLEL_ENDPOINTS", "并行路线缺少分开与汇合位置",
                "并行从哪道工序之后开始，各支路分别到哪里，是否在某道工序重新汇合？"));
        if (shapes.contains("有条件分支")) issues.add(issue("NEED_BRANCH_CONDITION", "条件分支缺少可执行条件和去向",
                "在哪道工序判断什么条件？条件成立与不成立分别进入哪道工序？"));
        if (shapes.contains("有合流")) issues.add(issue("NEED_MERGE_TARGET", "合流缺少来源支路和目标工序",
                "哪些支路在何处合流，合流后进入哪道工序？"));
        if (shapes.contains("有返工")) issues.add(issue("NEED_REWORK_PATH", "返工缺少触发点、返回点和放行出口",
                "哪道工序判定返工，返回哪道工序，复检通过后从哪里放行？"));
        if (shapes.contains("有质检/不合格处置")) issues.add(issue("NEED_DISPOSITION_PATH", "不合格处置缺少判定与去向",
                "在哪道工序检验？合格去哪里，不合格是返工、报废、隔离还是让步放行？"));
    }

    private static int productCount(Map<String, String> facts) {
        int declared = firstInteger(value(facts, "productCount"));
        int discovered = 0;
        for (String key : facts.keySet()) {
            Matcher matcher = PRODUCT_FIELD.matcher(key);
            if (matcher.find()) discovered = Math.max(discovered, Integer.parseInt(matcher.group(1)));
        }
        return Math.min(30, Math.max(declared, discovered));
    }

    private static int firstInteger(String value) {
        Matcher matcher = FIRST_INTEGER.matcher(value);
        return matcher.find() ? Integer.parseInt(matcher.group()) : 0;
    }

    private static List<String> splitSteps(String value) {
        if (value.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String part : STEP_SEPARATOR.split(value)) {
            String step = part.trim();
            if (!step.isBlank()) result.add(step);
        }
        return List.copyOf(result);
    }

    private static List<String> splitValues(String value) {
        if (value.isBlank()) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String part : VALUE_SEPARATOR.split(value)) {
            String item = part.trim();
            if (!item.isBlank()) result.add(item);
        }
        return List.copyOf(result);
    }

    private static String value(Map<String, String> facts, String key) {
        return clean(facts.get(key));
    }

    private static String clean(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    private static CompilationIssue issue(String code, String message, String question) {
        return new CompilationIssue(code, message, question);
    }
}
