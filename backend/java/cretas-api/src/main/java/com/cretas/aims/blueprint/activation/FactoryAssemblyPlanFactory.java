package com.cretas.aims.blueprint.activation;

import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.ActivationPlanRequest;
import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.DraftCandidate;
import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.CurrentDraft;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintService;
import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationCenter;
import com.cretas.aims.security.ActorRef;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Builds the activation request from server-owned canonical state and typed selections. */
public final class FactoryAssemblyPlanFactory {
    private final CanonicalFactoryBlueprintService blueprints;
    private final FactoryBlueprintActivationCenter center;
    private final FactoryFormalModuleActivationCommand modules;
    private final FactoryFormalModuleActivationCommand workflow;

    public FactoryAssemblyPlanFactory(CanonicalFactoryBlueprintService blueprints,
            FactoryBlueprintActivationCenter center,
            FactoryFormalModuleActivationCommand modules,
            FactoryFormalModuleActivationCommand workflow) {
        this.blueprints = Objects.requireNonNull(blueprints, "blueprints");
        this.center = Objects.requireNonNull(center, "center");
        this.modules = requireCapability(modules, "modules");
        this.workflow = requireCapability(workflow, "workflow");
    }

    public record Selection(String capabilityId, String configDraftId, Long workflowDraftId,
                            String bomDraftId, String criticalControlsDraftId) {
        public Selection {
            capabilityId = clean(capabilityId);
            configDraftId = clean(configDraftId);
            bomDraftId = clean(bomDraftId);
            criticalControlsDraftId = clean(criticalControlsDraftId);
            if (!Set.of("modules", "workflow").contains(capabilityId)) {
                throw new IllegalArgumentException("capabilityId must be modules or workflow");
            }
            if (bomDraftId.contains(";") || criticalControlsDraftId.contains(";")) {
                throw new IllegalArgumentException("selection IDs must not contain ';'");
            }
            if ("modules".equals(capabilityId)
                    ? configDraftId.isBlank() || workflowDraftId != null || !bomDraftId.isBlank()
                        || !criticalControlsDraftId.isBlank()
                    : workflowDraftId == null || workflowDraftId <= 0 || bomDraftId.isBlank()
                        || !configDraftId.isBlank()) {
                throw new IllegalArgumentException("selection contains IDs for the wrong capability");
            }
        }

        public static Selection modules(String configDraftId) {
            return new Selection("modules", configDraftId, null, null, null);
        }

        public static Selection workflow(Long workflowDraftId, String bomDraftId,
                                         String criticalControlsDraftId) {
            return new Selection("workflow", null, workflowDraftId, bomDraftId, criticalControlsDraftId);
        }
    }

    public ActivationPlanRequest build(String factoryId, Selection selection, ActorRef actor) {
        Objects.requireNonNull(actor, "actor");
        BlueprintResponse blueprint = blueprints.current(requireText(factoryId, "factoryId"));
        if (blueprint == null || !factoryId.equals(blueprint.getFactoryId())) {
            throw new IllegalArgumentException("current factory blueprint is unavailable");
        }
        String sourceDigest = center.serverBlueprintDigest(blueprint);
        DraftCandidate candidate;
        Set<String> permissions = new LinkedHashSet<>();
        if ("modules".equals(selection.capabilityId())) {
            CurrentDraft current = modules.currentDraft(factoryId, selection.configDraftId(), actor);
            candidate = new DraftCandidate("modules", current.draftId(), current.revision(),
                    current.digest(), current.state(), java.util.Map.of());
            permissions.add("factory-blueprint:activation:modules");
        } else {
            String encoded = encodeWorkflow(selection);
            CurrentDraft current = workflow.currentDraft(factoryId, encoded, actor);
            candidate = new DraftCandidate("workflow", current.draftId(), current.revision(),
                    current.digest(), current.state(), java.util.Map.of());
            permissions.add("factory-blueprint:activation:workflow");
        }
        return new ActivationPlanRequest(factoryId, blueprint.getId(), blueprint.getVersion(), sourceDigest,
                actor, permissions, List.of(candidate));
    }

    private static String encodeWorkflow(Selection selection) {
        String encoded = "w=" + selection.workflowDraftId()
                + ";b=" + selection.bomDraftId();
        if (!selection.criticalControlsDraftId().isBlank()) {
            encoded += ";c=" + selection.criticalControlsDraftId();
        }
        return encoded;
    }

    static String requireText(String value, String name) {
        String clean = clean(value);
        if (clean.isBlank()) throw new IllegalArgumentException(name + " is required");
        return clean;
    }

    static String clean(String value) { return value == null ? "" : value.trim(); }

    private static FactoryFormalModuleActivationCommand requireCapability(
            FactoryFormalModuleActivationCommand command, String capability) {
        if (command == null || !capability.equals(command.capabilityId())) {
            throw new IllegalArgumentException("formal command capability mismatch: " + capability);
        }
        return command;
    }
}
