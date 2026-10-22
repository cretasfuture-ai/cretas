package com.cretas.aims.service.processsecret;

import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.entity.processsecret.ProcessSecretGrant;
import com.cretas.aims.entity.processsecret.ProcessSecretGranteeType;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 判定矩阵: 方案 §2.1 / §3 / 验收 A02 A03 A14 A15 的服务层部分。 */
class ProcessSecretAccessAuthorizationTest {

    static final String F = "F-A";
    static final String P = "P17";
    static final long OWNER = 10L;

    ProcessSecretPolicyRepository policies;
    ProcessSecretGrantRepository grants;
    ProcessSecretAuditRecorder recorder;
    ProcessSecretAccessService service;

    @BeforeEach
    void setUp() {
        policies = mock(ProcessSecretPolicyRepository.class);
        grants = mock(ProcessSecretGrantRepository.class);
        recorder = mock(ProcessSecretAuditRecorder.class);
        service = new ProcessSecretAccessService(policies, grants, recorder);
        when(grants.findActive(anyString(), anyString(), any(), any(), any())).thenReturn(List.of());
    }

    static AuthenticatedActor factoryUser(long id, String factory, String role) {
        return new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, id, factory, role);
    }

    static AuthenticatedActor platform(long id, String role) {
        return new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, id, null, role);
    }

    void policy(ProcessSecretStorageMode mode) {
        when(policies.findByFactoryIdAndProductTypeId(F, P)).thenReturn(Optional.of(ProcessSecretPolicy.builder()
                .id(1L).factoryId(F).productTypeId(P).storageMode(mode).ownerUserId(OWNER).createdBy(OWNER)
                .build()));
    }

    void grant(ProcessSecretGranteeType type, long grantee, boolean view, boolean export) {
        when(grants.findActive(eq(F), eq(P), eq(type), eq(grantee), any(LocalDateTime.class)))
                .thenReturn(List.of(ProcessSecretGrant.builder().factoryId(F).productTypeId(P)
                        .granteeType(type).granteeId(grantee).canViewFull(view).canExport(export).canEdit(false)
                        .purpose("test").expiresAt(LocalDateTime.now().plusDays(1)).grantedBy(OWNER).build()));
    }

    @Test
    void productWithoutPolicyIsNotProtectedAndRequireDoesNotAudit() {
        when(policies.findByFactoryIdAndProductTypeId(F, P)).thenReturn(Optional.empty());
        var d = service.decide(factoryUser(5, F, "operator"), F, P, ProcessSecretAction.VIEW_FULL);
        assertThat(d.outcome()).isEqualTo(ProcessSecretAccessService.Outcome.NOT_PROTECTED);
        service.require(factoryUser(5, F, "operator"), F, P, ProcessSecretAction.VIEW_FULL, "r");
        verify(recorder, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void ownerSeesFullInCloudRestrictedMode() {
        policy(ProcessSecretStorageMode.CLOUD_RESTRICTED);
        assertThat(service.decide(factoryUser(OWNER, F, "factory_super_admin"), F, P, ProcessSecretAction.VIEW_FULL)
                .outcome()).isEqualTo(ProcessSecretAccessService.Outcome.ALLOW);
    }

    @Test
    void otherSuperAdminAndPermissionAdminAreDeniedDespiteTitle() {
        policy(ProcessSecretStorageMode.CLOUD_RESTRICTED);
        for (String role : List.of("factory_super_admin", "permission_admin", "production_manager", "operator")) {
            assertThat(service.decide(factoryUser(77, F, role), F, P, ProcessSecretAction.VIEW_FULL).permitted())
                    .as(role).isFalse();
        }
    }

    @Test
    void platformBypassRolesAreDeniedWithoutNamedSupportGrant() {
        policy(ProcessSecretStorageMode.CLOUD_RESTRICTED);
        for (String role : List.of("super_admin", "platform_admin", "developer")) {
            assertThat(service.decide(platform(OWNER, role), F, P, ProcessSecretAction.VIEW_FULL).permitted())
                    .as("platform %s with owner's numeric id", role).isFalse();
            // 平台令牌即使带着本厂 factoryId、且 id 与负责人相同, 也不是负责人 (两张账号表的 id 会撞号)
            AuthenticatedActor inFactory = new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, OWNER, F, role);
            assertThat(service.decide(inFactory, F, P, ProcessSecretAction.VIEW_FULL).permitted())
                    .as("platform %s carrying factory %s", role, F).isFalse();
        }
        grant(ProcessSecretGranteeType.PLATFORM_ADMIN, 900, true, false);
        assertThat(service.decide(platform(900, "developer"), F, P, ProcessSecretAction.VIEW_FULL).permitted()).isTrue();
        assertThat(service.decide(platform(900, "developer"), F, P, ProcessSecretAction.EXPORT).permitted()).isFalse();
    }

    @Test
    void grantCoversOnlyItsActionsAndOnlyItsGrantee() {
        policy(ProcessSecretStorageMode.CLOUD_RESTRICTED);
        grant(ProcessSecretGranteeType.FACTORY_USER, 20, true, false);
        assertThat(service.decide(factoryUser(20, F, "operator"), F, P, ProcessSecretAction.VIEW_FULL).permitted()).isTrue();
        assertThat(service.decide(factoryUser(20, F, "operator"), F, P, ProcessSecretAction.EXPORT).permitted()).isFalse();
        assertThat(service.decide(factoryUser(20, F, "operator"), F, P, ProcessSecretAction.EDIT).permitted()).isFalse();
        assertThat(service.decide(factoryUser(21, F, "operator"), F, P, ProcessSecretAction.VIEW_FULL).permitted()).isFalse();
    }

    @Test
    void sameUserIdFromAnotherFactoryIsNeitherOwnerNorGrantee() {
        policy(ProcessSecretStorageMode.CLOUD_RESTRICTED);
        grant(ProcessSecretGranteeType.FACTORY_USER, 20, true, true);
        assertThat(service.decide(factoryUser(OWNER, "F-B", "factory_super_admin"), F, P, ProcessSecretAction.VIEW_FULL)
                .permitted()).isFalse();
        assertThat(service.decide(factoryUser(20, "F-B", "operator"), F, P, ProcessSecretAction.VIEW_FULL)
                .permitted()).isFalse();
    }

    @Test
    void customerHeldDeniesEveryoneIncludingOwner() {
        policy(ProcessSecretStorageMode.CUSTOMER_HELD);
        grant(ProcessSecretGranteeType.FACTORY_USER, 20, true, true);
        for (ProcessSecretAction action : List.of(ProcessSecretAction.VIEW_FULL, ProcessSecretAction.EXPORT,
                ProcessSecretAction.EDIT)) {
            assertThat(service.decide(factoryUser(OWNER, F, "factory_super_admin"), F, P, action).permitted())
                    .as(action.name()).isFalse();
            assertThat(service.decide(factoryUser(20, F, "operator"), F, P, action).permitted())
                    .as(action.name()).isFalse();
        }
    }

    @Test
    void unknownCallerIsDeniedOnProtectedProduct() {
        policy(ProcessSecretStorageMode.CLOUD_RESTRICTED);
        assertThat(service.decide(null, F, P, ProcessSecretAction.VIEW_FULL).permitted()).isFalse();
    }

    @Test
    void requireThrows403AndAuditsDeny() {
        policy(ProcessSecretStorageMode.CLOUD_RESTRICTED);
        assertThatThrownBy(() -> service.require(factoryUser(77, F, "operator"), F, P,
                ProcessSecretAction.VIEW_FULL, "GET x"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
        verify(recorder).record(eq(F), eq(P), any(), eq(ProcessSecretAction.VIEW_FULL), eq("DENY"), any(), eq("GET x"));
    }

    @Test
    void allowIsDeniedWhenAuditCannotBeWritten() {
        policy(ProcessSecretStorageMode.CLOUD_RESTRICTED);
        doThrow(new IllegalStateException("db down")).when(recorder)
                .record(any(), any(), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> service.require(factoryUser(OWNER, F, "factory_super_admin"), F, P,
                ProcessSecretAction.VIEW_FULL, "GET x"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
    }

    @Test
    void implicitMultiProductFilterDropsDeniedProtectedProductsButKeepsOthers() {
        policy(ProcessSecretStorageMode.CLOUD_RESTRICTED);
        when(policies.findByFactoryIdAndProductTypeId(F, "PLAIN")).thenReturn(Optional.empty());
        List<String> kept = service.filterPermitted(factoryUser(77, F, "operator"), F, List.of("PLAIN", P),
                ProcessSecretAction.EXPORT, false, "export");
        assertThat(kept).containsExactly("PLAIN");
        assertThatThrownBy(() -> service.filterPermitted(factoryUser(77, F, "operator"), F, List.of("PLAIN", P),
                ProcessSecretAction.EXPORT, true, "export"))
                .isInstanceOf(BusinessException.class);
        // 阳性对照: 负责人全厂导出两个都在
        assertThat(service.filterPermitted(factoryUser(OWNER, F, "factory_super_admin"), F, List.of("PLAIN", P),
                ProcessSecretAction.EXPORT, false, "export")).containsExactly("PLAIN", P);
    }
}
