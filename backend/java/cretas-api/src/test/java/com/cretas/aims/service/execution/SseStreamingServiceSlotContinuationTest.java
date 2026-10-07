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

    /**
     * prod 2026-10-06: 仓库员点「查库存」每次「操作执行失败」—— 流在 intent-sse 工作线程上跑, 没有 Servlet 请求,
     * SecurityUtils.currentUserId() 读不到人, 库存服务就抛 "Authenticated inventory context is required"。
     * 跑在真实入口 executeStream (直接调 executeStreamAsync 恰好绕过了线程切换)。
     * (#944 先用 SecurityContext 传递, 没生效 —— 因为身份读的不是 SecurityContext; 这条断言读的就是库存服务读的那个值。)
     */
    @Test
    void streamWorkerThreadResolvesTheCallerAsCurrentUserAndNothingLeaksToTheNextRequest() throws Exception {
        Harness h = new Harness();
        java.util.concurrent.atomic.AtomicReference<Long> seen = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> workerThread = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        when(h.aiIntentService.recognizeIntentWithConfidence(
                anyString(), eq(F), eq(3), eq(USER), eq(ROLE), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    seen.set(com.cretas.aims.utils.SecurityUtils.currentUserId());
                    workerThread.set(Thread.currentThread().getName());
                    done.countDown();
                    return IntentMatchResult.builder().userInput("x").confidence(0.0).build();
                });

        h.service.executeStream(F, IntentExecuteRequest.builder().userInput("查询原材料库存").build(), USER, ROLE);

        assertThat(done.await(10, java.util.concurrent.TimeUnit.SECONDS)).as("工作线程真的跑到了识别 (必发生的读数)").isTrue();
        assertThat(workerThread.get()).as("确实是切到了工作线程").isEqualTo("intent-sse");
        assertThat(seen.get()).as("工作线程上 SecurityUtils.currentUserId() = 调用者").isEqualTo(USER);
        // 线程池复用线程: 做完要还原, 下一个任务不能继承上一个人
        java.util.concurrent.atomic.AtomicReference<Long> next = new java.util.concurrent.atomic.AtomicReference<>(-1L);
        java.util.concurrent.CountDownLatch done2 = new java.util.concurrent.CountDownLatch(1);
        ((java.util.concurrent.ExecutorService) ReflectionTestUtils.getField(h.service, "sseExecutor")).execute(() -> {
            next.set(com.cretas.aims.utils.SecurityUtils.currentUserId());
            done2.countDown();
        });
        assertThat(done2.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(next.get()).as("复用线程时没有残留上一个人").isNull();
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

    // ==================== 预览发出的追问也有人接答案 (2026-10-07 真机: 领用2015胸肉0.1公斤) ====================

    private static final String BATCH_NO = "MT-20260829-9687";

    /**
     * 整条链路, 每一段都走真入口: 预览 (真 ToolDispatchService + 真 MaterialBatchUseTool) 问「要领用哪个批次？」→
     * 答案走流式入口 (executeStreamAsync) → 带着 batchId 和 quantity=0.1 回到同一个意图 → 再预览出确认卡。
     */
    @Test
    void thePreviewQuestionOpensASessionAndTheBatchAnswerContinuesWithTheQuantity() throws Exception {
        Harness h = new Harness();
        PreviewLeg preview = new PreviewLeg(h);

        // 1) 预览: 只说了原料名 → 追问。必发生的读数: 追问真的发出来了 (后面所有「没有 X」都靠它有意义)
        IntentExecuteResponse asked = preview.preview("领用2015胸肉0.1公斤",
                Map.of("batchId", "2015胸肉", "quantity", 0.1));
        assertThat(asked.getStatus()).isEqualTo("NEED_MORE_INFO");
        assertThat(asked.getClarificationQuestions()).singleElement().asString()
                .startsWith("要领用哪个批次？").contains(BATCH_NO);
        assertThat(asked.getSessionId()).as("预览追问要带会话 id, App 才能把答案带回来").isNotBlank();
        ConversationSession opened = h.sessions.get(asked.getSessionId());
        assertThat(opened.getKnownIntentCode()).isEqualTo("MATERIAL_BATCH_USE");
        assertThat(opened.getRequiredParameters()).extracting(ConversationSession.RequiredParameter::getName)
                .containsExactly("batchId");
        assertThat(opened.getCollectedParameters())
                .as("已收集 = 这一句里的数量; 被追问的槽不带旧值「2015胸肉」")
                .containsOnly(Map.entry("quantity", "0.1"));

        // 2) 答案: 批次号, 走流式入口
        IntentExecuteResponse answered = h.execute(IntentExecuteRequest.builder().userInput(BATCH_NO)
                .sessionId(asked.getSessionId()).build());

        ArgumentCaptor<IntentExecuteRequest> sent = ArgumentCaptor.forClass(IntentExecuteRequest.class);
        verify(h.toolDispatchService).executeWithTool(eq(h.useTool), eq(F), sent.capture(), eq(h.use),
                eq(USER), eq(ROLE), any());
        assertThat(sent.getValue().getContext()).containsEntry("batchId", BATCH_NO).containsEntry("quantity", "0.1");
        assertThat(answered.getStatus()).isEqualTo("WRITE_CONFIRM_REQUIRED");
        assertThat(answered.getIntentCode()).isEqualTo("MATERIAL_BATCH_USE");
        @SuppressWarnings("unchecked")
        Map<String, Object> previewParams = (Map<String, Object>) answered.getMetadata().get("previewParams");
        assertThat(previewParams).containsOnly(Map.entry("batchId", BATCH_NO), Map.entry("quantity", "0.1"));
        // 答案没有被当一句新话识别 (原来的症状), 也没有交给模型
        verify(h.aiIntentService, never()).recognizeIntentWithConfidence(
                eq(BATCH_NO), anyString(), anyInt(), any(), any(), any(), any(), any(), any());
        verify(h.conversationLlm, never()).chat(anyString(), anyString());
        assertThat(h.sessions.get(asked.getSessionId()).getStatus()).isEqualTo(ConversationSession.SessionStatus.COMPLETED);

        // 3) App 拿 previewParams 去请求预览 → 出确认卡 (同一个真工具), 而不是再追问
        IntentExecuteResponse card = preview.preview(BATCH_NO, previewParams);
        assertThat(card.getStatus()).isEqualTo("PREVIEW");
        assertThat(card.getConfirmableAction()).isNotNull();
        assertThat(card.getConfirmableAction().getNewValues()).containsEntry("本次领用", "0.1 kg");
    }

    @Test
    void aBatchNumberLongerThanTheShortAnswerLimitIsStillTheAnswer() throws Exception {
        // MT-20260829-9687 恰好 16 个字符 (= SlotAnswerRule.MAX_ANSWER_CHARS), 差一个字就会被当新话
        String longNumber = "MT-20260829-9687-02";
        assertThat(longNumber.length()).isGreaterThan(SlotAnswerRule.MAX_ANSWER_CHARS);
        Harness h = new Harness();
        PreviewLeg preview = new PreviewLeg(h);
        IntentExecuteResponse asked = preview.preview("领用2015胸肉0.1公斤", Map.of("batchId", "2015胸肉", "quantity", 0.1));
        assertThat(asked.getSessionId()).isNotBlank();

        h.execute(IntentExecuteRequest.builder().userInput(longNumber).sessionId(asked.getSessionId()).build());

        ArgumentCaptor<IntentExecuteRequest> sent = ArgumentCaptor.forClass(IntentExecuteRequest.class);
        verify(h.toolDispatchService).executeWithTool(eq(h.useTool), eq(F), sent.capture(), eq(h.use),
                eq(USER), eq(ROLE), any());
        assertThat(sent.getValue().getContext()).containsEntry("batchId", longNumber).containsEntry("quantity", "0.1");
    }

    @Test
    void aNewSentenceWhileThePreviewQuestionIsOpenIsRecognisedAndClosesTheQuestion() throws Exception {
        Harness h = new Harness();
        PreviewLeg preview = new PreviewLeg(h);
        h.recognizes("上班打卡", h.query);
        // prod 的短语表里「上班打卡」= CLOCK_IN (IntentKnowledgeBase phraseToIntentMapping): 它是一条已知命令, 不是批次的名字
        when(h.knowledgeBase.matchPhrase("上班打卡")).thenReturn(Optional.of("CLOCK_IN"));
        String sessionId = preview.preview("领用2015胸肉0.1公斤", Map.of("batchId", "2015胸肉", "quantity", 0.1))
                .getSessionId();

        IntentExecuteResponse result = h.execute(IntentExecuteRequest.builder().userInput("上班打卡")
                .sessionId(sessionId).build());

        assertThat(result.getIntentCode()).isEqualTo("PROCESS_TASK_QUERY");   // 桩识别的结果: 走的是识别, 不是续轮
        verify(h.aiIntentService).recognizeIntentWithConfidence(
                eq("上班打卡"), eq(F), eq(3), eq(USER), eq(ROLE), any(), any(), any(), any());
        assertThat(h.sessions.get(sessionId).getStatus()).isEqualTo(ConversationSession.SessionStatus.CANCELLED);
        verify(h.toolDispatchService, never()).executeWithTool(eq(h.useTool), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aBatchNumberWithoutTheSessionIsAnOrdinarySentence() throws Exception {
        Harness h = new Harness();
        PreviewLeg preview = new PreviewLeg(h);
        h.recognizes(BATCH_NO, h.query);
        String sessionId = preview.preview("领用2015胸肉0.1公斤", Map.of("batchId", "2015胸肉", "quantity", 0.1))
                .getSessionId();

        h.execute(IntentExecuteRequest.builder().userInput(BATCH_NO).build());   // 没带会话

        verify(h.aiIntentService).recognizeIntentWithConfidence(
                eq(BATCH_NO), eq(F), eq(3), eq(USER), eq(ROLE), any(), any(), any(), any());
        verify(h.toolDispatchService, never()).executeWithTool(eq(h.useTool), any(), any(), any(), any(), any(), any());
        assertThat(h.sessions.get(sessionId).getStatus()).as("会话没被动").isEqualTo(ConversationSession.SessionStatus.ACTIVE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancelled", "expired"})
    void aCancelledOrExpiredPreviewQuestionNoLongerCapturesTheAnswer(String how) throws Exception {
        Harness h = new Harness();
        PreviewLeg preview = new PreviewLeg(h);
        h.recognizes(BATCH_NO, h.query);
        String sessionId = preview.preview("领用2015胸肉0.1公斤", Map.of("batchId", "2015胸肉", "quantity", 0.1))
                .getSessionId();
        if (how.equals("cancelled")) {
            assertThat(h.conversations.cancelConversation(F, USER, sessionId)).isTrue();
        } else {
            h.sessions.get(sessionId).setLastActiveAt(LocalDateTime.now().minusMinutes(11));
        }

        h.execute(IntentExecuteRequest.builder().userInput(BATCH_NO).sessionId(sessionId).build());

        verify(h.aiIntentService).recognizeIntentWithConfidence(
                eq(BATCH_NO), eq(F), eq(3), eq(USER), eq(ROLE), any(), any(), any(), any());
        verify(h.toolDispatchService, never()).executeWithTool(eq(h.useTool), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aPreviewQuestionThatDoesNotSayWhichSlotIsMissingOpensNoSession() throws Exception {
        // 没有 missingParameters 就不知道答案该填哪个槽: 开了会话只会截走下一句话
        Harness h = new Harness();
        ToolExecutor vague = mock(ToolExecutor.class);
        when(vague.getToolName()).thenReturn("vague_write");
        when(vague.supportsPreview()).thenReturn(true);
        when(vague.getAccessMode()).thenReturn(ToolExecutor.AccessMode.WRITE);
        when(vague.getParametersSchema()).thenReturn(Map.of("type", "object", "properties",
                Map.of("batchId", Map.of("type", "string")), "required", List.of("batchId")));
        when(vague.preview(any(), any())).thenReturn(
                "{\"success\":false,\"status\":\"NEED_MORE_INFO\",\"needMoreInfo\":true,"
                        + "\"clarificationQuestions\":[\"请再说清楚一点\"],\"message\":\"请再说清楚一点\"}");
        PreviewLeg preview = new PreviewLeg(h);

        IntentExecuteResponse asked = preview.dispatch.executeWithTool(vague, F,
                IntentExecuteRequest.builder().intentCode("MATERIAL_BATCH_USE").userInput("领用").previewOnly(true)
                        .context(new HashMap<>()).build(), preview.useIntent, USER, PreviewLeg.PREVIEW_ROLE, null);

        assertThat(asked.getStatus()).as("必发生: 追问照常发出").isEqualTo("NEED_MORE_INFO");
        assertThat(asked.getSessionId()).isNull();
        assertThat(h.sessions).isEmpty();
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

    /** 预览那一侧: 真 ToolDispatchService + 真 MaterialBatchUseTool, 桩掉批次 service / repository / token 签发。 */
    private static final class PreviewLeg {
        /** MaterialBatchUseTool.hasPermission 认的角色 (prod 上领用原料的是仓库员); 与流式侧的 operator 无关, 会话只绑 工厂 + 本人。 */
        static final String PREVIEW_ROLE = "warehouse_worker";
        final ToolDispatchService dispatch;
        final MaterialBatchUseTool tool = new MaterialBatchUseTool();
        final AIIntentConfig useIntent;

        PreviewLeg(Harness h) throws Exception {
            com.cretas.aims.service.MaterialBatchService batches = mock(com.cretas.aims.service.MaterialBatchService.class);
            com.cretas.aims.repository.MaterialBatchRepository repo =
                    mock(com.cretas.aims.repository.MaterialBatchRepository.class);
            ReflectionTestUtils.setField(tool, "materialBatchService", batches);
            ReflectionTestUtils.setField(tool, "materialBatchRepository", repo);
            com.cretas.aims.ai.tool.PreviewToolTestSupport.injectMapper(tool);
            // 本厂有「2015胸肉」的可领批次; 按批次号 / 主键都查得到
            List<com.cretas.aims.dto.material.MaterialBatchDTO> listed = new java.util.ArrayList<>();
            for (String[] b : new String[][] {{"id-9687", BATCH_NO}, {"id-9687-02", "MT-20260829-9687-02"}}) {
                com.cretas.aims.dto.material.MaterialBatchDTO dto = com.cretas.aims.dto.material.MaterialBatchDTO.builder()
                        .id(b[0]).factoryId(F).batchNumber(b[1]).materialName("2015胸肉").quantityUnit("kg")
                        .currentQuantity(new java.math.BigDecimal("12.5"))
                        .status(com.cretas.aims.entity.enums.MaterialBatchStatus.AVAILABLE).build();
                com.cretas.aims.entity.MaterialBatch entity = new com.cretas.aims.entity.MaterialBatch();
                entity.setId(b[0]);
                entity.setFactoryId(F);
                entity.setBatchNumber(b[1]);
                when(batches.getMaterialBatchById(F, b[0])).thenReturn(dto);
                when(repo.findByFactoryIdAndBatchNumber(F, b[1])).thenReturn(Optional.of(entity));
                listed.add(dto);
            }
            when(batches.getMaterialBatchList(eq(F), any(com.cretas.aims.dto.common.PageRequest.class)))
                    .thenReturn(com.cretas.aims.dto.common.PageResponse.of(listed, 1, 50, (long) listed.size()));

            ToolRegistry registry = mock(ToolRegistry.class);
            when(registry.isToolEnabledForFactory(anyString(), anyString())).thenReturn(true);
            dispatch = new ToolDispatchService(registry, new ObjectMapper(), mock(DashScopeClient.class),
                    mock(com.cretas.aims.service.calibration.ToolCallRedundancyService.class),
                    mock(com.cretas.aims.service.calibration.SelfCorrectionService.class),
                    mock(com.cretas.aims.service.calibration.CorrectionAgentService.class),
                    mock(com.cretas.aims.service.calibration.ExternalVerifierService.class),
                    mock(com.cretas.aims.service.calibration.ToolResultValidatorService.class),
                    mock(ParameterExtractionLearningService.class));
            com.cretas.aims.service.PreviewTokenService tokens = mock(com.cretas.aims.service.PreviewTokenService.class);
            when(tokens.createBoundToken(any())).thenReturn(com.cretas.aims.entity.intent.IntentPreviewToken.builder()
                    .token("tok-1").commandDigest("digest-1").expiresAt(LocalDateTime.now().plusMinutes(5)).build());
            ReflectionTestUtils.setField(dispatch, "previewTokenService", tokens);
            ReflectionTestUtils.setField(dispatch, "writeGuardService", new WriteGuardService());
            // 与流式侧共用同一个会话服务 / 同一张会话表: 预览开的会话, 流式那边读得到
            ReflectionTestUtils.setField(dispatch, "conversationService", h.conversations);
            useIntent = AIIntentConfig.builder().intentCode("MATERIAL_BATCH_USE").intentName("领用原料")
                    .intentCategory("PRODUCTION").businessType("FACTORY").sensitivityLevel("LOW")
                    .toolName("material_batch_use").build();
        }

        /** App 请求 POST /preview 的样子: 原话 + 被拦的意图码 + 流式那一轮定下的参数 (context), previewOnly。 */
        IntentExecuteResponse preview(String userInput, Map<String, Object> context) {
            return dispatch.executeWithTool(tool, F, IntentExecuteRequest.builder()
                    .intentCode("MATERIAL_BATCH_USE").userInput(userInput).context(new HashMap<>(context))
                    .previewOnly(true).build(), useIntent, USER, PREVIEW_ROLE, null);
        }
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

            conversations = new ConversationServiceImpl(
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
