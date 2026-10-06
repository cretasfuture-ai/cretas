package com.cretas.aims.service.execution;

import com.cretas.aims.ai.capability.FactoryCapabilityPackRegistry;
import com.cretas.aims.ai.capability.FactoryCapabilityPackRoutingPolicy;
import com.cretas.aims.ai.capability.FactoryCapabilityPackSelector;
import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
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
class SseStreamingServiceSlotContinuationTest {

    private static final String F = "F006";
    private static final long USER = 1686L;
    private static final String ROLE = "operator";

    @Test
    void slotAnswerFillsTheOpenSessionAndContinuesTheSameIntent() throws Exception {
        Harness h = new Harness();
        h.recognizes("做完了，产量0", h.complete);
        h.extracts("做完了，产量0", h.completeTool, Map.of("actualQuantity", 0));

        IntentExecuteResponse ask = h.execute(IntentExecuteRequest.builder().userInput("做完了，产量0").build());

        assertThat(ask.getStatus()).isEqualTo("NEED_MORE_INFO");
        assertThat(ask.getSessionId()).as("缺参追问的结果要带会话 id, App 才能带回来").isNotBlank();
        assertThat(h.sessions.get(ask.getSessionId()).getCollectedParameters())
                .as("首句抽到的产量记进会话").containsEntry("actualQuantity", "0");

        IntentExecuteResponse answer = h.execute(IntentExecuteRequest.builder().userInput("1827")
                .sessionId(ask.getSessionId()).build());

        // 继续的是同一个意图, 参数是 会话里的产量 + 这一句的任务号
        ArgumentCaptor<IntentExecuteRequest> sent = ArgumentCaptor.forClass(IntentExecuteRequest.class);
        verify(h.toolDispatchService).executeWithTool(eq(h.completeTool), eq(F), sent.capture(), eq(h.complete),
                eq(USER), eq(ROLE), any());
        assertThat(sent.getValue().getContext()).containsEntry("taskId", "1827").containsEntry("actualQuantity", "0");
        assertThat(answer.getStatus()).isEqualTo("WRITE_CONFIRM_REQUIRED");
        assertThat(answer.getIntentCode()).isEqualTo("WORK_PROCESS_TASK_COMPLETE");
        @SuppressWarnings("unchecked")
        Map<String, Object> previewParams = (Map<String, Object>) answer.getMetadata().get("previewParams");
        assertThat(previewParams).containsOnly(Map.entry("taskId", "1827"), Map.entry("actualQuantity", "0"));
        // 「1827」没有被当一句新话识别, 也没有交给模型去抽 (数就在原话里)
        verify(h.aiIntentService, never()).recognizeIntentWithConfidence(
                eq("1827"), anyString(), anyInt(), any(), any(), any(), any(), any(), any());
        verify(h.conversationLlm, never()).chat(anyString(), anyString());
        assertThat(h.sessions.get(ask.getSessionId()).getStatus())
                .isEqualTo(ConversationSession.SessionStatus.COMPLETED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"上班打卡", "查一下1830号任务"})
    void aNewSentenceWhileTheSessionIsOpenIsRecognisedNormallyAndClosesTheSession(String newSentence)
            throws Exception {
        Harness h = new Harness();
        h.recognizes("做完了，产量0", h.complete);
        h.extracts("做完了，产量0", h.completeTool, Map.of("actualQuantity", 0));
        h.recognizes(newSentence, h.query);
        String sessionId = h.execute(IntentExecuteRequest.builder().userInput("做完了，产量0").build()).getSessionId();

        IntentExecuteResponse result = h.execute(IntentExecuteRequest.builder().userInput(newSentence)
                .sessionId(sessionId).build());

        assertThat(result.getIntentCode()).isEqualTo("PROCESS_TASK_QUERY");
        verify(h.aiIntentService).recognizeIntentWithConfidence(
                eq(newSentence), eq(F), eq(3), eq(USER), eq(ROLE), any(), any(), any(), any());
        assertThat(h.sessions.get(sessionId).getStatus()).isEqualTo(ConversationSession.SessionStatus.CANCELLED);

        // 会话已经关了: 再来一个「1827」不会被这个旧问题截走
        h.recognizes("1827", h.query);
        h.execute(IntentExecuteRequest.builder().userInput("1827").sessionId(sessionId).build());
        verify(h.aiIntentService).recognizeIntentWithConfidence(
                eq("1827"), eq(F), eq(3), eq(USER), eq(ROLE), any(), any(), any(), any());
        verify(h.toolDispatchService, never()).executeWithTool(eq(h.completeTool), any(), any(), any(), any(),
                any(), any());
    }

    @Test
    void aSessionTheRequestDoesNotNameIsNotContinued() throws Exception {
        Harness h = new Harness();
        h.recognizes("做完了，产量0", h.complete);
        h.extracts("做完了，产量0", h.completeTool, Map.of("actualQuantity", 0));
        h.recognizes("1827", h.query);
        String sessionId = h.execute(IntentExecuteRequest.builder().userInput("做完了，产量0").build()).getSessionId();

        h.execute(IntentExecuteRequest.builder().userInput("1827").build());

        verify(h.aiIntentService).recognizeIntentWithConfidence(
                eq("1827"), eq(F), eq(3), eq(USER), eq(ROLE), any(), any(), any(), any());
        assertThat(h.sessions.get(sessionId).getStatus()).isEqualTo(ConversationSession.SessionStatus.ACTIVE);
    }

    @Test
    void anExplicitButtonPickClosesTheUnansweredQuestion() throws Exception {
        Harness h = new Harness();
        h.recognizes("做完了，产量0", h.complete);
        h.extracts("做完了，产量0", h.completeTool, Map.of("actualQuantity", 0));
        String sessionId = h.execute(IntentExecuteRequest.builder().userInput("做完了，产量0").build()).getSessionId();

        h.execute(IntentExecuteRequest.builder().userInput("今天我的工序任务").intentCode("PROCESS_TASK_QUERY")
                .sessionId(sessionId).build());

        assertThat(h.sessions.get(sessionId).getStatus()).isEqualTo(ConversationSession.SessionStatus.CANCELLED);
    }

    @Test
    void anExpiredSessionIsNotContinued() throws Exception {
        Harness h = new Harness();
        h.recognizes("做完了，产量0", h.complete);
        h.extracts("做完了，产量0", h.completeTool, Map.of("actualQuantity", 0));
        h.recognizes("1827", h.query);
        String sessionId = h.execute(IntentExecuteRequest.builder().userInput("做完了，产量0").build()).getSessionId();
        h.sessions.get(sessionId).setLastActiveAt(LocalDateTime.now().minusMinutes(11));

        h.execute(IntentExecuteRequest.builder().userInput("1827").sessionId(sessionId).build());

        verify(h.aiIntentService).recognizeIntentWithConfidence(
                eq("1827"), eq(F), eq(3), eq(USER), eq(ROLE), any(), any(), any(), any());
    }

    @Test
    void writeIntentIsExtractedWithTheExplicitPathMethodBeforeAnythingIsAsked() throws Exception {
        Harness h = new Harness();
        h.recognizes("开始1827号工序任务", h.start);
        h.extracts("开始1827号工序任务", h.startTool, Map.of("taskId", 1827));

        IntentExecuteResponse result = h.execute(IntentExecuteRequest.builder().userInput("开始1827号工序任务")
                .context(new HashMap<>(Map.of("recentTurns", List.of(), "factoryId", "EVIL"))).build());

        assertThat(result.getStatus()).as("抽到了就不追问").isEqualTo("WRITE_CONFIRM_REQUIRED");
        assertThat(h.sessions).as("没有建缺参会话").isEmpty();
        verify(h.toolDispatchService).extractParametersWithLLM(eq("开始1827号工序任务"), eq(h.startTool), anyMap());
        ArgumentCaptor<IntentExecuteRequest> sent = ArgumentCaptor.forClass(IntentExecuteRequest.class);
        verify(h.toolDispatchService).executeWithTool(eq(h.startTool), eq(F), sent.capture(), eq(h.start),
                eq(USER), eq(ROLE), any());
        assertThat(sent.getValue().getContext()).containsEntry("taskId", 1827);
        @SuppressWarnings("unchecked")
        Map<String, Object> previewParams = (Map<String, Object>) result.getMetadata().get("previewParams");
        assertThat(previewParams).as("只带工具声明的参数, 主体键 / 对话上下文不出门")
                .containsOnly(Map.entry("taskId", 1827));
    }

    @Test
    void withoutExtractionTheSameSentenceIsAskedForTheTaskNumber() throws Exception {
        // 对照: 证明上一条的「不追问」来自抽参, 不是这条路本来就不问
        Harness h = new Harness();
        h.recognizes("开始1827号工序任务", h.start);
        h.extracts("开始1827号工序任务", h.startTool, Map.of());

        IntentExecuteResponse result = h.execute(IntentExecuteRequest.builder().userInput("开始1827号工序任务").build());

        assertThat(result.getStatus()).isEqualTo("NEED_MORE_INFO");
        assertThat(h.sessions).hasSize(1);
        verify(h.toolDispatchService, never()).executeWithTool(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void readIntentsAreNotPreExtractedOnTheStream() throws Exception {
        // 对照: 读意图交给 executeWithTool 自己 (先学习规则后模型), 流式不提前抽
        Harness h = new Harness();
        h.recognizes("今天我的工序任务", h.query);

        h.execute(IntentExecuteRequest.builder().userInput("今天我的工序任务").build());

        verify(h.toolDispatchService, never()).extractParametersWithLLM(anyString(), any(), anyMap());
        verify(h.toolDispatchService, times(1)).executeWithTool(eq(h.queryTool), eq(F), any(), eq(h.query),
                eq(USER), eq(ROLE), any());
    }

    // ------------------------------------------------------------------

    // ==================== 入场就问一线开放名单 (2026-10-06 真机 #12) ====================

    @Test
    void unopenedWriteForAFrontlineRoleIsRefusedAtAdmissionBeforeAnyParameterQuestion() throws Exception {
        Harness h = new Harness();
        h.recognizes("帮我创建一个生产批次", h.batchCreate);

        IntentExecuteResponse r = h.execute(IntentExecuteRequest.builder().userInput("帮我创建一个生产批次").build());

        assertThat(r.getStatus()).isEqualTo("NO_PERMISSION");
        assertThat(r.getMessage()).contains("创建生产批次").contains("还没有对操作员开放");
        assertThat(h.sessions).as("不追问参数 —— 没有建缺参会话").isEmpty();
        verify(h.toolDispatchService, never()).extractParametersWithLLM(anyString(), any(), anyMap());
        verify(h.toolDispatchService, never()).executeWithTool(eq(h.batchTool), any(), any(), any(), any(), any(), any());
    }

    @Test
    void sameWriteProceedsOnceTheFactoryHasOpenedItForTheRole() throws Exception {
        Harness h = new Harness();
        h.grants.add(com.cretas.aims.entity.config.AiFrontlineIntentGrant.builder()
                .factoryId(F).roleCode(ROLE).intentCode("PRODUCTION_BATCH_CREATE").grantedAt(LocalDateTime.now()).build());
        h.recognizes("帮我创建一个生产批次", h.batchCreate);

        IntentExecuteResponse r = h.execute(IntentExecuteRequest.builder().userInput("帮我创建一个生产批次").build());

        assertThat(r.getStatus()).as("开放后照常走: 缺参就问, 不缺就到写确认").isIn("NEED_MORE_INFO", "WRITE_CONFIRM_REQUIRED");
        verify(h.toolDispatchService).extractParametersWithLLM(anyString(), eq(h.batchTool), anyMap());
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

            ConversationServiceImpl conversations = new ConversationServiceImpl(
                    inMemorySessions(), mock(AIIntentConfigRepository.class), null, conversationLlm);
            ReflectionTestUtils.setField(conversations, "maxRounds", 5);
            ReflectionTestUtils.setField(conversations, "timeoutMinutes", 10);
            ReflectionTestUtils.setField(conversations, "confidenceThreshold", 0.7);
            when(conversationLlm.isAvailable()).thenReturn(true);

            IntentSlotConfiguration slotConfiguration = new IntentSlotConfiguration();
            slotConfiguration.init();
            SlotFillingServiceImpl slotFilling = new SlotFillingServiceImpl(slotConfiguration, toolRegistry,
                    conversations, mock(ParameterExtractionLearningService.class));

            service = spy(new SseStreamingService(
                    aiIntentService, semanticCache, mock(IntentKnowledgeBase.class),
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

            for (AIIntentConfig intent : List.of(complete, start, query, batchCreate)) {
                when(aiIntentService.getIntentByCode(F, intent.getIntentCode())).thenReturn(Optional.of(intent));
                when(aiIntentService.hasPermission(intent.getIntentCode(), ROLE)).thenReturn(true);
            }
            stubDispatch(completeTool, complete, "WRITE_CONFIRM_REQUIRED");
            stubDispatch(startTool, start, "WRITE_CONFIRM_REQUIRED");
            stubDispatch(queryTool, query, "COMPLETED");
            stubDispatch(batchTool, batchCreate, "WRITE_CONFIRM_REQUIRED");
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

        void recognizes(String input, AIIntentConfig intent) {
            when(aiIntentService.recognizeIntentWithConfidence(
                    eq(input), eq(F), eq(3), eq(USER), eq(ROLE), any(), any(), any(), any()))
                    .thenReturn(IntentMatchResult.builder().userInput(input).bestMatch(intent).confidence(0.95)
                            .matchMethod(IntentMatchResult.MatchMethod.LLM).isStrongSignal(true)
                            .requiresConfirmation(false)
                            .questionType(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND).build());
        }

        void extracts(String input, ToolExecutor tool, Map<String, Object> extracted) {
            when(toolDispatchService.extractParametersWithLLM(eq(input), eq(tool), anyMap()))
                    .thenAnswer(inv -> new HashMap<>(extracted));
        }

        IntentExecuteResponse execute(IntentExecuteRequest request) throws Exception {
            ReflectionTestUtils.invokeMethod(service, "executeStreamAsync", emitter, F, request, USER, ROLE);
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
