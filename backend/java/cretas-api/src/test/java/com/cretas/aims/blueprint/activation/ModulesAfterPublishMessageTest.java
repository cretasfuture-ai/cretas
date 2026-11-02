package com.cretas.aims.blueprint.activation;

import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.*;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintService;
import com.cretas.aims.security.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 蓝图已发布后再预览「模块配置应用」：说中文、说清要重做什么，而不是只报身份不一致。 */
class ModulesAfterPublishMessageTest {

    private ActivationPlan previewModules(String state, int requestedVersion, int currentVersion) {
        String digest = "a".repeat(64);
        var bp = BlueprintResponse.builder().id("bp").factoryId("TEST").version(currentVersion).rowVersion(1L)
                .lifecycleState(state).build();
        var canonical = mock(CanonicalFactoryBlueprintService.class);
        when(canonical.current("TEST")).thenReturn(bp);
        var store = mock(FactoryActivationExecutionStore.class);
        class Command implements FactoryModuleActivationCommand {
            public String capabilityId() { return "modules"; }
            public CurrentDraft currentDraft(String factory, String draft) { return new CurrentDraft("modules", "draft", 1, digest, "DRAFT"); }
            public ModuleCommandReceipt activate(ModuleActivationCommandRequest r) { return new ModuleCommandReceipt("modules", "r", "t", 1, digest, 1); }
            public RuntimeReadback readBack(String f, ModuleCommandReceipt r) { return new RuntimeReadback("modules", "t", 1, digest, "ACTIVE"); }
            public String recovery(ModuleCommandReceipt r) { return "redo"; }
        }
        var center = new FactoryBlueprintActivationCenter(List.of(new Command()), store, canonical,
                Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC), Duration.ofHours(1));
        var requester = new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, 7);
        var request = new ActivationPlanRequest("TEST", "bp", requestedVersion, center.serverBlueprintDigest(bp), requester,
                Set.of("factory-blueprint:activation:modules"),
                List.of(new DraftCandidate("modules", "draft", 1, digest, "DRAFT", Map.of())));
        return center.preview(request);
    }

    @Test
    void publishedBlueprintExplainsModulesMustBeAppliedBeforePublishing() {
        var plan = previewModules("PUBLISHED", 5, 5);
        var issue = plan.issues().stream().filter(i -> "BLUEPRINT_LINEAGE_MISMATCH".equals(i.code())).findFirst().orElseThrow();
        assertThat(issue.message()).contains("已经发布").contains("发布之前应用");
        assertThat(issue.recovery()).contains("新版本").contains("先完成「配置应用」，再发布蓝图");
    }

    @Test
    void versionMovedOnKeepsTheGenericRefreshAdvice() {
        var plan = previewModules("VALIDATED", 4, 5);
        var issue = plan.issues().stream().filter(i -> "BLUEPRINT_LINEAGE_MISMATCH".equals(i.code())).findFirst().orElseThrow();
        assertThat(issue.message()).doesNotContain("已经发布").contains("新版本");
        assertThat(issue.recovery()).contains("重新写入草稿");
    }

    @Test
    void validatedBlueprintHasNoLineageIssue() {
        var plan = previewModules("VALIDATED", 5, 5);
        assertThat(plan.issues()).noneMatch(i -> "BLUEPRINT_LINEAGE_MISMATCH".equals(i.code()));
    }
}
