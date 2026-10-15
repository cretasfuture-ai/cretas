package com.cretas.aims.blueprint.bomassembly;

import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowRevision;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.DraftRequest;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.MaterialCategory;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a client may say about a BOM DRAFT (its <b>choices</b>), and what the server assembles from them.
 *
 * <p>A BOM line is chosen by the Workflow port it consumes; the quantity, the yield and the category are the
 * user's. Everything that pins the line to live data — the product ref and its snapshot digest, the Workflow
 * revision pin, the material ref and its digest, the Workflow node / edge ids, the unit — is read by
 * {@link BomCompileRequestAssembler} from the live tables. The command re-validates every one of them
 * ({@code BomRecipeDraftCommand}), but a client that never sends them cannot send a stale or forged one.</p>
 */
public final class BomAssemblyModels {

    private BomAssemblyModels() {
    }

    /**
     * One BOM line the user fills in. Wrapper types on purpose: a proposal emits the rows with only the
     * port filled and the rest {@code null}, and a primitive could not be null.
     *
     * @param workflowPortId a raw-material INPUT port of the product's current Workflow DRAFT
     */
    public record BomLineChoice(String workflowPortId, MaterialCategory category, BigDecimal standardQuantity,
                                BigDecimal yieldPercent, Boolean optional) {
    }

    /**
     * The user's choices for one product's BOM.
     *
     * @param routeProductCode which blueprint product (the same key the Workflow proposal uses)
     */
    public record BomChoices(String routeProductCode, BigDecimal outputQuantity, String outputUnit,
                             BigDecimal netContentQuantity, String netContentUnit, String changeReason,
                             List<BomLineChoice> lines) {
        public BomChoices {
            routeProductCode = routeProductCode == null ? "" : routeProductCode.strip();
            outputUnit = outputUnit == null ? "" : outputUnit.strip();
            netContentUnit = netContentUnit == null ? "" : netContentUnit.strip();
            changeReason = changeReason == null ? "" : changeReason.strip();
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** A precondition the live data does not meet yet (or a choice it cannot take), with the next step. */
    public record AssemblyBlocker(String code, String target, String message, String nextStep) {
    }

    /**
     * A raw-material INPUT port of the Workflow DRAFT: the only kind of port a BOM line can bind (a
     * semi-finished input is a product, not a material).
     */
    public record RawInputPort(String processNodeId, String processName, String portId, String materialNodeId,
                               String materialId, String materialName, String unit, String edgeId) {
        public String label() {
            return processName + "：" + materialName + "（" + unit + "）";
        }
    }

    /**
     * The product's live row and its current Workflow DRAFT, and the ports the BOM can bind.
     * {@code blockers} empty ⇔ a request can be assembled once the user's choices are valid.
     */
    public record Facts(String productCode, ProductType product, ProductProcessWorkflow workflow,
                        ProductProcessWorkflowRevision revision, List<RawInputPort> rawInputPorts,
                        List<AssemblyBlocker> blockers) {
        public Facts {
            rawInputPorts = List.copyOf(rawInputPorts);
            blockers = List.copyOf(blockers);
        }

        public boolean ready() {
            return blockers.isEmpty();
        }
    }

    /** Either the blockers, or the command's own request the server built. Never both. */
    public record Assembly(Facts facts, DraftRequest request, List<AssemblyBlocker> blockers) {
        public Assembly {
            blockers = List.copyOf(blockers);
        }

        public boolean blocked() {
            return request == null;
        }
    }
}
