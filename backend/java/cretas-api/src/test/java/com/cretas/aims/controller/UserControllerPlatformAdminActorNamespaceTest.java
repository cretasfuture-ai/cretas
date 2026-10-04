package com.cretas.aims.controller;

import com.cretas.aims.config.JwtAuthInterceptor;
import com.cretas.aims.config.ModuleEnabledInterceptor;
import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.TokenBlacklistService;
import com.cretas.aims.service.UserModuleAccessService;
import com.cretas.aims.service.UserService;
import com.cretas.aims.service.config.FactoryConfigService;
import com.cretas.aims.utils.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/mobile/{factoryId}/users} through the production interceptor chain
 * (JWT → @RequirePermission → @RequireModule), with a platform admin whose id equals a
 * factory user's id.
 *
 * <p>Platform admin tokens carry {@code factoryId=PLATFORM} and the {@code platform_admins} id;
 * {@code users.id} is an independent sequence, so on a fresh DB platform admin id=1 and
 * {@code users.id=1} ({@code phase2a_test_user}, F999) coexist. Before the fix both gates looked
 * the platform id up in {@code users}: the module gate refused with 「不属于工厂」, and the
 * permission gate judged the request by that unrelated account's permissions — which lets a
 * platform {@code auditor} borrow a same-id factory member's {@code hr:read_write}.</p>
 */
class UserControllerPlatformAdminActorNamespaceTest {

    private static final String TARGET = "FOOD_3100_001";
    private static final String OTHER = "F999";
    private static final String SUPER_ADMIN_TOKEN = "synthetic-platform-super-admin";
    private static final String AUDITOR_TOKEN = "synthetic-platform-auditor";
    private static final String FACTORY_TOKEN = "synthetic-factory-user";
    private static final String BODY = "{\"username\":\"new_worker\",\"password\":\"x1234567\",\"roleCode\":\"operator\"}";

    private final UserService userService = mock(UserService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final FactoryConfigService configs = mock(FactoryConfigService.class);
    private final UserModuleAccessService moduleAccess = mock(UserModuleAccessService.class);
    private final JwtUtil jwt = mock(JwtUtil.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;

    /** {@code users.id = 1}: same number as the platform admin's id, different table, other factory. */
    private final User seededUserOne = user(1L, OTHER, "phase2a_test_user");

    @BeforeEach
    void setUp() {
        when(users.findById(1L)).thenReturn(Optional.of(seededUserOne));
        when(configs.isModuleEnabled(anyString(), eq("hr_employee"))).thenReturn(true);
        when(userService.createUser(anyString(), any())).thenReturn(new UserDTO());

        token(SUPER_ADMIN_TOKEN, "PLATFORM", "super_admin");
        token(AUDITOR_TOKEN, "PLATFORM", "auditor");
        token(FACTORY_TOKEN, OTHER, "factory_super_admin");

        JwtAuthInterceptor auth = new JwtAuthInterceptor();
        ReflectionTestUtils.setField(auth, "jwtUtil", jwt);
        ReflectionTestUtils.setField(auth, "tokenBlacklistService", mock(TokenBlacklistService.class));
        ReflectionTestUtils.setField(auth, "factoryRepository", mock(FactoryRepository.class));
        ReflectionTestUtils.setField(auth, "userRepository", users);
        mvc = MockMvcBuilders.standaloneSetup(new UserController(
                        userService, mock(MobileService.class), mock(PurchaseOrderRepository.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(auth,
                        new PermissionInterceptor(permissions, users, mapper),
                        new ModuleEnabledInterceptor(configs, moduleAccess, users, mapper))
                .build();
    }

    @Test
    @DisplayName("platform super_admin whose id equals users.id=1 (F999) creates a user in another factory")
    void platformAdminIsNotResolvedThroughUsers() throws Exception {
        mvc.perform(create(TARGET, SUPER_ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(userService).createUser(eq(TARGET), any());
        // Both gates ran (the module gate consulted the factory switch) without asking users who id=1 is.
        verify(configs).isModuleEnabled(TARGET, "hr_employee");
        verify(users, never()).findById(anyLong());
        verify(permissions, never()).hasAnyPermission(any(), any(String[].class));
        verify(moduleAccess, never()).canAccessModule(any(), anyString());
    }

    @Test
    @DisplayName("platform auditor does not borrow the permissions of a same-id member of the target factory")
    void platformAuditorDoesNotBorrowSameIdMembersPermissions() throws Exception {
        User memberOne = user(1L, TARGET, "target_member");
        when(users.findById(1L)).thenReturn(Optional.of(memberOne));
        when(permissions.hasAnyPermission(eq(memberOne), any(String[].class))).thenReturn(true);
        when(moduleAccess.canAccessModule(memberOne, "hr_employee")).thenReturn(true);

        mvc.perform(create(TARGET, AUDITOR_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("auditor")));

        verify(userService, never()).createUser(anyString(), any());
        verify(permissions, never()).hasAnyPermission(any(), any(String[].class));
    }

    @Test
    @DisplayName("module gate on its own refuses a platform role without factory bypass by type, without a users lookup")
    void moduleGateRefusesPlatformRoleWithoutBypassByType() throws Exception {
        MockMvc moduleOnly = MockMvcBuilders.standaloneSetup(new UserController(
                        userService, mock(MobileService.class), mock(PurchaseOrderRepository.class)))
                .addInterceptors(new ModuleEnabledInterceptor(configs, moduleAccess, users, mapper))
                .build();
        moduleOnly.perform(create(TARGET, null)
                        .requestAttr("userId", 1L)
                        .requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE, new AuthenticatedActor(
                                AuthenticatedActorType.PLATFORM_ADMIN, 1L, "PLATFORM", "auditor")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("auditor")))
                .andExpect(jsonPath("$.message", containsString("hr_employee")))
                .andExpect(jsonPath("$.meta.actorType").value("PLATFORM_ADMIN"));

        verify(users, never()).findById(anyLong());
        verify(userService, never()).createUser(anyString(), any());
    }

    @Test
    @DisplayName("attribution: a factory switch that is off still stops the platform admin, with the module gate's wording")
    void disabledModuleStillStopsPlatformAdmin() throws Exception {
        when(configs.isModuleEnabled(TARGET, "hr_employee")).thenReturn(false);

        // canonical still answers 「模块未启用」 with 400 (ModuleEnabledInterceptor#sendModuleDisabled)
        mvc.perform(create(TARGET, SUPER_ADMIN_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("hr_employee")))
                .andExpect(jsonPath("$.message", containsString("未启用")));

        verify(userService, never()).createUser(anyString(), any());
    }

    @Test
    @DisplayName("negative control: factory user id=1 still gets their own permissions and module access (grant)")
    void factoryUserWithSameIdKeepsOwnGrant() throws Exception {
        when(permissions.hasAnyPermission(eq(seededUserOne), any(String[].class))).thenReturn(true);
        when(moduleAccess.canAccessModule(seededUserOne, "hr_employee")).thenReturn(true);

        mvc.perform(create(OTHER, FACTORY_TOKEN)).andExpect(status().isOk());

        verify(permissions).hasAnyPermission(eq(seededUserOne), any(String[].class));
        verify(moduleAccess).canAccessModule(seededUserOne, "hr_employee");
        verify(userService).createUser(eq(OTHER), any());
    }

    @Test
    @DisplayName("negative control: factory user id=1 still gets their own module deny, from the module gate")
    void factoryUserWithSameIdKeepsOwnDeny() throws Exception {
        when(permissions.hasAnyPermission(eq(seededUserOne), any(String[].class))).thenReturn(true);
        when(moduleAccess.canAccessModule(seededUserOne, "hr_employee")).thenReturn(false);

        mvc.perform(create(OTHER, FACTORY_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("phase2a_test_user(1)")))
                .andExpect(jsonPath("$.message", containsString("缺少模块 hr_employee")))
                .andExpect(jsonPath("$.meta.module").value("hr_employee"));

        verify(userService, never()).createUser(anyString(), any());
    }

    @Test
    @DisplayName("negative control: factory user id=1 still gets their own permission deny, from the permission gate")
    void factoryUserWithSameIdKeepsOwnPermissionDeny() throws Exception {
        when(permissions.hasAnyPermission(eq(seededUserOne), any(String[].class))).thenReturn(false);

        mvc.perform(create(OTHER, FACTORY_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(permissions).hasAnyPermission(eq(seededUserOne), any(String[].class));
        verify(moduleAccess, never()).canAccessModule(any(), anyString());
        verify(userService, never()).createUser(anyString(), any());
    }

    private MockHttpServletRequestBuilder create(String factoryId, String token) {
        MockHttpServletRequestBuilder req = post("/api/mobile/{f}/users", factoryId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY);
        return token == null ? req : req.header("Authorization", "Bearer " + token);
    }

    private void token(String token, String factoryId, String role) {
        when(jwt.validateToken(token)).thenReturn(true);
        when(jwt.getUserIdFromToken(token)).thenReturn(1L);
        when(jwt.getUsernameFromToken(token)).thenReturn(role + "-1");
        when(jwt.getFactoryIdFromToken(token)).thenReturn(factoryId);
        when(jwt.getRoleFromToken(token)).thenReturn(role);
    }

    private static User user(long id, String factoryId, String username) {
        User u = new User();
        u.setId(id);
        u.setFactoryId(factoryId);
        u.setUsername(username);
        u.setRoleCode("factory_super_admin");
        u.setIsActive(true);
        return u;
    }
}
