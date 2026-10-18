package com.cretas.aims.controller;

import com.cretas.aims.entity.User;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CurrentPermissionsControllerContractTest {
    private UserRepository users;
    private PermissionService permissions;
    private CurrentPermissionsController controller;
    private MockHttpServletRequest request;
    private User user;

    @BeforeEach void setUp() {
        users = mock(UserRepository.class);
        permissions = mock(PermissionService.class);
        controller = new CurrentPermissionsController(users, permissions);
        request = new MockHttpServletRequest();
        request.setAttribute("userId", 999L); // Legacy numeric identity is never authoritative.
        actor(AuthenticatedActorType.FACTORY_USER, "F1");
        user = new User();
        user.setId(9L); user.setFactoryId("F1"); user.setIsActive(true);
        user.setRoleCode("procurement_manager");
        when(users.findById(9L)).thenReturn(Optional.of(user));
    }

    private void actor(AuthenticatedActorType type, String factory) {
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(type, 9L, factory, "factory_super_admin"));
    }

    @Test void returnsAuthoritativeLevelsForPersistedRoleAndTypedSelf() {
        var levels = Map.of("procurement", "rw", "finance", "r", "system", "-");
        when(permissions.resolveModuleLevelsForAuthorization(user)).thenReturn(levels);
        assertThat(controller.read("F1", request).getData()).isEqualTo(levels);
        verify(users).findById(9L);
        verify(permissions).resolveModuleLevelsForAuthorization(user);
        assertThat(user.getRoleCode()).isEqualTo("procurement_manager");
    }

    @Test void missingTypedActorCannotBorrowLegacyId() {
        request.removeAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE);
        denied(401);
        verifyNoInteractions(users, permissions);
    }

    @Test void platformIdCollisionCannotReadFactoryUser() {
        actor(AuthenticatedActorType.PLATFORM_ADMIN, "F1");
        denied(403); verifyNoInteractions(users, permissions);
    }

    @Test void otherFactoryActorIsRejectedBeforeRepository() {
        actor(AuthenticatedActorType.FACTORY_USER, "F2");
        denied(403); verifyNoInteractions(users, permissions);
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "inactive", "nullActive", "deleted", "foreign"})
    void unavailablePersistedAccountCannotObtainSnapshot(String state) {
        switch (state) {
            case "missing" -> when(users.findById(9L)).thenReturn(Optional.empty());
            case "inactive" -> user.setIsActive(false);
            case "nullActive" -> user.setIsActive(null);
            case "deleted" -> user.setDeletedAt(LocalDateTime.now());
            case "foreign" -> user.setFactoryId("F2");
        }
        denied(403); verifyNoInteractions(permissions);
    }

    @Test void unavailablePermissionStoreDoesNotBecomeAnEmptySuccess() {
        when(permissions.resolveModuleLevelsForAuthorization(user)).thenThrow(new BusinessException(503, "权限配置暂不可用"));
        denied(503);
    }

    private void denied(int code) {
        assertThatThrownBy(() -> controller.read("F1", request))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(code));
    }
}
