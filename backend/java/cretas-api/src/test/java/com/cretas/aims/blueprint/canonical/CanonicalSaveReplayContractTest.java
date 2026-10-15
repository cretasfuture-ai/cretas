package com.cretas.aims.blueprint.canonical;

import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.*;
import com.cretas.aims.blueprint.interview.FactoryBuildApplyService;
import com.cretas.aims.blueprint.interview.FactoryBuildPlanService;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.config.FactoryTypeBlueprint;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.config.*;
import com.cretas.aims.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CanonicalSaveReplayContractTest {
    private final FactoryTypeBlueprintRepository rows = mock(FactoryTypeBlueprintRepository.class);
    private final FactoryRepository factories = mock(FactoryRepository.class);
    private final FactoryBuildPlanService planner = mock(FactoryBuildPlanService.class);
    private final FactoryBlueprintCapabilityCatalog catalog = mock(FactoryBlueprintCapabilityCatalog.class);
    private final BlueprintApplicationRepository applications = mock(BlueprintApplicationRepository.class);
    private final Map<String, com.cretas.aims.entity.config.BlueprintApplication> receipts = new HashMap<>();
    private CanonicalFactoryBlueprintService service;
    private FactoryTypeBlueprint stored;
    private final ActorRef actor = new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, 62L);

    @BeforeEach void setup() {
        service = new CanonicalFactoryBlueprintService(rows, mock(BlueprintVersionHistoryRepository.class),
                mock(FactoryBlueprintBindingRepository.class), applications,
                planner, mock(FactoryBuildApplyService.class), catalog, new ObjectMapper().findAndRegisterModules());
        service.actorAuthority = mock(CanonicalBlueprintActorAuthority.class);
        service.factoryRepository = factories;
        when(factories.findByIdForUpdate("F1")).thenReturn(Optional.of(new Factory()));
        when(applications.findByFactoryIdAndOperationTypeAndIdempotencyKeyAndDeletedAtIsNull(eq("F1"), eq("SAVE_DRAFT"), anyString()))
                .thenAnswer(call -> Optional.ofNullable(receipts.get(call.getArgument(2))));
        when(applications.saveAndFlush(any())).thenAnswer(call -> {
            com.cretas.aims.entity.config.BlueprintApplication receipt = call.getArgument(0);
            receipt.setId(UUID.randomUUID().toString()); receipts.put(receipt.getIdempotencyKey(), receipt); return receipt;
        });
        when(applications.findByFactoryIdAndBlueprintIdAndBlueprintVersionAndDeletedAtIsNullOrderByAppliedAtDesc(anyString(), anyString(), anyInt()))
                .thenAnswer(call -> new ArrayList<>(receipts.values()));
        when(rows.saveAndFlush(any())).thenAnswer(call -> {
            stored = call.getArgument(0); stored.setId("canonical-1"); stored.setRowVersion(0L); return stored;
        });
        when(catalog.current()).thenReturn(new FactoryBlueprintCapabilityCatalog.CatalogSnapshot("v1", "digest", List.of(), LocalDateTime.now()));
    }

    private SaveDraftRequest request() {
        SaveDraftRequest request = new SaveDraftRequest();
        request.setFactoryName("测试工厂"); request.setIdempotencyKey("save-62");
        request.setPayload(new ApplyAIInitRequest());
        return request;
    }

    @Test void configurationPreflightDoesNotPublishOrEraseBlockedWorkflow() {
        var blockedWorkflow = CapabilityView.builder().id("workflow").label("Workflow")
                .status("BLOCKED").missingDependencies(List.of("bom")).build();
        var snapshot = catalog.current();
        when(catalog.load("v1")).thenReturn(snapshot);
        when(catalog.derive(any(), anyList(), any())).thenReturn(List.of(blockedWorkflow));
        service.saveDraft("F1", request(), actor);
        when(rows.lockCurrentByFactoryId("F1")).thenAnswer(call -> Optional.of(stored));
        var operation = new OperationRequest(); operation.setIdempotencyKey("validate-config"); operation.setExpectedRowVersion(0L);
        var validated = service.validate("F1", stored.getId(), operation, actor);
        assertThat(validated.getValidation().isValid()).isTrue();
        assertThat(validated.getCapabilities().get(0).getStatus()).isEqualTo("BLOCKED");
        assertThat(validated.getCapabilities().get(0).getMissingDependencies()).containsExactly("bom");
        operation.setIdempotencyKey("simulate-config");
        var simulated = service.simulate("F1", stored.getId(), operation, actor);
        assertThat(simulated.getSimulation().isSafe()).isTrue();
        assertThat(simulated.getSimulation().getChecks().get("capabilityDependenciesReady")).isTrue();
        assertThat(simulated.getSimulation().getWorkflow()).containsEntry("state", "NOT_PUBLISHED")
                .containsEntry("simulationExecuted", false).containsEntry("businessWriteCount", 0);
        operation.setIdempotencyKey("publish-config");
        var published = service.publish("F1", stored.getId(), operation, actor);
        assertThat(published.getLatestReceipt().getResult()).containsEntry("workflowPublished", false);
        assertThat(published.getWorkflowState()).isEqualTo("NOT_PUBLISHED");
    }

    @Test void blockedNonWorkflowCapabilityStillRefusesConfigurationPreflight() {
        var blocked = CapabilityView.builder().id("routes").label("路线")
                .status("BLOCKED").missingDependencies(List.of("products")).build();
        var snapshot = catalog.current();
        when(catalog.load("v1")).thenReturn(snapshot);
        when(catalog.derive(any(), anyList(), any())).thenReturn(List.of(blocked));
        service.saveDraft("F1", request(), actor);
        when(rows.lockCurrentByFactoryId("F1")).thenAnswer(call -> Optional.of(stored));
        var operation = new OperationRequest(); operation.setIdempotencyKey("validate-blocked"); operation.setExpectedRowVersion(0L);
        var result = service.validate("F1", stored.getId(), operation, actor);
        assertThat(result.getValidation().isValid()).isFalse();
        assertThat(result.getValidation().getBlockingIssues()).extracting(ValidationIssue::getCapabilityId).containsExactly("routes");
        operation.setIdempotencyKey("simulate-blocked");
        assertThatThrownBy(() -> service.simulate("F1", stored.getId(), operation, actor))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class);
    }

    @Test void exactReplayReturnsOriginalWithoutPlanningOrWritingAgain() {
        service.saveDraft("F1", request(), actor);
        clearInvocations(rows, planner, factories);
        assertThat(service.saveDraft("F1", request(), actor).getId()).isEqualTo("canonical-1");
        verify(factories).findByIdForUpdate("F1");
        verify(rows, never()).saveAndFlush(any()); verifyNoInteractions(planner);
    }

    @Test void sameKeyCannotReplayChangedPayloadEvidenceNameVersionOrActor() {
        service.saveDraft("F1", request(), actor);
        var changedName = request(); changedName.setFactoryName("其他工厂");
        var changedPayload = request(); changedPayload.getPayload().setIndustryCode("OTHER");
        var changedEvidence = request(); var evidence = new ConversionEvidence(); evidence.setProvenance("MANUAL");
        changedEvidence.setConversionEvidence(List.of(evidence));
        var changedVersion = request(); changedVersion.setExpectedRowVersion(2L);
        clearInvocations(rows, planner);
        for (var changed : List.of(changedName, changedPayload, changedEvidence, changedVersion)) {
            assertThatThrownBy(() -> service.saveDraft("F1", changed, actor)).hasMessageContaining("幂等键");
        }
        assertThatThrownBy(() -> service.saveDraft("F1", request(), new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, 63L)))
                .hasMessageContaining("幂等键");
        verify(rows, never()).saveAndFlush(any()); verifyNoInteractions(planner);
    }

    @Test void firstSaveRequiresExistingFactoryLockBeforeAnyBlueprintWrite() {
        when(factories.findByIdForUpdate("F1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.saveDraft("F1", request(), actor)).hasMessageContaining("目标工厂不存在");
        verifyNoInteractions(rows, planner);
    }

    @Test void earlierKeyRemainsImmutableAfterAnotherDraftSave() {
        var original = service.saveDraft("F1", request(), actor);
        when(rows.lockCurrentByFactoryId("F1")).thenAnswer(call -> Optional.of(stored));
        var newer = request(); newer.setIdempotencyKey("save-B"); newer.setFactoryName("下一版"); newer.setExpectedRowVersion(0L);
        service.saveDraft("F1", newer, actor);
        clearInvocations(rows, planner, applications);
        assertThat(service.saveDraft("F1", request(), actor).getFactoryName()).isEqualTo(original.getFactoryName());
        var changedOld = request(); changedOld.setFactoryName("篡改旧键"); changedOld.setExpectedRowVersion(0L);
        assertThatThrownBy(() -> service.saveDraft("F1", changedOld, actor)).hasMessageContaining("幂等键");
        verify(rows, never()).saveAndFlush(any()); verify(applications, never()).saveAndFlush(any()); verifyNoInteractions(planner);
    }

    @Test void repeatedSaveResponsesNeverNestStoredReplayResponses() {
        service.saveDraft("F1", request(), actor);
        when(rows.lockCurrentByFactoryId("F1")).thenAnswer(call -> Optional.of(stored));
        for (int i = 0; i < 8; i++) {
            var next = request(); next.setIdempotencyKey("next-" + i); next.setExpectedRowVersion(0L);
            var response = service.saveDraft("F1", next, actor);
            assertThat(response.getLatestReceipt()).isNotNull();
            assertThat(response.getLatestReceipt().getResult()).doesNotContainKey("response");
        }
    }
}
