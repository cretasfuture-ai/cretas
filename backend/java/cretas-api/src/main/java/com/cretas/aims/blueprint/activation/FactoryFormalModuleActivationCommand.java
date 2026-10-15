package com.cretas.aims.blueprint.activation;

import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.ActivationIssue;
import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.CurrentDraft;
import com.cretas.aims.security.ActorRef;
import java.util.List;

/**
 * Explicit formal phase. Prerequisites are read by the owning formal service; reviewed DRAFTs
 * and preview-only routes are never represented as ACTIVE to satisfy the B25 draft DAG.
 * The enclosing HTTP boundary constructs draft selection IDs and permissions on the server.
 */
public interface FactoryFormalModuleActivationCommand extends FactoryModuleActivationCommand {
    String formalCommandName();
    List<String> formalDependencies();
    List<ActivationIssue> validateFormalPrerequisites(String factoryId, String draftId, ActorRef actor);

    @Override
    CurrentDraft currentDraft(String factoryId, String draftId, ActorRef actor);

    @Override
    default CurrentDraft currentDraft(String factoryId, String draftId) {
        throw new IllegalStateException("Formal activation requires a typed authenticated actor");
    }
}
