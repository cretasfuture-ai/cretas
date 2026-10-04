package com.cretas.aims.security;

import com.cretas.aims.entity.PlatformAdmin;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.PlatformAdminRepository;
import com.cretas.aims.repository.UserRepository;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Force-insert audit records, approvals and plan-confirm notifications carry the operator's name and
 * role. platform_admins.id and users.id are independent sequences, so a platform admin's id equals
 * some unrelated factory user's id; that user must never be written into the record.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CurrentOperatorResolver — platform admin is never named as the factory user with the same id")
class CurrentOperatorResolverTest {

    private static final long SHARED_ID = 1L;

    @Mock private UserRepository userRepository;
    @Mock private PlatformAdminRepository platformAdminRepository;

    private CurrentOperatorResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new CurrentOperatorResolver(userRepository, platformAdminRepository);
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

    /** F999's phase2a_test_user, who owns {@link #SHARED_ID} in {@code users}. */
    private void collidingFactoryUser() {
        User user = new User();
        user.setId(SHARED_ID);
        user.setFullName("phase2a_test_user");
        user.setRoleCode("operator");
        lenient().when(userRepository.findById(SHARED_ID)).thenReturn(Optional.of(user));
    }

    @Test
    @DisplayName("bypass platform roles are named from platform_admins, not from the colliding users row")
    void bypassPlatformRole_namedFromPlatformAdmins() {
        collidingFactoryUser();
        PlatformAdmin admin = new PlatformAdmin();
        admin.setId(SHARED_ID);
        admin.setUsername("platform_root");
        admin.setRealName("平台运维");
        when(platformAdminRepository.findById(SHARED_ID)).thenReturn(Optional.of(admin));

        for (String role : AuthenticatedActor.FACTORY_BYPASS_PLATFORM_ROLES) {
            as(AuthenticatedActorType.PLATFORM_ADMIN, "PLATFORM", role);
            CurrentOperatorResolver.Operator operator = resolver.resolve(SHARED_ID).orElseThrow();
            assertEquals("平台运维", operator.displayName(), role);
            assertEquals(role, operator.role());
            assertEquals(SHARED_ID, operator.id());
        }
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("other platform roles are refused, not named as the colliding users row")
    void nonBypassPlatformRole_refused() {
        collidingFactoryUser();
        for (String role : List.of("auditor", "operation_admin", "system_admin")) {
            as(AuthenticatedActorType.PLATFORM_ADMIN, "PLATFORM", role);
            BusinessException ex = assertThrows(BusinessException.class, () -> resolver.resolve(SHARED_ID), role);
            assertEquals(403, ex.getCode(), role);
        }
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("negative control: the factory user with that id keeps their own name and role")
    void negativeControl_factoryUserWithSameIdKeepsOwnResult() {
        collidingFactoryUser();
        as(AuthenticatedActorType.FACTORY_USER, "F999", "operator");

        CurrentOperatorResolver.Operator operator = resolver.resolve(SHARED_ID).orElseThrow();
        assertEquals("phase2a_test_user", operator.displayName());
        assertEquals("operator", operator.role());
        verify(platformAdminRepository, never()).findById(any());
    }

    @Test
    @DisplayName("outside an HTTP request (jobs) the users lookup is unchanged")
    void noRequest_usesUsersRow() {
        collidingFactoryUser();
        assertEquals("phase2a_test_user", resolver.resolve(SHARED_ID).orElseThrow().displayName());
    }
}
