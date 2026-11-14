package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.impl.workprocess.WorkProcessTaskCompleteTool;
import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.conversation.ConversationSession;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.entity.workprocess.WorkProcessTask.Status;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.repository.conversation.ConversationSessionRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.ConversationService;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.cretas.aims.service.impl.ConversationServiceImpl;
import com.cretas.aims.service.workprocess.WorkProcessTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「我要报工，产量100」: 先确定报哪一个产品 / 批次的哪一道工序, 再记这一轮的产量
 * (Steve 2026-11-14: 「首先应该是确定是哪一个产品或者哪一个生产计划，然后报的是那一轮的工」)。
 *
 * <p>真的: 派发层 {@link ToolDispatchService} (普通入口与 SSE 共用的写预览出口)、真实
 * {@link WorkProcessTaskCompleteTool}、真实 {@link ConversationServiceImpl} 与真实选项匹配
 * ({@code ClarificationChoiceMatcher}); 会话仓库用内存实现 (存 / 取同一个对象)。桩: 工序任务服务 (给出本人进行中的任务)、
 * 预览令牌服务 (只记下被绑定的参数)。
 *
 * <p>每条「没发生」都配一条「必发生」(measurement-and-wiring 硬约束 9): 没出确认卡 ↔ 预览令牌服务确实被问过 / 工具确实被调过。
 */
@DisplayName("报工: 先选哪一道工序, 再记产量")
class WorkProcessReportChoiceContractTest {

    private static final String FACTORY = "F006";
    private static final long USER = 42L;
    private static final String ROLE = "operator";

    private final ObjectMapper mapper = new ObjectMapper();
    private ToolDispatchService dispatch;
    private WorkProcessTaskService taskService;
    private PreviewTokenService previewTokenService;
    private ConversationServiceImpl conversations;
    private WorkProcessTaskCompleteTool tool;
    private AIIntentConfig intent;
    private final Map<String, ConversationSession> store = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        dispatch = new ToolDispatchService(
                mock(ToolRegistry.class), mapper, mock(DashScopeClient.class),
                mock(ToolCallRedundancyService.class), mock(SelfCorrectionService.class),
                mock(CorrectionAgentService.class), mock(ExternalVerifierService.class),
                mock(ToolResultValidatorService.class), mock(ParameterExtractionLearningService.class));
        previewTokenService = mock(PreviewTokenService.class);
        when(previewTokenService.createBoundToken(any())).thenReturn(IntentPreviewToken.builder()
                .token("tok-1").commandDigest("digest-1").expiresAt(LocalDateTime.now().plusMinutes(5)).build());
        taskService = mock(WorkProcessTaskService.class);
        tool = new WorkProcessTaskCompleteTool(taskService);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        WriteGuardService writeGuard = mock(WriteGuardService.class);
        when(writeGuard.isWriteTool(tool)).thenReturn(true);
        ReflectionTestUtils.setField(dispatch, "previewTokenService", previewTokenService);
        ReflectionTestUtils.setField(dispatch, "writeGuardService", writeGuard);

        ConversationSessionRepository repo = mock(ConversationSessionRepository.class);
        when(repo.save(any())).thenAnswer(inv -> {
            ConversationSession s = inv.getArgument(0);
            if (s.getSessionId() == null) {
                ReflectionTestUtils.setField(s, "sessionId", java.util.UUID.randomUUID().toString());
            }
            store.put(s.getSessionId(), s);
            return s;
        });
        when(repo.findBySessionIdAndFactoryIdAndUserId(anyString(), anyString(), anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(store.get((String) inv.getArgument(0))));
        conversations = new ConversationServiceImpl(repo, mock(AIIntentConfigRepository.class), null,
                mock(DashScopeClient.class));
        ReflectionTestUtils.setField(conversations, "maxRounds", 5);
        ReflectionTestUtils.setField(conversations, "timeoutMinutes", 10);
        ReflectionTestUtils.setField(conversations, "confidenceThreshold", 0.7);
        ReflectionTestUtils.setField(dispatch, "conversationService", (ConversationService) conversations);

        intent = AIIntentConfig.builder().intentCode("WORK_PROCESS_TASK_COMPLETE").intentName("完成工序")
                .intentCategory("PRODUCTION").toolName("work_process_task_complete").build();
    }

    // ------------------------------------------------------------------ 多个可报: 列出来选

    @Test
    @DisplayName("本人有 2 道进行中: 不问编号, 列「产品·批次·工序·计划/已报」, 产量 100 记着, 开会话等回「1」")
    void listsMyInProgressTasksInsteadOfAskingForTheInternalId() throws Exception {
        two();

        IntentExecuteResponse r = ask("我要报工，产量100");

        assertThat(r.getStatus()).isEqualTo("NEED_MORE_INFO");
        assertThat(r.getMessage())
                .contains("产量 100 我先记着").contains("1. 红烧肉 · 批次 B-001 · 切块 · 计划 100kg，已报 20kg")
                .contains("2. 卤鸭腿 · 批次 B-002 · 腌制 · 计划 50kg，还没报过")
                .doesNotContain("编号").doesNotContain("taskId").doesNotContain("工序任务ID");
        assertThat(r.getSessionId()).as("会话 id 带回去, 下一句的「1」才对得上").isNotBlank();
        assertThat(r.getConfirmableAction()).isNull();
        verify(previewTokenService, never()).createBoundToken(any());
        // 必发生: 查的是「本人 + 进行中」(口径 = RN 报工屏第一跳: 我的 + 未指派, 指派给别人的不出现)
        verify(taskService).list(eq(FACTORY), eq(Status.IN_PROGRESS), eq(null), eq(USER), any(Pageable.class));
    }

    @Test
    @DisplayName("回「2」→ 选中第二道, 产量 100 还在, 直接进写确认 (不重复问产量), 令牌绑的是选中那一道")
    void replyingWithTheNumberPicksThatTaskAndKeepsTheQuantity() throws Exception {
        two();
        when(taskService.getById(FACTORY, 202L)).thenReturn(task(202, "卤鸭腿", "B-002", "腌制", "50", null));
        IntentExecuteResponse asked = ask("我要报工，产量100");

        ConversationService.ConversationResponse picked =
                conversations.continueConversation(FACTORY, USER, asked.getSessionId(), "2");

        assertThat(picked.isCompleted()).as("选中后收集完成, 不再追问").isTrue();
        Map<String, String> collected = store.get(asked.getSessionId()).getCollectedParameters();
        assertThat(collected).containsEntry("taskId", "202").containsEntry("actualQuantity", "100");

        IntentExecuteResponse confirm = previewWith(new HashMap<>(collected), "我要报工，产量100");
        assertThat(confirm.getConfirmableAction()).as("写确认卡在, 不自动提交").isNotNull();
        verify(taskService, never()).complete(anyString(), anyLong(), anyLong(), any());
        assertThat(boundParams()).containsEntry("taskId", "202");
    }

    @Test
    @DisplayName("回产品名「红烧肉」(只对上一道) → 选中; 说「卤鸭腿那批」同理")
    void replyingWithAProductNamePicksTheUniqueMatch() throws Exception {
        two();
        IntentExecuteResponse asked = ask("我要报工，产量100");

        conversations.continueConversation(FACTORY, USER, asked.getSessionId(), "卤鸭腿那批");

        assertThat(store.get(asked.getSessionId()).getCollectedParameters())
                .containsEntry("taskId", "202").containsEntry("actualQuantity", "100");
    }

    @Test
    @DisplayName("阴性对照: 同一产品下好几道工序、只说产品名 → 对不上, 再列一遍, 不猜")
    void ambiguousProductNameAsksAgainInsteadOfGuessing() throws Exception {
        List<WorkProcessTaskDTO> sameProduct = List.of(
                task(301, "红烧肉", "B-001", "切块", "100", "0"),
                task(302, "红烧肉", "B-001", "炖煮", "100", "0"));
        when(taskService.list(eq(FACTORY), eq(Status.IN_PROGRESS), any(), eq(USER), any(Pageable.class)))
                .thenReturn(page(sameProduct));
        IntentExecuteResponse asked = ask("我要报工，产量100");

        ConversationService.ConversationResponse reply =
                conversations.continueConversation(FACTORY, USER, asked.getSessionId(), "红烧肉");

        assertThat(reply.isCompleted()).isFalse();
        assertThat(reply.getMessage()).contains("没对上").contains("1. 红烧肉").contains("2. 红烧肉");
        assertThat(store.get(asked.getSessionId()).getCollectedParameters()).doesNotContainKey("taskId");
        // 再说「炖煮」就唯一了 (必发生: 不是会话坏了)
        ConversationService.ConversationResponse again =
                conversations.continueConversation(FACTORY, USER, asked.getSessionId(), "红烧肉炖煮那道");
        assertThat(again.isCompleted()).isTrue();
        assertThat(store.get(asked.getSessionId()).getCollectedParameters()).containsEntry("taskId", "302");
    }

    @Test
    @DisplayName("阴性对照: 回的是另一句话(「查一下红烧肉库存」)不被旧选项截走")
    void aNewSentenceMentioningTheProductIsNotAPick() throws Exception {
        two();
        IntentExecuteResponse asked = ask("我要报工，产量100");

        ConversationService.ConversationResponse reply =
                conversations.continueConversation(FACTORY, USER, asked.getSessionId(), "查一下红烧肉库存");

        assertThat(reply.isCompleted()).isFalse();
        assertThat(store.get(asked.getSessionId()).getCollectedParameters()).doesNotContainKey("taskId");
    }

    // ------------------------------------------------------------------ 只有一个 / 一个都没有 / 话里带了名字

    @Test
    @DisplayName("只有一个可报: 直接带出这一个, 仍出写确认卡(不自动提交), 产量 100 保留")
    void singleTaskIsPickedForTheWorkerButStillNeedsConfirmation() throws Exception {
        when(taskService.list(eq(FACTORY), eq(Status.IN_PROGRESS), any(), eq(USER), any(Pageable.class)))
                .thenReturn(page(List.of(task(101, "红烧肉", "B-001", "切块", "100", "20"))));
        when(taskService.getById(FACTORY, 101L)).thenReturn(task(101, "红烧肉", "B-001", "切块", "100", "20"));

        IntentExecuteResponse r = ask("我要报工，产量100");

        assertThat(r.getStatus()).isEqualTo("PREVIEW");
        assertThat(r.getConfirmableAction()).isNotNull();
        verify(taskService, never()).complete(anyString(), anyLong(), anyLong(), any());
        assertThat(boundParams()).containsEntry("taskId", "101");
        assertThat(String.valueOf(boundParams().get("actualQuantity"))).startsWith("100");
    }

    @Test
    @DisplayName("一个都没有: 如实说没有进行中的工序, 提示先开工, 不出确认卡、不编任务")
    void noTaskSaysSoAndPointsToStartingOne() throws Exception {
        when(taskService.list(eq(FACTORY), eq(Status.IN_PROGRESS), any(), eq(USER), any(Pageable.class)))
                .thenReturn(page(List.of()));

        IntentExecuteResponse r = ask("我要报工，产量100");

        assertThat(r.getStatus()).isEqualTo(ToolDispatchService.PREVIEW_BLOCKED);
        assertThat(r.getMessage()).contains("没有进行中的工序任务").contains("开工");
        assertThat(r.getConfirmableAction()).isNull();
        assertThat(r.getSessionId()).isNull();
        verify(taskService).list(eq(FACTORY), eq(Status.IN_PROGRESS), any(), eq(USER), any(Pageable.class));
        verify(previewTokenService, never()).createBoundToken(any());
    }

    @Test
    @DisplayName("话里带了产品名(「红烧肉那批报工 100」): 先按它缩小, 唯一就直接带出")
    void productNameInTheSentenceNarrowsToTheOnlyCandidate() throws Exception {
        two();
        when(taskService.getById(FACTORY, 101L)).thenReturn(task(101, "红烧肉", "B-001", "切块", "100", "20"));

        IntentExecuteResponse r = ask("红烧肉那批报工 100");

        assertThat(r.getStatus()).isEqualTo("PREVIEW");
        assertThat(boundParams()).containsEntry("taskId", "101");
    }

    @Test
    @DisplayName("对照: 用户自己给了任务号(「完成工序任务 101」)走原路, 不再列选项")
    void anExplicitTaskIdSkipsTheList() throws Exception {
        two();
        when(taskService.getById(FACTORY, 101L)).thenReturn(task(101, "红烧肉", "B-001", "切块", "100", "20"));
        Map<String, Object> ctx = new HashMap<>(Map.of("taskId", 101, "actualQuantity", 100));

        IntentExecuteResponse r = previewWith(ctx, "完成工序任务 101，产量 100");

        assertThat(r.getStatus()).isEqualTo("PREVIEW");
        verify(taskService, never()).list(anyString(), any(), any(), any(), any(Pageable.class));
    }

    // ------------------------------------------------------------------ helpers

    private void two() {
        when(taskService.list(eq(FACTORY), eq(Status.IN_PROGRESS), any(), eq(USER), any(Pageable.class)))
                .thenReturn(page(List.of(
                        task(101, "红烧肉", "B-001", "切块", "100", "20"),
                        task(202, "卤鸭腿", "B-002", "腌制", "50", null))));
    }

    private IntentExecuteResponse ask(String userInput) {
        return previewWith(new HashMap<>(Map.of("actualQuantity", 100)), userInput);
    }

    private IntentExecuteResponse previewWith(Map<String, Object> context, String userInput) {
        IntentExecuteRequest request = IntentExecuteRequest.builder()
                .intentCode(intent.getIntentCode()).userInput(userInput).context(context).previewOnly(true).build();
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, USER, FACTORY, ROLE))) {
            return dispatch.executeToolPreview(tool, FACTORY, request, intent, USER, ROLE);
        }
    }

    private Map<String, Object> boundParams() {
        ArgumentCaptor<PreviewTokenService.BoundTokenRequest> captor =
                ArgumentCaptor.forClass(PreviewTokenService.BoundTokenRequest.class);
        verify(previewTokenService).createBoundToken(captor.capture());
        return captor.getValue().parameters();
    }

    private static PageResponse<WorkProcessTaskDTO> page(List<WorkProcessTaskDTO> tasks) {
        return PageResponse.of(tasks, 1, 50, (long) tasks.size());
    }

    private static WorkProcessTaskDTO task(long id, String product, String batch, String process,
                                           String planned, String reported) {
        return WorkProcessTaskDTO.builder().id(id).factoryId(FACTORY).status(Status.IN_PROGRESS)
                .productTypeName(product).batchNumber(batch).processName(process)
                .plannedQuantity(new BigDecimal(planned)).plannedUnit("kg")
                .actualQuantity(reported == null ? null : new BigDecimal(reported)).build();
    }
}
