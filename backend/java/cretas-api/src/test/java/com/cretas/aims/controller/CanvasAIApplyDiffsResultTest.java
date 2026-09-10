package com.cretas.aims.controller;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.ToolExecutionGateway;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.MobileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CanvasAIApplyDiffsResultTest {
    private ToolRegistry registry;
    private CanvasAIController controller;
    private MobileService mobile;
    private static final String TOOL = "canvas_work_process_catalog";

    @BeforeEach void setUp() {
        registry = mock(ToolRegistry.class);
        mobile = mock(MobileService.class);
        var user = new com.cretas.aims.dto.user.UserDTO();
        user.setId(1L);
        when(mobile.getUserFromToken("local-test")).thenReturn(user);
        controller = new CanvasAIController(registry, new ObjectMapper(), mock(DashScopeClient.class),
                mobile, mock(ToolExecutionGateway.class));
    }

    private Map<String, Object> diff(Map<String, Object> params) { return Map.of("tool", TOOL, "params", params); }

    @Test void realBusinessToolFailureAndMissingParametersAreNotSuccessful() {
        var tool = new ContractTool();
        when(registry.getExecutor(TOOL)).thenReturn(Optional.of(tool));
        var response = controller.applyDiffs("LOCAL_B05", "Bearer local-test", List.of(diff(Map.of()), diff(Map.of("action", "fail"))));
        assertFalse(response.getSuccess());
        assertEquals(0, response.getData().applied());
        assertTrue(response.getData().items().stream().allMatch(item -> item.status().equals("FAILURE")));
        assertTrue(response.getData().items().get(0).message().contains("信息"));
        assertEquals(0, tool.writes);
    }

    @Test void partialResultKeepsSuccessReuseFailureAndUnknownSeparateWithoutReplaying() {
        var tool = new ContractTool();
        when(registry.getExecutor(TOOL)).thenReturn(Optional.of(tool));
        var response = controller.applyDiffs("LOCAL_B05", "Bearer local-test", List.of(
                diff(Map.of("action", "create")), diff(Map.of("action", "duplicate")), diff(Map.of("action", "fail"))));
        assertFalse(response.getSuccess());
        assertEquals(List.of("SUCCESS", "REUSED", "FAILURE"), response.getData().items().stream().map(CanvasAIController.ApplyDiffResult::status).toList());
        assertEquals(1, response.getData().applied());
        assertEquals(1, response.getData().reused());
        assertEquals(1, tool.writes);
        assertEquals(List.of(0, 1, 2), response.getData().items().stream().map(CanvasAIController.ApplyDiffResult::index).toList());
    }

    @Test void allSuccessfulHasExplicitSuccessAndDistinctInvocationIds() throws Exception {
        ToolExecutor tool = mock(ToolExecutor.class);
        when(registry.getExecutor(TOOL)).thenReturn(Optional.of(tool));
        when(tool.execute(any(), anyMap())).thenReturn("{\"success\":true,\"data\":{}}");
        var response = controller.applyDiffs("LOCAL_B05", null, List.of(diff(Map.of()), diff(Map.of())));
        assertTrue(response.getSuccess());
        assertEquals(2, response.getData().applied());
        var ids = org.mockito.ArgumentCaptor.forClass(ToolCall.class);
        verify(tool, times(2)).execute(ids.capture(), anyMap());
        assertNotEquals(ids.getAllValues().get(0).getId(), ids.getAllValues().get(1).getId());
    }

    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = {"not-json", "null", "[]", "{}", "{\"success\":\"true\"}"})
    void malformedToolResultIsUnknownNotSafeToRetry(String raw) throws Exception {
        ToolExecutor tool = mock(ToolExecutor.class);
        when(registry.getExecutor(TOOL)).thenReturn(Optional.of(tool));
        when(tool.execute(any(), anyMap())).thenReturn(raw);
        var response = controller.applyDiffs("LOCAL_B05", null, List.of(diff(Map.of())));
        assertFalse(response.getSuccess());
        assertEquals("UNKNOWN", response.getData().items().get(0).status());
        verify(tool, times(1)).execute(any(), anyMap());
    }

    @Test void thrownExecutorMayAlreadyHaveWrittenSoIsUnknown() throws Exception {
        ToolExecutor tool = mock(ToolExecutor.class);
        when(registry.getExecutor(TOOL)).thenReturn(Optional.of(tool));
        when(tool.execute(any(), anyMap())).thenThrow(new IllegalStateException("private-internal-error"));
        var response = controller.applyDiffs("LOCAL_B05", null, List.of(diff(Map.of())));
        assertEquals("UNKNOWN", response.getData().items().get(0).status());
        assertFalse(response.getData().items().get(0).message().contains("private-internal-error"));
    }

    @Test void emptyInvalidUnknownAndPreviewOnlyNeverExecute() {
        assertFalse(controller.applyDiffs("LOCAL_B05", null, List.of()).getSuccess());
        assertFalse(controller.applyDiffs("LOCAL_B05", null, null).getSuccess());
        var response = controller.applyDiffs("LOCAL_B05", null, Arrays.asList(null, Map.of(),
                Map.of("tool", TOOL, "params", "invalid"), Map.of("tool", "canvas_missing"),
                Map.of("tool", "canvas_product_process_workflow_config")));
        assertFalse(response.getSuccess());
        assertEquals(5, response.getData().items().size());
        assertTrue(response.getData().items().stream().allMatch(item -> item.status().equals("FAILURE")));
        verify(registry, never()).getExecutor("canvas_product_process_workflow_config");
    }

    /** Run the real AbstractBusinessTool JSON wrapping, including caught BusinessException. */
    static class ContractTool extends AbstractBusinessTool {
        int writes;
        ContractTool() { objectMapper = new ObjectMapper(); }
        public String getToolName() { return TOOL; }
        public String getDescription() { return "local test"; }
        public Map<String, Object> getParametersSchema() { return Map.of(); }
        protected List<String> getRequiredParameters() { return List.of("action"); }
        protected Map<String, Object> doExecute(String factory, Map<String, Object> params, Map<String, Object> context) {
            if ("fail".equals(params.get("action"))) throw new BusinessException("工序未完成");
            if ("duplicate".equals(params.get("action"))) return Map.of("status", "DUPLICATE");
            writes++;
            return Map.of("id", "local-1");
        }
    }
}
