package com.cretas.aims.blueprint.compilation;

import com.cretas.aims.blueprint.canonical.FactoryRouteGraphPrototype.RouteEdge;
import com.cretas.aims.blueprint.canonical.FactoryRouteGraphPrototype.RouteGraph;
import com.cretas.aims.blueprint.canonical.FactoryRouteGraphPrototype.RouteIssue;
import com.cretas.aims.blueprint.canonical.FactoryRouteGraphPrototype.RouteNode;
import com.cretas.aims.blueprint.canonical.FactoryRouteIntentCompiler.RouteCompilation;
import com.cretas.aims.blueprint.canonical.FactoryRouteIntentCompiler.RouteEdgeIntent;
import com.cretas.aims.blueprint.compilation.FactoryBlueprintCompilationModels.CapabilityArtifact;
import com.cretas.aims.blueprint.compilation.FactoryBlueprintCompilationModels.CompilationIssue;
import com.cretas.aims.blueprint.compilation.FactoryBlueprintCompilationModels.IssueSeverity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compatibility adapter that carries B19 route previews forward without mutating their facts. */
public final class FactoryRouteArtifactAdapter implements FactoryCapabilityAdapter {

    @Override
    public String adapterId() {
        return "factory-route-artifact-adapter/v1";
    }

    @Override
    public String capabilityId() {
        return "routes";
    }

    @Override
    public String artifactType() {
        return "ROUTE_GRAPH_PREVIEW";
    }

    @Override
    public String targetSchemaVersion() {
        return "factory-route-graph/v1";
    }

    @Override
    public boolean hasInput(CompilationContext context) {
        return !context.routeCompilations().isEmpty() || !context.factsFor(capabilityId()).isEmpty();
    }

    @Override
    public AdapterOutput compile(CompilationContext context) {
        List<RouteCompilation> routes = context.routeCompilations();
        if (routes.isEmpty()) {
            return new AdapterOutput(null, List.of(new CompilationIssue(
                    "ROUTE_COMPILATION_REQUIRED",
                    IssueSeverity.BLOCKING,
                    capabilityId(),
                    "",
                    "已确认路线事实，但没有可兼容的 B19 路线编译产物",
                    "先由 B19 路线编译器生成并复核只读拓扑")));
        }
        List<CompilationIssue> issues = routes.stream()
                .filter(route -> !"READY".equals(route.status()) || !route.topologyComplete())
                .map(route -> new CompilationIssue(
                        "ROUTE_PREVIEW_NOT_READY",
                        IssueSeverity.BLOCKING,
                        capabilityId(),
                        "",
                        "产品“" + route.productName() + "”的 B19 路线尚未形成完整拓扑",
                        "回到路线确认卡，补齐分支、合流或返工出口后重新编译"))
                .toList();
        if (!issues.isEmpty()) return new AdapterOutput(null, issues);

        List<Map<String, Object>> payloadRoutes = routes.stream().map(this::routePayload).toList();
        List<String> sourceRefs = context.factsFor(capabilityId()).stream()
                .map(fact -> fact.factId()).sorted().toList();
        CapabilityArtifact artifact = new CapabilityArtifact(
                capabilityId(), artifactType(), targetSchemaVersion(),
                Map.of("routes", payloadRoutes, "source", "B19_CONFIRMED_ROUTE_COMPILATION"),
                sourceRefs, "NOT_PUBLISHED", 0);
        return new AdapterOutput(artifact, List.of());
    }

    private Map<String, Object> routePayload(RouteCompilation route) {
        RouteGraph graph = route.graph();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productIndex", route.productIndex());
        payload.put("productName", route.productName());
        payload.put("status", route.status());
        payload.put("previewKind", route.previewKind());
        payload.put("publishState", "NOT_PUBLISHED");
        payload.put("topologyComplete", route.topologyComplete());
        payload.put("routeShapes", route.routeShapes());
        payload.put("routeDetails", route.routeDetails());
        payload.put("sourceFields", route.sourceFields());
        payload.put("graph", graphPayload(graph));
        payload.put("edgeIntents", route.edgeIntents().stream().map(this::edgeIntentPayload).toList());
        return Map.copyOf(payload);
    }

    private Map<String, Object> graphPayload(RouteGraph graph) {
        return Map.of(
                "productCode", graph.productCode(),
                "productName", graph.productName(),
                "nodes", graph.nodes().stream().map(this::nodePayload).toList(),
                "edges", graph.edges().stream().map(this::edgePayload).toList(),
                "entryNodeIds", graph.entryNodeIds(),
                "terminalNodeIds", graph.terminalNodeIds(),
                "validation", Map.of(
                        "valid", graph.validation().valid(),
                        "issues", graph.validation().issues().stream().map(this::routeIssuePayload).toList()));
    }

    private Map<String, Object> nodePayload(RouteNode node) {
        return Map.of("id", node.id(), "label", node.label());
    }

    private Map<String, Object> edgePayload(RouteEdge edge) {
        return Map.of("fromNodeId", edge.fromNodeId(), "toNodeId", edge.toNodeId(), "type", edge.type().name());
    }

    private Map<String, Object> routeIssuePayload(RouteIssue issue) {
        return Map.of("code", issue.code(), "message", issue.message(), "nodeIds", issue.nodeIds());
    }

    private Map<String, Object> edgeIntentPayload(RouteEdgeIntent edge) {
        return Map.of(
                "fromNodeId", edge.fromNodeId(), "fromLabel", edge.fromLabel(),
                "toNodeId", edge.toNodeId(), "toLabel", edge.toLabel(),
                "type", edge.type().name(), "condition", edge.condition(),
                "reworkExit", edge.reworkExit());
    }
}
