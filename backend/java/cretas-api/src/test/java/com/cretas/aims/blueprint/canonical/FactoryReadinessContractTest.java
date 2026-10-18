package com.cretas.aims.blueprint.canonical;

import com.cretas.aims.blueprint.activation.FactoryFormalRuntimeReadback;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.*;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowActivation;
import com.cretas.aims.entity.ProductProcessWorkflowRevision;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.config.FactoryBlueprintBinding;
import com.cretas.aims.repository.*;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.config.FactoryBlueprintBindingRepository;
import com.cretas.aims.service.indicator.FactoryIndicatorApplicabilityService;
import com.cretas.aims.service.workflow.ProductWorkflowResolutionService;
import com.cretas.aims.service.workflow.WorkflowProcessPath;
import com.cretas.aims.exception.BusinessException;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FactoryReadinessContractTest {
    private final CanonicalFactoryBlueprintService blueprints = mock(CanonicalFactoryBlueprintService.class);
    private final FactoryFormalRuntimeReadback committed = mock(FactoryFormalRuntimeReadback.class);
    private final ProductTypeRepository products = mock(ProductTypeRepository.class);
    private final ProductProcessWorkflowActivationRepository activations = mock(ProductProcessWorkflowActivationRepository.class);
    private final ProductProcessWorkflowRepository workflows = mock(ProductProcessWorkflowRepository.class);
    private final ProductProcessWorkflowRevisionRepository revisions = mock(ProductProcessWorkflowRevisionRepository.class);
    private final BomRecipeRepository recipes = mock(BomRecipeRepository.class);
    private final FactoryBlueprintBindingRepository bindings = mock(FactoryBlueprintBindingRepository.class);
    private final FactoryIndicatorApplicabilityService indicators = mock(FactoryIndicatorApplicabilityService.class);
    private final ProductWorkflowResolutionService resolution = mock(ProductWorkflowResolutionService.class);
    private final FactoryReadinessService service = new FactoryReadinessService(blueprints, committed, products,
            activations, workflows, revisions, recipes, bindings, indicators, resolution);
    private final BlueprintResponse candidate = BlueprintResponse.builder().id("candidate").factoryId("FA")
            .version(2).rowVersion(8L).lifecycleState("DRAFT").capabilities(List.of()).build();

    @BeforeEach void setup() {
        when(committed.read(any())).thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(0).get());
        when(blueprints.current("FA")).thenReturn(candidate);
        when(products.findVisibleByFactoryIdAndIsActiveTrue("FA")).thenReturn(List.of());
    }

    @Test void acceptedConfigurationCannotMasqueradeAsBusinessAcceptance() {
        var capability = CapabilityView.builder().id("products").label("成品").included(true)
                .configurationApplyState("APPLIED").acceptanceState("ACCEPTED")
                .configurationReceipts(List.of(Map.of("status", "COMPLETED"))).build();
        candidate.setCapabilities(List.of(capability));
        var result = service.read("FA", "candidate");
        assertThat(result.capabilities().getFirst().stage()).isEqualTo("CONFIGURATION_ACCEPTED");
        assertThat(result.businessAcceptanceState()).isEqualTo("UNVERIFIED");
        assertThat(result.businessWriteCount()).isZero();
        verifyNoMoreInteractions(activations, workflows, recipes);
        verify(indicators).assess("FA");
        verify(indicators).assessPlanned("FA", List.of());
    }

    @Test void failedReceiptOrRejectionBlocksEvenIfConfigurationClaimsApplied() {
        var capability = CapabilityView.builder().id("products").included(true).configurationApplyState("APPLIED")
                .acceptanceState("ACCEPTED").configurationReceipts(List.of(Map.of("status", "FAILED"))).build();
        assertThat(FactoryReadinessService.capability(capability).stage()).isEqualTo("BLOCKED");
        capability.setConfigurationReceipts(List.of(Map.of("status", "COMPLETED")));
        capability.setAcceptanceState("PENDING");
        assertThat(FactoryReadinessService.capability(capability).stage()).isEqualTo("WAITING_REVIEW");
        capability.setAcceptanceState("REJECTED");
        assertThat(FactoryReadinessService.capability(capability).stage()).isEqualTo("BLOCKED");
        capability.setIncluded(false);
        assertThat(FactoryReadinessService.capability(capability).stage()).isEqualTo("NOT_APPLICABLE");
    }

    @Test void newCandidateKeepsOldAppliedBindingAndActiveRuntimeDistinctWithoutWrites() {
        var binding = FactoryBlueprintBinding.builder().factoryId("FA").blueprintId("old")
                .appliedVersion(1).build();
        when(bindings.findByFactoryIdAndDeletedAtIsNull("FA")).thenReturn(Optional.of(binding));
        ProductType product = new ProductType(); product.setId("p1"); product.setName("旧盒装"); product.setProductCategory("FINISHED_PRODUCT"); product.setIsActive(true);
        when(products.findVisibleByFactoryIdAndIsActiveTrue("FA")).thenReturn(List.of(product));
        ProductProcessWorkflow workflow = new ProductProcessWorkflow(); workflow.setId(7L);
        workflow.setFactoryId("FA"); workflow.setProductTypeId("p1"); workflow.setStatus(ProductProcessWorkflow.Status.PUBLISHED);
        workflow.setDefinitionVersion(1); workflow.setCurrentRevisionId(11L); workflow.setCurrentRevisionHash("published-hash");
        ProductProcessWorkflowRevision revision = new ProductProcessWorkflowRevision();
        revision.setId(11L); revision.setFactoryId("FA"); revision.setProductTypeId("p1");
        revision.setWorkflowId(7L); revision.setDefinitionVersion(1); revision.setRevisionHash("published-hash");
        revision.setStatus(ProductProcessWorkflowRevision.Status.PUBLISHED);
        when(revisions.findByIdAndFactoryId(11L, "FA")).thenReturn(Optional.of(revision));
        ProductProcessWorkflowActivation activation = new ProductProcessWorkflowActivation();
        activation.setActiveWorkflowId(7L); activation.setActiveDefinitionVersion(1); activation.setEnabled(true);
        when(activations.findByFactoryIdAndProductTypeId("FA", "p1")).thenReturn(Optional.of(activation));
        when(workflows.findByIdAndFactoryId(7L, "FA")).thenReturn(Optional.of(workflow));
        BomRecipe recipe = new BomRecipe(); recipe.setId("old-bom"); recipe.setVersion(1);
        recipe.setWorkflowId(7L); recipe.setWorkflowRevisionId(11L);
        recipe.setWorkflowDefinitionVersion(1); recipe.setWorkflowRevisionHash("published-hash");
        when(recipes.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus("FA", "p1", BomRecipe.Status.ACTIVE))
                .thenReturn(Optional.of(recipe));
        when(resolution.resolveProcessPath("FA", "p1")).thenReturn(Optional.of(
                new WorkflowProcessPath(7L, 1, "p1", "PRODUCT", "p1", null, List.of())));
        var result = service.read("FA", "candidate");
        assertThat(result.appliedVersion().blueprintId()).isEqualTo("old");
        assertThat(result.appliedVersion().candidateDiffers()).isTrue();
        assertThat(result.currentRuntime().getFirst().state()).isEqualTo("PUBLISHED");
        assertThat(candidate.getLifecycleState()).isEqualTo("DRAFT");
        assertThat(binding.getBlueprintId()).isEqualTo("old");
        for (String category : new String[]{null, "CONTRACT_MANUFACTURING"}) {
            product.setProductCategory(category);
            assertThat(service.read("FA", "candidate").currentRuntime().getFirst().state()).isEqualTo("PUBLISHED");
        }
        revision.setStatus(ProductProcessWorkflowRevision.Status.DRAFT);
        assertThat(service.read("FA", "candidate").currentRuntime().getFirst().state()).isEqualTo("NEEDS_REVIEW");
        revision.setStatus(ProductProcessWorkflowRevision.Status.PUBLISHED);
        revision.setFactoryId("FB");
        assertThat(service.read("FA", "candidate").currentRuntime().getFirst().state()).isEqualTo("NEEDS_REVIEW");
        revision.setFactoryId("FA"); revision.setProductTypeId("other");
        assertThat(service.read("FA", "candidate").currentRuntime().getFirst().state()).isEqualTo("NEEDS_REVIEW");
        revision.setProductTypeId("p1"); revision.setWorkflowId(99L);
        assertThat(service.read("FA", "candidate").currentRuntime().getFirst().state()).isEqualTo("NEEDS_REVIEW");
        revision.setWorkflowId(7L); revision.setDefinitionVersion(2);
        assertThat(service.read("FA", "candidate").currentRuntime().getFirst().state()).isEqualTo("NEEDS_REVIEW");
        revision.setDefinitionVersion(1); revision.setRevisionHash("drifted");
        assertThat(service.read("FA", "candidate").currentRuntime().getFirst().state()).isEqualTo("NEEDS_REVIEW");
        revision.setRevisionHash("published-hash"); recipe.setWorkflowRevisionHash("drifted");
        assertThat(service.read("FA", "candidate").currentRuntime().getFirst().state()).isEqualTo("NEEDS_REVIEW");
        recipe.setWorkflowRevisionHash("published-hash"); recipe.setWorkflowDefinitionVersion(2);
        assertThat(service.read("FA", "candidate").currentRuntime().getFirst().state()).isEqualTo("NEEDS_REVIEW");
        recipe.setWorkflowDefinitionVersion(1);
        recipe.setWorkflowRevisionId(99L);
        assertThat(service.read("FA", "candidate").currentRuntime().getFirst().state()).isEqualTo("NEEDS_REVIEW");
        recipe.setWorkflowRevisionId(11L);
        // Shared owner graph produces p1 and p2; p2 must keep its own BOM and target identity.
        ProductType second = new ProductType(); second.setId("p2"); second.setName("第二成品"); second.setIsActive(true);
        when(products.findVisibleByFactoryIdAndIsActiveTrue("FA")).thenReturn(List.of(product, second));
        when(resolution.resolveProcessPath("FA", "p2")).thenReturn(Optional.of(
                new WorkflowProcessPath(7L, 1, "p1", "PRODUCT", "p2", null, List.of())));
        BomRecipe secondRecipe = new BomRecipe(); secondRecipe.setId("second-bom"); secondRecipe.setVersion(1);
        secondRecipe.setWorkflowId(7L); secondRecipe.setWorkflowRevisionId(11L);
        secondRecipe.setWorkflowDefinitionVersion(1); secondRecipe.setWorkflowRevisionHash("published-hash");
        when(recipes.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus("FA", "p2", BomRecipe.Status.ACTIVE))
                .thenReturn(Optional.of(secondRecipe));
        assertThat(service.read("FA", "candidate").currentRuntime()).allMatch(row -> row.state().equals("PUBLISHED"));
        assertThat(service.read("FA", "candidate").currentRuntime().get(1).bomId()).isEqualTo("second-bom");
        verify(activations, never()).findByFactoryIdAndProductTypeId("FA", "p2");
        secondRecipe.setWorkflowRevisionHash("stale");
        assertThat(service.read("FA", "candidate").currentRuntime().get(1).state()).isEqualTo("NEEDS_REVIEW");
        when(resolution.resolveProcessPath("FA", "p2")).thenThrow(new BusinessException(409, "工艺解析存在歧义"));
        assertThatThrownBy(() -> service.read("FA", "candidate")).isInstanceOf(BusinessException.class);
        doThrow(new IllegalStateException("database unavailable")).when(resolution).resolveProcessPath("FA", "p2");
        assertThatThrownBy(() -> service.read("FA", "candidate")).hasMessage("database unavailable");
        verify(bindings, never()).save(any()); verify(recipes, never()).save(any());
        verify(workflows, never()).save(any()); verify(activations, never()).save(any());
    }

    @Test void staleOrForeignBlueprintIsRejectedBeforeAnyRuntimeRead() {
        assertThatThrownBy(() -> service.read("FA", "old")).hasMessageContaining("蓝图版本已变化");
        candidate.setFactoryId("FB");
        assertThatThrownBy(() -> service.read("FA", "candidate")).hasMessageContaining("蓝图版本已变化");
        verifyNoInteractions(products, activations, recipes, indicators, bindings);
    }

    @Test void plannedMultipleTerminalsPreserveMultiOutputAndMissingByproductFactsStayUnknown() {
        var data = new ApplyAIInitRequest.SuggestedData(); var payload = new ApplyAIInitRequest(); payload.setSuggestedData(data);
        var product = new ApplyAIInitRequest.TypeItem(); product.setCode("p1"); product.setName("成品"); product.setUnit("kg");
        var step = new ApplyAIInitRequest.WorkProcessItem(); step.setName("加工"); step.setUnit("g");
        data.setProductTypes(List.of(product)); data.setWorkProcesses(List.of(step)); candidate.setSourcePayload(payload);
        candidate.setRouteGraphs(List.of(FactoryRouteGraphPrototype.linear("p1", "成品", List.of("加工"))));
        var spec = FactoryReadinessService.plannedProducts(candidate).getFirst();
        assertThat(spec.inputUnit()).isEqualTo("g"); assertThat(spec.multiOutput()).isNull();
        step.setByproducts(List.of());
        assertThat(FactoryReadinessService.plannedProducts(candidate).getFirst().multiOutput()).isFalse();
        candidate.setRouteGraphs(List.of(FactoryRouteGraphPrototype.graph("p1", "成品",
                List.of(FactoryRouteGraphPrototype.node("entry", "加工"), FactoryRouteGraphPrototype.node("a", "包装A"), FactoryRouteGraphPrototype.node("b", "包装B")),
                List.of(FactoryRouteGraphPrototype.edge("entry", "a", FactoryRouteGraphPrototype.EdgeType.BRANCH), FactoryRouteGraphPrototype.edge("entry", "b", FactoryRouteGraphPrototype.EdgeType.BRANCH)))));
        assertThat(FactoryReadinessService.plannedProducts(candidate).getFirst().multiOutput()).isTrue();
    }
}
