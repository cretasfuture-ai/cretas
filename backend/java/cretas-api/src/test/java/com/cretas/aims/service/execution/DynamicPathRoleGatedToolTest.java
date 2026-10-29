package com.cretas.aims.service.execution;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.impl.system.OperationLogQueryTool;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.repository.ToolEmbeddingRepository;
import com.cretas.aims.service.ToolRouterService;
import com.cretas.aims.service.impl.LlmIntentFallbackClientImpl;
import com.cretas.aims.service.impl.ToolRouterServiceImpl;
import com.cretas.aims.service.intent.FrontlineAiWriteGrantService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S6 re-review B1 (2026-10-29): role-gated tools (here the real {@code operation_log_query}, admin roles only)
 * declare no permission codes, so RBAC alone ({@code toolRbacEnforcer.isAllowed}) lets every role through.
 * The three dynamic-selection paths must ask the full {@link ToolPermissionChain}:
 * the auto-planner (refusing the whole plan, no step runs), the dynamic tool chain and the LLM tool-calling fallback.
 */
@DisplayName("动态选工具三条路径: 角色门工具与直接执行同一条权限链 (自动计划整单拒绝)")
class DynamicPathRoleGatedToolTest {

    private static final String FACTORY = "F006";
    private static final long USER = 42L;

    private ToolPermissionChain chain;
    private OperationLogQueryTool roleGated;

    @BeforeEach
    void setUp() throws Exception {
        // Real chain: frontline gate, legacy role gate, RBAC. Neither tool here declares codes, so RBAC allows
        // and only the role gate can refuse: exactly the layer isAllowed() never consulted.
        chain = new ToolPermissionChain(new ToolRbacEnforcer(), new FrontlineAiWriteGrantService(
                mock(com.cretas.aims.repository.config.AiFrontlineIntentGrantRepository.class),
                new WriteGuardService(),
                mock(com.cretas.aims.service.intent.IntentConfigManagementService.class),
                mock(ToolRegistry.class)));
        roleGated = spy(new OperationLogQueryTool());
        doReturn("{\"success\":true}").when(roleGated).execute(any(), any());
        assertThat(roleGated.requiresPermission()).isTrue();
        assertThat(roleGated.getRequiredPermissions()).isEmpty();
        assertThat(roleGated.hasPermission("production_manager")).isFalse();
    }

    // ---------- dynamic tool chain (ToolRouterServiceImpl) ----------

    @Test
    void toolChainRefusesARoleGatedToolTheRoleMayNotUseAndNeverRunsIt() throws Exception {
        ToolRouterServiceImpl router = router(roleGated);
        Throwable thrown = invokeSingle(router, context("production_manager"));
        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(thrown.getMessage()).contains("没有权限");
        verify(roleGated, never()).execute(any(), any());
    }

    @Test
    void toolChainRunsTheToolForARoleItAdmits() throws Exception {
        ToolRouterServiceImpl router = router(roleGated);
        assertThat(invokeSingle(router, context("factory_super_admin"))).isNull();
        verify(roleGated).execute(any(), any());
    }

    @Test
    void toolChainRefusesWhenThePermissionChainIsMissing() throws Exception {
        ToolRouterServiceImpl router = router(roleGated);
        ReflectionTestUtils.setField(router, "toolPermissionChain", null);
        assertThat(invokeSingle(router, context("factory_super_admin"))).isInstanceOf(IllegalStateException.class);
        verify(roleGated, never()).execute(any(), any());
    }

    // ---------- auto-planner (DynamicToolSelectionService) ----------

    @Test
    void autoPlanWithOneDeniedStepIsRefusedWholeAndNoStepRuns() throws Exception {
        ToolExecutor open = mock(ToolExecutor.class);
        when(open.getToolName()).thenReturn("production_summary");
        when(open.getRequiredPermissions()).thenReturn(java.util.Set.of());
        when(open.getActionType()).thenReturn(ToolExecutor.ActionType.READ);
        when(open.getAccessMode()).thenReturn(ToolExecutor.AccessMode.READ);
        when(open.execute(any(), any())).thenReturn("{\"ok\":true}");

        DynamicToolSelectionService service = autoPlanService(open);
        IntentExecuteResponse denied = service.executeWithDynamicToolSelection(
                FACTORY, request(), intent(), null, USER, "production_manager");

        assertThat(denied.getStatus()).isEqualTo("PERMISSION_DENIED");
        assertThat(denied.getMessage()).contains("未执行任何操作").contains("operation_log_query");
        verify(open, never()).execute(any(), any());
        verify(roleGated, never()).execute(any(), any());
    }

    @Test
    void autoPlanRunsEveryStepForARoleAdmittedToAll() throws Exception {
        ToolExecutor open = mock(ToolExecutor.class);
        when(open.getToolName()).thenReturn("production_summary");
        when(open.getRequiredPermissions()).thenReturn(java.util.Set.of());
        when(open.getActionType()).thenReturn(ToolExecutor.ActionType.READ);
        when(open.getAccessMode()).thenReturn(ToolExecutor.AccessMode.READ);
        when(open.execute(any(), any())).thenReturn("{\"ok\":true}");

        DynamicToolSelectionService service = autoPlanService(open);
        IntentExecuteResponse allowed = service.executeWithDynamicToolSelection(
                FACTORY, request(), intent(), null, USER, "factory_super_admin");

        assertThat(allowed.getStatus()).isEqualTo("SUCCESS");
        verify(open).execute(any(), any());
        verify(roleGated).execute(any(), any());
    }

    // ---------- LLM tool-calling fallback (LlmIntentFallbackClientImpl) ----------

    @Test
    void llmToolCallingAsksTheFullChain() {
        LlmIntentFallbackClientImpl fallback = mock(LlmIntentFallbackClientImpl.class, Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(fallback, "toolPermissionChain", chain);
        assertThat(fallback.toolCallingDenyReason(roleGated, FACTORY, "production_manager", context("production_manager")))
                .isPresent();
        assertThat(fallback.toolCallingDenyReason(roleGated, FACTORY, "factory_super_admin", context("factory_super_admin")))
                .isEmpty();
        ReflectionTestUtils.setField(fallback, "toolPermissionChain", null);
        assertThat(fallback.toolCallingDenyReason(roleGated, FACTORY, "factory_super_admin", context("factory_super_admin")))
                .as("no chain ⇒ refuse").isPresent();
    }

    // ---------- helpers ----------

    private ToolRouterServiceImpl router(ToolExecutor tool) {
        ToolRouterServiceImpl router = new ToolRouterServiceImpl();
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getExecutor("operation_log_query")).thenReturn(Optional.of(tool));
        ReflectionTestUtils.setField(router, "toolRegistry", registry);
        ReflectionTestUtils.setField(router, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(router, "toolEmbeddingRepository", mock(ToolEmbeddingRepository.class));
        ReflectionTestUtils.setField(router, "writeGuardService", new WriteGuardService());
        ReflectionTestUtils.setField(router, "toolRbacEnforcer", new ToolRbacEnforcer());
        ReflectionTestUtils.setField(router, "toolPermissionChain", chain);
        return router;
    }

    /** @return the thrown cause, or null when the call returned normally */
    private static Throwable invokeSingle(ToolRouterServiceImpl router, Map<String, Object> context) throws Exception {
        Method m = ToolRouterServiceImpl.class.getDeclaredMethod("executeSingleTool", String.class, Map.class);
        m.setAccessible(true);
        try {
            m.invoke(router, "operation_log_query", context);
            return null;
        } catch (InvocationTargetException e) {
            return e.getCause();
        }
    }

    private DynamicToolSelectionService autoPlanService(ToolExecutor open) {
        ToolRouterService toolRouter = mock(ToolRouterService.class);
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getExecutor("production_summary")).thenReturn(Optional.of(open));
        when(registry.getExecutor("operation_log_query")).thenReturn(Optional.of(roleGated));
        DynamicToolSelectionService service = new DynamicToolSelectionService(toolRouter, registry, new ObjectMapper());
        ReflectionTestUtils.setField(service, "writeGuardService", new WriteGuardService());
        ReflectionTestUtils.setField(service, "toolRbacEnforcer", new ToolRbacEnforcer());
        ReflectionTestUtils.setField(service, "toolPermissionChain", chain);

        when(toolRouter.retrieveCandidateTools(anyString(), any(Integer.class))).thenReturn(List.of(
                ToolRouterService.ToolCandidate.builder().toolName("production_summary").toolDescription("t")
                        .toolCategory("QUERY").similarity(0.9).keywords(List.of("x")).build()));
        when(toolRouter.requiresMultiToolPlan(anyString(), anyList())).thenReturn(true);
        // The open step comes first: under the old per-step check it would already have run.
        ToolRouterService.AutoPlan plan = ToolRouterService.AutoPlan.builder()
                .steps(List.of(step("s1", "production_summary", 1), step("s2", "operation_log_query", 2)))
                .executionOrder(ToolRouterService.SelectedTools.ExecutionOrder.SEQUENTIAL)
                .confidence(0.9)
                .build();
        when(toolRouter.generateExecutionPlan(anyString(), anyList(), anyMap())).thenReturn(plan);
        return service;
    }

    private static ToolRouterService.PlanStep step(String id, String tool, int order) {
        return ToolRouterService.PlanStep.builder().stepId(id).toolName(tool)
                .params(new HashMap<>()).dependsOn(Collections.emptyList()).order(order).build();
    }

    private static Map<String, Object> context(String role) {
        Map<String, Object> c = new HashMap<>();
        TrustedExecutionContext.enforcePrincipal(c, FACTORY, USER, role);
        return c;
    }

    private static IntentExecuteRequest request() {
        return IntentExecuteRequest.builder().userInput("看看今天的操作日志和产量").build();
    }

    private static AIIntentConfig intent() {
        return AIIntentConfig.builder().intentCode("DYNAMIC").intentName("动态").intentCategory("QUERY").build();
    }
}
