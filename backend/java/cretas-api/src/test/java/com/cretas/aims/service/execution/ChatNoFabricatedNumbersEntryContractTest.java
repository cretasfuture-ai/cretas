package com.cretas.aims.service.execution;

import com.cretas.aims.ai.capability.FactoryCapabilityPackRegistry;
import com.cretas.aims.ai.capability.FactoryCapabilityPackRoutingPolicy;
import com.cretas.aims.ai.capability.FactoryCapabilityPackSelector;
import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.client.F006BossQuestionClient;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
import com.cretas.aims.ai.grounding.ChatBusinessFactGuard;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.config.IntentKnowledgeBase.QuestionType;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.ai.RAGRouteResult;
import com.cretas.aims.dto.cache.SemanticCacheHit;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.config.AIIntentConfig;
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
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 闲聊 / 兜底路径不许编业务数字 —— 两个产品真实入口 (普通 {@link IntentExecutionOrchestrator#execute}
 * 与 SSE {@code executeStreamAsync}) 行为一致。
 *
 * <p>prod 2026-11-14 18:16 (F006 viewer, 普通入口, correlation 0d61abf8): 「这个月卖得怎么样」短语表没有,
 * 早期问题类型 = GENERAL_QUESTION (含「怎么」), 不算分析请求 (业务词表里有「销售」没有「卖」), RAG 路由 GENERAL,
 * 落到闲聊提示词「你是白垩纪AI Agent的智能助手…」, Python /api/llm/chat (chat 槽, qwen3:8b) 回了「同比增长 8%」。
 *
 * <p>真的: 两个入口类、真实短语表 / 问题类型检测 {@link IntentKnowledgeBase}、真实护栏
 * {@link ChatBusinessFactGuard}。桩: 识别层结果、配置与权限、工具分发 (返回一段带真实数字的查询结果)、
 * 闲聊模型 (返回编造的业务数字)。
 */
@DisplayName("闲聊不编业务数字: 普通入口与流式入口一致")
class ChatNoFabricatedNumbersEntryContractTest {

    enum Entry { ORDINARY, SSE }

    private static final String FACTORY = "F006";
    private static final long USER = 1707L;
    private static final String ROLE = "viewer";
    private static final String SALES_INTENT = "REPORT_DASHBOARD_OVERVIEW";
    /** 线上原样的编造回复。 */
    private static final String FABRICATED = "这个月销售情况不错，同比增长 8%，继续保持。";
    /** 真实查询结果里的数 (工具产出), 必须原样到用户。 */
    private static final String TOOL_TEXT = "本月销售额 ¥35,000，同比增长 8%，出货 1200 箱。";

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("「这个月卖得怎么样」→ 查真实销售概况, 不进闲聊; 查询结果里的数字照常显示 (护栏不误杀)")
    void salesQuestionRunsTheRealQueryAndItsNumbersReachTheUser(Entry entry) throws Exception {
        Harness h = new Harness(entry);
        String input = "这个月卖得怎么样";
        assertThat(h.knowledgeBase.matchPhrase(input, "FACTORY"))
                .as("前提 (读数来源: 真实短语表)").contains(SALES_INTENT);

        IntentExecuteResponse shown = h.send(input);

        assertThat(h.executedIntents).as("阳性对照: 工具分发真的被调用了").containsExactly(SALES_INTENT);
        assertThat(h.chatCalls()).as("没进闲聊模型").isZero();
        assertThat(shown.getFormattedText()).as("阴性对照: 真实查询结果的数字不被护栏删").isEqualTo(TOOL_TEXT);
        assertThat(h.guard.total(h.chatSite())).as("查询结果不走闲聊护栏").isZero();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("短语表没有的业务问法 → 交给识别, 识别不出就是澄清, 闲聊模型一次都不调")
    void unmatchedBusinessQuestionBecomesClarificationNotChat(Entry entry) throws Exception {
        Harness h = new Harness(entry);
        String input = "最近良品率怎么样";
        assertThat(h.knowledgeBase.matchPhrase(input, "FACTORY")).as("前提: 短语表没有").isEmpty();
        // 早期检测按线上「这个月卖得怎么样」那次的判定 (GENERAL_QUESTION) 走: 这里测的是判定之后的分流
        org.mockito.Mockito.doReturn(QuestionType.GENERAL_QUESTION).when(h.knowledgeBase).detectQuestionType(input);
        h.chatReplies(FABRICATED);
        h.recognizerAnswers(IntentMatchResult.builder().userInput(input)
                .matchMethod(IntentMatchResult.MatchMethod.NONE).confidence(0.0)
                .questionType(QuestionType.GENERAL_QUESTION).build());

        IntentExecuteResponse shown = h.send(input);

        assertThat(h.recognitionCalls()).as("阳性对照: 识别被调用").containsExactly(input);
        assertThat(h.chatCalls()).as("业务问法没进闲聊模型").isZero();
        assertThat(shown.getStatus()).isIn("NEED_CLARIFICATION", "NO_MATCH");
        assertThat(shown.getFormattedText()).doesNotContain("8%", "同比增长");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("闲聊模型编了业务数字 → 不下发 (流式一个 token 也不出去), 换成「需要先查」, 计数 +1")
    void fabricatedNumbersInChatNeverReachTheUser(Entry entry) throws Exception {
        Harness h = new Harness(entry);
        String input = "早上好";
        assertThat(h.knowledgeBase.matchPhrase(input, "FACTORY")).as("前提: 短语表没有").isEmpty();
        assertThat(h.knowledgeBase.detectQuestionType(input)).as("前提: 早期检测送去闲聊")
                .isIn(QuestionType.GENERAL_QUESTION, QuestionType.CONVERSATIONAL);
        assertThat(ChatBusinessFactGuard.isBusinessQuestion(input)).as("前提: 不是业务问法").isFalse();
        h.chatReplies(FABRICATED);

        IntentExecuteResponse shown = h.send(input);

        assertThat(h.chatCalls()).as("阳性对照: 闲聊模型真的被调用了").isEqualTo(1);
        assertThat(shown.getFormattedText()).isEqualTo(ChatBusinessFactGuard.NEED_QUERY_REPLY);
        assertThat(h.sentTokens()).noneMatch(t -> t.contains("8%") || t.contains("同比"));
        assertThat(h.guard.count(h.chatSite(), ChatBusinessFactGuard.Decision.BLOCKED)).isEqualTo(1);
        assertThat(h.systemPrompts()).allMatch(p -> p.contains("与古生物学") && p.contains("不得给出任何业务数字"))
                .noneMatch(p -> p.contains("白垩纪AI Agent"));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("阴性对照: 常识数字的闲聊回答原样下发, 闸确实跑过")
    void commonSenseChatPassesUnchanged(Entry entry) throws Exception {
        Harness h = new Harness(entry);
        String input = "早上好";
        String reply = "早上好！冷库记得保持在 0-4℃，车间每班清洁消毒一次。";
        assertThat(h.knowledgeBase.matchPhrase(input, "FACTORY")).as("前提: 短语表没有").isEmpty();
        assertThat(h.knowledgeBase.detectQuestionType(input)).as("前提: 早期检测送去闲聊")
                .isIn(QuestionType.GENERAL_QUESTION, QuestionType.CONVERSATIONAL);
        assertThat(ChatBusinessFactGuard.isBusinessQuestion(input)).as("前提: 不是业务问法").isFalse();
        h.chatReplies(reply);

        IntentExecuteResponse shown = h.send(input);

        assertThat(h.chatCalls()).isEqualTo(1);
        assertThat(shown.getFormattedText()).isEqualTo(reply);
        assertThat(h.guard.count(h.chatSite(), ChatBusinessFactGuard.Decision.PASS)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ harness

    private static final class Harness {
        final Entry entry;
        final AIIntentService recognizer = mock(AIIntentService.class);
        final IntentConfigManagementService configService = mock(IntentConfigManagementService.class);
        final IntentPermissionGate permissionGate = mock(IntentPermissionGate.class);
        final SemanticCacheService semanticCache = mock(SemanticCacheService.class);
        final IntentKnowledgeBase knowledgeBase = spy(new IntentKnowledgeBase());
        final QueryPreprocessorService preprocessor = mock(QueryPreprocessorService.class);
        final ToolDispatchService toolDispatch = mock(ToolDispatchService.class);
        final ToolRegistry toolRegistry = mock(ToolRegistry.class);
        final DashScopeClient llm = mock(DashScopeClient.class);
        final DashScopeConfig llmConfig = mock(DashScopeConfig.class);
        final AgenticRAGRouterService rag = mock(AgenticRAGRouterService.class);
        final ChatBusinessFactGuard guard = new ChatBusinessFactGuard();
        final ConversationServiceImpl conversations;
        final List<String> executedIntents = new ArrayList<>();
        final List<String> systemPrompts = new ArrayList<>();
        SseStreamingService sse;

        Harness(Entry entry) {
            this.entry = entry;
            knowledgeBase.initDefaults();
            conversations = new ConversationServiceImpl(mock(ConversationSessionRepository.class),
                    mock(AIIntentConfigRepository.class), null, mock(DashScopeClient.class));
            ReflectionTestUtils.setField(conversations, "maxRounds", 5);
            ReflectionTestUtils.setField(conversations, "timeoutMinutes", 10);
            ReflectionTestUtils.setField(conversations, "confidenceThreshold", 0.7);
            when(configService.resolveBusinessDomain(FACTORY)).thenReturn("FACTORY");
            when(preprocessor.detectNegationVeto(anyString(), any()))
                    .thenReturn(QueryPreprocessorService.NegationKind.NONE);
            when(llmConfig.getFastModel()).thenReturn("chat-model");
            when(llmConfig.getModel()).thenReturn("chat-model");
            when(rag.route(anyString())).thenAnswer(inv -> RAGRouteResult.general(inv.getArgument(0)));
            AIIntentConfig config = AIIntentConfig.builder().intentCode(SALES_INTENT).intentName("经营概况")
                    .intentCategory("QUERY").businessType("COMMON").sensitivityLevel("LOW")
                    .requiredPermission("any:read").toolName("report_dashboard_overview").build();
            config.setIsActive(true);
            when(configService.getIntentByCode(FACTORY, SALES_INTENT)).thenReturn(Optional.of(config));
            when(recognizer.getIntentByCode(FACTORY, SALES_INTENT)).thenReturn(Optional.of(config));
            when(recognizer.getIntentByCode(SALES_INTENT)).thenReturn(Optional.of(config));
            when(recognizer.hasPermission(SALES_INTENT, ROLE)).thenReturn(true);
            when(permissionGate.check(org.mockito.ArgumentMatchers.argThat(
                    i -> i != null && SALES_INTENT.equals(i.getIntentCode())), eq(USER), eq(ROLE)))
                    .thenReturn(IntentPermissionGate.PermissionCheck.allowed());
            ToolExecutor tool = mock(ToolExecutor.class);
            when(tool.getToolName()).thenReturn("report_dashboard_overview");
            when(toolRegistry.getExecutor("report_dashboard_overview")).thenReturn(Optional.of(tool));
            when(toolDispatch.executeWithTool(any(), anyString(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
                AIIntentConfig seen = inv.getArgument(3);
                executedIntents.add(seen.getIntentCode());
                return IntentExecuteResponse.builder().intentRecognized(true).intentCode(seen.getIntentCode())
                        .status("SUCCESS").message(TOOL_TEXT).formattedText(TOOL_TEXT).build();
            });
            when(semanticCache.queryCache(anyString(), anyString())).thenReturn(SemanticCacheHit.miss(0L));
            recognizerAnswers(IntentMatchResult.builder().matchMethod(IntentMatchResult.MatchMethod.NONE)
                    .confidence(0.0).questionType(QuestionType.CONVERSATIONAL).build());
        }

        void recognizerAnswers(IntentMatchResult answer) {
            when(recognizer.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), any(), any(),
                    any(), any())).thenReturn(answer);
            when(recognizer.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), any(), any(),
                    any(), any(), any())).thenReturn(answer);
            when(recognizer.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), any(), any(),
                    any(), any(), any(), any())).thenReturn(answer);
        }

        /** 闲聊模型 (普通入口 chat / 流式入口 chatCompletionStream, token 拆成两段) 回这段话。 */
        void chatReplies(String reply) {
            when(llm.chat(anyString(), anyString())).thenAnswer(inv -> {
                systemPrompts.add(inv.getArgument(0));
                return reply;
            });
            doAnswer(inv -> {
                com.cretas.aims.ai.dto.ChatCompletionRequest req = inv.getArgument(0);
                systemPrompts.add(String.valueOf(req.getMessages().get(0).getContent()));
                Consumer<String> tokens = inv.getArgument(1);
                int half = reply.length() / 2;
                tokens.accept(reply.substring(0, half));
                tokens.accept(reply.substring(half));
                ChatCompletionResponse done = mock(ChatCompletionResponse.class);
                when(done.getContent()).thenReturn(reply);
                Consumer<ChatCompletionResponse> complete = inv.getArgument(2);
                complete.accept(done);
                return null;
            }).when(llm).chatCompletionStream(any(), any(), any());
        }

        String chatSite() {
            return entry == Entry.ORDINARY ? IntentExecutionOrchestrator.CHAT_FACT_GUARD_SITE
                    : SseStreamingService.CHAT_FACT_GUARD_SITE;
        }

        List<String> systemPrompts() {
            return systemPrompts;
        }

        long chatCalls() {
            return org.mockito.Mockito.mockingDetails(llm).getInvocations().stream()
                    .filter(i -> i.getMethod().getName().startsWith("chat"))
                    .count();
        }

        List<String> recognitionCalls() {
            return org.mockito.Mockito.mockingDetails(recognizer).getInvocations().stream()
                    .filter(i -> "recognizeIntentWithConfidence".equals(i.getMethod().getName()))
                    .map(i -> String.valueOf((Object) i.getArgument(0)))
                    .toList();
        }

        /** 流式入口发出去的 token 事件 (普通入口没有 token)。 */
        List<String> sentTokens() throws Exception {
            if (sse == null) return List.of();
            return org.mockito.Mockito.mockingDetails(sse).getInvocations().stream()
                    .filter(i -> "sendSseEvent".equals(i.getMethod().getName()) && "token".equals(i.getArgument(1)))
                    .map(i -> String.valueOf((Object) i.getArgument(2)))
                    .toList();
        }

        IntentExecuteResponse send(String input) throws Exception {
            IntentExecuteRequest request = IntentExecuteRequest.builder().userInput(input).build();
            try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(
                    new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, USER, FACTORY, ROLE))) {
                if (entry == Entry.ORDINARY) {
                    return ordinary().execute(FACTORY, request, USER, ROLE);
                }
                sse = sse();
                ReflectionTestUtils.invokeMethod(sse, "executeStreamAsync", mock(SseEmitter.class), FACTORY,
                        request, USER, ROLE, new F006BossQuestionClient.Cancellation());
                List<IntentExecuteResponse> results = org.mockito.Mockito.mockingDetails(sse).getInvocations()
                        .stream()
                        .filter(i -> "sendSseEvent".equals(i.getMethod().getName())
                                && "result".equals(i.getArgument(1)))
                        .map(i -> (IntentExecuteResponse) i.getArgument(2))
                        .toList();
                assertThat(results).as("流式入口发了且只发了一次 result").hasSize(1);
                return results.get(0);
            }
        }

        private IntentExecutionOrchestrator ordinary() {
            IntentExecutionOrchestrator orchestrator = new IntentExecutionOrchestrator(
                    recognizer, mock(IntentSemanticsParser.class), semanticCache,
                    mock(RuleEngineService.class), conversations, mock(ConversationMemoryService.class),
                    new ObjectMapper(), llm, llmConfig,
                    knowledgeBase, mock(AIAnalysisResultRepository.class), toolRegistry,
                    mock(AnalysisRouterService.class), mock(ComplexityRouter.class), mock(AgentOrchestrator.class),
                    rag, mock(ResultValidatorService.class), toolDispatch,
                    mock(DynamicToolSelectionService.class), preprocessor);
            ReflectionTestUtils.setField(orchestrator, "intentPermissionGate", permissionGate);
            ReflectionTestUtils.setField(orchestrator, "writeGuardService", new WriteGuardService());
            ReflectionTestUtils.setField(orchestrator, "configService", configService);
            ReflectionTestUtils.setField(orchestrator, "chatBusinessFactGuard", guard);
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
            SseStreamingService s = spy(new SseStreamingService(
                    recognizer, semanticCache, knowledgeBase, mock(AnalysisRouterService.class), new ObjectMapper(),
                    toolRegistry, toolDispatch, mock(DynamicToolSelectionService.class), llm, llmConfig));
            ReflectionTestUtils.setField(s, "factoryCapabilityPackRoutingPolicy", new FactoryCapabilityPackRoutingPolicy(
                    factories, new FactoryCapabilityPackSelector(new FactoryCapabilityPackRegistry()), false));
            FactoryAnalysisTestMetadata.bindNamedFactories(s);
            ReflectionTestUtils.setField(s, "writeGuardService", new WriteGuardService());
            ReflectionTestUtils.setField(s, "conversationService", conversations);
            ReflectionTestUtils.setField(s, "intentPermissionGate", permissionGate);
            ReflectionTestUtils.setField(s, "queryPreprocessorService", preprocessor);
            ReflectionTestUtils.setField(s, "chatBusinessFactGuard", guard);
            doAnswer(invocation -> null).when(s).sendSseEvent(any(), anyString(), any());
            return s;
        }
    }
}
