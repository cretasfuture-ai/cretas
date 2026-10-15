package com.cretas.aims.blueprint.activation;

import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintService;
import com.cretas.aims.repository.*;
import com.cretas.aims.repository.blueprintapproval.FactoryAssemblyApprovalRepository;
import com.cretas.aims.repository.bomdraft.*;
import com.cretas.aims.repository.config.FactoryTypeBlueprintRepository;
import com.cretas.aims.repository.criticalcontroldraft.CriticalControlDraftRepository;
import com.cretas.aims.repository.hazardanalysisdraft.HazardAnalysisDraftRepository;
import com.cretas.aims.repository.moduleconfig.ModuleConfigDraftRepository;
import com.cretas.aims.repository.moduleconfig.ModuleConfigDraftItemRepository;
import com.cretas.aims.repository.promotion.MasterDataDraftPromotionRepository;
import com.cretas.aims.service.ProductProcessWorkflowService;
import com.cretas.aims.service.bom.BomRecipeService;
import com.cretas.aims.service.bomdraft.BomRecipeDraftCommand;
import com.cretas.aims.service.bomdraft.BomRecipeDraftPromotionAuthority;
import com.cretas.aims.service.config.FactoryConfigService;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftCommand;
import com.cretas.aims.service.hazardanalysisdraft.HazardAnalysisDraftCommand;
import com.cretas.aims.service.moduleconfigdraft.ModuleConfigDraftCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicitly registers only the two independently confirmed formal actions. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Configuration
public class FactoryAssemblyActivationConfiguration {
    @Bean
    public BlueprintWorkflowPublicationService blueprintWorkflowPublicationService(
            AssemblyActivationActorResolver actors, CanonicalFactoryBlueprintService blueprints,
            FactoryTypeBlueprintRepository blueprintRows, ProductProcessWorkflowRepository workflows,
            BomRecipeDraftRepository bomDrafts, BomRecipeDraftItemRepository bomDraftItems,
            CriticalControlDraftRepository criticalControls, HazardAnalysisDraftRepository hazards,
            MasterDataDraftPromotionRepository promotions, BomRecipeService recipes,
            ProductProcessWorkflowService workflowService, BomRecipeDraftCommand bomDraftCommand,
            HazardAnalysisDraftCommand hazardCommand, CriticalControlDraftCommand criticalCommand,
            BomRecipeDraftPromotionAuthority promotionAuthority) {
        return new BlueprintWorkflowPublicationService(actors, blueprints, blueprintRows, workflows,
                bomDrafts, bomDraftItems, criticalControls, hazards, promotions, recipes, workflowService,
                bomDraftCommand, hazardCommand, criticalCommand, promotionAuthority);
    }

    @Bean
    public ModuleConfigurationFormalActivationCommand moduleConfigurationFormalActivationCommand(
            AssemblyActivationActorResolver actors, ModuleConfigDraftCommand drafts,
            ModuleConfigDraftRepository rows, ModuleConfigDraftItemRepository itemRows, BlueprintModuleConfigurationApplyService apply,
            FactoryConfigService runtime, FactoryFeatureConfigRepository features) {
        return new ModuleConfigurationFormalActivationCommand(actors, drafts, rows, itemRows, apply, runtime, features);
    }

    @Bean
    public WorkflowPublicationFormalActivationCommand workflowPublicationFormalActivationCommand(
            AssemblyActivationActorResolver actors, BlueprintWorkflowPublicationService publication,
            CanonicalFactoryBlueprintService blueprints, ProductProcessWorkflowRepository workflows,
            ProductProcessWorkflowActivationRepository activations, BomRecipeService recipes) {
        return new WorkflowPublicationFormalActivationCommand(actors, publication, blueprints, workflows,
                activations, recipes);
    }

    @Bean
    public FactoryAssemblyPlanFactory factoryAssemblyPlanFactory(CanonicalFactoryBlueprintService blueprints,
            FactoryBlueprintActivationCenter center,
            ModuleConfigurationFormalActivationCommand modules, WorkflowPublicationFormalActivationCommand workflow) {
        return new FactoryAssemblyPlanFactory(blueprints, center, modules, workflow);
    }

    @Bean
    public FactoryAssemblyApprovalLifecycle factoryAssemblyApprovalLifecycle(
            FactoryAssemblyApprovalRepository approvals, FactoryBlueprintActivationCenter center,
            AssemblyActivationActorResolver actors, ObjectMapper json, FactoryActivationExecutionStore executionStore) {
        return new FactoryAssemblyApprovalLifecycle(approvals, center, actors, json, Clock.systemUTC(), executionStore);
    }
}
