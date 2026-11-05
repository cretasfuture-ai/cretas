package com.cretas.aims.service.execution;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.*;
import com.cretas.aims.ai.tool.gateway.*;
import com.cretas.aims.dto.ai.*;
import com.cretas.aims.entity.calibration.ToolCallRecord;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.QueryPreprocessorService;
import com.cretas.aims.service.calibration.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConfirmedWriteDispatchContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final WriteGuardService guard = new WriteGuardService();
    private final ToolExecutor tool = mock(ToolExecutor.class);
    private final ToolRegistry registry = mock(ToolRegistry.class);
    private final ToolCallRedundancyService redundancy = mock(ToolCallRedundancyService.class);
    private final ParameterExtractionLearningService extraction = mock(ParameterExtractionLearningService.class);
    private final QueryPreprocessorService preprocessor = mock(QueryPreprocessorService.class);
    private ToolDispatchService dispatch;
    private AIIntentConfig intent;

    @BeforeEach
    void setUp() throws Exception {
        dispatch = new ToolDispatchService(registry, mapper, null, redundancy,
                mock(SelfCorrectionService.class), mock(CorrectionAgentService.class),
                mock(ExternalVerifierService.class), mock(ToolResultValidatorService.class), extraction);
        ReflectionTestUtils.setField(dispatch, "writeGuardService", guard);
        ToolRbacEnforcer rbac = mock(ToolRbacEnforcer.class);
        when(rbac.check(any(), anyMap())).thenReturn(ToolRbacEnforcer.Decision.allow());
        ReflectionTestUtils.setField(dispatch, "toolRbacEnforcer", rbac);
        ReflectionTestUtils.setField(dispatch, "queryPreprocessorService", preprocessor);
        when(tool.getToolName()).thenReturn("material_consume");
        when(tool.getAccessMode()).thenReturn(ToolExecutor.AccessMode.WRITE);
        when(tool.getParametersSchema()).thenReturn(Map.of("type", "object",
                "properties", Map.of(), "required", List.of("quantity")));
        when(registry.isToolEnabledForFactory("F006", "material_consume")).thenReturn(true);
        when(tool.execute(any(), anyMap())).thenReturn("{\"success\":true,\"message\":\"已领料\"}");
        when(redundancy.recordToolCall(any())).thenReturn(ToolCallRecord.builder().id(7L).build());
        intent = AIIntentConfig.builder().intentCode("MATERIAL_CONSUME")
                .intentName("领料").toolName("material_consume").build();
    }

    @Test
    void twoIndependentConfirmedWritesExecuteTwiceEvenWhenReadCacheContainsSuccess() throws Exception {
        when(redundancy.isRedundant(anyString(), anyString(), anyMap())).thenReturn(true);
        when(redundancy.getCachedResult(anyString(), anyString(), anyMap()))
                .thenReturn(Optional.of("{\"success\":true,\"message\":\"旧领料结果\"}"));

        assertThat(execute().getStatus()).isEqualTo("SUCCESS");
        assertThat(execute().getStatus()).isEqualTo("SUCCESS");

        verify(tool, times(2)).execute(any(), anyMap());
        verify(redundancy, never()).isRedundant(anyString(), anyString(), anyMap());
        verify(redundancy, never()).getCachedResult(anyString(), anyString(), anyMap());
        verify(redundancy, never()).cacheResult(anyString(), anyString(), anyMap(), anyString(), any());
        ArgumentCaptor<ToolCallRecord> record = ArgumentCaptor.forClass(ToolCallRecord.class);
        verify(redundancy, times(2)).recordToolCall(record.capture());
        assertThat(record.getAllValues()).allSatisfy(r -> {
            assertThat(r.getUserId()).isEqualTo(42L);
            assertThat(r.getFactoryId()).isEqualTo("F006");
        });
    }

    @Test
    void claimedCommandDoesNotResolveReferencesOrExtractMissingParameters() throws Exception {
        execute();
        ArgumentCaptor<ToolCall> call = ArgumentCaptor.forClass(ToolCall.class);
        verify(tool).execute(call.capture(), anyMap());
        var parameters = mapper.readTree(call.getValue().getFunction().getArguments());
        assertThat(parameters.path("quantity").asInt())
                .isEqualTo(30);
        assertThat(parameters.path("userInput").asText()).isEqualTo("领用这批原料30公斤");
        assertThat(parameters.toString()).doesNotContain("确认执行:");
        verifyNoInteractions(preprocessor, extraction);
    }

    @Test
    void declaredReadStillUsesReadCache() throws Exception {
        when(tool.getToolName()).thenReturn("material_list");
        when(tool.getAccessMode()).thenReturn(ToolExecutor.AccessMode.READ);
        when(registry.isToolEnabledForFactory("F006", "material_list")).thenReturn(true);
        intent.setIntentCode("MATERIAL_LIST");
        when(redundancy.isRedundant(anyString(), anyString(), anyMap())).thenReturn(true);
        when(redundancy.getCachedResult(anyString(), anyString(), anyMap()))
                .thenReturn(Optional.of("{\"success\":true,\"message\":\"库存查询\"}"));
        assertThat(execute().getMetadata()).containsEntry("cached", true);
        verify(tool, never()).execute(any(), anyMap());
    }

    @Test
    void writeFailureAfterInvocationIsUnknownAndNeverRetriedOrCached() throws Exception {
        when(tool.execute(any(), anyMap())).thenThrow(new IllegalStateException("connection lost after commit"));
        IntentExecuteResponse response = execute();
        assertThat(response.getStatus()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(response.getMetadata()).containsEntry("retryable", false);
        assertThat(response.getMessage()).contains("核对", "勿重复提交");
        verify(tool).execute(any(), anyMap());
        verifyNoInteractions(redundancy);
    }

    @Test
    void governedWriteCannotBypassGatewayEvenWithServerConfirmationAndMigrationDisabled() throws Exception {
        ToolRuntimeRegistry runtime = mock(ToolRuntimeRegistry.class);
        when(runtime.isGoverned("material_consume")).thenReturn(true);
        when(tool.getVersion()).thenReturn("1.0.0");
        ReflectionTestUtils.setField(dispatch, "toolRuntimeRegistry", runtime);
        ToolExecutionGateway gateway = mock(ToolExecutionGateway.class);
        ReflectionTestUtils.setField(dispatch, "toolExecutionGateway", gateway);
        AuthenticatedToolPrincipalFactory principal = mock(AuthenticatedToolPrincipalFactory.class);
        when(principal.create("F006", 42L, "factory_super_admin"))
                .thenReturn(new ExecutionPrincipal("F006", "FACTORY", "42", PrincipalType.USER,
                        java.util.Set.of("factory_super_admin"), java.util.Set.of(), java.util.Set.of()));
        ReflectionTestUtils.setField(dispatch, "authenticatedToolPrincipalFactory", principal);
        when(gateway.execute(any())).thenAnswer(i -> {
            ToolExecutionCommand c = i.getArgument(0);
            return new ToolExecutionResult(c.requestId(), c.toolName(), c.expectedDescriptorVersion(),
                    "audit-denied", c.traceId(), ToolExecutionStatus.DENIED, mapper.createObjectNode(),
                    "缺少受治理写操作的绑定确认凭证，未执行", false);
        });
        assertThat(execute().getStatus()).isEqualTo("DENIED");
        verify(gateway).execute(any());
        verify(tool, never()).execute(any(), anyMap());
        verifyNoInteractions(redundancy);
    }

    @Test
    void unavailableGovernanceClassificationRefusesBeforeLegacyExecutionOrCache() throws Exception {
        ToolRuntimeRegistry runtime = mock(ToolRuntimeRegistry.class);
        when(runtime.isGoverned(anyString())).thenThrow(new IllegalStateException("policy unavailable"));
        ReflectionTestUtils.setField(dispatch, "toolRuntimeRegistry", runtime);
        assertThat(execute().getStatus()).isEqualTo("DENIED");
        verify(tool, never()).execute(any(), anyMap());
        verifyNoInteractions(redundancy);
    }

    private IntentExecuteResponse execute() {
        return dispatch.executeWithTool(tool, "F006", IntentExecuteRequest.builder()
                .userInput("确认执行: MATERIAL_CONSUME")
                .context(guard.withServerConfirmation(Map.of("quantity", 30, "userInput", "领用这批原料30公斤")))
                .build(), intent, 42L, "factory_super_admin", null);
    }
}
