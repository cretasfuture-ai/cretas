package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.cache.IntentResultCache;
import com.cretas.aims.client.PythonAiMatcherClient;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.cache.SemanticCacheHit;
import com.cretas.aims.dto.conversation.ConversationContext;
import com.cretas.aims.dto.conversation.ConversationMessage;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.dto.intent.PythonIntentMatchRequest;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.*;
import com.cretas.aims.service.consent.LlmEgressGuard;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import com.cretas.aims.service.impl.AIIntentServiceImpl;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.cretas.aims.service.intent.IntentRecognitionPipelineService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * INTENT-DISTILL-20261111 A 线: 普通入口 {@code /ai-intents/execute} 进入识别时拿到的最近对话,
 * 与 SSE 入口是同一个解析器的同一份结果。Web 不发 recentTurns, 由本人会话记忆里的用户话补上。
 *
 * <p>全部走真实入口 {@link IntentExecutionOrchestrator#execute}; 外发载荷那条再接上真实的
 * {@link AIIntentServiceImpl}, 只在 Python HTTP 客户端那一层打桩。
 */
@DisplayName("普通入口 — 识别拿到与 SSE 同一份最近对话, 助手原文不外发")
class OrdinaryEntryRecognitionHistoryContractTest {

    private static final String FACTORY = "F006";
    private static final long USER = 1309L;
    private static final String ROLE = "factory_super_admin";
    private static final String SESSION = "web-session-1";
    private static final String FOLLOW_UP = "只看上周的";
    /** 助手上一轮的业务结果: 换回真名后的表格, 必须一个字都不出去。 */
    private static final String ASSISTANT_TABLE = "| 客户 | 联系人 | 金额 |\n| 华东冷链商贸有限公司 | 赵铁柱 13912345678 | 8,800 |";

    private AIIntentService aiIntentService;
    private SemanticCacheService semanticCache;
    private ConversationMemoryService memory;
    private IntentExecutionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        aiIntentService = mock(AIIntentService.class);
        semanticCache = mock(SemanticCacheService.class);
        when(semanticCache.queryCache(anyString(), anyString())).thenReturn(SemanticCacheHit.miss(0L));
        memory = mock(ConversationMemoryService.class);
        orchestrator = new IntentExecutionOrchestrator(
                aiIntentService,
                mock(IntentSemanticsParser.class),
                semanticCache,
                mock(RuleEngineService.class),
                mock(ConversationService.class),
                memory,
                new ObjectMapper(),
                mock(DashScopeClient.class),
                mock(DashScopeConfig.class),
                mock(IntentKnowledgeBase.class),
                mock(AIAnalysisResultRepository.class),
                mock(ToolRegistry.class),
                mock(AnalysisRouterService.class),
                mock(ComplexityRouter.class),
                mock(AgentOrchestrator.class),
                mock(AgenticRAGRouterService.class),
                mock(ResultValidatorService.class),
                mock(ToolDispatchService.class),
                mock(DynamicToolSelectionService.class),
                mock(QueryPreprocessorService.class));
        ReflectionTestUtils.setField(orchestrator, "writeGuardService", new WriteGuardService());
        BusinessTypeGate businessTypeGate = mock(BusinessTypeGate.class);
        when(businessTypeGate.check(any(), any())).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(orchestrator, "businessTypeGate", businessTypeGate);
        ReflectionTestUtils.setField(orchestrator, "recognitionHistoryResolver",
                new RecognitionHistoryResolver(memory));
        OrchestratorPriceViewTestSupport.callerSeesAllAmounts(orchestrator);
        FactoryAnalysisTestMetadata.bindNamedFactories(orchestrator);
    }

    /** Web 上一轮 (普通入口) 写进会话记忆的样子: 用户话 + 助手业务结果。 */
    private ConversationContext priorWebTurns(String factoryId, Long ownerId) {
        LocalDateTime now = LocalDateTime.now();
        List<ConversationMessage> messages = new ArrayList<>();
        messages.add(msg(ConversationMessage.Role.USER, "华东冷链商贸有限公司这个月的订单", now.minusMinutes(3)));
        messages.add(msg(ConversationMessage.Role.ASSISTANT, ASSISTANT_TABLE, now.minusMinutes(3)));
        messages.add(msg(ConversationMessage.Role.USER, "按金额从高到低", now.minusMinutes(1)));
        messages.add(msg(ConversationMessage.Role.ASSISTANT, ASSISTANT_TABLE, now.minusMinutes(1)));
        return ConversationContext.builder().sessionId(SESSION).factoryId(factoryId).userId(ownerId)
                .recentMessages(messages).build();
    }

    private static ConversationMessage msg(ConversationMessage.Role role, String content, LocalDateTime at) {
        return ConversationMessage.builder().role(role).content(content).timestamp(at).build();
    }

    private void executeAsFactoryUser(IntentExecuteRequest request) {
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, USER, FACTORY, ROLE))) {
            orchestrator.execute(FACTORY, request, USER, ROLE);
        }
    }

    private static final List<Map<String, String>> EXPECTED_USER_TURNS = List.of(
            Map.of("role", "user", "content", "华东冷链商贸有限公司这个月的订单"),
            Map.of("role", "user", "content", "按金额从高到低"));

    @Test
    @DisplayName("Web 续句: 本人会话记忆里的用户话进入识别调用, 助手结果不在其中, 不读不写按原话缓存")
    void ordinaryEntryPassesOwnedSessionUserTurnsToRecognition() {
        when(memory.getContext(SESSION)).thenReturn(priorWebTurns(FACTORY, USER));
        when(aiIntentService.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), any(), any(),
                any(), any(), anyList())).thenReturn(IntentMatchResult.empty(FOLLOW_UP));

        executeAsFactoryUser(IntentExecuteRequest.builder().userInput(FOLLOW_UP).sessionId(SESSION).build());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> history = ArgumentCaptor.forClass(List.class);
        verify(aiIntentService).recognizeIntentWithConfidence(eq(FOLLOW_UP), eq(FACTORY), eq(3), eq(USER), eq(ROLE),
                eq(SESSION), any(), any(), history.capture());
        assertThat(history.getValue()).isEqualTo(EXPECTED_USER_TURNS);
        assertThat(history.getValue().toString()).doesNotContain("赵铁柱", "13912345678", "8,800");
        verify(aiIntentService, never()).recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(),
                any(), any(), any(), any());
        verify(semanticCache, never()).queryCache(anyString(), anyString());
        verify(semanticCache, never()).cacheResult(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("阴性对照: 没有会话、没有 recentTurns → 调用形状与改动前一致 (8 参), 照常读缓存")
    void withoutHistoryTheRecognitionCallIsUnchanged() {
        when(aiIntentService.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), any(), any(),
                any(), any())).thenReturn(IntentMatchResult.empty(FOLLOW_UP));

        executeAsFactoryUser(IntentExecuteRequest.builder().userInput(FOLLOW_UP).build());

        verify(aiIntentService).recognizeIntentWithConfidence(eq(FOLLOW_UP), eq(FACTORY), eq(3), eq(USER), eq(ROLE),
                any(), any(), any());
        verify(aiIntentService, never()).recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(),
                any(), any(), any(), any(), anyList());
        verify(semanticCache).queryCache(FACTORY, FOLLOW_UP);
    }

    @Test
    @DisplayName("别人的会话 (工厂或用户对不上) 的记忆不当上下文")
    void foreignSessionMemoryIsNotUsed() {
        when(memory.getContext(SESSION)).thenReturn(priorWebTurns(FACTORY, 9999L));
        when(aiIntentService.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), any(), any(),
                any(), any())).thenReturn(IntentMatchResult.empty(FOLLOW_UP));

        executeAsFactoryUser(IntentExecuteRequest.builder().userInput(FOLLOW_UP).sessionId(SESSION).build());

        verify(aiIntentService, never()).recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(),
                any(), any(), any(), any(), anyList());
    }

    @Test
    @DisplayName("与 SSE 同一份: App 带了 recentTurns 时普通入口用同样清洗后的那份")
    void clientRecentTurnsAreUsedExactlyAsSseUsesThem() {
        List<Map<String, String>> turns = List.of(
                Map.of("role", "user", "content", "今天包装线的任务"),
                Map.of("role", "assistant", "content", "包装线今天有 3 个任务"));
        IntentExecuteRequest request = IntentExecuteRequest.builder().userInput("好了")
                .context(new java.util.HashMap<>(Map.of("recentTurns", turns))).build();
        when(aiIntentService.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), any(), any(),
                any(), any(), anyList())).thenReturn(IntentMatchResult.empty("好了"));

        executeAsFactoryUser(request);

        verify(aiIntentService).recognizeIntentWithConfidence(eq("好了"), eq(FACTORY), eq(3), eq(USER), eq(ROLE),
                any(), any(), any(), eq(SseStreamingService.recentTurns(IntentExecuteRequest.builder()
                        .context(Map.of("recentTurns", turns)).build())));
    }

    @Test
    @DisplayName("外发载荷: 普通入口 → 真实识别服务 → 发给 Python 的只有脱敏后的用户话, 助手表格一个字都没有")
    void assistantBusinessTextNeverReachesThePythonPayload() {
        ProcessSecretSampleFilter processSecret = mock(ProcessSecretSampleFilter.class);
        when(processSecret.protectedProductNames(anyString())).thenReturn(Set.of());
        PythonAiMatcherClient python = mock(PythonAiMatcherClient.class);
        IntentRecognitionPipelineService pipeline = mock(IntentRecognitionPipelineService.class);
        when(pipeline.recognizeExpectedEarlyPhrase(anyString(), anyString(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(pipeline.recognizeIntentWithConfidence(anyString(), any(), anyInt(), any(), any(), any()))
                .thenAnswer(inv -> IntentMatchResult.empty(inv.getArgument(0)));
        IntentConfigManagementService configService = mock(IntentConfigManagementService.class);
        when(configService.resolveBusinessDomain(FACTORY)).thenReturn("FACTORY");
        AIIntentServiceImpl realRecognizer = new AIIntentServiceImpl(pipeline, configService, null);
        ReflectionTestUtils.setField(realRecognizer, "pythonClient", python);
        ReflectionTestUtils.setField(realRecognizer, "intentResultCache", mock(IntentResultCache.class));
        ReflectionTestUtils.setField(realRecognizer, "usePythonMatcher", true);
        ReflectionTestUtils.setField(realRecognizer, "llmEgressGuard",
                new LlmEgressGuard(new ObjectMapper(), processSecret));
        ArgumentCaptor<PythonIntentMatchRequest> sent = ArgumentCaptor.forClass(PythonIntentMatchRequest.class);
        when(python.match(sent.capture())).thenReturn(IntentMatchResult.empty(FOLLOW_UP));
        ReflectionTestUtils.setField(orchestrator, "aiIntentService", realRecognizer);
        when(memory.getContext(SESSION)).thenReturn(priorWebTurns(FACTORY, USER));

        executeAsFactoryUser(IntentExecuteRequest.builder().userInput(FOLLOW_UP).sessionId(SESSION).build());

        PythonIntentMatchRequest payload = sent.getValue();
        assertThat(payload.getHistory()).hasSize(2)
                .allSatisfy(turn -> assertThat(turn.get("role")).isEqualTo("user"));
        assertThat(payload.getHistory().get(1).get("content")).isEqualTo("按金额从高到低");
        String wire = payload.toString();
        assertThat(wire).doesNotContain("赵铁柱", "13912345678", "8,800", "联系人", "华东冷链商贸有限公司");
    }
}
