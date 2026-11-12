package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.cache.IntentResultCache;
import com.cretas.aims.client.PythonAiMatcherClient;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.cache.SemanticCacheHit;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.dto.intent.PythonIntentMatchRequest;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.conversation.ConversationSession;
import com.cretas.aims.entity.conversation.ConversationSession.SessionStatus;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.repository.conversation.ConversationSessionRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.*;
import com.cretas.aims.service.consent.LlmEgressGuard;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import com.cretas.aims.service.impl.AIIntentServiceImpl;
import com.cretas.aims.service.impl.ConversationServiceImpl;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.cretas.aims.service.intent.IntentRecognitionPipelineService;
import com.cretas.aims.service.secrecy.FactorySecrecyConfig;
import com.cretas.aims.service.secrecy.SecrecyItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 普通入口 (Web {@code /ai-intents/execute}) 上的任务状态, 全部走真实入口 {@link IntentExecutionOrchestrator#execute}
 * (终审 B1 / S1 / S2)。真的: 编排器、ConversationServiceImpl (续轮)、ConversationTaskStateService、
 * 外发那条用真实 AIIntentServiceImpl + LlmEgressGuard。桩: 会话表 (内存)、识别结果、Python HTTP 客户端。
 *
 * <p>Web 客户端打断之后<b>一直带着旧的 sessionId</b> (AIQuery.vue 只在收到新 sessionId 时换), 四轮都用同一个 id。
 */
@DisplayName("普通入口 — 打断即挂起、旧 sessionId 下「继续」能恢复、补齐后照常走显式意图; 摘要外发脱敏")
class OrdinaryEntryTaskStateContractTest {

    private static final String F = "F006";
    private static final long USER = 1686L;
    private static final String ROLE = "operator";
    private static final String INTENT = "WORK_PROCESS_TASK_COMPLETE";

    private final Map<String, ConversationSession> sessions = new LinkedHashMap<>();
    private AIIntentService aiIntentService;
    private ToolDispatchService toolDispatchService;
    private ConversationTaskStateService taskState;
    private IntentExecutionOrchestrator orchestrator;
    private IntentKnowledgeBase knowledgeBase;

    @BeforeEach
    void setUp() {
        aiIntentService = mock(AIIntentService.class);
        toolDispatchService = mock(ToolDispatchService.class);
        knowledgeBase = mock(IntentKnowledgeBase.class);
        SemanticCacheService semanticCache = mock(SemanticCacheService.class);
        when(semanticCache.queryCache(anyString(), anyString())).thenReturn(SemanticCacheHit.miss(0L));
        ConversationSessionRepository repo = inMemorySessions();
        ConversationServiceImpl conversations = new ConversationServiceImpl(
                repo, mock(AIIntentConfigRepository.class), null, mock(DashScopeClient.class));
        ReflectionTestUtils.setField(conversations, "maxRounds", 5);
        ReflectionTestUtils.setField(conversations, "timeoutMinutes", 10);
        ReflectionTestUtils.setField(conversations, "confidenceThreshold", 0.7);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        orchestrator = new IntentExecutionOrchestrator(
                aiIntentService, mock(IntentSemanticsParser.class), semanticCache, mock(RuleEngineService.class),
                conversations, mock(ConversationMemoryService.class), new ObjectMapper(),
                mock(DashScopeClient.class), mock(DashScopeConfig.class), knowledgeBase,
                mock(AIAnalysisResultRepository.class), toolRegistry, mock(AnalysisRouterService.class),
                mock(ComplexityRouter.class), mock(AgentOrchestrator.class), mock(AgenticRAGRouterService.class),
                mock(ResultValidatorService.class), toolDispatchService, mock(DynamicToolSelectionService.class),
                mock(QueryPreprocessorService.class));
        ReflectionTestUtils.setField(orchestrator, "writeGuardService", new WriteGuardService());
        BusinessTypeGate businessTypeGate = mock(BusinessTypeGate.class);
        when(businessTypeGate.check(any(), any())).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(orchestrator, "businessTypeGate", businessTypeGate);
        OrchestratorPriceViewTestSupport.callerSeesAllAmounts(orchestrator);
        FactoryAnalysisTestMetadata.bindNamedFactories(orchestrator);

        taskState = new ConversationTaskStateService(repo);
        taskState.setEnabled(true);
        ReflectionTestUtils.setField(orchestrator, "taskStateService", taskState);

        ToolExecutor tool = mock(ToolExecutor.class);
        when(tool.getToolName()).thenReturn("work_process_task_complete");
        when(tool.getAccessMode()).thenReturn(ToolExecutor.AccessMode.WRITE);
        when(toolRegistry.getExecutor("work_process_task_complete")).thenReturn(Optional.of(tool));
        AIIntentConfig intent = AIIntentConfig.builder().intentCode(INTENT).intentName("完成工序")
                .intentCategory("PRODUCTION").businessType("FACTORY").sensitivityLevel("LOW").isActive(true)
                .toolName("work_process_task_complete").build();
        when(aiIntentService.getIntentByCode(F, INTENT)).thenReturn(Optional.of(intent));
        when(aiIntentService.hasPermission(INTENT, ROLE)).thenReturn(true);
        when(aiIntentService.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), any(), any(),
                any(), any())).thenAnswer(inv -> IntentMatchResult.empty(inv.getArgument(0)));
        when(aiIntentService.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), any(), any(),
                any(), any(), anyList(), anyMap())).thenAnswer(inv -> IntentMatchResult.empty(inv.getArgument(0)));

        // 第 0 轮的结果: 报工问到一半 (缺任务号, 产量已说了 0) —— 与 SlotFillingService 建的会话同形
        ConversationService.ConversationResponse started = conversations.startParameterCollection(F, USER, INTENT,
                "完成工序", List.of(ConversationService.RequiredParameter.builder()
                        .name("taskId").label("工序任务ID").type("INTEGER").build()),
                List.of(), Map.of("actualQuantity", 0));
        sessionId = started.getSessionId();
    }

    private String sessionId;

    @Test
    @DisplayName("四轮同一个旧 sessionId: 打断+查询 → 「继续」恢复 → 「1827」补齐 → 显式意图 (产量 0 不丢)")
    void webKeepsTheOldSessionIdAndStillResumes() {
        // 第 1 轮: 插一句查询 → 报工挂起, 这句照常识别 (带任务状态摘要)
        execute("先看下库存，回头再报工");
        assertThat(sessions.get(sessionId).getStatus()).isEqualTo(SessionStatus.SUSPENDED);
        verify(aiIntentService).recognizeIntentWithConfidence(eq("先看下库存，回头再报工"), eq(F), eq(3), eq(USER),
                eq(ROLE), any(), any(), any(), anyList(), anyMap());

        // 第 2 轮: 仍带旧 sessionId 说「继续」 —— 不能落进 continueConversation 得到「对话状态异常」
        IntentExecuteResponse resumed = execute("继续");
        assertThat(resumed.getStatus()).isEqualTo("CONVERSATION_CONTINUE");
        assertThat(resumed.getSessionId()).isEqualTo(sessionId);
        assertThat(resumed.getMessage()).contains("完成工序").contains("工序任务ID").doesNotContain("对话状态异常");
        assertThat(sessions.get(sessionId).getStatus()).isEqualTo(SessionStatus.ACTIVE);
        verify(aiIntentService, never()).recognizeIntentWithConfidence(eq("继续"), anyString(), anyInt(), any(),
                any(), any(), any(), any(), anyList(), anyMap());
        verify(toolDispatchService, never()).executeWithTool(any(), any(), any(), any(), any(), any(), any());

        // 第 3 轮: 补齐任务号 → 会话完成, 走显式意图 (写确认由那条路负责), 挂起前的产量还在
        execute("1827");
        ConversationSession done = sessions.get(sessionId);
        assertThat(done.getStatus()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(done.getCollectedParameters()).containsEntry("taskId", "1827").containsEntry("actualQuantity", "0");
        verify(aiIntentService, never()).recognizeIntentWithConfidence(eq("1827"), anyString(), anyInt(), any(),
                any(), any(), any(), any());
        verify(aiIntentService, never()).recognizeIntentWithConfidence(eq("1827"), anyString(), anyInt(), any(),
                any(), any(), any(), any(), anyList(), anyMap());
    }

    @Test
    @DisplayName("S1: 打断任务的那一句不会在同一轮里把它恢复 (「继续完成工序1830」的 1830 不被吞)")
    void theInterruptingSentenceDoesNotResumeInTheSameTurn() {
        when(knowledgeBase.matchPhrase(anyString())).thenReturn(Optional.of(INTENT));

        IntentExecuteResponse response = execute("继续完成工序任务1830");

        assertThat(sessions.get(sessionId).getStatus()).isEqualTo(SessionStatus.SUSPENDED);
        assertThat(response.getStatus()).isNotEqualTo("CONVERSATION_CONTINUE");
        assertThat(sessions.get(sessionId).getCollectedParameters()).doesNotContainKey("taskId");
    }

    @Test
    @DisplayName("S1 (只有「同轮不恢复」这一道挡): 任务正问着时说「继续完成工序」→ 只挂起, 这一轮不恢复")
    void aValidResumeSentenceSaidWhileTheTaskIsOpenOnlySuspendsInThatTurn() {
        IntentExecuteResponse response = execute("继续完成工序");

        assertThat(response.getStatus()).isNotEqualTo("CONVERSATION_CONTINUE");
        assertThat(sessions.get(sessionId).getStatus()).isEqualTo(SessionStatus.SUSPENDED);
        verify(aiIntentService).recognizeIntentWithConfidence(eq("继续完成工序"), eq(F), eq(3), eq(USER),
                eq(ROLE), any(), any(), any(), anyList(), anyMap());

        // 下一轮再说同一句 (这时没有正在问的任务) → 恢复
        assertThat(execute("继续完成工序").getStatus()).isEqualTo("CONVERSATION_CONTINUE");
        assertThat(sessions.get(sessionId).getStatus()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    @DisplayName("S1: 否定说法不恢复 (「继续，别报工了」; 短语表按包含命中「报工」也不行)")
    void negationDoesNotResume() {
        when(knowledgeBase.matchPhrase(anyString())).thenAnswer(inv ->
                String.valueOf((Object) inv.getArgument(0)).contains("报工") ? Optional.of(INTENT) : Optional.empty());
        execute("先看下库存，回头再报工");
        IntentExecuteResponse response = execute("继续，别报工了");
        assertThat(response.getStatus()).isNotEqualTo("CONVERSATION_CONTINUE");
        assertThat(sessions.get(sessionId).getStatus()).isEqualTo(SessionStatus.SUSPENDED);
    }

    @Test
    @DisplayName("N1: 只说意图短语 (没有「继续 / 回到」) 不恢复, 当一次新请求")
    void aBarePhraseDoesNotResume() {
        when(knowledgeBase.matchPhrase("完成工序")).thenReturn(Optional.of(INTENT));
        execute("先看下库存，回头再报工");
        IntentExecuteResponse response = execute("完成工序");
        assertThat(response.getStatus()).isNotEqualTo("CONVERSATION_CONTINUE");
        assertThat(sessions.get(sessionId).getStatus()).isEqualTo(SessionStatus.SUSPENDED);
    }

    // ---------------- S2: 摘要外发走真实识别服务 + 真实外发守卫 ----------------

    @Test
    @DisplayName("S2: 出外脱敏 (c) 开 → 参数里的 11 位手机号被换掉; 关 → 原样; 工艺保密 (a) 关 → 数字照发")
    void taskStateValuesGoThroughTheRealEgressGuard() {
        String phone = "13912345678";
        ConversationSession task = sessions.get(sessionId);
        task.addCollectedParameter("contactPhone", phone);

        PythonIntentMatchRequest masked = sendSummaryThroughRealRecognizer(true, false);
        String maskedWire = String.valueOf(masked.getTaskState());
        assertThat(maskedWire).contains("WORK_PROCESS_TASK_COMPLETE").contains("contactPhone")
                .doesNotContain(phone);
        assertThat(maskedWire).as("工艺保密关: 产量这个数字照发").contains("actualQuantity=0");

        PythonIntentMatchRequest clear = sendSummaryThroughRealRecognizer(false, false);
        assertThat(String.valueOf(clear.getTaskState())).contains(phone).contains("actualQuantity=0");

        PythonIntentMatchRequest secret = sendSummaryThroughRealRecognizer(false, true);
        assertThat(String.valueOf(secret.getTaskState())).as("工艺保密开: 数字一律「已填」")
                .doesNotContain(phone).doesNotContain("actualQuantity=0").contains("actualQuantity=已填");
    }

    /** 第 1 轮 (打断 + 新话识别) 走真实 AIIntentServiceImpl + LlmEgressGuard, 返回发给 Python 的请求体。 */
    private PythonIntentMatchRequest sendSummaryThroughRealRecognizer(boolean egressRedaction, boolean processSecret) {
        // 每次都从「报工问到一半」开始
        sessions.get(sessionId).setStatus(SessionStatus.ACTIVE);
        sessions.get(sessionId).setLastActiveAt(LocalDateTime.now());
        FactorySecrecyConfig secrecy = mock(FactorySecrecyConfig.class);
        when(secrecy.isOn(eq(F), eq(SecrecyItem.EGRESS_REDACTION))).thenReturn(egressRedaction);
        when(secrecy.isOn(eq(F), eq(SecrecyItem.PROCESS_SECRET))).thenReturn(processSecret);
        ReflectionTestUtils.setField(taskState, "secrecy", secrecy);

        ProcessSecretSampleFilter secretFilter = mock(ProcessSecretSampleFilter.class);
        when(secretFilter.protectedProductNames(anyString())).thenReturn(Set.of());
        LlmEgressGuard guard = new LlmEgressGuard(new ObjectMapper(), secretFilter);
        guard.setFactorySecrecyConfig(secrecy);
        PythonAiMatcherClient python = mock(PythonAiMatcherClient.class);
        ArgumentCaptor<PythonIntentMatchRequest> sent = ArgumentCaptor.forClass(PythonIntentMatchRequest.class);
        when(python.match(sent.capture())).thenAnswer(inv -> IntentMatchResult.empty("x"));
        IntentRecognitionPipelineService pipeline = mock(IntentRecognitionPipelineService.class);
        when(pipeline.recognizeExpectedEarlyPhrase(anyString(), anyString(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(pipeline.recognizeIntentWithConfidence(anyString(), any(), anyInt(), any(), any(), any()))
                .thenAnswer(inv -> IntentMatchResult.empty(inv.getArgument(0)));
        IntentConfigManagementService configService = mock(IntentConfigManagementService.class);
        when(configService.resolveBusinessDomain(F)).thenReturn("FACTORY");
        AIIntentServiceImpl realRecognizer = new AIIntentServiceImpl(pipeline, configService, null);
        ReflectionTestUtils.setField(realRecognizer, "pythonClient", python);
        ReflectionTestUtils.setField(realRecognizer, "intentResultCache", mock(IntentResultCache.class));
        ReflectionTestUtils.setField(realRecognizer, "usePythonMatcher", true);
        ReflectionTestUtils.setField(realRecognizer, "llmEgressGuard", guard);
        ReflectionTestUtils.setField(orchestrator, "aiIntentService", realRecognizer);

        execute("先看下库存，回头再报工");

        assertThat(sent.getAllValues()).as("Python 真的被调用了 (必然发生的读数)").isNotEmpty();
        PythonIntentMatchRequest payload = sent.getValue();
        assertThat(payload.getTaskState()).as("摘要随请求体外发").isNotNull();
        return payload;
    }

    private IntentExecuteResponse execute(String input) {
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, USER, F, ROLE))) {
            return orchestrator.execute(F, IntentExecuteRequest.builder().userInput(input).sessionId(sessionId)
                    .build(), USER, ROLE);
        }
    }

    private ConversationSessionRepository inMemorySessions() {
        ConversationSessionRepository repo = mock(ConversationSessionRepository.class);
        when(repo.save(any(ConversationSession.class))).thenAnswer(inv -> {
            ConversationSession s = inv.getArgument(0);
            if (s.getSessionId() == null) s.setSessionId(UUID.randomUUID().toString());
            if (s.getLastActiveAt() == null) s.setLastActiveAt(LocalDateTime.now());
            sessions.put(s.getSessionId(), s);
            return s;
        });
        when(repo.findBySessionIdAndFactoryIdAndUserId(anyString(), anyString(), anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(sessions.get((String) inv.getArgument(0)))
                        .filter(s -> s.getFactoryId().equals(inv.getArgument(1))
                                && s.getUserId().equals(inv.getArgument(2))));
        when(repo.findSuspendedByFactoryAndUser(anyString(), anyLong())).thenAnswer(inv -> sessions.values().stream()
                .filter(s -> s.getStatus() == SessionStatus.SUSPENDED && s.getFactoryId().equals(inv.getArgument(0))
                        && s.getUserId().equals(inv.getArgument(1))).toList());
        return repo;
    }
}
