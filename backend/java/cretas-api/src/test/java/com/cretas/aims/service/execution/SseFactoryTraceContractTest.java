package com.cretas.aims.service.execution;

import com.cretas.aims.client.agentops.AgentOpsClient;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.service.agentops.FactoryAgentTraceRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S6-07: the app's AI chat runs through executeStream, which never touches the facade. */
class SseFactoryTraceContractTest {

    private record Harness(SseStreamingService service, AgentOpsClient client,
                           FactoryAgentTraceRecorder recorder) {}

    private Harness harness(String domain, IntentExecuteResponse streamedResult) {
        return harness(domain, streamedResult, null);
    }

    private Harness harness(String domain, IntentExecuteResponse streamedResult, String routedIntent) {
        var executor = Executors.newSingleThreadExecutor();
        var real = new SseStreamingService(null, null, null, null,
                new ObjectMapper().findAndRegisterModules(), null, null, null, null, null);
        ReflectionTestUtils.setField(real, "sseExecutor", executor);
        var service = spy(real);
        var client = mock(AgentOpsClient.class);
        when(client.isConfigured()).thenReturn(true);
        var gate = mock(BusinessTypeGate.class);
        when(gate.resolveDomain("F006")).thenReturn(domain);
        var recorder = new FactoryAgentTraceRecorder(client, gate);
        ReflectionTestUtils.setField(service, "factoryTraceRecorder", recorder);
        // Stand in for the (heavy) routing: it ends the streamed run by sending its "result" event.
        doAnswer(call -> {
            SseEmitter emitter = call.getArgument(0);
            if (routedIntent != null) {
                service.noteStreamedIntent(emitter, routedIntent); // what the real router does once it resolved
            }
            service.sendSseEvent(emitter, "result", streamedResult);
            return null;
        }).when(service).executeStreamAsync(any(), any(), any(), any(), any(), any());
        return new Harness(service, client, recorder);
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamedFactoryRunWritesATrace() throws Exception {
        var run = IntentExecuteResponse.builder().intentCode("MATERIAL_BATCH_QUERY").status("COMPLETED")
                .metadata(Map.of("toolName", "material_batch_query")).build();
        var h = harness("FACTORY", run);

        h.service().executeStream("F006", new IntentExecuteRequest(), 42L, "operator");

        assertThat(awaitTrace(h)).isTrue();
        ArgumentCaptor<Map<String, Object>> trace = ArgumentCaptor.forClass(Map.class);
        verify(h.client()).recordFactoryTrace(trace.capture(), any());
        assertThat(trace.getValue()).containsEntry("intentCode", "MATERIAL_BATCH_QUERY")
                .containsEntry("outcome", "SUCCESS");
    }

    @Test
    void streamedRestaurantRunIsNotTraced() throws Exception {
        var run = IntentExecuteResponse.builder().intentCode("MATERIAL_BATCH_QUERY").status("COMPLETED")
                .metadata(Map.of("toolName", "material_batch_query")).build();
        var h = harness("RESTAURANT", run);

        h.service().executeStream("F006", new IntentExecuteRequest(), 42L, "operator");

        Thread.sleep(300);
        assertThat(h.recorder().awaitIdle(2_000)).isTrue();
        verify(h.client(), never()).recordFactoryTrace(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamedToolLessReportIntentIsTracedUnderTheRoutedIntent() throws Exception {
        // REPORT_INVENTORY (tool_name NULL) streams the skill's own response: no intentCode, no toolName.
        var skillResult = IntentExecuteResponse.builder().status("COMPLETED").message("库存报表").build();
        var h = harness("FACTORY", skillResult, "REPORT_INVENTORY");

        h.service().executeStream("F006", new IntentExecuteRequest(), 42L, "operator");

        assertThat(awaitTrace(h)).isTrue();
        ArgumentCaptor<Map<String, Object>> trace = ArgumentCaptor.forClass(Map.class);
        verify(h.client()).recordFactoryTrace(trace.capture(), any());
        assertThat(trace.getValue()).containsEntry("intentCode", "REPORT_INVENTORY")
                .containsEntry("outcome", "SUCCESS");
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamThatEndsInTheErrorExitIsTracedAsFailed() throws Exception {
        var h = harness("FACTORY", null, "MATERIAL_BATCH_QUERY");
        doAnswer(call -> {
            SseEmitter emitter = call.getArgument(0);
            h.service().noteStreamedIntent(emitter, "MATERIAL_BATCH_QUERY");
            h.service().sendSseEvent(emitter, "error", Map.of("status", "FAILED", "message", "boom"));
            return null;
        }).when(h.service()).executeStreamAsync(any(), any(), any(), any(), any(), any());

        h.service().executeStream("F006", new IntentExecuteRequest(), 42L, "operator");

        assertThat(awaitTrace(h)).isTrue();
        ArgumentCaptor<Map<String, Object>> trace = ArgumentCaptor.forClass(Map.class);
        verify(h.client()).recordFactoryTrace(trace.capture(), any());
        assertThat(trace.getValue()).containsEntry("intentCode", "MATERIAL_BATCH_QUERY")
                .containsEntry("outcome", "FAILED");
    }

    private boolean awaitTrace(Harness h) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (h.recorder().recordedCount() >= 1) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }
}
