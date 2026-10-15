package com.cretas.aims.blueprint.activation;

import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.CurrentDraft;
import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.ModuleActivationCommandRequest;
import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.ModuleCommandReceipt;
import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.RuntimeReadback;

/**
 * Module-owned activation boundary. Implementations must call a named module service and must
 * never accept generic table names, SQL, scripts, arbitrary JSON patches, or AI text.
 */
public interface FactoryModuleActivationCommand {

    String capabilityId();

    CurrentDraft currentDraft(String factoryId, String draftId);

    default CurrentDraft currentDraft(String factoryId, String draftId,
            com.cretas.aims.security.ActorRef actor) {
        return currentDraft(factoryId, draftId);
    }

    ModuleCommandReceipt activate(ModuleActivationCommandRequest request);

    RuntimeReadback readBack(String factoryId, ModuleCommandReceipt receipt);

    String recovery(ModuleCommandReceipt receipt);
}
