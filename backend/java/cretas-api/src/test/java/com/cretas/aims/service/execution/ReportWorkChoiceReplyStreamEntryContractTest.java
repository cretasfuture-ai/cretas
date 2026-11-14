package com.cretas.aims.service.execution;

import com.cretas.aims.ai.capability.FactoryCapabilityPackRegistry;
import com.cretas.aims.ai.capability.FactoryCapabilityPackRoutingPolicy;
import com.cretas.aims.ai.capability.FactoryCapabilityPackSelector;
import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.impl.material.MaterialBatchUseTool;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.config.IntentSlotConfiguration;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.cache.SemanticCacheHit;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.conversation.ConversationSession;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.repository.conversation.ConversationSessionRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.AnalysisRouterService;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.SemanticCacheService;
import com.cretas.aims.service.impl.ConversationServiceImpl;
import com.cretas.aims.service.impl.SlotFillingServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * prod e2e 2026-10-06 第 2 / 3 条, 跑在 App 实际走的流式入口上 ({@code executeStreamAsync})。
 *
 * <p>真的: SseStreamingService、SlotFillingServiceImpl (建会话)、ConversationServiceImpl (续轮)、
 * attachPreviewParams。桩: 识别 (aiIntentService)、工具执行 (toolDispatchService)、会话表 (内存 map)、LLM。
 *
 * <ul>
 *   <li>「做完了，产量0」问了任务号 → 「1827」接着填, 继续完成工序, 产量 0 不丢; 不重新识别、不问模型;</li>
 *   <li>对照: 会话开着时说一句新话 → 照常识别, 会话关掉 (之后的「1827」也不再被它截走);</li>
 *   <li>对照: 请求没点名会话 / 点了按钮 → 不续;</li>
 *   <li>「开始1827号工序任务」(写意图) → 先用显式路径同一个抽参方法抽到 1827, 不追问, 并随确认卡带去预览;
 *       对照: 抽不到才追问; 读意图不提前抽。</li>
 * </ul>
 */
/**
 * 流式入口 (App 实际走的 {@code executeStreamAsync} → streamSlotContinuation) 上「选哪一道工序」的续轮。
 * 真的: SseStreamingService、ConversationServiceImpl、ConversationTaskStateService、attachPreviewParams;
 * 桩: 识别、工具分发、会话表 (内存)。第 1 轮 (列选项) 的产出 = 会话 + 选项, 见 WorkProcessReportChoiceContractTest。
 */
class ReportWorkChoiceReplyStreamEntryContractTest {

    private static final String F = "F006";
    private static final long USER = 1686L;
    private static final String ROLE = "operator";

    /** 第 1 轮「我要报工，产量100」留下的状态: 待选哪一道工序 + 两个选项, 产量 100 已记。 */
    private static String openChoice(Harness h) {
        String sid = h.conversations.startParameterCollection(F, USER, "WORK_PROCESS_TASK_COMPLETE", "完成工序",
                List.of(com.cretas.aims.service.ConversationService.RequiredParameter.builder()
                        .name("taskId").label("工序任务编号").type("INTEGER").build()),
                List.of(), Map.of("actualQuantity", 100)).getSessionId();
        assertThat(h.conversations.attachParameterChoices(F, USER, sid, "taskId", "哪一道工序", List.of(
                com.cretas.aims.service.ConversationService.CandidateInfo.builder().intentCode("101")
                        .intentName("红烧肉 · 批次 B-001 · 切块").description("红烧肉|B-001|切块").build(),
                com.cretas.aims.service.ConversationService.CandidateInfo.builder().intentCode("202")
                        .intentName("卤鸭腿 · 批次 B-002 · 腌制").description("卤鸭腿|B-002|腌制").build()))).isTrue();
        return sid;
    }

    @ParameterizedTest(name = "任务状态开关={0}")
    @ValueSource(booleans = {true, false})
    void numberReplyPicksTheOptionAndGoesToTheWriteConfirmWithBothParameters(boolean taskStateOn) throws Exception {
        Harness h = new Harness();
        h.enableTaskState(taskStateOn);
        String sid = openChoice(h);

        IntentExecuteResponse answer = h.execute(IntentExecuteRequest.builder().userInput("2").sessionId(sid).build());

        ArgumentCaptor<IntentExecuteRequest> sent = ArgumentCaptor.forClass(IntentExecuteRequest.class);
        verify(h.toolDispatchService).executeWithTool(eq(h.completeTool), eq(F), sent.capture(), eq(h.complete),
                eq(USER), eq(ROLE), any());
        assertThat(sent.getValue().getContext()).containsEntry("taskId", "202").containsEntry("actualQuantity", "100");
        assertThat(answer.getStatus()).isEqualTo("WRITE_CONFIRM_REQUIRED");
        assertThat(answer.getIntentCode()).isEqualTo("WORK_PROCESS_TASK_COMPLETE");
        @SuppressWarnings("unchecked")
        Map<String, Object> previewParams = (Map<String, Object>) answer.getMetadata().get("previewParams");
        assertThat(previewParams).containsOnly(Map.entry("taskId", "202"), Map.entry("actualQuantity", "100"));
        assertThat(h.sessions.get(sid).getStatus()).isEqualTo(ConversationSession.SessionStatus.COMPLETED);
        verify(h.aiIntentService, never()).recognizeIntentWithConfidence(
                eq("2"), anyString(), anyInt(), any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest(name = "任务状态开关={0}: 「{1}」")
    @CsvSource({"true,不报了", "false,不报了", "true,红烧肉还剩多少", "false,红烧肉还剩多少", "true,算了", "false,算了"})
    void anythingElseIsRecognisedAsANewSentenceAndNeverPicksAnOption(boolean taskStateOn, String reply) throws Exception {
        Harness h = new Harness();
        h.enableTaskState(taskStateOn);
        h.recognizes(reply, h.query);
        h.recognizesWithTaskState(reply, h.query);
        String sid = openChoice(h);

        IntentExecuteResponse result = h.execute(IntentExecuteRequest.builder().userInput(reply).sessionId(sid).build());

        // 阳性对照: 这句话被当新话识别了 (识别结果是查询意图)
        assertThat(result.getIntentCode()).isEqualTo("PROCESS_TASK_QUERY");
        // 阴性: 没有被旧选项带去执行完成工序, 会话里没有 taskId, 回复里没有「没对上」
        verify(h.toolDispatchService, never()).executeWithTool(eq(h.completeTool), any(), any(), any(), any(), any(), any());
        assertThat(h.sessions.get(sid).getCollectedParameters()).doesNotContainKey("taskId");
        assertThat(h.sessions.get(sid).getStatus()).isNotEqualTo(ConversationSession.SessionStatus.COMPLETED);
        assertThat(String.valueOf(result.getMessage())).doesNotContain("没对上");
    }

    private static final class Harness {
        final AIIntentService aiIntentService = mock(AIIntentService.class);
        final ToolRegistry toolRegistry = mock(ToolRegistry.class);
        final ToolDispatchService toolDispatchService = mock(ToolDispatchService.class);
        final DashScopeClient conversationLlm = mock(DashScopeClient.class);
        final Map<String, ConversationSession> sessions = new LinkedHashMap<>();
        final SseStreamingService service;
        final SseEmitter emitter = mock(SseEmitter.class);

        final ToolExecutor completeTool = tool("work_process_task_complete", ToolExecutor.AccessMode.WRITE,
                Map.of("taskId", Map.of("type", "integer", "description", "工序任务ID (Long)"),
                        "actualQuantity", Map.of("type", "number", "description", "实际产量"),
                        "notes", Map.of("type", "string")),
                List.of("taskId", "actualQuantity"));
        final ToolExecutor startTool = tool("work_process_task_start", ToolExecutor.AccessMode.WRITE,
                Map.of("taskId", Map.of("type", "integer", "description", "工序任务ID (Long)")),
                List.of("taskId"));
        final ToolExecutor queryTool = tool("process_task_query", ToolExecutor.AccessMode.READ,
                Map.of("date", Map.of("type", "string")), List.of());
        final AIIntentConfig complete = intent("WORK_PROCESS_TASK_COMPLETE", "完成工序", completeTool);
        final AIIntentConfig start = intent("WORK_PROCESS_TASK_START", "开始工序", startTool);
        final AIIntentConfig query = intent("PROCESS_TASK_QUERY", "工序任务查询", queryTool);
        final ToolExecutor batchTool = tool("production_batch_create", ToolExecutor.AccessMode.WRITE,
                Map.of("productName", Map.of("type", "string", "description", "产品名称"),
                        "quantity", Map.of("type", "number", "description", "数量")),
                List.of("productName", "quantity"));
        final AIIntentConfig batchCreate = intent("PRODUCTION_BATCH_CREATE", "创建生产批次", batchTool);
        /** 流式这一侧的「领用原料」(桩); 预览那一侧用真实的 MaterialBatchUseTool, 见 PreviewLeg。 */
        final ToolExecutor useTool = tool("material_batch_use", ToolExecutor.AccessMode.WRITE,
                Map.of("batchId", Map.of("type", "string", "description", "批次"),
                        "quantity", Map.of("type", "number", "description", "数量"),
                        "productionPlanId", Map.of("type", "string")),
                List.of("batchId", "quantity"));
        final AIIntentConfig use = intent("MATERIAL_BATCH_USE", "领用原料", useTool);
        final ConversationServiceImpl conversations;
        /** 会话表 (内存), 任务状态服务与会话服务共用同一份。 */
        ConversationSessionRepository repo;
        /** 知识库 (桩): 默认什么短语都不认; 要模拟「这是一条已知命令」就 stub matchPhrase (prod 的短语表里有「上班打卡」)。 */
        final IntentKnowledgeBase knowledgeBase = mock(IntentKnowledgeBase.class);
        /** 本厂的一线开放记录 (内存), 由真 FrontlineAiWriteGrantService 读。 */
        final List<com.cretas.aims.entity.config.AiFrontlineIntentGrant> grants = new java.util.ArrayList<>();

        Harness() throws Exception {
            FactoryRepository factoryRepository = mock(FactoryRepository.class);
            Factory factory = new Factory();
            factory.setId(F);
            factory.setType(FactoryType.FACTORY);
            factory.setIsActive(true);
            when(factoryRepository.findById(anyString())).thenReturn(Optional.of(factory));
            SemanticCacheService semanticCache = mock(SemanticCacheService.class);
            when(semanticCache.queryCache(anyString(), anyString())).thenReturn(SemanticCacheHit.miss(0L));

            repo = inMemorySessions();
            conversations = new ConversationServiceImpl(
                    repo, mock(AIIntentConfigRepository.class), null, conversationLlm);
            ReflectionTestUtils.setField(conversations, "maxRounds", 5);
            ReflectionTestUtils.setField(conversations, "timeoutMinutes", 10);
            ReflectionTestUtils.setField(conversations, "confidenceThreshold", 0.7);
            when(conversationLlm.isAvailable()).thenReturn(true);

            IntentSlotConfiguration slotConfiguration = new IntentSlotConfiguration();
            slotConfiguration.init();
            SlotFillingServiceImpl slotFilling = new SlotFillingServiceImpl(slotConfiguration, toolRegistry,
                    conversations, mock(ParameterExtractionLearningService.class));

            service = spy(new SseStreamingService(
                    aiIntentService, semanticCache, knowledgeBase,
                    mock(AnalysisRouterService.class), new ObjectMapper(), toolRegistry, toolDispatchService,
                    mock(DynamicToolSelectionService.class), mock(DashScopeClient.class), mock(DashScopeConfig.class)));
            ReflectionTestUtils.setField(service, "factoryCapabilityPackRoutingPolicy",
                    new FactoryCapabilityPackRoutingPolicy(factoryRepository,
                            new FactoryCapabilityPackSelector(new FactoryCapabilityPackRegistry()), false));
            ReflectionTestUtils.setField(service, "writeGuardService", new WriteGuardService());
            ReflectionTestUtils.setField(service, "businessTypeGate", mock(BusinessTypeGate.class));
            ReflectionTestUtils.setField(service, "slotFillingService", slotFilling);
            ReflectionTestUtils.setField(service, "conversationService", conversations);
            com.cretas.aims.repository.config.AiFrontlineIntentGrantRepository grantRepo =
                    mock(com.cretas.aims.repository.config.AiFrontlineIntentGrantRepository.class);
            when(grantRepo.findByFactoryIdAndRevokedAtIsNull(anyString())).thenAnswer(inv -> List.copyOf(grants));
            ReflectionTestUtils.setField(service, "frontlineWriteGrants",
                    new com.cretas.aims.service.intent.FrontlineAiWriteGrantService(grantRepo, new WriteGuardService(),
                            mock(com.cretas.aims.service.intent.IntentConfigManagementService.class), toolRegistry));
            doNothing().when(service).sendSseEvent(any(), anyString(), any());

            for (AIIntentConfig intent : List.of(complete, start, query, batchCreate, use)) {
                when(aiIntentService.getIntentByCode(F, intent.getIntentCode())).thenReturn(Optional.of(intent));
                when(aiIntentService.hasPermission(intent.getIntentCode(), ROLE)).thenReturn(true);
            }
            stubDispatch(completeTool, complete, "WRITE_CONFIRM_REQUIRED");
            stubDispatch(startTool, start, "WRITE_CONFIRM_REQUIRED");
            stubDispatch(queryTool, query, "COMPLETED");
            stubDispatch(batchTool, batchCreate, "WRITE_CONFIRM_REQUIRED");
            stubDispatch(useTool, use, "WRITE_CONFIRM_REQUIRED");
            FactoryAnalysisTestMetadata.bindNamedFactories(service);
        }

        /** 会话表: 内存 map, save 时像 JPA 一样补 sessionId / lastActiveAt。 */
        private ConversationSessionRepository inMemorySessions() {
            ConversationSessionRepository repo = mock(ConversationSessionRepository.class);
            when(repo.save(any(ConversationSession.class))).thenAnswer(inv -> {
                ConversationSession s = inv.getArgument(0);
                if (s.getSessionId() == null) {
                    s.setSessionId(UUID.randomUUID().toString());
                }
                if (s.getLastActiveAt() == null) {
                    s.setLastActiveAt(LocalDateTime.now());
                }
                sessions.put(s.getSessionId(), s);
                return s;
            });
            when(repo.findSuspendedByFactoryAndUser(anyString(), anyLong()))
                    .thenAnswer(inv -> sessions.values().stream()
                            .filter(s -> s.getStatus() == ConversationSession.SessionStatus.SUSPENDED
                                    && s.getFactoryId().equals(inv.getArgument(0))
                                    && s.getUserId().equals(inv.getArgument(1)))
                            .toList());
            when(repo.findBySessionIdAndFactoryIdAndUserId(anyString(), anyString(), anyLong()))
                    .thenAnswer(inv -> Optional.ofNullable(sessions.get((String) inv.getArgument(0)))
                            .filter(s -> s.getFactoryId().equals(inv.getArgument(1))
                                    && s.getUserId().equals(inv.getArgument(2))));
            return repo;
        }

        private void stubDispatch(ToolExecutor tool, AIIntentConfig intent, String status) {
            when(toolRegistry.getExecutor(tool.getToolName())).thenReturn(Optional.of(tool));
            when(toolDispatchService.executeWithTool(eq(tool), eq(F), any(), eq(intent), eq(USER), eq(ROLE), any()))
                    .thenAnswer(inv -> IntentExecuteResponse.builder().intentRecognized(true)
                            .intentCode(intent.getIntentCode()).status(status).message("该操作会写入/修改数据，执行前需要确认。")
                            .build());
        }

        /** 打开任务状态 (挂起 / 恢复 / 摘要), 用与会话服务同一张会话表。 */
        ConversationTaskStateService enableTaskState(boolean enabled) {
            ConversationTaskStateService taskState = new ConversationTaskStateService(repo);
            taskState.setEnabled(enabled);
            ReflectionTestUtils.setField(service, "taskStateService", taskState);
            return taskState;
        }

        /** 带任务状态摘要的识别 (10 参版) 也按同一个结果回。 */
        void recognizesWithTaskState(String input, AIIntentConfig intent) {
            when(aiIntentService.recognizeIntentWithConfidence(
                    eq(input), eq(F), eq(3), eq(USER), eq(ROLE), any(), any(), any(), any(), anyMap()))
                    .thenReturn(IntentMatchResult.builder().userInput(input).bestMatch(intent).confidence(0.95)
                            .matchMethod(IntentMatchResult.MatchMethod.LLM).isStrongSignal(true)
                            .requiresConfirmation(false)
                            .questionType(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND).build());
        }

        void recognizes(String input, AIIntentConfig intent) {
            when(aiIntentService.recognizeIntentWithConfidence(
                    eq(input), eq(F), eq(3), eq(USER), eq(ROLE), any(), any(), any(), any()))
                    .thenReturn(IntentMatchResult.builder().userInput(input).bestMatch(intent).confidence(0.95)
                            .matchMethod(IntentMatchResult.MatchMethod.LLM).isStrongSignal(true)
                            .requiresConfirmation(false)
                            .questionType(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND).build());
        }

        void extracts(String input, ToolExecutor tool, Map<String, Object> extracted) {
            when(toolDispatchService.extractParametersWithLLM(eq(F), eq(input), eq(tool), anyMap()))
                    .thenAnswer(inv -> new HashMap<>(extracted));
        }

        IntentExecuteResponse execute(IntentExecuteRequest request) throws Exception {
            return executeAs(request, ROLE);
        }

        IntentExecuteResponse executeAs(String userInput, String sessionId, String role) throws Exception {
            return executeAs(IntentExecuteRequest.builder().userInput(userInput).sessionId(sessionId).build(), role);
        }

        IntentExecuteResponse executeAs(IntentExecuteRequest request, String role) throws Exception {
            AuthenticatedActor actor = new AuthenticatedActor(
                    AuthenticatedActorType.FACTORY_USER, USER, F, role);
            try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(actor)) {
                ReflectionTestUtils.invokeMethod(service, "executeStreamAsync", emitter, F, request, USER, role);
            }
            ArgumentCaptor<Object> result = ArgumentCaptor.forClass(Object.class);
            verify(service, org.mockito.Mockito.atLeastOnce()).sendSseEvent(eq(emitter), eq("result"), result.capture());
            List<Object> all = result.getAllValues();
            return (IntentExecuteResponse) all.get(all.size() - 1);
        }

        private static ToolExecutor tool(String name, ToolExecutor.AccessMode mode,
                                         Map<String, Object> properties, List<String> required) {
            ToolExecutor tool = mock(ToolExecutor.class);
            when(tool.getToolName()).thenReturn(name);
            when(tool.getAccessMode()).thenReturn(mode);
            when(tool.getParametersSchema()).thenReturn(
                    Map.of("type", "object", "properties", properties, "required", required));
            return tool;
        }

        private static AIIntentConfig intent(String code, String name, ToolExecutor tool) {
            return AIIntentConfig.builder().intentCode(code).intentName(name).intentCategory("PRODUCTION")
                    .businessType("FACTORY").sensitivityLevel("LOW").toolName(tool.getToolName()).build();
        }
    }
}
