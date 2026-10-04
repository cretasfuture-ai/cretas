package com.cretas.aims.utils;

import com.cretas.aims.entity.User;
import com.cretas.aims.exception.AuthorizationException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
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
 * platform_admins.id and users.id are independent sequences, so a platform admin's id equals some
 * unrelated factory user's id (on a fresh DB, platform admin 1 vs F999's phase2a_test_user 1).
 * Cross-factory access must be decided by the actor type, never by {@code users.findById(actor.id())}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FactoryAccessValidator — platform admin is never judged as the factory user with the same id")
class FactoryAccessValidatorPlatformActorTest {

    private static final long SHARED_ID = 1L;
    private static final String TARGET_FACTORY = "F001";

    @Mock private UserRepository userRepository;

    private FactoryAccessValidator validator;

    @BeforeEach
    void setUp() {
        validator = new FactoryAccessValidator(userRepository);
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
    private void collidingUser(String factoryId, String roleCode) {
        User user = new User();
        user.setId(SHARED_ID);
        user.setFactoryId(factoryId);
        user.setRoleCode(roleCode);
        lenient().when(userRepository.findById(SHARED_ID)).thenReturn(Optional.of(user));
    }

    @Test
    @DisplayName("bypass platform roles reach any factory even though users row with the same id is F999's")
    void bypassPlatformRole_notJudgedAsCollidingUser() {
        collidingUser("F999", "operator");
        for (String role : AuthenticatedActor.FACTORY_BYPASS_PLATFORM_ROLES) {
            as(AuthenticatedActorType.PLATFORM_ADMIN, "PLATFORM", role);
            assertDoesNotThrow(() -> validator.validateAccess(TARGET_FACTORY, SHARED_ID), role);
        }
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("other platform roles are refused even though users row with the same id would bypass")
    void nonBypassPlatformRole_refused_evenIfCollidingUserWouldBypass() {
        collidingUser("PLATFORM", "super_admin");
        for (String role : List.of("auditor", "operation_admin", "system_admin")) {
            as(AuthenticatedActorType.PLATFORM_ADMIN, "PLATFORM", role);
            AuthorizationException ex = assertThrows(AuthorizationException.class,
                    () -> validator.validateAccess(TARGET_FACTORY, SHARED_ID), role);
            assertTrue(ex.getMessage().contains(role), ex.getMessage());
        }
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("negative control: the factory user with that id keeps their own result")
    void negativeControl_factoryUserWithSameIdKeepsOwnResult() {
        collidingUser("F999", "operator");
        as(AuthenticatedActorType.FACTORY_USER, "F999", "operator");

        assertThrows(AuthorizationException.class, () -> validator.validateAccess(TARGET_FACTORY, SHARED_ID));
        assertDoesNotThrow(() -> validator.validateAccess("F999", SHARED_ID));
    }

    @Test
    @DisplayName("outside an HTTP request (jobs) the users lookup is unchanged")
    void noRequest_usesUsersRow() {
        when(userRepository.findById(SHARED_ID)).thenReturn(Optional.of(user("F999")));
        assertThrows(AuthorizationException.class, () -> validator.validateAccess(TARGET_FACTORY, SHARED_ID));
    }

    private static User user(String factoryId) {
        User user = new User();
        user.setId(SHARED_ID);
        user.setFactoryId(factoryId);
        user.setRoleCode("operator");
        return user;
    }
}
