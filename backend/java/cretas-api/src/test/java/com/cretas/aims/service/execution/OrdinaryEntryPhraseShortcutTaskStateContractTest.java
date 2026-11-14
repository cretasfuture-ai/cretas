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
@DisplayName("普通入口 — 真实短语快捷通道命中时仍先挂起 / 先恢复; 回复不外露字段名; 开关关时顺序不变")
class OrdinaryEntryPhraseShortcutTaskStateContractTest {

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

        // 第 1 轮「我要报工，产量100」的结果: 参数收集会话 (缺任务号, 产量已抽到 100)。
        // 待问项的标签按编排器 enrichWithClarificationQuestions 的建法 = 字段名本身 (prod 那条路就是这样存的)
        sessionId = conversations.startParameterCollection(F, USER, INTENT, "完成工序",
                List.of(ConversationService.RequiredParameter.builder()
                        .name("taskId").label("taskId").type("INTEGER").build()),
                List.of(), Map.of("actualQuantity", 100)).getSessionId();
    }

    private String phraseCode(String text) {
        return knowledgeBase.matchPhrase(text, "FACTORY").orElseThrow();
    }

    @Test
    @DisplayName("前提读数: 生产短语表真的命中「先看下库存」「继续报工」(否则下面的测试测不到快捷通道)")
    void theRealPhraseTableHitsBothSentences() {
        Optional<String> inventory = knowledgeBase.matchPhrase("先看下库存", "FACTORY");
        Optional<String> resume = knowledgeBase.matchPhrase("继续报工", "FACTORY");
        System.out.println("[phrase-table] 先看下库存 -> " + inventory + ", 继续报工 -> " + resume);
        assertThat(inventory).isPresent();
        assertThat(resume).isPresent();
        assertThat(knowledgeBase.matchPhrase("先看下库存")).as("classify 用的单参版也认得它 (= 打断)").isPresent();
    }

    @Test
    @DisplayName("三轮同一个 sessionId: 「先看下库存」挂起报工 → 「继续报工」恢复, 不被短语表执行掉")
    void phraseShortcutYieldsToTaskStateWhenTheSwitchIsOn() {
        // 第 2 轮: 正在问任务号, 插一句查询 → 报工挂起 (改动前: 短语表直接执行 REPORT_INVENTORY, 会话一直 ACTIVE)
        IntentExecuteResponse inventory = execute("先看下库存");
        assertThat(sessions.get(sessionId).getStatus()).as("报工被挂起").isEqualTo(SessionStatus.SUSPENDED);
        assertThat(inventory.getStatus()).isNotEqualTo("CONVERSATION_CONTINUE");
        assertThat(inventory.getIntentCode()).as("挂起之后这句照常走短语快捷通道, 查库存照查")
                .isEqualTo(phraseCode("先看下库存"));

        // 第 3 轮: 「继续报工」→ 回到报工 (改动前: 短语表执行成 CONTEXT_CONTINUE「正在为您恢复上次操作」)
        IntentExecuteResponse resumed = execute("继续报工");
        assertThat(resumed.getStatus()).isEqualTo("CONVERSATION_CONTINUE");
        assertThat(resumed.getIntentCode()).isEqualTo(INTENT);
        assertThat(resumed.getSessionId()).isEqualTo(sessionId);
        assertThat(resumed.getMetadata()).containsEntry("taskResumed", true);
        assertThat(sessions.get(sessionId).getStatus()).isEqualTo(SessionStatus.ACTIVE);

        // 缺陷 2: 显示名, 不是字段名
        String message = resumed.getMessage();
        System.out.println("[resume-message] " + message);
        assertThat(message).contains("完成工序").contains("实际产量：100").contains("工序任务编号");
        assertThat(CAMEL_FIELD.matcher(message).find()).as("回复里不许有驼峰字段名: %s", message).isFalse();
        assertThat(message).doesNotContain("taskId").doesNotContain("actualQuantity");

        // 恢复只找回参数, 不执行任何东西 (短语表没有把「继续报工」执行成 CONTEXT_CONTINUE)
        verify(toolDispatchService, never()).buildNoToolResponse(
                org.mockito.ArgumentMatchers.argThat(i -> i != null && phraseCode("继续报工").equals(i.getIntentCode())));
        verify(aiIntentService, never()).recognizeIntentWithConfidence(eq("继续报工"), anyString(), anyInt(), any(),
                any(), any(), any(), any(), anyList(), anyMap());
    }

    @Test
    @DisplayName("正在问时说「继续完成工序」(它自己) → 重问, 重问那句同样不外露字段名")
    void repeatingTheOpenTaskUsesDisplayNames() {
        IntentExecuteResponse response = execute("继续完成工序");
        assertThat(response.getStatus()).isEqualTo("CONVERSATION_CONTINUE");
        assertThat(response.getSessionId()).isEqualTo(sessionId);
        assertThat(sessions.get(sessionId).getStatus()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(response.getMessage()).contains("实际产量：100").contains("工序任务编号");
        assertThat(CAMEL_FIELD.matcher(response.getMessage()).find())
                .as("回复里不许有驼峰字段名: %s", response.getMessage()).isFalse();
    }

    @Test
    @DisplayName("阴性对照: 开关对本厂关 → 短语表照旧先执行, 不查任务、不挂起、不恢复 (与改动前相同)")
    void switchOffKeepsThePhraseShortcutFirst() {
        taskState.setFactories("");
        clearInvocations(repo);

        IntentExecuteResponse inventory = execute("先看下库存");
        assertThat(inventory.getIntentCode()).as("必然读数: 短语快捷通道照旧先执行").isEqualTo(phraseCode("先看下库存"));
        assertThat(sessions.get(sessionId).getStatus()).as("开关关: 不挂起").isEqualTo(SessionStatus.ACTIVE);
        verify(repo, never()).findBySessionIdAndFactoryIdAndUserId(anyString(), anyString(), anyLong());
        verify(repo, never()).findSuspendedByFactoryAndUser(anyString(), anyLong());

        IntentExecuteResponse cont = execute("继续报工");
        assertThat(cont.getIntentCode()).as("开关关: 与改动前一样被短语表执行").isEqualTo(phraseCode("继续报工"));
        assertThat(cont.getMetadata() == null || !Boolean.TRUE.equals(cont.getMetadata().get("taskResumed")))
                .as("开关关: 不走恢复").isTrue();
        verify(repo, never()).findSuspendedByFactoryAndUser(anyString(), anyLong());
    }

    @Test
    @DisplayName("显示名兜底: 工具没说明、常用表也没有的字段 → 只计数, 仍不出现字段名")
    void unknownFieldsAreCountedNotNamed() {
        ConversationSession s = sessions.get(sessionId);
        s.addCollectedParameter("lineCode", "L2");
        String message = ConversationTaskStateService.resumeMessage(s, code -> List.of("完成工序"), (c, p) -> null);
        System.out.println("[fallback-message] " + message);
        assertThat(message).contains("实际产量：100").contains("另有 1 项信息").contains("工序任务");
        assertThat(CAMEL_FIELD.matcher(message).find()).as("回复里不许有驼峰字段名: %s", message).isFalse();
        assertThat(message).doesNotContain("lineCode").doesNotContain("L2");
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
