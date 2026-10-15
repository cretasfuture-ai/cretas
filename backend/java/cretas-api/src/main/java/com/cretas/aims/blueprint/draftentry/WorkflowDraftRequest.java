package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.workflowassembly.WorkflowAssemblyModels.EntryInputChoice;
import com.cretas.aims.blueprint.workflowassembly.WorkflowAssemblyModels.WipOutputChoice;
import com.cretas.aims.blueprint.workflowmaterialize.FactoryWorkflowDraftMaterializationModels.DraftMode;

import java.util.List;

/**
 * The body of a Workflow DRAFT preview / materialize: the blueprint lineage, the user's choices and the
 * confirmation fields — and nothing the compiler consumes. Route, catalog, BOM input slots and WIP
 * bindings are assembled on the server ({@code WorkflowCompileRequestAssembler});
 * {@code DraftEntryRequestsNeverCarryAClientGraphTest} walks this record and fails if any of them becomes
 * reachable from it.
 *
 * @param routeProductCode     which blueprint route ({@code routeGraphs[].productCode})
 * @param entryInputs          raw materials the entry process consumes (live raw material ids)
 * @param extraInputs          raw materials a process <em>after</em> the entry also consumes (seasoning,
 *                             packaging, ...): route node id of a non-entry node + live raw material id. Optional.
 * @param wipOutputs           the semi-finished product each non-terminal process produces (live ids)
 * @param mode                 conflict mode; null means {@code CREATE_NEW_ONLY}
 * @param expectedLockVersion  MERGE_EXISTING / SNAPSHOT_AND_REPLACE: the existing DRAFT's lock version
 * @param expectedRevisionHash MERGE_EXISTING / SNAPSHOT_AND_REPLACE: the existing DRAFT's revision hash
 * @param operatorId           server-owned: re-signed by {@code BlueprintDraftController}, 0 on preview
 */
public record WorkflowDraftRequest(
        String factoryId,
        String blueprintId,
        long blueprintVersion,
        String sourceDigest,
        String routeProductCode,
        List<EntryInputChoice> entryInputs,
        List<EntryInputChoice> extraInputs,
        List<WipOutputChoice> wipOutputs,
        DraftMode mode,
        Long expectedLockVersion,
        String expectedRevisionHash,
        String confirmedPreviewDigest,
        String confirmedOperationDigest,
        String idempotencyKey,
        long operatorId) {

    public WorkflowDraftRequest {
        entryInputs = entryInputs == null ? List.of() : List.copyOf(entryInputs);
        extraInputs = extraInputs == null ? List.of() : List.copyOf(extraInputs);
        wipOutputs = wipOutputs == null ? List.of() : List.copyOf(wipOutputs);
    }
}
