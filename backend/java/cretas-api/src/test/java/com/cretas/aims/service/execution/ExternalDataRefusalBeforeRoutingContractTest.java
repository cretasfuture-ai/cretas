package com.cretas.aims.service.execution;

import com.cretas.aims.ai.capability.FactoryCapabilityPackRegistry;
import com.cretas.aims.ai.capability.FactoryCapabilityPackRoutingPolicy;
import com.cretas.aims.ai.capability.FactoryCapabilityPackSelector;
import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.client.F006BossQuestionClient;
import com.cretas.aims.ai.tool.OwnFactoryDataOnlyGuard;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.Factory;
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
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 2026-11-16 复跑 Q18「竞争对手的卤牛肉成本是多少」: viewer 先被成本权限拒, sales_manager 落到「我没有理解您的意图」——
 * 外部数据拒答 (EXTERNAL_DATA_REFUSED) 原来只接在 ToolDispatchService.prepareReadTurn, 要先认出意图、再过权限才轮得到。
 * 现在在识别和权限判定之前 (普通 / 流式 / 显式意图 / 多意图入口) 就整轮拒答, 与账号权限无关, 不执行任何查询。
 *
 * <p>入口是真的 ({@link IntentExecutionOrchestrator#execute} / {@link SseStreamingService} executeStreamAsync),
 * 识别器、工具调度、技能选择都是桩且必须一次都没被碰到 (拒答不靠识别, 也没有查数)。
 */
@DisplayName("问别家的成本 / 价格 / 毛利: 任何账号都先给外部数据拒答, 在识别和权限之前")
class ExternalDataRefusalBeforeRoutingContractTest {

    private static final String FACTORY = "F006";
    private static final long USER = 91L;
    private static final String Q18 = "竞争对手的卤牛肉成本是多少";

    private final AIIntentService recognizer = mock(AIIntentService.class);
    private final ToolDispatchService toolDispatch = mock(ToolDispatchService.class);
    private final DynamicToolSelectionService dynamic = mock(DynamicToolSelectionService.class);
    private final IntentPermissionGate permissionGate = mock(IntentPermissionGate.class);
    private final List<Object[]> events = new ArrayList<>();

    private IntentExecutionOrchestrator ordinary() {
        IntentExecutionOrchestrator orchestrator = new IntentExecutionOrchestrator(
                recognizer, mock(IntentSemanticsParser.class), mock(SemanticCacheService.class),
                mock(RuleEngineService.class), mock(ConversationService.class), mock(ConversationMemoryService.class),
                new ObjectMapper(), mock(DashScopeClient.class), mock(DashScopeConfig.class),
                mock(IntentKnowledgeBase.class), mock(AIAnalysisResultRepository.class), mock(ToolRegistry.class),
                mock(AnalysisRouterService.class), mock(ComplexityRouter.class), mock(AgentOrchestrator.class),
                mock(AgenticRAGRouterService.class), mock(ResultValidatorService.class), toolDispatch,
                dynamic, mock(QueryPreprocessorService.class));
        ReflectionTestUtils.setField(orchestrator, "intentPermissionGate", permissionGate);
        ReflectionTestUtils.setField(orchestrator, "writeGuardService", new WriteGuardService());
        IntentConfigManagementService configService = mock(IntentConfigManagementService.class);
        when(configService.resolveBusinessDomain(FACTORY)).thenReturn("FACTORY");
        ReflectionTestUtils.setField(orchestrator, "configService", configService);
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
                recognizer, mock(SemanticCacheService.class), mock(IntentKnowledgeBase.class),
                mock(AnalysisRouterService.class), new ObjectMapper(), mock(ToolRegistry.class), toolDispatch, dynamic,
                mock(DashScopeClient.class), mock(DashScopeConfig.class)));
        ReflectionTestUtils.setField(sse, "factoryCapabilityPackRoutingPolicy", new FactoryCapabilityPackRoutingPolicy(
                factories, new FactoryCapabilityPackSelector(new FactoryCapabilityPackRegistry()), false));
        FactoryAnalysisTestMetadata.bindNamedFactories(sse);
        ReflectionTestUtils.setField(sse, "writeGuardService", new WriteGuardService());
        doAnswer(invocation -> {
            events.add(new Object[]{invocation.getArgument(1), invocation.getArgument(2)});
            return null;
        }).when(sse).sendSseEvent(any(), anyString(), any());
        return sse;
    }

    private static AuthenticatedActor.ActorScope as(String role) {
        return AuthenticatedActor.trustedAsyncScope(
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, USER, FACTORY, role));
    }

    private void assertRefusedWithoutRoutingOrQuery(IntentExecuteResponse r, String role) {
        assertThat(r.getStatus()).as(role).isEqualTo("EXTERNAL_DATA_REFUSED");
        assertThat(r.getMessage()).as(role).isEqualTo(OwnFactoryDataOnlyGuard.MESSAGE)
                .contains("本厂").contains("不会拿本厂的数字代替")
                .doesNotContain("没有权限").doesNotContain("没有理解");
        assertThat(r.getResultData()).isNull();
        verifyNoInteractions(recognizer, toolDispatch, dynamic, permissionGate);
    }

    // viewer = 有财务查看、没有成本权限 (原先先被成本权限拒); sales_manager = 没有财务 (原先落到「我没有理解您的意图」)
    @ParameterizedTest(name = "普通入口 {0}")
    @ValueSource(strings = {"viewer", "sales_manager"})
    void ordinaryEntryRefusesBeforeRecognitionAndPermission(String role) {
        try (AuthenticatedActor.ActorScope ignored = as(role)) {
            // PR-3: 普通入口 (/execute, 无意图码) = 门面 → StreamTurnCollector → 流式管线 (OrdinaryEntryPipeline 同一接线)
            IntentExecuteResponse r = OrdinaryEntryPipeline.execute(ordinary(), FACTORY,
                    IntentExecuteRequest.builder().userInput(Q18).mode("READ").build(), USER, role);
            assertRefusedWithoutRoutingOrQuery(r, role);
        }
    }

    @ParameterizedTest(name = "流式入口 {0}")
    @ValueSource(strings = {"viewer", "sales_manager"})
    void streamEntryRefusesBeforeRecognitionAndPermission(String role) throws Exception {
        SseStreamingService sse = sse();
        try (AuthenticatedActor.ActorScope ignored = as(role)) {
            ReflectionTestUtils.invokeMethod(sse, "executeStreamAsync", mock(SseEmitter.class), FACTORY,
                    IntentExecuteRequest.builder().userInput(Q18).mode("READ").build(), USER, role,
                    new F006BossQuestionClient.Cancellation());
        }
        IntentExecuteResponse r = events.stream().filter(e -> "result".equals(e[0]))
                .map(e -> (IntentExecuteResponse) e[1]).reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("no SSE result; events="
                        + events.stream().map(e -> e[0] + "=" + e[1]).toList()));
        assertRefusedWithoutRoutingOrQuery(r, role);
    }

    @ParameterizedTest(name = "显式意图入口 {0}")
    @ValueSource(strings = {"viewer", "sales_manager"})
    void explicitIntentEntryRefusesBeforeThePermissionGate(String role) {
        try (AuthenticatedActor.ActorScope ignored = as(role)) {
            IntentExecuteResponse r = ordinary().executeWithExplicitIntent(FACTORY,
                    IntentExecuteRequest.builder().userInput(Q18).intentCode("REPORT_COST_QUERY").mode("READ").build(),
                    USER, role);
            assertRefusedWithoutRoutingOrQuery(r, role);
        }
    }

    @Test
    void refusedTurnLeavesOneTaggedLogLineWithoutTheQuestionText() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(IntentExecutionOrchestrator.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try (AuthenticatedActor.ActorScope ignored = as("viewer")) {
            OrdinaryEntryPipeline.execute(ordinary(), FACTORY,
                    IntentExecuteRequest.builder().userInput(Q18).mode("READ").build(), USER, "viewer");
        } finally {
            logger.detachAppender(appender);
        }
        List<String> tagged = appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("[PreRoutingRefusal]")).toList();
        assertThat(tagged).hasSize(1);
        // PR-3: /execute 普通入口跑流式管线, 拒答出自管线那一处守卫 (entry=stream); 编排器旧普通入口分支已删
        assertThat(tagged.get(0)).contains("EXTERNAL_DATA_REFUSED").contains("entry=stream").contains(FACTORY)
                .doesNotContain("卤牛肉").doesNotContain("竞争对手");
    }

    // 阴性对照: 问本厂的同一个指标、本厂与客户 / 供应商的往来、供应商比价 都不拒 (识别器被调用 = 正常往下走了)
    @ParameterizedTest
    @ValueSource(strings = {"本月卤牛肉的单位成本是多少", "卖给其他公司的卤牛肉价格是多少", "别家供应商的报价比现在便宜吗",
            "其他工厂的产量", "比较一下各供应商的卤牛肉报价"})
    void ownFactoryQuestionIsNotRefused_control(String text) {
        com.cretas.aims.dto.intent.IntentMatchResult noMatch = com.cretas.aims.dto.intent.IntentMatchResult.builder()
                .userInput(text).confidence(0.0).build();
        when(recognizer.recognizeIntentWithConfidence(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any(), any(), any())).thenReturn(noMatch);
        when(recognizer.recognizeIntentWithConfidence(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any(), any(), any(), any())).thenReturn(noMatch);
        try (AuthenticatedActor.ActorScope ignored = as("viewer")) {
            IntentExecuteResponse r = OrdinaryEntryPipeline.execute(ordinary(), FACTORY,
                    IntentExecuteRequest.builder().userInput(text).mode("READ").build(), USER, "viewer");
            assertThat(r == null ? null : r.getStatus()).as(text).isNotEqualTo("EXTERNAL_DATA_REFUSED");
        }
        assertThat(org.mockito.Mockito.mockingDetails(recognizer).getInvocations())
                .as("本厂问题必须照常进识别").isNotEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "竞争对手的卤牛肉成本是多少|true", "同行的毛利率一般多少|true", "行业平均售价是多少|true",
            "别家的卤牛肉卖多少钱|true", "竞品的价格是多少|true", "市场上其他厂家的卤牛肉成本|true",
            "本月卤牛肉的总成本是多少|false", "哪个产品毛利率最高|false",
            "明天和同行一起去展会|false", "竞争对手是谁|false",
            // #1788 审查 M1: 本厂与客户 / 供应商往来、集团内其他工厂 —— 都是本厂数据, 不在识别前拒
            "卖给其他公司的卤牛肉价格是多少|false", "别家供应商的报价比现在便宜吗|false", "其他工厂的产量|false",
            "比较一下各供应商的报价|false", "别家的进价是多少|false",
            // 有外部主语 + 价格词, 但语境是本厂往来 —— 只有往来语境排除能放行它们 (变异 R2a 第一轮存活后补)
            "别家供应商的价格是多少|false", "卖给别家的卤牛肉价格是多少|false", "别家客户的采购价格|false",
            // #1788 增量复审: 强主语不受本厂往来豁免 —— 竞品的进价 / 采购成本 / 报价仍在识别前拒
            "竞争对手的进价|true", "同行的采购成本|true", "竞品的报价|true", "隔壁厂的成本|true", "同行客户的采购价格|true",
            // 不是成本 / 价格 / 毛利的指标不在识别前拒 (落到本厂数据工具时由工具级 refuses 兜)
            "行业平均出成率是多少|false"})
    void beforeRoutingPredicateOnlyRefusesCompetitorCostPriceMargin(String text, boolean refused) {
        assertThat(OwnFactoryDataOnlyGuard.refusesBeforeRouting(text)).as(text).isEqualTo(refused);
    }

    // 多意图入口没有便宜的整体装配: 钉住守卫在它两个公开方法里、且排在识别 / 执行之前 (形态 B 接线)
    @Test
    void multiIntentEntriesCallTheGuardFirst() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/cretas/aims/service/execution/MultiIntentExecutionService.java"));
        for (String method : List.of("public IntentExecuteResponse executeMultiIntent(",
                "public IntentExecuteResponse executeMultiIntentFromMatchResult(")) {
            int body = src.indexOf(method);
            int guard = src.indexOf("OwnFactoryDataOnlyGuard.refusesBeforeRouting(request.getUserInput())", body);
            int actor = src.indexOf("AuthenticatedActor trustedActor", body);
            assertThat(body).as(method).isPositive();
            assertThat(guard).as(method).isGreaterThan(body).isLessThan(actor);
        }
    }
}
