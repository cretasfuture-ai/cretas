package com.cretas.aims.service.execution;

import com.cretas.aims.controller.AIPublicDemoController;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.ConversationMemoryService;
import com.cretas.aims.service.IntentExecutorService;
import com.cretas.aims.service.LinUCBService;
import com.cretas.aims.service.ResultFormatterService;
import com.cretas.aims.service.impl.IntentExecutorServiceImpl;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.IntentSemanticsParser;
import com.cretas.aims.service.RuleEngineService;
import com.cretas.aims.service.SemanticCacheService;
import com.cretas.aims.service.ConversationService;
import com.cretas.aims.service.AnalysisRouterService;
import com.cretas.aims.service.ComplexityRouter;
import com.cretas.aims.service.AgentOrchestrator;
import com.cretas.aims.service.AgenticRAGRouterService;
import com.cretas.aims.service.ResultValidatorService;
import com.cretas.aims.service.QueryPreprocessorService;
import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.concurrent.atomic.AtomicReference;

class DemoConversationAdmissionContractTest {

    private static final String FACTORY = "F_DEMO";
    private static final String ROLE = "viewer";

    @Test
    void lowDemoReadUsesRealFacadeOrchestratorAndCentralToolDispatch() {
        AIIntentService recognizer = mock(AIIntentService.class);
        // Real memory service and entity mutations; only persistence is a scoped in-memory
        // repository. This proves wiring/round semantics, not PostgreSQL or live LLM.
        Map<String, com.cretas.aims.entity.conversation.ConversationMemory> rows = new java.util.HashMap<>();
        com.cretas.aims.repository.conversation.ConversationMemoryRepository repository =
                mock(com.cretas.aims.repository.conversation.ConversationMemoryRepository.class);
        when(repository.findBySessionId(anyString())).thenAnswer(call ->
                Optional.ofNullable(rows.get(call.getArgument(0, String.class))));
        when(repository.save(any(com.cretas.aims.entity.conversation.ConversationMemory.class)))
                .thenAnswer(call -> {
                    var entity = call.getArgument(0, com.cretas.aims.entity.conversation.ConversationMemory.class);
                    rows.put(entity.getSessionId(), entity);
                    return entity;
                });
        var memory = new com.cretas.aims.service.impl.ConversationMemoryServiceImpl(repository, null);
        ReflectionTestUtils.setField(memory, "windowSize", 6);
        ReflectionTestUtils.setField(memory, "summaryThreshold", 1000);
        ReflectionTestUtils.setField(memory, "expireMinutes", 60);
        DemoReadTool tool = new DemoReadTool();
        IntentExecutionOrchestrator orchestrator = realOrchestrator(recognizer, memory, tool);
        AIPublicDemoController controller = controller(recognizer, realFacade(orchestrator, memory));
        AIIntentConfig intent = match("DEMO_READ", "LOW").getBestMatch();
        intent.setToolName(tool.getToolName());
        when(recognizer.recognizeIntentWithConfidence(
                anyString(), eq(FACTORY), eq(1), anyLong(), eq(ROLE), anyString()))
                .thenAnswer(call -> {
                    assertThat(DemoConversationScope.allows(FACTORY,
                            call.getArgument(3, Long.class), call.getArgument(5, String.class))).isTrue();
                    return IntentMatchResult.builder().bestMatch(intent).confidence(0.99d)
                            .matchMethod(IntentMatchResult.MatchMethod.KEYWORD).build();
                });
        when(recognizer.getIntentByCode(anyString(), eq("DEMO_READ"))).thenReturn(Optional.of(intent));
        when(recognizer.hasPermission("DEMO_READ", ROLE)).thenReturn(true);

        var first = controller.executeDemo(demoRequest("查询演示数据", null),
                request("demo-real-chain")).getBody();
        assertThat(first).isNotNull();
        assertThat(first.getData().getStatus()).isEqualTo("SUCCESS");
        assertThat(first.getData().getConversationRound()).isEqualTo(1);
        String session = first.getData().getSessionId();
        assertThat(session).startsWith("demo-");
        assertThat(rows).containsKey(session);
        var owner = rows.get(session);
        assertThat(owner.getFactoryId()).isEqualTo(FACTORY);
        assertThat(owner.getUserId()).isNegative();
        assertThat(owner.getMessageCount()).isEqualTo(2);
        assertThat(DemoConversationScope.allows(FACTORY, owner.getUserId(), session)).isFalse();

        var second = controller.executeDemo(demoRequest("继续查询", session),
                request("demo-real-chain")).getBody();
        assertThat(second).isNotNull();
        assertThat(second.getData().getStatus()).isEqualTo("SUCCESS");
        assertThat(second.getData().getSessionId()).isEqualTo(session);
        assertThat(second.getData().getConversationRound()).isEqualTo(2);
        assertThat(rows).hasSize(1);
        assertThat(owner.getMessageCount()).isEqualTo(4);
        assertThat(owner.getRecentMessages()).extracting(
                com.cretas.aims.entity.conversation.ConversationMemory.MessageData::getRole)
                .containsExactly("user", "assistant", "user", "assistant");
        assertThat(tool.calls).isEqualTo(2);
        assertThat(DemoConversationScope.allows(FACTORY, owner.getUserId(), session)).isFalse();
    }

    @Test
    void lowDemoWriteMetadataStillStopsAtRealCentralWriteGuard() {
        AIIntentService recognizer = mock(AIIntentService.class);
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        DemoWriteTool tool = new DemoWriteTool();
        IntentExecutionOrchestrator orchestrator = realOrchestrator(recognizer, memory, tool);
        AIPublicDemoController controller = controller(recognizer, realFacade(orchestrator, memory));
        AIIntentConfig intent = match("DEMO_WRITE", "LOW").getBestMatch();
        intent.setToolName(tool.getToolName());
        when(recognizer.recognizeIntentWithConfidence(
                anyString(), eq(FACTORY), eq(1), anyLong(), eq(ROLE), anyString()))
                .thenReturn(IntentMatchResult.builder().bestMatch(intent).confidence(0.99d)
                        .matchMethod(IntentMatchResult.MatchMethod.KEYWORD).build());
        when(recognizer.getIntentByCode(anyString(), eq("DEMO_WRITE"))).thenReturn(Optional.of(intent));
        when(recognizer.hasPermission("DEMO_WRITE", ROLE)).thenReturn(true);

        ApiResponse<IntentExecuteResponse> body = controller.executeDemo(
                demoRequest("写入演示数据", null), request("demo-real-write")).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getData()).isNotNull();
        assertThat(body.getData().getStatus()).isEqualTo("WRITE_CONFIRM_REQUIRED");
        if (body.getData().getConfirmableAction() != null) {
            assertThat(body.getData().getConfirmableAction().getConfirmToken()).isNullOrEmpty();
        }
        if (body.getData().getMetadata() != null) {
            assertThat(body.getData().getMetadata()).doesNotContainKeys("previewToken", "confirmToken", "taskGrantId");
        }
        if (body.getData().getResultData() instanceof Map<?, ?> data) {
            assertThat(data.keySet()).noneMatch(key -> List.of("previewToken", "confirmToken", "taskGrantId").contains(key));
        }
        assertThat(tool.calls).isZero();
    }

    @Test
    void controllerCreatesServerOwnedScopeForTwoRoundsAndIgnoresUnknownClientSession() {
        AIIntentService recognizer = mock(AIIntentService.class);
        IntentExecutionOrchestrator orchestrator = mock(IntentExecutionOrchestrator.class);
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        IntentExecutorService executor = realFacade(orchestrator, memory);
        AIPublicDemoController controller = controller(recognizer, executor);
        HttpServletRequest http = request("demo-contract-positive");
        AtomicReference<Long> observedUser = new AtomicReference<>();
        AtomicReference<String> observedSession = new AtomicReference<>();
        when(recognizer.recognizeIntentWithConfidence(
                anyString(), eq(FACTORY), eq(1), anyLong(), eq(ROLE), anyString()))
                .thenAnswer(invocation -> {
                    Long userId = invocation.getArgument(3, Long.class);
                    String sessionId = invocation.getArgument(5, String.class);
                    observedUser.set(userId);
                    observedSession.set(sessionId);
                    assertThat(DemoConversationScope.allows(FACTORY, userId, sessionId)).isTrue();
                    assertThat(DemoConversationScope.allows("F_OTHER", userId, sessionId)).isFalse();
                    assertThat(DemoConversationScope.allows(FACTORY, userId - 1L, sessionId)).isFalse();
                    assertThat(DemoConversationScope.allows(FACTORY, userId, sessionId + "-forged")).isFalse();
                    return match("DEMO_READ", "LOW");
                });
        when(orchestrator.execute(anyString(), any(), anyLong(), eq(ROLE)))
                .thenAnswer(invocation -> {
                    String factory = invocation.getArgument(0);
                    Long userId = invocation.getArgument(2);
                    var request = invocation.getArgument(1,
                            com.cretas.aims.dto.ai.IntentExecuteRequest.class);
                    assertThat(DemoConversationScope.allows(factory, userId, request.getSessionId()))
                            .isTrue();
                    return IntentExecuteResponse.builder().status("FAILED").build();
                });

        AIPublicDemoController.DemoExecuteRequest first =
                demoRequest("查询演示数据", null);
        ApiResponse<IntentExecuteResponse> firstBody =
                controller.executeDemo(first, http).getBody();
        assertThat(firstBody).isNotNull();
        assertThat(firstBody.getData()).isNotNull();
        String sessionId = firstBody.getData().getSessionId();
        assertThat(sessionId).startsWith("demo-");

        AIPublicDemoController.DemoExecuteRequest second =
                demoRequest("继续查询", sessionId);
        ApiResponse<IntentExecuteResponse> secondBody =
                controller.executeDemo(second, http).getBody();
        assertThat(secondBody).isNotNull();
        assertThat(secondBody.getData().getSessionId()).isEqualTo(sessionId);

        AIPublicDemoController.DemoExecuteRequest unknown =
                demoRequest("过期会话查询", "client-forged-session");
        ApiResponse<IntentExecuteResponse> unknownBody =
                controller.executeDemo(unknown, request("demo-contract-unknown")).getBody();
        assertThat(unknownBody).isNotNull();
        assertThat(unknownBody.getData().getSessionId())
                .startsWith("demo-")
                .isNotEqualTo("client-forged-session");

        ArgumentCaptor<Long> users = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<com.cretas.aims.dto.ai.IntentExecuteRequest> requests =
                ArgumentCaptor.forClass(com.cretas.aims.dto.ai.IntentExecuteRequest.class);
        verify(orchestrator, org.mockito.Mockito.times(3))
                .execute(eq(FACTORY), requests.capture(), users.capture(), eq(ROLE));
        List<Long> userIds = users.getAllValues();
        List<com.cretas.aims.dto.ai.IntentExecuteRequest> calls = requests.getAllValues();
        assertThat(userIds).hasSize(3);
        assertThat(userIds.get(0)).isNegative().isEqualTo(userIds.get(1));
        assertThat(userIds.get(2)).isNegative().isNotEqualTo(userIds.get(0));
        assertThat(calls.get(0).getSessionId()).isEqualTo(calls.get(1).getSessionId());
        assertThat(calls.get(2).getSessionId()).isNotEqualTo(calls.get(0).getSessionId());
        assertThat(DemoConversationScope.allows(
                FACTORY, userIds.get(0), calls.get(0).getSessionId())).isFalse();
        assertThat(observedUser.get()).isEqualTo(userIds.get(2));
        assertThat(observedSession.get()).isEqualTo(calls.get(2).getSessionId());
        for (String wrongFactory : List.of("F_OTHER", "", "F_DEMO-demo")) {
            assertThat(DemoConversationScope.allows(wrongFactory, observedUser.get(), observedSession.get())).isFalse();
        }
        assertThat(DemoConversationScope.allows(FACTORY, observedUser.get() - 1L, observedSession.get())).isFalse();
        assertThat(DemoConversationScope.allows(FACTORY, observedUser.get(), "demo-forged-session")).isFalse();
    }

    @Test
    void controllerRejectsHighSensitivityBeforeExecutorAndRestoresScope() {
        AIIntentService recognizer = mock(AIIntentService.class);
        IntentExecutionOrchestrator orchestrator = mock(IntentExecutionOrchestrator.class);
        IntentExecutorService executor = realFacade(orchestrator, mock(ConversationMemoryService.class));
        AIPublicDemoController controller = controller(recognizer, executor);
        AtomicReference<Long> observedUser = new AtomicReference<>();
        AtomicReference<String> observedSession = new AtomicReference<>();
        when(recognizer.recognizeIntentWithConfidence(
                anyString(), eq(FACTORY), eq(1), anyLong(), eq(ROLE), anyString()))
                .thenAnswer(invocation -> {
                    Long userId = invocation.getArgument(3, Long.class);
                    String sessionId = invocation.getArgument(5, String.class);
                    observedUser.set(userId);
                    observedSession.set(sessionId);
                    assertThat(DemoConversationScope.allows(FACTORY, userId, sessionId)).isTrue();
                    assertThat(DemoConversationScope.allows("F_OTHER", userId, sessionId)).isFalse();
                    assertThat(DemoConversationScope.allows(FACTORY, userId - 1L, sessionId)).isFalse();
                    assertThat(DemoConversationScope.allows(FACTORY, userId, sessionId + "-forged")).isFalse();
                    return match("DEMO_WRITE", "HIGH");
                });

        ApiResponse<IntentExecuteResponse> body =
                controller.executeDemo(demoRequest("写入演示数据", null),
                        request("demo-contract-high")).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getData().getStatus()).isEqualTo("NO_PERMISSION");
        assertThat(body.getData().getSensitivityLevel()).isEqualTo("HIGH");
        verify(orchestrator, never()).execute(anyString(), any(), anyLong(), anyString());
        assertThat(observedUser.get()).isNotNull();
        assertThat(observedSession.get()).isEqualTo(body.getData().getSessionId());
        assertThat(DemoConversationScope.allows(FACTORY, observedUser.get(), observedSession.get())).isFalse();
    }

    @Test
    void controllerRestoresScopeWhenExecutorThrows() {
        AIIntentService recognizer = mock(AIIntentService.class);
        IntentExecutionOrchestrator orchestrator = mock(IntentExecutionOrchestrator.class);
        IntentExecutorService executor = realFacade(orchestrator, mock(ConversationMemoryService.class));
        AIPublicDemoController controller = controller(recognizer, executor);
        AtomicReference<Long> observedUser = new AtomicReference<>();
        AtomicReference<String> observedSession = new AtomicReference<>();
        when(recognizer.recognizeIntentWithConfidence(
                anyString(), eq(FACTORY), eq(1), anyLong(), eq(ROLE), anyString()))
                .thenAnswer(invocation -> {
                    Long userId = invocation.getArgument(3, Long.class);
                    String sessionId = invocation.getArgument(5, String.class);
                    observedUser.set(userId);
                    observedSession.set(sessionId);
                    assertThat(DemoConversationScope.allows(FACTORY, userId, sessionId)).isTrue();
                    assertThat(DemoConversationScope.allows("F_OTHER", userId, sessionId)).isFalse();
                    assertThat(DemoConversationScope.allows(FACTORY, userId - 1L, sessionId)).isFalse();
                    assertThat(DemoConversationScope.allows(FACTORY, userId, sessionId + "-forged")).isFalse();
                    return match("DEMO_READ", "LOW");
                });
        when(orchestrator.execute(anyString(), any(), anyLong(), eq(ROLE)))
                .thenAnswer(invocation -> {
                    Long userId = invocation.getArgument(2, Long.class);
                    String sessionId = invocation.getArgument(1,
                            com.cretas.aims.dto.ai.IntentExecuteRequest.class).getSessionId();
                    assertThat(DemoConversationScope.allows(FACTORY, userId, sessionId)).isTrue();
                    assertThat(DemoConversationScope.allows("F_OTHER", userId, sessionId)).isFalse();
                    assertThat(DemoConversationScope.allows(FACTORY, userId - 1L, sessionId)).isFalse();
                    assertThat(DemoConversationScope.allows(FACTORY, userId, sessionId + "-forged")).isFalse();
                    observedUser.set(userId);
                    observedSession.set(sessionId);
                    throw new IllegalStateException("contract failure");
                });

        ApiResponse<IntentExecuteResponse> body =
                controller.executeDemo(demoRequest("查询演示数据", null),
                        request("demo-contract-exception")).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getData().getStatus()).isEqualTo("FAILED");
        assertThat(body.getData().getSessionId()).startsWith("demo-");
        verify(orchestrator).execute(eq(FACTORY), any(), anyLong(), eq(ROLE));
        assertThat(observedUser.get()).isNotNull();
        assertThat(observedSession.get()).isEqualTo(body.getData().getSessionId());
        assertThat(DemoConversationScope.allows(
                FACTORY, observedUser.get(), observedSession.get())).isFalse();
    }

    private AIPublicDemoController controller(AIIntentService recognizer,
                                               IntentExecutorService executor) {
        return new AIPublicDemoController(
                recognizer, executor, mock(ResultFormatterService.class), mock(LinUCBService.class));
    }

    private IntentExecutorService realFacade(IntentExecutionOrchestrator orchestrator,
                                             ConversationMemoryService memory) {
        return new IntentExecutorServiceImpl(orchestrator, mock(SseStreamingService.class),
                mock(MultiIntentExecutionService.class), memory);
    }

    private IntentExecutionOrchestrator realOrchestrator(AIIntentService recognizer,
                                                          ConversationMemoryService memory,
                                                          ToolExecutor tool) {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getExecutor(tool.getToolName())).thenReturn(Optional.of(tool));
        when(registry.isToolEnabledForFactory(anyString(), anyString())).thenReturn(true);
        ToolCallRedundancyService redundancy = mock(ToolCallRedundancyService.class);
        when(redundancy.isRedundant(anyString(), anyString(), anyMap())).thenReturn(false);
        ToolDispatchService dispatch = new ToolDispatchService(registry, new ObjectMapper(), null,
                redundancy, mock(SelfCorrectionService.class), mock(CorrectionAgentService.class),
                mock(ExternalVerifierService.class), mock(ToolResultValidatorService.class),
                mock(ParameterExtractionLearningService.class));
        ReflectionTestUtils.setField(dispatch, "writeGuardService", new WriteGuardService());
        ToolRbacEnforcer enforcer = mock(ToolRbacEnforcer.class);
        when(enforcer.check(any(), anyMap())).thenReturn(ToolRbacEnforcer.Decision.allow());
        when(enforcer.isAllowed(any(), anyMap())).thenReturn(true);
        ReflectionTestUtils.setField(dispatch, "toolRbacEnforcer", enforcer);

        IntentExecutionOrchestrator orchestrator = new IntentExecutionOrchestrator(
                recognizer, mock(IntentSemanticsParser.class), mock(SemanticCacheService.class),
                mock(RuleEngineService.class), mock(ConversationService.class), memory,
                new ObjectMapper(), mock(DashScopeClient.class), mock(DashScopeConfig.class),
                mock(IntentKnowledgeBase.class), mock(AIAnalysisResultRepository.class), registry,
                mock(AnalysisRouterService.class), mock(ComplexityRouter.class), mock(AgentOrchestrator.class),
                mock(AgenticRAGRouterService.class), mock(ResultValidatorService.class), dispatch,
                mock(DynamicToolSelectionService.class), mock(QueryPreprocessorService.class));
        IntentPermissionGate gate = mock(IntentPermissionGate.class);
        when(gate.check(any(), any(), anyString())).thenReturn(IntentPermissionGate.PermissionCheck.allowed());
        ReflectionTestUtils.setField(orchestrator, "intentPermissionGate", gate);
        ReflectionTestUtils.setField(orchestrator, "writeGuardService", new WriteGuardService());
        IntentConfigManagementService config = mock(IntentConfigManagementService.class);
        when(config.resolveBusinessDomain(FACTORY)).thenReturn("FACTORY");
        ReflectionTestUtils.setField(orchestrator, "configService", config);
        BusinessTypeGate businessTypeGate = mock(BusinessTypeGate.class);
        when(businessTypeGate.check(anyString(), any())).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(orchestrator, "businessTypeGate", businessTypeGate);
        for (String counter : List.of("branchToolDirect", "branchSkill", "branchDynamic", "branchNoMatch")) {
            ReflectionTestUtils.setField(orchestrator, counter, new java.util.concurrent.atomic.AtomicLong());
        }
        return orchestrator;
    }

    private IntentMatchResult match(String code, String sensitivity) {
        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode(code)
                .intentName(code)
                .intentCategory("ANALYSIS")
                .businessType("FACTORY")
                .sensitivityLevel(sensitivity)
                .requiresApproval(false)
                .build();
        return IntentMatchResult.builder()
                .bestMatch(intent)
                .confidence(0.98d)
                .matchMethod(IntentMatchResult.MatchMethod.KEYWORD)
                .build();
    }

    private AIPublicDemoController.DemoExecuteRequest demoRequest(String input, String sessionId) {
        AIPublicDemoController.DemoExecuteRequest request =
                new AIPublicDemoController.DemoExecuteRequest();
        request.setUserInput(input);
        request.setSessionId(sessionId);
        return request;
    }

    private HttpServletRequest request(String remoteAddress) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn(remoteAddress);
        return request;
    }

    private static class DemoReadTool implements ToolExecutor {
        protected int calls;

        @Override public String getToolName() { return "demo_read_tool"; }
        @Override public String getDescription() { return "demo read"; }
        @Override public AccessMode getAccessMode() { return AccessMode.READ; }
        @Override public Map<String, Object> getParametersSchema() {
            return Map.of("type", "object", "properties", Map.of(), "required", List.of());
        }
        @Override public String execute(ToolCall call, Map<String, Object> context) {
            calls++;
            return "{\"success\":true,\"data\":{\"source\":\"DEMO_REAL_READ\"}}";
        }
    }

    private static final class DemoWriteTool extends DemoReadTool {
        @Override public String getToolName() { return "demo_write_tool"; }
        @Override public AccessMode getAccessMode() { return AccessMode.WRITE; }
        @Override public ActionType getActionType() { return ActionType.WRITE; }
    }
}
