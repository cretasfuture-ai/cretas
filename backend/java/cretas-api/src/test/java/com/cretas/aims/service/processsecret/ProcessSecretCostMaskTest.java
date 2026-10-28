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
        mask = new ProcessSecretCostMask(new ProcessSecretAccessService(policies, grants, recorder));
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
}
