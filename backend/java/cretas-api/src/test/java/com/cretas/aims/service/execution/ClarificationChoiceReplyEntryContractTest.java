package com.cretas.aims.service.execution;

import com.cretas.aims.ai.capability.FactoryCapabilityPackRegistry;
import com.cretas.aims.ai.capability.FactoryCapabilityPackRoutingPolicy;
import com.cretas.aims.ai.capability.FactoryCapabilityPackSelector;
import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.client.F006BossQuestionClient;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.cache.SemanticCacheHit;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.conversation.ConversationSession;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.repository.conversation.ConversationSessionRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.AgentOrchestrator;
import com.cretas.aims.service.AgenticRAGRouterService;
import com.cretas.aims.service.AnalysisRouterService;
import com.cretas.aims.service.ComplexityRouter;
import com.cretas.aims.service.ConversationMemoryService;
import com.cretas.aims.service.IntentSemanticsParser;
import com.cretas.aims.service.QueryPreprocessorService;
import com.cretas.aims.service.ResultValidatorService;
import com.cretas.aims.service.RuleEngineService;
import com.cretas.aims.service.SemanticCacheService;
import com.cretas.aims.service.impl.ConversationServiceImpl;
import com.cretas.aims.service.intent.FrontlineAiWriteGrantService;
import com.cretas.aims.service.intent.IntentAccessModeFilter;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 澄清之后用户回「1」「完成工序」: 两个产品真实入口 (普通 {@link IntentExecutionOrchestrator#execute} 与
 * SSE {@code executeStreamAsync}) 都要把它当成「点了那个选项」。
 *
 * <p>prod 2026-11-13 (F006): 首轮「我要报工，产量100」→ NEED_CLARIFICATION (1. 完成工序 2. 库存报表),
 * 但这一轮没建会话也没回 sessionId; 回「1」时 Java 日志 {@code 会话不存在}, 「1」被当新话走了闲聊。
 *
 * <p>真的: 编排器 / SSE 服务、ClarificationCandidateService、ConversationServiceImpl (会话表为内存桩)、W0 写确认门。
 * 桩: 识别结果 (模型)、配置与权限查询。
 */
@DisplayName("澄清后回「1」/ 选项名: 两个入口都能选中 (首轮回 sessionId; 选中 = 点了按钮, 写操作照走 W0 确认)")
class ClarificationChoiceReplyEntryContractTest {

    enum Entry { ORDINARY, SSE }

    private static final String FACTORY = "F006";
    private static final long USER = 1707L;
    private static final String ROLE = "dispatcher";
    private static final String QUESTION = "我要报工，产量100";
    private static final String WRITE = "WORK_PROCESS_TASK_COMPLETE";
    private static final String READ = "INVENTORY_REPORT";

    @ParameterizedTest(name = "{0}: 回复「{1}」")
    @CsvSource({
            "ORDINARY,1", "ORDINARY,完成工序", "ORDINARY,我选完成工序", "ORDINARY,第一个",
            "SSE,1", "SSE,完成工序", "SSE,我选完成工序", "SSE,第一个"})
    @DisplayName("首轮澄清回 sessionId; 回复序号/选项名 = 选中「完成工序」, 不再识别、直接走显式意图 (写操作停在 W0 确认)")
    void replyPicksTheOptionAndGoesToTheExplicitIntentPath(Entry entry, String reply) throws Exception {
        Harness h = new Harness(entry);
        IntentExecuteResponse clarification = h.send(QUESTION, null);

        assertThat(clarification.getStatus()).isEqualTo("NEED_CLARIFICATION");
        assertThat(clarification.getSessionId()).as("首轮澄清必须回 sessionId, 否则下一句无从对号").isNotBlank();
        assertThat(clarification.getMetadata()).containsEntry("sessionId", clarification.getSessionId());
        ConversationSession stored = h.sessions.get(clarification.getSessionId());
        assertThat(stored).as("会话真的落了库 (必然发生的读数)").isNotNull();
        assertThat(stored.getCandidates()).extracting(ConversationSession.CandidateIntent::getIntentCode)
                .containsExactly(WRITE, READ);
        clearInvocations(h.recognizer);

        IntentExecuteResponse picked = h.send(reply, clarification.getSessionId());

        assertThat(picked.getIntentCode()).isEqualTo(WRITE);
        assertThat(picked.getStatus()).isEqualTo("WRITE_CONFIRM_REQUIRED");
        assertThat(recognitionCalls(h)).as("选中不再重新识别").isEmpty();
        assertThat(h.sessions.get(clarification.getSessionId()).getStatus())
                .isEqualTo(ConversationSession.SessionStatus.COMPLETED);
        assertThat(h.lastExecutedInput).as("选中后用原话继续 (「产量100」不丢), 不是那个「1」").isEqualTo(QUESTION);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("阴性对照: 回的是一句新话 (含选项名但不是在选) → 不当选择, 照常识别; 旧选项作废")
    void aNewSentenceIsNotAChoice(Entry entry) throws Exception {
        Harness h = new Harness(entry);
        IntentExecuteResponse clarification = h.send(QUESTION, null);
        clearInvocations(h.recognizer);

        h.send("完成工序任务1830怎么还没出结果", clarification.getSessionId());

        assertThat(recognitionCalls(h)).as("新话照常识别 (阳性对照: 识别真的被调用了)")
                .containsExactly("完成工序任务1830怎么还没出结果");
        assertThat(h.sessions.get(clarification.getSessionId()).getStatus())
                .isEqualTo(ConversationSession.SessionStatus.CANCELLED);
    }

    @Test
    @DisplayName("别人的会话 id 选不中 (按工厂 + 本人查会话)")
    void anotherUsersSessionCannotBePicked() throws Exception {
        Harness h = new Harness(Entry.ORDINARY);
        IntentExecuteResponse clarification = h.send(QUESTION, null);

        var picked = h.conversations.resolveClarificationChoice(FACTORY, USER + 1, clarification.getSessionId(), "1");

        assertThat(picked).isEmpty();
        assertThat(h.sessions.get(clarification.getSessionId()).getStatus())
                .isEqualTo(ConversationSession.SessionStatus.ACTIVE);
    }

    /** 识别被调用的原话 (不论哪个重载)。 */
    private static List<String> recognitionCalls(Harness h) {
        return org.mockito.Mockito.mockingDetails(h.recognizer).getInvocations().stream()
                .filter(i -> "recognizeIntentWithConfidence".equals(i.getMethod().getName()))
                .map(i -> String.valueOf((Object) i.getArgument(0)))
                .toList();
    }

    // ------------------------------------------------------------------ harness

    private static final class Harness {
        final Entry entry;
        final Map<String, ConversationSession> sessions = new LinkedHashMap<>();
        final AIIntentService recognizer = mock(AIIntentService.class);
        final IntentConfigManagementService configService = mock(IntentConfigManagementService.class);
        final IntentPermissionGate permissionGate = mock(IntentPermissionGate.class);
        final SemanticCacheService semanticCache = mock(SemanticCacheService.class);
        final IntentKnowledgeBase knowledgeBase = mock(IntentKnowledgeBase.class);
        final ToolDispatchService toolDispatch = mock(ToolDispatchService.class);
        final ToolRegistry toolRegistry = mock(ToolRegistry.class);
        final ConversationServiceImpl conversations;
        final ClarificationCandidateService candidates;
        final List<Object[]> events = new ArrayList<>();
        String lastExecutedInput;

        Harness(Entry entry) {
            this.entry = entry;
            ConversationSessionRepository repo = inMemory();
            conversations = new ConversationServiceImpl(repo, mock(AIIntentConfigRepository.class), null,
                    mock(DashScopeClient.class));
            ReflectionTestUtils.setField(conversations, "maxRounds", 5);
            ReflectionTestUtils.setField(conversations, "timeoutMinutes", 10);
            ReflectionTestUtils.setField(conversations, "confidenceThreshold", 0.7);
            IntentAccessModeFilter accessModeFilter = mock(IntentAccessModeFilter.class);
            when(accessModeFilter.isWriteIntent(any(AIIntentConfig.class))).thenReturn(false);
            FrontlineAiWriteGrantService grants = mock(FrontlineAiWriteGrantService.class);
            when(grants.denyReason(any(), any(), any(), any())).thenReturn(Optional.empty());
            candidates = new ClarificationCandidateService(configService, permissionGate, accessModeFilter,
                    new WriteGuardService(), grants, mock(ToolRegistry.class));
            when(configService.resolveBusinessDomain(FACTORY)).thenReturn("FACTORY");
            for (String[] c : new String[][]{{WRITE, "完成工序", "PRODUCTION"}, {READ, "库存报表", "QUERY"}}) {
                AIIntentConfig config = AIIntentConfig.builder().intentCode(c[0]).intentName(c[1]).intentCategory(c[2])
                        .businessType("COMMON").sensitivityLevel("LOW").requiredPermission("any:read")
                        .toolName(WRITE.equals(c[0]) ? "work_process_task_complete" : null).build();
                config.setIsActive(true);
                when(configService.getIntentByCode(FACTORY, c[0])).thenReturn(Optional.of(config));
                when(recognizer.getIntentByCode(FACTORY, c[0])).thenReturn(Optional.of(config));
                when(recognizer.hasPermission(c[0], ROLE)).thenReturn(true);
                when(permissionGate.check(org.mockito.ArgumentMatchers.argThat(
                        i -> i != null && c[0].equals(i.getIntentCode())), eq(USER), eq(ROLE)))
                        .thenReturn(IntentPermissionGate.PermissionCheck.allowed());
            }
            // SSE 选中后走到工具分发: W0 写确认在分发里 (普通入口在编排器里), 这里只记下它收到的请求
            ToolExecutor tool = mock(ToolExecutor.class);
            when(tool.getToolName()).thenReturn("work_process_task_complete");
            when(toolRegistry.getExecutor("work_process_task_complete")).thenReturn(Optional.of(tool));
            when(toolDispatch.executeWithTool(any(), anyString(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
                IntentExecuteRequest seen = inv.getArgument(2);
                lastExecutedInput = seen.getUserInput();
                return IntentExecuteResponse.builder().intentRecognized(true).intentCode(WRITE)
                        .status("WRITE_CONFIRM_REQUIRED").build();
            });
            when(semanticCache.queryCache(anyString(), anyString())).thenReturn(SemanticCacheHit.miss(0L));
            when(knowledgeBase.detectQuestionType(anyString()))
                    .thenReturn(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND);
            // 识别 (Luna 决策) 判歧义: 两个候选、需要确认
            IntentMatchResult ambiguous = IntentMatchResult.builder()
                    .bestMatch(configService.getIntentByCode(FACTORY, WRITE).orElseThrow())
                    .topCandidates(List.of(
                            IntentMatchResult.CandidateIntent.builder().intentCode(WRITE).intentName("完成工序")
                                    .confidence(0.5).build(),
                            IntentMatchResult.CandidateIntent.builder().intentCode(READ).intentName("库存报表")
                                    .confidence(0.4).build()))
                    .confidence(0.5).matchMethod(IntentMatchResult.MatchMethod.LLM)
                    .requiresConfirmation(true)
                    .questionType(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND)
                    .userInput(QUESTION).build();
            when(recognizer.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), any(), any(),
                    any(), any())).thenReturn(ambiguous);
            when(recognizer.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), any(), any(),
                    any(), any(), any())).thenReturn(ambiguous);
            when(recognizer.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), any(), any(),
                    any(), any(), any(), any())).thenReturn(ambiguous);
        }

        IntentExecuteResponse send(String input, String sessionId) throws Exception {
            IntentExecuteRequest request = IntentExecuteRequest.builder().userInput(input).sessionId(sessionId).build();
            try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(
                    new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, USER, FACTORY, ROLE))) {
                if (entry == Entry.ORDINARY) {
                    IntentExecuteResponse response = ordinary().execute(FACTORY, request, USER, ROLE);
                    lastExecutedInput = request.getUserInput();
                    return response;
                }
                events.clear();
                SseStreamingService sse = sse();
                ReflectionTestUtils.invokeMethod(sse, "executeStreamAsync", mock(SseEmitter.class), FACTORY,
                        request, USER, ROLE, new F006BossQuestionClient.Cancellation());
            }
            return events.stream().filter(e -> "result".equals(e[0]))
                    .map(e -> (IntentExecuteResponse) e[1]).reduce((a, b) -> b)
                    .orElseThrow(() -> new AssertionError("no SSE result; events="
                            + events.stream().map(e -> e[0] + "=" + e[1]).toList()));
        }

        private IntentExecutionOrchestrator ordinary() {
            IntentExecutionOrchestrator orchestrator = new IntentExecutionOrchestrator(
                    recognizer, mock(IntentSemanticsParser.class), semanticCache,
                    mock(RuleEngineService.class), conversations, mock(ConversationMemoryService.class),
                    new ObjectMapper(), mock(DashScopeClient.class), mock(DashScopeConfig.class),
                    knowledgeBase, mock(AIAnalysisResultRepository.class), toolRegistry,
                    mock(AnalysisRouterService.class), mock(ComplexityRouter.class), mock(AgentOrchestrator.class),
                    mock(AgenticRAGRouterService.class), mock(ResultValidatorService.class), toolDispatch,
                    mock(DynamicToolSelectionService.class), mock(QueryPreprocessorService.class));
            ReflectionTestUtils.setField(orchestrator, "intentPermissionGate", permissionGate);
            ReflectionTestUtils.setField(orchestrator, "writeGuardService", new WriteGuardService());
            ReflectionTestUtils.setField(orchestrator, "configService", configService);
            ReflectionTestUtils.setField(orchestrator, "clarificationCandidateService", candidates);
            BusinessTypeGate businessTypeGate = mock(BusinessTypeGate.class);
            when(businessTypeGate.check(anyString(), any())).thenReturn(Optional.empty());
            ReflectionTestUtils.setField(orchestrator, "businessTypeGate", businessTypeGate);
            OrchestratorPriceViewTestSupport.callerSeesAllAmounts(orchestrator);
            FactoryAnalysisTestMetadata.bindNamedFactories(orchestrator);
            return orchestrator;
        }

        private SseStreamingService sse() throws Exception {
            FactoryRepository factories = mock(FactoryRepository.class);
            Factory factory = new Factory();
            factory.setId(FACTORY);
            factory.setType(FactoryType.FACTORY);
            factory.setIsActive(true);
            when(factories.findById(anyString())).thenReturn(Optional.of(factory));
            SseStreamingService sse = spy(new SseStreamingService(
                    recognizer, semanticCache, knowledgeBase, mock(AnalysisRouterService.class), new ObjectMapper(),
                    toolRegistry, toolDispatch, mock(DynamicToolSelectionService.class),
                    mock(DashScopeClient.class), mock(DashScopeConfig.class)));
            ReflectionTestUtils.setField(sse, "factoryCapabilityPackRoutingPolicy", new FactoryCapabilityPackRoutingPolicy(
                    factories, new FactoryCapabilityPackSelector(new FactoryCapabilityPackRegistry()), false));
            FactoryAnalysisTestMetadata.bindNamedFactories(sse);
            ReflectionTestUtils.setField(sse, "writeGuardService", new WriteGuardService());
            ReflectionTestUtils.setField(sse, "clarificationCandidateService", candidates);
            ReflectionTestUtils.setField(sse, "conversationService", conversations);
            doAnswer(invocation -> {
                events.add(new Object[]{invocation.getArgument(1), invocation.getArgument(2)});
                return null;
            }).when(sse).sendSseEvent(any(), anyString(), any());
            return sse;
        }

        private ConversationSessionRepository inMemory() {
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
            return repo;
        }
    }
}
