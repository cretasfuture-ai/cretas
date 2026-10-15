package com.cretas.aims.blueprint.platform;

import com.cretas.aims.entity.PlatformAdmin;
import com.cretas.aims.entity.enums.PlatformRole;
import com.cretas.aims.entity.enums.Status;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.PlatformAdminRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.utils.CookieAuthHelper;
import com.cretas.aims.utils.JwtUtil;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformAssemblyAccessContractTest {
    private final JwtUtil jwt = new JwtUtil();
    private final PlatformAdminRepository admins = mock(PlatformAdminRepository.class);
    private PlatformAssemblyAccessConfiguration configuration;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(jwt, "secret", UUID.randomUUID().toString() + UUID.randomUUID());
        ReflectionTestUtils.setField(jwt, "expiration", 60_000L);
        configuration = new PlatformAssemblyAccessConfiguration(jwt, admins, false);
    }

    private MockHttpServletRequest bearer(String id, String role) {
        var request = new MockHttpServletRequest("POST", "/api/platform/factory-assembly/factories/NEW/ai-initialize/preview");
        String token = id.startsWith("platform_")
                ? jwt.generatePlatformToken(Long.parseLong(id.substring(9)), "platform-test", role)
                : jwt.generateToken(id, role);
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private void liveAdmin(PlatformRole role) {
        when(admins.findById(62L)).thenReturn(Optional.of(PlatformAdmin.builder()
                .id(62L).platformRole(role).status(Status.active).build()));
    }

    @Test
    void signedPlatformIdentityUsesOwnNamespaceAndLiveRole() {
        liveAdmin(PlatformRole.super_admin);
        var request = bearer("platform_62", "super_admin");
        configuration.authorize(request);
        var actor = AuthenticatedActor.current(request).orElseThrow();
        assertEquals(AuthenticatedActorType.PLATFORM_ADMIN, actor.type());
        assertEquals(62L, actor.id());
        assertNull(actor.factoryId());
        verify(admins).findById(62L);
    }

    @Test
    void collidingFactoryNumberAndClaimedSuperAdminCannotBecomePlatform() {
        var request = bearer("62", "super_admin");
        assertEquals(403, assertThrows(BusinessException.class, () -> configuration.authorize(request)).getCode());
        assertTrue(AuthenticatedActor.current(request).isEmpty());
        verifyNoInteractions(admins);
    }

    @Test
    void factorySuperAdminCannotEnterEvenWithSamePlatformNumber() {
        var request = bearer("62", "factory_super_admin");
        assertThrows(BusinessException.class, () -> configuration.authorize(request));
        verifyNoInteractions(admins);
    }

    @Test
    void forgedRequestActorDoesNotReplaceSignedPrincipal() {
        var request = bearer("62", "super_admin");
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 62L, null, "super_admin"));
        assertThrows(BusinessException.class, () -> configuration.authorize(request));
        verifyNoInteractions(admins);
    }

    @Test
    void revokedOrDeletedPlatformAccountCannotWrite() {
        when(admins.findById(62L)).thenReturn(Optional.empty());
        assertThrows(BusinessException.class, () -> configuration.authorize(bearer("platform_62", "super_admin")));
        when(admins.findById(62L)).thenReturn(Optional.of(PlatformAdmin.builder()
                .id(62L).platformRole(PlatformRole.super_admin).status(Status.inactive).build()));
        assertThrows(BusinessException.class, () -> configuration.authorize(bearer("platform_62", "super_admin")));
    }

    @Test
    void signedOldSuperAdminRoleCannotOverrideLiveDemotion() {
        liveAdmin(PlatformRole.system_admin);
        assertThrows(BusinessException.class, () -> configuration.authorize(bearer("platform_62", "super_admin")));
    }

    @Test
    void cookieLoginUsesSameValidatedPlatformBoundary() {
        liveAdmin(PlatformRole.super_admin);
        var request = new MockHttpServletRequest();
        request.setCookies(new Cookie(CookieAuthHelper.ACCESS_TOKEN_COOKIE, jwt.generatePlatformToken(62L, "platform-test", "super_admin")));
        configuration.authorize(request);
        assertEquals(AuthenticatedActorType.PLATFORM_ADMIN, AuthenticatedActor.current(request).orElseThrow().type());
    }

    @Test
    void unsignedInputCannotReachDatabase() {
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer not-a-signed-token");
        assertEquals(401, assertThrows(BusinessException.class, () -> configuration.authorize(request)).getCode());
        verifyNoInteractions(admins);
    }

    @Test
    void legacyRoleAndFactorySentinelCannotSubstituteForExplicitNamespace() {
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + jwt.generateToken(62L, "PLATFORM", "collision", "super_admin"));
        assertThrows(BusinessException.class, () -> configuration.authorize(request));
        verifyNoInteractions(admins);
    }

    @Test
    void platformClaimDoesNotChangeExistingNumericIdOrFactoryLoginContract() {
        String platform = jwt.generatePlatformToken(62L, "platform-test", "super_admin");
        assertEquals(62L, jwt.getUserIdFromToken(platform));
        assertEquals("PLATFORM", jwt.getFactoryIdFromToken(platform));
        assertTrue(jwt.isExplicitPlatformToken(platform));
        String factory = jwt.generateToken(62L, "EXISTING", "member", "factory_super_admin");
        assertEquals(62L, jwt.getUserIdFromToken(factory));
        assertEquals("EXISTING", jwt.getFactoryIdFromToken(factory));
        assertFalse(jwt.isExplicitPlatformToken(factory));
    }

    @Test
    void disabledCapabilityIsExplicitAndDoesNotWrite() {
        assertFalse(new PlatformAssemblyAccessConfiguration.CapabilitiesController(false).capabilities().getData().enabled());
        assertTrue(new PlatformAssemblyAccessConfiguration.CapabilitiesController(true).capabilities().getData().enabled());
        verifyNoInteractions(admins);
    }
}
