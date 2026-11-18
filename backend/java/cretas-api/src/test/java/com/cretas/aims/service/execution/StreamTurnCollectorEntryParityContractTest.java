package com.cretas.aims.service.execution;

import com.cretas.aims.ai.capability.FactoryBusinessTypeResolver;
import com.cretas.aims.ai.capability.FactoryCapabilityPackRegistry;
import com.cretas.aims.ai.capability.FactoryCapabilityPackRoutingPolicy;
import com.cretas.aims.ai.capability.FactoryCapabilityPackSelector;
import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.client.F006BossQuestionClient;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
import com.cretas.aims.ai.grounding.ChatBusinessFactGuard;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRbacGuard;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.config.IntentKnowledgeBase.QuestionType;
import com.cretas.aims.dto.ai.AnalysisResult;
import com.cretas.aims.dto.ai.AnalysisTopic;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.ai.ProcessingMode;
import com.cretas.aims.dto.ai.RAGRouteResult;
import com.cretas.aims.dto.cache.SemanticCacheHit;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.dto.intent.ValidationResult;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.crew.DigitalCrewDelegationLock;
import com.cretas.aims.entity.crew.DigitalCrewWorkItem;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.repository.conversation.ConversationSessionRepository;
import com.cretas.aims.repository.crew.DigitalCrewDelegationLockRepository;
import com.cretas.aims.repository.crew.DigitalCrewMemberSettingRepository;
import com.cretas.aims.repository.crew.DigitalCrewRoleMappingRepository;
import com.cretas.aims.repository.crew.DigitalCrewSettingLogRepository;
import com.cretas.aims.repository.crew.DigitalCrewWorkItemRepository;
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
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.service.QueryPreprocessorService;
import com.cretas.aims.service.ResultFormatterService;
import com.cretas.aims.service.ResultValidatorService;
import com.cretas.aims.service.RuleEngineService;
import com.cretas.aims.service.SemanticCacheService;
import com.cretas.aims.service.crew.DigitalCrewService;
import com.cretas.aims.service.impl.ConversationServiceImpl;
import com.cretas.aims.service.impl.IntentExecutorServiceImpl;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretAiAccessAudit;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 意图蒸馏第③步 PR-3: 识别 / 执行只留流式一条管线。
 *
 * <p>两个入口, 都是产品真实入口方法:
 * <ul>
 *   <li>{@code EXECUTE}: 门面 {@link IntentExecutorServiceImpl#execute} (/execute, 经 {@link StreamTurnCollector});</li>
 *   <li>{@code STREAM}: {@code SseStreamingService#executeStreamAsync} —— App 的 /execute/stream。</li>
 * </ul>
 * 对齐类用例断言两入口的 status + intentCode 相同, 并证明 EXECUTE 真的走了流式管线 (编排器 {@code execute} 0 次、
 * 流式管线 1 次)。PR-2b 时留在原分支的几类 (否定说法、平台账号、早期通用问法 / 分析流、无 sessionId、预览、
 * 多意图回退) 每类一条两入口对照; 原普通入口独有的步骤 (Drools、动态选工具、补问、文字兜底、对话记忆) 两个入口都跑。
 * 真的: 两个入口类、编排器、{@code IntentKnowledgeBase}、{@code WriteGuardService}、闲聊护栏、
 * {@code ProcessSecretAiAccessAudit} (只桩写库那一层)、会话服务 (用到时只桩仓库)。桩: 识别、权限、工具分发、模型、
 * 价格判定、格式化、追问、Drools 引擎、分析路由。
 */
@DisplayName("PR-3 识别/执行只留流式一条管线: 同一句话两入口同状态")
class StreamTurnCollectorEntryParityContractTest {

    enum Entry { EXECUTE, STREAM }

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
    /** 声明需要审批的写操作, 工具也是「先选要操作哪一个」那类 —— 顺序错了 (先列选项 / 先出确认卡) 就会露出来。 */
    private static final String APPROVAL_WRITE_INTENT = "MATERIAL_BATCH_DELETE";
    private static final String APPROVAL_WRITE_TOOL = "material_batch_delete";
    /** 本人这一页的会话 (Web / App 都带)。 */
    private static final String SESSION = "web-session-1";
    private static final String APP_SESSION = "app-session-1";
    private static final String CHAT_REPLY = "降低成本可以从优化采购批量、减少损耗、提高设备稼动率入手。";

    // ------------------------------------------------------------------ 对齐: 同句两入口同状态

    @Test
    @DisplayName("「怎么降低成本？」→ 两入口都直答 COMPLETED")
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
    @DisplayName("报工第 1 轮 (「我要报工」) → 两入口都先追问参数 NEED_MORE_INFO, 都不执行")
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
    }

    @Test
    @DisplayName("审批: 声明需要审批 + forceExecute → 两入口都停在 PENDING_APPROVAL, 不执行")
    void approvalHasSameStatusOnBothEntries() throws Exception {
        String input = "看下成本差异复核";
        Map<Entry, Harness> runs = runAll(h -> h.recognizerAnswers(IntentMatchResult.builder().userInput(input)
                .bestMatch(h.approvalIntent).matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build()),
                IntentExecuteRequest.builder().userInput(input).forceExecute(true).build());

        assertSameStatusAndIntent(runs, "PENDING_APPROVAL");
        for (Entry entry : Entry.values()) {
            assertThat(runs.get(entry).executedIntents).as(entry + ": 执行层没碰").isEmpty();
        }
    }

    @Test
    @DisplayName("咨询页拦写: mode=READ 识别到写意图 → 两入口都 READ_MODE_WRITE_BLOCKED, 没开始追问")
    void readModeWriteBlockHasSameStatusOnBothEntries() throws Exception {
        String input = "那批到货的鸡腿帮我登记进系统";
        Map<Entry, Harness> runs = runAll(h -> h.recognizerAnswers(IntentMatchResult.builder().userInput(input)
                .bestMatch(h.writeIntent).matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build()),
                IntentExecuteRequest.builder().userInput(input).mode("READ").build());

        assertSameStatusAndIntent(runs, "READ_MODE_WRITE_BLOCKED");
        assertThat(runs.get(Entry.EXECUTE).slotFillingCalls()).as("没开始追问参数").isZero();
    }

    // ------------------------------------------------------------------ PR-3 迁过来的几类 (每类一条两入口对照)

    @Test
    @DisplayName("#9 否定说法 VETO_READ (「别查库存了」): 两入口都在续轮 / 选项回复之前先澄清, 会话一次都没读, 不识别不执行")
    void readVetoClarifiesBeforeAnyConversationStateOnBothEntries() throws Exception {
        String input = "别查库存了";
        Map<Entry, Harness> runs = runAll(h -> {
            h.useRealSessionServices();
            when(h.preprocessor.detectNegationVeto(eq(input), any()))
                    .thenReturn(QueryPreprocessorService.NegationKind.VETO_READ);
        }, IntentExecuteRequest.builder().userInput(input).build());

        assertSameStatusAndIntent(runs, "NEED_CLARIFICATION");
        for (Entry entry : Entry.values()) {
            Harness h = runs.get(entry);
            assertThat(h.recognitionCalls()).as(entry + ": 不识别").isZero();
            assertThat(h.executedIntents).as(entry + ": 不执行").isEmpty();
            assertThat(invocationsOf(h.sessionRepository, "findBySessionIdAndFactoryIdAndUserId"))
                    .as(entry + ": 选项回复 / 缺参续轮都没轮到 (会话表 0 次)").isZero();
        }
        // 阳性对照: 同样的会话服务, 不是否定的一句会去读这一页的缺参 / 选项会话 (仪器是活的)
        Map<Entry, Harness> control = runAll(h -> {
            h.useRealSessionServices();
            org.mockito.Mockito.doReturn(QuestionType.OPERATIONAL_COMMAND).when(h.knowledgeBase).detectQuestionType("100");
            h.chatReplies(CHAT_REPLY);
        }, IntentExecuteRequest.builder().userInput("100").build());
        for (Entry entry : Entry.values()) {
            assertThat(invocationsOf(control.get(entry).sessionRepository, "findBySessionIdAndFactoryIdAndUserId"))
                    .as(entry + ": 不是否定时会读会话").isPositive();
        }
    }

    @Test
    @DisplayName("#9 否定说法 VETO_WRITE (「不要报工」): 两入口都不走短语快捷、交给识别 (识别层的否决闸), 不执行")
    void writeVetoSkipsShortcutsAndGoesToRecognitionOnBothEntries() throws Exception {
        String input = "不要报工";
        Map<Entry, Harness> runs = runAll(h -> {
            // 前提: 不看否定的话短语表会接这句 (守的是「含短语的否定句不被短语快捷抢走」)
            org.mockito.Mockito.doReturn(Optional.of(REPORT_WORK_INTENT)).when(h.knowledgeBase).matchPhrase(input, "FACTORY");
            when(h.preprocessor.detectNegationVeto(eq(input), any()))
                    .thenReturn(QueryPreprocessorService.NegationKind.VETO_WRITE);
            h.recognizerAnswers(IntentMatchResult.builder().userInput(input)
                    .matchMethod(IntentMatchResult.MatchMethod.REJECTED).confidence(0.0)
                    .questionType(QuestionType.OPERATIONAL_COMMAND).build());
        }, IntentExecuteRequest.builder().userInput(input).mode("OPERATE").build());

        assertSameStatusAndIntent(runs, "NEED_CLARIFICATION");
        for (Entry entry : Entry.values()) {
            assertThat(runs.get(entry).recognitionCalls()).as(entry + ": 短语快捷没抢, 交给识别").isEqualTo(1);
            assertThat(runs.get(entry).executedIntents).as(entry + ": 不执行").isEmpty();
        }
    }

    @Test
    @DisplayName("#15 平台账号 (不是本厂工厂账号): 不带会话两入口同状态 (无状态识别); 点名会话两入口都拒")
    void platformActorHasSameStatusOnBothEntries() throws Exception {
        String input = "看下经营概况";
        AuthenticatedActor platform = new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, USER, null, ROLE);
        Map<Entry, Harness> stateless = runAllExact(h -> {
            h.actorOverride = platform;
            h.recognizerAnswers(IntentMatchResult.builder().userInput(input).bestMatch(h.readIntent)
                    .matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                    .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build());
        }, IntentExecuteRequest.builder().userInput(input).build());
        assertSameStatusAndIntent(stateless, "SUCCESS");

        Map<Entry, Harness> named = runAll(h -> {
            h.actorOverride = platform;
            h.recognizerAnswers(IntentMatchResult.builder().userInput(input).bestMatch(h.readIntent)
                    .matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                    .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build());
        }, IntentExecuteRequest.builder().userInput(input).build());
        assertSameStatusAndIntent(named, "SESSION_OWNERSHIP_DENIED");
        for (Entry entry : Entry.values()) {
            assertThat(named.get(entry).executedIntents).as(entry + ": 不执行").isEmpty();
        }
    }

    @Test
    @DisplayName("#16 早期通用问法 / 分析流: 「分析一下最近的成本走势」两入口都进分析流 (复杂度路由 → 多代理), 不识别")
    void earlyAnalysisRequestRunsTheAnalysisFlowOnBothEntries() throws Exception {
        String input = "分析一下最近的成本走势";
        String analysis = "近 30 天单位成本小幅上升，主要来自包材与能耗。";
        Map<Entry, Harness> runs = runAll(h -> {
            org.mockito.Mockito.doReturn(QuestionType.GENERAL_QUESTION).when(h.knowledgeBase).detectQuestionType(input);
            when(h.analysisRouter.isAnalysisRequest(input, QuestionType.GENERAL_QUESTION)).thenReturn(true);
            when(h.analysisRouter.detectAnalysisTopic(input)).thenReturn(AnalysisTopic.values()[0]);
            when(h.complexityRouter.route(eq(input), any())).thenReturn(ProcessingMode.MULTI_AGENT);
            when(h.agents.executeCollaborativeAnalysis(any())).thenReturn(
                    AnalysisResult.builder().success(true).formattedAnalysis(analysis).build());
        }, IntentExecuteRequest.builder().userInput(input).build());

        assertSameStatusAndIntent(runs, "ANALYSIS_COMPLETED");
        for (Entry entry : Entry.values()) {
            Harness h = runs.get(entry);
            assertThat(h.shown.getMessage()).as(entry.name()).isEqualTo(analysis);
            assertThat(invocationsOf(h.agents, "executeCollaborativeAnalysis")).as(entry + ": 多代理跑了一次").isEqualTo(1);
            assertThat(h.recognitionCalls()).as(entry + ": 早期就接住, 不识别").isZero();
        }
    }

    // ------------------------------------------------------------------ PR-3 复审 M2: 分析流准入 (两入口共用一处)

    private static final String ANALYSIS_INPUT = "分析一下最近的成本走势";
    private static final String ANALYSIS_TEXT = "分析结论：成本上升主要来自包材与能耗。";
    /** 存档经营报告里的一句 (兜底时会被拼进模型提示词); 模型桩把系统提示词原样回出来, 报告进没进回复一看便知。 */
    private static final String REPORT_MARKER = "日报存档采购单价九九八八元每公斤";

    /**
     * 早期判为分析问法 → 分析流。权限按真实角色矩阵判 ({@link PermissionServiceImpl} + {@link ToolRbacGuard} +
     * {@link IntentPermissionGate}, 只桩用户仓库): 分析流准入那一次检查交给真实的门, 其它意图检查照旧放行。
     */
    private static void analysisTurn(Harness h, String role, boolean analysisSucceeds, boolean seesFinanceFigures) {
        org.mockito.Mockito.doReturn(QuestionType.GENERAL_QUESTION).when(h.knowledgeBase).detectQuestionType(ANALYSIS_INPUT);
        when(h.analysisRouter.isAnalysisRequest(ANALYSIS_INPUT, QuestionType.GENERAL_QUESTION)).thenReturn(true);
        when(h.analysisRouter.detectAnalysisTopic(ANALYSIS_INPUT)).thenReturn(AnalysisTopic.values()[0]);
        when(h.complexityRouter.route(eq(ANALYSIS_INPUT), any())).thenReturn(ProcessingMode.MULTI_AGENT);
        when(h.agents.executeCollaborativeAnalysis(any())).thenReturn(analysisSucceeds
                ? AnalysisResult.builder().success(true).formattedAnalysis(ANALYSIS_TEXT).build()
                : AnalysisResult.builder().success(false).build());
        IntentPermissionGate realGate = realPermissionGate(role);
        when(h.permissionGate.check(org.mockito.ArgumentMatchers.argThat(i -> i != null
                        && IntentExecutionOrchestrator.ANALYSIS_FLOW_PERMISSION.equals(i.getRequiredPermission())),
                any(), any()))
                .thenAnswer(inv -> realGate.check(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        com.cretas.aims.entity.AIAnalysisResult report = new com.cretas.aims.entity.AIAnalysisResult();
        report.setAnalysisText(REPORT_MARKER);
        when(h.reports.findFirstByFactoryIdAndReportTypeAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(FACTORY), anyString(), any())).thenReturn(Optional.of(report));
        when(h.priceView.seesFinanceFigures(USER)).thenReturn(seesFinanceFigures);
        when(h.llm.chat(anyString(), anyString())).thenAnswer(inv -> "建议：" + inv.getArgument(0));
    }

    private static IntentPermissionGate realPermissionGate(String role) {
        PermissionServiceImpl matrix = new PermissionServiceImpl();
        com.cretas.aims.testutil.PermissionRepositoryFixtures.installSuccessfulEmpty(matrix);
        UserRepository users = mock(UserRepository.class);
        User caller = new User();
        caller.setId(USER);
        caller.setFactoryId(FACTORY);
        caller.setRoleCode(role);
        caller.setIsActive(true);
        when(users.findById(USER)).thenReturn(Optional.of(caller));
        ToolRbacGuard rbac = new ToolRbacGuard();
        ReflectionTestUtils.setField(rbac, "userRepository", users);
        ReflectionTestUtils.setField(rbac, "permissionService", matrix);
        IntentPermissionGate gate = new IntentPermissionGate();
        ReflectionTestUtils.setField(gate, "rbacGuard", rbac);
        return gate;
    }

    /** 两入口都跑在「本人」的可信用户作用域里 (生产: /execute 有 servlet 请求的 userId, App 流在工作线程上绑同一个)。 */
    private static Map<Entry, Harness> runAnalysis(Consumer<Harness> setup) throws Exception {
        try (com.cretas.aims.utils.SecurityUtils.Scope ignored =
                     com.cretas.aims.utils.SecurityUtils.trustedUserIdScope(USER)) {
            return runAll(setup, IntentExecuteRequest.builder().userInput(ANALYSIS_INPUT).build());
        }
    }

    private static String shownText(IntentExecuteResponse r) {
        return String.valueOf(r.getMessage()) + "\n" + r.getFormattedText();
    }

    private static long analysisGateChecks(Harness h) {
        return org.mockito.Mockito.mockingDetails(h.permissionGate).getInvocations().stream()
                .filter(i -> "check".equals(i.getMethod().getName()))
                .filter(i -> i.getArgument(0) instanceof AIIntentConfig c
                        && IntentExecutionOrchestrator.ANALYSIS_FLOW_PERMISSION.equals(c.getRequiredPermission()))
                .count();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"operator", "warehouse_worker"})
    @DisplayName("M2 没有 analytics 的操作员 / 仓管问分析问法: 两入口都 NO_PERMISSION, 不进分析、不读存档报告、不调模型; /execute envelope success=false")
    void analysisFlowWithoutAnalyticsIsRefusedOnBothEntries(String role) throws Exception {
        // 分析本身失败 + 看得到财务数字: 去掉准入时会走「存档报告兜底」并把报告回给用户 (变异会红在这里)
        Map<Entry, Harness> runs = runAnalysis(h -> analysisTurn(h, role, false, true));

        assertSameStatusAndIntent(runs, "NO_PERMISSION");
        for (Entry entry : Entry.values()) {
            Harness h = runs.get(entry);
            assertThat(analysisGateChecks(h)).as(entry + ": 分析流准入真的问了权限门 (仪器是活的)").isEqualTo(1);
            assertThat(h.shown.getRequiredPermission()).as(entry.name())
                    .isEqualTo(IntentExecutionOrchestrator.ANALYSIS_FLOW_PERMISSION);
            assertThat(shownText(h.shown)).as(entry + ": 回复里没有存档报告")
                    .doesNotContain(REPORT_MARKER).contains("需要权限");
            assertThat(invocationsOf(h.complexityRouter, "route")).as(entry + ": 没进复杂度路由").isZero();
            assertThat(invocationsOf(h.agents, "executeCollaborativeAnalysis")).as(entry + ": 没跑分析").isZero();
            assertThat(invocationsOf(h.reports, "findFirstByFactoryIdAndReportTypeAndExpiresAtAfterOrderByCreatedAtDesc"))
                    .as(entry + ": 没读存档经营报告").isZero();
            assertThat(h.llmChatCalls()).as(entry + ": 没调模型").isZero();
            assertThat(h.recognitionCalls()).as(entry + ": 也没转去识别").isZero();
        }
        assertThat(envelope(runs.get(Entry.EXECUTE).shown).getSuccess()).as("/execute envelope 不报成功").isFalse();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"dispatcher", "finance_manager"})
    @DisplayName("M2 阳性对照: 有 analytics 的角色问同一句, 两入口都进分析流拿到回答 (同一个真实权限门)")
    void analysisFlowWithAnalyticsAnswersOnBothEntries(String role) throws Exception {
        Map<Entry, Harness> runs = runAnalysis(h -> analysisTurn(h, role, true, true));

        assertSameStatusAndIntent(runs, "ANALYSIS_COMPLETED");
        for (Entry entry : Entry.values()) {
            Harness h = runs.get(entry);
            assertThat(analysisGateChecks(h)).as(entry + ": 问了权限门").isEqualTo(1);
            assertThat(h.shown.getMessage()).as(entry.name()).isEqualTo(ANALYSIS_TEXT);
            assertThat(invocationsOf(h.agents, "executeCollaborativeAnalysis")).as(entry + ": 分析跑了一次").isEqualTo(1);
        }
        assertThat(envelope(runs.get(Entry.EXECUTE).shown).getSuccess()).isTrue();
    }

    @Test
    @DisplayName("M2 有 analytics、分析失败走存档报告兜底: 看不到财务数字的人拿不到报告 (遮价), 看得到的人拿得到 (阳性对照)")
    void analysisFallbackShowsTheStoredReportOnlyToCallersWhoSeeFinanceFigures() throws Exception {
        Map<Entry, Harness> hidden = runAnalysis(h -> analysisTurn(h, "dispatcher", false, false));
        Map<Entry, Harness> shown = runAnalysis(h -> analysisTurn(h, "dispatcher", false, true));

        assertSameStatusAndIntent(hidden, "COMPLETED");
        assertSameStatusAndIntent(shown, "COMPLETED");
        for (Entry entry : Entry.values()) {
            Harness h = hidden.get(entry);
            assertThat(h.llmChatCalls()).as(entry + ": 兜底真的调了模型 (一定会发生)").isEqualTo(1);
            assertThat(shownText(h.shown)).as(entry + ": 看不到财务数字 → 报告没进模型、也没进回复")
                    .doesNotContain(REPORT_MARKER);
            assertThat(invocationsOf(h.reports, "findFirstByFactoryIdAndReportTypeAndExpiresAtAfterOrderByCreatedAtDesc"))
                    .as(entry + ": 没读存档报告").isZero();
            assertThat(shownText(shown.get(entry).shown)).as(entry + ": 阳性对照 —— 看得到财务数字的人, 报告进了模型")
                    .contains(REPORT_MARKER);
        }
    }

    // ------------------------------------------------------------------ PR-3 复审 M1: 拒绝不许报成功

    @Test
    @DisplayName("M1 平台账号点名会话: /execute 回 SESSION_OWNERSHIP_DENIED, envelope success=false (不执行)")
    void platformNamedSessionIsNotEnvelopedAsSuccess() throws Exception {
        String input = "看下经营概况";
        AuthenticatedActor platform = new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, USER, null, ROLE);
        Map<Entry, Harness> named = runAll(h -> {
            h.actorOverride = platform;
            h.recognizerAnswers(IntentMatchResult.builder().userInput(input).bestMatch(h.readIntent)
                    .matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                    .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build());
        }, IntentExecuteRequest.builder().userInput(input).build());
        assertSameStatusAndIntent(named, "SESSION_OWNERSHIP_DENIED");
        assertThat(named.get(Entry.EXECUTE).executedIntents).isEmpty();
        com.cretas.aims.dto.common.ApiResponse<IntentExecuteResponse> body = envelope(named.get(Entry.EXECUTE).shown);
        assertThat(body.getSuccess()).as("会话归属被拒, envelope 不许报成功").isFalse();
        assertThat(body.getMessage()).isNotBlank().isNotEqualTo("操作成功");
        assertThat(body.getData()).as("data 仍带上").isSameAs(named.get(Entry.EXECUTE).shown);

        // 阳性对照: 同一个平台账号不点名会话 (无状态识别) 照常成功, envelope success=true
        Map<Entry, Harness> stateless = runAllExact(h -> {
            h.actorOverride = platform;
            h.recognizerAnswers(IntentMatchResult.builder().userInput(input).bestMatch(h.readIntent)
                    .matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                    .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build());
        }, IntentExecuteRequest.builder().userInput(input).build());
        assertSameStatusAndIntent(stateless, "SUCCESS");
        assertThat(envelope(stateless.get(Entry.EXECUTE).shown).getSuccess()).isTrue();
    }

    // ------------------------------------------------------------------ #1788 合入: 问别家成本/价格/毛利, 识别之前拒

    @Test
    @DisplayName("#1788 竞品毛利问法: /execute 普通入口 (门面 → 流式管线) 与 App 流式都在识别之前回 EXTERNAL_DATA_REFUSED, 不识别不执行")
    void competitorMarginQuestionIsRefusedBeforeRecognitionOnBothEntries() throws Exception {
        String input = "同行的毛利率一般多少";
        assertThat(com.cretas.aims.ai.tool.OwnFactoryDataOnlyGuard.refusesBeforeRouting(input)).as("前提: 守卫认这句").isTrue();
        Map<Entry, Harness> runs = runAll(h -> h.recognizerAnswers(IntentMatchResult.builder().userInput(input)
                .bestMatch(h.readIntent).matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build()),
                IntentExecuteRequest.builder().userInput(input).build());

        assertSameStatusAndIntent(runs, com.cretas.aims.ai.tool.OwnFactoryDataOnlyGuard.STATUS);
        for (Entry entry : Entry.values()) {
            Harness h = runs.get(entry);
            assertThat(h.shown.getMessage()).as(entry.name()).isEqualTo(com.cretas.aims.ai.tool.OwnFactoryDataOnlyGuard.MESSAGE);
            assertThat(h.recognitionCalls()).as(entry + ": 识别之前就拒").isZero();
            assertThat(h.executedIntents).as(entry + ": 不执行").isEmpty();
            assertThat(invocationsOf(h.permissionGate, "check")).as(entry + ": 也没轮到权限门").isZero();
        }
        // 阳性对照: 本厂的同一指标照常进识别 (仪器是活的)
        String own = "哪个产品毛利率最高";
        assertThat(com.cretas.aims.ai.tool.OwnFactoryDataOnlyGuard.refusesBeforeRouting(own)).isFalse();
        Map<Entry, Harness> control = runAll(h -> h.recognizerAnswers(IntentMatchResult.builder().userInput(own)
                .bestMatch(h.readIntent).matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build()),
                IntentExecuteRequest.builder().userInput(own).build());
        for (Entry entry : Entry.values()) {
            assertThat(control.get(entry).recognitionCalls()).as(entry + ": 本厂问题进了识别").isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ PR-3 复审 L2: 共用步骤缺席 → 启动失败

    @Test
    @DisplayName("L2 每轮共用步骤 (编排器) 没装配: 启动校验抛错 (fail closed); 装配了就通过")
    void missingTurnStepsFailsStartup() throws Exception {
        Harness h = new Harness(Entry.STREAM);
        h.build();
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<IntentExecutionOrchestrator> absent =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        ReflectionTestUtils.setField(h.sse, "turnStepsBean", absent);
        org.assertj.core.api.Assertions.assertThatThrownBy(h.sse::verifyTurnStepsWired)
                .isInstanceOf(IllegalStateException.class);
        ReflectionTestUtils.setField(h.sse, "turnStepsBean", null);
        org.assertj.core.api.Assertions.assertThatThrownBy(h.sse::verifyTurnStepsWired)
                .as("ObjectProvider 本身没注入也按缺席").isInstanceOf(IllegalStateException.class);

        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<IntentExecutionOrchestrator> present =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(present.getIfAvailable()).thenReturn(h.orchestrator);
        ReflectionTestUtils.setField(h.sse, "turnStepsBean", present);
        h.sse.verifyTurnStepsWired();   // 阳性对照: 不抛
    }

    @SuppressWarnings("unchecked")
    private static com.cretas.aims.dto.common.ApiResponse<IntentExecuteResponse> envelope(IntentExecuteResponse r) {
        com.cretas.aims.controller.AIIntentConfigController controller = mock(
                com.cretas.aims.controller.AIIntentConfigController.class,
                org.mockito.Mockito.withSettings().defaultAnswer(org.mockito.Mockito.CALLS_REAL_METHODS));
        org.springframework.http.ResponseEntity<com.cretas.aims.dto.common.ApiResponse<IntentExecuteResponse>> entity =
                (org.springframework.http.ResponseEntity<com.cretas.aims.dto.common.ApiResponse<IntentExecuteResponse>>)
                        ReflectionTestUtils.invokeMethod(controller, "envelopeByInnerStatus", r);
        assertThat(entity).isNotNull();
        return entity.getBody();
    }

    @Test
    @DisplayName("中-1 无 sessionId: /execute 走流式但是独立一问 —— 不接上 App 正在问参数的那一轮; App 流式同一句也同状态")
    void sessionlessExecuteIsAStandaloneTurn() throws Exception {
        Map<Entry, Harness> runs = runAllExact(h -> {
            h.useRealSessionServices();
            // App 那边: 本人最近一次活跃会话 = app-session-1, 且正停在报工的缺参追问上
            com.cretas.aims.entity.conversation.ConversationMemory appMemory =
                    com.cretas.aims.entity.conversation.ConversationMemory.builder()
                            .factoryId(FACTORY).userId(USER).sessionId(APP_SESSION).build();
            appMemory.setLastActiveAt(java.time.LocalDateTime.now());
            when(h.memoryRepository.findLatestActiveByFactoryIdAndUserId(FACTORY, USER)).thenReturn(Optional.of(appMemory));
            when(h.memoryRepository.findBySessionId(APP_SESSION)).thenReturn(Optional.of(appMemory));
            if (h.entry == Entry.EXECUTE) {
                com.cretas.aims.entity.conversation.ConversationSession pending =
                        mock(com.cretas.aims.entity.conversation.ConversationSession.class);
                when(pending.getSessionId()).thenReturn(APP_SESSION);
                when(pending.isParameterCollectionMode()).thenReturn(true);
                when(pending.getStatus()).thenReturn(
                        com.cretas.aims.entity.conversation.ConversationSession.SessionStatus.ACTIVE);
                when(h.sessionRepository.findBySessionIdAndFactoryIdAndUserId(APP_SESSION, FACTORY, USER))
                        .thenReturn(Optional.of(pending));
                h.pendingAppSession = pending;
            }
            org.mockito.Mockito.doReturn(QuestionType.OPERATIONAL_COMMAND).when(h.knowledgeBase).detectQuestionType("100");
            h.chatReplies(CHAT_REPLY);
        }, IntentExecuteRequest.builder().userInput("100").build());

        Harness execute = runs.get(Entry.EXECUTE);
        assertThat(execute.streamRuns()).as("走的是流式管线").isEqualTo(1);
        assertThat(execute.orchestratorExecuteCalls()).isZero();
        assertThat(invocationsOf(execute.memoryRepository, "findLatestActiveByFactoryIdAndUserId"))
                .as("没去找本人最近一次会话").isZero();
        assertThat(invocationsOf(execute.sessionRepository, "findBySessionIdAndFactoryIdAndUserId"))
                .as("App 那一轮的缺参会话一次都没被读").isZero();
        assertThat(org.mockito.Mockito.mockingDetails(execute.pendingAppSession).getInvocations().stream()
                .filter(i -> i.getMethod().getName().startsWith("set") || i.getMethod().getName().startsWith("add"))
                .count()).as("也没被改").isZero();
        assertThat(execute.shown.getSessionId()).isNotEqualTo(APP_SESSION);
        assertThat(execute.shown.getStatus()).as("当成独立一问回答").isEqualTo("COMPLETED");
        // App 流式不带 sessionId 是另一份入口契约 (接上最近会话): 这里只钉 /execute 的独立一问不被接走
        assertThat(invocationsOf(runs.get(Entry.STREAM).memoryRepository, "findLatestActiveByFactoryIdAndUserId"))
                .as("阳性对照: App 流式不带 sessionId 会去接最近会话 (仪器是活的)").isPositive();
    }

    @Test
    @DisplayName("预览 (无意图码): 门面 preview 走流式管线 (编排器 0 次), 与 App 流式 previewOnly 同状态")
    void previewWithoutIntentCodeRunsTheStreamPipeline() throws Exception {
        String input = "看下经营概况";
        Harness h = new Harness(Entry.EXECUTE);
        h.recognizerAnswers(IntentMatchResult.builder().userInput(input).bestMatch(h.readIntent)
                .matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build());
        IntentExecuteResponse previewed;
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(h.actor())) {
            previewed = h.facade().preview(FACTORY, req(input), USER, ROLE);
        }
        assertThat(h.streamRuns()).isEqualTo(1);
        assertThat(h.orchestratorExecuteCalls()).isZero();

        Harness stream = new Harness(Entry.STREAM);
        stream.recognizerAnswers(IntentMatchResult.builder().userInput(input).bestMatch(stream.readIntent)
                .matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build());
        IntentExecuteResponse streamed = stream.send(IntentExecuteRequest.builder().userInput(input)
                .sessionId(SESSION).previewOnly(true).build());
        assertThat(previewed.getStatus()).isEqualTo(streamed.getStatus()).isEqualTo("SUCCESS");
        assertThat(h.previewFlagsSeenByTools).as("工具层收到的是只预览").containsExactly(true);
    }

    @Test
    @DisplayName("多意图回退 (不是多意图 / 功能关): 单意图那一轮走流式管线, 编排器 0 次")
    void multiIntentFallbackRunsTheStreamPipeline() throws Exception {
        String input = "看下经营概况";
        Harness h = new Harness(Entry.EXECUTE);
        h.recognizerAnswers(IntentMatchResult.builder().userInput(input).bestMatch(h.readIntent)
                .matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build());
        IntentExecuteResponse shown;
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(h.actor())) {
            h.build();
            MultiIntentExecutionService multi = new MultiIntentExecutionService(h.recognizer, h.orchestrator, h.sse,
                    h.configService);
            ReflectionTestUtils.setField(multi, "streamTurnCollector", h.collector());
            ReflectionTestUtils.setField(multi, "multiIntentEnabled", false);
            shown = multi.executeMultiIntent(FACTORY, req(input), USER, ROLE);
        }
        assertThat(shown.getStatus()).isEqualTo("SUCCESS");
        assertThat(h.streamRuns()).isEqualTo(1);
        assertThat(h.orchestratorExecuteCalls()).isZero();
    }

    @Test
    @DisplayName("#17 显式意图码 (按钮点选) 留编排器显式意图路径 (写确认收敛点); 声明审批的两入口都 PENDING_APPROVAL")
    void explicitIntentCodeStaysOnTheExplicitIntentPath() throws Exception {
        Map<Entry, Harness> runs = runAll(h -> { },
                IntentExecuteRequest.builder().userInput("看下成本差异复核").intentCode(APPROVAL_INTENT).build());
        Harness execute = runs.get(Entry.EXECUTE);
        assertThat(execute.orchestratorExecuteCalls()).as("显式意图码进编排器显式意图路径").isEqualTo(1);
        assertThat(execute.streamRuns()).isZero();
        assertThat(execute.shown.getStatus()).isEqualTo("PENDING_APPROVAL")
                .isEqualTo(runs.get(Entry.STREAM).shown.getStatus());
        for (Entry entry : Entry.values()) {
            assertThat(runs.get(entry).executedIntents).as(entry + ": 不执行").isEmpty();
        }
        assertThat(execute.collector().route(req("别查库存了"))).as("其余一律流式").isEqualTo(StreamTurnCollector.Route.STREAM);
        assertThat(execute.collector().route(IntentExecuteRequest.builder().userInput("看下").previewOnly(true).build()))
                .isEqualTo(StreamTurnCollector.Route.STREAM);
    }

    // ------------------------------------------------------------------ PR-3b 显式意图码: 两入口先判审批再出写确认

    @Test
    @DisplayName("PR-3b 要审批的写 (带意图码 + forceExecute): 两入口都 PENDING_APPROVAL, 不列选项、不出确认卡、不预览、不执行")
    void explicitApprovalWriteStopsBeforeTheConfirmCardOnBothEntries() throws Exception {
        Map<Entry, Harness> runs = runAll(Harness::choiceListingAsks, IntentExecuteRequest.builder()
                .userInput("把 0601 那批鸡腿删了").intentCode(APPROVAL_WRITE_INTENT).mode("OPERATE")
                .forceExecute(true).build());

        assertExplicitParity(runs, "PENDING_APPROVAL");
        for (Entry entry : Entry.values()) {
            Harness h = runs.get(entry);
            assertThat(h.shown.getMessage()).as(entry + ": 现有审批文案").contains("需要审批").contains("未执行操作");
            assertThat(h.shown.getRequiresApproval()).isTrue();
            // 阴性对照: 不出确认卡、不落库 (工具层 / 预览 / 工具本体都 0 次)
            assertThat(h.shown.getStatus()).isNotEqualTo("WRITE_CONFIRM_REQUIRED");
            assertThat(h.choiceListingCalls()).as(entry + ": 审批在列可选对象之前").isZero();
            assertThat(h.toolLayerCalls()).as(entry + ": 没到工具层").isZero();
            assertThat(h.toolPreviewCalls()).as(entry + ": 没出预览").isZero();
            assertThat(h.toolBodyCalls()).as(entry + ": 没写").isZero();
            assertThat(h.executedIntents).isEmpty();
        }
    }

    @Test
    @DisplayName("PR-3b 免审批的写 (带意图码): 两入口都出同一张 W0 写确认卡, 都不到工具层")
    void explicitWriteWithoutApprovalGetsTheSameConfirmCardOnBothEntries() throws Exception {
        Map<Entry, Harness> runs = runAll(h -> { }, IntentExecuteRequest.builder()
                .userInput("那批到货的鸡腿帮我登记进系统").intentCode(WRITE_INTENT).mode("OPERATE").build());

        assertExplicitParity(runs, "WRITE_CONFIRM_REQUIRED");
        assertThat(runs.get(Entry.EXECUTE).shown.getMessage()).isEqualTo(runs.get(Entry.STREAM).shown.getMessage())
                .contains("新建原料批次");
        for (Entry entry : Entry.values()) {
            assertThat(runs.get(entry).toolLayerCalls()).as(entry + ": 确认之前不到工具层").isZero();
            assertThat(runs.get(entry).toolBodyCalls()).isZero();
        }
    }

    @Test
    @DisplayName("PR-3b 只读 (带意图码): 两入口都执行一次, SUCCESS")
    void explicitReadExecutesOnBothEntries() throws Exception {
        Map<Entry, Harness> runs = runAll(h -> { }, IntentExecuteRequest.builder()
                .userInput("看下经营概况").intentCode(READ_INTENT).build());

        assertExplicitParity(runs, "SUCCESS");
        for (Entry entry : Entry.values()) {
            assertThat(runs.get(entry).executedIntents).as(entry.name()).containsExactly(READ_INTENT);
        }
    }

    @Test
    @DisplayName("PR-3b 需要先选对象的写 (带意图码报工): 两入口都先列可选对象 (抽参后), 不出空确认卡, 不执行")
    void explicitChoiceWriteListsChoicesFirstOnBothEntries() throws Exception {
        Map<Entry, Harness> runs = runAll(Harness::choiceListingAsks, IntentExecuteRequest.builder()
                .userInput("我要报工，产量100").intentCode(REPORT_WORK_INTENT).mode("OPERATE").build());

        assertExplicitParity(runs, "NEED_MORE_INFO");
        for (Entry entry : Entry.values()) {
            Harness h = runs.get(entry);
            assertThat(h.shown.getMessage()).as(entry.name()).startsWith("请选择要操作的那一个");
            assertThat(h.choiceListingCalls()).as(entry + ": 列了一次").isEqualTo(1);
            org.mockito.Mockito.verify(h.toolDispatch).askWhichOneBeforeWriteGuard(
                    any(), eq(FACTORY), any(), any(), eq(USER), eq(ROLE), eq(true));
            assertThat(h.toolLayerCalls()).as(entry + ": 不执行").isZero();
            assertThat(h.slotFillingCalls()).as(entry + ": 不走通用缺参追问").isZero();
        }
    }

    @Test
    @DisplayName("PR-3b 预览 (带意图码 previewOnly, 真实调度): 免审批的写两入口都走预览出口并各铸一张令牌 (阳性对照)")
    void explicitPreviewRunsThePreviewBranchOnBothEntries() throws Exception {
        Map<Entry, Harness> runs = runAll(h -> {
            h.useRealDispatch();
            uncheck(() -> h.toolPreviewsSuccessfully(WRITE_TOOL));
        }, IntentExecuteRequest.builder().userInput("先看下会改什么").intentCode(WRITE_INTENT).previewOnly(true).build());

        assertExplicitParity(runs, "PREVIEW");
        for (Entry entry : Entry.values()) {
            Harness h = runs.get(entry);
            assertThat(h.toolPreviewCalls()).as(entry + ": 预览一次").isEqualTo(1);
            assertThat(h.toolPreviewBodyCalls()).as(entry + ": 工具预览真的跑了").isEqualTo(1);
            assertThat(h.tokensMinted()).as(entry + ": 阳性对照: 铸令牌的仪器是活的").isEqualTo(1);
            assertThat(h.shown.getConfirmableAction()).as(entry.name()).isNotNull();
            assertThat(h.toolLayerCalls()).as(entry + ": 不进执行出口").isZero();
            assertThat(h.toolBodyCalls()).isZero();
        }
    }

    @Test
    @DisplayName("PR-3b 终审 M2: 要审批的写带 previewOnly → 两入口都 PENDING_APPROVAL, 不调预览、不铸令牌")
    void explicitApprovalWritePreviewStopsAtApprovalOnBothEntries() throws Exception {
        Map<Entry, Harness> runs = runAll(h -> {
            h.useRealDispatch();
            h.choiceListingAsks();
            uncheck(() -> h.toolPreviewsSuccessfully(APPROVAL_WRITE_TOOL));
        }, IntentExecuteRequest.builder().userInput("先看下会删什么").intentCode(APPROVAL_WRITE_INTENT)
                .previewOnly(true).build());

        assertExplicitParity(runs, "PENDING_APPROVAL");
        for (Entry entry : Entry.values()) {
            Harness h = runs.get(entry);
            assertThat(h.shown.getConfirmableAction()).as(entry + ": 没有确认按钮").isNull();
            assertThat(h.toolPreviewCalls()).as(entry + ": 预览出口 0 次").isZero();
            assertThat(h.toolPreviewBodyCalls()).as(entry + ": 工具预览 0 次").isZero();
            assertThat(h.tokensMinted()).as(entry + ": 令牌 0 张").isZero();
            assertThat(h.choiceListingCalls()).isZero();
        }
    }

    @Test
    @DisplayName("PR-3b 终审 M1: 本厂关了这个工具 → 带意图码的预览两入口都 TOOL_DISABLED, 不跑预览、不铸令牌")
    void explicitPreviewOfADisabledToolIsRefusedOnBothEntries() throws Exception {
        Map<Entry, Harness> runs = runAll(h -> {
            h.useRealDispatch();
            uncheck(() -> h.toolPreviewsSuccessfully(WRITE_TOOL));
            when(h.toolRegistry.isToolEnabledForFactory(FACTORY, WRITE_TOOL)).thenReturn(false);
        }, IntentExecuteRequest.builder().userInput("先看下会改什么").intentCode(WRITE_INTENT).previewOnly(true).build());

        assertExplicitParity(runs, "TOOL_DISABLED");
        assertPreviewRefusedWithoutToken(runs);
    }

    @Test
    @DisplayName("PR-3b 终审 M1: 旧逐工具角色检查过不了 → 带意图码的预览两入口都 PERMISSION_DENIED, 不跑预览、不铸令牌")
    void explicitPreviewDeniedByTheLegacyRoleCheckIsRefusedOnBothEntries() throws Exception {
        Map<Entry, Harness> runs = runAll(h -> {
            h.useRealDispatch();
            uncheck(() -> h.toolPreviewsSuccessfully(WRITE_TOOL));
            ToolExecutor tool = h.tools.get(WRITE_TOOL);
            when(tool.requiresPermission()).thenReturn(true);
            when(tool.getRequiredPermissions()).thenReturn(java.util.Set.of());
            when(tool.hasPermission(ROLE)).thenReturn(false);
        }, IntentExecuteRequest.builder().userInput("先看下会改什么").intentCode(WRITE_INTENT).previewOnly(true).build());

        assertExplicitParity(runs, "PERMISSION_DENIED");
        assertPreviewRefusedWithoutToken(runs);
    }

    @Test
    @DisplayName("PR-3b 终审 L1: 不能安全预览的工具 → 带意图码的预览两入口都 PREVIEW_UNSUPPORTED, 不执行")
    void explicitPreviewOfAToolWithoutSafePreviewIsUnsupportedOnBothEntries() throws Exception {
        Map<Entry, Harness> runs = runAll(Harness::useRealDispatch, IntentExecuteRequest.builder()
                .userInput("先看下会改什么").intentCode(WRITE_INTENT).previewOnly(true).build());

        assertExplicitParity(runs, "PREVIEW_UNSUPPORTED");
        assertPreviewRefusedWithoutToken(runs);
    }

    private static void assertPreviewRefusedWithoutToken(Map<Entry, Harness> runs) {
        for (Entry entry : Entry.values()) {
            Harness h = runs.get(entry);
            assertThat(h.shown.getConfirmableAction()).as(entry + ": 没有确认按钮").isNull();
            assertThat(h.toolPreviewBodyCalls()).as(entry + ": 工具预览 0 次").isZero();
            assertThat(h.tokensMinted()).as(entry + ": 令牌 0 张").isZero();
            assertThat(h.toolBodyCalls()).as(entry + ": 没写").isZero();
        }
    }

    private interface ThrowingRunnable { void run() throws Exception; }

    private static void uncheck(ThrowingRunnable r) {
        try {
            r.run();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("PR-3b 咨询页 (mode=READ) 带意图码的写: 两入口都 READ_MODE_WRITE_BLOCKED, 先于审批与确认卡")
    void explicitWriteInReadModeIsBlockedOnBothEntries() throws Exception {
        Map<Entry, Harness> runs = runAll(Harness::choiceListingAsks, IntentExecuteRequest.builder()
                .userInput("把 0601 那批鸡腿删了").intentCode(APPROVAL_WRITE_INTENT).mode("READ").build());

        assertExplicitParity(runs, "READ_MODE_WRITE_BLOCKED");
    }

    /** 显式意图码: /execute 走编排器显式意图路径 (流式管线 0 次), 两入口都经过同一份停步 (各一次), 同状态同意图。 */
    private static void assertExplicitParity(Map<Entry, Harness> runs, String expectedStatus) {
        IntentExecuteResponse execute = runs.get(Entry.EXECUTE).shown;
        IntentExecuteResponse stream = runs.get(Entry.STREAM).shown;
        assertThat(execute.getStatus()).as("/execute").isEqualTo(expectedStatus);
        assertThat(stream.getStatus()).as("/execute/stream").isEqualTo(expectedStatus);
        assertThat(execute.getIntentCode()).as("两入口同意图").isEqualTo(stream.getIntentCode());
        Harness executed = runs.get(Entry.EXECUTE);
        assertThat(executed.orchestratorExecuteCalls()).as("/execute 进编排器显式意图路径").isEqualTo(1);
        assertThat(executed.streamRuns()).isZero();
        for (Entry entry : Entry.values()) {
            assertThat(runs.get(entry).explicitGateCalls()).as(entry + ": 经过同一份显式意图停步").isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ 原普通入口独有步骤: PR-3 起两入口都跑

    @Test
    @DisplayName("#10 Drools 校验: 两入口都拦 (VALIDATION_FAILED), App 流式也调了规则引擎")
    void droolsValidationRunsOnBothEntries() throws Exception {
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

        assertSameStatusAndIntent(runs, "VALIDATION_FAILED");
        for (Entry entry : Entry.values()) {
            assertThat(runs.get(entry).ruleEngineCalls()).as(entry + ": 规则引擎跑了").isEqualTo(1);
            assertThat(runs.get(entry).slotFillingCalls()).as(entry + ": 拦在追问参数之前").isZero();
        }
    }

    @Test
    @DisplayName("#11 没绑工具 + 需要动态选工具: 两入口都动态选一次 SUCCESS")
    void dynamicToolSelectionRunsOnBothEntries() throws Exception {
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

        assertSameStatusAndIntent(runs, "SUCCESS");
        for (Entry entry : Entry.values()) {
            assertThat(runs.get(entry).dynamicSelectionCalls()).as(entry.name()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("#11 NEED_MORE_INFO 补问: 工具缺参 → 两入口都补出追问问题")
    void needMoreInfoIsEnrichedOnBothEntries() throws Exception {
        String input = "看下经营概况";
        Map<Entry, Harness> runs = runAll(h -> {
            h.toolReturns(IntentExecuteResponse.builder().intentRecognized(true).intentCode(READ_INTENT)
                    .status("NEED_MORE_INFO").message("缺少必需参数 (startDate)").build());
            h.recognizerAnswers(IntentMatchResult.builder().userInput(input).bestMatch(h.readIntent)
                    .matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                    .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build());
        }, IntentExecuteRequest.builder().userInput(input).build());

        assertSameStatusAndIntent(runs, "NEED_MORE_INFO");
        for (Entry entry : Entry.values()) {
            assertThat(runs.get(entry).shown.getClarificationQuestions()).as(entry + ": 补出了追问问题").isNotEmpty();
        }
    }

    @Test
    @DisplayName("#13 文字兜底: 工具 message 是占位符「操作已完成。」→ 两入口都换成真答案")
    void textFallbackRunsOnBothEntries() throws Exception {
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
        for (Entry entry : Entry.values()) {
            assertThat(runs.get(entry).shown.getMessage()).as(entry.name()).isEqualTo(answer);
        }
    }

    @Test
    @DisplayName("#12 对话记忆: 两入口都把这一轮记进本人会话, 记的是用户看到的那句 (文字兜底之后)")
    void conversationMemoryRecordsTheShownAnswerOnBothEntries() throws Exception {
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

        for (Entry entry : Entry.values()) {
            Harness h = runs.get(entry);
            assertThat(h.shown.getMessage()).as(entry + " 返回").isEqualTo(answer);
            assertThat(h.assistantMemory()).as(entry + " 记忆里的助手那句 == 返回 message").containsExactly(answer);
        }
    }

    @Test
    @DisplayName("#13 workdesk 摘要: 两入口都调; /execute 调用时外发按本厂脱敏, 结束后还原")
    void workdeskSummaryRunsOnBothEntriesInsideTheEgressScope() throws Exception {
        String input = "看下经营概况";
        @SuppressWarnings("unchecked")
        ThreadLocal<String> analysed = (ThreadLocal<String>) ReflectionTestUtils.getField(
                com.cretas.aims.service.consent.LlmEgressGuard.class, "ANALYSED_FACTORY");
        Map<Entry, List<Object>> seen = new EnumMap<>(Entry.class);
        Map<Entry, Harness> runs = runAll(h -> {
            List<Object> egressAtSummary = new ArrayList<>();
            seen.put(h.entry, egressAtSummary);
            WorkdeskOutputSummarizer summarizer = mock(WorkdeskOutputSummarizer.class);
            doAnswer(inv -> {
                egressAtSummary.add(String.valueOf(analysed.get()));
                return null;
            }).when(summarizer).apply(any(), anyString());
            h.workdeskSummarizer = summarizer;
            h.recognizerAnswers(IntentMatchResult.builder().userInput(input).bestMatch(h.readIntent)
                    .matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                    .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build());
        }, IntentExecuteRequest.builder().userInput(input).build());

        assertSameStatusAndIntent(runs, "SUCCESS");
        assertThat(seen.get(Entry.EXECUTE)).as("/execute: 摘要被调用, 且当时外发按本厂脱敏").containsExactly(FACTORY);
        assertThat(seen.get(Entry.STREAM)).as("App 流式也调 (工作线程的脱敏作用域由 executeStream 开)").hasSize(1);
        assertThat(analysed.get()).as("作用域结束后还原").isNull();
    }

    // ------------------------------------------------------------------ 审计 / error 出口

    @Test
    @DisplayName("#12 /execute 写 INTENT_EXECUTION 审计条目 (一次); App 流式照旧不写这一条")
    void intentExecutionAuditEntryIsKeptOnExecute() throws Exception {
        String input = "看下经营概况";
        Map<Entry, Harness> runs = runAll(h -> h.recognizerAnswers(IntentMatchResult.builder().userInput(input)
                .bestMatch(h.readIntent).matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build()),
                IntentExecuteRequest.builder().userInput(input).build());

        assertSameStatusAndIntent(runs, "SUCCESS");
        assertThat(runs.get(Entry.EXECUTE).auditRefs()).as("/execute").containsExactly("ai:intent-execution");
        assertThat(runs.get(Entry.STREAM).auditRefs()).as("App 流式不变 (不写 INTENT_EXECUTION)").isEmpty();
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
    @DisplayName("#1782 终审低项: F006 老板问通用问题、本地模型不可用 → /execute FAILED, 说明已脱敏, INTENT_EXECUTION 审计恰好 1 条")
    void f006LocalModelUnavailableIsAnHonestSanitizedFailure() throws Exception {
        String input = "怎么降低成本？";
        Harness h = new Harness(Entry.EXECUTE);
        h.useRealSessionServices();
        org.mockito.Mockito.doReturn(QuestionType.OPERATIONAL_COMMAND).when(h.knowledgeBase).detectQuestionType(input);
        h.recognizerAnswers(IntentMatchResult.builder().userInput(input)
                .matchMethod(IntentMatchResult.MatchMethod.NONE).confidence(0.0)
                .questionType(QuestionType.GENERAL_QUESTION).build());
        F006BossQuestionClient mini = mock(F006BossQuestionClient.class);
        when(mini.shouldRoute(FACTORY, USER, ROLE)).thenReturn(true);
        when(mini.stream(anyString(), any(), anyString(), anyString(), anyBoolean(), any(), any(), any()))
                .thenThrow(new IOException("本地 AI 服务不可用: Connection refused: connect to http://10.8.0.12:11437 "
                        + "at com.cretas.aims.ai.client.F006BossQuestionClient.stream"));
        h.miniClient = mini;

        IntentExecuteResponse shown = h.sendThroughFacade(req(input));

        assertThat(invocationsOf(mini, "stream")).as("阳性对照: 确实走到了本地模型").isEqualTo(1);
        assertThat(h.streamRuns()).isEqualTo(1);
        assertThat(shown.getStatus()).isEqualTo("FAILED");
        assertThat(shown.getMessage()).as("如实失败并带说明").isNotBlank()
                .doesNotContain("10.8.0.12").doesNotContain("com.cretas").doesNotContain("F006BossQuestionClient");
        assertThat(h.llmChatCalls()).as("⛔ 不兜到别的模型").isZero();
        assertThat(h.auditRefs()).as("INTENT_EXECUTION 审计恰好一条").containsExactly("ai:intent-execution");
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

    // ------------------------------------------------------------------ #1782 终审低项: 数字班组 (mode=READ 调门面)

    @Test
    @DisplayName("数字班组 ask (READ, 带 sessionId) 走流式: 写意图有写权限 → READ_MODE_WRITE_BLOCKED, 执行层 0 次, 交办")
    void digitalCrewAskWithWritePermissionIsReadModeBlocked() throws Exception {
        CrewRun run = crewAsk(true);
        assertThat(run.view.status()).isEqualTo("READ_MODE_WRITE_BLOCKED");
        assertThat(run.view.kind()).isEqualTo("DELEGATED");
        assertThat(run.harness.streamRuns()).as("经门面走的是流式管线").isEqualTo(1);
        assertThat(run.harness.orchestratorExecuteCalls()).isZero();
        assertThat(run.harness.executedIntents).as("执行层 0 次").isEmpty();
        assertThat(run.harness.slotFillingCalls()).as("也没追问参数").isZero();
    }

    @Test
    @DisplayName("数字班组 ask (READ, 带 sessionId) 走流式: 写意图没写权限 → NO_PERMISSION, 执行层 0 次, 交办给有权限的人")
    void digitalCrewAskWithoutWritePermissionIsNoPermission() throws Exception {
        CrewRun run = crewAsk(false);
        assertThat(run.view.status()).isEqualTo("NO_PERMISSION");
        assertThat(run.view.kind()).isEqualTo("DELEGATED");
        assertThat(run.harness.streamRuns()).isEqualTo(1);
        assertThat(run.harness.orchestratorExecuteCalls()).isZero();
        assertThat(run.harness.executedIntents).as("执行层 0 次").isEmpty();
        assertThat(run.harness.slotFillingCalls()).isZero();
    }

    @Test
    @DisplayName("BP 第 8 页: 数字计划员收到急单原话 → 短语表识别成急单影响分析 → 归计划员管、只读直答, 四项都有输出或说缺什么")
    void digitalCrewPlannerRushOrderReachesRushOrderAnalysis() throws Exception {
        String input = "周五要交 2 吨酱牛肉急单，原料够不够，产线排不排得开？";
        Harness h = new Harness(Entry.EXECUTE);
        // 识别: 用真实短语表 (外部模型不参与); 识别器桩只把短语表的结论原样交给管线
        String matched = h.knowledgeBase.matchPhrase(input).orElse(null);
        assertThat(matched).as("真实短语表把急单原话识别成新意图").isEqualTo("SCHEDULING_RUSH_ORDER_ANALYSIS");
        AIIntentConfig rush = h.intent(matched, "急单影响分析", "scheduling_rush_order_impact",
                ToolExecutor.AccessMode.READ);
        rush.setRequiredPermission(null); // 迁移不设: 读意图不比 App 更严
        h.recognizerAnswers(IntentMatchResult.builder().userInput(input).bestMatch(rush)
                .matchMethod(IntentMatchResult.MatchMethod.PHRASE_MATCH).confidence(0.98)
                .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build());

        // 工具: 真实 RushOrderImpactTool 跑这句原话 (本厂有酱牛肉 / 料够 / 交期前没有插单时段), 结果交给答复
        com.cretas.aims.ai.tool.impl.scheduling.RushOrderImpactTool tool =
                new com.cretas.aims.ai.tool.impl.scheduling.RushOrderImpactTool();
        com.cretas.aims.repository.ProductTypeRepository products = mock(com.cretas.aims.repository.ProductTypeRepository.class);
        com.cretas.aims.entity.ProductType beef = new com.cretas.aims.entity.ProductType();
        beef.setId("QA-PT-JNR");
        beef.setName("酱牛肉");
        beef.setUnit("kg");
        when(products.findVisibleByFactoryIdAndIsActiveTrue(FACTORY)).thenReturn(List.of(beef));
        com.cretas.aims.service.scheduling.UrgentInsertMaterialAssessor assessor =
                mock(com.cretas.aims.service.scheduling.UrgentInsertMaterialAssessor.class);
        when(assessor.assess(eq(FACTORY), eq("QA-PT-JNR"), org.mockito.ArgumentMatchers.argThat(q -> q != null && q.compareTo(new java.math.BigDecimal("2000")) == 0))).thenReturn(
                new com.cretas.aims.service.scheduling.UrgentInsertMaterialAssessor.Assessment(
                        com.cretas.aims.service.scheduling.UrgentInsertMaterialAssessor.Status.OK, null,
                        "QA-RECIPE-JNR", 2, List.of(), "fp"));
        com.cretas.aims.repository.InsertSlotRepository slots = mock(com.cretas.aims.repository.InsertSlotRepository.class);
        when(slots.findByFactoryIdAndStatus(FACTORY, "available")).thenReturn(List.of());
        ReflectionTestUtils.setField(tool, "productTypeRepository", products);
        ReflectionTestUtils.setField(tool, "materialAssessor", assessor);
        ReflectionTestUtils.setField(tool, "insertSlotRepository", slots);
        ReflectionTestUtils.setField(tool, "urgentInsertService", mock(com.cretas.aims.service.UrgentInsertService.class));
        ReflectionTestUtils.setField(tool, "objectMapper", new ObjectMapper().findAndRegisterModules());
        String json = tool.execute(com.cretas.aims.ai.dto.ToolCall.of("t", "scheduling_rush_order_impact",
                new ObjectMapper().writeValueAsString(Map.of("userInput", input))), Map.of("factoryId", FACTORY, "userId", USER));
        String toolMessage = new ObjectMapper().readTree(json).path("data").path("message").asText();
        assertThat(toolMessage).as(json).isNotBlank();
        h.toolReturns(IntentExecuteResponse.builder().intentRecognized(true).intentCode(matched).status("SUCCESS")
                .message(toolMessage).formattedText(toolMessage).build());

        DigitalCrewService.AskView view = crewAskWith(h, input);

        assertThat(view.kind()).as("只读直答: 不越界、不交办").isEqualTo("ANSWER");
        assertThat(view.intentCode()).isEqualTo("SCHEDULING_RUSH_ORDER_ANALYSIS");
        assertThat(h.executedIntents).as("只读工具真的被调度一次").containsExactly("SCHEDULING_RUSH_ORDER_ANALYSIS");
        assertThat(view.text()).contains("1. 物料够不够").contains("够。")
                .contains("2. 产线和人手排不排得开").contains("3. 要挪哪几张原单、各晚多久")
                .contains("算不出来，缺交期").contains("可插单时段")
                .contains("4. 方案草案").contains("待负责人确认");
        assertThat(view.item()).as("只读: 不进「等我确认」").isNull();
    }

    @Test
    @DisplayName("#1816 L1: 写意图的权限码取自迁移层 (V20261119_05 跑完后的值), 经真实编排器门面 → DELEGATED; 置空 → 「没有登记」不交办")
    void digitalCrewAskUsesTheMigratedPermissionThroughTheRealOrchestrator() throws Exception {
        String migrated = com.cretas.aims.ai.catalog.WriteIntentPermissionSources.migratedPermissions().get("RECEIVE_CONFIRM_CREATE");
        assertThat(migrated).as("迁移层登记了入库确认的权限码").isNotBlank();
        CrewRun run = crewAsk(true, migrated, "keeper");
        assertThat(run.view.kind()).isEqualTo("DELEGATED");
        assertThat(run.view.status()).isEqualTo("READ_MODE_WRITE_BLOCKED");
        assertThat(run.view.item().requiredPermission()).isEqualTo(migrated);
        assertThat(run.harness.streamRuns()).as("经门面走的是流式管线").isEqualTo(1);
        assertThat(run.harness.executedIntents).as("执行层 0 次").isEmpty();

        // 阴性对照: 同一条路径, 意图没登记权限码 ⇒ 不交办 (这就是 2026-11-17 实测到的现象)
        CrewRun blank = crewAsk(true, null, "keeper");
        assertThat(blank.view.kind()).isNotEqualTo("DELEGATED");
        assertThat(blank.view.item()).isNull();
        assertThat(blank.harness.executedIntents).isEmpty();
    }

    private record CrewRun(DigitalCrewService.AskView view, Harness harness) { }

    private static DigitalCrewService.AskView crewAskWith(Harness h, String input) throws Exception {
        DigitalCrewWorkItemRepository items = mock(DigitalCrewWorkItemRepository.class);
        UserRepository users = mock(UserRepository.class);
        User me = new User();
        me.setId(USER);
        when(users.findById(USER)).thenReturn(Optional.of(me));
        DigitalCrewService.AskView view;
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(h.actor())) {
            DigitalCrewService crew = new DigitalCrewService(items, mock(DigitalCrewMemberSettingRepository.class),
                    mock(DigitalCrewRoleMappingRepository.class), mock(DigitalCrewSettingLogRepository.class),
                    mock(DigitalCrewDelegationLockRepository.class), users, mock(PermissionService.class), h.facade(),
                    h.recognizer, mock(PreviewTokenService.class), mock(FactoryCapabilityPackSelector.class),
                    mock(FactoryBusinessTypeResolver.class), mock(com.cretas.aims.service.crew.DigitalCrewExecutionGate.class),
                    mock(PlatformTransactionManager.class));
            view = crew.ask(FACTORY, new DigitalCrewService.Actor(AuthenticatedActorType.FACTORY_USER, USER, ROLE),
                    "planner", input, SESSION);
        }
        assertThat(h.streamRuns()).as("阳性对照: 经门面跑了流式管线一次").isEqualTo(1);
        org.mockito.Mockito.verifyNoInteractions(items);
        return view;
    }

    private static CrewRun crewAsk(boolean hasWritePermission) throws Exception {
        return crewAsk(hasWritePermission, "production:read_write", "planner");
    }

    private static CrewRun crewAsk(boolean hasWritePermission, String requiredPermission, String member) throws Exception {
        String input = "那批到货的鸡腿帮我登记进系统";
        Harness h = new Harness(Entry.EXECUTE);
        h.writeIntent.setRequiredPermission(requiredPermission);
        if (!hasWritePermission) {
            when(h.permissionGate.check(eq(h.writeIntent), eq(USER), eq(ROLE)))
                    .thenReturn(IntentPermissionGate.PermissionCheck.denied(requiredPermission));
        }
        h.recognizerAnswers(IntentMatchResult.builder().userInput(input).bestMatch(h.writeIntent)
                .matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build());

        DigitalCrewWorkItemRepository items = mock(DigitalCrewWorkItemRepository.class);
        when(items.saveAndFlush(any())).thenAnswer(inv -> {
            DigitalCrewWorkItem w = inv.getArgument(0);
            w.setId(77L);
            return w;
        });
        DigitalCrewDelegationLockRepository locks = mock(DigitalCrewDelegationLockRepository.class);
        when(locks.existsById(anyString())).thenReturn(true);
        when(locks.lockForUpdate(anyString(), anyString())).thenReturn(Optional.of(new DigitalCrewDelegationLock()));
        UserRepository users = mock(UserRepository.class);
        User me = new User();
        me.setId(USER);
        when(users.findById(USER)).thenReturn(Optional.of(me));
        DigitalCrewService.AskView view;
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(h.actor())) {
            DigitalCrewService crew = new DigitalCrewService(items, mock(DigitalCrewMemberSettingRepository.class),
                    mock(DigitalCrewRoleMappingRepository.class), mock(DigitalCrewSettingLogRepository.class),
                    locks, users, mock(PermissionService.class), h.facade(), h.recognizer,
                    mock(PreviewTokenService.class), mock(FactoryCapabilityPackSelector.class),
                    mock(FactoryBusinessTypeResolver.class), mock(com.cretas.aims.service.crew.DigitalCrewExecutionGate.class),
                    mock(PlatformTransactionManager.class));
            view = crew.ask(FACTORY, new DigitalCrewService.Actor(AuthenticatedActorType.FACTORY_USER, USER, ROLE),
                    member, input, SESSION);
        }
        assertThat(h.recognitionCalls()).as("阳性对照: 识别跑了").isEqualTo(1);
        return new CrewRun(view, h);
    }

    private static long invocationsOf(Object mock, String method) {
        return org.mockito.Mockito.mockingDetails(mock).getInvocations().stream()
                .filter(i -> method.equals(i.getMethod().getName())).count();
    }

    // ------------------------------------------------------------------ helpers

    private static IntentExecuteRequest req(String input) {
        return IntentExecuteRequest.builder().userInput(input).sessionId(SESSION).build();
    }

    /** 两个入口各跑一次同一句话; 请求没点名会话时补上本页会话 {@link #SESSION}。 */
    private static Map<Entry, Harness> runAll(Consumer<Harness> setup, IntentExecuteRequest request) throws Exception {
        return run(setup, request, true);
    }

    /** 同 {@link #runAll}, 但会话原样 (不带 sessionId 就是不带)。 */
    private static Map<Entry, Harness> runAllExact(Consumer<Harness> setup, IntentExecuteRequest request) throws Exception {
        return run(setup, request, false);
    }

    private static Map<Entry, Harness> run(Consumer<Harness> setup, IntentExecuteRequest request, boolean defaultSession)
            throws Exception {
        Map<Entry, Harness> runs = new EnumMap<>(Entry.class);
        for (Entry entry : Entry.values()) {
            Harness h = new Harness(entry);
            setup.accept(h);
            h.shown = h.send(copy(request, defaultSession));
            runs.put(entry, h);
        }
        return runs;
    }

    private static IntentExecuteRequest copy(IntentExecuteRequest r, boolean defaultSession) {
        return IntentExecuteRequest.builder().userInput(r.getUserInput()).intentCode(r.getIntentCode())
                .mode(r.getMode()).forceExecute(r.getForceExecute()).previewOnly(r.getPreviewOnly())
                .sessionId(r.getSessionId() != null || !defaultSession ? r.getSessionId() : SESSION).build();
    }

    private static void assertSameStatusAndIntent(Map<Entry, Harness> runs, String expectedStatus) {
        IntentExecuteResponse execute = runs.get(Entry.EXECUTE).shown;
        IntentExecuteResponse stream = runs.get(Entry.STREAM).shown;
        assertThat(execute.getStatus()).as("/execute").isEqualTo(expectedStatus);
        assertThat(execute.getStatus()).as("/execute 与 /execute/stream 同状态").isEqualTo(stream.getStatus());
        assertThat(execute.getIntentCode()).as("/execute 与 /execute/stream 同意图").isEqualTo(stream.getIntentCode());
        Harness executed = runs.get(Entry.EXECUTE);
        assertThat(executed.orchestratorExecuteCalls()).as("/execute 没进编排器 execute").isZero();
        assertThat(executed.streamRuns()).as("/execute 跑的是流式管线 (一次)").isEqualTo(1);
    }

    static final class Harness {
        static final Map<String, Object> MASKED = Map.of("unitPrice", "***");

        final Entry entry;
        final AIIntentService recognizer = mock(AIIntentService.class);
        final IntentConfigManagementService configService = mock(IntentConfigManagementService.class);
        final IntentPermissionGate permissionGate = mock(IntentPermissionGate.class);
        final SemanticCacheService semanticCache = mock(SemanticCacheService.class);
        final IntentKnowledgeBase knowledgeBase = spy(new IntentKnowledgeBase());
        final QueryPreprocessorService preprocessor = mock(QueryPreprocessorService.class);
        ToolDispatchService toolDispatch = mock(ToolDispatchService.class);
        /** useRealDispatch 时的确认令牌服务 (数铸了几张)。 */
        PreviewTokenService previewTokens;
        final ToolRegistry toolRegistry = mock(ToolRegistry.class);
        final DashScopeClient llm = mock(DashScopeClient.class);
        final DashScopeConfig llmConfig = mock(DashScopeConfig.class);
        final AgenticRAGRouterService rag = mock(AgenticRAGRouterService.class);
        final AnalysisRouterService analysisRouter = mock(AnalysisRouterService.class);
        final ComplexityRouter complexityRouter = mock(ComplexityRouter.class);
        final AgentOrchestrator agents = mock(AgentOrchestrator.class);
        final AIAnalysisResultRepository reports = mock(AIAnalysisResultRepository.class);
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
        /** 流式用的对话记忆; null = 裸构造 (不建立服务端会话)。 */
        ConversationMemoryService streamMemory;
        final List<String> memoryWrites = new ArrayList<>();
        final List<String> executedIntents = new ArrayList<>();
        final List<Boolean> previewFlagsSeenByTools = new ArrayList<>();
        final AIIntentConfig readIntent;
        final AIIntentConfig writeIntent;
        final AIIntentConfig approvalIntent;
        final AIIntentConfig noToolIntent;
        final AIIntentConfig approvalWriteIntent;
        final Map<String, ToolExecutor> tools = new java.util.HashMap<>();
        IntentExecuteResponse toolResult;
        WorkdeskOutputSummarizer workdeskSummarizer;
        F006BossQuestionClient miniClient;
        AuthenticatedActor actorOverride;
        com.cretas.aims.entity.conversation.ConversationSession pendingAppSession;
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
            approvalWriteIntent = intent(APPROVAL_WRITE_INTENT, "删除原料批次", APPROVAL_WRITE_TOOL,
                    ToolExecutor.AccessMode.WRITE);
            approvalWriteIntent.setRequiresApproval(true);

            when(toolDispatch.executeWithTool(any(), anyString(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
                IntentExecuteRequest seenRequest = inv.getArgument(2);
                AIIntentConfig seen = inv.getArgument(3);
                executedIntents.add(seen.getIntentCode());
                previewFlagsSeenByTools.add(Boolean.TRUE.equals(seenRequest.getPreviewOnly()));
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

        AIIntentConfig intent(String code, String name, String toolName, ToolExecutor.AccessMode accessMode) {
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
            // 报工 / 审批写的工具是「先选要操作哪一个」那类 (编排器按 instanceof 判)
            ToolExecutor tool = REPORT_WORK_TOOL.equals(toolName) || APPROVAL_WRITE_TOOL.equals(toolName)
                    ? mock(ToolExecutor.class, org.mockito.Mockito.withSettings()
                            .extraInterfaces(com.cretas.aims.ai.tool.ParameterChoiceResolver.class))
                    : mock(ToolExecutor.class);
            tools.put(toolName, tool);
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

        /** 编排器的会话记忆 (两入口的对话记忆都写这一份): 本人本页会话存在, 助手那句记进 memoryWrites。 */
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
            return actorOverride != null ? actorOverride
                    : new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, USER, FACTORY, ROLE);
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

        long llmChatCalls() {
            return invocations(llm, "chat") + invocations(llm, "chatCompletionStream");
        }

        long explicitGateCalls() {
            return orchestrator == null ? 0 : invocations(orchestrator, "explicitIntentStopBeforeExecution");
        }

        long choiceListingCalls() {
            return invocations(toolDispatch, "askWhichOneBeforeWriteGuard");
        }

        long toolPreviewCalls() {
            return invocations(toolDispatch, "executeToolPreview");
        }

        long toolLayerCalls() {
            return invocations(toolDispatch, "executeWithTool");
        }

        /** 工具本体被调用的次数 (execute = 真的写 / 查)。 */
        long toolBodyCalls() {
            return tools.values().stream().mapToLong(t -> invocations(t, "execute")).sum();
        }

        /** 「列出来选」的追问 (askWhichOneBeforeWriteGuard 多个可选时的形状)。 */
        void choiceListingAsks() {
            when(toolDispatch.askWhichOneBeforeWriteGuard(any(), anyString(), any(), any(), any(), any(), anyBoolean()))
                    .thenAnswer(inv -> {
                        AIIntentConfig seen = inv.getArgument(3);
                        return IntentExecuteResponse.builder().intentRecognized(true).intentCode(seen.getIntentCode())
                                .status("NEED_MORE_INFO").message("请选择要操作的那一个：1. 鸡腿 0601 批 2. 鸡腿 0602 批")
                                .build();
                    });
        }

        /** 工具支持安全预览; 预览出口回一张预览卡。 */
        void previewSupported(String toolName) {
            when(tools.get(toolName).supportsPreview()).thenReturn(true);
            when(toolDispatch.executeToolPreview(any(), anyString(), any(), any(), any(), any())).thenAnswer(inv -> {
                AIIntentConfig seen = inv.getArgument(3);
                return IntentExecuteResponse.builder().intentRecognized(true).intentCode(seen.getIntentCode())
                        .status("PREVIEW").message("将删除 1 个批次（预览，未执行）").build();
            });
        }

        /**
         * 真实工具调度 (预览出口的工具开关 / 身份 / 一线 / 旧角色门与铸令牌都是真的), 只桩它的外部依赖;
         * 令牌服务是桩, 数 createBoundToken。工具默认在本厂开着。build() 之前调。
         */
        void useRealDispatch() {
            ToolDispatchService real = new ToolDispatchService(toolRegistry, new ObjectMapper(),
                    mock(DashScopeClient.class),
                    mock(com.cretas.aims.service.calibration.ToolCallRedundancyService.class),
                    mock(com.cretas.aims.service.calibration.SelfCorrectionService.class),
                    mock(com.cretas.aims.service.calibration.CorrectionAgentService.class),
                    mock(com.cretas.aims.service.calibration.ExternalVerifierService.class),
                    mock(com.cretas.aims.service.calibration.ToolResultValidatorService.class),
                    mock(com.cretas.aims.service.ParameterExtractionLearningService.class));
            previewTokens = mock(PreviewTokenService.class);
            when(previewTokens.createBoundToken(any())).thenReturn(com.cretas.aims.entity.intent.IntentPreviewToken
                    .builder().token("tok-1").commandDigest("digest-1")
                    .expiresAt(java.time.LocalDateTime.now().plusMinutes(5)).build());
            ReflectionTestUtils.setField(real, "previewTokenService", previewTokens);
            ReflectionTestUtils.setField(real, "writeGuardService", new WriteGuardService());
            when(toolRegistry.isToolEnabledForFactory(anyString(), anyString())).thenReturn(true);
            toolDispatch = spy(real);
        }

        /** 写工具支持预览且预览成功 (能确认), 真实调度会为它铸一张令牌。 */
        void toolPreviewsSuccessfully(String toolName) throws Exception {
            ToolExecutor tool = tools.get(toolName);
            when(tool.supportsPreview()).thenReturn(true);
            when(tool.getVersion()).thenReturn("1");
            when(tool.preview(any(), any())).thenReturn("{\"success\":true,\"data\":{\"status\":\"PREVIEW\","
                    + "\"canDo\":true,\"currentValues\":{\"状态\":\"在库\"},\"newValues\":{\"状态\":\"已删除\"}}}");
        }

        long tokensMinted() {
            return previewTokens == null ? 0 : invocations(previewTokens, "createBoundToken");
        }

        long toolPreviewBodyCalls() {
            return tools.values().stream().mapToLong(t -> invocations(t, "preview")).sum();
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
                return facade().execute(FACTORY, request, USER, ROLE);
            }
        }

        IntentExecutorServiceImpl facade() throws Exception {
            build();
            IntentExecutorServiceImpl facade = new IntentExecutorServiceImpl(orchestrator, sse,
                    mock(MultiIntentExecutionService.class), mock(ConversationMemoryService.class));
            ReflectionTestUtils.setField(facade, "streamTurnCollector", collector());
            return facade;
        }

        StreamTurnCollector collector() throws Exception {
            build();
            StreamTurnCollector collector = new StreamTurnCollector(sse, orchestrator);
            ReflectionTestUtils.setField(collector, "aiAccessAudit", audit);
            return collector;
        }

        void build() throws Exception {
            if (orchestrator == null) {
                orchestrator = ordinary();
            }
            if (sse == null) {
                sse = sse();
                ReflectionTestUtils.setField(sse, "turnSteps", orchestrator);
            }
        }

        private IntentExecutionOrchestrator ordinary() {
            IntentExecutionOrchestrator o = new IntentExecutionOrchestrator(
                    recognizer, mock(IntentSemanticsParser.class), semanticCache,
                    ruleEngine, conversations, memory,
                    new ObjectMapper(), llm, llmConfig,
                    knowledgeBase, reports, toolRegistry,
                    analysisRouter, complexityRouter, agents,
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
            if (workdeskSummarizer != null) {
                ReflectionTestUtils.setField(s, "workdeskOutputSummarizer", workdeskSummarizer);
            }
            if (miniClient != null) {
                ReflectionTestUtils.setField(s, "f006BossQuestionClient", miniClient);
            }
            if (streamMemory != null) {
                ReflectionTestUtils.setField(s, "conversationMemoryService", streamMemory);
            }
            return spy(s);
        }
    }
}
