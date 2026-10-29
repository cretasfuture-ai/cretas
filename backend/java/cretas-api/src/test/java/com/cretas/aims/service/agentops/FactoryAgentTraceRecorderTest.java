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

    @Test
    void gatewayStatusesMapToTheirRealOutcome() {
        assertThat(FactoryAgentTraceRecorder.outcomeOf("SUCCEEDED")).isEqualTo("SUCCESS");
        assertThat(FactoryAgentTraceRecorder.outcomeOf("DENIED")).isEqualTo("BLOCKED");
        assertThat(FactoryAgentTraceRecorder.outcomeOf("TIMEOUT")).isEqualTo("FAILED");
        assertThat(FactoryAgentTraceRecorder.outcomeOf("OUTCOME_UNKNOWN")).isEqualTo("UNKNOWN");
    }

    @Test
    @SuppressWarnings("unchecked")
    void blockedRunWithoutToolMetadataIsStillTracedWithItsIntent() throws Exception {
        // ToolDispatchService FORBIDDEN: toolName only inside resultData
        IntentExecuteResponse forbidden = IntentExecuteResponse.builder().intentCode("SUPPLIER_PRICE_COMPARE")
                .status("FORBIDDEN").resultData(Map.of("code", "FORBIDDEN", "toolName", "supplier_price_compare")).build();
        Map<String, Object> trace = FactoryAgentTraceRecorder.buildTrace(forbidden, 12);
        assertThat(trace).containsEntry("outcome", "BLOCKED");
        assertThat(((List<Map<String, Object>>) trace.get("steps")).get(0))
                .containsEntry("toolName", "supplier_price_compare");

        // blocked before any tool was chosen: keep it, name the step honestly
        IntentExecuteResponse noTool = IntentExecuteResponse.builder().intentCode("MATERIAL_BATCH_CREATE")
                .status("PERMISSION_DENIED").build();
        Map<String, Object> blocked = FactoryAgentTraceRecorder.buildTrace(noTool, 3);
        assertThat(blocked).containsEntry("outcome", "BLOCKED");
        assertThat(((List<Map<String, Object>>) blocked.get("steps")).get(0))
                .containsEntry("toolName", FactoryAgentTraceRecorder.NO_TOOL_STEP);

        // clarification / not-recognised turns still have nothing to trace
        assertThat(FactoryAgentTraceRecorder.buildTrace(IntentExecuteResponse.builder()
                .intentCode("MATERIAL_BATCH_QUERY").status("NEED_MORE_INFO").build(), 3)).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void skillAndReportRoutedRunsWithoutAToolAreStillTraced() {
        // REPORT_INVENTORY binds tool_name=NULL and runs through the Skill branch: no toolName anywhere.
        IntentExecuteResponse report = IntentExecuteResponse.builder()
                .intentCode("REPORT_INVENTORY").status("COMPLETED").build();
        Map<String, Object> trace = FactoryAgentTraceRecorder.buildTrace(report, 40);
        assertThat(trace).containsEntry("intentCode", "REPORT_INVENTORY").containsEntry("outcome", "SUCCESS");
        assertThat(((List<Map<String, Object>>) trace.get("steps")).get(0))
                .containsEntry("toolName", FactoryAgentTraceRecorder.INTENT_ROUTE_STEP);
    }

    @Test
    void everyDenialStatusTheDispatchersEmitBecomesABlockedRow() {
        // statuses produced by IntentExecutionOrchestrator / ToolDispatchService / SseStreamingService
        for (String status : new String[]{"NO_PERMISSION", "PERMISSION_DENIED", "FORBIDDEN",
                "READ_MODE_WRITE_BLOCKED", "WRITE_CONFIRM_REQUIRED", "TOOL_DISABLED", "PREVIEW_UNSUPPORTED"}) {
            Map<String, Object> trace = FactoryAgentTraceRecorder.buildTrace(IntentExecuteResponse.builder()
                    .intentCode("CUSTOMER_QUERY").status(status).build(), 5);
            assertThat(trace).as(status).isNotNull().containsEntry("outcome", "BLOCKED");
        }
        // session admission denial resolves no intent but is still recorded (never silently dropped)
        Map<String, Object> admission = FactoryAgentTraceRecorder.buildTrace(IntentExecuteResponse.builder()
                .intentRecognized(false).status("NO_PERMISSION").build(), 5);
        assertThat(admission).containsEntry("intentCode", FactoryAgentTraceRecorder.UNRESOLVED_INTENT)
                .containsEntry("outcome", "BLOCKED");
        // a clarification turn is the system asking, not a run
        assertThat(FactoryAgentTraceRecorder.buildTrace(IntentExecuteResponse.builder()
                .intentRecognized(false).status("NEED_CLARIFICATION").build(), 5)).isNull();
        assertThat(FactoryAgentTraceRecorder.buildTrace(IntentExecuteResponse.builder()
                .intentRecognized(false).status("NEED_MORE_INFO").build(), 5)).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void attemptThatResolvedToNothingIsRecordedAsUnknownNeverSuccess() {
        // sales_mgr 「停用员工账号」: intent=null, status COMPLETED
        Map<String, Object> trace = FactoryAgentTraceRecorder.buildTrace(IntentExecuteResponse.builder()
                .intentRecognized(false).status("COMPLETED").message("好的").build(), 9);
        assertThat(trace).containsEntry("intentCode", FactoryAgentTraceRecorder.UNRESOLVED_INTENT)
                .containsEntry("outcome", "UNKNOWN");
        assertThat(((List<Map<String, Object>>) trace.get("steps")).get(0))
                .containsEntry("toolName", FactoryAgentTraceRecorder.NO_TOOL_STEP)
                .containsEntry("outcome", "UNKNOWN");
        assertThat(FactoryAgentTraceRecorder.buildTrace(IntentExecuteResponse.builder()
                .intentRecognized(false).status("NOT_RECOGNIZED").build(), 9))
                .containsEntry("outcome", "UNKNOWN");
    }

    @Test
    void fallbackIntentNamesARunWhoseResponseCarriesNone() {
        // streamed skill/report result: no intentCode on the response, the router knew it
        Map<String, Object> trace = FactoryAgentTraceRecorder.buildTrace(IntentExecuteResponse.builder()
                .status("COMPLETED").build(), 9, "REPORT_INVENTORY");
        assertThat(trace).containsEntry("intentCode", "REPORT_INVENTORY").containsEntry("outcome", "SUCCESS");
    }

    @Test
    void everyFinalNoIntentStatusTheStreamEmitsIsRecordedAsUnknown() {
        for (String status : new String[]{"NO_MATCH", "FACTORY_PACK_NO_MATCH", "VALIDATION_FAILED"}) {
            Map<String, Object> trace = FactoryAgentTraceRecorder.buildTrace(
                    IntentExecuteResponse.builder().intentRecognized(false).status(status).build(), 4);
            assertThat(trace).as(status).isNotNull()
                    .containsEntry("intentCode", FactoryAgentTraceRecorder.UNRESOLVED_INTENT)
                    .containsEntry("outcome", "UNKNOWN");
        }
        // with a resolved intent the pack's no-match keeps its intent code
        assertThat(FactoryAgentTraceRecorder.buildTrace(IntentExecuteResponse.builder()
                .intentCode("MATERIAL_BATCH_QUERY").status("FACTORY_PACK_NO_MATCH").build(), 4))
                .containsEntry("intentCode", "MATERIAL_BATCH_QUERY").containsEntry("outcome", "UNKNOWN");
    }
}
