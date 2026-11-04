package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRbacGuard;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.impl.report.SkuGrossMarginTool;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.controller.finance.FinanceReportController;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.permission.PlatformRolePermission;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「成本率分析」→ SKU_GROSS_MARGIN → report_sku_gross_margin 与 web SKU 毛利视图同一个门 (acc-s6 s610k, 组织者裁定
 * 2026-11-03): web (GET /finance/report/shipment-sku-margin) 给没有财务模块的销售 / 生产 / 采购主管 403, 对话以前按
 * procurement:price:view 放行, 金额遮了但毛利率 (70.53、各产品 28.25% …) 照给。web 拒整份, 对话就拒整个工具。
 *
 * <p>真实 {@link ToolDispatchService} 直调分支 + 真实 {@link ToolRbacEnforcer} / {@link PermissionServiceImpl} (prod L1)。
 */
class SkuGrossMarginChatGateContractTest {

    private static final String F = "F006";
    /** prod platform_role_permissions 的 finance 级别 (2026-11-03 只读核对; F006 无这些角色的 L2 覆盖)。 */
    private static final Map<String, Map<String, String>> L1 = Map.of(
            "factory_super_admin", Map.of("finance", "rw"),
            "finance_manager", Map.of("finance", "rw"),
            "department_admin", Map.of("finance", "r"),
            "sales_manager", Map.of("finance", "-"),
            "production_manager", Map.of("finance", "-"),
            "procurement_manager", Map.of("finance", "-"));

    private final UserRepository users = mock(UserRepository.class);
    private final Map<String, Long> idOf = new HashMap<>();
    private ToolRbacEnforcer enforcer;
    private ToolDispatchService dispatch;
    private final SkuGrossMarginTool tool = new SkuGrossMarginTool();

    @BeforeEach
    void setUp() {
        PlatformRolePermissionRepository l1 = mock(PlatformRolePermissionRepository.class);
        FactoryModuleConfigRepository l2 = mock(FactoryModuleConfigRepository.class);
        PermissionServiceImpl permissions = new PermissionServiceImpl();
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
        long id = 900;
        for (String role : L1.keySet()) {
            User u = new User();
            u.setId(++id);
            u.setFactoryId(F);
            u.setUsername("sku-" + role);
            u.setRoleCode(role);
            u.setIsActive(true);
            when(users.findById(id)).thenReturn(Optional.of(u));
            idOf.put(role, id);
        }
        ToolRbacGuard guard = new ToolRbacGuard();
        ReflectionTestUtils.setField(guard, "userRepository", users);
        ReflectionTestUtils.setField(guard, "permissionService", permissions);
        enforcer = new ToolRbacEnforcer();
        ReflectionTestUtils.setField(enforcer, "rbacGuard", guard);

        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.isToolEnabledForFactory(anyString(), anyString())).thenReturn(true);
        dispatch = new ToolDispatchService(registry, new ObjectMapper(), mock(DashScopeClient.class),
                mock(ToolCallRedundancyService.class), mock(SelfCorrectionService.class),
                mock(CorrectionAgentService.class), mock(ExternalVerifierService.class),
                mock(ToolResultValidatorService.class), mock(ParameterExtractionLearningService.class));
        ReflectionTestUtils.setField(dispatch, "toolRbacEnforcer", enforcer);
        ReflectionTestUtils.setField(dispatch, "writeGuardService", new WriteGuardService());
        ReflectionTestUtils.setField(dispatch, "priceUserRepository", users);
        ReflectionTestUtils.setField(dispatch, "pricePermissionService", permissions);
    }

    @Test
    void costRateQuestionRoutesToSkuGrossMargin() {
        IntentKnowledgeBase knowledge = new IntentKnowledgeBase();
        knowledge.initDefaults();
        assertThat(knowledge.matchPhrase("成本率分析", "FACTORY")).contains("SKU_GROSS_MARGIN");
    }

    @ParameterizedTest(name = "{0}: 成本率分析 → PERMISSION_DENIED (web 也是 403)")
    @ValueSource(strings = {"sales_manager", "production_manager", "procurement_manager"})
    void rolesWithoutFinanceAreRefusedTheWholeTool(String role) {
        IntentExecuteResponse response = dispatch.executeWithTool(tool, F,
                IntentExecuteRequest.builder().userInput("成本率分析").build(),
                AIIntentConfig.builder().intentCode("SKU_GROSS_MARGIN").intentCategory("REPORT")
                        .intentName("SKU 毛利率").sensitivityLevel("LOW").toolName("report_sku_gross_margin").build(),
                idOf.get(role), role, null);
        assertThat(response.getStatus()).as(response.getMessage()).isEqualTo("PERMISSION_DENIED");
        assertThat(String.valueOf(response.getResultData())).doesNotContain("marginRate");
    }

    @ParameterizedTest(name = "{0}: 照常放行")
    @ValueSource(strings = {"factory_super_admin", "finance_manager", "department_admin"})
    void financeReadersAreStillAllowed(String role) {
        assertThat(enforcer.check(tool, Map.of("userId", idOf.get(role), "userRole", role, "factoryId", F)).isAllowed())
                .as("role=%s", role).isTrue();
    }

    /** 对话工具的门 == web SKU 毛利视图的接口门 (FinanceReportController 类门, 方法上没有另设)。 */
    @Test
    void toolGateEqualsTheWebViewRestGate() throws Exception {
        RequirePermission classGate = FinanceReportController.class.getAnnotation(RequirePermission.class);
        assertThat(FinanceReportController.class.getMethod("shipmentSkuMargin", String.class, LocalDate.class,
                LocalDate.class).getAnnotation(RequirePermission.class)).as("方法上没有另设门").isNull();
        assertThat(tool.getRequiredPermissions()).containsExactlyInAnyOrder(classGate.value());
        assertThat(tool.getAlsoRequiredPermissions()).isEmpty();
    }
}
