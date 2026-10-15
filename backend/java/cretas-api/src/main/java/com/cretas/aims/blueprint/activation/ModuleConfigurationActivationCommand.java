package com.cretas.aims.blueprint.activation;

import com.cretas.aims.security.ActorRef;

/** Typed SPI for applying a validated module configuration draft. */
public interface ModuleConfigurationActivationCommand {
    BlueprintModuleConfigurationApplyService.ApplyReceipt apply(String factoryId, String draftId, ActorRef actor);
}
