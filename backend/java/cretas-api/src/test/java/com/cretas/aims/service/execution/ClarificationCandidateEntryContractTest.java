package com.cretas.aims.service.execution;

import com.cretas.aims.ai.capability.FactoryCapabilityPackRegistry;
import com.cretas.aims.ai.capability.FactoryCapabilityPackRoutingPolicy;
import com.cretas.aims.ai.capability.FactoryCapabilityPackSelector;
import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.client.F006BossQuestionClient;
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
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.AgentOrchestrator;
import com.cretas.aims.service.AgenticRAGRouterService;
import com.cretas.aims.service.AnalysisRouterService;
import com.cretas.aims.service.ComplexityRouter;
import com.cretas.aims.service.ConversationMemoryService;
import com.cretas.aims.service.ConversationService;
import com.cretas.aims.service.IntentSemanticsParser;
import com.cretas.aims.service.QueryPreprocessorService;
import com.cretas.aims.service.ResultValidatorService;
import com.cretas.aims.service.RuleEngineService;
import com.cretas.aims.service.SemanticCacheService;
import com.cretas.aims.service.intent.FrontlineAiWriteGrantService;
import com.cretas.aims.service.intent.IntentAccessModeFilter;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

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
 * INTENT-DISTILL D2: 低置信澄清在两个产品真实入口上走同一个按权限过滤的候选服务
 * ({@link ClarificationCandidateService}), 同一句话、同一组识别候选得到同一组按钮与同一句话术。
 *
 * <p>普通入口 {@link IntentExecutionOrchestrator#execute} 以前自己拼候选: 不按意图权限过滤, 过滤空了再拿
 * 「查询原料库存 / 查询生产批次」默认按钮顶上, 还多一个「查看所有可用操作」。
 * 识别 (模型) 是桩, 候选服务、权限判定入口、话术都是真的。
 */
@DisplayName("澄清候选: 两个入口同一个服务; 无权限的不出; 特殊选项不占业务名额; 业务候选为 0 明确失败")
class ClarificationCandidateEntryContractTest {

    enum Entry { ORDINARY, SSE }

    private static final String FACTORY = "F006";
    private static final long USER = 77L;
    private static final String ROLE = "dispatcher";
    private static final String INPUT = "看看那个单子";

    // ------------------------------------------------------------------ 权限过滤 + 两入口一致

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("无权限的意图不出现在澄清里 (阴性对照: 同样的候选, 有权限时出现)")
    void deniedIntentIsNotOffered(Entry entry) throws Exception {
        Harness denied = new Harness(entry);
        denied.deny("FINANCE_REPORT");
        IntentExecuteResponse response = denied.clarify("ORDER_LIST", "FINANCE_REPORT", "SHIPMENT_QUERY");

        assertThat(response.getStatus()).isEqualTo("NEED_CLARIFICATION");
        assertThat(selectable(response)).containsExactly("ORDER_LIST", "SHIPMENT_QUERY");
        assertThat(response.getMessage()).contains("销售订单").doesNotContain("财务报表");
        assertThat(codes(response)).containsExactly("SELECT_INTENT", "SELECT_INTENT", "REPHRASE");

        Harness allowed = new Harness(entry);
        IntentExecuteResponse control = allowed.clarify("ORDER_LIST", "FINANCE_REPORT", "SHIPMENT_QUERY");
        assertThat(selectable(control)).containsExactly("ORDER_LIST", "FINANCE_REPORT", "SHIPMENT_QUERY");
    }

    // ------------------------------------------------------------------ 候选预算

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("「其他 / 不支持 / 翻页」排在前面也不挤掉业务候选; 业务名额用满后它们不出")
    void specialOptionsDoNotCrowdOutBusinessCandidates(Entry entry) throws Exception {
        Harness h = new Harness(entry);
        IntentExecuteResponse response = h.clarify("ORDER_LIST",
                "OUT_OF_DOMAIN", "UNSUPPORTED_CAPABILITY", "PAGINATION_NEXT",
                "FINANCE_REPORT", "SHIPMENT_QUERY");

        assertThat(selectable(response)).containsExactly("ORDER_LIST", "FINANCE_REPORT", "SHIPMENT_QUERY");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("业务名额没用满时, 会话类选项排在业务候选之后")
    void conversationOptionsFillOnlyTheRemainingSlotsAfterBusiness(Entry entry) throws Exception {
        Harness h = new Harness(entry);
        IntentExecuteResponse response = h.clarify("ORDER_LIST", "PAGINATION_NEXT", "SHIPMENT_QUERY");

        assertThat(selectable(response)).containsExactly("ORDER_LIST", "SHIPMENT_QUERY", "PAGINATION_NEXT");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("业务候选为 0: 明确说没有可安全提供的业务选项, 只给「重新描述」, 不回退默认业务按钮")
    void zeroBusinessCandidatesFailExplicitlyWithoutLegacyDefaults(Entry entry) throws Exception {
        Harness h = new Harness(entry);
        h.deny("ORDER_LIST");
        h.deny("FINANCE_REPORT");
        IntentExecuteResponse response = h.clarify("ORDER_LIST", "FINANCE_REPORT", "PAGINATION_NEXT", "OUT_OF_DOMAIN");

        assertThat(response.getStatus()).isEqualTo("NEED_CLARIFICATION");
        assertThat(codes(response)).containsExactly("REPHRASE");
        assertThat(response.getMessage()).isEqualTo("当前没有可安全提供的业务选项，请重新描述您的需求。");
        assertThat(String.valueOf(response.getFormattedText())).doesNotContain("原料库存", "生产批次", "下一页");
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> selectable(IntentExecuteResponse response) {
        return response.getSuggestedActions().stream()
                .filter(a -> "SELECT_INTENT".equals(a.getActionCode()))
                .map(a -> String.valueOf(a.getParameters().get("intentCode")))
                .toList();
    }

    private static List<String> codes(IntentExecuteResponse response) {
        return response.getSuggestedActions().stream().map(IntentExecuteResponse.SuggestedAction::getActionCode).toList();
    }

    private static final class Harness {
        private final Entry entry;
        private final AIIntentService recognizer = mock(AIIntentService.class);
        private final IntentConfigManagementService configService = mock(IntentConfigManagementService.class);
        private final IntentPermissionGate permissionGate = mock(IntentPermissionGate.class);
        private final SemanticCacheService semanticCache = mock(SemanticCacheService.class);
        private final IntentKnowledgeBase knowledgeBase = mock(IntentKnowledgeBase.class);
        private final ClarificationCandidateService service;
        private final List<Object[]> events = new ArrayList<>();

        Harness(Entry entry) {
            this.entry = entry;
            IntentAccessModeFilter accessModeFilter = mock(IntentAccessModeFilter.class);
            when(accessModeFilter.isWriteIntent(any(AIIntentConfig.class))).thenReturn(false);
            FrontlineAiWriteGrantService grants = mock(FrontlineAiWriteGrantService.class);
            when(grants.denyReason(any(), any(), any(), any())).thenReturn(Optional.empty());
            service = new ClarificationCandidateService(configService, permissionGate, accessModeFilter,
                    new WriteGuardService(), grants, mock(ToolRegistry.class));
            when(configService.resolveBusinessDomain(FACTORY)).thenReturn("FACTORY");
            for (String[] c : new String[][]{
                    {"ORDER_LIST", "销售订单查询", "QUERY"}, {"FINANCE_REPORT", "财务报表", "QUERY"},
                    {"SHIPMENT_QUERY", "出货记录查询", "QUERY"}, {"OUT_OF_DOMAIN", "域外对话", "SYSTEM"},
                    {"UNSUPPORTED_CAPABILITY", "能力不支持-明确拒答", "SYSTEM"}, {"PAGINATION_NEXT", "下一页", "SYSTEM"}}) {
                AIIntentConfig config = AIIntentConfig.builder().intentCode(c[0]).intentName(c[1]).intentCategory(c[2])
                        .businessType("COMMON").sensitivityLevel("LOW").requiredPermission("any:read").build();
                config.setIsActive(true);
                when(configService.getIntentByCode(FACTORY, c[0])).thenReturn(Optional.of(config));
                when(permissionGate.check(org.mockito.ArgumentMatchers.argThat(i -> i != null && c[0].equals(i.getIntentCode())),
                        eq(USER), eq(ROLE))).thenReturn(IntentPermissionGate.PermissionCheck.allowed());
            }
            when(semanticCache.queryCache(anyString(), anyString())).thenReturn(SemanticCacheHit.miss(0L));
            when(knowledgeBase.detectQuestionType(anyString()))
                    .thenReturn(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND);
        }

        void deny(String code) {
            // AIIntentConfig.equals 按实体 id 比 (这里都是 null), 按意图码匹配才不会一拒全拒
            when(permissionGate.check(org.mockito.ArgumentMatchers.argThat(i -> i != null && code.equals(i.getIntentCode())),
                    eq(USER), eq(ROLE))).thenReturn(IntentPermissionGate.PermissionCheck.denied("finance:read"));
        }

        IntentExecuteResponse clarify(String... candidateCodes) throws Exception {
            AIIntentConfig best = configService.getIntentByCode(FACTORY, candidateCodes[0]).orElseThrow();
            IntentMatchResult match = IntentMatchResult.builder()
                    .bestMatch(best)
                    // 识别给的名字不可信: 按钮名只能来自当前工厂配置
                    .topCandidates(Arrays.stream(candidateCodes).map(code -> IntentMatchResult.CandidateIntent.builder()
                            .intentCode(code).intentName("untrusted " + code).confidence(0.4).build()).toList())
                    .confidence(0.42).matchMethod(IntentMatchResult.MatchMethod.LLM)
                    .requiresConfirmation(true)
                    .questionType(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND)
                    .userInput(INPUT)
                    .build();
            when(recognizer.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), any(), any(),
                    any(), any())).thenReturn(match);
            when(recognizer.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), any(), any(),
                    any(), any(), any())).thenReturn(match);
            IntentExecuteRequest request = IntentExecuteRequest.builder().userInput(INPUT).build();
            try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(
                    new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, USER, FACTORY, ROLE))) {
                if (entry == Entry.ORDINARY) {
                    return ordinary().execute(FACTORY, request, USER, ROLE);
                }
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
                    mock(RuleEngineService.class), mock(ConversationService.class), mock(ConversationMemoryService.class),
                    new ObjectMapper(), mock(DashScopeClient.class), mock(DashScopeConfig.class),
                    knowledgeBase, mock(AIAnalysisResultRepository.class), mock(ToolRegistry.class),
                    mock(AnalysisRouterService.class), mock(ComplexityRouter.class), mock(AgentOrchestrator.class),
                    mock(AgenticRAGRouterService.class), mock(ResultValidatorService.class), mock(ToolDispatchService.class),
                    mock(DynamicToolSelectionService.class), mock(QueryPreprocessorService.class));
            ReflectionTestUtils.setField(orchestrator, "intentPermissionGate", permissionGate);
            ReflectionTestUtils.setField(orchestrator, "writeGuardService", new WriteGuardService());
            ReflectionTestUtils.setField(orchestrator, "configService", configService);
            ReflectionTestUtils.setField(orchestrator, "clarificationCandidateService", service);
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
                    mock(ToolRegistry.class), mock(ToolDispatchService.class), mock(DynamicToolSelectionService.class),
                    mock(DashScopeClient.class), mock(DashScopeConfig.class)));
            ReflectionTestUtils.setField(sse, "factoryCapabilityPackRoutingPolicy", new FactoryCapabilityPackRoutingPolicy(
                    factories, new FactoryCapabilityPackSelector(new FactoryCapabilityPackRegistry()), false));
            FactoryAnalysisTestMetadata.bindNamedFactories(sse);
            ReflectionTestUtils.setField(sse, "writeGuardService", new WriteGuardService());
            ReflectionTestUtils.setField(sse, "clarificationCandidateService", service);
            doAnswer(invocation -> {
                events.add(new Object[]{invocation.getArgument(1), invocation.getArgument(2)});
                return null;
            }).when(sse).sendSseEvent(any(), anyString(), any());
            return sse;
        }
    }
}
