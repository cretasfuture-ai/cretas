package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.cache.SemanticCacheHit;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.conversation.ConversationSession;
import com.cretas.aims.entity.conversation.ConversationSession.SessionStatus;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.repository.conversation.ConversationSessionRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.*;
import com.cretas.aims.service.impl.ConversationServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F006 prod 实测缺陷 1 / 2 的回归 (普通入口 {@code /ai-intents/execute}, 三轮同一个 sessionId)。
 *
 * <p>与 {@link OrdinaryEntryTaskStateContractTest} 的区别: 这里的短语表是<b>真的</b> {@link IntentKnowledgeBase}
 * (initDefaults 后的生产短语表), 编排器的短语快捷通道 (Sprint12-CacheFix / Round7-EarlyPhrase) 真的会命中
 * 「先看下库存」「继续报工」—— 改动前就是这条通道抢在任务判断之前把它们直接执行掉的。
 * 真的: 编排器、短语表、ConversationServiceImpl、ConversationTaskStateService (按工厂白名单开)。
 * 桩: 会话表 (内存)、识别结果 (空)、工具执行。
 */
@DisplayName("普通入口 — 选哪一道工序的续轮: 回序号选中, 回别的话交回识别 (真实 orchestrator.execute)")
class ReportWorkChoiceReplyOrdinaryEntryContractTest {

    private static final String F = "F006";
    private static final long USER = 1686L;
    private static final String ROLE = "operator";
    private static final String INTENT = "WORK_PROCESS_TASK_COMPLETE";
    /** 驼峰字段名 (actualQuantity / taskId): 回复里一个都不许有。 */
    private static final Pattern CAMEL_FIELD = Pattern.compile("[a-z]+[A-Z][A-Za-z0-9]*");

    private final Map<String, ConversationSession> sessions = new LinkedHashMap<>();
    private AIIntentService aiIntentService;
    private ConversationTaskStateService taskState;
    private IntentExecutionOrchestrator orchestrator;
    private IntentKnowledgeBase knowledgeBase;
    private ConversationSessionRepository repo;
    private ToolDispatchService toolDispatchService;
    private String sessionId;
    private final List<String> executed = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        aiIntentService = mock(AIIntentService.class);
        knowledgeBase = new IntentKnowledgeBase();
        knowledgeBase.initDefaults();
        SemanticCacheService semanticCache = mock(SemanticCacheService.class);
        when(semanticCache.queryCache(anyString(), anyString())).thenReturn(SemanticCacheHit.miss(0L));
        repo = inMemorySessions();
        ConversationServiceImpl conversations = new ConversationServiceImpl(
                repo, mock(AIIntentConfigRepository.class), null, mock(DashScopeClient.class));
        ReflectionTestUtils.setField(conversations, "maxRounds", 5);
        ReflectionTestUtils.setField(conversations, "timeoutMinutes", 10);
        ReflectionTestUtils.setField(conversations, "confidenceThreshold", 0.7);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        // 短语快捷通道命中后走显式意图 → 这两个意图没绑工具 → buildNoToolResponse。记下是哪个意图被执行了。
        toolDispatchService = mock(ToolDispatchService.class);
        when(toolDispatchService.buildNoToolResponse(any())).thenAnswer(inv -> IntentExecuteResponse.builder()
                .intentRecognized(true).intentCode(((AIIntentConfig) inv.getArgument(0)).getIntentCode())
                .status("COMPLETED").message("phrase-executed").build());
        orchestrator = new IntentExecutionOrchestrator(
                aiIntentService, mock(IntentSemanticsParser.class), semanticCache, mock(RuleEngineService.class),
                conversations, mock(ConversationMemoryService.class), new ObjectMapper(),
                mock(DashScopeClient.class), mock(DashScopeConfig.class), knowledgeBase,
                mock(AIAnalysisResultRepository.class), toolRegistry, mock(AnalysisRouterService.class),
                mock(ComplexityRouter.class), mock(AgentOrchestrator.class), mock(AgenticRAGRouterService.class),
                mock(ResultValidatorService.class), toolDispatchService,
                mock(DynamicToolSelectionService.class), mock(QueryPreprocessorService.class));
        ReflectionTestUtils.setField(orchestrator, "writeGuardService", new WriteGuardService());
        BusinessTypeGate businessTypeGate = mock(BusinessTypeGate.class);
        when(businessTypeGate.check(any(), any())).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(orchestrator, "businessTypeGate", businessTypeGate);
        OrchestratorPriceViewTestSupport.callerSeesAllAmounts(orchestrator);
        FactoryAnalysisTestMetadata.bindNamedFactories(orchestrator);

        // 与 prod 同一种开法: 全局关, 只有 F006 在白名单里
        taskState = new ConversationTaskStateService(repo);
        taskState.setEnabled(false);
        taskState.setFactories("F006");
        ReflectionTestUtils.setField(orchestrator, "taskStateService", taskState);

        // 报工工具: 参数说明与 WorkProcessTaskCompleteTool 的 schema 相同 (给人看的名字从这里来)
        ToolExecutor tool = mock(ToolExecutor.class);
        when(tool.getToolName()).thenReturn("work_process_task_complete");
        when(tool.getAccessMode()).thenReturn(ToolExecutor.AccessMode.WRITE);
        when(tool.getParametersSchema()).thenReturn(Map.of("type", "object", "properties", Map.of(
                "taskId", Map.of("type", "integer", "description", "工序任务编号"),
                "actualQuantity", Map.of("type", "number",
                        "description", "实际产量（一个数字，单位按这道工序的产出单位，如公斤或件）"))));
        when(toolRegistry.getExecutor("work_process_task_complete")).thenReturn(Optional.of(tool));
        AIIntentConfig intent = AIIntentConfig.builder().intentCode(INTENT).intentName("完成工序")
                .keywords("[\"报工\",\"报产量\",\"完成工序\"]")
                .intentCategory("PRODUCTION").businessType("FACTORY").sensitivityLevel("LOW").isActive(true)
                .toolName("work_process_task_complete").build();
        when(aiIntentService.getIntentByCode(F, INTENT)).thenReturn(Optional.of(intent));
        when(aiIntentService.hasPermission(anyString(), eq(ROLE))).thenReturn(true);
        // 短语表命中的那两个意图在本厂是配置了的 (prod 上就是它们被直接执行了)
        for (String code : List.of(phraseCode("先看下库存"), phraseCode("继续报工"))) {
            when(aiIntentService.getIntentByCode(F, code)).thenReturn(Optional.of(AIIntentConfig.builder()
                    .intentCode(code).intentName(code).intentCategory("QUERY").businessType("FACTORY")
                    .sensitivityLevel("LOW").isActive(true).build()));
        }
        when(aiIntentService.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), any(), any(),
                any(), any())).thenAnswer(inv -> IntentMatchResult.empty(inv.getArgument(0)));
        when(aiIntentService.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), any(), any(),
                any(), any(), anyList(), anyMap())).thenAnswer(inv -> IntentMatchResult.empty(inv.getArgument(0)));

        // 第 1 轮「我要报工，产量100」的结果 = ToolDispatchService.resolveChoiceOrAsk 留下的状态:
        // 参数收集会话 (待选的是哪一道工序, 产量已记 100) + 两个选项 (见 WorkProcessReportChoiceContractTest)
        sessionId = conversations.startParameterCollection(F, USER, INTENT, "完成工序",
                List.of(ConversationService.RequiredParameter.builder()
                        .name("taskId").label("工序任务编号").type("INTEGER").build()),
                List.of(), Map.of("actualQuantity", 100)).getSessionId();
        assertThat(conversations.attachParameterChoices(F, USER, sessionId, "taskId", "哪一道工序", List.of(
                ConversationService.CandidateInfo.builder().intentCode("101").intentName("红烧肉 · 批次 B-001 · 切块")
                        .description("红烧肉|B-001|切块").build(),
                ConversationService.CandidateInfo.builder().intentCode("202").intentName("卤鸭腿 · 批次 B-002 · 腌制")
                        .description("卤鸭腿|B-002|腌制").build()))).isTrue();
        // 选中后继续的是显式意图 → 工具分发 (桩): 记下被执行的意图
        when(toolDispatchService.executeWithTool(any(), anyString(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    executed.add(((AIIntentConfig) inv.getArgument(3)).getIntentCode());
                    return IntentExecuteResponse.builder().intentRecognized(true)
                            .intentCode(((AIIntentConfig) inv.getArgument(3)).getIntentCode())
                            .status("WRITE_CONFIRM_REQUIRED").message("需要确认").build();
                });
    }

    private String phraseCode(String text) {
        return knowledgeBase.matchPhrase(text, "FACTORY").orElseThrow();
    }

    @ParameterizedTest(name = "任务状态开关={0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("回「2」→ 选中第二道: 会话收集到 taskId=202 且产量 100 还在, 继续的是完成工序 (不重新识别)")
    void numberReplyPicksTheOptionAndContinuesTheSameIntent(boolean taskStateOn) {
        taskState.setFactories(taskStateOn ? "F006" : "");

        IntentExecuteResponse r = execute("2");

        ConversationSession s = sessions.get(sessionId);
        assertThat(s.getCollectedParameters()).containsEntry("taskId", "202").containsEntry("actualQuantity", "100");
        assertThat(s.getStatus()).isEqualTo(SessionStatus.COMPLETED);
        // 继续的是完成工序, 写操作停在写确认闸 (WRITE_CONFIRM_REQUIRED), 没有直接执行
        assertThat(r.getStatus()).isEqualTo("WRITE_CONFIRM_REQUIRED");
        assertThat(r.getIntentCode()).isEqualTo(INTENT);
        assertThat(executed).as("写操作没有被直接执行").isEmpty();
        verify(aiIntentService, never()).recognizeIntentWithConfidence(eq("2"), anyString(), anyInt(), any(),
                any(), any(), any(), any(), anyList(), anyMap());
    }

    @ParameterizedTest(name = "任务状态开关={0}: 「{1}」")
    @org.junit.jupiter.params.provider.CsvSource({
            "true,不报了", "false,不报了", "true,红烧肉还剩多少", "false,红烧肉还剩多少", "true,算了", "false,算了"})
    @DisplayName("阴性对照 (M1): 回的不是选择 → 不被旧选项吞掉、不回「没对上」, 交回识别, 没有写入确认")
    void anythingElseIsRecognisedAsANewSentence(boolean taskStateOn, String reply) {
        taskState.setFactories(taskStateOn ? "F006" : "");

        IntentExecuteResponse r = execute(reply);

        // 阳性对照: 识别真的被调用了 (这句话被当新话)
        assertThat(org.mockito.Mockito.mockingDetails(aiIntentService).getInvocations().stream()
                .filter(i -> "recognizeIntentWithConfidence".equals(i.getMethod().getName()))
                .map(i -> String.valueOf((Object) i.getArgument(0))).toList())
                .as("这句话被当新话识别了 (阳性对照)").contains(reply);
        assertThat(executed).as("没有被旧选项带去执行完成工序").doesNotContain(INTENT);
        assertThat(sessions.get(sessionId).getCollectedParameters()).doesNotContainKey("taskId");
        assertThat(sessions.get(sessionId).getStatus()).isNotEqualTo(SessionStatus.COMPLETED);
        assertThat(String.valueOf(r.getMessage())).doesNotContain("没对上");
    }

    private IntentExecuteResponse execute(String input) {
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, USER, F, ROLE))) {
            return orchestrator.execute(F, IntentExecuteRequest.builder().userInput(input).sessionId(sessionId)
                    .build(), USER, ROLE);
        }
    }

    private ConversationSessionRepository inMemorySessions() {
        ConversationSessionRepository r = mock(ConversationSessionRepository.class);
        when(r.save(any(ConversationSession.class))).thenAnswer(inv -> {
            ConversationSession s = inv.getArgument(0);
            if (s.getSessionId() == null) s.setSessionId(UUID.randomUUID().toString());
            if (s.getLastActiveAt() == null) s.setLastActiveAt(LocalDateTime.now());
            sessions.put(s.getSessionId(), s);
            return s;
        });
        when(r.findBySessionIdAndFactoryIdAndUserId(anyString(), anyString(), anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(sessions.get((String) inv.getArgument(0)))
                        .filter(s -> s.getFactoryId().equals(inv.getArgument(1))
                                && s.getUserId().equals(inv.getArgument(2))));
        when(r.findSuspendedByFactoryAndUser(anyString(), anyLong())).thenAnswer(inv -> sessions.values().stream()
                .filter(s -> s.getStatus() == SessionStatus.SUSPENDED && s.getFactoryId().equals(inv.getArgument(0))
                        && s.getUserId().equals(inv.getArgument(1))).toList());
        return r;
    }
}
