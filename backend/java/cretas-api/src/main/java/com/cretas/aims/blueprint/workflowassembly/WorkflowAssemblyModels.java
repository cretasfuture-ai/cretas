package com.cretas.aims.blueprint.workflowassembly;

import com.cretas.aims.blueprint.canonical.FactoryRouteGraphPrototype.RouteGraph;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowPreviewModels.CompileRequest;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.WorkProcess;

import java.util.List;
import java.util.Map;

/**
 * What a client may say about a Workflow DRAFT (its <b>choices</b>), and what the server assembles from them.
 *
 * <p>The two choice records are the only Workflow vocabulary a request body carries: a route node id the
 * server already knows from the blueprint, and the id of a live master-data row. Everything the compiler
 * consumes — route, catalog, BOM input slots, WIP bindings, digests — is built by
 * {@link WorkflowCompileRequestAssembler} from the blueprint and the live tables
 * ({@code DraftEntryRequestsNeverCarryAClientGraphTest} keeps it that way).</p>
 */
public final class WorkflowAssemblyModels {

    private WorkflowAssemblyModels() {
    }

    /** The entry process of the route consumes this live raw material. */
    public record EntryInputChoice(String routeNodeId, String rawMaterialId) {
    }

    /** This non-terminal route node produces this live semi-finished product. */
    public record WipOutputChoice(String routeNodeId, String semiFinishedProductId) {
    }

    /** A precondition the live master data or the blueprint route does not meet yet, with the next step. */
    public record AssemblyBlocker(String code, String message, String nextStep) {
    }

    /**
     * One blueprint route, resolved against the live tables.
     *
     * @param routeIndex     index in {@code blueprint.routeGraphs} (for evidence pointers)
     * @param product        the live finished product, or null when it is not resolvable
     * @param processByNode  route node id → the live work process its label names (only resolved nodes)
     * @param chain          node ids in material-flow order; empty when the route is not a pure chain
     * @param blockers       empty ⇔ the route can be assembled once the user's choices are valid
     */
    public record RouteFacts(String productCode, int routeIndex, RouteGraph graph, ProductType product,
                             Map<String, WorkProcess> processByNode, List<String> chain,
                             List<AssemblyBlocker> blockers) {
        public RouteFacts {
            processByNode = Map.copyOf(processByNode);
            chain = List.copyOf(chain);
            blockers = List.copyOf(blockers);
        }

        public boolean ready() {
            return blockers.isEmpty();
        }

        /** The one node without an incoming edge (a pure chain has exactly one). */
        public String entryNodeId() {
            return chain.getFirst();
        }

        /** Every node except the last: each must produce its own semi-finished product. */
        public List<String> nonTerminalNodeIds() {
            return chain.subList(0, chain.size() - 1);
        }

        public String processName(String nodeId) {
            WorkProcess process = processByNode.get(nodeId);
            return process == null ? nodeId : process.getProcessName();
        }
    }

    /** Either the blockers of the route, or the compile request the server built. Never both. */
    public record Assembly(RouteFacts facts, CompileRequest request) {
        public boolean blocked() {
            return request == null;
        }

        public List<AssemblyBlocker> blockers() {
            return facts.blockers();
        }
    }
}
