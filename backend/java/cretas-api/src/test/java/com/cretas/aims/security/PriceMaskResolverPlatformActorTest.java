package com.cretas.aims.security;

import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Binary exports (PDF / Excel) mask prices through {@link PriceMaskResolver}. For a platform token
 * {@code getUserFromToken} returns the platform_admins id, which equals some unrelated factory user's
 * users.id; that user's permissions must never decide what a platform admin sees.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PriceMaskResolver — platform admin is never judged as the factory user with the same id")
class PriceMaskResolverPlatformActorTest {

    private static final long SHARED_ID = 1L;
    private static final String AUTH = "Bearer token";

    @Mock private MobileService mobileService;
    @Mock private UserRepository userRepository;
    @Mock private PermissionService permissionService;

    private PriceMaskResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new PriceMaskResolver(mobileService, userRepository, permissionService);
        // Both token kinds resolve to the same numeric id: that is the collision.
        lenient().when(mobileService.getUserFromToken(anyString()))
                .thenReturn(UserDTO.builder().id(SHARED_ID).build());
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void as(AuthenticatedActorType type, String factoryId, String role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", SHARED_ID);
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(type, SHARED_ID, factoryId, role));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    /** The factory user who happens to own {@link #SHARED_ID} in {@code users}. */
    private void collidingFactoryUser(boolean canSee) {
        User user = new User();
        user.setId(SHARED_ID);
        lenient().when(userRepository.findById(SHARED_ID)).thenReturn(Optional.of(user));
        lenient().when(permissionService.hasPermission(eq(user), anyString())).thenReturn(canSee);
    }

    @Test
    @DisplayName("bypass platform roles see prices and costs even when the colliding users row cannot")
    void bypassPlatformRole_unmasked() {
        collidingFactoryUser(false);
        for (String role : AuthenticatedActor.FACTORY_BYPASS_PLATFORM_ROLES) {
            as(AuthenticatedActorType.PLATFORM_ADMIN, "PLATFORM", role);
            assertFalse(resolver.shouldMaskPrice(AUTH), role);
            assertFalse(resolver.shouldMaskOperationalCost(AUTH), role);
        }
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("other platform roles are masked even when the colliding users row may see")
    void nonBypassPlatformRole_masked() {
        collidingFactoryUser(true);
        for (String role : List.of("auditor", "operation_admin", "system_admin")) {
            as(AuthenticatedActorType.PLATFORM_ADMIN, "PLATFORM", role);
            assertTrue(resolver.shouldMaskPrice(AUTH), role);
            assertTrue(resolver.shouldMaskOperationalCost(AUTH), role);
        }
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("negative control: the factory user with that id keeps their own result")
    void negativeControl_factoryUserWithSameIdKeepsOwnResult() {
        as(AuthenticatedActorType.FACTORY_USER, "F999", "operator");

        collidingFactoryUser(false);
        assertTrue(resolver.shouldMaskPrice(AUTH));
        assertTrue(resolver.shouldMaskOperationalCost(AUTH));

        collidingFactoryUser(true);
        assertFalse(resolver.shouldMaskPrice(AUTH));
        assertFalse(resolver.shouldMaskOperationalCost(AUTH));
    }
}
