package com.cretas.aims.service.execution;

import com.cretas.aims.ai.capability.FactoryCapabilityPackRegistry;
import com.cretas.aims.ai.capability.FactoryCapabilityPackRoutingPolicy;
import com.cretas.aims.ai.capability.FactoryCapabilityPackSelector;
import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.impl.workprocess.WorkProcessTaskCompleteTool;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.config.IntentSlotConfiguration;
import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.cache.SemanticCacheHit;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.conversation.ConversationSession;
import com.cretas.aims.entity.conversation.ConversationSession.SessionStatus;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.workprocess.WorkProcessTask.Status;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.repository.conversation.ConversationSessionRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.*;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.cretas.aims.service.impl.ConversationServiceImpl;
import com.cretas.aims.service.impl.SlotFillingServiceImpl;
import com.cretas.aims.service.workprocess.WorkProcessTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * prod 2026-11-14 16:28 F006: #1706 合并部署后, 两个真实入口第一轮「我要报工，产量100」都没列可报工序:
 * <ul>
 *   <li>流式: 7b Slot Filling 先问「需要您提供以下信息：1. 请提供工序任务编号」, 回「1」被收成 taskId=1;</li>
 *   <li>普通: 短语快捷通道 → 显式意图 → W0 直接出空确认卡「「完成工序」是写入/修改操作，执行前需要确认。」。</li>
 * </ul>
 * #1706 的选项只挂在 {@code ToolDispatchService#executeToolPreview}, 它的入口测试从第 2 轮手工开好的选项会话起步、
 * 工具分发整个打桩 —— 第 1 轮真正经过的 Slot Filling / W0 都没跑, 所以全绿。
 *
 * <p>这里从<b>第 1 轮</b>起跑在真实入口上: 真的 SseStreamingService (含 7a.5 抽参、7b Slot Filling = 真
 * SlotFillingServiceImpl) / 真的 IntentExecutionOrchestrator (含短语快捷通道 = 真 IntentKnowledgeBase、W0 写确认、
 * 3.6 Slot Filling 入口)、真的 ToolDispatchService (列选项)、真的 WorkProcessTaskCompleteTool、真的
 * ConversationServiceImpl。桩只有: 工序任务服务 (给出 2 道 / 1 道 / 0 道进行中)、LLM 抽参 (抽到产量 100)、
 * 会话表 (内存)、识别 (普通入口非短语那条)、流式第 2 轮之后的工具执行 (它之前的都是真的)。
 */
@DisplayName("报工第 1 轮跑在真实入口上: 先列可报工序 (不问编号、不出空确认卡), 回「1」= 选第 1 项")
class ReportWorkChoiceFirstTurnEntryContractTest {

    private static final String F = "F006";
    private static final long USER = 1707L;
    private static final String ROLE = "operator";
    private static final String INTENT = "WORK_PROCESS_TASK_COMPLETE";
    private static final String TURN1 = "我要报工，产量100";

    private final Map<String, ConversationSession> sessions = new LinkedHashMap<>();
    private WorkProcessTaskService taskService;
    private WorkProcessTaskCompleteTool completeTool;
    private ToolRegistry toolRegistry;
    private ConversationServiceImpl conversations;
    private ConversationSessionRepository repo;
    private ToolDispatchService dispatch;
    private AIIntentService aiIntentService;
    private AIIntentConfig complete;
    private IntentKnowledgeBase knowledgeBase;

    @BeforeEach
    void commonSetUp() {
        taskService = mock(WorkProcessTaskService.class);
        completeTool = new WorkProcessTaskCompleteTool(taskService);
        ReflectionTestUtils.setField(completeTool, "objectMapper", new ObjectMapper());
        toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getExecutor(completeTool.getToolName())).thenReturn(Optional.of(completeTool));

        repo = inMemorySessions();
        conversations = new ConversationServiceImpl(repo, mock(AIIntentConfigRepository.class), null,
                mock(DashScopeClient.class));
        ReflectionTestUtils.setField(conversations, "maxRounds", 5);
        ReflectionTestUtils.setField(conversations, "timeoutMinutes", 10);
        ReflectionTestUtils.setField(conversations, "confidenceThreshold", 0.7);

        // 真的派发层 (列选项的出口); 只把 LLM 抽参换成「抽到产量 100」
        dispatch = spy(new ToolDispatchService(
                toolRegistry, new ObjectMapper(), mock(DashScopeClient.class),
                mock(ToolCallRedundancyService.class), mock(SelfCorrectionService.class),
                mock(CorrectionAgentService.class), mock(ExternalVerifierService.class),
                mock(ToolResultValidatorService.class), mock(ParameterExtractionLearningService.class)));
        ReflectionTestUtils.setField(dispatch, "conversationService", (ConversationService) conversations);
        ReflectionTestUtils.setField(dispatch, "writeGuardService", new WriteGuardService());
        doAnswer(inv -> {
            String input = inv.getArgument(1);
            Map<String, Object> existing = inv.getArgument(3);
            Map<String, Object> out = new HashMap<>();
            if (input != null && input.contains("100") && existing.get("actualQuantity") == null) {
                out.put("actualQuantity", "100");
            }
            return out;
        }).when(dispatch).extractParametersWithLLM(anyString(), any(), eq(completeTool), anyMap());

        aiIntentService = mock(AIIntentService.class);
        complete = AIIntentConfig.builder().intentCode(INTENT).intentName("完成工序")
                .keywords("[\"报工\",\"报产量\",\"完成工序\"]")
                .intentCategory("PRODUCTION").businessType("FACTORY").sensitivityLevel("LOW").isActive(true)
                .toolName(completeTool.getToolName()).build();
        when(aiIntentService.getIntentByCode(F, INTENT)).thenReturn(Optional.of(complete));
        when(aiIntentService.hasPermission(anyString(), eq(ROLE))).thenReturn(true);

        knowledgeBase = new IntentKnowledgeBase();
        knowledgeBase.initDefaults();
        assertThat(knowledgeBase.matchPhrase(TURN1)).as("前提: 「我要报工，产量100」在短语表里指向报工").contains(INTENT);
    }

    // ================================================================== 流式入口 (App 实际走的 executeStreamAsync)

    @Nested
    @DisplayName("流式入口")
    class Stream {
        private SseStreamingService service;
        private final SseEmitter emitter = mock(SseEmitter.class);
        private final List<Map<String, Object>> executedContexts = new java.util.ArrayList<>();

        @BeforeEach
        void setUp() throws Exception {
            FactoryRepository factoryRepository = mock(FactoryRepository.class);
            Factory factory = new Factory();
            factory.setId(F);
            factory.setType(FactoryType.FACTORY);
            factory.setIsActive(true);
            when(factoryRepository.findById(anyString())).thenReturn(Optional.of(factory));
            SemanticCacheService semanticCache = mock(SemanticCacheService.class);
            when(semanticCache.queryCache(anyString(), anyString())).thenReturn(SemanticCacheHit.miss(0L));

            IntentSlotConfiguration slotConfiguration = new IntentSlotConfiguration();
            slotConfiguration.init();
            // 真的通用缺参追问 (prod 第 1 轮就是它问了「请提供工序任务编号」)
            SlotFillingServiceImpl slotFilling = new SlotFillingServiceImpl(slotConfiguration, toolRegistry,
                    conversations, mock(ParameterExtractionLearningService.class));

            service = spy(new SseStreamingService(
                    aiIntentService, semanticCache, knowledgeBase,
                    mock(AnalysisRouterService.class), new ObjectMapper(), toolRegistry, dispatch,
                    mock(DynamicToolSelectionService.class), mock(DashScopeClient.class), mock(DashScopeConfig.class)));
            ReflectionTestUtils.setField(service, "factoryCapabilityPackRoutingPolicy",
                    new FactoryCapabilityPackRoutingPolicy(factoryRepository,
                            new FactoryCapabilityPackSelector(new FactoryCapabilityPackRegistry()), false));
            ReflectionTestUtils.setField(service, "writeGuardService", new WriteGuardService());
            ReflectionTestUtils.setField(service, "businessTypeGate", mock(BusinessTypeGate.class));
            ReflectionTestUtils.setField(service, "slotFillingService", slotFilling);
            ReflectionTestUtils.setField(service, "conversationService", conversations);
            ConversationTaskStateService taskState = new ConversationTaskStateService(repo);
            taskState.setEnabled(false);
            ReflectionTestUtils.setField(service, "taskStateService", taskState);
            doNothing().when(service).sendSseEvent(any(), anyString(), any());
            FactoryAnalysisTestMetadata.bindNamedFactories(service);

            // 识别兜底: 万一短语通道没接住, 识别也给出报工 (两条路都得列选项)
            when(aiIntentService.recognizeIntentWithConfidence(anyString(), eq(F), anyInt(), any(), any(), any(),
                    any(), any(), any())).thenAnswer(inv -> recognized(inv.getArgument(0)));
            when(aiIntentService.recognizeIntentWithConfidence(anyString(), eq(F), anyInt(), any(), any(), any(),
                    any(), any(), any(), anyMap())).thenAnswer(inv -> recognized(inv.getArgument(0)));

            // 选中之后的工具执行 (它之前的 Slot Filling / 续轮 / 选项匹配都是真的): 记下收到的参数, 回写确认
            doAnswer(inv -> {
                IntentExecuteRequest req = inv.getArgument(2);
                executedContexts.add(req.getContext() == null ? Map.of() : new LinkedHashMap<>(req.getContext()));
                return IntentExecuteResponse.builder().intentRecognized(true).intentCode(INTENT)
                        .status("WRITE_CONFIRM_REQUIRED").message("该操作会写入/修改数据，执行前需要确认。").build();
            }).when(dispatch).executeWithTool(eq(completeTool), eq(F), any(), eq(complete), eq(USER), eq(ROLE), any());
        }

        @Test
        @DisplayName("2 道可报: 第 1 轮列「产品·批次·工序·计划/已报」, 不问编号; 回「1」选中第 1 项 (101, 不是 taskId=1), 产量 100 保留, 停在写确认")
        @SuppressWarnings("unchecked")
        void listsOnTheFirstTurnAndReplyOnePicksTheFirstOption() throws Exception {
            twoTasks();

            IntentExecuteResponse first = stream(TURN1, null);

            assertThat(first.getStatus()).isEqualTo("NEED_MORE_INFO");
            assertThat(first.getMessage())
                    .contains("产量 100 我先记着")
                    .contains("1. 红烧肉 · 批次 B-001 · 切块 · 计划 100kg，已报 20kg")
                    .contains("2. 卤鸭腿 · 批次 B-002 · 腌制 · 计划 50kg，还没报过")
                    .doesNotContain("编号").doesNotContain("需要您提供以下信息").doesNotContain("taskId");
            assertThat(first.getSessionId()).isNotBlank();
            // 必发生: 查的是本人 (+未指派) 的进行中工序
            verify(taskService).list(eq(F), eq(Status.IN_PROGRESS), eq(null), eq(USER), any(Pageable.class));
            assertThat(executedContexts).as("第 1 轮没去执行写工具").isEmpty();

            IntentExecuteResponse second = stream("1", first.getSessionId());

            assertThat(executedContexts).hasSize(1);
            assertThat(executedContexts.get(0)).containsEntry("taskId", "101").containsEntry("actualQuantity", "100");
            assertThat(second.getStatus()).isEqualTo("WRITE_CONFIRM_REQUIRED");
            Map<String, Object> previewParams = (Map<String, Object>) second.getMetadata().get("previewParams");
            assertThat(previewParams).containsEntry("taskId", "101").containsEntry("actualQuantity", "100");
            assertThat(sessions.get(first.getSessionId()).getStatus()).isEqualTo(SessionStatus.COMPLETED);
            verify(taskService, never()).complete(anyString(), anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("只有 1 道可报: 第 1 轮直接带出 (taskId=101 + 产量 100), 不追问, 仍停在写确认卡")
        @SuppressWarnings("unchecked")
        void singleTaskIsCarriedButStillNeedsConfirmation() throws Exception {
            oneTask();

            IntentExecuteResponse first = stream(TURN1, null);

            assertThat(first.getStatus()).isEqualTo("WRITE_CONFIRM_REQUIRED");
            assertThat(executedContexts).hasSize(1);
            assertThat(executedContexts.get(0)).containsEntry("taskId", "101").containsEntry("actualQuantity", "100");
            Map<String, Object> previewParams = (Map<String, Object>) first.getMetadata().get("previewParams");
            assertThat(previewParams).containsEntry("taskId", "101").containsEntry("actualQuantity", "100");
            verify(taskService, never()).complete(anyString(), anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("一道都没有: 如实说没有、提示先开工, 不问编号、不执行")
        void noTaskSaysSo() throws Exception {
            when(taskService.list(eq(F), eq(Status.IN_PROGRESS), any(), eq(USER), any(Pageable.class)))
                    .thenReturn(page(List.of()));

            IntentExecuteResponse first = stream(TURN1, null);

            assertThat(first.getMessage()).contains("没有进行中的工序任务").doesNotContain("编号");
            assertThat(executedContexts).isEmpty();
            verify(taskService).list(eq(F), eq(Status.IN_PROGRESS), any(), eq(USER), any(Pageable.class));
        }

        private IntentExecuteResponse stream(String input, String sessionId) throws Exception {
            org.mockito.Mockito.clearInvocations(service);
            doNothing().when(service).sendSseEvent(any(), anyString(), any());
            IntentExecuteRequest request = IntentExecuteRequest.builder().userInput(input).sessionId(sessionId).build();
            try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(
                    new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, USER, F, ROLE))) {
                ReflectionTestUtils.invokeMethod(service, "executeStreamAsync", emitter, F, request, USER, ROLE);
            }
            ArgumentCaptor<Object> result = ArgumentCaptor.forClass(Object.class);
            verify(service, org.mockito.Mockito.atLeastOnce()).sendSseEvent(eq(emitter), eq("result"), result.capture());
            List<Object> all = result.getAllValues();
            return (IntentExecuteResponse) all.get(all.size() - 1);
        }
    }

    // ================================================================== 普通入口 (/execute → orchestrator.execute)

    @Nested
    @DisplayName("普通入口")
    class Ordinary {
        private IntentExecutionOrchestrator orchestrator;

        @BeforeEach
        void setUp() {
            SemanticCacheService semanticCache = mock(SemanticCacheService.class);
            when(semanticCache.queryCache(anyString(), anyString())).thenReturn(SemanticCacheHit.miss(0L));
            orchestrator = new IntentExecutionOrchestrator(
                    aiIntentService, mock(IntentSemanticsParser.class), semanticCache, mock(RuleEngineService.class),
                    conversations, mock(ConversationMemoryService.class), new ObjectMapper(),
                    mock(DashScopeClient.class), mock(DashScopeConfig.class), knowledgeBase,
                    mock(AIAnalysisResultRepository.class), toolRegistry, mock(AnalysisRouterService.class),
                    mock(ComplexityRouter.class), mock(AgentOrchestrator.class), mock(AgenticRAGRouterService.class),
                    mock(ResultValidatorService.class), dispatch,
                    mock(DynamicToolSelectionService.class), mock(QueryPreprocessorService.class));
            ReflectionTestUtils.setField(orchestrator, "writeGuardService", new WriteGuardService());
            BusinessTypeGate businessTypeGate = mock(BusinessTypeGate.class);
            when(businessTypeGate.check(any(), any())).thenReturn(Optional.empty());
            ReflectionTestUtils.setField(orchestrator, "businessTypeGate", businessTypeGate);
            OrchestratorPriceViewTestSupport.callerSeesAllAmounts(orchestrator);
            FactoryAnalysisTestMetadata.bindNamedFactories(orchestrator);
            ConversationTaskStateService taskState = new ConversationTaskStateService(repo);
            taskState.setEnabled(false);
            taskState.setFactories("F006");
            ReflectionTestUtils.setField(orchestrator, "taskStateService", taskState);

            when(aiIntentService.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), any(),
                    any(), any(), any())).thenAnswer(inv -> recognized(inv.getArgument(0)));
            when(aiIntentService.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), any(),
                    any(), any(), any(), anyList(), anyMap())).thenAnswer(inv -> recognized(inv.getArgument(0)));
        }

        @Test
        @DisplayName("2 道可报 (短语快捷通道 → 显式意图): 第 1 轮列选项, 不出空确认卡; 回「1」选中 101 (不是 taskId=1), 产量 100 保留, 停在 W0 写确认")
        @SuppressWarnings("unchecked")
        void phraseShortcutListsOnTheFirstTurnAndReplyOnePicksTheFirstOption() {
            twoTasks();

            IntentExecuteResponse first = execute(TURN1, null);

            assertThat(first.getStatus()).isEqualTo("NEED_MORE_INFO");
            assertThat(first.getMessage())
                    .contains("产量 100 我先记着")
                    .contains("1. 红烧肉 · 批次 B-001 · 切块")
                    .contains("2. 卤鸭腿 · 批次 B-002 · 腌制")
                    .doesNotContain("是写入/修改操作").doesNotContain("编号");
            assertThat(first.getSessionId()).isNotBlank();
            verify(taskService).list(eq(F), eq(Status.IN_PROGRESS), eq(null), eq(USER), any(Pageable.class));

            IntentExecuteResponse second = execute("1", first.getSessionId());

            assertThat(second.getStatus()).as("写确认没被绕过").isEqualTo("WRITE_CONFIRM_REQUIRED");
            assertThat(second.getIntentCode()).isEqualTo(INTENT);
            Map<String, Object> previewParams = (Map<String, Object>) second.getMetadata().get("previewParams");
            assertThat(previewParams).containsEntry("taskId", "101").containsEntry("actualQuantity", "100");
            assertThat(sessions.get(first.getSessionId()).getCollectedParameters()).containsEntry("taskId", "101");
            verify(dispatch, never()).executeWithTool(any(), anyString(), any(), any(), any(), any(), any());
            verify(taskService, never()).complete(anyString(), anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("只有 1 道可报: 第 1 轮直接带出, W0 确认卡带 previewParams (taskId=101 + 产量 100), 不执行")
        @SuppressWarnings("unchecked")
        void singleTaskIsCarriedIntoTheWriteConfirm() {
            oneTask();

            IntentExecuteResponse first = execute(TURN1, null);

            assertThat(first.getStatus()).isEqualTo("WRITE_CONFIRM_REQUIRED");
            Map<String, Object> previewParams = (Map<String, Object>) first.getMetadata().get("previewParams");
            assertThat(previewParams).containsEntry("taskId", "101").containsEntry("actualQuantity", "100");
            verify(dispatch, never()).executeWithTool(any(), anyString(), any(), any(), any(), any(), any());
            verify(taskService, never()).complete(anyString(), anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("非短语说法 (走识别 → 3.6 Slot Filling 那条): 同样先列选项, 不问编号")
        void recognisedPathAlsoListsBeforeTheGenericQuestion() {
            twoTasks();
            String input = "帮我把今天这道活报一下，做了100";
            assertThat(knowledgeBase.matchPhrase(input)).as("前提: 不在短语表里, 走识别").isEmpty();

            IntentExecuteResponse first = execute(input, null);

            assertThat(first.getStatus()).isEqualTo("NEED_MORE_INFO");
            assertThat(first.getMessage()).contains("1. 红烧肉 · 批次 B-001 · 切块").doesNotContain("编号");
            verify(taskService).list(eq(F), eq(Status.IN_PROGRESS), eq(null), eq(USER), any(Pageable.class));
        }

        @Test
        @DisplayName("对照: 已确认的请求 / 话里自带 taskId 不列选项 (不多查)")
        void explicitTaskIdSkipsTheList() {
            twoTasks();
            IntentExecuteResponse r;
            try (AuthenticatedActor.ActorScope ignored = actor()) {
                r = orchestrator.executeWithExplicitIntent(F, IntentExecuteRequest.builder().intentCode(INTENT)
                        .userInput(TURN1).context(new HashMap<>(Map.of("taskId", 202, "actualQuantity", 100)))
                        .build(), USER, ROLE);
            }
            assertThat(r.getStatus()).isEqualTo("WRITE_CONFIRM_REQUIRED");
            verify(taskService, never()).list(anyString(), any(), any(), any(), any(Pageable.class));
        }

        private IntentExecuteResponse execute(String input, String sessionId) {
            try (AuthenticatedActor.ActorScope ignored = actor()) {
                return orchestrator.execute(F, IntentExecuteRequest.builder().userInput(input).sessionId(sessionId)
                        .build(), USER, ROLE);
            }
        }
    }

    // ================================================================== helpers

    private static AuthenticatedActor.ActorScope actor() {
        return AuthenticatedActor.trustedAsyncScope(
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, USER, F, ROLE));
    }

    private IntentMatchResult recognized(String input) {
        return IntentMatchResult.builder().userInput(input).bestMatch(complete).confidence(0.95)
                .matchMethod(IntentMatchResult.MatchMethod.LLM).isStrongSignal(true).requiresConfirmation(false)
                .questionType(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND).build();
    }

    private void twoTasks() {
        when(taskService.list(eq(F), eq(Status.IN_PROGRESS), any(), eq(USER), any(Pageable.class)))
                .thenReturn(page(List.of(
                        task(101, "红烧肉", "B-001", "切块", "100", "20"),
                        task(202, "卤鸭腿", "B-002", "腌制", "50", null))));
    }

    private void oneTask() {
        when(taskService.list(eq(F), eq(Status.IN_PROGRESS), any(), eq(USER), any(Pageable.class)))
                .thenReturn(page(List.of(task(101, "红烧肉", "B-001", "切块", "100", "20"))));
    }

    private static PageResponse<WorkProcessTaskDTO> page(List<WorkProcessTaskDTO> tasks) {
        return PageResponse.of(tasks, 1, 50, (long) tasks.size());
    }

    private static WorkProcessTaskDTO task(long id, String product, String batch, String process,
                                           String planned, String reported) {
        return WorkProcessTaskDTO.builder().id(id).factoryId(F).status(Status.IN_PROGRESS)
                .productTypeName(product).batchNumber(batch).processName(process)
                .plannedQuantity(new BigDecimal(planned)).plannedUnit("kg")
                .actualQuantity(reported == null ? null : new BigDecimal(reported)).build();
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
