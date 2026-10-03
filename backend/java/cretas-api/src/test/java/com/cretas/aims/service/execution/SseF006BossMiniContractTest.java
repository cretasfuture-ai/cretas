package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.client.F006BossQuestionClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.cache.SemanticCacheHit;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.dto.conversation.ConversationContext;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.AnalysisRouterService;
import com.cretas.aims.service.ConversationMemoryService;
import com.cretas.aims.service.SemanticCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Executes the actual SSE routing path; client protocol is covered separately. */
class SseF006BossMiniContractTest {
    private static final String QUESTION = "HACCP是什么？";
    private static final String SESSION = "server-owned-session";
    private static final String CORRELATION = "a".repeat(32);
    private AIIntentService intents;
    private IntentKnowledgeBase knowledge;
    private AnalysisRouterService analysis;
    private ConversationMemoryService memory;
    private DashScopeClient cloud;
    private F006BossQuestionClient mini;
    private SseStreamingService service;
    private SseEmitter emitter;

    @BeforeEach
    void setUp() throws Exception {
        intents = mock(AIIntentService.class);
        knowledge = mock(IntentKnowledgeBase.class);
        analysis = mock(AnalysisRouterService.class);
        memory = mock(ConversationMemoryService.class);
        cloud = mock(DashScopeClient.class);
        mini = mock(F006BossQuestionClient.class);
        DashScopeConfig config = mock(DashScopeConfig.class);
        when(config.getFastModel()).thenReturn("cloud-model");
        service = spy(new SseStreamingService(intents, mock(SemanticCacheService.class), knowledge,
                analysis, new ObjectMapper(), mock(ToolRegistry.class), mock(ToolDispatchService.class),
                mock(DynamicToolSelectionService.class), cloud, config));
        emitter = mock(SseEmitter.class);
        doNothing().when(service).sendSseEvent(any(), any(), any());
        ReflectionTestUtils.setField(service, "f006BossQuestionClient", mini);
        ReflectionTestUtils.setField(service, "conversationMemoryService", memory);
        when(memory.getOrCreateContext(anyString(), anyLong(), any())).thenAnswer(call ->
                ConversationContext.builder().factoryId(call.getArgument(0)).userId(call.getArgument(1))
                        .sessionId(SESSION).build());
        when(knowledge.detectQuestionType(QUESTION)).thenReturn(IntentKnowledgeBase.QuestionType.GENERAL_QUESTION);
        when(knowledge.matchPhrase(QUESTION)).thenReturn(Optional.empty());
        when(mini.shouldRoute(any(), any(), any())).thenAnswer(call ->
                "F006".equals(call.getArgument(0)) && Long.valueOf(77).equals(call.getArgument(1))
                        && "factory_super_admin".equals(call.getArgument(2)));
        answer(new F006BossQuestionClient.Usage(12, 8, 20));
    }

    private void answer(F006BossQuestionClient.Usage usage) throws Exception {
        doAnswer(call -> {
            Consumer<F006BossQuestionClient.Metadata> meta = call.getArgument(5);
            Consumer<String> tokens = call.getArgument(6);
            meta.accept(new F006BossQuestionClient.Metadata("mac-mini", "qwen3.8:27b",
                    call.getArgument(4), CORRELATION));
            tokens.accept("食品安全建议");
            return new F006BossQuestionClient.Answer("食品安全建议", "mac-mini", "qwen3.8:27b",
                    "stop", CORRELATION, usage);
        }).when(mini).stream(anyString(), anyLong(), anyString(), anyString(), anyBoolean(), any(), any(), any());
    }

    private void execute(String factory, Long user, String role, boolean thinking) {
        ReflectionTestUtils.invokeMethod(service, "executeStreamAsync", emitter, factory,
                IntentExecuteRequest.builder().userInput(QUESTION).enableThinking(thinking)
                        .context(Map.of("factoryId", "FOREIGN", "userId", 999,
                                "role", "factory_super_admin", "model", "forged-model"))
                        .build(), user, role);
    }

    @Test
    void jwtIdentityAndServerSessionReachMiniAndOnlyTerminalAnswerCompletes() throws Exception {
        execute("F006", 77L, "factory_super_admin", true);
        verify(mini).stream(eq("F006"), eq(77L), eq("factory_super_admin"), eq(QUESTION), eq(true), any(), any(), any());
        verifyNoInteractions(cloud);
        ArgumentCaptor<Object> result = ArgumentCaptor.forClass(Object.class);
        verify(service).sendSseEvent(eq(emitter), eq("result"), result.capture());
        IntentExecuteResponse response = (IntentExecuteResponse) result.getValue();
        assertThat(response.getStatus()).isEqualTo("COMPLETED");
        assertThat(response.getSessionId()).isEqualTo(SESSION);
        assertThat(response.getFormattedText()).isEqualTo("食品安全建议");
        ArgumentCaptor<Object> complete = ArgumentCaptor.forClass(Object.class);
        verify(service).sendSseEvent(eq(emitter), eq("complete"), complete.capture());
        Map<?, ?> event = (Map<?, ?>) complete.getValue();
        assertThat(event.get("tokensUsed")).isEqualTo(20);
        assertThat(event.get("provider")).isEqualTo("mac-mini");
        assertThat(event.get("correlationId")).isEqualTo(CORRELATION);
        assertThat(event.get("sessionId")).isEqualTo(SESSION);
        verify(emitter).complete();
    }

    @Test
    void unknownUsageRemainsNullInsteadOfZero() throws Exception {
        answer(null);
        execute("F006", 77L, "factory_super_admin", false);
        ArgumentCaptor<Object> complete = ArgumentCaptor.forClass(Object.class);
        verify(service).sendSseEvent(eq(emitter), eq("complete"), complete.capture());
        Map<?, ?> event = (Map<?, ?>) complete.getValue();
        assertThat(event.get("usageAvailable")).isEqualTo(false);
        assertThat(event.containsKey("tokensUsed")).isTrue();
        assertThat(event.get("tokensUsed")).isNull();
        assertThat(event.get("usage")).isNull();
    }

    @Test
    void miniErrorCannotFallThroughToCloudOrCompletePartialAnswer() throws Exception {
        doAnswer(call -> {
            Consumer<String> tokens = call.getArgument(6);
            tokens.accept("尚未完成");
            throw new IOException("本地 AI 回答未完整完成，请重试");
        }).when(mini).stream(anyString(), anyLong(), anyString(), anyString(), anyBoolean(), any(), any(), any());
        execute("F006", 77L, "factory_super_admin", false);
        verifyNoInteractions(cloud);
        verify(service).sendSseEvent(eq(emitter), eq("error"), any());
        verify(service, never()).sendSseEvent(eq(emitter), eq("result"), any());
        verify(service, never()).sendSseEvent(eq(emitter), eq("complete"), any());
        verify(emitter).complete();
    }

    @Test
    void disconnectedTokenStopsUpstreamCallbackAndNeverCompletesAnswer() throws Exception {
        doThrow(new IOException("disconnected")).when(service).sendSseEvent(eq(emitter), eq("token"), any());
        execute("F006", 77L, "factory_super_admin", false);
        verifyNoInteractions(cloud);
        verify(service, never()).sendSseEvent(eq(emitter), eq("result"), any());
        verify(service, never()).sendSseEvent(eq(emitter), eq("complete"), any());
        verify(emitter).completeWithError(any(IOException.class));
    }

    @Test
    void otherFactoryStaysOnExistingChatProvider() throws Exception {
        execute("F007", 77L, "factory_super_admin", false);
        verify(mini, never()).stream(any(), any(), any(), any(), anyBoolean(), any(), any(), any());
        verify(cloud).chatCompletionStream(any(), any(), any());
    }

    @Test
    void otherRoleCannotUseBodyIdentityToReachMini() throws Exception {
        execute("F006", 77L, "finance_manager", false);
        verify(mini, never()).stream(any(), any(), any(), any(), anyBoolean(), any(), any(), any());
        verify(cloud).chatCompletionStream(any(), any(), any());
    }

    @Test
    void conversationalGreetingDoesNotEnterGeneralQuestionPilot() throws Exception {
        when(knowledge.detectQuestionType(QUESTION)).thenReturn(IntentKnowledgeBase.QuestionType.CONVERSATIONAL);
        execute("F006", 77L, "factory_super_admin", false);
        verifyNoInteractions(mini);
        verify(cloud).chatCompletionStream(any(), any(), any());
    }

    @Test
    void deniedSessionAndMissingPrincipalStopBeforeProviderCalls() throws Exception {
        when(memory.getOrCreateContext(anyString(), anyLong(), any())).thenReturn(null);
        execute("F006", 77L, "factory_super_admin", false);
        execute("F006", null, "factory_super_admin", false);
        verifyNoInteractions(mini, cloud);
        verify(memory, times(1)).getOrCreateContext(anyString(), anyLong(), any());
    }

    @Test
    void missingMemoryBeanCannotEchoCallerSessionOrContactMini() throws Exception {
        ReflectionTestUtils.setField(service, "conversationMemoryService", null);
        ReflectionTestUtils.invokeMethod(service, "executeStreamAsync", emitter, "F006",
                IntentExecuteRequest.builder().userInput(QUESTION).sessionId("caller-forged-session").build(),
                77L, "factory_super_admin");
        verify(mini, never()).stream(any(), any(), any(), any(), anyBoolean(), any(), any(), any());
        verifyNoInteractions(cloud);
        ArgumentCaptor<Object> result = ArgumentCaptor.forClass(Object.class);
        verify(service).sendSseEvent(eq(emitter), eq("result"), result.capture());
        assertThat(((IntentExecuteResponse) result.getValue()).getStatus()).isEqualTo("SESSION_OWNERSHIP_DENIED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign-factory", "foreign-user", "blank-session"})
    void invalidServerContextStopsBeforeAnyProvider(String invalid) {
        when(memory.getOrCreateContext(anyString(), anyLong(), any())).thenReturn(ConversationContext.builder()
                .factoryId("foreign-factory".equals(invalid) ? "F007" : "F006")
                .userId("foreign-user".equals(invalid) ? 999L : 77L)
                .sessionId("blank-session".equals(invalid) ? " " : SESSION).build());
        execute("F006", 77L, "factory_super_admin", false);
        verifyNoInteractions(mini, cloud);
    }

    @ParameterizedTest
    @ValueSource(strings = {"completion", "timeout", "error"})
    void emitterLifecycleCancelsRealHttpCallBeforeFirstToken(String event) throws Exception {
        AtomicReference<Runnable> completed = new AtomicReference<>();
        AtomicReference<Runnable> timeout = new AtomicReference<>();
        AtomicReference<Consumer<Throwable>> failed = new AtomicReference<>();
        doAnswer(call -> { completed.set(call.getArgument(0)); return null; }).when(emitter).onCompletion(any());
        doAnswer(call -> { timeout.set(call.getArgument(0)); return null; }).when(emitter).onTimeout(any());
        doAnswer(call -> { failed.set(call.getArgument(0)); return null; }).when(emitter).onError(any());
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            ReflectionTestUtils.setField(service, "f006BossQuestionClient", new F006BossQuestionClient(
                    server.url("/").toString(), "unit-test-secret", true, new OkHttpClient(), new ObjectMapper()));
            CompletableFuture<Void> execution = CompletableFuture.runAsync(() ->
                    execute("F006", 77L, "factory_super_admin", false));
            try {
                assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
                switch (event) {
                    case "completion" -> completed.get().run();
                    case "timeout" -> timeout.get().run();
                    case "error" -> failed.get().accept(new IOException("client closed"));
                    default -> throw new AssertionError(event);
                }
                execution.get(3, TimeUnit.SECONDS); // client read/call deadlines are 60/70s.
                verify(service, never()).sendSseEvent(eq(emitter), eq("token"), any());
                verify(service, never()).sendSseEvent(eq(emitter), eq("result"), any());
                verify(service, never()).sendSseEvent(eq(emitter), eq("complete"), any());
                verifyNoInteractions(cloud);
            } finally {
                if (timeout.get() != null) timeout.get().run();
                execution.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"active-food", "analysis"})
    void enabledMiniCannotInterceptActiveFoodOrAnalysis(String route) throws Exception {
        if ("active-food".equals(route)) {
            when(knowledge.matchPhrase(QUESTION)).thenReturn(Optional.of("FOOD_KNOWLEDGE_QUERY"));
            when(intents.getIntentByCode("F006", "FOOD_KNOWLEDGE_QUERY"))
                    .thenReturn(Optional.of(AIIntentConfig.builder().intentCode("FOOD_KNOWLEDGE_QUERY")
                            .isActive(true).build()));
        } else {
            when(analysis.isAnalysisRequest(eq(QUESTION), any())).thenReturn(true);
        }
        SemanticCacheService cache = (SemanticCacheService) ReflectionTestUtils.getField(service, "semanticCacheService");
        when(cache.queryCache("F006", QUESTION)).thenReturn(SemanticCacheHit.miss(0L));
        when(intents.recognizeIntentWithConfidence(eq(QUESTION), eq("F006"), eq(3), eq(77L),
                eq("factory_super_admin"), any(), any(), any(), eq(List.of()))).thenReturn(IntentMatchResult.builder()
                .matchMethod(IntentMatchResult.MatchMethod.NONE)
                .questionType(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND).build());
        execute("F006", 77L, "factory_super_admin", false);
        verifyNoInteractions(mini, cloud);
        verify(intents).recognizeIntentWithConfidence(eq(QUESTION), eq("F006"), eq(3), eq(77L),
                eq("factory_super_admin"), any(), any(), any(), eq(List.of()));
    }

    @Test
    void explicitReadToolExecutesBeforeEnabledMiniClassification() throws Exception {
        AIIntentConfig intent = AIIntentConfig.builder().intentCode("MATERIAL_BATCH_QUERY")
                .intentName("原料查询").intentCategory("DATA_QUERY").toolName("material_batch_query").build();
        when(intents.getIntentByCode("F006", "MATERIAL_BATCH_QUERY")).thenReturn(Optional.of(intent));
        when(intents.hasPermission("MATERIAL_BATCH_QUERY", "factory_super_admin")).thenReturn(true);
        ToolExecutor tool = mock(ToolExecutor.class);
        when(tool.getAccessMode()).thenReturn(ToolExecutor.AccessMode.READ);
        ToolRegistry registry = (ToolRegistry) ReflectionTestUtils.getField(service, "toolRegistry");
        when(registry.getExecutor("material_batch_query")).thenReturn(Optional.of(tool));
        BusinessTypeGate gate = mock(BusinessTypeGate.class);
        when(gate.check(anyString(), any())).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(service, "businessTypeGate", gate);
        ToolDispatchService dispatch = (ToolDispatchService) ReflectionTestUtils.getField(service, "toolDispatchService");
        when(dispatch.executeWithTool(eq(tool), eq("F006"), any(), eq(intent), eq(77L),
                eq("factory_super_admin"), any())).thenReturn(IntentExecuteResponse.builder()
                .status("SUCCESS").message("查询完成").build());
        ReflectionTestUtils.invokeMethod(service, "executeStreamAsync", emitter, "F006",
                IntentExecuteRequest.builder().userInput(QUESTION).intentCode("MATERIAL_BATCH_QUERY").build(),
                77L, "factory_super_admin");
        verify(dispatch).executeWithTool(eq(tool), eq("F006"), any(), eq(intent), eq(77L),
                eq("factory_super_admin"), any());
        verifyNoInteractions(mini, cloud, knowledge);
    }
}
