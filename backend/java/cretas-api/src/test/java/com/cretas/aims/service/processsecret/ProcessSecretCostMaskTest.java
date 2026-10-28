package com.cretas.aims.service.processsecret;

import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProcessSecretCostMaskTest {

    static final String F = "F-A";
    static final String SECRET = "P17";
    static final long OWNER = 10L;

    ProcessSecretAuditRecorder recorder;
    ProcessSecretCostMask mask;
    com.cretas.aims.repository.ProductionPlanRepository plans;
    com.cretas.aims.repository.ProductionBatchRepository batches;

    @BeforeEach
    void setUp() {
        ProcessSecretPolicyRepository policies = mock(ProcessSecretPolicyRepository.class);
        ProcessSecretGrantRepository grants = mock(ProcessSecretGrantRepository.class);
        recorder = mock(ProcessSecretAuditRecorder.class);
        when(grants.findActive(anyString(), anyString(), any(), any(), any())).thenReturn(List.of());
        ProcessSecretPolicy policy = ProcessSecretPolicy.builder().id(1L).factoryId(F).productTypeId(SECRET)
                .storageMode(ProcessSecretStorageMode.CLOUD_RESTRICTED).ownerUserId(OWNER).createdBy(OWNER).build();
        when(policies.findByFactoryIdAndProductTypeId(F, SECRET)).thenReturn(Optional.of(policy));
        when(policies.findByFactoryIdOrderByProductTypeIdAsc(F)).thenReturn(List.of(policy));
        plans = mock(com.cretas.aims.repository.ProductionPlanRepository.class);
        batches = mock(com.cretas.aims.repository.ProductionBatchRepository.class);
        mask = new ProcessSecretCostMask(new ProcessSecretAccessService(policies, grants, recorder), plans, batches,
                mock(com.cretas.aims.repository.bom.BomRecipeRepository.class));
    }

    static AuthenticatedActor user(long id) {
        return new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, id, F, "operator");
    }

    @Test
    void aProtectedProductIsHiddenFromAnAccountWithoutAGrantAndTheDenyIsAudited() {
        assertThat(mask.hiddenFor(user(5), F, List.of(SECRET, "PLAIN"), "EXPORT x")).containsExactly(SECRET);
        verify(recorder).record(eq(F), eq(SECRET), eq(user(5)), eq(ProcessSecretAction.VIEW_FULL), eq("DENY"),
                contains("排除"), eq("EXPORT x"));
    }

    @Test
    void theOwnerSeesItsCosts() {
        assertThat(mask.hiddenFor(user(OWNER), F, List.of(SECRET), "EXPORT x")).isEmpty();
    }

    @Test
    void anUnknownCallerIsTreatedAsUnauthorized() {
        assertThat(mask.hiddenFor(null, F, List.of(SECRET), "EXPORT x")).containsExactly(SECRET);
    }

    @Test
    void unprotectedProductsAreNeverHiddenAndWriteNoAudit() {
        assertThat(mask.hiddenFor(user(5), F, List.of("PLAIN", "OTHER"), "EXPORT x")).isEmpty();
        verify(recorder, never()).record(any(), any(), any(), any(), any(), any(), any());
    }
    @Test
    void aConsumptionCountsTheProductsOfItsPlanAndOfItsBatch() {
        com.cretas.aims.entity.ProductionPlan plan = new com.cretas.aims.entity.ProductionPlan();
        plan.setId("PLAN-1");
        plan.setFactoryId(F);
        plan.setProductTypeId(SECRET);
        plan.setTargetFinishedGoodIds(List.of("BYPRODUCT"));
        when(plans.findByIdAndFactoryId("PLAN-1", F)).thenReturn(Optional.of(plan));
        com.cretas.aims.entity.ProductionBatch batch = new com.cretas.aims.entity.ProductionBatch();
        batch.setProductTypeId("WIP-SKU");
        batch.setProductionPlanId("PLAN-1");
        when(batches.findByIdAndFactoryId(7L, F)).thenReturn(Optional.of(batch));
        java.util.Map<String, java.util.Set<String>> cache = new java.util.HashMap<>();
        assertThat(mask.productsOf(F, "PLAN-1", null, cache)).containsExactlyInAnyOrder(SECRET, "BYPRODUCT");
        assertThat(mask.productsOf(F, null, 7L, cache)).containsExactlyInAnyOrder("WIP-SKU", SECRET, "BYPRODUCT");
        assertThat(mask.productsOf(F, null, null, cache)).isEmpty();
    }

    @Test
    void anyHiddenTellsWhetherTheFactoryHasAProtectedProductTheCallerCannotSee() {
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(user(5))) {
            assertThat(mask.anyHidden(F, "EXPORT x")).isTrue();
        }
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(user(OWNER))) {
            assertThat(mask.anyHidden(F, "EXPORT x")).isFalse();
        }
    }

    @Test
    void onlyPlatformOtherFactoryAndUnknownCallersLoseQuantities() {
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(user(5))) {
            assertThat(mask.quantitiesHidden(F)).isFalse();
        }
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(
                new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 1L, "PLATFORM", "super_admin"))) {
            assertThat(mask.quantitiesHidden(F)).isTrue();
        }
        assertThat(mask.quantitiesHidden(F)).as("识别不出调用者").isTrue();
    }
}
