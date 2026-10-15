package com.cretas.aims.blueprint.draftentry;

/** Applying factory configuration does not publish its production Workflow or BOM. */
public final class BlueprintDraftLifecycle {
    private BlueprintDraftLifecycle() {}

    public static boolean allows(String state, String capability) {
        return "DRAFT".equals(state) || "VALIDATED".equals(state)
                || (("PUBLISHED".equals(state) || "APPLIED".equals(state))
                    && ("workflow".equals(capability) || "bom".equals(capability)));
    }
}
