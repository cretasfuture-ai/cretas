package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRbacGuard;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.impl.report.ReportCostQueryTool;
import com.cretas.aims.ai.tool.impl.report.ReportCostVarianceTool;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.controller.ReportController;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.report.CostVarianceReportDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.permission.PlatformRolePermission;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.ReportService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 咨询页 (mode=READ) 里的成本差异问题: 走 REPORT_COST_VARIANCE → report_cost_variance (READ), 不被写闸拦;
 * 真写工具 (report_cost_query 会生成并保存 AI 成本分析报告) 照旧拦; 门槛与 GET /reports/cost-variance 同一组,
 * 门槛外的角色被拒 (= REST 403)。真实 {@link ToolDispatchService} 直调分支 + 真实 {@link WriteGuardService}
 * + 真实 {@link ToolRbacEnforcer} / {@link PermissionServiceImpl} (prod L1)。
 */
class ReportCostVarianceReadModeContractTest {

    private static final String F = "F006";
    /** prod platform_role_permissions 的 finance / procurement 级别 (F006 没有这些角色的 L2 覆盖)。 */
    private static final Map<String, Map<String, String>> L1 = Map.of(
            "factory_super_admin", Map.of("finance", "rw", "procurement", "rw"),
            "finance_manager", Map.of("finance", "rw", "procurement", "-"),
            "department_admin", Map.of("finance", "r", "procurement", "r"),
            "warehouse_manager", Map.of("finance", "-", "procurement", "r"),
            "viewer", Map.of("finance", "-", "procurement", "r"));

    private final UserRepository users = mock(UserRepository.class);
    private final Map<String, Long> idOf = new HashMap<>();
    private PermissionServiceImpl permissions;
    private ToolDispatchService dispatch;
    private ReportCostVarianceTool varianceTool;

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
        long id = 700;
        for (String role : L1.keySet()) {
            User u = new User();
            u.setId(++id);
            u.setFactoryId(F);
            u.setUsername("cvr-" + role);
            u.setRoleCode(role);
            u.setIsActive(true);
            when(users.findById(id)).thenReturn(Optional.of(u));
            idOf.put(role, id);
        }

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ReportService reports = mock(ReportService.class);
        when(reports.getCostVarianceReport(eq(F), any(), any())).thenReturn(CostVarianceReportDTO.builder()
                .comparable(false).comparabilityReason(CostVarianceReportDTO.NO_INDEPENDENT_STANDARD_REASON)
                .totalActualCost(new BigDecimal("3442.39")).batchCount(1).build());
        ProcessSecretLlmCostScope costScope = mock(ProcessSecretLlmCostScope.class);
        when(costScope.forCurrentCaller(F)).thenReturn(ProcessSecretLlmCostScope.Excluded.NONE);
        varianceTool = new ReportCostVarianceTool();
        ReflectionTestUtils.setField(varianceTool, "reportService", reports);
        ReflectionTestUtils.setField(varianceTool, "llmCostScope", costScope);
        ReflectionTestUtils.setField(varianceTool, "objectMapper", mapper);

        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.isToolEnabledForFactory(anyString(), anyString())).thenReturn(true);
        dispatch = new ToolDispatchService(registry, mapper, mock(DashScopeClient.class),
                mock(ToolCallRedundancyService.class), mock(SelfCorrectionService.class),
                mock(CorrectionAgentService.class), mock(ExternalVerifierService.class),
                mock(ToolResultValidatorService.class), mock(ParameterExtractionLearningService.class));
        ToolRbacGuard guard = new ToolRbacGuard();
        ReflectionTestUtils.setField(guard, "userRepository", users);
        ReflectionTestUtils.setField(guard, "permissionService", permissions);
        ToolRbacEnforcer enforcer = new ToolRbacEnforcer();
        ReflectionTestUtils.setField(enforcer, "rbacGuard", guard);
        ReflectionTestUtils.setField(dispatch, "toolRbacEnforcer", enforcer);
        ReflectionTestUtils.setField(dispatch, "writeGuardService", new WriteGuardService());
        ReflectionTestUtils.setField(dispatch, "priceUserRepository", users);
        ReflectionTestUtils.setField(dispatch, "pricePermissionService", permissions);
    }

    @ParameterizedTest(name = "{0}: 咨询页里成本差异能查")
    @ValueSource(strings = {"factory_super_admin", "finance_manager", "department_admin"})
    void readModeCostVarianceIsNotBlocked(String role) {
        IntentExecuteResponse response = run(varianceTool, intent("REPORT_COST_VARIANCE", "report_cost_variance"), role);
        assertThat(response.getStatus()).as(response.getMessage()).isEqualTo("SUCCESS");
        assertThat(response.getStatus()).isNotEqualTo("READ_MODE_WRITE_BLOCKED");
    }

    /** 阴性对照: 真写工具 (生成并保存 AI 成本分析报告) 在咨询页照旧被写闸拦下。 */
    @Test
    void aRealWriteIntentIsStillBlockedInReadMode() {
        IntentExecuteResponse response = run(new ReportCostQueryTool(), intent("COST_QUERY", "report_cost_query"),
                "factory_super_admin");
        assertThat(response.getStatus()).isEqualTo("READ_MODE_WRITE_BLOCKED");
    }

    /** 门槛外的角色: 对话被拒 (PERMISSION_DENIED), 与 REST GET /reports/cost-variance 的 403 同一组权限。 */
    @ParameterizedTest(name = "{0}: 门槛外 → 拒绝, 与 REST 一致")
    @ValueSource(strings = {"warehouse_manager", "viewer"})
    void aRoleOutsideTheGateIsRefusedLikeRest(String role) {
        User user = users.findById(idOf.get(role)).orElseThrow();
        assertThat(permissions.hasAnyPermission(user, ReportController.class.getAnnotation(RequirePermission.class).value()))
                .as("REST 门槛 (=403)").isFalse();
        IntentExecuteResponse response = run(varianceTool, intent("REPORT_COST_VARIANCE", "report_cost_variance"), role);
        assertThat(response.getStatus()).isEqualTo("PERMISSION_DENIED");
    }

    private IntentExecuteResponse run(com.cretas.aims.ai.tool.ToolExecutor tool, AIIntentConfig intent, String role) {
        IntentExecuteRequest request = IntentExecuteRequest.builder().userInput("成本差异").build();
        request.setMode("READ");
        return dispatch.executeWithTool(tool, F, request, intent, idOf.get(role), role, null);
    }

    private static AIIntentConfig intent(String code, String toolName) {
        return AIIntentConfig.builder().intentCode(code).intentCategory("REPORT").intentName(code)
                .sensitivityLevel("LOW").toolName(toolName).build();
    }
}
