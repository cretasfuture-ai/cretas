package com.cretas.aims.blueprint.canonical;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only route graph carried by Factory Blueprint responses.
 *
 * <p>This prototype deliberately has no JPA entity, repository or migration. It makes branch,
 * merge and explicit rework semantics representable before any shared Workflow persistence
 * contract is changed.</p>
 */
public final class FactoryRouteGraphPrototype {

    private FactoryRouteGraphPrototype() {
    }

    public enum EdgeType {
        FORWARD,
        BRANCH,
        MERGE,
        REWORK
    }

    public record RouteNode(String id, String label) {
        public RouteNode {
            id = Objects.requireNonNullElse(id, "").trim();
            label = Objects.requireNonNullElse(label, "").trim();
        }
    }

    public record RouteEdge(String fromNodeId, String toNodeId, EdgeType type) {
        public RouteEdge {
            fromNodeId = Objects.requireNonNullElse(fromNodeId, "").trim();
            toNodeId = Objects.requireNonNullElse(toNodeId, "").trim();
            type = type == null ? EdgeType.FORWARD : type;
        }
    }

    public record RouteIssue(String code, String message, List<String> nodeIds) {
        public RouteIssue {
            nodeIds = nodeIds == null ? List.of() : List.copyOf(nodeIds);
        }
    }

    public record RouteValidation(boolean valid, List<RouteIssue> issues) {
        public RouteValidation {
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    public record RouteGraph(
            String productCode,
            String productName,
            List<RouteNode> nodes,
            List<RouteEdge> edges,
            List<String> entryNodeIds,
            List<String> terminalNodeIds,
            RouteValidation validation) {
        public RouteGraph {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            edges = edges == null ? List.of() : List.copyOf(edges);
            entryNodeIds = entryNodeIds == null ? List.of() : List.copyOf(entryNodeIds);
            terminalNodeIds = terminalNodeIds == null ? List.of() : List.copyOf(terminalNodeIds);
        }
    }

    public static RouteNode node(String id, String label) {
        return new RouteNode(id, label);
    }

    public static RouteEdge edge(String from, String to, EdgeType type) {
        return new RouteEdge(from, to, type);
    }

    public static RouteGraph linear(String productCode, String productName, List<String> processNames) {
        List<String> names = processNames == null ? List.of() : processNames;
        List<RouteNode> nodes = new ArrayList<>();
        List<RouteEdge> edges = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String id = "process-" + (i + 1);
            nodes.add(node(id, names.get(i)));
            if (i > 0) {
                edges.add(edge("process-" + i, id, EdgeType.FORWARD));
            }
        }
        return graph(productCode, productName, nodes, edges);
    }

    public static RouteGraph graph(
            String productCode,
            String productName,
            List<RouteNode> nodes,
            List<RouteEdge> edges) {
        List<RouteNode> safeNodes = nodes == null ? List.of() : List.copyOf(nodes);
        List<RouteEdge> safeEdges = edges == null ? List.of() : List.copyOf(edges);
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        safeNodes.forEach(node -> ids.add(node.id()));

        Map<String, Integer> incoming = new LinkedHashMap<>();
        Map<String, Integer> outgoing = new LinkedHashMap<>();
        ids.forEach(id -> {
            incoming.put(id, 0);
            outgoing.put(id, 0);
        });
        safeEdges.stream()
                .filter(edge -> ids.contains(edge.fromNodeId()) && ids.contains(edge.toNodeId()))
                .forEach(edge -> {
                    outgoing.computeIfPresent(edge.fromNodeId(), (ignored, count) -> count + 1);
                    incoming.computeIfPresent(edge.toNodeId(), (ignored, count) -> count + 1);
                });
        List<String> entries = ids.stream().filter(id -> incoming.get(id) == 0).toList();
        List<String> terminals = ids.stream().filter(id -> outgoing.get(id) == 0).toList();
        RouteValidation validation = validate(safeNodes, safeEdges, entries, terminals);
        return new RouteGraph(productCode, productName, safeNodes, safeEdges,
                entries, terminals, validation);
    }

    private static RouteValidation validate(
            List<RouteNode> nodes,
            List<RouteEdge> edges,
            List<String> entries,
            List<String> terminals) {
        List<RouteIssue> issues = new ArrayList<>();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        LinkedHashSet<String> duplicates = new LinkedHashSet<>();
        for (RouteNode node : nodes) {
            if (node.id().isBlank() || node.label().isBlank()) {
                issues.add(issue("INVALID_NODE", "路线节点必须同时有稳定 ID 和名称", node.id()));
            }
            if (!ids.add(node.id())) duplicates.add(node.id());
        }
        if (!duplicates.isEmpty()) {
            issues.add(new RouteIssue("DUPLICATE_NODE", "路线节点 ID 重复", new ArrayList<>(duplicates)));
        }
        LinkedHashSet<String> unknown = new LinkedHashSet<>();
        for (RouteEdge edge : edges) {
            if (!ids.contains(edge.fromNodeId())) unknown.add(edge.fromNodeId());
            if (!ids.contains(edge.toNodeId())) unknown.add(edge.toNodeId());
        }
        if (!unknown.isEmpty()) {
            issues.add(new RouteIssue("UNKNOWN_EDGE_NODE", "连线引用了不存在的节点", new ArrayList<>(unknown)));
        }
        Set<String> connected = new LinkedHashSet<>();
        edges.stream()
                .filter(edge -> ids.contains(edge.fromNodeId()) && ids.contains(edge.toNodeId()))
                .forEach(edge -> {
                    connected.add(edge.fromNodeId());
                    connected.add(edge.toNodeId());
                });
        // A one-step product is a complete route by itself: the same node is both entry and
        // terminal, so the absence of an edge is not an isolation defect.
        List<String> isolated = ids.size() <= 1 ? List.of()
                : ids.stream().filter(id -> !connected.contains(id)).toList();
        if (!isolated.isEmpty()) {
            issues.add(new RouteIssue("ISOLATED_NODE", "存在没有任何连线的孤立节点", isolated));
        }
        if (entries.isEmpty()) {
            issues.add(new RouteIssue("NO_ENTRY", "路线没有可进入的起点", List.of()));
        }
        if (terminals.isEmpty()) {
            issues.add(new RouteIssue("NO_TERMINAL", "路线没有可完成的终点", List.of()));
        }

        Map<String, List<String>> forward = adjacency(ids, edges, false);
        Map<String, List<String>> reverse = reverseAdjacency(ids, edges);
        if (!entries.isEmpty()) {
            Set<String> reachable = traverse(entries, forward);
            List<String> unreachable = ids.stream().filter(id -> !reachable.contains(id)).toList();
            if (!unreachable.isEmpty()) {
                issues.add(new RouteIssue("UNREACHABLE_NODE", "存在从起点无法到达的孤立节点", unreachable));
            }
        }
        if (!terminals.isEmpty()) {
            Set<String> canFinish = traverse(terminals, reverse);
            List<String> trapped = ids.stream().filter(id -> !canFinish.contains(id)).toList();
            if (!trapped.isEmpty()) {
                issues.add(new RouteIssue("NO_TERMINAL_PATH", "存在无法到达终点的节点", trapped));
            }
        }
        List<String> cycle = findCycle(ids, adjacency(ids, edges, true));
        if (!cycle.isEmpty()) {
            issues.add(new RouteIssue(
                    "NON_REWORK_CYCLE", "普通流转形成闭环；循环必须显式标成返工并保留放行出口", cycle));
        }
        return new RouteValidation(issues.isEmpty(), issues);
    }

    private static Map<String, List<String>> adjacency(
            Set<String> ids, List<RouteEdge> edges, boolean omitRework) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        ids.forEach(id -> result.put(id, new ArrayList<>()));
        edges.stream()
                .filter(edge -> !omitRework || edge.type() != EdgeType.REWORK)
                .filter(edge -> ids.contains(edge.fromNodeId()) && ids.contains(edge.toNodeId()))
                .forEach(edge -> result.get(edge.fromNodeId()).add(edge.toNodeId()));
        return result;
    }

    private static Map<String, List<String>> reverseAdjacency(
            Set<String> ids, List<RouteEdge> edges) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        ids.forEach(id -> result.put(id, new ArrayList<>()));
        edges.stream()
                .filter(edge -> ids.contains(edge.fromNodeId()) && ids.contains(edge.toNodeId()))
                .forEach(edge -> result.get(edge.toNodeId()).add(edge.fromNodeId()));
        return result;
    }

    private static Set<String> traverse(List<String> starts, Map<String, List<String>> graph) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(starts);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!visited.add(current)) continue;
            queue.addAll(graph.getOrDefault(current, List.of()));
        }
        return visited;
    }

    private static List<String> findCycle(Set<String> ids, Map<String, List<String>> graph) {
        Set<String> visited = new HashSet<>();
        Set<String> active = new HashSet<>();
        Deque<String> path = new ArrayDeque<>();
        for (String id : ids) {
            List<String> cycle = findCycleFrom(id, graph, visited, active, path);
            if (!cycle.isEmpty()) return cycle;
        }
        return List.of();
    }

    private static List<String> findCycleFrom(
            String node,
            Map<String, List<String>> graph,
            Set<String> visited,
            Set<String> active,
            Deque<String> path) {
        if (active.contains(node)) {
            List<String> current = new ArrayList<>(path);
            int index = current.indexOf(node);
            return index < 0 ? List.of(node) : current.subList(index, current.size());
        }
        if (!visited.add(node)) return List.of();
        active.add(node);
        path.addLast(node);
        for (String next : graph.getOrDefault(node, List.of())) {
            List<String> cycle = findCycleFrom(next, graph, visited, active, path);
            if (!cycle.isEmpty()) return cycle;
        }
        path.removeLast();
        active.remove(node);
        return List.of();
    }

    private static RouteIssue issue(String code, String message, String nodeId) {
        return new RouteIssue(code, message,
                nodeId == null || nodeId.isBlank() ? List.of() : List.of(nodeId));
    }
}
