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
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.repository.conversation.ConversationSessionRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.AgentOrchestrator;
import com.cretas.aims.service.AgenticRAGRouterService;
import com.cretas.aims.service.AnalysisRouterService;
import com.cretas.aims.service.ComplexityRouter;
import com.cretas.aims.service.ConversationMemoryService;
import com.cretas.aims.service.IntentSemanticsParser;
import com.cretas.aims.service.QueryPreprocessorService;
import com.cretas.aims.service.ResultFormatterService;
import com.cretas.aims.service.ResultValidatorService;
import com.cretas.aims.service.RuleEngineService;
import com.cretas.aims.service.SemanticCacheService;
import com.cretas.aims.service.impl.ConversationServiceImpl;
import com.cretas.aims.service.impl.IntentExecutorServiceImpl;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretAiAccessAudit;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.cretas.aims.dto.intent.ValidationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
 * 意图蒸馏第③步 PR-2b: 普通入口 {@code /execute} 改走流式管线 ({@link StreamTurnCollector})。
 *
 * <p>三个入口, 都是产品真实入口方法:
 * <ul>
 *   <li>{@code EXECUTE}: 门面 {@link IntentExecutorServiceImpl#execute}, 开关开 (默认) —— 本片之后的 /execute;</li>
 *   <li>{@code STREAM}: {@code SseStreamingService#executeStreamAsync} —— App 的 /execute/stream;</li>
 *   <li>{@code LEGACY}: 门面, 开关关 —— 本片之前的 /execute (回退杆)。</li>
 * </ul>
 * 对齐类用例断言 EXECUTE 与 STREAM 的 status + intentCode 相同, 并证明 EXECUTE 真的走了流式管线 (编排器
 * {@code execute} 0 次); 保留类用例 (Drools、动态选工具、文字兜底、审计) 断言 EXECUTE 与 LEGACY 相同, App 流式不变。
 */
@DisplayName("PR-2b /execute 走流式管线: 同一句话两入口同状态, 原普通入口独有步骤保留")
class StreamTurnCollectorEntryParityContractTest {

    enum Entry { EXECUTE, STREAM, LEGACY }

    private static final String FACTORY = "F006";
    private static final long USER = 1707L;
    private static final String ROLE = "viewer";
    private static final String READ_INTENT = "REPORT_DASHBOARD_OVERVIEW";
    private static final String READ_TOOL = "report_dashboard_overview";
    private static final String WRITE_INTENT = "MATERIAL_BATCH_CREATE";
    private static final String WRITE_TOOL = "material_batch_create";
    private static final String APPROVAL_INTENT = "COST_VARIANCE_REVIEW";
    private static final String APPROVAL_TOOL = "cost_variance_review";
    private static final String INVENTORY_INTENT = "REPORT_INVENTORY";
    private static final String INVENTORY_TOOL = "report_inventory";
    private static final String REPORT_WORK_INTENT = "WORK_PROCESS_TASK_COMPLETE";
    private static final String REPORT_WORK_TOOL = "work_process_task_complete";
    private static final String NO_TOOL_INTENT = "DAILY_CUSTOMER_FOLLOWUP";
    /** 本人这一页的会话 (Web / App 都带); 不带 sessionId 的 /execute 留原分支, 见 sessionlessExecuteDoesNotJoinTheAppSession。 */
    private static final String SESSION = "web-session-1";
    private static final String APP_SESSION = "app-session-1";
    private static final String CHAT_REPLY = "降低成本可以从优化采购批量、减少损耗、提高设备稼动率入手。";

    // ------------------------------------------------------------------ 对齐: 同句两入口同状态

    @Test
    @DisplayName("「怎么降低成本？」→ 两入口都直答 COMPLETED (此前线上 /execute=COMPLETED, 流式=NEED_CLARIFICATION)")
    void generalCostQuestionHasSameStatusOnBothEntries() throws Exception {
        String input = "怎么降低成本？";
        Map<Entry, Harness> runs = runAll(h -> {
            org.mockito.Mockito.doReturn(QuestionType.OPERATIONAL_COMMAND).when(h.knowledgeBase).detectQuestionType(input);
            h.chatReplies(CHAT_REPLY);
            h.recognizerAnswers(IntentMatchResult.builder().userInput(input)
                    .matchMethod(IntentMatchResult.MatchMethod.NONE).confidence(0.0)
                    .questionType(QuestionType.GENERAL_QUESTION).build());
        }, IntentExecuteRequest.builder().userInput(input).build());

        assertSameStatusAndIntent(runs, "COMPLETED");
        assertThat(runs.get(Entry.EXECUTE).shown.getFormattedText()).isEqualTo(CHAT_REPLY);
        assertThat(runs.get(Entry.EXECUTE).recognitionCalls()).as("阳性对照: 识别被调用").isEqualTo(1);
        assertThat(runs.get(Entry.LEGACY).shown.getStatus()).as("原 /execute 也是 COMPLETED").isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("短语快捷 (「库存查询」) → 两入口都直达同一意图, 不经识别")
    void phraseShortcutHasSameStatusOnBothEntries() throws Exception {
        String input = "库存查询";
        Map<Entry, Harness> runs = runAll(h -> {
            assertThat(h.knowledgeBase.matchPhrase(input, "FACTORY")).as("前提: 短语表有这句").contains(INVENTORY_INTENT);
        }, IntentExecuteRequest.builder().userInput(input).build());

        assertSameStatusAndIntent(runs, "SUCCESS");
        assertThat(runs.get(Entry.EXECUTE).shown.getIntentCode()).isEqualTo(INVENTORY_INTENT);
        assertThat(runs.get(Entry.EXECUTE).executedIntents).containsExactly(INVENTORY_INTENT);
        assertThat(runs.get(Entry.EXECUTE).recognitionCalls()).as("短语直达, 不经识别").isZero();
    }

    @Test
    @DisplayName("报工第 1 轮 (「我要报工」) → 两入口都先追问参数 NEED_MORE_INFO; 原 /execute 出写确认卡 (登记的行为变化, 都不执行)")
    void reportWorkFirstTurnHasSameStatusOnBothEntries() throws Exception {
        String input = "我要报工";
        Map<Entry, Harness> runs = runAll(h -> {
            assertThat(h.knowledgeBase.matchPhrase(input, "FACTORY")).as("前提: 短语表有这句").contains(REPORT_WORK_INTENT);
        }, IntentExecuteRequest.builder().userInput(input).mode("OPERATE").build());

        assertSameStatusAndIntent(runs, "NEED_MORE_INFO");
        assertThat(runs.get(Entry.EXECUTE).shown.getIntentCode()).isEqualTo(REPORT_WORK_INTENT);
        for (Entry entry : Entry.values()) {
            assertThat(runs.get(entry).executedIntents).as(entry + ": 写操作一次都没执行").isEmpty();
        }
        assertThat(runs.get(Entry.LEGACY).shown.getStatus())
                .as("原 /execute: 短语走显式路径, 先出写确认卡 (本片的行为变化, 见 PR 描述)")
                .isEqualTo("WRITE_CONFIRM_REQUIRED");
    }

    @Test
    @DisplayName("审批: 声明需要审批 + forceExecute → 两入口 (含原 /execute) 都停在 PENDING_APPROVAL, 不执行")
    void approvalHasSameStatusOnAllEntries() throws Exception {
        String input = "看下成本差异复核";
        Map<Entry, Harness> runs = runAll(h -> h.recognizerAnswers(IntentMatchResult.builder().userInput(input)
                .bestMatch(h.approvalIntent).matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build()),
                IntentExecuteRequest.builder().userInput(input).forceExecute(true).build());

        assertSameStatusAndIntent(runs, "PENDING_APPROVAL");
        assertThat(runs.get(Entry.LEGACY).shown.getStatus()).isEqualTo("PENDING_APPROVAL");
        for (Entry entry : Entry.values()) {
            assertThat(runs.get(entry).executedIntents).as(entry + ": 执行层没碰").isEmpty();
        }
    }

    @Test
    @DisplayName("咨询页拦写: mode=READ 识别到写意图 → 两入口 (含原 /execute) 都 READ_MODE_WRITE_BLOCKED")
    void readModeWriteBlockHasSameStatusOnAllEntries() throws Exception {
        String input = "那批到货的鸡腿帮我登记进系统";
        Map<Entry, Harness> runs = runAll(h -> h.recognizerAnswers(IntentMatchResult.builder().userInput(input)
                .bestMatch(h.writeIntent).matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build()),
                IntentExecuteRequest.builder().userInput(input).mode("READ").build());

        assertSameStatusAndIntent(runs, "READ_MODE_WRITE_BLOCKED");
        assertThat(runs.get(Entry.LEGACY).shown.getStatus()).isEqualTo("READ_MODE_WRITE_BLOCKED");
        assertThat(runs.get(Entry.EXECUTE).slotFillingCalls()).as("没开始追问参数").isZero();
    }

    // ------------------------------------------------------------------ #12 AI 访问审计

    @Test
    @DisplayName("#12 /execute 走流式后仍写 INTENT_EXECUTION 审计条目 (一次); App 流式照旧不写这一条; 开关关时原分支写")
    void intentExecutionAuditEntryIsKeptOnExecute() throws Exception {
        String input = "看下经营概况";
        Map<Entry, Harness> runs = runAll(h -> h.recognizerAnswers(IntentMatchResult.builder().userInput(input)
                .bestMatch(h.readIntent).matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build()),
                IntentExecuteRequest.builder().userInput(input).build());

        assertSameStatusAndIntent(runs, "SUCCESS");
        assertThat(runs.get(Entry.EXECUTE).auditRefs()).as("/execute (走流式)").containsExactly("ai:intent-execution");
        assertThat(runs.get(Entry.LEGACY).auditRefs()).as("阳性对照: 原分支同样一条").containsExactly("ai:intent-execution");
        assertThat(runs.get(Entry.STREAM).auditRefs()).as("App 流式不变 (不写 INTENT_EXECUTION)").isEmpty();
    }

    // ------------------------------------------------------------------ 开关 / 路由

    @Test
    @DisplayName("开关 false → 走原编排器, 流式管线一次都不跑; 开关 true → 反过来")
    void switchSelectsThePath() throws Exception {
        String input = "看下经营概况";
        Map<Entry, Harness> runs = runAll(h -> h.recognizerAnswers(IntentMatchResult.builder().userInput(input)
                .bestMatch(h.readIntent).matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build()),
                IntentExecuteRequest.builder().userInput(input).build());

        Harness legacy = runs.get(Entry.LEGACY);
        assertThat(legacy.orchestratorExecuteCalls()).isEqualTo(1);
        assertThat(legacy.streamRuns()).isZero();
        Harness execute = runs.get(Entry.EXECUTE);
        assertThat(execute.orchestratorExecuteCalls()).isZero();
        assertThat(execute.streamRuns()).isEqualTo(1);
    }

    @Test
    @DisplayName("留原分支的几类 (C 类 #9/#15/#16/#17): 否定说法、早期通用咨询、显式意图码、非本厂工厂账号")
    void legacyRoutesForNotYetAlignedRequests() throws Exception {
        Harness h = new Harness(Entry.EXECUTE);
        StreamTurnCollector collector = h.collector();

        when(h.preprocessor.detectNegationVeto(eq("别查库存了"), any()))
                .thenReturn(QueryPreprocessorService.NegationKind.VETO_READ);
        org.mockito.Mockito.doReturn(QuestionType.GENERAL_QUESTION).when(h.knowledgeBase).detectQuestionType("分析一下最近的成本走势");
        org.mockito.Mockito.doReturn(QuestionType.OPERATIONAL_COMMAND).when(h.knowledgeBase).detectQuestionType("怎么降低成本？");

        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(h.actor())) {
            assertThat(collector.route(FACTORY, req("别查库存了"), USER)).isEqualTo(StreamTurnCollector.Route.LEGACY_NEGATION);
            assertThat(collector.route(FACTORY, req("分析一下最近的成本走势"), USER))
                    .isEqualTo(StreamTurnCollector.Route.LEGACY_EARLY_GENERAL);
            assertThat(collector.route(FACTORY, IntentExecuteRequest.builder().userInput("看下成本差异复核")
                    .intentCode(APPROVAL_INTENT).build(), USER)).isEqualTo(StreamTurnCollector.Route.LEGACY_EXPLICIT_INTENT);
            assertThat(collector.route(FACTORY, IntentExecuteRequest.builder().userInput("看下")
                    .previewOnly(true).build(), USER)).isEqualTo(StreamTurnCollector.Route.LEGACY_PREVIEW);
            assertThat(collector.route("F001", req("怎么降低成本？"), USER))
                    .as("账号绑定的不是这家工厂").isEqualTo(StreamTurnCollector.Route.LEGACY_NOT_FACTORY_ACTOR);
            assertThat(collector.route(FACTORY, req("怎么降低成本？"), USER))
                    .as("阳性对照: 对齐类走流式").isEqualTo(StreamTurnCollector.Route.STREAM);
            assertThat(collector.route(FACTORY, IntentExecuteRequest.builder().userInput("怎么降低成本？").build(), USER))
                    .as("没带 sessionId").isEqualTo(StreamTurnCollector.Route.LEGACY_SESSIONLESS);
            assertThat(collector.route(FACTORY, req("库存查询"), USER))
                    .as("短语快捷会接的句子不看早期问题类型").isEqualTo(StreamTurnCollector.Route.STREAM);
        }
        assertThat(collector.route(FACTORY, req("怎么降低成本？"), USER))
                .as("没有绑定的工厂账号 (演示会话 / 公开演示 / 平台)").isEqualTo(StreamTurnCollector.Route.LEGACY_NOT_FACTORY_ACTOR);

        // 经门面真跑一次: 否定说法确实落到原编排器
        IntentExecuteResponse shown = h.sendThroughFacade(req("别查库存了"));
        assertThat(h.orchestratorExecuteCalls()).isEqualTo(1);
        assertThat(h.streamRuns()).isZero();
        assertThat(shown.getStatus()).isEqualTo("NEED_CLARIFICATION");
    }

    // ------------------------------------------------------------------ 保留: 原普通入口独有步骤

    @Test
    @DisplayName("#10 Drools 校验: /execute 走流式后照旧拦 (同原分支 VALIDATION_FAILED); App 流式照旧不跑 Drools")
    void droolsValidationIsKeptForExecuteOnly() throws Exception {
        String input = "那批到货的鸡腿帮我登记进系统";
        Map<Entry, Harness> runs = runAll(h -> {
            h.recognizerAnswers(IntentMatchResult.builder().userInput(input).bestMatch(h.writeIntent)
                    .matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                    .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build());
            ValidationResult invalid = ValidationResult.builder().valid(false).build();
            invalid.addViolation("批次数量超限", "单批不得超过 500kg", "HIGH");
            when(h.ruleEngine.executeRulesWithAudit(anyString(), anyString(), anyString(), anyString(), any(), any(),
                    any(), any())).thenReturn(invalid);
        }, IntentExecuteRequest.builder().userInput(input).mode("OPERATE").build());

        assertThat(runs.get(Entry.EXECUTE).shown.getStatus()).isEqualTo("VALIDATION_FAILED");
        assertThat(runs.get(Entry.EXECUTE).shown.getStatus()).isEqualTo(runs.get(Entry.LEGACY).shown.getStatus());
        assertThat(runs.get(Entry.EXECUTE).streamRuns()).as("确实走的流式管线").isEqualTo(1);
        assertThat(runs.get(Entry.STREAM).shown.getStatus()).as("App 流式不变: 照常追问参数").isEqualTo("NEED_MORE_INFO");
        assertThat(runs.get(Entry.STREAM).ruleEngineCalls()).isZero();
    }

    @Test
    @DisplayName("#11 没绑工具 + 需要动态选工具: /execute 走流式后照旧动态选 (同原分支); App 流式照旧不选")
    void dynamicToolSelectionIsKeptForExecuteOnly() throws Exception {
        String input = "今天要跟进哪些客户";
        Map<Entry, Harness> runs = runAll(h -> {
            h.recognizerAnswers(IntentMatchResult.builder().userInput(input).bestMatch(h.noToolIntent)
                    .matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                    .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build());
            when(h.dynamicTools.requiresDynamicSelection(any())).thenReturn(true);
            when(h.dynamicTools.executeWithDynamicToolSelection(anyString(), any(), any(), any(), any(), any()))
                    .thenReturn(IntentExecuteResponse.builder().intentRecognized(true).intentCode(NO_TOOL_INTENT)
                            .status("SUCCESS").message("今天要跟进 3 位客户：张三、李四、王五。").build());
        }, IntentExecuteRequest.builder().userInput(input).build());

        assertThat(runs.get(Entry.EXECUTE).dynamicSelectionCalls()).isEqualTo(1);
        assertThat(runs.get(Entry.LEGACY).dynamicSelectionCalls()).as("阳性对照: 原分支也选").isEqualTo(1);
        assertThat(runs.get(Entry.EXECUTE).shown.getStatus()).isEqualTo("SUCCESS")
                .isEqualTo(runs.get(Entry.LEGACY).shown.getStatus());
        assertThat(runs.get(Entry.STREAM).dynamicSelectionCalls()).as("App 流式不变").isZero();
        assertThat(runs.get(Entry.STREAM).shown.getStatus()).isEqualTo("NOT_SUPPORTED");
    }

    @Test
    @DisplayName("#13 文字兜底: 工具 message 是占位符「操作已完成。」→ /execute 照旧换成真答案 (同原分支); App 流式不变")
    void ordinaryTextFallbackIsKeptForExecute() throws Exception {
        String input = "看下经营概况";
        String answer = "本月销售额 ¥35,000，出货 1200 箱，环比增长 3 个百分点。";
        Map<Entry, Harness> runs = runAll(h -> {
            h.toolReturns(IntentExecuteResponse.builder().intentRecognized(true).intentCode(READ_INTENT)
                    .status("SUCCESS").message("操作已完成。").formattedText(answer).build());
            h.recognizerAnswers(IntentMatchResult.builder().userInput(input).bestMatch(h.readIntent)
                    .matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                    .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build());
        }, IntentExecuteRequest.builder().userInput(input).build());

        assertSameStatusAndIntent(runs, "SUCCESS");
        assertThat(runs.get(Entry.EXECUTE).shown.getMessage()).isEqualTo(answer)
                .isEqualTo(runs.get(Entry.LEGACY).shown.getMessage());
        assertThat(runs.get(Entry.STREAM).shown.getMessage()).as("App 流式不变").isEqualTo("操作已完成。");
    }

    @Test
    @DisplayName("流式管线 error 出口 (没给 result) → /execute 如实 FAILED, 不兜成成功")
    void streamErrorExitIsAnHonestFailure() throws Exception {
        Harness h = new Harness(Entry.EXECUTE);
        String input = "看下经营概况";
        when(h.recognizer.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), any(), any(),
                any(), any(), any())).thenThrow(new IllegalStateException("boom"));
        IntentExecuteResponse shown = h.sendThroughFacade(req(input));
        assertThat(h.streamRuns()).isEqualTo(1);
        assertThat(shown.getStatus()).isEqualTo("FAILED");
        assertThat(shown.getMessage()).as("带上 error 事件里的说明 (#1782 低-3)").isNotBlank()
                .doesNotContain("boom").doesNotContain("IllegalStateException");
        assertThat(h.auditRefs()).as("error 出口审计恰好一条 (#1782 低-5)").containsExactly("ai:intent-execution");
    }

    @Test
    @DisplayName("中-1: 不带 sessionId 的 /execute 不接上 App 正在问参数的那一轮 (「100」不被当成报工那一问的回答)")
    void sessionlessExecuteDoesNotJoinTheAppSession() throws Exception {
        Harness h = new Harness(Entry.EXECUTE);
        h.useRealSessionServices();
        // App 那边: 本人最近一次活跃会话 = app-session-1, 且正停在报工的缺参追问上
        com.cretas.aims.entity.conversation.ConversationMemory appMemory =
                com.cretas.aims.entity.conversation.ConversationMemory.builder()
                        .factoryId(FACTORY).userId(USER).sessionId(APP_SESSION).build();
        appMemory.setLastActiveAt(java.time.LocalDateTime.now());
        when(h.memoryRepository.findLatestActiveByFactoryIdAndUserId(FACTORY, USER)).thenReturn(Optional.of(appMemory));
        when(h.memoryRepository.findBySessionId(APP_SESSION)).thenReturn(Optional.of(appMemory));
        com.cretas.aims.entity.conversation.ConversationSession pending =
                mock(com.cretas.aims.entity.conversation.ConversationSession.class);
        when(pending.getSessionId()).thenReturn(APP_SESSION);
        when(pending.isParameterCollectionMode()).thenReturn(true);
        when(pending.getStatus()).thenReturn(com.cretas.aims.entity.conversation.ConversationSession.SessionStatus.ACTIVE);
        when(h.sessionRepository.findBySessionIdAndFactoryIdAndUserId(APP_SESSION, FACTORY, USER))
                .thenReturn(Optional.of(pending));
        org.mockito.Mockito.doReturn(QuestionType.OPERATIONAL_COMMAND).when(h.knowledgeBase).detectQuestionType("100");

        IntentExecuteResponse shown = h.sendThroughFacade(IntentExecuteRequest.builder().userInput("100").build());

        assertThat(h.streamRuns()).as("没带 sessionId 留原分支").isZero();
        assertThat(h.orchestratorExecuteCalls()).isEqualTo(1);
        assertThat(invocationsOf(h.memoryRepository, "findLatestActiveByFactoryIdAndUserId"))
                .as("没去找本人最近一次会话").isZero();
        assertThat(invocationsOf(h.sessionRepository, "findBySessionIdAndFactoryIdAndUserId"))
                .as("App 那一轮的缺参会话一次都没被读").isZero();
        assertThat(org.mockito.Mockito.mockingDetails(pending).getInvocations().stream()
                .filter(i -> i.getMethod().getName().startsWith("set") || i.getMethod().getName().startsWith("add"))
                .count()).as("也没被改").isZero();
        assertThat(shown.getSessionId()).isNotEqualTo(APP_SESSION);

        // 阳性对照: 同样的会话服务, 带着本页 sessionId 的 /execute 走流式, 会去读这一页的缺参会话 (仪器是活的)
        Harness sessionful = new Harness(Entry.EXECUTE);
        sessionful.useRealSessionServices();
        org.mockito.Mockito.doReturn(QuestionType.OPERATIONAL_COMMAND).when(sessionful.knowledgeBase).detectQuestionType("100");
        sessionful.sendThroughFacade(req("100"));
        assertThat(sessionful.streamRuns()).isEqualTo(1);
        assertThat(invocationsOf(sessionful.sessionRepository, "findBySessionIdAndFactoryIdAndUserId"))
                .as("带 sessionId 时确实会读会话").isPositive();
    }

    @Test
    @DisplayName("低-2: /execute 走流式时对话记忆记的是用户看到的那句 (文字兜底之后), 同原分支")
    void conversationMemoryRecordsTheShownAnswer() throws Exception {
        String input = "看下经营概况";
        String answer = "本月销售额 ¥35,000，出货 1200 箱，环比增长 3 个百分点。";
        Map<Entry, Harness> runs = runAll(h -> {
            h.recordMemory();
            h.toolReturns(IntentExecuteResponse.builder().intentRecognized(true).intentCode(READ_INTENT)
                    .status("SUCCESS").message("操作已完成。").formattedText(answer).build());
            h.recognizerAnswers(IntentMatchResult.builder().userInput(input).bestMatch(h.readIntent)
                    .matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                    .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build());
        }, IntentExecuteRequest.builder().userInput(input).build());

        for (Entry entry : new Entry[] {Entry.EXECUTE, Entry.LEGACY}) {
            Harness h = runs.get(entry);
            assertThat(h.shown.getMessage()).as(entry + " 返回").isEqualTo(answer);
            assertThat(h.assistantMemory()).as(entry + " 记忆里的助手那句 == 返回 message").containsExactly(answer);
        }
    }

    @Test
    @DisplayName("低-5: 在 HTTP 请求线程上 (审计挂在请求上, 请求结束时写): /execute 走流式仍恰好一条 INTENT_EXECUTION")
    void auditAttachedToTheRequestIsWrittenOnceWhenTheRequestEnds() throws Exception {
        Harness h = new Harness(Entry.EXECUTE);
        String input = "看下经营概况";
        h.recognizerAnswers(IntentMatchResult.builder().userInput(input).bestMatch(h.readIntent)
                .matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build());
        org.springframework.mock.web.MockHttpServletRequest http = new org.springframework.mock.web.MockHttpServletRequest();
        http.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, h.actor());
        org.springframework.web.context.request.ServletRequestAttributes attributes =
                new org.springframework.web.context.request.ServletRequestAttributes(http);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(attributes);
        IntentExecuteResponse shown;
        try {
            shown = h.sendThroughFacade(req(input));
            assertThat(h.streamRuns()).as("请求线程上同样走流式").isEqualTo(1);
            assertThat(h.auditRefs()).as("请求还没结束: 挂着, 没写").isEmpty();
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
            attributes.requestCompleted();
        }
        assertThat(shown.getStatus()).isEqualTo("SUCCESS");
        assertThat(h.auditRefs()).as("请求结束写恰好一条").containsExactly("ai:intent-execution");
    }

    @Test
    @DisplayName("低-1: 取回后的文字兜底 (workdesk 摘要会调模型) 在按本厂脱敏的外发作用域内")
    void ordinaryTextFallbackRunsInsideTheEgressScope() throws Exception {
        Harness h = new Harness(Entry.EXECUTE);
        String input = "怎么降低成本？";
        org.mockito.Mockito.doReturn(QuestionType.OPERATIONAL_COMMAND).when(h.knowledgeBase).detectQuestionType(input);
        h.chatReplies(CHAT_REPLY);
        h.recognizerAnswers(IntentMatchResult.builder().userInput(input)
                .matchMethod(IntentMatchResult.MatchMethod.NONE).confidence(0.0)
                .questionType(QuestionType.GENERAL_QUESTION).build());
        List<Object> egressFactoryAtSummary = new ArrayList<>();
        WorkdeskOutputSummarizer summarizer = mock(WorkdeskOutputSummarizer.class);
        @SuppressWarnings("unchecked")
        ThreadLocal<String> analysed = (ThreadLocal<String>) ReflectionTestUtils.getField(
                com.cretas.aims.service.consent.LlmEgressGuard.class, "ANALYSED_FACTORY");
        doAnswer(inv -> {
            egressFactoryAtSummary.add(String.valueOf(analysed.get()));
            return null;
        }).when(summarizer).apply(any(), anyString());
        h.workdeskSummarizer = summarizer;

        IntentExecuteResponse shown = h.sendThroughFacade(req(input));

        assertThat(shown.getStatus()).isEqualTo("COMPLETED");
        assertThat(h.streamRuns()).isEqualTo(1);
        assertThat(egressFactoryAtSummary).as("摘要被调用, 且当时外发按本厂脱敏").containsExactly(FACTORY);
        assertThat(analysed.get()).as("作用域结束后还原").isNull();
    }

    private static long invocationsOf(Object mock, String method) {
        return org.mockito.Mockito.mockingDetails(mock).getInvocations().stream()
                .filter(i -> method.equals(i.getMethod().getName())).count();
    }

    // ------------------------------------------------------------------ helpers

    private static IntentExecuteRequest req(String input) {
        return IntentExecuteRequest.builder().userInput(input).sessionId(SESSION).build();
    }

    private static Map<Entry, Harness> runAll(Consumer<Harness> setup, IntentExecuteRequest request) throws Exception {
        Map<Entry, Harness> runs = new EnumMap<>(Entry.class);
        for (Entry entry : Entry.values()) {
            Harness h = new Harness(entry);
            setup.accept(h);
            h.shown = h.send(copy(request));
            runs.put(entry, h);
        }
        return runs;
    }

    private static IntentExecuteRequest copy(IntentExecuteRequest r) {
        return IntentExecuteRequest.builder().userInput(r.getUserInput()).intentCode(r.getIntentCode())
                .mode(r.getMode()).forceExecute(r.getForceExecute()).previewOnly(r.getPreviewOnly())
                .sessionId(r.getSessionId() != null ? r.getSessionId() : SESSION).build();
    }

    private static void assertSameStatusAndIntent(Map<Entry, Harness> runs, String expectedStatus) {
        IntentExecuteResponse execute = runs.get(Entry.EXECUTE).shown;
        IntentExecuteResponse stream = runs.get(Entry.STREAM).shown;
        assertThat(execute.getStatus()).as("/execute").isEqualTo(expectedStatus);
        assertThat(execute.getStatus()).as("/execute 与 /execute/stream 同状态").isEqualTo(stream.getStatus());
        assertThat(execute.getIntentCode()).as("/execute 与 /execute/stream 同意图").isEqualTo(stream.getIntentCode());
        Harness executed = runs.get(Entry.EXECUTE);
        assertThat(executed.orchestratorExecuteCalls()).as("/execute 没进原编排器 execute").isZero();
        assertThat(executed.streamRuns()).as("/execute 跑的是流式管线 (一次)").isEqualTo(1);
    }

    private static final class Harness {
        static final Map<String, Object> MASKED = Map.of("unitPrice", "***");

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
        final ResultFormatterService formatter = mock(ResultFormatterService.class);
        final DynamicToolSelectionService dynamicTools = mock(DynamicToolSelectionService.class);
        final PriceFieldResponseAdvice priceView = mock(PriceFieldResponseAdvice.class);
        final RuleEngineService ruleEngine = mock(RuleEngineService.class);
        final ProcessSecretAccessService auditStore = mock(ProcessSecretAccessService.class);
        final ProcessSecretAiAccessAudit audit = new ProcessSecretAiAccessAudit(auditStore,
                mock(ProcessSecretLlmCostScope.class));
        final com.cretas.aims.service.SlotFillingService slotFilling = mock(com.cretas.aims.service.SlotFillingService.class);
        final ChatBusinessFactGuard guard = new ChatBusinessFactGuard();
        final ConversationServiceImpl conversations;
        final ConversationSessionRepository sessionRepository = mock(ConversationSessionRepository.class);
        final com.cretas.aims.repository.conversation.ConversationMemoryRepository memoryRepository =
                mock(com.cretas.aims.repository.conversation.ConversationMemoryRepository.class);
        /** 编排器用的对话记忆 (默认桩, 什么也不记); useRealSessionServices 换成真实实现。 */
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        /** 流式用的对话记忆; null = 与改动前的裸构造一致。 */
        ConversationMemoryService streamMemory;
        final List<String> memoryWrites = new ArrayList<>();
        final List<String> executedIntents = new ArrayList<>();
        final AIIntentConfig readIntent;
        final AIIntentConfig writeIntent;
        final AIIntentConfig approvalIntent;
        final AIIntentConfig noToolIntent;
        IntentExecuteResponse toolResult;
        WorkdeskOutputSummarizer workdeskSummarizer;
        IntentExecuteResponse shown;
        IntentExecutionOrchestrator orchestrator;
        SseStreamingService sse;

        Harness(Entry entry) {
            this.entry = entry;
            knowledgeBase.initDefaults();
            conversations = new ConversationServiceImpl(sessionRepository,
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
            when(permissionGate.check(any(), eq(USER), eq(ROLE)))
                    .thenReturn(IntentPermissionGate.PermissionCheck.allowed());
            when(auditStore.audit(anyString(), any(), any(), any(), anyString(), anyString(), anyString())).thenReturn(true);

            readIntent = intent(READ_INTENT, "经营概况", READ_TOOL, ToolExecutor.AccessMode.READ);
            writeIntent = intent(WRITE_INTENT, "新建原料批次", WRITE_TOOL, ToolExecutor.AccessMode.WRITE);
            approvalIntent = intent(APPROVAL_INTENT, "成本差异复核", APPROVAL_TOOL, ToolExecutor.AccessMode.READ);
            approvalIntent.setRequiresApproval(true);
            intent(INVENTORY_INTENT, "库存报表", INVENTORY_TOOL, ToolExecutor.AccessMode.READ);
            intent(REPORT_WORK_INTENT, "完成工序任务", REPORT_WORK_TOOL, ToolExecutor.AccessMode.WRITE);
            noToolIntent = intent(NO_TOOL_INTENT, "今日客户跟进", null, ToolExecutor.AccessMode.READ);

            when(toolDispatch.executeWithTool(any(), anyString(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
                AIIntentConfig seen = inv.getArgument(3);
                executedIntents.add(seen.getIntentCode());
                if (toolResult != null) {
                    return toolResult;
                }
                return IntentExecuteResponse.builder().intentRecognized(true).intentCode(seen.getIntentCode())
                        .status("SUCCESS").message("本月销售额 ¥35,000，出货 1200 箱。")
                        .formattedText("本月销售额 ¥35,000，出货 1200 箱。").build();
            });
            when(toolDispatch.buildNoToolResponse(any())).thenAnswer(inv -> {
                AIIntentConfig seen = inv.getArgument(0);
                return IntentExecuteResponse.builder().intentRecognized(true).intentCode(seen.getIntentCode())
                        .status("NOT_SUPPORTED").message("暂不支持此类型的意图执行").build();
            });
            when(semanticCache.queryCache(anyString(), anyString())).thenReturn(SemanticCacheHit.miss(0L));
            when(slotFilling.checkAndStartSlotFilling(anyString(), any(), any(), any(), any())).thenAnswer(inv -> {
                AIIntentConfig seen = inv.getArgument(2);
                if (!WRITE_INTENT.equals(seen.getIntentCode()) && !REPORT_WORK_INTENT.equals(seen.getIntentCode())) {
                    return null;
                }
                return IntentExecuteResponse.builder().intentRecognized(true).intentCode(seen.getIntentCode())
                        .status("NEED_MORE_INFO").message("请提供要报的工序任务和产量").build();
            });
            when(priceView.maskedViewFor(any(), any())).thenAnswer(inv -> MASKED);
            when(formatter.formatAndSet(any(), any())).thenAnswer(inv -> inv.getArgument(0));
            recognizerAnswers(IntentMatchResult.builder().matchMethod(IntentMatchResult.MatchMethod.NONE)
                    .confidence(0.0).questionType(QuestionType.CONVERSATIONAL).build());
        }

        private AIIntentConfig intent(String code, String name, String toolName, ToolExecutor.AccessMode accessMode) {
            AIIntentConfig config = AIIntentConfig.builder().intentCode(code).intentName(name)
                    .intentCategory("QUERY").businessType("COMMON").sensitivityLevel("MEDIUM")
                    .requiredPermission("any:read").toolName(toolName).build();
            config.setIsActive(true);
            config.setRequiresApproval(false);
            when(configService.getIntentByCode(FACTORY, code)).thenReturn(Optional.of(config));
            when(recognizer.getIntentByCode(FACTORY, code)).thenReturn(Optional.of(config));
            when(recognizer.getIntentByCode(code)).thenReturn(Optional.of(config));
            when(recognizer.hasPermission(code, ROLE)).thenReturn(true);
            if (toolName == null) {
                return config;
            }
            ToolExecutor tool = mock(ToolExecutor.class);
            when(tool.getToolName()).thenReturn(toolName);
            when(tool.getAccessMode()).thenReturn(accessMode);
            when(toolRegistry.getExecutor(toolName)).thenReturn(Optional.of(tool));
            return config;
        }

        void toolReturns(IntentExecuteResponse response) {
            this.toolResult = response;
        }

        void recognizerAnswers(IntentMatchResult answer) {
            when(recognizer.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), any(), any(),
                    any(), any())).thenReturn(answer);
            when(recognizer.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), any(), any(),
                    any(), any(), any())).thenReturn(answer);
            when(recognizer.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), any(), any(),
                    any(), any(), any(), any())).thenReturn(answer);
        }

        void chatReplies(String reply) {
            when(llm.chat(anyString(), anyString())).thenReturn(reply);
            doAnswer(inv -> {
                Consumer<String> tokens = inv.getArgument(1);
                tokens.accept(reply);
                ChatCompletionResponse done = mock(ChatCompletionResponse.class);
                when(done.getContent()).thenReturn(reply);
                Consumer<ChatCompletionResponse> complete = inv.getArgument(2);
                complete.accept(done);
                return null;
            }).when(llm).chatCompletionStream(any(), any(), any());
        }

        /** 真实的会话记忆 / 会话服务 (只桩仓库那一层), 编排器与流式共用。 */
        void useRealSessionServices() {
            ConversationMemoryService real = new com.cretas.aims.service.impl.ConversationMemoryServiceImpl(
                    memoryRepository, mock(DashScopeClient.class));
            when(memoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            memory = real;
            streamMemory = real;
        }

        /** 编排器的会话记忆: 本人本页会话存在, 助手那句记进 memoryWrites。 */
        void recordMemory() {
            when(memory.getOrCreateContext(FACTORY, USER, SESSION)).thenReturn(
                    com.cretas.aims.dto.conversation.ConversationContext.builder()
                            .sessionId(SESSION).factoryId(FACTORY).userId(USER).build());
            doAnswer(inv -> {
                com.cretas.aims.dto.conversation.ConversationMessage m = inv.getArgument(1);
                if (m.getRole() == com.cretas.aims.dto.conversation.ConversationMessage.Role.ASSISTANT) {
                    memoryWrites.add(m.getContent());
                }
                return null;
            }).when(memory).addMessage(eq(SESSION), any());
        }

        List<String> assistantMemory() {
            return memoryWrites;
        }

        AuthenticatedActor actor() {
            return new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, USER, FACTORY, ROLE);
        }

        long recognitionCalls() {
            return invocations(recognizer, "recognizeIntentWithConfidence");
        }

        long slotFillingCalls() {
            return invocations(slotFilling, "checkAndStartSlotFilling");
        }

        long ruleEngineCalls() {
            return invocations(ruleEngine, "executeRulesWithAudit");
        }

        long dynamicSelectionCalls() {
            return invocations(dynamicTools, "executeWithDynamicToolSelection");
        }

        long orchestratorExecuteCalls() {
            return orchestrator == null ? 0 : invocations(orchestrator, "execute");
        }

        long streamRuns() {
            return sse == null ? 0 : invocations(sse, "runOnCurrentThread");
        }

        /** 审计写进库的 resource_ref, 每条一项。 */
        List<String> auditRefs() {
            List<String> refs = new ArrayList<>();
            for (Invocation i : org.mockito.Mockito.mockingDetails(auditStore).getInvocations()) {
                if ("audit".equals(i.getMethod().getName()) && i.getArgument(3) == ProcessSecretAction.AI_ACCESS) {
                    refs.add(i.getArgument(6));
                }
            }
            return refs;
        }

        private static long invocations(Object mockOrSpy, String method) {
            return org.mockito.Mockito.mockingDetails(mockOrSpy).getInvocations().stream()
                    .filter(i -> method.equals(i.getMethod().getName()))
                    .count();
        }

        IntentExecuteResponse send(IntentExecuteRequest request) throws Exception {
            if (entry != Entry.STREAM) {
                return sendThroughFacade(request);
            }
            try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(actor())) {
                build();
                ReflectionTestUtils.invokeMethod(sse, "executeStreamAsync", mock(SseEmitter.class), FACTORY,
                        request, USER, ROLE, new F006BossQuestionClient.Cancellation());
                List<Object> results = org.mockito.Mockito.mockingDetails(sse).getInvocations().stream()
                        .filter(i -> "sendSseEvent".equals(i.getMethod().getName())
                                && ("result".equals(i.getArgument(1)) || "error".equals(i.getArgument(1))))
                        .map(i -> (Object) i.getArgument(2))
                        .toList();
                assertThat(results).as("流式入口发了且只发了一次 result (没有 error)").hasSize(1);
                assertThat(results.get(0)).isInstanceOf(IntentExecuteResponse.class);
                return (IntentExecuteResponse) results.get(0);
            }
        }

        IntentExecuteResponse sendThroughFacade(IntentExecuteRequest request) throws Exception {
            try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(actor())) {
                build();
                IntentExecutorServiceImpl facade = new IntentExecutorServiceImpl(orchestrator, sse,
                        mock(MultiIntentExecutionService.class), mock(ConversationMemoryService.class));
                ReflectionTestUtils.setField(facade, "streamTurnCollector", collector());
                return facade.execute(FACTORY, request, USER, ROLE);
            }
        }

        StreamTurnCollector collector() throws Exception {
            build();
            StreamTurnCollector collector = new StreamTurnCollector(sse, orchestrator, knowledgeBase);
            ReflectionTestUtils.setField(collector, "queryPreprocessorService", preprocessor);
            ReflectionTestUtils.setField(collector, "aiAccessAudit", audit);
            ReflectionTestUtils.setField(collector, "viaStream", entry != Entry.LEGACY);
            return collector;
        }

        private void build() throws Exception {
            if (orchestrator == null) {
                orchestrator = ordinary();
            }
            if (sse == null) {
                sse = sse();
                ReflectionTestUtils.setField(sse, "ordinaryEntrySteps", orchestrator);
            }
        }

        private IntentExecutionOrchestrator ordinary() {
            IntentExecutionOrchestrator o = new IntentExecutionOrchestrator(
                    recognizer, mock(IntentSemanticsParser.class), semanticCache,
                    ruleEngine, conversations, memory,
                    new ObjectMapper(), llm, llmConfig,
                    knowledgeBase, mock(AIAnalysisResultRepository.class), toolRegistry,
                    mock(AnalysisRouterService.class), mock(ComplexityRouter.class), mock(AgentOrchestrator.class),
                    rag, mock(ResultValidatorService.class), toolDispatch,
                    dynamicTools, preprocessor);
            ReflectionTestUtils.setField(o, "intentPermissionGate", permissionGate);
            ReflectionTestUtils.setField(o, "writeGuardService", new WriteGuardService());
            ReflectionTestUtils.setField(o, "configService", configService);
            ReflectionTestUtils.setField(o, "chatBusinessFactGuard", guard);
            ReflectionTestUtils.setField(o, "resultFormatterService", formatter);
            ReflectionTestUtils.setField(o, "priceView", priceView);
            ReflectionTestUtils.setField(o, "slotFillingService", slotFilling);
            ReflectionTestUtils.setField(o, "validationEnabled", true);
            ReflectionTestUtils.setField(o, "aiAccessAudit", audit);
            if (workdeskSummarizer != null) {
                ReflectionTestUtils.setField(o, "workdeskOutputSummarizer", workdeskSummarizer);
            }
            BusinessTypeGate businessTypeGate = mock(BusinessTypeGate.class);
            when(businessTypeGate.check(anyString(), any())).thenReturn(Optional.empty());
            ReflectionTestUtils.setField(o, "businessTypeGate", businessTypeGate);
            FactoryAnalysisTestMetadata.bindNamedFactories(o);
            return spy(o);
        }

        private SseStreamingService sse() throws Exception {
            FactoryRepository factories = mock(FactoryRepository.class);
            Factory factory = new Factory();
            factory.setId(FACTORY);
            factory.setType(FactoryType.FACTORY);
            factory.setIsActive(true);
            when(factories.findById(anyString())).thenReturn(Optional.of(factory));
            SseStreamingService s = new SseStreamingService(
                    recognizer, semanticCache, knowledgeBase, mock(AnalysisRouterService.class),
                    new ObjectMapper().findAndRegisterModules(), // 与 Spring 注入的同样认 LocalDateTime
                    toolRegistry, toolDispatch, dynamicTools, llm, llmConfig);
            ReflectionTestUtils.setField(s, "factoryCapabilityPackRoutingPolicy", new FactoryCapabilityPackRoutingPolicy(
                    factories, new FactoryCapabilityPackSelector(new FactoryCapabilityPackRegistry()), false));
            FactoryAnalysisTestMetadata.bindNamedFactories(s);
            ReflectionTestUtils.setField(s, "writeGuardService", new WriteGuardService());
            ReflectionTestUtils.setField(s, "conversationService", conversations);
            ReflectionTestUtils.setField(s, "intentPermissionGate", permissionGate);
            ReflectionTestUtils.setField(s, "queryPreprocessorService", preprocessor);
            ReflectionTestUtils.setField(s, "chatBusinessFactGuard", guard);
            ReflectionTestUtils.setField(s, "resultFormatterService", formatter);
            ReflectionTestUtils.setField(s, "priceView", priceView);
            ReflectionTestUtils.setField(s, "slotFillingService", slotFilling);
            if (streamMemory != null) {
                ReflectionTestUtils.setField(s, "conversationMemoryService", streamMemory);
            }
            return spy(s);
        }
    }
}
