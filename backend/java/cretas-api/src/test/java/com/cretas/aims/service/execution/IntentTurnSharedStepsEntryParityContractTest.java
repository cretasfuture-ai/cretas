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
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.invocation.Invocation;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * 意图蒸馏第③步 PR-2a: 只在普通入口有的步骤收成 {@link IntentTurnSharedSteps}, 两个产品真实入口
 * (普通 {@link IntentExecutionOrchestrator#execute} 与流式 {@code SseStreamingService#executeStreamAsync})
 * 同一句话、同一判定。每个用例两个入口各跑一次, 断言状态 / 关键字段相同, 并带阳性 / 阴性对照。
 *
 * <p>真的: 两个入口类、真实问题类型检测 {@link IntentKnowledgeBase}、真实写判定 {@link WriteGuardService}、
 * 真实闲聊护栏。桩: 识别层结果、权限、工具分发、闲聊模型、价格可见性判定、格式化器。
 */
@DisplayName("PR-2a 两入口共用步骤: 同一句话同一判定")
class IntentTurnSharedStepsEntryParityContractTest {

    enum Entry { ORDINARY, SSE }

    private static final String FACTORY = "F006";
    private static final long USER = 1707L;
    private static final String ROLE = "viewer";
    private static final String READ_INTENT = "REPORT_DASHBOARD_OVERVIEW";
    private static final String READ_TOOL = "report_dashboard_overview";
    private static final String WRITE_INTENT = "MATERIAL_BATCH_CREATE";
    private static final String WRITE_TOOL = "material_batch_create";
    private static final String APPROVAL_INTENT = "COST_VARIANCE_REVIEW";
    private static final String APPROVAL_TOOL = "cost_variance_review";
    private static final String SKILL_WRITE_INTENT = "GOODS_ARRIVAL_CREATE";
    private static final String CHAT_REPLY = "降低成本可以从优化采购批量、减少损耗、提高设备稼动率入手。";

    // ------------------------------------------------------------------ 2b 通用问法直答

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("「怎么降低成本？」早期判操作类 → 识别不出 + 通用咨询 → 两入口都直答 COMPLETED, 不追问")
    void unmatchedGeneralQuestionIsAnsweredDirectlyOnBothEntries(Entry entry) throws Exception {
        Harness h = new Harness(entry);
        String input = "怎么降低成本？";
        // 线上分叉的那一种: 早期类型 = 操作类 (含「成本」), 两入口都进识别; 识别层判通用咨询、无匹配
        org.mockito.Mockito.doReturn(QuestionType.OPERATIONAL_COMMAND).when(h.knowledgeBase).detectQuestionType(input);
        assertThat(ChatBusinessFactGuard.isBusinessQuestion(input)).as("前提: 咨询不是业务问法").isFalse();
        h.chatReplies(CHAT_REPLY);
        h.recognizerAnswers(IntentMatchResult.builder().userInput(input)
                .matchMethod(IntentMatchResult.MatchMethod.NONE).confidence(0.0)
                .questionType(QuestionType.GENERAL_QUESTION).build());

        IntentExecuteResponse shown = h.send(IntentExecuteRequest.builder().userInput(input).build());

        assertThat(h.recognitionCalls()).as("阳性对照: 识别真的被调用 (不是早期闲聊截走的)").isEqualTo(1);
        assertThat(h.chatCalls()).as("直答: 闲聊模型调了一次").isEqualTo(1);
        assertThat(shown.getStatus()).isEqualTo("COMPLETED");
        assertThat(shown.getFormattedText()).isEqualTo(CHAT_REPLY);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("阴性对照: 否定否决 (REJECTED) 即使判通用咨询也不直答 → 两入口都是澄清, 闲聊模型 0 次")
    void negationVetoIsNeverTurnedIntoChat(Entry entry) throws Exception {
        Harness h = new Harness(entry);
        String input = "怎么降低成本？";
        org.mockito.Mockito.doReturn(QuestionType.OPERATIONAL_COMMAND).when(h.knowledgeBase).detectQuestionType(input);
        h.chatReplies(CHAT_REPLY);
        h.recognizerAnswers(IntentMatchResult.builder().userInput(input)
                .matchMethod(IntentMatchResult.MatchMethod.REJECTED).confidence(0.0)
                .clarificationQuestion("您是要取消这次操作吗?需要我帮您查询或处理什么?")
                .questionType(QuestionType.GENERAL_QUESTION).build());

        IntentExecuteResponse shown = h.send(IntentExecuteRequest.builder().userInput(input).build());

        assertThat(h.recognitionCalls()).as("阳性对照: 识别被调用").isEqualTo(1);
        assertThat(h.chatCalls()).isZero();
        assertThat(shown.getStatus()).isEqualTo("NEED_CLARIFICATION");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("识别到意图 + 通用咨询 → 两入口都照常执行该意图 (不丢匹配去闲聊)")
    void matchedGeneralQuestionExecutesOnBothEntries(Entry entry) throws Exception {
        Harness h = new Harness(entry);
        String input = "怎么看本月经营情况";
        org.mockito.Mockito.doReturn(QuestionType.OPERATIONAL_COMMAND).when(h.knowledgeBase).detectQuestionType(input);
        h.chatReplies(CHAT_REPLY);
        h.recognizerAnswers(IntentMatchResult.builder().userInput(input).bestMatch(h.readIntent)
                .matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.92)
                .requiresConfirmation(false).questionType(QuestionType.GENERAL_QUESTION).build());

        IntentExecuteResponse shown = h.send(IntentExecuteRequest.builder().userInput(input).build());

        assertThat(h.executedIntents).as("工具分发被调用").containsExactly(READ_INTENT);
        assertThat(h.chatCalls()).as("没进闲聊模型").isZero();
        assertThat(shown.getStatus()).isEqualTo("SUCCESS");
    }

    // ------------------------------------------------------------------ 咨询 tab 拦写

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("咨询 tab (mode=READ) 识别到写意图 → 两入口都是 READ_MODE_WRITE_BLOCKED, 不进执行层")
    void readModeBlocksWriteIntentOnBothEntries(Entry entry) throws Exception {
        Harness h = new Harness(entry);
        String input = "那批到货的鸡腿帮我登记进系统";
        assertThat(h.knowledgeBase.matchPhrase(input, "FACTORY")).as("前提: 短语表没有 (走识别分支)").isEmpty();
        h.recognizerAnswers(IntentMatchResult.builder().userInput(input).bestMatch(h.writeIntent)
                .matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build());

        IntentExecuteResponse shown = h.send(IntentExecuteRequest.builder().userInput(input).mode("READ").build());

        assertThat(h.recognitionCalls()).as("阳性对照: 识别被调用").isEqualTo(1);
        assertThat(shown.getStatus()).isEqualTo("READ_MODE_WRITE_BLOCKED");
        assertThat(shown.getAiMode()).isEqualTo("WRITE");
        assertThat(h.executedIntents).as("执行层一次都没碰").isEmpty();
        assertThat(org.mockito.Mockito.mockingDetails(h.slotFilling).getInvocations())
                .as("也没开始追问参数").isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("阴性对照: 操作 tab (mode=OPERATE) 同一写意图不被咨询 tab 闸拦")
    void operateModeIsNotBlockedByReadGate(Entry entry) throws Exception {
        Harness h = new Harness(entry);
        String input = "那批到货的鸡腿帮我登记进系统";
        assertThat(h.knowledgeBase.matchPhrase(input, "FACTORY")).as("前提: 短语表没有 (走识别分支)").isEmpty();
        h.recognizerAnswers(IntentMatchResult.builder().userInput(input).bestMatch(h.writeIntent)
                .matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build());

        IntentExecuteResponse shown = h.send(IntentExecuteRequest.builder().userInput(input).mode("OPERATE").build());

        assertThat(shown.getStatus()).as("没被拦, 走到了缺参追问 (阳性: 追问桩真的会开火)").isEqualTo("NEED_MORE_INFO");
    }

    // ------------------------------------------------------------------ 审批

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("声明了需要审批 (非 CRITICAL) + 客户端带 forceExecute → 两入口都停在 PENDING_APPROVAL, 如实说未提交")
    void approvalPredicateIsTheSameOnBothEntries(Entry entry) throws Exception {
        Harness h = new Harness(entry);
        String input = "看下成本差异复核";
        h.recognizerAnswers(IntentMatchResult.builder().userInput(input).bestMatch(h.approvalIntent)
                .matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build());

        IntentExecuteResponse shown = h.send(IntentExecuteRequest.builder().userInput(input).forceExecute(true).build());

        assertThat(h.recognitionCalls()).as("阳性对照: 识别被调用").isEqualTo(1);
        assertThat(shown.getStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(shown.getMessage()).contains("未提交审批、未执行操作").doesNotContain("已提交审批请求");
        assertThat(h.executedIntents).as("执行层一次都没碰").isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("阴性对照: 没声明审批的同形意图照常执行")
    void intentWithoutApprovalExecutes(Entry entry) throws Exception {
        Harness h = new Harness(entry);
        String input = "看下成本差异复核";
        h.approvalIntent.setRequiresApproval(false);
        h.recognizerAnswers(IntentMatchResult.builder().userInput(input).bestMatch(h.approvalIntent)
                .matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build());

        IntentExecuteResponse shown = h.send(IntentExecuteRequest.builder().userInput(input).forceExecute(true).build());

        assertThat(shown.getStatus()).isEqualTo("SUCCESS");
        assertThat(h.executedIntents).containsExactly(APPROVAL_INTENT);
    }

    // ------------------------------------------------------------------ 结果收尾: 遮价 / 消毒 / aiMode

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("执行结果: 格式化器拿到的、下发对象上的 resultData 都是调用者视图; aiMode=READ")
    void resultIsCallersPriceViewBeforeFormattingOnBothEntries(Entry entry) throws Exception {
        Harness h = new Harness(entry);
        h.toolReturns(IntentExecuteResponse.builder().intentRecognized(true).intentCode(READ_INTENT)
                .status("SUCCESS").message("查询完成，共 1 条记录").resultData(Map.of("unitPrice", 12.5)).build());
        String input = "看下经营概况";
        h.recognizerAnswers(IntentMatchResult.builder().userInput(input).bestMatch(h.readIntent)
                .matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build());

        IntentExecuteResponse shown = h.send(IntentExecuteRequest.builder().userInput(input).build());

        assertThat(h.executedIntents).as("阳性对照: 工具执行了").containsExactly(READ_INTENT);
        assertThat(h.formatterSaw).as("格式化器被调用且拿到的是遮过的那一份").containsExactly(Harness.MASKED);
        assertThat(shown.getResultData()).isEqualTo(Harness.MASKED);
        assertThat(shown.getAiMode()).isEqualTo("READ");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("工具回了模型内部推理 (意图列表 + 意图码) → 两入口都换成安全回复")
    void internalReasoningLeakIsSanitizedOnBothEntries(Entry entry) throws Exception {
        Harness h = new Harness(entry);
        String leak = "在提供的可用意图列表中，只有 PRODUCT_SALES_QUERY 覆盖该问题。";
        h.toolReturns(IntentExecuteResponse.builder().intentRecognized(true).intentCode(READ_INTENT)
                .status("SUCCESS").message(leak).formattedText(leak).build());
        String input = "看下经营概况";
        h.recognizerAnswers(IntentMatchResult.builder().userInput(input).bestMatch(h.readIntent)
                .matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build());

        IntentExecuteResponse shown = h.send(IntentExecuteRequest.builder().userInput(input).build());

        assertThat(h.executedIntents).as("阳性对照: 工具执行了").containsExactly(READ_INTENT);
        assertThat(shown.getFormattedText()).isEqualTo(IntentTurnSharedSteps.INTERNAL_REASONING_SAFE_REPLY);
        assertThat(shown.getMessage()).doesNotContain("PRODUCT_SALES_QUERY");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("显式意图码 (按钮点选) + forceExecute, 声明了需要审批 (非 CRITICAL) → 两入口都停在 PENDING_APPROVAL")
    void explicitIntentCodeWithForceExecuteStillStopsForApproval(Entry entry) throws Exception {
        Harness h = new Harness(entry);

        IntentExecuteResponse shown = h.send(IntentExecuteRequest.builder().userInput("看下成本差异复核")
                .intentCode(APPROVAL_INTENT).forceExecute(true).build());

        assertThat(h.recognitionCalls()).as("走的是显式意图路径, 不经过识别").isZero();
        assertThat(shown.getStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(shown.getMessage()).contains("未提交审批、未执行操作").doesNotContain("已提交审批请求");
        assertThat(h.executedIntents).as("执行层一次都没碰").isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("阴性对照: 显式意图码, 没声明审批 → 照常执行")
    void explicitIntentCodeWithoutApprovalExecutes(Entry entry) throws Exception {
        Harness h = new Harness(entry);
        h.approvalIntent.setRequiresApproval(false);

        IntentExecuteResponse shown = h.send(IntentExecuteRequest.builder().userInput("看下成本差异复核")
                .intentCode(APPROVAL_INTENT).forceExecute(true).build());

        assertThat(shown.getStatus()).isEqualTo("SUCCESS");
        assertThat(h.executedIntents).containsExactly(APPROVAL_INTENT);
    }

    // ------------------------------------------------------------------ Skill 分支 (没绑工具的意图)

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("Skill 开启 + 咨询 tab + 没绑工具的写意图 → 两入口都拦写, Skill 一次都不跑")
    void readModeBlocksSkillRoutedWriteIntentOnBothEntries(Entry entry) throws Exception {
        Harness h = new Harness(entry);
        h.enableSkills();
        String input = "那批到货的鸡腿帮我登记进系统";
        assertThat(h.knowledgeBase.matchPhrase(input, "FACTORY")).as("前提: 短语表没有 (走识别分支)").isEmpty();
        h.recognizerAnswers(IntentMatchResult.builder().userInput(input).bestMatch(h.skillWriteIntent)
                .matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build());

        IntentExecuteResponse shown = h.send(IntentExecuteRequest.builder().userInput(input).mode("READ").build());

        assertThat(h.recognitionCalls()).as("阳性对照: 识别被调用").isEqualTo(1);
        assertThat(shown.getStatus()).isEqualTo("READ_MODE_WRITE_BLOCKED");
        assertThat(h.skillCalls()).as("Skill 一次都没跑").isZero();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("阴性对照: 操作 tab 同一句走 Skill, 结果经过共用收尾 (遮价 + aiMode)")
    void operateModeSkillResultGoesThroughSharedResultSteps(Entry entry) throws Exception {
        Harness h = new Harness(entry);
        h.enableSkills();
        String input = "那批到货的鸡腿帮我登记进系统";
        h.recognizerAnswers(IntentMatchResult.builder().userInput(input).bestMatch(h.skillWriteIntent)
                .matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build());

        IntentExecuteResponse shown = h.send(IntentExecuteRequest.builder().userInput(input).mode("OPERATE").build());

        assertThat(h.skillCalls()).as("阳性对照: Skill 真的跑了").isEqualTo(1);
        assertThat(shown.getStatus()).isEqualTo("SUCCESS");
        assertThat(shown.getResultData()).as("Skill 结果也换成调用者视图").isEqualTo(Harness.MASKED);
        assertThat(shown.getAiMode()).isEqualTo("WRITE");
    }

    // ------------------------------------------------------------------ 识别候选按权限收窄

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("操作 tab: 两入口都把本人权限集交给识别层收窄候选; 咨询 tab 不传 (阴性对照)")
    void recognitionGetsPermissionFilterOnOperateTabOnBothEntries(Entry entry) throws Exception {
        Set<String> perms = Set.of("report:read", "material:read");
        Harness h = new Harness(entry);
        when(h.permissionGate.resolveUserPermissions(USER, FACTORY)).thenReturn(perms);
        String input = "看下经营概况";
        h.recognizerAnswers(IntentMatchResult.builder().userInput(input).bestMatch(h.readIntent)
                .matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build());

        h.send(IntentExecuteRequest.builder().userInput(input).mode("OPERATE").build());
        assertThat(h.recognitionFilterArgs()).as("操作 tab").containsExactly(perms);

        Harness read = new Harness(entry);
        when(read.permissionGate.resolveUserPermissions(USER, FACTORY)).thenReturn(perms);
        read.recognizerAnswers(IntentMatchResult.builder().userInput(input).bestMatch(read.readIntent)
                .matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build());
        read.send(IntentExecuteRequest.builder().userInput(input).mode("READ").build());
        List<Object> readArgs = read.recognitionFilterArgs();
        assertThat(readArgs).as("咨询 tab: 识别被调用一次, 不传权限集").hasSize(1);
        assertThat(readArgs.get(0)).isNull();
    }

    // ------------------------------------------------------------------ harness

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
        /** 写意图一律「还缺参数」: 咨询 tab 的拦写必须在它之前, 否则用户先被追问一个根本不该办的写操作。 */
        final com.cretas.aims.service.SlotFillingService slotFilling = mock(com.cretas.aims.service.SlotFillingService.class);
        final ChatBusinessFactGuard guard = new ChatBusinessFactGuard();
        final ConversationServiceImpl conversations;
        final List<String> executedIntents = new ArrayList<>();
        final List<Object> formatterSaw = new ArrayList<>();
        final AIIntentConfig readIntent;
        final AIIntentConfig writeIntent;
        final AIIntentConfig approvalIntent;
        /** 没绑工具的写意图 (WORKDESK 类, 由 Skill 执行)。 */
        final AIIntentConfig skillWriteIntent;
        IntentExecuteResponse toolResult;
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
            when(permissionGate.check(any(), eq(USER), eq(ROLE)))
                    .thenReturn(IntentPermissionGate.PermissionCheck.allowed());

            readIntent = intent(READ_INTENT, "经营概况", READ_TOOL, "LOW", ToolExecutor.AccessMode.READ);
            writeIntent = intent(WRITE_INTENT, "新建原料批次", WRITE_TOOL, "MEDIUM", ToolExecutor.AccessMode.WRITE);
            approvalIntent = intent(APPROVAL_INTENT, "成本差异复核", APPROVAL_TOOL, "MEDIUM",
                    ToolExecutor.AccessMode.READ);
            approvalIntent.setRequiresApproval(true);
            skillWriteIntent = intent(SKILL_WRITE_INTENT, "到货登记", null, "MEDIUM", ToolExecutor.AccessMode.WRITE);

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
            when(semanticCache.queryCache(anyString(), anyString())).thenReturn(SemanticCacheHit.miss(0L));
            when(slotFilling.checkAndStartSlotFilling(anyString(), any(), any(), any(), any())).thenAnswer(inv -> {
                AIIntentConfig seen = inv.getArgument(2);
                if (!WRITE_INTENT.equals(seen.getIntentCode())) {
                    return null;
                }
                return IntentExecuteResponse.builder().intentRecognized(true).intentCode(WRITE_INTENT)
                        .status("NEED_MORE_INFO").message("请提供原料批次的供应商和数量").build();
            });
            when(priceView.maskedViewFor(any(), any())).thenAnswer(inv -> MASKED);
            when(formatter.formatAndSet(any(), any())).thenAnswer(inv -> {
                IntentExecuteResponse r = inv.getArgument(0);
                formatterSaw.add(r.getResultData());
                return r;
            });
            recognizerAnswers(IntentMatchResult.builder().matchMethod(IntentMatchResult.MatchMethod.NONE)
                    .confidence(0.0).questionType(QuestionType.CONVERSATIONAL).build());
        }

        private AIIntentConfig intent(String code, String name, String toolName, String sensitivity,
                                      ToolExecutor.AccessMode accessMode) {
            AIIntentConfig config = AIIntentConfig.builder().intentCode(code).intentName(name)
                    .intentCategory("QUERY").businessType("COMMON").sensitivityLevel(sensitivity)
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

        /** Skill 开启: 关键词 Skill 对任何话都命中, 回一份带价格字段的结果。 */
        void enableSkills() {
            when(dynamicTools.isSkillsEnabled()).thenReturn(true);
            when(dynamicTools.trySkillRoute(anyString(), anyString(), any(), any())).thenAnswer(inv ->
                    IntentExecuteResponse.builder().intentRecognized(true).intentCode(SKILL_WRITE_INTENT)
                            .status("SUCCESS").message("已按 Skill 完成到货登记")
                            .resultData(Map.of("unitPrice", 12.5)).build());
        }

        long skillCalls() {
            return org.mockito.Mockito.mockingDetails(dynamicTools).getInvocations().stream()
                    .filter(i -> i.getMethod().getName().startsWith("try"))
                    .count();
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

        long chatCalls() {
            return org.mockito.Mockito.mockingDetails(llm).getInvocations().stream()
                    .filter(i -> i.getMethod().getName().startsWith("chat"))
                    .count();
        }

        private List<Invocation> recognitionInvocations() {
            return org.mockito.Mockito.mockingDetails(recognizer).getInvocations().stream()
                    .filter(i -> "recognizeIntentWithConfidence".equals(i.getMethod().getName()))
                    .toList();
        }

        long recognitionCalls() {
            return recognitionInvocations().size();
        }

        /** 识别层收到的第 8 个参数 (filterPermissions), 每次调用一项。 */
        List<Object> recognitionFilterArgs() {
            List<Object> args = new ArrayList<>();
            for (Invocation i : recognitionInvocations()) {
                args.add(i.getArgument(7));
            }
            return args;
        }

        IntentExecuteResponse send(IntentExecuteRequest request) throws Exception {
            try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(
                    new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, USER, FACTORY, ROLE))) {
                if (entry == Entry.ORDINARY) {
                    return OrdinaryEntryPipeline.execute(ordinary(), FACTORY, request, USER, ROLE);
                }
                sse = sse();
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

        private IntentExecutionOrchestrator ordinary() {
            IntentExecutionOrchestrator orchestrator = new IntentExecutionOrchestrator(
                    recognizer, mock(IntentSemanticsParser.class), semanticCache,
                    mock(RuleEngineService.class), conversations, mock(ConversationMemoryService.class),
                    new ObjectMapper(), llm, llmConfig,
                    knowledgeBase, mock(AIAnalysisResultRepository.class), toolRegistry,
                    mock(AnalysisRouterService.class), mock(ComplexityRouter.class), mock(AgentOrchestrator.class),
                    rag, mock(ResultValidatorService.class), toolDispatch,
                    dynamicTools, preprocessor);
            ReflectionTestUtils.setField(orchestrator, "intentPermissionGate", permissionGate);
            ReflectionTestUtils.setField(orchestrator, "writeGuardService", new WriteGuardService());
            ReflectionTestUtils.setField(orchestrator, "configService", configService);
            ReflectionTestUtils.setField(orchestrator, "chatBusinessFactGuard", guard);
            ReflectionTestUtils.setField(orchestrator, "resultFormatterService", formatter);
            ReflectionTestUtils.setField(orchestrator, "priceView", priceView);
            ReflectionTestUtils.setField(orchestrator, "slotFillingService", slotFilling);
            BusinessTypeGate businessTypeGate = mock(BusinessTypeGate.class);
            when(businessTypeGate.check(anyString(), any())).thenReturn(Optional.empty());
            ReflectionTestUtils.setField(orchestrator, "businessTypeGate", businessTypeGate);
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
                    toolRegistry, toolDispatch, dynamicTools, llm, llmConfig));
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
            doAnswer(invocation -> null).when(s).sendSseEvent(any(), anyString(), any());
            return s;
        }
    }
}
