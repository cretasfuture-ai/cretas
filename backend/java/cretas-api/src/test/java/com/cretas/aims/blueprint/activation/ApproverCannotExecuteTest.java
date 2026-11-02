package com.cretas.aims.blueprint.activation;

import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.*;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintService;
import com.cretas.aims.security.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 职责分离在服务端：审批人不能执行自己审批的申请（页面只是提前把按钮灰掉）。 */
class ApproverCannotExecuteTest {

    @Test
    void theApproverIsRefusedBeforeAnythingIsClaimedOrWritten() {
        String digest = "a".repeat(64);
        var bp = BlueprintResponse.builder().id("bp").factoryId("TEST").version(2).rowVersion(1L).lifecycleState("VALIDATED").build();
        var canonical = mock(CanonicalFactoryBlueprintService.class);
        when(canonical.current("TEST")).thenReturn(bp);
        var store = mock(FactoryActivationExecutionStore.class);
        int[] calls = {0};
        class Command implements FactoryModuleActivationCommand {
            public String capabilityId() { return "workflow"; }
            public CurrentDraft currentDraft(String f, String d) { return new CurrentDraft("workflow", "draft", 1, digest, "DRAFT"); }
            public ModuleCommandReceipt activate(ModuleActivationCommandRequest r) { calls[0]++; return new ModuleCommandReceipt("workflow", "r", "t", 1, digest, 1); }
            public RuntimeReadback readBack(String f, ModuleCommandReceipt r) { return new RuntimeReadback("workflow", "t", 1, digest, "ACTIVE"); }
            public String recovery(ModuleCommandReceipt r) { return "redo"; }
        }
        var center = new FactoryBlueprintActivationCenter(List.of(new Command()), store, canonical,
                Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC), Duration.ofHours(1));
        var requester = new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, 7);
        var approver = new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, 8);
        var permissions = Set.of("factory-blueprint:activation:workflow", "factory-blueprint:activation:execute");
        var request = new ActivationPlanRequest("TEST", "bp", 2, center.serverBlueprintDigest(bp), requester, permissions,
                List.of(new DraftCandidate("workflow", "draft", 1, digest, "DRAFT",
                        Map.of("routes", "ACTIVE", "products", "ACTIVE", "materials", "ACTIVE", "processes", "ACTIVE"))));
        var plan = center.preview(request);
        assertThat(plan.status()).isEqualTo(PlanStatus.READY_FOR_APPROVAL);
        var approved = center.approve(plan, approver, Set.of("factory-blueprint:activation:approve"), "independent review passed");

        assertThatThrownBy(() -> center.execute(approved, approved.approvalDigest(), "k1", approver, permissions))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("审批人不能执行");
        verify(store, never()).claim(anyString(), anyString(), anyString(), any(ActorRef.class), any(ActorRef.class), any(ActorRef.class));
        assertThat(calls[0]).isZero();
    }
}
