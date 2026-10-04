package com.cretas.aims.controller;

import com.cretas.aims.controller.permission.PermissionMatrixController;
import com.cretas.aims.controller.platform.PlatformRolePermissionController;
import com.cretas.aims.dto.user.UpdateMyProfileRequest;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.permission.PlatformRolePermission;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.TimeClockService;
import com.cretas.aims.service.TimeStatsService;
import com.cretas.aims.service.UserModuleAccessService;
import com.cretas.aims.service.UserService;
import com.cretas.aims.service.permission.PlatformRolePermissionWriteService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Canonical-only controllers that take the caller's id from the request and load {@code users} by it.
 * platform_admins.id and users.id are independent sequences: id 1 below is both a platform admin and
 * F999's phase2a_test_user, an operator with no HR / system grants. Bypass platform roles get full
 * factory access, other platform roles are refused, and the colliding factory user is never consulted.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("request-resolved controllers — platform admin is never judged as the factory user with the same id")
class PlatformActorRequestControllersTest {

    private static final long SHARED_ID = 1L;
    private static final long OTHER_WORKER = 2L;
    private static final String FACTORY = "F999";
    private static final List<String> NON_BYPASS = List.of("auditor", "operation_admin", "system_admin");

    @Mock private UserRepository userRepository;
    @Mock private PermissionService permissionService;
    @Mock private UserModuleAccessService userModuleAccessService;
    @Mock private TimeStatsService timeStatsService;
    @Mock private TimeClockService timeClockService;
    @Mock private PlatformRolePermissionWriteService writeService;
    @Mock private MobileService mobileService;
    @Mock private UserService userService;

    @InjectMocks private TimeStatsController timeStatsController;
    @InjectMocks private UserModuleAccessController userModuleAccessController;
    @InjectMocks private TimeClockController timeClockController;
    @InjectMocks private PermissionMatrixController permissionMatrixController;
    @InjectMocks private PlatformRolePermissionController platformRolePermissionController;
    @InjectMocks private UserController userController;

    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        User colliding = user(SHARED_ID, "operator");
        when(userRepository.findById(SHARED_ID)).thenReturn(Optional.of(colliding));
        when(userRepository.findByIdAndFactoryId(SHARED_ID, FACTORY)).thenReturn(Optional.of(colliding));
        when(userRepository.findByIdAndFactoryId(OTHER_WORKER, FACTORY))
                .thenReturn(Optional.of(user(OTHER_WORKER, "operator")));
        // The colliding operator holds no HR / system grants.
        when(permissionService.hasAnyPermission(eq(colliding), any(String[].class))).thenReturn(false);
        when(mobileService.getUserFromToken(anyString())).thenReturn(UserDTO.builder().id(SHARED_ID).build());
        PlatformRolePermission row = new PlatformRolePermission();
        PlatformRolePermissionWriteService.WriteResult result =
                new PlatformRolePermissionWriteService.WriteResult(row, 9L, "-");
        when(writeService.write(anyLong(), anyString(), any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(result);
        when(writeService.write(any(User.class), any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(result);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static User user(long id, String roleCode) {
        User user = new User();
        user.setId(id);
        user.setFactoryId(FACTORY);
        user.setRoleCode(roleCode);
        user.setIsActive(true);
        return user;
    }

    private void as(AuthenticatedActorType type, String factoryId, String role) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("userId", SHARED_ID);
        req.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, new AuthenticatedActor(type, SHARED_ID, factoryId, role));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
        request = req;
    }

    private boolean timeStatsOk(long workerId) {
        return timeStatsController.getWorkerTimeStatsById(FACTORY, (int) workerId,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 4), request).getStatusCode().is2xxSuccessful();
    }

    private boolean moduleAccessOk(long targetUserId) {
        return opens(() -> userModuleAccessController.get(FACTORY, String.valueOf(targetUserId), SHARED_ID, request));
    }

    private boolean clockStatusOk() {
        return opens(() -> timeClockController.getClockStatus(FACTORY, SHARED_ID));
    }

    private boolean matrixWriteOk() {
        return opens(() -> permissionMatrixController.writeCell(FACTORY, "sales_manager", "warehouse",
                new PermissionMatrixController.WriteCellRequest("r", "why"), SHARED_ID, request));
    }

    private boolean platformWriteOk() {
        return opens(() -> platformRolePermissionController.update("sales_manager", "warehouse", "r",
                "Bearer token", request));
    }

    private boolean myProfileOk() {
        return opens(() -> userController.updateMyProfile(FACTORY, new UpdateMyProfileRequest()));
    }

    private static boolean opens(Runnable call) {
        try {
            call.run();
            return true;
        } catch (BusinessException e) {
            assertEquals(403, e.getCode(), e.getMessage());
            return false;
        }
    }

    @Test
    @DisplayName("bypass platform roles: full factory access, audited as themselves, never via users")
    void bypassPlatformRole_fullAccess() {
        for (String role : AuthenticatedActor.FACTORY_BYPASS_PLATFORM_ROLES) {
            as(AuthenticatedActorType.PLATFORM_ADMIN, "PLATFORM", role);
            // Reading another worker needs HR grants the colliding operator does not have.
            assertTrue(timeStatsOk(OTHER_WORKER), role);
            // Reading another account's module access needs system grants the colliding operator lacks.
            assertTrue(moduleAccessOk(OTHER_WORKER), role);
            assertTrue(matrixWriteOk(), role);
            verify(writeService).write(SHARED_ID, role, FACTORY, "sales_manager", "warehouse", "r", "why");
            assertTrue(platformWriteOk(), role);
            verify(writeService).write(SHARED_ID, role, null, "sales_manager", "warehouse", "r", null);
            // Self-service endpoints have no platform-admin identity to act on.
            assertFalse(clockStatusOk(), role);
            assertFalse(myProfileOk(), role);
            clearInvocations(writeService);
        }
        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).findByIdAndFactoryId(eq(SHARED_ID), any());
        verify(timeClockService, never()).getClockStatus(any(), any());
        verify(userService, never()).updateMyProfile(any(), any(), any());
        verify(writeService, never()).write(any(User.class), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("other platform roles: refused, even where the colliding user would pass as 'self'")
    void nonBypassPlatformRole_refused() {
        for (String role : NON_BYPASS) {
            as(AuthenticatedActorType.PLATFORM_ADMIN, "PLATFORM", role);
            // id 1 is the colliding operator's own id: the self checks would let it through.
            assertFalse(timeStatsOk(SHARED_ID), role);
            assertFalse(moduleAccessOk(SHARED_ID), role);
            assertFalse(matrixWriteOk(), role);
            assertFalse(platformWriteOk(), role);
            assertFalse(clockStatusOk(), role);
            assertFalse(myProfileOk(), role);
        }
        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).findByIdAndFactoryId(eq(SHARED_ID), any());
        verify(timeStatsService, never()).getWorkerTimeStatsById(any(), any(), any(), any());
        verify(userModuleAccessService, never()).listEffectiveAccess(any(), any());
        verify(writeService, never()).write(anyLong(), any(), any(), any(), any(), any(), any());
        verify(writeService, never()).write(any(User.class), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("negative control: the factory user with that id keeps their own result")
    void negativeControl_factoryUserWithSameIdKeepsOwnResult() {
        as(AuthenticatedActorType.FACTORY_USER, FACTORY, "operator");

        assertTrue(timeStatsOk(SHARED_ID), "self read");
        assertFalse(timeStatsOk(OTHER_WORKER), "no HR grants");
        assertTrue(moduleAccessOk(SHARED_ID), "self read");
        assertFalse(moduleAccessOk(OTHER_WORKER), "no system grants");
        assertTrue(clockStatusOk());
        assertTrue(myProfileOk());
        verify(userService).updateMyProfile(eq(FACTORY), eq(SHARED_ID), any());
        assertTrue(matrixWriteOk());
        verify(writeService).write(any(User.class), eq(FACTORY), eq("sales_manager"), eq("warehouse"), eq("r"), eq("why"));
        verify(writeService, never()).write(anyLong(), any(), any(), any(), any(), any(), any());
    }
}
