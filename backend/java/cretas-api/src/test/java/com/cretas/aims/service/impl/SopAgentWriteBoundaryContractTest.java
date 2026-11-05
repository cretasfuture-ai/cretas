package com.cretas.aims.service.impl;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    private Map<String, Object> run(String name, ToolExecutor.AccessMode mode) throws Exception {
        when(executor.getToolName()).thenReturn(name);
        when(executor.getAccessMode()).thenReturn(mode);
        when(executor.getActionType()).thenReturn(ToolExecutor.ActionType.READ);
        when(registry.getExecutor(name)).thenReturn(Optional.of(executor));
        AiAgentRule rule = new AiAgentRule();
        rule.setToolChainConfig(mapper.writeValueAsString(Map.of("tools", List.of(
                Map.of("toolName", name, "paramsMapping", Map.of(), "outputKey", "result")))));
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
}
