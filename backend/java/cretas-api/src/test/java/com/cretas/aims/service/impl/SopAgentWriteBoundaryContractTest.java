package com.cretas.aims.service.impl;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.smartbi.AiAgentRule;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.smartbi.AiAgentRuleRepository;
import com.cretas.aims.service.execution.ToolPermissionChain;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SopAgentWriteBoundaryContractTest {
    private final SopAgentOrchestratorImpl service = new SopAgentOrchestratorImpl();
    private final AiAgentRuleRepository rules = mock(AiAgentRuleRepository.class);
    private final ToolRegistry registry = mock(ToolRegistry.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ToolPermissionChain permissions = mock(ToolPermissionChain.class);
    private final ToolExecutor executor = mock(ToolExecutor.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Object> context = new HashMap<>();

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "ruleRepository", rules);
        ReflectionTestUtils.setField(service, "toolRegistry", registry);
        ReflectionTestUtils.setField(service, "objectMapper", mapper);
        ReflectionTestUtils.setField(service, "userRepository", users);
        ReflectionTestUtils.setField(service, "toolPermissionChain", permissions);
        ReflectionTestUtils.setField(service, "writeGuardService", new WriteGuardService());
        User user = new User();
        user.setId(1L);
        user.setFactoryId("F006");
        user.setRoleCode("factory_super_admin");
        user.setIsActive(true);
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(permissions.denyReason(any(), anyString(), anyString(), isNull(), anyMap()))
                .thenReturn(Optional.empty());
        context.put("factoryId", "F006");
        context.put("userId", 1L);
    }

    private Map<String, Object> run(String name, ToolExecutor.AccessMode mode, String... followingTools) throws Exception {
        when(executor.getToolName()).thenReturn(name);
        when(executor.getAccessMode()).thenReturn(mode);
        when(executor.getActionType()).thenReturn(ToolExecutor.ActionType.READ);
        when(registry.getExecutor(name)).thenReturn(Optional.of(executor));
        AiAgentRule rule = new AiAgentRule();
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(Map.of("toolName", name, "paramsMapping", Map.of(), "outputKey", "result"));
        for (String following : followingTools) {
            steps.add(Map.of("toolName", following, "paramsMapping", Map.of(), "outputKey", "nextResult"));
        }
        rule.setToolChainConfig(mapper.writeValueAsString(Map.of("tools", steps)));
        when(rules.findById("rule")).thenReturn(Optional.of(rule));
        return service.executeRuleToolChain("rule", context);
    }

    @Test
    void arbitraryDeclaredWriteNeverReachesExecutor() throws Exception {
        assertThat(run("business_operation", ToolExecutor.AccessMode.WRITE).get("success")).isEqualTo(false);
        verify(executor, never()).execute(any(), anyMap());
    }

    @Test
    void misleadingReadDeclarationCannotBypassCentralWriteHeuristic() throws Exception {
        assertThat(run("inventory_adjust", ToolExecutor.AccessMode.READ).get("success")).isEqualTo(false);
        verify(executor, never()).execute(any(), anyMap());
    }

    @Test
    void undeclaredAccessModeFailsClosed() throws Exception {
        assertThat(run("new_business_tool", null).get("success")).isEqualTo(false);
        verify(executor, never()).execute(any(), anyMap());
    }

    @Test
    void requestConfirmationFlagsDoNotAuthorizeAnAutomaticChainWrite() throws Exception {
        context.put("forceExecute", true);
        context.put("confirmed", true);
        context.put("cretas.internal.confirmation.authority", "true");
        assertThat(run("inventory_adjust", ToolExecutor.AccessMode.WRITE).get("success")).isEqualTo(false);
        verify(executor, never()).execute(any(), anyMap());
    }

    @Test
    void authorizedReadStillExecutes() throws Exception {
        when(executor.execute(any(ToolCall.class), anyMap())).thenReturn("{\"success\":true}");
        assertThat(run("sop_parse_document", ToolExecutor.AccessMode.READ).get("success")).isEqualTo(true);
        verify(executor).execute(any(ToolCall.class), same(context));
    }

    @Test
    void explicitComplexityMetadataExceptionStillRequiresRealPermissions() throws Exception {
        when(permissions.denyReason(any(), anyString(), anyString(), isNull(), anyMap()))
                .thenReturn(Optional.of("permission revoked"));
        assertThat(run("sku_update_complexity", ToolExecutor.AccessMode.WRITE).get("success")).isEqualTo(false);
        verify(executor, never()).execute(any(), anyMap());
    }

    @Test
    void authorizedComplexityMetadataWriteRemainsAvailable() throws Exception {
        when(executor.execute(any(ToolCall.class), anyMap())).thenReturn("{\"success\":true}");
        assertThat(run("sku_update_complexity", ToolExecutor.AccessMode.WRITE).get("success")).isEqualTo(true);
        verify(executor).execute(any(ToolCall.class), same(context));
    }

    @Test
    void missingClassifierBlocksEvenTheMetadataException() throws Exception {
        ReflectionTestUtils.setField(service, "writeGuardService", null);
        assertThat(run("sku_update_complexity", ToolExecutor.AccessMode.WRITE).get("success")).isEqualTo(false);
        verify(executor, never()).execute(any(), anyMap());
    }

    @Test
    void failedReadStepStopsBeforeAnyFollowingWriteAndNeverReportsWholeChainSuccess() throws Exception {
        when(executor.execute(any(ToolCall.class), anyMap())).thenReturn("{\"success\":false,\"message\":\"parse rejected\"}");
        Map<String, Object> result = run("sop_parse_document", ToolExecutor.AccessMode.READ, "sku_update_complexity");
        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("failedStep")).isEqualTo("sop_parse_document");
        assertThat(result).containsKey("lastResult");
        verify(registry, never()).getExecutor("sku_update_complexity");
    }

    @Test
    void wrappedBusinessFailureCannotBecomeSuccessfulChain() throws Exception {
        when(executor.execute(any(ToolCall.class), anyMap()))
                .thenReturn("{\"success\":true,\"data\":{\"success\":false,\"message\":\"not applied\"}}");
        Map<String, Object> result = run("sku_update_complexity", ToolExecutor.AccessMode.WRITE);
        assertThat(result).containsEntry("success", false).containsEntry("outcome", "UNKNOWN")
                .containsEntry("retryable", false).containsKey("lastResult");
    }

    @Test
    void metadataWriteExceptionIsUnknownAndNeverRepeated() throws Exception {
        when(executor.execute(any(ToolCall.class), anyMap())).thenThrow(new IllegalStateException("after possible write"));
        Map<String, Object> result = run("sku_update_complexity", ToolExecutor.AccessMode.WRITE);
        assertThat(result).containsEntry("success", false).containsEntry("outcome", "UNKNOWN")
                .containsEntry("retryable", false);
        assertThat(String.valueOf(result.get("error"))).contains("勿重复执行");
        verify(executor, times(1)).execute(any(ToolCall.class), anyMap());
    }

    @Test
    void realBusinessToolSwallowingAnExceptionAfterItsEffectStillProducesUnknown() throws Exception {
        AtomicInteger effects = new AtomicInteger();
        AbstractBusinessTool realTool = new AbstractBusinessTool() {
            @Override public String getToolName() { return "sku_update_complexity"; }
            @Override public String getDescription() { return "Test SOP metadata updater"; }
            @Override public Map<String, Object> getParametersSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }
            @Override protected List<String> getRequiredParameters() { return List.of(); }
            @Override protected Map<String, Object> doExecute(String factory, Map<String, Object> params,
                                                             Map<String, Object> ctx) {
                effects.incrementAndGet();
                throw new IllegalStateException("after simulated metadata commit");
            }
        };
        ReflectionTestUtils.setField(realTool, "objectMapper", mapper);
        when(registry.getExecutor("sku_update_complexity")).thenReturn(Optional.of(realTool));
        AiAgentRule rule = new AiAgentRule();
        rule.setToolChainConfig("{\"tools\":[{\"toolName\":\"sku_update_complexity\",\"paramsMapping\":{}}]}");
        when(rules.findById("rule")).thenReturn(Optional.of(rule));
        Map<String, Object> result = service.executeRuleToolChain("rule", context);
        assertThat(effects.get()).isEqualTo(1);
        assertThat(result).containsEntry("success", false).containsEntry("outcome", "UNKNOWN")
                .containsEntry("retryable", false).containsKey("lastResult");
        assertThat(String.valueOf(result.get("error"))).contains("勿重复执行");
    }
}
