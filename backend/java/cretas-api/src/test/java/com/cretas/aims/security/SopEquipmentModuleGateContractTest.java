package com.cretas.aims.security;

import com.cretas.aims.entity.PlatformAdmin;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.Attachment.EntityType;
import com.cretas.aims.entity.enums.PlatformRole;
import com.cretas.aims.entity.enums.Status;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.PlatformAdminRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.UserModuleAccessService;
import com.cretas.aims.service.config.FactoryConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SopEquipmentModuleGateContractTest {

    private static final String FACTORY = "F-EQUIPMENT";
    private static final long USER_ID = 701L;
    private static final long PLATFORM_ID = 701L;

    @Mock AttachmentPermissionResolver genericPermissions;
    @Mock UserRepository userRepository;
    @Mock PlatformAdminRepository platformAdminRepository;
    @Mock FactoryConfigService factoryConfigService;
    @Mock UserModuleAccessService userModuleAccessService;

    private SopAttachmentAccessResolver resolver;
    private User user;

    @BeforeEach
    void setUp() {
        resolver = new SopAttachmentAccessResolver(genericPermissions, userRepository,
                platformAdminRepository, factoryConfigService, userModuleAccessService);
        user = new User();
        user.setId(USER_ID);
        user.setFactoryId(FACTORY);
        user.setIsActive(true);
    }

    @Test
    void disabledFactoryModuleBlocksFactoryAndPlatformBeforePermissionOrPlatformLookup() {
        when(factoryConfigService.isModuleEnabled(FACTORY, "equipment")).thenReturn(false);

        assertThatThrownBy(() -> resolver.require(request(factoryActor()), FACTORY,
                EntityType.EQUIPMENT, false))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(403));
        assertThatThrownBy(() -> resolver.require(request(platformActor()), FACTORY,
                EntityType.EQUIPMENT, false))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(403));
        verify(genericPermissions, never()).requireRead(any(), eq(EntityType.EQUIPMENT));
        verify(platformAdminRepository, never()).findById(PLATFORM_ID);
    }

    @Test
    void accountModuleDenyBlocksEvenWhenEquipmentPermissionWouldAllow() {
        when(factoryConfigService.isModuleEnabled(FACTORY, "equipment")).thenReturn(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userModuleAccessService.canAccessModule(user, "equipment")).thenReturn(false);

        assertThatThrownBy(() -> resolver.require(request(factoryActor()), FACTORY,
                EntityType.EQUIPMENT, false))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(403));
        verify(genericPermissions, never()).requireRead(any(), eq(EntityType.EQUIPMENT));
    }

    @Test
    void moduleAndAccountLookupFailuresFailClosed() {
        when(factoryConfigService.isModuleEnabled(FACTORY, "equipment"))
                .thenThrow(new IllegalStateException("config unavailable"));
        assertThatThrownBy(() -> resolver.require(request(factoryActor()), FACTORY,
                EntityType.EQUIPMENT, false))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(503));

        doReturn(true).when(factoryConfigService).isModuleEnabled(FACTORY, "equipment");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userModuleAccessService.canAccessModule(user, "equipment"))
                .thenThrow(new IllegalStateException("access unavailable"));
        assertThatThrownBy(() -> resolver.require(request(factoryActor()), FACTORY,
                EntityType.EQUIPMENT, false))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(503));
        verify(genericPermissions, never()).requireRead(any(), eq(EntityType.EQUIPMENT));
    }

    @Test
    void platformNamespaceDoesNotResolveCollidingFactoryUserId() {
        when(factoryConfigService.isModuleEnabled(FACTORY, "equipment")).thenReturn(true);
        PlatformAdmin platform = PlatformAdmin.builder().id(PLATFORM_ID)
                .platformRole(PlatformRole.super_admin).status(Status.active).build();
        when(platformAdminRepository.findById(PLATFORM_ID)).thenReturn(Optional.of(platform));

        assertThat(resolver.require(request(platformActor()), FACTORY, EntityType.EQUIPMENT, false))
                .isEqualTo(platformActor());
        verify(userRepository, never()).findById(PLATFORM_ID);
        verify(userModuleAccessService, never()).canAccessModule(any(), eq("equipment"));
    }

    @Test
    void missingOrInactiveFactoryActorIsRejectedBeforePermission() {
        when(factoryConfigService.isModuleEnabled(FACTORY, "equipment")).thenReturn(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> resolver.require(request(factoryActor()), FACTORY,
                EntityType.EQUIPMENT, false))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(401));

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        user.setIsActive(false);
        assertThatThrownBy(() -> resolver.require(request(factoryActor()), FACTORY,
                EntityType.EQUIPMENT, false))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(401));
        verify(genericPermissions, never()).requireRead(any(), eq(EntityType.EQUIPMENT));
    }

    @Test
    void workProcessStillUsesExistingPermissionPathWithoutEquipmentModuleGate() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        assertThat(resolver.require(request(factoryActor()), FACTORY, EntityType.WORK_PROCESS, false))
                .isNotNull();
        verify(genericPermissions).requireRead(user, EntityType.WORK_PROCESS);
        verify(userModuleAccessService, never()).canAccessModule(any(), eq("equipment"));
    }

    private MockHttpServletRequest request(AuthenticatedActor actor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, actor);
        return request;
    }

    private AuthenticatedActor factoryActor() {
        return new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, USER_ID, FACTORY, "equipment_admin");
    }

    private AuthenticatedActor platformActor() {
        return new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, PLATFORM_ID, null, "super_admin");
    }
}
