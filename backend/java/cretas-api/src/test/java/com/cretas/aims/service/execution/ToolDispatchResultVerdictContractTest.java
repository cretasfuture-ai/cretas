package com.cretas.aims.service.execution;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.calibration.ToolCallRecord;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * A normally returned tool payload is a success only when it carries an
 * explicit Boolean true verdict.  Every other verdict must remain a failed
 * dispatch in the public response, audit record, and cache path.
 */
class ToolDispatchResultVerdictContractTest {

    private static final String SUCCESS_RESULT = "{\"success\":true,\"data\":{\"source\":\"REAL_READ\"}}";
    private static final String FACTORY_ID = "F006";
    private static final String TOOL_NAME = "result_verdict_read";

    @ParameterizedTest(name = "live malformed or negative result is failed: {0}")
    @MethodSource("negativeResults")
    void liveResultRequiresExplicitBooleanTrue(String label, String result) {
        DispatchFixture fixture = newDispatch(false, Optional.empty());
        ScriptedReadTool tool = spy(new ScriptedReadTool(result));

        IntentExecuteResponse response = execute(fixture.dispatch, tool, "live " + label);

        assertThat(response.getStatus()).isEqualTo("FAILED");
        assertThat(fixture.records).singleElement()
                .extracting(ToolCallRecord::getExecutionStatus)
                .isEqualTo(ToolCallRecord.ExecutionStatus.FAILED);
        verify(tool, times(1)).execute(any(), anyMap());
        verify(fixture.redundancy, never()).cacheResult(anyString(), anyString(), anyMap(), anyString(), any());
        verify(fixture.validator, never()).validate(anyString(), anyString(), anyMap(), anyString());
        verify(fixture.externalVerifier, never()).verifyToolCall(anyString(), anyString(), anyMap(), anyString());
        verify(fixture.correctionAgent, never()).analyzeAndCorrect(
                anyString(), anyString(), anyMap(), anyString(), any(), anyInt());
    }

    @ParameterizedTest(name = "cached malformed or negative result remains failed: {0}")
    @MethodSource("cachedNegativeResults")
    void cachedMalformedOrNegativeResultCannotBecomeSuccessOrExecuteLive(String label, String cachedResult) {
        DispatchFixture fixture = newDispatch(true, Optional.ofNullable(cachedResult));
        ScriptedReadTool tool = spy(new ScriptedReadTool(SUCCESS_RESULT));

        IntentExecuteResponse response = execute(fixture.dispatch, tool, "cached " + label);

        assertThat(response.getStatus()).isEqualTo("FAILED");
        assertThat(response.getMetadata()).containsEntry("cached", true)
                .containsEntry("toolName", TOOL_NAME);
        assertThat(fixture.records).isEmpty();
        verify(tool, never()).execute(any(), anyMap());
        verify(fixture.redundancy, never()).recordToolCall(any(ToolCallRecord.class));
        verify(fixture.redundancy, never()).cacheResult(anyString(), anyString(), anyMap(), anyString(), any());
    }

    @Test
    void liveExplicitBooleanTrueIsSuccessAuditedAndCached() {
        DispatchFixture fixture = newDispatch(false, Optional.empty());
        ScriptedReadTool tool = spy(new ScriptedReadTool(SUCCESS_RESULT));

        IntentExecuteResponse response = execute(fixture.dispatch, tool, "live success");

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(fixture.records).singleElement()
                .extracting(ToolCallRecord::getExecutionStatus)
                .isEqualTo(ToolCallRecord.ExecutionStatus.SUCCESS);
        verify(tool, times(1)).execute(any(), anyMap());
        verify(fixture.redundancy).cacheResult(anyString(), anyString(), anyMap(), anyString(), any());
    }

    @Test
    void cachedExplicitBooleanTrueRemainsSuccessfulWithoutLiveExecution() {
        DispatchFixture fixture = newDispatch(true, Optional.of(SUCCESS_RESULT));
        ScriptedReadTool tool = spy(new ScriptedReadTool("{\"success\":false}"));

        IntentExecuteResponse response = execute(fixture.dispatch, tool, "cached success");

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getMetadata()).containsEntry("cached", true)
                .containsEntry("toolName", TOOL_NAME);
        assertThat(fixture.records).isEmpty();
        verify(tool, never()).execute(any(), anyMap());
        verify(fixture.redundancy, never()).recordToolCall(any(ToolCallRecord.class));
        verify(fixture.redundancy, never()).cacheResult(anyString(), anyString(), anyMap(), anyString(), any());
    }

    private static Stream<Arguments> negativeResults() {
        return Stream.of(
                Arguments.of("missing success", "{}"),
                Arguments.of("null return", null),
                Arguments.of("JSON null", "null"),
                Arguments.of("malformed", "{"),
                Arguments.of("scalar", "\"scalar\""),
                Arguments.of("array", "[]"),
                Arguments.of("success null", "{\"success\":null}"),
                Arguments.of("success string true", "{\"success\":\"true\"}"),
                Arguments.of("success string false", "{\"success\":\"false\"}"),
                Arguments.of("success numeric one", "{\"success\":1}"),
                Arguments.of("explicit false", "{\"success\":false,\"error\":\"REAL_FAILURE\"}"));
    }

    private static Stream<Arguments> cachedNegativeResults() {
        return Stream.of(
                Arguments.of("malformed", "{"),
                Arguments.of("explicit false", "{\"success\":false,\"error\":\"REAL_FAILURE\"}"));
    }

    private IntentExecuteResponse execute(ToolDispatchService dispatch, ToolExecutor tool, String input) {
        return dispatch.executeWithTool(tool, FACTORY_ID,
                IntentExecuteRequest.builder().userInput(input).context(new HashMap<>()).build(),
                intent(), 42L, "procurement_manager", null);
    }

    private AIIntentConfig intent() {
        return AIIntentConfig.builder().intentCode("RESULT_VERDICT_READ")
                .intentName("结果判定读取").intentCategory("TEST").toolName(TOOL_NAME).build();
    }

    private DispatchFixture newDispatch(boolean redundant, Optional<String> cached) {
        ToolCallRedundancyService redundancy = mock(ToolCallRedundancyService.class);
        org.mockito.Mockito.when(redundancy.isRedundant(anyString(), anyString(), anyMap())).thenReturn(redundant);
        org.mockito.Mockito.when(redundancy.getCachedResult(anyString(), anyString(), anyMap())).thenReturn(cached);
        org.mockito.Mockito.when(redundancy.computeParametersHash(anyMap())).thenReturn("hash");
        List<ToolCallRecord> records = new ArrayList<>();
        org.mockito.Mockito.when(redundancy.recordToolCall(any(ToolCallRecord.class))).thenAnswer(invocation -> {
            ToolCallRecord record = invocation.getArgument(0);
            record.setId(1L + records.size());
            records.add(record);
            return record;
        });

        ToolRegistry registry = mock(ToolRegistry.class);
        org.mockito.Mockito.when(registry.isToolEnabledForFactory(FACTORY_ID, TOOL_NAME)).thenReturn(true);
        ToolResultValidatorService validator = mock(ToolResultValidatorService.class);
        ExternalVerifierService externalVerifier = mock(ExternalVerifierService.class);
        CorrectionAgentService correctionAgent = mock(CorrectionAgentService.class);
        ToolDispatchService dispatch = new ToolDispatchService(
                registry, new ObjectMapper(), null, redundancy,
                mock(SelfCorrectionService.class), correctionAgent, externalVerifier, validator,
                mock(ParameterExtractionLearningService.class));
        ReflectionTestUtils.setField(dispatch, "writeGuardService", new WriteGuardService());
        ToolRbacEnforcer rbac = mock(ToolRbacEnforcer.class);
        org.mockito.Mockito.when(rbac.check(any(), anyMap())).thenReturn(ToolRbacEnforcer.Decision.allow());
        org.mockito.Mockito.when(rbac.isAllowed(any(), anyMap())).thenReturn(true);
        ReflectionTestUtils.setField(dispatch, "toolRbacEnforcer", rbac);
        return new DispatchFixture(dispatch, redundancy, validator, externalVerifier, correctionAgent, records);
    }

    private record DispatchFixture(ToolDispatchService dispatch, ToolCallRedundancyService redundancy,
                                   ToolResultValidatorService validator, ExternalVerifierService externalVerifier,
                                   CorrectionAgentService correctionAgent, List<ToolCallRecord> records) {}

    private static final class ScriptedReadTool implements ToolExecutor {
        private final String result;

        private ScriptedReadTool(String result) {
            this.result = result;
        }

        @Override public AccessMode getAccessMode() { return AccessMode.READ; }
        @Override public String getToolName() { return TOOL_NAME; }
        @Override public String getDescription() { return "result verdict read"; }
        @Override public Map<String, Object> getParametersSchema() {
            return Map.of("type", "object", "properties", Map.of(), "required", List.of());
        }
        @Override public String execute(ToolCall call, Map<String, Object> context) { return result; }
    }
}
