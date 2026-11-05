package com.cretas.aims.controller;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.ToolExecutionGateway;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import org.springframework.test.util.ReflectionTestUtils;
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
    private PreviewTokenService previewTokens;
    private static final String TOOL = "canvas_work_process_catalog";

    @BeforeEach void setUp() {
        registry = mock(ToolRegistry.class);
        mobile = mock(MobileService.class);
        var user = new com.cretas.aims.dto.user.UserDTO();
        user.setId(1L);
        user.setFactoryId("LOCAL_B05");
        user.setFactoryType("FACTORY");
        user.setRoleCode(FactoryUserRole.permission_admin);
        when(mobile.getUserFromToken("local-test")).thenReturn(user);
        controller = new CanvasAIController(registry, new ObjectMapper(), mock(DashScopeClient.class),
                mobile, mock(ToolExecutionGateway.class));
        previewTokens = mock(PreviewTokenService.class);
        when(previewTokens.resolveClaim(anyString(), anyString(), anyBoolean(), anyString())).thenReturn(true);
        ReflectionTestUtils.setField(controller, "previewTokenService", previewTokens);
    }

    private Map<String, Object> diff(Map<String, Object> params) {
        String proof = "test-proof-" + UUID.randomUUID();
        String digest = "test-digest-" + UUID.randomUUID();
        String claimId = "test-claim-" + UUID.randomUUID();
        Map<String, Object> persisted = Map.copyOf(params);
        IntentPreviewToken row = IntentPreviewToken.builder().token(proof)
                .factoryId("LOCAL_B05").tenantId("LOCAL_B05").userId(1L)
                .intentCode("CANVAS_DIFF_" + UUID.randomUUID()).entityType("CANVAS_DIFF")
                .entityId(TOOL).operation("UPDATE").executionMode(ToolExecutionMode.EXECUTE)
                .toolName(TOOL).descriptorVersion("1.0.0").commandDigest(digest).build();
        when(previewTokens.claimToken(proof, "LOCAL_B05", 1L, digest))
                .thenReturn(PreviewTokenService.ClaimResult.success(row, claimId, persisted))
                .thenReturn(PreviewTokenService.ClaimResult.failure("令牌已处理"));
        return Map.of("tool", TOOL, "params", params,
                "proof", Map.of("token", proof, "commandDigest", digest, "descriptorVersion", "1.0.0"));
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"1", "2"})
    void legacyHttpClientIsRejectedBeforeAnyExecution(String protocol) throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/mobile/LOCAL_B05/config/v2/ai/apply-diffs")
                .contentType("application/json").content("[]");
        if (protocol != null) request.header("X-Canvas-Result-Protocol", protocol);
        mvc.perform(request)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.success").value(false))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value(org.hamcrest.Matchers.containsString("未执行")));
        verifyNoInteractions(registry, mobile);
    }

    @Test void protocolThreeWithoutServerProofIsRejectedBeforeExecution() throws Exception {
        var tool = new ContractTool();
        when(registry.getExecutor(TOOL)).thenReturn(Optional.of(tool));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/mobile/LOCAL_B05/config/v2/ai/apply-diffs")
                .header("X-Canvas-Result-Protocol", "3").header("Authorization", "Bearer local-test")
                .contentType("application/json").content("[{\"tool\":\"canvas_work_process_catalog\",\"params\":{\"action\":\"create\"}}]"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.success").value(false))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.items[0].message").value(org.hamcrest.Matchers.containsString("确认凭据")));
        assertEquals(0, tool.writes);
    }

    @Test void protocolThreeWithNoActorCannotClaimOrExecute() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/mobile/LOCAL_B05/config/v2/ai/apply-diffs")
                .header("X-Canvas-Result-Protocol", "3").contentType("application/json").content("[]"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.success").value(false))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value(org.hamcrest.Matchers.containsString("登录")));
        verify(previewTokens, never()).claimToken(anyString(), anyString(), anyLong(), anyString());
        verifyNoInteractions(registry);
    }

    @Test void internalEntryCannotExecuteRawDiffWithoutProof() {
        var tool = new ContractTool();
        when(registry.getExecutor(TOOL)).thenReturn(Optional.of(tool));
        var response = controller.applyDiffs("LOCAL_B05", "Bearer local-test",
                List.of(Map.of("tool", TOOL, "params", Map.of("action", "create"))));
        assertFalse(response.getSuccess());
        assertEquals("FAILURE", response.getData().items().get(0).status());
        assertTrue(response.getData().items().get(0).message().contains("确认凭据"));
        assertEquals(0, tool.writes);
        verify(previewTokens, never()).claimToken(anyString(), anyString(), anyLong(), anyString());
        verify(registry, never()).getExecutor(anyString());
    }

    @Test void toolWrappedFailuresDoNotProveThatRetryIsSafe() {
        var tool = new ContractTool();
        when(registry.getExecutor(TOOL)).thenReturn(Optional.of(tool));
        var response = controller.applyDiffs("LOCAL_B05", "Bearer local-test", List.of(diff(Map.of()), diff(Map.of("action", "fail"))));
        assertFalse(response.getSuccess());
        assertEquals(0, response.getData().applied());
        // Both responses arrive after execute(). The controller cannot distinguish
        // wrapper validation from an error caught after a business side effect.
        assertEquals(List.of("UNKNOWN", "UNKNOWN"), response.getData().items().stream().map(CanvasAIController.ApplyDiffResult::status).toList());
        assertTrue(response.getData().items().get(0).message().contains("信息"));
        assertEquals(0, tool.writes);
    }

    @Test void partialResultKeepsSuccessReuseFailureAndUnknownSeparateWithoutReplaying() {
        var tool = new ContractTool();
        when(registry.getExecutor(TOOL)).thenReturn(Optional.of(tool));
        var response = controller.applyDiffs("LOCAL_B05", "Bearer local-test", List.of(
                diff(Map.of("action", "create")), diff(Map.of("action", "duplicate")), diff(Map.of("action", "fail"))));
        assertFalse(response.getSuccess());
        assertEquals(List.of("SUCCESS", "REUSED", "UNKNOWN"), response.getData().items().stream().map(CanvasAIController.ApplyDiffResult::status).toList());
        assertEquals(1, response.getData().applied());
        assertEquals(1, response.getData().reused());
        assertEquals(1, tool.writes);
        assertEquals(List.of(0, 1, 2), response.getData().items().stream().map(CanvasAIController.ApplyDiffResult::index).toList());
    }

    @Test void allSuccessfulHasExplicitSuccessAndDistinctInvocationIds() throws Exception {
        ToolExecutor tool = mock(ToolExecutor.class);
        when(tool.getVersion()).thenReturn("1.0.0");
        when(registry.getExecutor(TOOL)).thenReturn(Optional.of(tool));
        when(tool.execute(any(), anyMap())).thenReturn("{\"success\":true,\"data\":{}}");
        var response = controller.applyDiffs("LOCAL_B05", "Bearer local-test", List.of(diff(Map.of()), diff(Map.of())));
        assertTrue(response.getSuccess());
        assertEquals(2, response.getData().applied());
        var ids = org.mockito.ArgumentCaptor.forClass(ToolCall.class);
        verify(tool, times(2)).execute(ids.capture(), anyMap());
        assertNotEquals(ids.getAllValues().get(0).getId(), ids.getAllValues().get(1).getId());
    }

    @Test void pendingDdlInsideRealToolWrappingIsNotReportedComplete() {
        var tool = new ContractTool();
        when(registry.getExecutor(TOOL)).thenReturn(Optional.of(tool));
        var response = controller.applyDiffs("LOCAL_B05", "Bearer local-test", List.of(diff(Map.of("action", "pending-ddl"))));
        assertFalse(response.getSuccess());
        assertEquals("UNKNOWN", response.getData().items().get(0).status());
        assertTrue(response.getData().items().get(0).message().contains("尚未完成"));
    }

    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = {"not-json", "null", "[]", "{}", "{\"success\":\"true\"}"})
    void malformedToolResultIsUnknownNotSafeToRetry(String raw) throws Exception {
        ToolExecutor tool = mock(ToolExecutor.class);
        when(tool.getVersion()).thenReturn("1.0.0");
        when(registry.getExecutor(TOOL)).thenReturn(Optional.of(tool));
        when(tool.execute(any(), anyMap())).thenReturn(raw);
        var response = controller.applyDiffs("LOCAL_B05", "Bearer local-test", List.of(diff(Map.of())));
        assertFalse(response.getSuccess());
        assertEquals("UNKNOWN", response.getData().items().get(0).status());
        verify(tool, times(1)).execute(any(), anyMap());
    }

    @Test void thrownExecutorMayAlreadyHaveWrittenSoIsUnknown() throws Exception {
        ToolExecutor tool = mock(ToolExecutor.class);
        when(tool.getVersion()).thenReturn("1.0.0");
        when(registry.getExecutor(TOOL)).thenReturn(Optional.of(tool));
        when(tool.execute(any(), anyMap())).thenThrow(new IllegalStateException("private-internal-error"));
        var response = controller.applyDiffs("LOCAL_B05", "Bearer local-test", List.of(diff(Map.of())));
        assertEquals("UNKNOWN", response.getData().items().get(0).status());
        assertFalse(response.getData().items().get(0).message().contains("private-internal-error"));
    }

    @Test void emptyInvalidUnknownAndPreviewOnlyNeverExecute() {
        assertFalse(controller.applyDiffs("LOCAL_B05", "Bearer local-test", List.of()).getSuccess());
        assertFalse(controller.applyDiffs("LOCAL_B05", "Bearer local-test", null).getSuccess());
        var response = controller.applyDiffs("LOCAL_B05", "Bearer local-test", Arrays.asList(null, Map.of(),
                Map.of("tool", TOOL, "params", "invalid"), Map.of("tool", "canvas_missing"),
                Map.of("tool", "canvas_product_process_workflow_config")));
        assertFalse(response.getSuccess());
        assertEquals(5, response.getData().items().size());
        assertTrue(response.getData().items().stream().allMatch(item -> item.status().equals("FAILURE")));
        verify(registry, never()).getExecutor("canvas_product_process_workflow_config");
    }

    /** Run the real AbstractBusinessTool JSON wrapping, including caught BusinessException. */
    @Test void caughtFailureAfterSideEffectIsNotSafeToRetry() {
        var tool = new ContractTool();
        when(registry.getExecutor(TOOL)).thenReturn(Optional.of(tool));
        var response = controller.applyDiffs("LOCAL_B05", "Bearer local-test", List.of(diff(Map.of("action", "write-then-fail"))));
        assertEquals(1, tool.writes);
        assertEquals("UNKNOWN", response.getData().items().get(0).status());
        assertFalse(response.getSuccess());
    }

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
            if ("pending-ddl".equals(params.get("action"))) return Map.of("message", "字段待处理", "data", Map.of("status", "PENDING_DDL"));
            writes++;
            if ("write-then-fail".equals(params.get("action"))) throw new BusinessException("写后异常");
            return Map.of("id", "local-1");
        }
    }
}
