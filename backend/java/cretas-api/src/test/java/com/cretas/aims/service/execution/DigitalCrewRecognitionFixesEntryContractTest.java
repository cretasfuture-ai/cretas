package com.cretas.aims.service.execution;

import com.cretas.aims.ai.capability.FactoryBusinessTypeResolver;
import com.cretas.aims.ai.capability.FactoryCapabilityPackSelector;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.config.IntentKnowledgeBase.QuestionType;
import com.cretas.aims.controller.DigitalCrewController;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.crew.DigitalCrewDelegationLock;
import com.cretas.aims.entity.crew.DigitalCrewWorkItem;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.crew.DigitalCrewDelegationLockRepository;
import com.cretas.aims.repository.crew.DigitalCrewMemberSettingRepository;
import com.cretas.aims.repository.crew.DigitalCrewRoleMappingRepository;
import com.cretas.aims.repository.crew.DigitalCrewSettingLogRepository;
import com.cretas.aims.repository.crew.DigitalCrewWorkItemRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.service.crew.DigitalCrewService;
import com.cretas.aims.service.impl.IntentExecutorServiceImpl;
import com.cretas.aims.service.intent.LegacyIntentExitMetrics;
import com.cretas.aims.service.intent.flywheel.FlywheelDecisionAnalysis;
import com.cretas.aims.service.intent.flywheel.IntentFlywheelRecorder;
import com.cretas.aims.service.intent.flywheel.IntentFlywheelTurnContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-11-17 F006 数字班组实测 (BP 截图) 的识别问题, 跑在产品真实入口
 * {@code POST /api/mobile/{factoryId}/digital-crew/members/{member}/ask} ({@link DigitalCrewController#ask})
 * → 真实 {@link DigitalCrewService} → 门面 {@link IntentExecutorServiceImpl} → 流式管线。
 *
 * <p>真的: 控制器、数字班组服务、门面、流式管线、编排器步骤、真实短语表 / 闲聊护栏 / 飞轮痕迹与来源分析。
 * 桩: 决策层识别结果 (外部模型)、工具分发 (只记下收到的意图)、权限、仓库。
 */
@DisplayName("数字班组 ask 入口: 2026-11-17 F006 识别问题回归")
class DigitalCrewRecognitionFixesEntryContractTest {

    private static final String FACTORY = "F006";
    private static final long USER = 1707L;
    private static final String ROLE = "viewer";
    private static final String MODEL_INTENT = "REPORT_DASHBOARD_OVERVIEW";

    // ------------------------------------------------------------------ 问题一: 复合 / 带条件的句子不走短语快捷

    @ParameterizedTest(name = "{0}「{1}」")
    @CsvSource(delimiter = '|', value = {
            "keeper|现在牛前腱还有多少库存？",
            "clerk|这个月销售额多少，哪个客户买得最多？"})
    @DisplayName("带条件 / 多个子问 → 不被句中短语截走, 交给决策层")
    void conditionedOrCompoundSentenceIsLeftToTheDecisionLayer(String member, String input) throws Exception {
        StreamTurnCollectorEntryParityContractTest.Harness h = harness();
        String phraseCode = h.knowledgeBase.matchPhrase(input.toLowerCase().trim(), "FACTORY").orElse(null);
        assertThat(phraseCode).as("前提: 真实短语表会命中这句 (改前就是被它截走的)").isNotNull();
        h.intent(phraseCode, phraseCode, phraseCode.toLowerCase(), ToolExecutor.AccessMode.READ);
        h.recognizerAnswers(answer(input, h.readIntent));

        Run run = ask(h, member, input);

        assertThat(h.recognitionCalls()).as("交给了决策层").isEqualTo(1);
        assertThat(h.executedIntents).as("执行的是决策层定的意图, 不是短语 %s", phraseCode)
                .containsExactly(MODEL_INTENT);
        assertThat(run.view.intentCode()).isEqualTo(MODEL_INTENT);
    }

    @Test
    @DisplayName("急单复合句 (#1884 有意放行): 只能由 SCHEDULING_RUSH_ORDER_ANALYSIS 接, 不落到其它短语, 也不经决策层")
    void rushOrderCompoundSentenceIsTakenOnlyByTheRushIntent() throws Exception {
        StreamTurnCollectorEntryParityContractTest.Harness h = harness();
        String input = "周五要交 2 吨酱牛肉急单，原料够不够，产线排不排得开？";
        String phraseCode = h.knowledgeBase.matchPhrase(input.toLowerCase().trim(), "FACTORY").orElse(null);
        assertThat(phraseCode).as("前提: 真实短语表会命中这句").isEqualTo("SCHEDULING_RUSH_ORDER_ANALYSIS");
        h.intent(phraseCode, phraseCode, phraseCode.toLowerCase(), ToolExecutor.AccessMode.READ);
        h.recognizerAnswers(answer(input, h.readIntent));

        Run run = ask(h, "planner", input);

        assertThat(h.executedIntents).as("只被急单意图接, 不是别的短语").containsExactly("SCHEDULING_RUSH_ORDER_ANALYSIS");
        assertThat(run.view.intentCode()).isEqualTo("SCHEDULING_RUSH_ORDER_ANALYSIS");
    }

    @Test
    @DisplayName("阳性对照: 无条件的简单短语 (「库存查询」) 仍走短语快捷, 且飞轮记下意图码与来源 PHRASE_SHORTCUT")
    void unconditionedPhraseStillShortcutsAndTheFlywheelRecordsItsIntent() throws Exception {
        StreamTurnCollectorEntryParityContractTest.Harness h = harness();
        String input = "库存查询";

        Run run = ask(h, "keeper", input);

        assertThat(h.recognitionCalls()).as("短语直达, 不经识别").isZero();
        assertThat(h.executedIntents).containsExactly("REPORT_INVENTORY");
        assertThat(run.turns).as("飞轮这一轮被记了").hasSize(1);
        FlywheelDecisionAnalysis.Decision d = FlywheelDecisionAnalysis.analyse(run.turns.get(0), null);
        assertThat(d.source()).isEqualTo("PHRASE_SHORTCUT");
        assertThat(d.intentCode()).as("改前为空").isEqualTo("REPORT_INVENTORY");
    }

    @Test
    @DisplayName("句首时间词不算条件 (P1-A): 「上个月毛利率多少？」照走短语, 工具按原话解析时间; 「本月」标题错在格式化 (ReportPeriodLabelContractTest)")
    void leadingTimeWordKeepsThePhraseShortcut() throws Exception {
        StreamTurnCollectorEntryParityContractTest.Harness h = harness();
        String input = "上个月毛利率多少？";
        String phraseCode = h.knowledgeBase.matchPhrase(input.toLowerCase().trim(), "FACTORY").orElseThrow();
        h.intent(phraseCode, phraseCode, phraseCode.toLowerCase(), ToolExecutor.AccessMode.READ);
        List<String> inputsSeenByTool = new ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            com.cretas.aims.dto.ai.IntentExecuteRequest r = inv.getArgument(2);
            inputsSeenByTool.add(r.getUserInput());
            AIIntentConfig seen = inv.getArgument(3);
            h.executedIntents.add(seen.getIntentCode());
            return com.cretas.aims.dto.ai.IntentExecuteResponse.builder().intentRecognized(true)
                    .intentCode(seen.getIntentCode()).status("SUCCESS").formattedText("ok").build();
        }).when(h.toolDispatch).executeWithTool(any(), anyString(), any(), any(), any(), any(), any());

        ask(h, "accountant", input);

        assertThat(h.recognitionCalls()).isZero();
        assertThat(h.executedIntents).containsExactly(phraseCode);
        assertThat(inputsSeenByTool).as("工具拿到的是带「上个月」的原话").containsExactly(input);
    }

    @ParameterizedTest(name = "「{0}」")
    @CsvSource({"本月营收", "上月净利率", "本周发货"})
    @DisplayName("#1813 终审 M1: 时间词开头的短语一字不差说出来, 照走短语快捷 (改前剩下「营收」被拒)")
    void timePrefixedPhraseSpokenVerbatimStillShortcuts(String input) throws Exception {
        StreamTurnCollectorEntryParityContractTest.Harness h = harness();
        java.util.Map.Entry<String, String> hit = h.knowledgeBase.matchPhraseHit(input, "FACTORY").orElse(null);
        assertThat(hit).as("前提: 真实短语表命中这句").isNotNull();
        assertThat(hit.getKey()).as("前提: 命中的就是这条时间词开头的短语本身").isEqualTo(input);
        h.intent(hit.getValue(), hit.getValue(), hit.getValue().toLowerCase(), ToolExecutor.AccessMode.READ);

        ask(h, "accountant", input);

        assertThat(h.recognitionCalls()).as("短语直达, 不经决策层").isZero();
        assertThat(h.executedIntents).containsExactly(hit.getValue());
    }

    // ------------------------------------------------------------------ 问题六: 出成率是业务问法, 不进闲聊 / 本地兜底

    @Test
    @DisplayName("「酱牛肉这个月的出成率怎么样？」早期判成通用问法时也交给识别, 不进闲聊模型")
    void yieldRateQuestionGoesToRecognitionNotChat() throws Exception {
        StreamTurnCollectorEntryParityContractTest.Harness h = harness();
        String input = "酱牛肉这个月的出成率怎么样？";
        org.mockito.Mockito.doReturn(QuestionType.GENERAL_QUESTION).when(h.knowledgeBase).detectQuestionType(input);
        h.chatReplies("出成率一般在 60% 左右。");
        AIIntentConfig yield = h.intent("REPORT_BATCH_YIELD_RANK", "批次出成率排行", "report_batch_yield_rank",
                ToolExecutor.AccessMode.READ);
        h.recognizerAnswers(answer(input, yield));

        ask(h, "accountant", input);

        assertThat(h.llmChatCalls()).as("没交给闲聊模型").isZero();
        assertThat(h.recognitionCalls()).isEqualTo(1);
        assertThat(h.executedIntents).containsExactly("REPORT_BATCH_YIELD_RANK");
    }

    // ------------------------------------------------------------------ 11-19: 「X 成本怎么构成」不进闲聊

    @ParameterizedTest(name = "「{0}」")
    @CsvSource(delimiter = '|', value = {"酱卤牛肉成本怎么构成|0", "酱卤牛肉的成本构成|1", "酱卤牛肉成本是怎么算的|0",
            "演示·酱卤牛肉250g 的成本是怎么构成的？|0"})
    @DisplayName("成本构成问法 → 执行成本查询, 不进闲聊兜底 (改前答「需要先查一下真实数据…」)")
    void costCompositionQuestionGoesToRecognitionNotChat(String input, int expectedRecognitionCalls) throws Exception {
        StreamTurnCollectorEntryParityContractTest.Harness h = harness();
        // 线上实测早期问题类型就是通用问法 (这才会走到「业务问法不进闲聊」的判定)
        org.mockito.Mockito.doReturn(QuestionType.GENERAL_QUESTION).when(h.knowledgeBase).detectQuestionType(input);
        h.chatReplies("需要先查一下真实数据，请您说明想查哪一项。");
        AIIntentConfig cost = h.intent("COST_QUERY", "成本查询", "report_cost_query", ToolExecutor.AccessMode.READ);
        h.recognizerAnswers(answer(input, cost));

        Run run = ask(h, "accountant", input);

        assertThat(h.llmChatCalls()).as("没交给闲聊模型").isZero();
        // 每句走哪条路是确定的: 句中带「成本怎么构成/怎么算」短语的由早期短语路由定成本查询 (0 次识别), 其余经决策层 (1 次)
        assertThat(h.recognitionCalls()).as("识别次数按路径精确断言").isEqualTo(expectedRecognitionCalls);
        assertThat(h.executedIntents).containsExactly("COST_QUERY");
        assertThat(run.view.intentCode()).isEqualTo("COST_QUERY");
    }

    @Test
    @DisplayName("阴性对照: 「怎么降低成本」是咨询, 仍走闲聊 (只放行成本构成/怎么算, 没有把咨询全放进识别)")
    void howToReduceCostIsStillConsultation() throws Exception {
        StreamTurnCollectorEntryParityContractTest.Harness h = harness();
        String input = "酱卤牛肉成本怎么降低";
        org.mockito.Mockito.doReturn(QuestionType.GENERAL_QUESTION).when(h.knowledgeBase).detectQuestionType(input);
        h.chatReplies("可以从原料采购和出成率入手。");

        ask(h, "accountant", input);

        assertThat(h.llmChatCalls()).as("咨询句照旧进闲聊").isEqualTo(1);
        assertThat(h.recognitionCalls()).isZero();
    }

    @Test
    @DisplayName("整句就是「成本怎么构成」→ 短语快捷直达成本查询; 带产品名的句子剩余字超限, 交决策层")
    void barePhraseShortcutsAndProductNamedSentenceIsLeftToTheDecisionLayer() throws Exception {
        StreamTurnCollectorEntryParityContractTest.Harness bare = harness();
        bare.intent("COST_QUERY", "成本查询", "report_cost_query", ToolExecutor.AccessMode.READ);
        ask(bare, "accountant", "成本怎么构成");
        assertThat(bare.recognitionCalls()).as("整句短语直达").isZero();
        assertThat(bare.executedIntents).containsExactly("COST_QUERY");

        StreamTurnCollectorEntryParityContractTest.Harness named = harness();
        String input = "酱卤牛肉成本怎么构成";
        assertThat(named.knowledgeBase.matchPhrase(input.toLowerCase(), "FACTORY")).as("前提: 句中短语会命中").contains("COST_QUERY");
        AIIntentConfig cost = named.intent("COST_QUERY", "成本查询", "report_cost_query", ToolExecutor.AccessMode.READ);
        named.recognizerAnswers(answer(input, cost));
        ask(named, "accountant", input);
        assertThat(named.recognitionCalls()).as("带产品名 → 决策层").isEqualTo(1);
        assertThat(named.executedIntents).containsExactly("COST_QUERY");
    }

    // ------------------------------------------------------------------ 对照: 现在好的两句不能变差

    @ParameterizedTest(name = "{0}「{1}」→ {2}")
    @CsvSource(delimiter = '|', value = {
            "accountant|演示·酱卤牛肉250g 的成本是怎么构成的？|COST_QUERY",
            "clerk|演示·南京食品经销商的订单发货了吗？|SHIPMENT_QUERY"})
    @DisplayName("阳性对照: 成本构成 / 发货查询照常落到决策层定的意图并执行")
    void goodSentencesStayGood(String member, String input, String intentCode) throws Exception {
        StreamTurnCollectorEntryParityContractTest.Harness h = harness();
        AIIntentConfig intent = h.intent(intentCode, intentCode, intentCode.toLowerCase(), ToolExecutor.AccessMode.READ);
        h.recognizerAnswers(answer(input, intent));

        Run run = ask(h, member, input);

        assertThat(h.executedIntents).containsExactly(intentCode);
        assertThat(run.view.intentCode()).isEqualTo(intentCode);
        assertThat(h.llmChatCalls()).isZero();
    }

    // ------------------------------------------------------------------ harness

    private record Run(DigitalCrewService.AskView view, List<IntentFlywheelTurnContext.Turn> turns) { }

    private static StreamTurnCollectorEntryParityContractTest.Harness harness() {
        return new StreamTurnCollectorEntryParityContractTest.Harness(StreamTurnCollectorEntryParityContractTest.Entry.EXECUTE);
    }

    private static IntentMatchResult answer(String input, AIIntentConfig intent) {
        return IntentMatchResult.builder().userInput(input).bestMatch(intent)
                .matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build();
    }

    private static Run ask(StreamTurnCollectorEntryParityContractTest.Harness h, String member, String input)
            throws Exception {
        IntentExecutorServiceImpl facade = h.facade();
        ReflectionTestUtils.setField(h.sse, "legacyExitMetrics",
                new LegacyIntentExitMetrics(new SimpleMeterRegistry(), 900, 20, 0.05, 0.2));
        IntentFlywheelRecorder recorder = mock(IntentFlywheelRecorder.class);
        List<IntentFlywheelTurnContext.Turn> turns = new ArrayList<>();
        doAnswer(inv -> {
            turns.add(inv.getArgument(5));
            return null;
        }).when(recorder).recordTurn(any(), any(), any(), any(), any(), any(), anyLong());
        ReflectionTestUtils.setField(facade, "flywheelRecorder", recorder);

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
        DigitalCrewService crew = new DigitalCrewService(items, mock(DigitalCrewMemberSettingRepository.class),
                mock(DigitalCrewRoleMappingRepository.class), mock(DigitalCrewSettingLogRepository.class),
                locks, users, mock(PermissionService.class), facade, h.recognizer,
                mock(PreviewTokenService.class), mock(FactoryCapabilityPackSelector.class),
                mock(FactoryBusinessTypeResolver.class), mock(com.cretas.aims.service.crew.DigitalCrewExecutionGate.class),
                mock(PlatformTransactionManager.class));
        DigitalCrewController controller = new DigitalCrewController(crew);
        MockHttpServletRequest http = new MockHttpServletRequest();
        AuthenticatedActor actor = new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, USER, FACTORY, ROLE);
        http.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, actor);
        DigitalCrewService.AskView view;
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(actor)) {
            view = controller.ask(FACTORY, member, new DigitalCrewController.AskRequest(input, "web-session-1"), http)
                    .getData();
        }
        return new Run(view, turns);
    }
}
