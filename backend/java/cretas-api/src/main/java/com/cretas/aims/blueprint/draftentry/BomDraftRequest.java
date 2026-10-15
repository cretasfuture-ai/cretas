package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.bomassembly.BomAssemblyModels.BomChoices;
import com.cretas.aims.blueprint.bomassembly.BomAssemblyModels.BomLineChoice;

import java.math.BigDecimal;
import java.util.List;

/**
 * The body of a BOM DRAFT preview / materialize: the blueprint lineage, the user's choices and the
 * confirmation fields — and none of the pins. The product ref and its digest, the Workflow revision pin, the
 * material refs and digests, the Workflow node / edge ids and the units are read on the server
 * ({@code BomCompileRequestAssembler}) from the live tables; a body that tries to send one is refused as an
 * unknown field.
 *
 * @param routeProductCode which blueprint product ({@code routeGraphs[].productCode})
 * @param outputQuantity   how much the recipe makes, in {@code outputUnit}
 * @param outputUnit       the product's output unit (the command checks its dimension against the product)
 * @param netContentQuantity optional net content per unit; pairs with {@code netContentUnit}
 * @param changeReason     why the recipe is this way; written on every line
 * @param lines            one row per Workflow raw-material input port the recipe uses
 * @param operatorId       server-owned: re-signed by {@code BlueprintDraftController}, 0 on preview
 */
public record BomDraftRequest(
        String factoryId,
        String blueprintId,
        long blueprintVersion,
        String sourceDigest,
        String routeProductCode,
        BigDecimal outputQuantity,
        String outputUnit,
        BigDecimal netContentQuantity,
        String netContentUnit,
        String changeReason,
        List<BomLineChoice> lines,
        String confirmedPreviewDigest,
        String idempotencyKey,
        long operatorId) {

    public BomDraftRequest {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    BomChoices choices() {
        return new BomChoices(routeProductCode, outputQuantity, outputUnit, netContentQuantity, netContentUnit,
                changeReason, lines);
    }
}
