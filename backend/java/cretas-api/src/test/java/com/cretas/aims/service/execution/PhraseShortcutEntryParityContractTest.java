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
 * 同一句话两个产品真实入口 (普通 {@link IntentExecutionOrchestrator#execute} 与 SSE {@code executeStreamAsync})
 * 必须落到同一个意图。
 *
 * <p>prod 2026-11-14 (F006 viewer): 「先看下库存」普通入口 = REPORT_INVENTORY (编排器短语快捷通道),
 * 流式入口缺这一步, 被决策层判成 MATERIAL_BATCH_QUERY, 澄清/被工具策略拒绝。
 *
 * <p>真的: 两个入口类、真实短语表 {@link IntentKnowledgeBase}。桩: 决策层识别结果 (一律回 MATERIAL_BATCH_QUERY,
 * 模拟线上决策层的分歧答案)、配置与权限查询、工具分发 (只记下它收到的意图)。
 */
@DisplayName("短语快捷通道: 普通入口与流式入口同句同意图")
class PhraseShortcutEntryParityContractTest {

    enum Entry { ORDINARY, SSE }

    private static final String FACTORY = "F006";
    private static final long USER = 1707L;
    private static final String ROLE = "viewer";
    private static final String PHRASE_INTENT = "REPORT_INVENTORY";
    private static final String DECISION_INTENT = "MATERIAL_BATCH_QUERY";

    @ParameterizedTest(name = "{0}: 「{1}」")
    @CsvSource({"ORDINARY,先看下库存", "SSE,先看下库存", "ORDINARY,查库存", "SSE,查库存"})
    @DisplayName("整句命中短语表 → 两个入口都执行短语指向的意图, 不再问决策层")
    void phraseHitWinsOverTheDecisionLayerOnBothEntries(Entry entry, String input) throws Exception {
        Harness h = new Harness(entry);
        String code = h.knowledgeBase.matchPhrase(input, "FACTORY").orElseThrow();
        assertThat(code).as("前提: 这句话在短语表里 (读数来源: 真实 IntentKnowledgeBase)").isEqualTo(PHRASE_INTENT);

        h.send(input);

        assertThat(h.executedIntents).as("阳性对照: 工具分发真的被调用了").hasSize(1);
        assertThat(h.executedIntents).containsExactly(PHRASE_INTENT);
        assertThat(h.recognitionCalls()).as("短语命中不再去识别").isEmpty();
    }

    @ParameterizedTest(name = "{0}: 否定说法「{1}」不被短语抢走")
    @CsvSource({"SSE,别查库存了", "SSE,不要看库存了"})
    @DisplayName("阴性对照: 否定说法不走短语快捷通道, 交给识别管线 (流式)")
    void negatedPhraseIsLeftToRecognition(Entry entry, String input) throws Exception {
        Harness h = new Harness(entry);
        when(h.preprocessor.detectNegationVeto(anyString(), any()))
                .thenReturn(QueryPreprocessorService.NegationKind.VETO_READ);

        h.send(input);

        assertThat(h.recognitionCalls()).as("没被短语抢 → 识别被调用 (阳性对照)").containsExactly(input);
        assertThat(h.executedIntents).doesNotContain(PHRASE_INTENT);
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
            for (String[] c : new String[][]{{PHRASE_INTENT, "库存报告", "report_inventory"},
                    {DECISION_INTENT, "原料批次查询", "material_batch_query"}}) {
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
                    ordinary().execute(FACTORY, request, USER, ROLE);
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
