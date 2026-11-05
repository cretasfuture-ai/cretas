package com.cretas.aims.controller;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.ToolExecutionGateway;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.repository.intent.IntentPreviewTokenRepository;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.service.impl.PreviewTokenServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Optional;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CanvasAIPlanConfirmationContractTest {
    private static final String FACTORY = "LOCAL_B05";
    private static final String TOOL = "canvas_work_process_catalog";

    private ToolRegistry registry;
    private ToolExecutor executor;
    private MobileService mobile;
    private PreviewTokenService previewTokens;
    private DashScopeClient llm;
    private CanvasAIController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        registry = mock(ToolRegistry.class);
        executor = mock(ToolExecutor.class);
        mobile = mock(MobileService.class);
        previewTokens = mock(PreviewTokenService.class);
        UserDTO user = new UserDTO();
        user.setId(7L);
        user.setFactoryId(FACTORY);
        user.setFactoryType("FACTORY");
        user.setRoleCode(FactoryUserRole.permission_admin);
        when(mobile.getUserFromToken("local-test")).thenReturn(user);
        when(registry.getExecutor(TOOL)).thenReturn(Optional.of(executor));
        when(executor.getVersion()).thenReturn("1.0.0");
        llm = mock(DashScopeClient.class);
        controller = new CanvasAIController(registry, new ObjectMapper(), llm,
                mobile, mock(ToolExecutionGateway.class));
        ReflectionTestUtils.setField(controller, "previewTokenService", previewTokens);
        when(previewTokens.resolveClaim(anyString(), anyString(), anyBoolean(), anyString())).thenReturn(true);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void missingProofNeverReachesExecutor() throws Exception {
        mvc.perform(post("/api/mobile/{factoryId}/config/v2/ai/apply-diffs", FACTORY)
                        .header("Authorization", "Bearer local-test")
                        .header("X-Canvas-Result-Protocol", "3")
                        .contentType("application/json")
                        .content("[{\"tool\":\"" + TOOL + "\",\"params\":{\"action\":\"create\"}}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.items[0].status").value("FAILURE"));
        verifyNoInteractions(executor);
        verify(previewTokens, never()).claimToken(anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void claimExceptionIsUnknownWithoutResolutionOrExecutor() throws Exception {
        when(previewTokens.claimToken("proof-claim-error", FACTORY, 7L, "digest-error"))
                .thenThrow(new IllegalStateException("database unavailable"));

        mvc.perform(post("/api/mobile/{factoryId}/config/v2/ai/apply-diffs", FACTORY)
                        .header("Authorization", "Bearer local-test")
                        .header("X-Canvas-Result-Protocol", "3")
                        .contentType("application/json")
                        .content("[{\"tool\":\"" + TOOL + "\",\"params\":{\"action\":\"create\"},"
                                + "\"proof\":{\"token\":\"proof-claim-error\",\"commandDigest\":\"digest-error\"}}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].status").value("UNKNOWN"));
        verify(executor, never()).execute(any(), anyMap());
        verify(previewTokens, never()).resolveClaim(anyString(), anyString(), anyBoolean(), anyString());
    }

    @Test
    void rejectedClaimCoversReplayOrExpiredProofWithoutExecutor() throws Exception {
        when(previewTokens.claimToken("proof-replay", FACTORY, 7L, "digest-replay"))
                .thenReturn(PreviewTokenService.ClaimResult.failure("令牌已被处理: CONFIRMED"));

        mvc.perform(post("/api/mobile/{factoryId}/config/v2/ai/apply-diffs", FACTORY)
                        .header("Authorization", "Bearer local-test")
                        .header("X-Canvas-Result-Protocol", "3")
                        .contentType("application/json")
                        .content("[{\"tool\":\"" + TOOL + "\",\"params\":{\"action\":\"create\"},"
                                + "\"proof\":{\"token\":\"proof-replay\",\"commandDigest\":\"digest-replay\"}}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].status").value("FAILURE"));
        verify(executor, never()).execute(any(), anyMap());
    }

    @Test
    void mismatchedFactoryIdentityStopsBeforeClaim() throws Exception {
        UserDTO otherFactoryUser = new UserDTO();
        otherFactoryUser.setId(7L);
        otherFactoryUser.setFactoryId("OTHER");
        otherFactoryUser.setFactoryType("FACTORY");
        otherFactoryUser.setRoleCode(FactoryUserRole.permission_admin);
        when(mobile.getUserFromToken("local-test")).thenReturn(otherFactoryUser);

        mvc.perform(post("/api/mobile/{factoryId}/config/v2/ai/apply-diffs", FACTORY)
                        .header("Authorization", "Bearer local-test")
                        .header("X-Canvas-Result-Protocol", "3")
                        .contentType("application/json")
                        .content("[{\"proof\":{\"token\":\"proof-cross-tenant\",\"commandDigest\":\"digest\"}}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
        verify(previewTokens, never()).claimToken(anyString(), anyString(), anyLong(), anyString());
        verify(executor, never()).execute(any(), anyMap());
    }

    @Test
    void planIssuesDistinctPersistentProofForEachSelectableDiff() {
        when(llm.chatFast(anyString(), anyString())).thenReturn("["
                + "{\"type\":\"FIELD_CHANGE\",\"tool\":\"" + TOOL + "\",\"params\":{\"action\":\"create\"},\"description\":\"one\"},"
                + "{\"type\":\"FIELD_CHANGE\",\"tool\":\"" + TOOL + "\",\"params\":{\"action\":\"update\"},\"description\":\"two\"}]");
        when(previewTokens.createBoundToken(any())).thenAnswer(invocation -> {
            PreviewTokenService.BoundTokenRequest request = invocation.getArgument(0);
            return IntentPreviewToken.builder().token("issued-" + request.intentCode())
                    .commandDigest("digest-issued").build();
        });

        CanvasAIController.AIRequest request = new CanvasAIController.AIRequest();
        request.setMode("plan");
        request.setMessage("生成两项配置");
        CanvasAIController.AIResponse response = controller.chat(
                FACTORY, "Bearer local-test", request).getData();

        assertEquals(2, response.getDiffs().size());
        var requests = org.mockito.ArgumentCaptor.forClass(PreviewTokenService.BoundTokenRequest.class);
        verify(previewTokens, times(2)).createBoundToken(requests.capture());
        assertEquals(2, requests.getAllValues().stream().map(PreviewTokenService.BoundTokenRequest::intentCode).distinct().count());
        assertEquals(2, response.getDiffs().stream().filter(diff -> diff.get("proof") instanceof Map<?, ?>).count());
    }

    @Test
    void genericPlanNeverBindsProductWorkProcessModuleTool() {
        when(llm.chatFast(anyString(), anyString())).thenReturn("["
                + "{\"type\":\"FIELD_CHANGE\",\"tool\":\"canvas_product_work_process_config\","
                + "\"params\":{\"action\":\"create\"},\"description\":\"draft\"}]");

        CanvasAIController.AIRequest request = new CanvasAIController.AIRequest();
        request.setMode("plan");
        request.setMessage("生成工序草稿");
        CanvasAIController.AIResponse response = controller.chat(
                FACTORY, "Bearer local-test", request).getData();

        verify(previewTokens, never()).createBoundToken(any());
        assertEquals(1, response.getDiffs().size());
        assertEquals(null, response.getDiffs().get(0).get("proof"));
    }

    @Test
    void realPreviewTokenServiceRoundTripsDecimalPlanBeforeHttpExecution() throws Exception {
        IntentPreviewTokenRepository repository = mock(IntentPreviewTokenRepository.class);
        PreviewTokenServiceImpl realPreviewTokens = new PreviewTokenServiceImpl(repository, new ObjectMapper());
        final IntentPreviewToken[] saved = new IntentPreviewToken[1];
        when(repository.cancelPreviousTokens(anyString(), anyLong(), anyString(), any())).thenReturn(0);
        when(repository.save(any(IntentPreviewToken.class))).thenAnswer(invocation -> {
            saved[0] = invocation.getArgument(0);
            return saved[0];
        });
        when(repository.findByToken(anyString())).thenAnswer(invocation -> Optional.ofNullable(saved[0]));
        when(repository.findByTokenAndClaimIdAndStatus(anyString(), anyString(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(saved[0]));
        when(repository.claimForExecution(anyString(), anyString(), anyString(), anyLong(), anyString(),
                anyString(), anyString(), anyString(), any(), anyString(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    saved[0].setStatus(IntentPreviewToken.TokenStatus.EXECUTING);
                    saved[0].setClaimId(invocation.getArgument(9));
                    return 1;
                });
        when(repository.resolveClaim(anyString(), anyString(), any(), any(), any(), anyString())).thenReturn(1);
        ReflectionTestUtils.setField(controller, "previewTokenService", realPreviewTokens);
        when(executor.execute(any(ToolCall.class), anyMap()))
                .thenReturn("{\"success\":true,\"data\":{}}" );
        when(llm.chatFast(anyString(), anyString())).thenReturn("["
                + "{\"type\":\"FIELD_CHANGE\",\"tool\":\"" + TOOL + "\","
                + "\"params\":{\"action\":\"create\",\"rate\":25.00,\"ratio\":0.30},"
                + "\"description\":\"decimal\"}]");

        CanvasAIController.AIRequest plan = new CanvasAIController.AIRequest();
        plan.setMode("plan");
        plan.setMessage("生成带小数的配置");
        CanvasAIController.AIResponse response = controller.chat(
                FACTORY, "Bearer local-test", plan).getData();
        Map<?, ?> proof = (Map<?, ?>) response.getDiffs().get(0).get("proof");
        assertEquals("25.0", String.valueOf(((Map<?, ?>) response.getDiffs().get(0).get("params")).get("rate")));
        assertEquals("0.3", String.valueOf(((Map<?, ?>) response.getDiffs().get(0).get("params")).get("ratio")));

        String body = "[{\"tool\":\"" + TOOL + "\",\"params\":{\"action\":\"create\","
                + "\"rate\":25,\"ratio\":0.3},\"proof\":{\"token\":\"" + proof.get("token")
                + "\",\"commandDigest\":\"" + proof.get("commandDigest") + "\"}}]";
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/api/mobile/{factoryId}/config/v2/ai/apply-diffs", FACTORY)
                        .header("Authorization", "Bearer local-test")
                        .header("X-Canvas-Result-Protocol", "3")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].status").value("SUCCESS"));
        verify(executor).execute(any(ToolCall.class), anyMap());
    }

    @Test
    void claimExecutesOnlyWhenBodyMatchesPersistedCommand() throws Exception {
        IntentPreviewToken token = token(Map.of("action", "create", "processName", "persisted"));
        when(previewTokens.claimToken("proof-1", FACTORY, 7L, "digest-1"))
                .thenReturn(PreviewTokenService.ClaimResult.success(token, "claim-1",
                        Map.of("action", "create", "processName", "persisted")));
        when(executor.execute(any(ToolCall.class), anyMap()))
                .thenReturn("{\"success\":true,\"data\":{}}" );

        mvc.perform(post("/api/mobile/{factoryId}/config/v2/ai/apply-diffs", FACTORY)
                        .header("Authorization", "Bearer local-test")
                        .header("X-Canvas-Result-Protocol", "3")
                        .contentType("application/json")
                        .content("[{\"tool\":\"" + TOOL + "\",\"params\":{\"action\":\"create\",\"processName\":\"persisted\"},"
                                + "\"proof\":{\"token\":\"proof-1\",\"commandDigest\":\"digest-1\",\"descriptorVersion\":\"1.0.0\"}}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].status").value("SUCCESS"));

        var call = org.mockito.ArgumentCaptor.forClass(ToolCall.class);
        verify(executor).execute(call.capture(), anyMap());
        assertEquals(TOOL, call.getValue().getFunction().getName());
        assertEquals("persisted", new ObjectMapper().readTree(call.getValue().getFunction().getArguments()).path("processName").asText());
        verify(previewTokens).resolveClaim("proof-1", "claim-1", true, "变更已完成");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unresolvedSuccessfulWriteIsUnknownAndTheSameProofNeverExecutesAgain(boolean throwsOnResolve) throws Exception {
        Map<String, Object> params = Map.of("action", "create");
        when(previewTokens.claimToken("proof-resolve", FACTORY, 7L, "digest-resolve"))
                .thenReturn(PreviewTokenService.ClaimResult.success(token(params), "claim-resolve", params))
                .thenReturn(PreviewTokenService.ClaimResult.failure("令牌已被处理: EXECUTING"));
        when(executor.execute(any(ToolCall.class), anyMap())).thenReturn("{\"success\":true,\"data\":{}}");
        when(previewTokens.resolveClaim(eq("proof-resolve"), eq("claim-resolve"), eq(true), anyString()))
                .thenAnswer(invocation -> {
                    if (throwsOnResolve) throw new IllegalStateException("resolution store unavailable");
                    return false;
                });
        String body = "[{\"tool\":\"" + TOOL + "\",\"params\":{\"action\":\"create\"},"
                + "\"proof\":{\"token\":\"proof-resolve\",\"commandDigest\":\"digest-resolve\"}}]";

        mvc.perform(post("/api/mobile/{factoryId}/config/v2/ai/apply-diffs", FACTORY)
                        .header("Authorization", "Bearer local-test")
                        .header("X-Canvas-Result-Protocol", "3")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.items[0].status").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.items[0].message").value(org.hamcrest.Matchers.containsString("勿重复执行")));
        mvc.perform(post("/api/mobile/{factoryId}/config/v2/ai/apply-diffs", FACTORY)
                        .header("Authorization", "Bearer local-test")
                        .header("X-Canvas-Result-Protocol", "3")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].status").value("FAILURE"));
        verify(executor, times(1)).execute(any(ToolCall.class), anyMap());
        verify(previewTokens, times(1)).resolveClaim("proof-resolve", "claim-resolve", true, "变更已完成");
    }

    @Test
    void parameterTamperResolvesClaimWithoutExecuting() throws Exception {
        IntentPreviewToken token = token(Map.of("action", "create", "processName", "persisted"));
        when(previewTokens.claimToken("proof-3", FACTORY, 7L, "digest-3"))
                .thenReturn(PreviewTokenService.ClaimResult.success(token, "claim-3",
                        Map.of("action", "create", "processName", "persisted")));

        mvc.perform(post("/api/mobile/{factoryId}/config/v2/ai/apply-diffs", FACTORY)
                        .header("Authorization", "Bearer local-test")
                        .header("X-Canvas-Result-Protocol", "3")
                        .contentType("application/json")
                        .content("[{\"tool\":\"" + TOOL + "\",\"params\":{\"action\":\"create\",\"processName\":\"tampered\"},"
                                + "\"proof\":{\"token\":\"proof-3\",\"commandDigest\":\"digest-3\"}}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].status").value("FAILURE"));
        verify(executor, never()).execute(any(), anyMap());
        verify(previewTokens).resolveClaim("proof-3", "claim-3", false, "确认内容与预览不一致，请重新生成方案");
    }

    @Test
    void descriptorDriftIsTerminalAndNeverExecutes() throws Exception {
        IntentPreviewToken token = token(Map.of("action", "create"));
        when(previewTokens.claimToken("proof-2", FACTORY, 7L, "digest-2"))
                .thenReturn(PreviewTokenService.ClaimResult.success(token, "claim-2", Map.of("action", "create")));
        when(executor.getVersion()).thenReturn("2.0.0");

        mvc.perform(post("/api/mobile/{factoryId}/config/v2/ai/apply-diffs", FACTORY)
                        .header("Authorization", "Bearer local-test")
                        .header("X-Canvas-Result-Protocol", "3")
                        .contentType("application/json")
                        .content("[{\"tool\":\"" + TOOL + "\",\"params\":{\"action\":\"create\"},"
                                + "\"proof\":{\"token\":\"proof-2\",\"commandDigest\":\"digest-2\"}}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].status").value("UNKNOWN"));
        verify(executor, never()).execute(any(), anyMap());
        verify(previewTokens).resolveClaim("proof-2", "claim-2", false, "认领后执行结果不明，请核对实际记录，勿重复执行");
    }

    @Test
    void numericJsonRoundTripUsesSemanticEquality() throws Exception {
        IntentPreviewToken token = token(Map.of("action", "create", "rate", new BigDecimal("25.00")));
        when(previewTokens.claimToken("proof-number", FACTORY, 7L, "digest-number"))
                .thenReturn(PreviewTokenService.ClaimResult.success(token, "claim-number",
                        Map.of("action", "create", "rate", new BigDecimal("25.00"))));
        when(executor.execute(any(ToolCall.class), anyMap())).thenReturn("{\"success\":true,\"data\":{}}" );

        mvc.perform(post("/api/mobile/{factoryId}/config/v2/ai/apply-diffs", FACTORY)
                        .header("Authorization", "Bearer local-test")
                        .header("X-Canvas-Result-Protocol", "3")
                        .contentType("application/json")
                        .content("[{\"tool\":\"" + TOOL + "\",\"params\":{\"action\":\"create\",\"rate\":25.0},"
                                + "\"proof\":{\"token\":\"proof-number\",\"commandDigest\":\"digest-number\"}}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].status").value("SUCCESS"));
        verify(executor).execute(any(ToolCall.class), anyMap());
    }

    @Test
    void nonCanvasDiffPurposeTokenIsRejectedBeforeExecutor() throws Exception {
        IntentPreviewToken token = token(Map.of("action", "create"));
        token.setIntentCode("AI_PRODUCT_CREATE");
        when(previewTokens.claimToken("proof-wrong-purpose", FACTORY, 7L, "digest-purpose"))
                .thenReturn(PreviewTokenService.ClaimResult.success(token, "claim-purpose", Map.of("action", "create")));

        mvc.perform(post("/api/mobile/{factoryId}/config/v2/ai/apply-diffs", FACTORY)
                        .header("Authorization", "Bearer local-test")
                        .header("X-Canvas-Result-Protocol", "3")
                        .contentType("application/json")
                        .content("[{\"tool\":\"" + TOOL + "\",\"params\":{\"action\":\"create\"},"
                                + "\"proof\":{\"token\":\"proof-wrong-purpose\",\"commandDigest\":\"digest-purpose\"}}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].status").value("FAILURE"));
        verify(executor, never()).execute(any(), anyMap());
        verify(previewTokens).resolveClaim("proof-wrong-purpose", "claim-purpose", false,
                "确认内容与预览不一致，请重新生成方案");
    }

    @ParameterizedTest
    @ValueSource(strings = {"canvas_product_process_workflow_config", "canvas_product_work_process_config"})
    void previewOnlyToolIsRejectedEvenWithAnOtherwiseMatchingClaim(String previewOnlyTool) throws Exception {
        Map<String, Object> params = Map.of("action", "create");
        IntentPreviewToken row = token(params);
        row.setToolName(previewOnlyTool);
        row.setEntityId(previewOnlyTool);
        when(previewTokens.claimToken("proof-preview-only", FACTORY, 7L, "digest-preview-only"))
                .thenReturn(PreviewTokenService.ClaimResult.success(row, "claim-preview-only", params));
        mvc.perform(post("/api/mobile/{factoryId}/config/v2/ai/apply-diffs", FACTORY)
                        .header("Authorization", "Bearer local-test")
                        .header("X-Canvas-Result-Protocol", "3")
                        .contentType("application/json")
                        .content("[{\"tool\":\"" + previewOnlyTool + "\",\"params\":{\"action\":\"create\"},"
                                + "\"proof\":{\"token\":\"proof-preview-only\",\"commandDigest\":\"digest-preview-only\"}}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].status").value("UNKNOWN"));
        verify(registry, never()).getExecutor(previewOnlyTool);
        verify(executor, never()).execute(any(), anyMap());
        verify(previewTokens).resolveClaim("proof-preview-only", "claim-preview-only", false,
                "认领后执行结果不明，请核对实际记录，勿重复执行");
    }

    private IntentPreviewToken token(Map<String, Object> params) {
        return IntentPreviewToken.builder()
                .token("persisted-token")
                .factoryId(FACTORY).tenantId(FACTORY).userId(7L)
                .intentCode("CANVAS_DIFF_123e4567-e89b-12d3-a456-426614174000")
                .entityType("CANVAS_DIFF").entityId(TOOL).operation("UPDATE")
                .executionMode(com.cretas.aims.ai.tool.gateway.ToolExecutionMode.EXECUTE)
                .toolName(TOOL).descriptorVersion("1.0.0")
                .commandDigest("persisted-digest")
                .parametersHash("persisted-parameters-hash")
                .previewData("{}")
                .build();
    }
}
