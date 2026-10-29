package com.cretas.aims.config;

import com.cretas.aims.controller.AIController;
import com.cretas.aims.controller.platform.AIReportPromptConfigController;
import com.cretas.aims.controller.workflow.WorkflowInstanceController;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.AIEnterpriseService;
import com.cretas.aims.service.AIReportPromptConfigService;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import com.cretas.aims.utils.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @PreAuthorize} 在本工程是 NO-OP（{@code SecurityAutoConfiguration} 被排除、没有
 * {@code @EnableMethodSecurity}），所以「注解写了 = 守住了」不成立。本类对复审点名的三处，
 * 用<b>生产的</b> {@link WebMvcConfig} 注册顺序、真签名的 JWT、真的 JWT / 权限 / 角色拦截器
 * 跑真实 HTTP，逐处回答「现在到底谁进得去」。
 *
 * <ol>
 *   <li>{@code PUT /api/mobile/{f}/ai/quota}：注解写「仅限平台超级管理员」，实际只有
 *       {@code analytics:read_write}。兜底矩阵里调度 / 财务主管 / 工厂总监都有这个权限 ⇒
 *       <b>工厂账号能给自己工厂调 AI 配额</b>（越权，已修：{@code @RequireRole}）。</li>
 *   <li>{@code /api/platform/report-prompts/**}：复审说它「不在 /api/platform 下、无兜底」——
 *       实测它<b>在</b>，JWT 拦截器的平台路径闸挡住了所有工厂账号；修复后另加类级
 *       {@code @RequireRole}，使它不依赖路径前缀。</li>
 *   <li>{@code WorkflowEngineService#listAllRunning}：那一处是 javadoc 里的文字，不是注解；
 *       唯一调用方 {@code GET .../workflow/instances/admin-running} 带生效的 {@code @RequireRole}。</li>
 * </ol>
 *
 * 权限判定用 {@link PermissionServiceImpl#hardcodedMatrixSnapshot()}（兜底矩阵）回答，
 * 不是随手 stub 成 true —— 「谁有 analytics:read_write」与代码里的矩阵同源。
 */
@DisplayName("@PreAuthorize 不生效的三处 — 真实 HTTP 下谁进得去")
class InertPreAuthorizeGapsAuthorizationTest {

    private final UserRepository users = mock(UserRepository.class);
    private final AIEnterpriseService aiEnterprise = mock(AIEnterpriseService.class);
    private final MobileService mobileService = mock(MobileService.class);
    private final AIReportPromptConfigService promptConfigs = mock(AIReportPromptConfigService.class);
    private final WorkflowEngineService workflowEngine = mock(WorkflowEngineService.class);
    private final PermissionService permissions = mock(PermissionService.class);

    private JwtUtil jwt;
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt, "secret", UUID.randomUUID().toString() + UUID.randomUUID());
        ReflectionTestUtils.setField(jwt, "expiration", 60_000L);

        long id = 100;
        for (String role : List.of("dispatcher", "finance_manager", "factory_super_admin", "operator")) {
            User user = new User();
            user.setId(id);
            user.setFactoryId("F006");
            user.setUsername(role);
            user.setRoleCode(role);
            user.setIsActive(true);
            when(users.findById(id)).thenReturn(Optional.of(user));
            when(users.findByIdAndFactoryId(id, "F006")).thenReturn(Optional.of(user));
            id++;
        }

        // 权限按兜底矩阵回答: module:read_write 要求矩阵里该角色该模块恰为 read_write
        Map<String, Map<String, String>> matrix = PermissionServiceImpl.hardcodedMatrixSnapshot();
        when(permissions.hasAnyPermission(any(User.class), any(String[].class))).thenAnswer(inv -> {
            User user = inv.getArgument(0);
            String[] required = (String[]) inv.getRawArguments()[1];
            Map<String, String> perms = matrix.getOrDefault(user.getRoleCode(), Map.of());
            for (String code : required) {
                String[] parts = code.split(":", 2);
                if ("read_write".equals(perms.get(parts[0]))) {
                    return true;
                }
            }
            return false;
        });
        var tokenUser = new com.cretas.aims.dto.user.UserDTO();
        tokenUser.setId(102L);
        when(mobileService.getUserFromToken(anyString())).thenReturn(tokenUser);
        when(promptConfigs.getAllConfigs(any())).thenReturn(
                new PageImpl<>(List.of(), org.springframework.data.domain.PageRequest.of(0, 20), 0));
        when(workflowEngine.listAllRunning(anyString(), any())).thenReturn(
                new PageImpl<>(List.of(), org.springframework.data.domain.PageRequest.of(0, 20), 0));

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
            // AIController (field injection)
            registry.registerSingleton("aiEnterpriseService", aiEnterprise);
            registry.registerSingleton("operatingReadAccess",
                    mock(com.cretas.aims.service.reports.OperatingReportReadAccess.class));
            registry.registerSingleton("basicAIService", mock(com.cretas.aims.service.AIAnalysisService.class));
            registry.registerSingleton("mobileService", mobileService);
            registry.registerSingleton("aiContextService", mock(com.cretas.aims.service.AIContextService.class));
            registry.registerSingleton("productTypeRepository",
                    mock(com.cretas.aims.repository.ProductTypeRepository.class));
            registry.registerSingleton("rawMaterialTypeRepository",
                    mock(com.cretas.aims.repository.RawMaterialTypeRepository.class));
            registry.registerSingleton("conversionRepository",
                    mock(com.cretas.aims.repository.ConversionRepository.class));
            // AIReportPromptConfigController
            registry.registerSingleton("aiReportPromptConfigService", promptConfigs);
            // WorkflowInstanceController
            registry.registerSingleton("workflowEngine", workflowEngine);
            registry.registerSingleton("approvalWorkflowService",
                    mock(com.cretas.aims.service.ApprovalWorkflowService.class));
            registry.registerSingleton("purchaseService", mock(com.cretas.aims.service.inventory.PurchaseService.class));
            registry.registerSingleton("salesService", mock(com.cretas.aims.service.inventory.SalesService.class));
            registry.registerSingleton("transferService", mock(com.cretas.aims.service.inventory.TransferService.class));
            registry.registerSingleton("stocktakeService",
                    mock(com.cretas.aims.service.factory.FactoryStocktakeService.class));
        });
        context.register(WebMvc.class, WebMvcConfig.class, JwtAuthInterceptor.class, DemoReadOnlyInterceptor.class,
                PermissionInterceptor.class, RequireRoleInterceptor.class,
                com.cretas.aims.exception.GlobalExceptionHandler.class,
                AIController.class, AIReportPromptConfigController.class, WorkflowInstanceController.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    // ------------------------------------------------------------- 1. AI 配额 (真越权, 已修)

    @Test
    @DisplayName("🔴 PUT /ai/quota: 调度 / 财务主管 / 工厂总监 都有 analytics:read_write, 却都改不了本厂 AI 配额")
    void factoryAccountsCannotRaiseTheirOwnAiQuota() throws Exception {
        long id = 100;
        for (String role : List.of("dispatcher", "finance_manager", "factory_super_admin")) {
            // 阳性前提: 这个角色确实过得了原来那道唯一生效的闸 (analytics:read_write)
            org.assertj.core.api.Assertions.assertThat(
                            PermissionServiceImpl.hardcodedMatrixSnapshot().get(role).get("analytics"))
                    .as(role + " 在兜底矩阵里有 analytics:read_write —— 否则这条用例证明不了任何事")
                    .isEqualTo("read_write");

            mvc.perform(put("/api/mobile/F006/ai/quota").param("newQuotaLimit", "999999")
                            .header("Authorization", bearer(jwt.generateToken(id, "F006", role, role))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            id++;
        }
        verify(aiEnterprise, never()).updateQuotaLimit(anyString(), anyInt());
    }

    @Test
    @DisplayName("阴性对照: 平台超级管理员仍能改工厂 AI 配额")
    void platformSuperAdminStillUpdatesQuota() throws Exception {
        mvc.perform(put("/api/mobile/F006/ai/quota").param("newQuotaLimit", "500")
                        .header("Authorization", bearer(jwt.generatePlatformToken(62L, "platform", "super_admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        verify(aiEnterprise).updateQuotaLimit("F006", 500);
    }

    // ------------------------------------------------- 2. 平台报告提示词配置 (路径闸 + 类级角色闸)

    @Test
    @DisplayName("/api/platform/report-prompts: 工厂总监与操作员都被挡 (读写都挡)")
    void factoryAccountsAreRefusedOnReportPrompts() throws Exception {
        String sa = bearer(jwt.generateToken(102L, "F006", "factory_super_admin", "factory_super_admin"));
        String op = bearer(jwt.generateToken(103L, "F006", "operator", "operator"));
        for (String token : List.of(sa, op)) {
            mvc.perform(get("/api/platform/report-prompts").header("Authorization", token))
                    .andExpect(status().isForbidden());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .delete("/api/platform/report-prompts/x").header("Authorization", token))
                    .andExpect(status().isForbidden());
        }
        verify(promptConfigs, never()).getAllConfigs(any());
        verify(promptConfigs, never()).deleteConfig(anyString());
    }

    @Test
    @DisplayName("阴性对照: 平台管理员照常读到报告提示词配置")
    void platformAdminReadsReportPrompts() throws Exception {
        mvc.perform(get("/api/platform/report-prompts")
                        .header("Authorization", bearer(jwt.generatePlatformToken(62L, "platform", "platform_admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        verify(promptConfigs).getAllConfigs(any());
    }

    @Test
    @DisplayName("类级 @RequireRole 不依赖路径前缀: 只跑角色拦截器, 工厂总监仍被挡、平台管理员放行")
    void reportPromptsRoleGateHoldsWithoutThePathGate() throws Exception {
        MockMvc roleOnly = MockMvcBuilders.standaloneSetup(new AIReportPromptConfigController(promptConfigs, jwt))
                .addInterceptors(new RequireRoleInterceptor(new com.fasterxml.jackson.databind.ObjectMapper()))
                .build();
        roleOnly.perform(get("/api/platform/report-prompts").requestAttr("role", "factory_super_admin"))
                .andExpect(status().isForbidden());
        verify(promptConfigs, never()).getAllConfigs(any());
        roleOnly.perform(get("/api/platform/report-prompts").requestAttr("role", "platform_admin"))
                .andExpect(status().isOk());
        verify(promptConfigs).getAllConfigs(any());
    }

    // ------------------------------------------- 3. listAllRunning 的唯一调用方 (已由 @RequireRole 守)

    @Test
    @DisplayName("admin-running: 调度被挡, 工厂总监放行 (listAllRunning 只有这一个入口)")
    void adminRunningIsSuperAdminOnly() throws Exception {
        String endpoint = "/api/mobile/F006/workflow/instances/admin-running";
        mvc.perform(get(endpoint).header("Authorization", bearer(jwt.generateToken(100L, "F006", "dispatcher", "dispatcher"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verify(workflowEngine, never()).listAllRunning(anyString(), any());

        clearInvocations(workflowEngine);
        mvc.perform(get(endpoint).header("Authorization",
                        bearer(jwt.generateToken(102L, "F006", "factory_super_admin", "factory_super_admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        verify(workflowEngine).listAllRunning(anyString(), any());
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
