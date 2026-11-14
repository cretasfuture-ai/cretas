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
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 「我要报工，产量100」这类话两个产品真实入口 (普通 {@link IntentExecutionOrchestrator#execute} 与 SSE
 * {@code executeStreamAsync}) 都必须稳定落到「完成工序(报工)」, 不进意图澄清。
 *
 * <p>prod 2026-11-14 (F006): 第一句有时被决策层 (Luna) 判成别的、反问「您想做哪个: 1. 完成工序 2. 生产耗料查询」。
 * 修法 = 短语表里补「我要报工 / 报工，产量」等整句说法 (两个入口同一张表)。
 *
 * <p>真的: 两个入口类、真实短语表 {@link IntentKnowledgeBase}。桩: 决策层识别结果 (一律回别的意图,
 * 模拟线上决策层的分歧答案)、配置与权限查询、工具分发 (只记下它收到的意图)。
 */
@DisplayName("报工说法: 两个入口都稳定落到完成工序, 查询类说法不被抢")
class ReportWorkPhraseShortcutEntryContractTest {

    enum Entry { ORDINARY, SSE }

    private static final String FACTORY = "F006";
    private static final long USER = 1707L;
    private static final String ROLE = "viewer";
    private static final String PHRASE_INTENT = "WORK_PROCESS_TASK_COMPLETE";
    private static final String DECISION_INTENT = "REPORT_PRODUCTION";

    @ParameterizedTest(name = "{0}: 「{1}」")
    @CsvSource({
            "ORDINARY,我要报工，产量100", "SSE,我要报工，产量100",
            "ORDINARY,我要报工", "SSE,我要报工",
            "ORDINARY,我想报工，做了100斤", "SSE,我想报工，做了100斤",
            "ORDINARY,报工，产量100", "SSE,报工，产量100"})
    @DisplayName("报工说法命中短语表 → 两个入口都执行完成工序, 不问决策层 (不再被反问「您想做哪个」)")
    void reportWorkPhraseWinsOverTheDecisionLayerOnBothEntries(Entry entry, String input) throws Exception {
        Harness h = new Harness(entry);
        String code = h.knowledgeBase.matchPhrase(input, "FACTORY").orElseThrow();
        assertThat(code).as("前提: 这句话在短语表里 (读数来源: 真实 IntentKnowledgeBase)").isEqualTo(PHRASE_INTENT);

        h.send(input);

        if (entry == Entry.ORDINARY) {
            // 报工是写操作: 普通入口先过写确认闸 (WRITE_CONFIRM_REQUIRED, App 再调预览出确认卡), 不会直接执行;
            // 这里要证明的是「落到了完成工序」, 阳性对照 = 确认闸确实拦了、没有任何工具被直接执行
            assertThat(h.ordinaryResponse).as("普通入口有回执").isNotNull();
            assertThat(h.ordinaryResponse.getIntentCode()).isEqualTo(PHRASE_INTENT);
            assertThat(h.ordinaryResponse.getStatus()).isEqualTo("WRITE_CONFIRM_REQUIRED");
            assertThat(h.executedIntents).as("写操作没被直接执行").isEmpty();
        } else {
            assertThat(h.executedIntents).as("阳性对照: 工具分发真的被调用了").hasSize(1);
            assertThat(h.executedIntents).containsExactly(PHRASE_INTENT);
        }
        assertThat(h.recognitionCalls()).as("短语命中不再去识别").isEmpty();
    }

    @ParameterizedTest(name = "否定说法「{0}」不被短语抢走 (流式)")
    @CsvSource({"不要报工了", "我不想报工", "别报工"})
    @DisplayName("阴性对照: 否定说法不走短语快捷通道, 交给识别管线")
    void negatedReportWorkIsLeftToRecognition(String input) throws Exception {
        Harness h = new Harness(Entry.SSE);
        when(h.preprocessor.detectNegationVeto(anyString(), any()))
                .thenReturn(QueryPreprocessorService.NegationKind.VETO_WRITE);

        h.send(input);

        assertThat(h.recognitionCalls()).as("没被短语抢 → 识别被调用 (阳性对照)").containsExactly(input);
        assertThat(h.executedIntents).doesNotContain(PHRASE_INTENT);
    }

    @ParameterizedTest(name = "查询类说法「{0}」仍不是报工")
    @CsvSource({"报产量100", "报工情况", "报工查询", "查看报工记录", "报工详情"})
    @DisplayName("阴性对照: 报产量 (产量查询) / 报工情况 / 报工记录 这些查询说法不被新短语抢成「完成工序」")
    void queryPhrasesAreNotStolenByReportWork(String input) {
        IntentKnowledgeBase kb = new IntentKnowledgeBase();
        kb.initDefaults();
        assertThat(kb.matchPhrase(input, "FACTORY").orElse("(无)")).isNotEqualTo(PHRASE_INTENT);
    }

    // ------------------------------------------------------------------ harness

    private static final class Harness {
        final Entry entry;
        final AIIntentService recognizer = mock(AIIntentService.class);
        final IntentConfigManagementService configService = mock(IntentConfigManagementService.class);
        final IntentPermissionGate permissionGate = mock(IntentPermissionGate.class);
        final SemanticCacheService semanticCache = mock(SemanticCacheService.class);
        final IntentKnowledgeBase knowledgeBase = new IntentKnowledgeBase();
        final QueryPreprocessorService preprocessor = mock(QueryPreprocessorService.class);
        final ToolDispatchService toolDispatch = mock(ToolDispatchService.class);
        final ToolRegistry toolRegistry = mock(ToolRegistry.class);
        final ConversationServiceImpl conversations;
        final List<String> executedIntents = new ArrayList<>();
        IntentExecuteResponse ordinaryResponse;

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
            AIIntentConfig phrase = null;
            AIIntentConfig decision = null;
            for (String[] c : new String[][]{{PHRASE_INTENT, "完成工序", "work_process_task_complete"},
                    {DECISION_INTENT, "生产报表", "report_production"}}) {
                AIIntentConfig config = AIIntentConfig.builder().intentCode(c[0]).intentName(c[1])
                        .intentCategory("QUERY").businessType("COMMON").sensitivityLevel("LOW")
                        .requiredPermission("any:read").toolName(c[2]).build();
                config.setIsActive(true);
                when(configService.getIntentByCode(FACTORY, c[0])).thenReturn(Optional.of(config));
                when(recognizer.getIntentByCode(FACTORY, c[0])).thenReturn(Optional.of(config));
                when(recognizer.getIntentByCode(c[0])).thenReturn(Optional.of(config));
                when(recognizer.hasPermission(c[0], ROLE)).thenReturn(true);
                when(permissionGate.check(org.mockito.ArgumentMatchers.argThat(
                        i -> i != null && c[0].equals(i.getIntentCode())), org.mockito.ArgumentMatchers.eq(USER),
                        org.mockito.ArgumentMatchers.eq(ROLE)))
                        .thenReturn(IntentPermissionGate.PermissionCheck.allowed());
                ToolExecutor tool = mock(ToolExecutor.class);
                when(tool.getToolName()).thenReturn(c[2]);
                when(toolRegistry.getExecutor(c[2])).thenReturn(Optional.of(tool));
                if (DECISION_INTENT.equals(c[0])) decision = config;
            }
            when(toolDispatch.executeWithTool(any(), anyString(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
                AIIntentConfig seen = inv.getArgument(3);
                executedIntents.add(seen.getIntentCode());
                return IntentExecuteResponse.builder().intentRecognized(true).intentCode(seen.getIntentCode())
                        .status("SUCCESS").build();
            });
            when(semanticCache.queryCache(anyString(), anyString())).thenReturn(SemanticCacheHit.miss(0L));
            // 决策层 (Python/Luna) 对「库存」类话的分歧答案: 原料批次查询
            IntentMatchResult decisionAnswer = IntentMatchResult.builder().bestMatch(decision)
                    .confidence(0.8).matchMethod(IntentMatchResult.MatchMethod.LLM)
                    .requiresConfirmation(false)
                    .questionType(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND).build();
            when(recognizer.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), any(), any(),
                    any(), any())).thenReturn(decisionAnswer);
            when(recognizer.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), any(), any(),
                    any(), any(), any())).thenReturn(decisionAnswer);
            when(recognizer.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), any(), any(),
                    any(), any(), any(), any())).thenReturn(decisionAnswer);
        }

        List<String> recognitionCalls() {
            return org.mockito.Mockito.mockingDetails(recognizer).getInvocations().stream()
                    .filter(i -> "recognizeIntentWithConfidence".equals(i.getMethod().getName()))
                    .map(i -> String.valueOf((Object) i.getArgument(0)))
                    .toList();
        }

        void send(String input) throws Exception {
            IntentExecuteRequest request = IntentExecuteRequest.builder().userInput(input).build();
            try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(
                    new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, USER, FACTORY, ROLE))) {
                if (entry == Entry.ORDINARY) {
                    ordinaryResponse = ordinary().execute(FACTORY, request, USER, ROLE);
                    return;
                }
                ReflectionTestUtils.invokeMethod(sse(), "executeStreamAsync", mock(SseEmitter.class), FACTORY,
                        request, USER, ROLE, new F006BossQuestionClient.Cancellation());
            }
        }

        private IntentExecutionOrchestrator ordinary() {
            IntentExecutionOrchestrator orchestrator = new IntentExecutionOrchestrator(
                    recognizer, mock(IntentSemanticsParser.class), semanticCache,
                    mock(RuleEngineService.class), conversations, mock(ConversationMemoryService.class),
                    new ObjectMapper(), mock(DashScopeClient.class), mock(DashScopeConfig.class),
                    knowledgeBase, mock(AIAnalysisResultRepository.class), toolRegistry,
                    mock(AnalysisRouterService.class), mock(ComplexityRouter.class), mock(AgentOrchestrator.class),
                    mock(AgenticRAGRouterService.class), mock(ResultValidatorService.class), toolDispatch,
                    mock(DynamicToolSelectionService.class), preprocessor);
            ReflectionTestUtils.setField(orchestrator, "intentPermissionGate", permissionGate);
            ReflectionTestUtils.setField(orchestrator, "writeGuardService", new WriteGuardService());
            ReflectionTestUtils.setField(orchestrator, "configService", configService);
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
            ReflectionTestUtils.setField(sse, "conversationService", conversations);
            ReflectionTestUtils.setField(sse, "intentPermissionGate", permissionGate);
            ReflectionTestUtils.setField(sse, "queryPreprocessorService", preprocessor);
            doAnswer(invocation -> null).when(sse).sendSseEvent(any(), anyString(), any());
            return sse;
        }
    }
}
