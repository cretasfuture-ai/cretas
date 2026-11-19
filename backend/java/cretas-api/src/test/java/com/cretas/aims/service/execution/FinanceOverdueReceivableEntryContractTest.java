package com.cretas.aims.service.execution;

import com.cretas.aims.ai.capability.FactoryBusinessTypeResolver;
import com.cretas.aims.ai.capability.FactoryCapabilityPackSelector;
import com.cretas.aims.ai.client.F006BossQuestionClient;
import com.cretas.aims.ai.grounding.ChatBusinessFactGuard;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.config.IntentKnowledgeBase.QuestionType;
import com.cretas.aims.controller.DigitalCrewController;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.crew.DigitalCrewDelegationLock;
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
import com.cretas.aims.service.intent.LegacyIntentExitMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BP4 问题 48 (2026-11-19 20:xx, prod main 19500db4c7): F006 张示范 (factory_super_admin) 在 web 管理端财务数字专员
 * 问「哪些客户有逾期应收？先催哪几张单，为什么？」, 约 40 秒回来的是本地 27B 的通用催款建议 (护栏去掉了编的本厂数据),
 * 没有查数。账龄页实际 3 家客户逾期, 合计 49,086 元。
 *
 * <p>根因: 早期问题类型检测因「为什么」判成 GENERAL_QUESTION; 「业务问法不进闲聊」的判定 {@link ChatBusinessFactGuard#isBusinessQuestion}
 * 又因「为什么」里的「什么」把整句当概念咨询 ⇒ 走了闲聊 / F006 老板提问本地模型, 识别层一次都没调, 应收账龄工具没执行。
 * prod 同一句去掉「为什么」, 识别 8/8 定到 ACCOUNTS_RECEIVABLE_AGING 并列出 3 家 / ¥49,086 (证据见 PR)。
 *
 * <p>跑在两个产品真实入口: 财务数字专员工作台 ({@code POST /ai-intents/execute}, mode=READ, 带会话) 走门面
 * {@link com.cretas.aims.service.impl.IntentExecutorServiceImpl}; 数字班组「数字成本会计」走
 * {@link DigitalCrewController#ask} → {@link DigitalCrewService}。两者都汇到同一条流式管线。
 * 真的: 门面 / 数字班组服务 / 流式管线 / 编排器步骤、真实问题类型检测与短语表、真实闲聊护栏。
 * 桩: 识别层结果 (外部模型)、工具分发 (返回账龄工具那段带真实数的文字)、F006 本地模型与云端闲聊模型 (返回编的本厂数)。
 */
@DisplayName("BP4-48: 财务专员问逾期应收 → 先查应收账龄, 不进闲聊 / 本地模型")
class FinanceOverdueReceivableEntryContractTest {

    enum Entry { WORKDESK_EXECUTE, CREW_ASK }

    private static final String FACTORY = "F006";
    private static final long USER = 1707L;
    private static final String ROLE = "viewer";
    /** 线上原句, 带标点。 */
    private static final String QUESTION = "哪些客户有逾期应收？先催哪几张单，为什么？";
    private static final String AR_INTENT = "ACCOUNTS_RECEIVABLE_AGING";
    /** 账龄工具 (overdue 块) 的真实输出形状, 数字来自工具, 必须原样到用户。 */
    private static final String TOOL_TEXT = "【逾期应收（账龄页口径：挂账流水里已过到期日的未结清单据）】\n"
            + "逾期应收共 3 家客户、4 张单据，合计 ¥49086.00。\n建议先催（按逾期天数x金额排序，前 3 张）：\n"
            + "1. 单号 AR-0001，客户 演示·上海连锁餐饮，金额 ¥8514.00，逾期 44 天，理由：逾期最久";
    /** 模型自由回答里编的本厂数 (线上被护栏剔掉的那类)。 */
    private static final String FABRICATED = "演示·上海连锁餐饮逾期 8514 元，建议先催它。催款一般先电话对账。";

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("原句 → 交给识别、执行应收账龄工具, 工具里的数原样显示; 本地模型 / 闲聊模型一次都不调")
    void overdueReceivableQuestionQueriesTheAgingToolInsteadOfFreeAnswer(Entry entry) throws Exception {
        StreamTurnCollectorEntryParityContractTest.Harness h = harness();
        assertThat(h.knowledgeBase.detectQuestionType(QUESTION))
                .as("前提 (读数来源: 真实问题类型检测): 这句早期就被判成通用问法").isEqualTo(QuestionType.GENERAL_QUESTION);
        assertThat(h.knowledgeBase.matchPhrase(QUESTION.toLowerCase(), "FACTORY"))
                .as("前提 (真实短语表): 没有短语截走这句, 只能靠早期分流把它交给识别").isEmpty();
        F006BossQuestionClient mini = miniAnswers(h, FABRICATED);
        h.chatReplies(FABRICATED);
        AIIntentConfig ar = h.intent(AR_INTENT, "应收账龄", "accounts_receivable_aging", ToolExecutor.AccessMode.READ);
        ar.setRequiredPermission("finance:read"); // 账龄工具的门 (finance:read / read_write), 归数字成本会计管
        h.recognizerAnswers(recognized(ar));
        h.toolReturns(IntentExecuteResponse.builder().intentRecognized(true).intentCode(AR_INTENT)
                .status("SUCCESS").message(TOOL_TEXT).formattedText(TOOL_TEXT).build());

        Shown shown = send(h, entry);

        assertThat(h.recognitionCalls()).as("交给了识别层").isEqualTo(1);
        assertThat(h.executedIntents).as("阳性对照: 应收账龄工具真的被执行").containsExactly(AR_INTENT);
        assertThat(invocations(mini, "stream")).as("没进 F006 本地模型").isZero();
        assertThat(h.llmChatCalls()).as("没进云端闲聊模型").isZero();
        assertThat(shown.intentCode).isEqualTo(AR_INTENT);
        assertThat(shown.text).as("工具里的数原样到用户 (有依据的数不被护栏剔)").isEqualTo(TOOL_TEXT)
                .doesNotContain("已去掉");
        assertThat(h.guard.total(SseStreamingService.CHAT_FACT_GUARD_SITE)
                + h.guard.total(SseStreamingService.MINI_FACT_GUARD_SITE)).as("查询结果不走闲聊护栏").isZero();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("阴性对照: 「怎么判断应收先催哪几张单？」是咨询, 仍由本地模型自由回答, 编的本厂数仍被护栏剔掉")
    void howToQuestionStillGoesToTheLocalModelAndFabricatedNumbersAreStillDropped(Entry entry) throws Exception {
        StreamTurnCollectorEntryParityContractTest.Harness h = harness();
        String input = "怎么判断应收先催哪几张单？";
        assertThat(h.knowledgeBase.detectQuestionType(input)).as("前提: 早期判成通用问法")
                .isEqualTo(QuestionType.GENERAL_QUESTION);
        F006BossQuestionClient mini = miniAnswers(h, FABRICATED);
        h.chatReplies(FABRICATED);

        Shown shown = send(h, entry, input);

        assertThat(h.recognitionCalls()).as("咨询句不进识别").isZero();
        assertThat(invocations(mini, "stream") + h.llmChatCalls()).as("阳性对照: 自由回答模型真的被调用了一次")
                .isEqualTo(1);
        assertThat(shown.text).as("护栏没被放宽: 编的金额照旧剔掉").doesNotContain("8514");
        assertThat(h.guard.count(SseStreamingService.MINI_FACT_GUARD_SITE, ChatBusinessFactGuard.Decision.BLOCKED)
                + h.guard.count(SseStreamingService.CHAT_FACT_GUARD_SITE, ChatBusinessFactGuard.Decision.BLOCKED))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("前提读数: 工作台入口的本地模型通道真的接通 (不是因为桩没接上才「没调」)")
    void localModelChannelIsWiredInThisHarness() throws Exception {
        StreamTurnCollectorEntryParityContractTest.Harness h = harness();
        F006BossQuestionClient mini = miniAnswers(h, "催款一般先电话对账，再发对账函。");
        h.chatReplies("不该到这里");

        Shown shown = send(h, Entry.WORKDESK_EXECUTE, "怎么判断应收先催哪几张单？");

        assertThat(invocations(mini, "stream")).isEqualTo(1);
        assertThat(h.llmChatCalls()).isZero();
        assertThat(shown.text).isEqualTo("催款一般先电话对账，再发对账函。");
    }

    // ------------------------------------------------------------------ harness

    private record Shown(String intentCode, String text) { }

    private static StreamTurnCollectorEntryParityContractTest.Harness harness() {
        StreamTurnCollectorEntryParityContractTest.Harness h = new StreamTurnCollectorEntryParityContractTest.Harness(
                StreamTurnCollectorEntryParityContractTest.Entry.EXECUTE);
        h.useRealSessionServices();
        return h;
    }

    /** F006 老板提问本地模型: 按线上 factory_super_admin 那条路由开着, 回一段编了本厂数的话。 */
    private static F006BossQuestionClient miniAnswers(StreamTurnCollectorEntryParityContractTest.Harness h,
                                                      String reply) throws Exception {
        F006BossQuestionClient mini = mock(F006BossQuestionClient.class);
        when(mini.shouldRoute(FACTORY, USER, ROLE)).thenReturn(true);
        doAnswer(inv -> {
            Consumer<String> tokens = inv.getArgument(6);
            tokens.accept(reply);
            return new F006BossQuestionClient.Answer(reply, "mac-mini", "qwen3.8:27b", "stop", "c".repeat(32), null);
        }).when(mini).stream(anyString(), any(), anyString(), anyString(), anyBoolean(), any(), any(), any());
        h.miniClient = mini;
        return mini;
    }

    private static IntentMatchResult recognized(AIIntentConfig intent) {
        return IntentMatchResult.builder().userInput(QUESTION).bestMatch(intent)
                .matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).confidence(0.93)
                .requiresConfirmation(false).questionType(QuestionType.OPERATIONAL_COMMAND).build();
    }

    private static Shown send(StreamTurnCollectorEntryParityContractTest.Harness h, Entry entry) throws Exception {
        return send(h, entry, QUESTION);
    }

    private static Shown send(StreamTurnCollectorEntryParityContractTest.Harness h, Entry entry, String input)
            throws Exception {
        if (entry == Entry.WORKDESK_EXECUTE) {
            // useWorkdeskIntent.ts: userInput + 前端生成的 sessionId + mode READ
            IntentExecuteResponse r = h.sendThroughFacade(IntentExecuteRequest.builder()
                    .userInput(input).sessionId("workdesk-session-1").mode("READ").build());
            return new Shown(r.getIntentCode(), r.getFormattedText() != null ? r.getFormattedText() : r.getMessage());
        }
        com.cretas.aims.service.impl.IntentExecutorServiceImpl facade = h.facade();
        ReflectionTestUtils.setField(h.sse, "legacyExitMetrics",
                new LegacyIntentExitMetrics(new SimpleMeterRegistry(), 900, 20, 0.05, 0.2));
        DigitalCrewDelegationLockRepository locks = mock(DigitalCrewDelegationLockRepository.class);
        when(locks.existsById(anyString())).thenReturn(true);
        when(locks.lockForUpdate(anyString(), anyString())).thenReturn(Optional.of(new DigitalCrewDelegationLock()));
        UserRepository users = mock(UserRepository.class);
        User me = new User();
        me.setId(USER);
        when(users.findById(USER)).thenReturn(Optional.of(me));
        DigitalCrewWorkItemRepository items = mock(DigitalCrewWorkItemRepository.class);
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
            // 新开对话: 前端第一问不带 sessionId (CrewMemberPage.vue)
            view = controller.ask(FACTORY, "accountant", new DigitalCrewController.AskRequest(input, null), http)
                    .getData();
        }
        assertThat(invocations(items, "saveAndFlush")).as("读问题不交办 (没有写数字班组工作项)").isZero();
        return new Shown(view.intentCode(), view.text());
    }

    private static long invocations(Object mockOrSpy, String method) {
        return org.mockito.Mockito.mockingDetails(mockOrSpy).getInvocations().stream()
                .filter(i -> method.equals(i.getMethod().getName()))
                .count();
    }
}
