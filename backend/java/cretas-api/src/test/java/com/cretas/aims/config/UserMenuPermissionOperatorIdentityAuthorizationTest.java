package com.cretas.aims.config;

import com.cretas.aims.controller.auth.UserMenuPermissionController;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.auth.UserMenuPermission;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.auth.UserMenuPermissionService;
import com.cretas.aims.utils.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UserMenuPermissionController 曾用 {@code SecurityContextHolder} 取操作人; 本项目从不填充它,
 * 读出来恒为空、被 catch 吞成 "system"。用生产的 {@link WebMvcConfig} 注册顺序、真签名 JWT、
 * 真 JWT / 权限 / 角色拦截器跑真实 HTTP, 回答两件事:
 * <ol>
 *   <li>越权? 否 —— 低权限账号、跨厂账号都被 {@code @RequireRole} / 租户闸挡在控制器之外(阴性对照在下面);</li>
 *   <li>失效? 是 —— 有权的人授权/撤销时, {@code granted_by} 审计字段恒为 "system", 查不出是谁改的(已修: 取 JWT username)。</li>
 * </ol>
 */
@DisplayName("菜单权限覆盖: 谁进得去 + 操作人审计取自真实身份")
class UserMenuPermissionOperatorIdentityAuthorizationTest {

    private static final String BASE = "/api/mobile/F006/users/200/menu-permissions";

    private final UserRepository users = mock(UserRepository.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final UserMenuPermissionService service = mock(UserMenuPermissionService.class);

    private JwtUtil jwt;
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt, "secret", UUID.randomUUID().toString() + UUID.randomUUID());
        ReflectionTestUtils.setField(jwt, "expiration", 60_000L);

        long id = 100;
        for (String[] u : new String[][]{{"F006", "factory_super_admin"}, {"F006", "operator"}, {"F001", "factory_super_admin"}}) {
            User user = new User();
            user.setId(id);
            user.setFactoryId(u[0]);
            user.setUsername(u[1] + "_" + u[0]);
            user.setRoleCode(u[1]);
            user.setIsActive(true);
            when(users.findById(id)).thenReturn(Optional.of(user));
            when(users.findByIdAndFactoryId(id, u[0])).thenReturn(Optional.of(user));
            id++;
        }
        // 权限闸: 只有 factory_super_admin 有 system:read_write (operator 在生产兜底矩阵里也没有)
        when(permissions.hasAnyPermission(any(User.class), any(String[].class)))
                .thenAnswer(inv -> "factory_super_admin".equals(((User) inv.getArgument(0)).getRoleCode()));
        when(service.grantMenu(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new UserMenuPermission());
        when(service.revokeMenu(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new UserMenuPermission());

        var factories = mock(com.cretas.aims.repository.FactoryRepository.class);
        when(factories.findById(anyString())).thenReturn(Optional.empty());

        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.addBeanFactoryPostProcessor(registry -> {
            registry.registerSingleton("jwtUtil", jwt);
            registry.registerSingleton("tokenBlacklistService", mock(com.cretas.aims.service.TokenBlacklistService.class));
            registry.registerSingleton("webLoginSessionService",
                    mock(com.cretas.aims.service.mobile.WebLoginSessionService.class));
            registry.registerSingleton("factoryRepository", factories);
            registry.registerSingleton("userRepository", users);
            registry.registerSingleton("platformAdminRepository",
                    mock(com.cretas.aims.repository.PlatformAdminRepository.class));
            registry.registerSingleton("objectMapper",
                    new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
            registry.registerSingleton("permissionService", permissions);
            registry.registerSingleton("moduleEnabledInterceptor", passThrough(ModuleEnabledInterceptor.class));
            registry.registerSingleton("dataScopeRouteInterceptor",
                    passThrough(com.cretas.aims.security.DataScopeRouteInterceptor.class));
            registry.registerSingleton("processSecretGuardInterceptor",
                    passThrough(com.cretas.aims.security.ProcessSecretGuardInterceptor.class));
            registry.registerSingleton("platformDataAccessLogInterceptor",
                    passThrough(com.cretas.aims.audit.PlatformDataAccessLogInterceptor.class));
            registry.registerSingleton("userMenuPermissionService", service);
        });
        context.register(WebMvc.class, WebMvcConfig.class, JwtAuthInterceptor.class, DemoReadOnlyInterceptor.class,
                PermissionInterceptor.class, RequireRoleInterceptor.class,
                com.cretas.aims.exception.GlobalExceptionHandler.class,
                UserMenuPermissionController.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    private String adminToken() {
        return bearer(jwt.generateToken(100L, "F006", "factory_super_admin_F006", "factory_super_admin"));
    }

    // ---------------------------------------------------------------- 没有越权 (阴性对照)

    @Test
    @DisplayName("阴性对照: 低权限(operator)不能看、不能授、不能撤、不能清别人的菜单覆盖")
    void lowPrivilegeCannotReadOrChange() throws Exception {
        String op = bearer(jwt.generateToken(101L, "F006", "operator_F006", "operator"));
        mvc.perform(get(BASE).header("Authorization", op)).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/grant").header("Authorization", op)
                .contentType(MediaType.APPLICATION_JSON).content("{\"menuCode\":\"finance\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/revoke").header("Authorization", op)
                .contentType(MediaType.APPLICATION_JSON).content("{\"menuCode\":\"finance\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete(BASE + "/finance").header("Authorization", op)).andExpect(status().isForbidden());
        verify(service, never()).listUserOverrides(anyString(), anyString());
        verify(service, never()).grantMenu(anyString(), anyString(), anyString(), any(), any());
        verify(service, never()).revokeMenu(anyString(), anyString(), anyString(), any(), any());
        verify(service, never()).clearUserOverride(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("阴性对照: 无 token / 别的工厂的总监都进不去")
    void anonymousAndCrossFactoryAreRefused() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().is4xxClientError());
        String otherFactoryAdmin = bearer(jwt.generateToken(102L, "F001", "factory_super_admin_F001", "factory_super_admin"));
        mvc.perform(post(BASE + "/grant").header("Authorization", otherFactoryAdmin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"menuCode\":\"finance\"}"))
                .andExpect(status().is4xxClientError());
        verify(service, never()).grantMenu(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("阴性对照: 有权的总监仍能列出覆盖")
    void adminStillReads() throws Exception {
        mvc.perform(get(BASE).header("Authorization", adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        verify(service).listUserOverrides("F006", "200");
    }

    // ---------------------------------------------------------------- 失效: 操作人审计

    @Test
    @DisplayName("🔴 授权/撤销落库的 granted_by 是真实操作人(JWT username), 不是恒定的 system")
    void grantedByIsTheRealOperator() throws Exception {
        mvc.perform(post(BASE + "/grant").header("Authorization", adminToken())
                .contentType(MediaType.APPLICATION_JSON).content("{\"menuCode\":\"finance\",\"remark\":\"r\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        verify(service).grantMenu(eq("F006"), eq("200"), eq("finance"), eq("factory_super_admin_F006"), eq("r"));

        mvc.perform(post(BASE + "/revoke").header("Authorization", adminToken())
                .contentType(MediaType.APPLICATION_JSON).content("{\"menuCode\":\"finance\"}"))
                .andExpect(status().isOk());
        verify(service).revokeMenu(eq("F006"), eq("200"), eq("finance"), eq("factory_super_admin_F006"), any());
        verify(service, never()).grantMenu(anyString(), anyString(), anyString(), eq("system"), any());
    }

    @org.springframework.context.annotation.Configuration
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    static class WebMvc { }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static <T extends HandlerInterceptor> T passThrough(Class<T> type) {
        T interceptor = mock(type);
        try {
            when(interceptor.preHandle(any(), any(), any())).thenReturn(true);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
        return interceptor;
    }
}
