package com.cretas.aims.blueprint.activation;

import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.*;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintService;
import com.cretas.aims.security.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

class AppliedWorkflowExecutionContractTest {
    @ParameterizedTest
    @ValueSource(strings={"VALIDATED","PUBLISHED","APPLIED"})
    void approvedWorkflowExecutesAndStillRejectsActualBlueprintDriftBeforeClaim(String state) {
        String digest="a".repeat(64);
        var bp=BlueprintResponse.builder().id("bp").factoryId("TEST").version(2).rowVersion(1L).lifecycleState(state).build();
        var canonical=mock(CanonicalFactoryBlueprintService.class);when(canonical.current("TEST")).thenReturn(bp);
        var store=mock(FactoryActivationExecutionStore.class);
        when(store.claim(anyString(),anyString(),anyString(),any(ActorRef.class),any(ActorRef.class),any(ActorRef.class))).thenReturn(true);
        when(store.save(any())).thenAnswer(i->i.getArgument(0));
        class Command implements FactoryModuleActivationCommand {
            int calls;
            public String capabilityId(){return "workflow";}
            public CurrentDraft currentDraft(String factory,String draft){return new CurrentDraft("workflow","draft",1,digest,"DRAFT");}
            public ModuleCommandReceipt activate(ModuleActivationCommandRequest r){calls++;return new ModuleCommandReceipt("workflow","receipt","runtime",1,digest,1);}
            public RuntimeReadback readBack(String f,ModuleCommandReceipt r){return new RuntimeReadback("workflow","runtime",1,digest,"ACTIVE");}
            public String recovery(ModuleCommandReceipt r){return "create new version";}
        }
        var command=new Command();
        var center=new FactoryBlueprintActivationCenter(List.of(command),store,canonical,Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"),ZoneOffset.UTC),Duration.ofHours(1));
        var requester=new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN,7);
        var approver=new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN,8);
        var permissions=Set.of("factory-blueprint:activation:workflow","factory-blueprint:activation:execute");
        var request=new ActivationPlanRequest("TEST","bp",2,center.serverBlueprintDigest(bp),requester,permissions,
                List.of(new DraftCandidate("workflow","draft",1,digest,"DRAFT",Map.of("routes","ACTIVE","products","ACTIVE","materials","ACTIVE","processes","ACTIVE"))));
        var plan=center.preview(request);
        assertThat(plan.status()).isEqualTo(PlanStatus.READY_FOR_APPROVAL);
        var approved=center.approve(plan,approver,Set.of("factory-blueprint:activation:approve"),"independent review passed");
        var result=assertDoesNotThrow(()->center.execute(approved,approved.approvalDigest(),"execute",requester,permissions));
        assertThat(result.status()).isEqualTo("ACTIVATED");assertThat(command.calls).isEqualTo(1);
        clearInvocations(store);
        bp.setVersion(3);
        assertThatThrownBy(()->center.execute(approved,approved.approvalDigest(),"drift",requester,permissions))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Blueprint authority drifted");
        verify(store,never()).claim(anyString(),anyString(),anyString(),any(ActorRef.class),any(ActorRef.class),any(ActorRef.class));
        assertThat(command.calls).isEqualTo(1);
    }
}
