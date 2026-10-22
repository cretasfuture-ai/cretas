package com.cretas.aims.service.processsecret;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.processsecret.ProcessSecretGrant;
import com.cretas.aims.entity.processsecret.ProcessSecretGranteeType;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretAccessEventRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 管理边界: 首次启用 / 负责人专属 / 防自授权 / 乐观并发 / B 档撤销授权 (验收 A14 A15 的服务层部分)。 */
class ProcessSecretPolicyPermissionTest {

    static final String F = "F-A";
    static final String P = "P17";
    static final long OWNER = 10L;
    static final long SUPER = 11L;
    static final long STAFF = 20L;

    ProcessSecretPolicyRepository policies;
    ProcessSecretGrantRepository grants;
    UserRepository users;
    ProcessSecretAccessService access;
    ProcessSecretPolicyService service;

    @BeforeEach
    void setUp() {
        policies = mock(ProcessSecretPolicyRepository.class);
        grants = mock(ProcessSecretGrantRepository.class);
        users = mock(UserRepository.class);
        access = mock(ProcessSecretAccessService.class);
        when(access.audit(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        service = new ProcessSecretPolicyService(policies, grants, mock(ProcessSecretAccessEventRepository.class),
                users, access);
        user(OWNER, "factory_super_admin", true);
        user(SUPER, "factory_super_admin", true);
        user(STAFF, "operator", true);
        when(policies.save(any(ProcessSecretPolicy.class))).thenAnswer(i -> i.getArgument(0));
        when(policies.saveAndFlush(any(ProcessSecretPolicy.class))).thenAnswer(i -> i.getArgument(0));
        when(grants.save(any(ProcessSecretGrant.class))).thenAnswer(i -> i.getArgument(0));
    }

    void user(long id, String role, boolean active) {
        User u = new User();
        u.setId(id);
        u.setFactoryId(F);
        u.setRoleCode(role);
        u.setIsActive(active);
        when(users.findByIdAndFactoryId(id, F)).thenReturn(Optional.of(u));
    }

    static AuthenticatedActor actor(long id, String role) {
        return new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, id, F, role);
    }

    ProcessSecretPolicy existing(ProcessSecretStorageMode mode) {
        ProcessSecretPolicy p = ProcessSecretPolicy.builder().id(1L).factoryId(F).productTypeId(P)
                .storageMode(mode).ownerUserId(OWNER).policyVersion(4L).createdBy(SUPER).build();
        when(policies.findForUpdate(F, P)).thenReturn(Optional.of(p));
        when(policies.findByFactoryIdAndProductTypeId(F, P)).thenReturn(Optional.of(p));
        return p;
    }

    static void assertCode(int code, Executable call) {
        BusinessException e = assertThrows(BusinessException.class, call);
        assertEquals(code, e.getCode(), e.getMessage());
    }

    @Test
    void firstEnableNeedsFactorySuperAdminWhoDesignatesOwner() {
        when(policies.findForUpdate(F, P)).thenReturn(Optional.empty());
        assertCode(403, () -> service.upsertPolicy(actor(STAFF, "operator"), F, P,
                ProcessSecretStorageMode.CLOUD_RESTRICTED, STAFF, null));
        assertCode(403, () -> service.upsertPolicy(
                new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, SUPER, null, "super_admin"), F, P,
                ProcessSecretStorageMode.CLOUD_RESTRICTED, OWNER, null));
        var view = service.upsertPolicy(actor(SUPER, "factory_super_admin"), F, P,
                ProcessSecretStorageMode.CLOUD_RESTRICTED, OWNER, null);
        assertThat(view.ownerUserId()).isEqualTo(OWNER);
        assertThat(view.policyVersion()).isEqualTo(1L);
        verify(access).invalidate(F);
    }

    @Test
    void afterEnableOnlyOwnerManagesEvenAnotherSuperAdminCannot() {
        existing(ProcessSecretStorageMode.CLOUD_RESTRICTED);
        assertCode(403, () -> service.upsertPolicy(actor(SUPER, "factory_super_admin"), F, P,
                ProcessSecretStorageMode.CLOUD_RESTRICTED, SUPER, 4L));
        assertCode(403, () -> service.disablePolicy(actor(SUPER, "factory_super_admin"), F, P, 4L));
        assertCode(403, () -> service.createGrant(actor(SUPER, "factory_super_admin"), F, P,
                new ProcessSecretPolicyService.GrantRequest(ProcessSecretGranteeType.FACTORY_USER, SUPER,
                        true, true, true, "自授", OffsetDateTime.now().plusDays(1))));
        verify(grants, never()).save(any());
    }

    @Test
    void ownerUpdateNeedsCurrentPolicyVersion() {
        ProcessSecretPolicy p = existing(ProcessSecretStorageMode.CLOUD_RESTRICTED);
        assertCode(409, () -> service.upsertPolicy(actor(OWNER, "factory_super_admin"), F, P,
                ProcessSecretStorageMode.CLOUD_RESTRICTED, OWNER, 3L));
        service.upsertPolicy(actor(OWNER, "factory_super_admin"), F, P,
                ProcessSecretStorageMode.CLOUD_RESTRICTED, STAFF, 4L);
        assertThat(p.getOwnerUserId()).isEqualTo(STAFF);
        assertThat(p.getPolicyVersion()).isEqualTo(5L);
    }

    @Test
    void switchingToCustomerHeldRevokesAllGrantsAndForbidsNewOnes() {
        existing(ProcessSecretStorageMode.CLOUD_RESTRICTED);
        service.upsertPolicy(actor(OWNER, "factory_super_admin"), F, P, ProcessSecretStorageMode.CUSTOMER_HELD,
                OWNER, 4L);
        verify(grants).revokeAllForProduct(eq(F), eq(P), any(LocalDateTime.class), eq(OWNER));
        assertCode(409, () -> service.createGrant(actor(OWNER, "factory_super_admin"), F, P,
                new ProcessSecretPolicyService.GrantRequest(ProcessSecretGranteeType.FACTORY_USER, STAFF,
                        true, false, false, "生产", OffsetDateTime.now().plusDays(1))));
    }

    @Test
    void grantRulesPurposeExpiryPlatformLimitsAndNoSelfGrant() {
        existing(ProcessSecretStorageMode.CLOUD_RESTRICTED);
        AuthenticatedActor owner = actor(OWNER, "factory_super_admin");
        OffsetDateTime tomorrow = OffsetDateTime.now().plusDays(1);
        assertCode(400, () -> service.createGrant(owner, F, P, new ProcessSecretPolicyService.GrantRequest(
                ProcessSecretGranteeType.FACTORY_USER, OWNER, true, false, false, "x", tomorrow)));
        assertCode(400, () -> service.createGrant(owner, F, P, new ProcessSecretPolicyService.GrantRequest(
                ProcessSecretGranteeType.FACTORY_USER, STAFF, true, false, false, " ", tomorrow)));
        assertCode(400, () -> service.createGrant(owner, F, P, new ProcessSecretPolicyService.GrantRequest(
                ProcessSecretGranteeType.FACTORY_USER, STAFF, false, false, false, "x", tomorrow)));
        assertCode(400, () -> service.createGrant(owner, F, P, new ProcessSecretPolicyService.GrantRequest(
                ProcessSecretGranteeType.PLATFORM_ADMIN, 900L, true, true, false, "排障", tomorrow)));
        assertCode(400, () -> service.createGrant(owner, F, P, new ProcessSecretPolicyService.GrantRequest(
                ProcessSecretGranteeType.PLATFORM_ADMIN, 900L, true, false, false, "排障",
                OffsetDateTime.now().plusDays(4))));
        user(30L, "operator", false);
        assertCode(400, () -> service.createGrant(owner, F, P, new ProcessSecretPolicyService.GrantRequest(
                ProcessSecretGranteeType.FACTORY_USER, 30L, true, false, false, "x", tomorrow)));

        ProcessSecretGrant ok = service.createGrant(owner, F, P, new ProcessSecretPolicyService.GrantRequest(
                ProcessSecretGranteeType.PLATFORM_ADMIN, 900L, true, false, false, "排障 #123",
                OffsetDateTime.now().plusHours(24)));
        assertThat(ok.getCanExport()).isFalse();
        assertThat(ok.getGrantedBy()).isEqualTo(OWNER);
    }

    @Test
    void expiryWithUtcOffsetIsConvertedNotTakenAsLocalTime() {
        existing(ProcessSecretStorageMode.CLOUD_RESTRICTED);
        // 14:00Z = 22:00 北京时间; 当本地时间用会提前 8 小时到期 (审查 L3)
        OffsetDateTime utc = OffsetDateTime.now(ZoneOffset.UTC).plusHours(2).withNano(0);
        ProcessSecretGrant g = service.createGrant(actor(OWNER, "factory_super_admin"), F, P,
                new ProcessSecretPolicyService.GrantRequest(ProcessSecretGranteeType.FACTORY_USER, STAFF,
                        true, false, false, "x", utc));
        assertThat(g.getExpiresAt()).isEqualTo(
                utc.atZoneSameInstant(ProcessSecretPolicyService.BUSINESS_ZONE).toLocalDateTime());
        assertCode(400, () -> service.createGrant(actor(OWNER, "factory_super_admin"), F, P,
                new ProcessSecretPolicyService.GrantRequest(ProcessSecretGranteeType.FACTORY_USER, STAFF,
                        true, false, false, "x", null)));
    }

    @Test
    void concurrentFirstEnableIs409NotA500() {
        when(policies.findForUpdate(F, P)).thenReturn(Optional.empty());
        when(policies.saveAndFlush(any(ProcessSecretPolicy.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq"));
        assertCode(409, () -> service.upsertPolicy(actor(SUPER, "factory_super_admin"), F, P,
                ProcessSecretStorageMode.CLOUD_RESTRICTED, OWNER, null));
        verify(access, never()).audit(any(), any(), any(), any(), eq("ALLOW"), any(), any());
    }

    @Test
    void managementActionFailsClosedWhenAuditUnavailable() {
        when(policies.findForUpdate(F, P)).thenReturn(Optional.empty());
        when(access.audit(any(), anyString(), any(), any(), anyString(), anyString(), any())).thenReturn(false);
        assertCode(503, () -> service.upsertPolicy(actor(SUPER, "factory_super_admin"), F, P,
                ProcessSecretStorageMode.CLOUD_RESTRICTED, OWNER, null));
    }

    @Test
    void ownerMustBeActiveUserOfSameFactory() {
        when(policies.findForUpdate(F, P)).thenReturn(Optional.empty());
        when(users.findByIdAndFactoryId(anyLong(), eq(F))).thenReturn(Optional.empty());
        user(SUPER, "factory_super_admin", true);
        assertCode(400, () -> service.upsertPolicy(actor(SUPER, "factory_super_admin"), F, P,
                ProcessSecretStorageMode.CLOUD_RESTRICTED, 999L, null));
    }
}
