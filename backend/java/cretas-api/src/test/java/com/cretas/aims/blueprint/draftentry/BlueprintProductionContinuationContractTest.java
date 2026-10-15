package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.CapabilityView;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintService;
import com.cretas.aims.blueprint.promotion.MasterDataDraftPromotionService;
import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationCenter;
import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.*;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest;
import com.cretas.aims.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BlueprintProductionContinuationContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private BlueprintResponse blueprint(String state) {
        BlueprintResponse bp = BlueprintResponse.builder().build();
        bp.setId("bp"); bp.setFactoryId("TEST"); bp.setVersion(2);
        bp.setLifecycleState(state); bp.setSourcePayload(new ApplyAIInitRequest());
        return bp;
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUBLISHED", "APPLIED"})
    void proposalContinuesOnlyProductionCapabilities(String state) {
        for (String capability : List.of("workflow", "bom", "products")) {
            BlueprintResponse bp = blueprint(state);
            CapabilityView view = mock(CapabilityView.class);
            when(view.getId()).thenReturn(capability); when(view.isIncluded()).thenReturn(true);
            when(view.getStatus()).thenReturn("READY"); bp.setCapabilities(List.of(view));
            var canonical = mock(CanonicalFactoryBlueprintService.class);
            when(canonical.current("TEST")).thenReturn(bp);
            var proposer = mock(CapabilityDraftProposer.class);
            when(proposer.capabilityId()).thenReturn(capability);
            when(proposer.propose(any())).thenReturn(new CapabilityDraftProposer.Proposed(List.of()));
            var entry = mock(CapabilityDraftEntry.class); when(entry.capabilityId()).thenReturn(capability);
            var operators = mock(BlueprintDraftOperatorResolver.class);
            var controller = new BlueprintDraftProposalController(List.of(proposer), List.of(entry), operators, canonical, mapper);
            if (capability.equals("products")) {
                assertThrows(BusinessException.class, () -> controller.propose("TEST", "bp", capability, new MockHttpServletRequest()));
                verify(proposer, never()).propose(any());
            } else {
                assertDoesNotThrow(() -> controller.propose("TEST", "bp", capability, new MockHttpServletRequest()));
                verify(proposer).propose(any());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "VALIDATED", "PUBLISHED", "APPLIED"})
    void bomAndWorkflowEntryAndPromotionKeepCurrentLineageAfterConfiguration(String state) throws Exception {
        BlueprintResponse bp = blueprint(state);
        CanonicalFactoryBlueprintService canonical = mock(CanonicalFactoryBlueprintService.class);
        when(canonical.current("TEST")).thenReturn(bp);
        for (String capability : List.of("bom", "workflow")) {
            Object entry = capability.equals("bom") ? mock(BomDraftEntry.class, CALLS_REAL_METHODS)
                    : mock(WorkflowDraftEntry.class, CALLS_REAL_METHODS);
            ReflectionTestUtils.setField(entry, "blueprints", canonical);
            var body = mapper.createObjectNode().put("factoryId", "TEST").put("blueprintId", "bp")
                    .put("blueprintVersion", 2).put("sourceDigest", BlueprintSourceDigest.of(bp));
            Class<?> requestType = capability.equals("bom") ? BomDraftRequest.class : WorkflowDraftRequest.class;
            Object request = mapper.treeToValue(body, requestType);
            assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(entry, "requireCurrentBlueprint", request));
            body.put("blueprintVersion", 1);
            Object stale = mapper.treeToValue(body, requestType);
            assertThrows(BusinessException.class, () -> ReflectionTestUtils.invokeMethod(entry, "requireCurrentBlueprint", stale));
            body.put("blueprintVersion", 2).put("sourceDigest", "0".repeat(64));
            Object drifted = mapper.treeToValue(body, requestType);
            assertThrows(BusinessException.class, () -> ReflectionTestUtils.invokeMethod(entry, "requireCurrentBlueprint", drifted));

            MasterDataDraftPromotionService promotions = mock(MasterDataDraftPromotionService.class, CALLS_REAL_METHODS);
            ReflectionTestUtils.setField(promotions, "blueprints", canonical);
            assertNotNull(ReflectionTestUtils.invokeMethod(promotions, "currentLineage", "TEST", "bp", capability));
            assertThrows(BusinessException.class, () -> ReflectionTestUtils.invokeMethod(promotions, "currentLineage", "TEST", "other", capability));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUBLISHED", "APPLIED", "FAILED", "UNKNOWN"})
    void otherCapabilitiesCannotRestartConfiguration(String state) {
        BlueprintResponse bp = blueprint(state);
        CanonicalFactoryBlueprintService canonical = mock(CanonicalFactoryBlueprintService.class);
        when(canonical.current("TEST")).thenReturn(bp);
        MasterDataDraftPromotionService promotions = mock(MasterDataDraftPromotionService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(promotions, "blueprints", canonical);
        for (String capability : List.of("materials", "products", "processes", "accounts", "modules")) {
            assertFalse(BlueprintDraftLifecycle.allows(state, capability));
            assertThrows(BusinessException.class, () -> ReflectionTestUtils.invokeMethod(promotions, "currentLineage", "TEST", "bp", capability));
        }
        if (!List.of("PUBLISHED", "APPLIED").contains(state)) {
            assertFalse(BlueprintDraftLifecycle.allows(state, "bom"));
            assertFalse(BlueprintDraftLifecycle.allows(state, "workflow"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUBLISHED", "APPLIED"})
    void formalActivationOnlyContinuesWorkflowAndRejectsMixedOrStalePlans(String state) {
        BlueprintResponse bp = blueprint(state);
        CanonicalFactoryBlueprintService canonical = mock(CanonicalFactoryBlueprintService.class);
        when(canonical.current("TEST")).thenReturn(bp);
        FactoryBlueprintActivationCenter center = mock(FactoryBlueprintActivationCenter.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(center, "canonicalBlueprints", canonical);
        for (String capability : List.of("workflow", "modules")) {
            var candidate = new DraftCandidate(capability, "draft", 1, "digest", "DRAFT", Map.of());
            var request = new ActivationPlanRequest("TEST", "bp", 2, center.serverBlueprintDigest(bp), 1,
                    Set.of(), List.of(candidate));
            List<ActivationIssue> issues = new ArrayList<>();
            ReflectionTestUtils.invokeMethod(center, "verifyBlueprintAuthority", request, issues);
            assertEquals(capability.equals("workflow"), issues.isEmpty());
        }
        var workflow = new DraftCandidate("workflow", "draft", 1, "digest", "DRAFT", Map.of());
        for (int version : List.of(1, 2)) {
            var stale = new ActivationPlanRequest("TEST", "bp", version, "wrong-digest", 1, Set.of(), List.of(workflow));
            List<ActivationIssue> issues = new ArrayList<>();
            ReflectionTestUtils.invokeMethod(center, "verifyBlueprintAuthority", stale, issues);
            assertFalse(issues.isEmpty());
        }
    }
}
