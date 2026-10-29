package com.cretas.aims.service.agentops;

import com.cretas.aims.client.agentops.AgentOpsClient;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.service.ConversationMemoryService;
import com.cretas.aims.service.execution.BusinessTypeGate;
import com.cretas.aims.service.execution.IntentExecutionOrchestrator;
import com.cretas.aims.service.execution.MultiIntentExecutionService;
import com.cretas.aims.service.execution.SseStreamingService;
import com.cretas.aims.service.impl.IntentExecutorServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S6-07: a real factory run through the execution facade leaves exactly one trace. */
class FactoryAgentTraceRecorderTest {
    private IntentExecutionOrchestrator orchestrator;
    private AgentOpsClient client;
    private BusinessTypeGate gate;
    private FactoryAgentTraceRecorder recorder;
    private IntentExecutorServiceImpl facade;
    private final IntentExecuteRequest request = mock(IntentExecuteRequest.class);

    @BeforeEach
    void setUp() {
        orchestrator = mock(IntentExecutionOrchestrator.class);
        client = mock(AgentOpsClient.class);
        gate = mock(BusinessTypeGate.class);
        when(client.isConfigured()).thenReturn(true);
        when(gate.resolveDomain("F006")).thenReturn("FACTORY");
        when(gate.resolveDomain("R001")).thenReturn("RESTAURANT");
        recorder = new FactoryAgentTraceRecorder(client, gate);
        facade = new IntentExecutorServiceImpl(orchestrator, mock(SseStreamingService.class),
                mock(MultiIntentExecutionService.class), mock(ConversationMemoryService.class));
        ReflectionTestUtils.setField(facade, "factoryTraceRecorder", recorder);
    }

    private static IntentExecuteResponse toolRun(String intent, String tool, String status) {
        return IntentExecuteResponse.builder().intentCode(intent).status(status)
                .metadata(Map.of("toolName", tool)).build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void realFactoryRunWritesOneTraceWithIntentToolAndOutcome() throws Exception {
        IntentExecuteResponse response = toolRun("MATERIAL_BATCH_QUERY", "material_batch_query", "COMPLETED");
        when(orchestrator.execute("F006", request, 42L, "operator")).thenReturn(response);

        IntentExecuteResponse returned = facade.execute("F006", request, 42L, "operator");

        assertThat(returned).isSameAs(response);
        assertThat(recorder.awaitIdle(5_000)).isTrue();
        ArgumentCaptor<Map<String, Object>> trace = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<AgentOpsClient.TrustedContext> ctx =
                ArgumentCaptor.forClass(AgentOpsClient.TrustedContext.class);
        verify(client).recordFactoryTrace(trace.capture(), ctx.capture());
        assertThat(trace.getValue()).containsEntry("intentCode", "MATERIAL_BATCH_QUERY")
                .containsEntry("outcome", "SUCCESS");
        Map<String, Object> step = ((List<Map<String, Object>>) trace.getValue().get("steps")).get(0);
        assertThat(step).containsEntry("toolName", "material_batch_query").containsEntry("outcome", "SUCCESS");
        assertThat(ctx.getValue().factoryId()).isEqualTo("F006");
        assertThat(ctx.getValue().role()).isEqualTo("operator");
        assertThat(ctx.getValue().businessType()).isEqualTo("FACTORY");
        assertThat(recorder.recordedCount()).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedAndBlockedRunsKeepTheirTruthAndUnknownIsNeverSuccess() throws Exception {
        assertThat(FactoryAgentTraceRecorder.outcomeOf("FAILED")).isEqualTo("FAILED");
        assertThat(FactoryAgentTraceRecorder.outcomeOf("PERMISSION_DENIED")).isEqualTo("BLOCKED");
        assertThat(FactoryAgentTraceRecorder.outcomeOf("SOMETHING_NEW")).isEqualTo("UNKNOWN");
        assertThat(FactoryAgentTraceRecorder.outcomeOf(null)).isEqualTo("UNKNOWN");
    }

    @Test
    void restaurantTenantsAndToolLessTurnsAreNotTraced() throws Exception {
        when(orchestrator.execute("R001", request, 7L, "platform_admin"))
                .thenReturn(toolRun("MATERIAL_BATCH_QUERY", "material_batch_query", "COMPLETED"));
        facade.execute("R001", request, 7L, "platform_admin");
        when(orchestrator.execute("F006", request, 42L, "operator"))
                .thenReturn(IntentExecuteResponse.builder().intentCode("MATERIAL_BATCH_QUERY")
                        .status("NEED_MORE_INFO").build());
        facade.execute("F006", request, 42L, "operator");
        assertThat(recorder.awaitIdle(2_000)).isTrue();
        verify(client, never()).recordFactoryTrace(any(), any());
    }

    @Test
    void uploadFailureNeverChangesTheAnswerButIsCounted() throws Exception {
        IntentExecuteResponse response = toolRun("MATERIAL_BATCH_QUERY", "material_batch_query", "COMPLETED");
        when(orchestrator.execute("F006", request, 42L, "operator")).thenReturn(response);
        when(client.recordFactoryTrace(any(), any())).thenThrow(new IOException("down"));

        assertThat(facade.execute("F006", request, 42L, "operator")).isSameAs(response);
        assertThat(recorder.awaitIdle(5_000)).isTrue();
        assertThat(recorder.failedCount()).isEqualTo(1);
        assertThat(recorder.recordedCount()).isZero();
    }
}
