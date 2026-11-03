package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.impl.report.ReportCostVarianceTool;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.controller.ReportController;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.report.CostVarianceReportDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.permission.PlatformRolePermission;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.security.PriceSensitiveContext;
import com.cretas.aims.security.PriceSensitiveSerializerModifier;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.ReportService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 成本差异: 对话 == REST (ERP-S6-MSG-TEXT-PRICE-LEAK-001 复测, #1394 CI 红在 ProcessSecretCostVarianceToolOverfetch)。
 *
 * <p>REST {@code GET /reports/cost-variance} 返回类型化 {@link CostVarianceReportDTO} (没有 @PriceSensitive 字段),
 * 门槛是类上的 {@code finance:read | finance:read_write | procurement:price:view}: 过了门槛就看得到成本。对话工具
 * {@code report_cost_variance} 同一份 DTO、同一个门槛, 但结果到响应层是 Map, Rule 2b (要 finance:read_write)
 * 把部门管理员 (finance r) 在 REST 上看得到的实际总成本剥成 null —— 对话比 App 遮得多 (#932 要求 AI = App)。
 *
 * <p>这里钉: 每个 F006 角色, REST 过不过门槛 == 对话工具过不过门槛; 过了的, 对话字段与回复文字都与 REST 一样
 * 看得到实际总成本; 没过的两边都拿不到。真实 {@link PermissionServiceImpl} (prod L1), 真实
 * {@link PriceFieldResponseAdvice}, 真实 {@link ToolDispatchService} 直调分支。
 */
class CostVarianceChatRestParityContractTest {

    private static final String F = "F006";
    /** prod L1 (platform_role_permissions) 里的 finance / procurement 级别; F006 没有这些角色的 L2 覆盖。 */
    private static final Map<String, Map<String, String>> L1 = Map.of(
            "finance_manager", Map.of("procurement", "-", "finance", "rw"),
            "department_admin", Map.of("procurement", "r", "finance", "r"),
            "procurement_manager", Map.of("procurement", "rw", "finance", "-"),
            "sales_manager", Map.of("procurement", "-", "finance", "-"),
            "warehouse_manager", Map.of("procurement", "r", "finance", "-"),
            "viewer", Map.of("procurement", "r", "finance", "-"));
    private static final List<String> ROLES = List.of("factory_super_admin", "finance_manager", "department_admin",
            "procurement_manager", "sales_manager", "warehouse_manager", "viewer");

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .registerModule(new SimpleModule("PriceSensitiveModule")
                    .setSerializerModifier(new PriceSensitiveSerializerModifier()));
    private final UserRepository users = mock(UserRepository.class);
    private final Map<String, Long> idOf = new HashMap<>();
    private PermissionServiceImpl permissions;
    private PriceFieldResponseAdvice advice;
    private ToolDispatchService dispatch;
    private ReportCostVarianceTool tool;

    @BeforeEach
    void setUp() {
        PlatformRolePermissionRepository l1 = mock(PlatformRolePermissionRepository.class);
        FactoryModuleConfigRepository l2 = mock(FactoryModuleConfigRepository.class);
        permissions = new PermissionServiceImpl();
        ReflectionTestUtils.setField(permissions, "platformRepo", l1);
        ReflectionTestUtils.setField(permissions, "factoryConfigRepo", l2);
        when(l2.findByFactoryIdAndConfigVersion(anyString(), anyInt())).thenReturn(List.of());
        when(l1.findByRoleCodeAndModuleCodeAndDeletedAtIsNull(anyString(), anyString())).thenAnswer(inv -> {
            String level = L1.getOrDefault(inv.getArgument(0), Map.of()).get(inv.getArgument(1));
            if (level == null) return Optional.empty();
            PlatformRolePermission row = new PlatformRolePermission();
            row.setRoleCode(inv.getArgument(0));
            row.setModuleCode(inv.getArgument(1));
            row.setPermissionLevel(level);
            return Optional.of(row);
        });
        long id = 300;
        for (String role : ROLES) {
            User u = new User();
            u.setId(++id);
            u.setFactoryId(F);
            u.setUsername("cv-" + role);
            u.setRoleCode(role);
            u.setIsActive(true);
            when(users.findById(id)).thenReturn(Optional.of(u));
            idOf.put(role, id);
        }

        advice = new PriceFieldResponseAdvice();
        ReflectionTestUtils.setField(advice, "permissionService", permissions);
        ReflectionTestUtils.setField(advice, "userRepository", users);
        ReflectionTestUtils.setField(advice, "objectMapper", mapper);

        ReportService reports = mock(ReportService.class);
        when(reports.getCostVarianceReport(eq(F), any(), any())).thenAnswer(inv -> report());
        com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope costScope =
                mock(com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope.class);
        when(costScope.forCurrentCaller(F)).thenReturn(com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope.Excluded.NONE);
        tool = new ReportCostVarianceTool();
        ReflectionTestUtils.setField(tool, "reportService", reports);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        ReflectionTestUtils.setField(tool, "priceView", advice);
        ReflectionTestUtils.setField(tool, "llmCostScope", costScope);

        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.isToolEnabledForFactory(anyString(), anyString())).thenReturn(true);
        dispatch = new ToolDispatchService(registry, mapper, mock(DashScopeClient.class),
                mock(ToolCallRedundancyService.class), mock(SelfCorrectionService.class),
                mock(CorrectionAgentService.class), mock(ExternalVerifierService.class),
                mock(ToolResultValidatorService.class), mock(ParameterExtractionLearningService.class));
        ToolRbacEnforcer rbac = mock(ToolRbacEnforcer.class);
        when(rbac.check(any(), anyMap())).thenReturn(ToolRbacEnforcer.Decision.allow());
        ReflectionTestUtils.setField(dispatch, "toolRbacEnforcer", rbac);
        ReflectionTestUtils.setField(dispatch, "writeGuardService", new WriteGuardService());
        ReflectionTestUtils.setField(dispatch, "priceUserRepository", users);
        ReflectionTestUtils.setField(dispatch, "pricePermissionService", permissions);
    }

    @AfterEach
    void clear() {
        PriceSensitiveContext.clear();
    }

    /** 门槛同一个: 对话工具的权限集合 == REST 控制器类上的 @RequirePermission。 */
    @Test
    void theChatToolAndTheRestEndpointHaveTheSameGate() {
        assertThat(tool.getRequiredPermissions()).containsExactlyInAnyOrderElementsOf(Set.of(restGate()));
    }

    @ParameterizedTest(name = "{0}: 对话 == REST")
    @ValueSource(strings = {"factory_super_admin", "finance_manager", "department_admin",
            "procurement_manager", "sales_manager", "warehouse_manager", "viewer"})
    void chatEqualsRestForEveryRole(String role) throws Exception {
        boolean passes = permissions.hasAnyPermission(users.findById(idOf.get(role)).orElseThrow(), restGate());
        if (!passes) {
            // REST: 类上的 @RequirePermission 403; 对话: ToolRbacEnforcer 按同一个集合拒绝 (门槛同一个, 上一个测试钉住)
            return;
        }
        BigDecimal rest = decimalOrNull(restBody(role).at("/data/totalActualCost"));
        IntentExecuteResponse chat = chat(role);
        BigDecimal chatField = decimalOrNull(writeThroughAdvice(ApiResponse.success(chat), role)
                .at("/data/resultData/data/totalActualCost"));

        assertThat(rest).as("REST 过了门槛就看得到 (role=%s)", role).isEqualByComparingTo("3442.39");
        assertThat(chatField).as("对话字段 == REST (role=%s)", role).isEqualByComparingTo("3442.39");
        assertThat(chat.getMessage()).as("回复文字 == 字段 (role=%s)", role).contains("实际总成本 ¥3442.39");
    }

    /** 缺陷本体 + 阴阳性锚点: 部门管理员 (finance r, 不是 rw) 过门槛、对话看得到; 至少一个角色两边都拿不到。 */
    @Test
    void departmentAdminSeesTheCostInChatAndSomeRoleIsRefusedOnBothPaths() throws Exception {
        User deptAdmin = users.findById(idOf.get("department_admin")).orElseThrow();
        assertThat(permissions.hasPermission(deptAdmin, PriceFieldResponseAdvice.FINANCE_READ_PERMISSION))
                .as("前提: 部门管理员没有 finance:read_write (Rule 2b 会剥他)").isFalse();
        assertThat(permissions.hasAnyPermission(deptAdmin, restGate())).isTrue();
        assertThat(decimalOrNull(writeThroughAdvice(ApiResponse.success(chat("department_admin")), "department_admin")
                .at("/data/resultData/data/totalActualCost"))).isEqualByComparingTo("3442.39");

        long refused = ROLES.stream()
                .filter(r -> !permissions.hasAnyPermission(users.findById(idOf.get(r)).orElseThrow(), restGate()))
                .count();
        assertThat(refused).as("阴性对照: 有角色过不了门槛 (REST 403 / 对话工具被拒)").isPositive();
    }

    // ── paths ──────────────────────────────────────────────────────────────

    private static String[] restGate() {
        return ReportController.class.getAnnotation(RequirePermission.class).value();
    }

    private JsonNode restBody(String role) throws Exception {
        try {
            return writeThroughAdvice(ApiResponse.success(report()), role);
        } finally {
            PriceSensitiveContext.clear();
        }
    }

    private IntentExecuteResponse chat(String role) {
        AIIntentConfig intent = AIIntentConfig.builder().intentCode("REPORT_COST_VARIANCE")
                .intentCategory("REPORT").intentName("成本差异").toolName("report_cost_variance").build();
        IntentExecuteResponse response = dispatch.executeWithTool(tool, F,
                IntentExecuteRequest.builder().userInput("成本差异").build(),
                intent, idOf.get(role), role, null);
        assertThat(response.getStatus()).as(response.getMessage()).isEqualTo("SUCCESS");
        return response;
    }

    private JsonNode writeThroughAdvice(Object body, String role) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("userId", idOf.get(role));
        Object stripped = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null,
                new ServletServerHttpRequest(req), new ServletServerHttpResponse(new MockHttpServletResponse()));
        return mapper.readTree(mapper.writeValueAsString(stripped));
    }

    private static BigDecimal decimalOrNull(JsonNode node) {
        return node.isNumber() ? node.decimalValue() : null;
    }

    private static CostVarianceReportDTO report() {
        return CostVarianceReportDTO.builder()
                .comparable(false)
                .comparabilityReason(CostVarianceReportDTO.NO_INDEPENDENT_STANDARD_REASON)
                .totalActualCost(new BigDecimal("3442.39"))
                .batchCount(1)
                .build();
    }
}
