package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.conversation.ConversationSession;
import com.cretas.aims.entity.conversation.ConversationSession.RequiredParameter;
import com.cretas.aims.entity.conversation.ConversationSession.SessionStatus;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.repository.conversation.ConversationSessionRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.AgentOrchestrator;
import com.cretas.aims.service.AgenticRAGRouterService;
import com.cretas.aims.service.AnalysisRouterService;
import com.cretas.aims.service.ComplexityRouter;
import com.cretas.aims.service.ConversationMemoryService;
import com.cretas.aims.service.ConversationService;
import com.cretas.aims.service.IntentSemanticsParser;
import com.cretas.aims.service.QueryPreprocessorService;
import com.cretas.aims.service.ResultValidatorService;
import com.cretas.aims.service.RuleEngineService;
import com.cretas.aims.service.SemanticCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 普通入口 (Web /execute) 与 SSE 用同一条续轮规则与同一个任务状态服务 (审阅 5.2 缺口 5)。
 * 开关开: 追问参数时说了不相干的话 → 挂起、不再交给旧任务抽参; 「继续」回到挂起的任务 (只问、不执行)。
 * 开关关: 与改动前相同 (一律交给旧任务抽参)。
 */
class IntentExecutionOrchestratorTaskStateContractTest {

    private static final String F = "F006";
    private static final long USER = 1686L;

    private IntentExecutionOrchestrator orchestrator;
    private ConversationService conversationService;
    private ConversationTaskStateService taskState;
    private final Map<String, ConversationSession> sessions = new LinkedHashMap<>();
    private ConversationSession report;

    @BeforeEach
    void setUp() {
        conversationService = mock(ConversationService.class);
        orchestrator = new IntentExecutionOrchestrator(
                mock(AIIntentService.class), mock(IntentSemanticsParser.class), mock(SemanticCacheService.class),
                mock(RuleEngineService.class), conversationService, mock(ConversationMemoryService.class),
                new ObjectMapper(), mock(DashScopeClient.class), mock(DashScopeConfig.class),
                mock(IntentKnowledgeBase.class), mock(AIAnalysisResultRepository.class), mock(ToolRegistry.class),
                mock(AnalysisRouterService.class), mock(ComplexityRouter.class), mock(AgentOrchestrator.class),
                mock(AgenticRAGRouterService.class), mock(ResultValidatorService.class),
                mock(ToolDispatchService.class), mock(DynamicToolSelectionService.class),
                mock(QueryPreprocessorService.class));

        ConversationSessionRepository repo = mock(ConversationSessionRepository.class);
        when(repo.save(any(ConversationSession.class))).thenAnswer(inv -> {
            ConversationSession s = inv.getArgument(0);
            sessions.put(s.getSessionId(), s);
            return s;
        });
        when(repo.findSuspendedByFactoryAndUser(anyString(), anyLong())).thenAnswer(inv -> sessions.values().stream()
                .filter(s -> s.getStatus() == SessionStatus.SUSPENDED && s.getFactoryId().equals(inv.getArgument(0))
                        && s.getUserId().equals(inv.getArgument(1))).toList());
        taskState = new ConversationTaskStateService(repo);
        ReflectionTestUtils.setField(orchestrator, "taskStateService", taskState);

        report = ConversationSession.createForParameterCollection(F, USER, "WORK_PROCESS_TASK_COMPLETE",
                new ArrayList<>(List.of(RequiredParameter.builder().name("taskId").label("工序任务ID")
                        .type("INTEGER").build())), "请提供工序任务ID");
        report.setSessionId("s-report");
        report.setLastActiveAt(LocalDateTime.now());
        report.setCollectedParameters(new LinkedHashMap<>(Map.of("actualQuantity", "0")));
        sessions.put(report.getSessionId(), report);
        when(conversationService.getSession(F, USER, "s-report")).thenReturn(Optional.of(report));
    }

    @Test
    void anUnrelatedSentenceSuspendsTheTaskInsteadOfFeedingItToTheOldTask() {
        taskState.setEnabled(true);

        IntentExecuteResponse response = continuation("先看下库存，回头再报工");

        assertThat(response).as("交回正常识别").isNull();
        assertThat(report.getStatus()).isEqualTo(SessionStatus.SUSPENDED);
        verify(conversationService, never()).continueConversation(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void anAnswerStillGoesToTheOpenTask() {
        taskState.setEnabled(true);
        continuation("1827");
        verify(conversationService).continueConversation(F, USER, "s-report", "1827");
        assertThat(report.getStatus()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void switchedOffTheOldBehaviourIsUnchanged() {
        taskState.setEnabled(false);
        continuation("先看下库存，回头再报工");
        verify(conversationService).continueConversation(F, USER, "s-report", "先看下库存，回头再报工");
        assertThat(report.getStatus()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void continueResumesTheSuspendedTaskWithoutExecutingAnything() {
        taskState.setEnabled(true);
        continuation("先看下库存，回头再报工");

        IntentExecuteResponse resumed;
        try (AuthenticatedActor.ActorScope ignored = actor()) {
            resumed = ReflectionTestUtils.invokeMethod(orchestrator, "handleTaskResume", F,
                    IntentExecuteRequest.builder().userInput("好了，继续").build(), USER);
        }

        assertThat(resumed).isNotNull();
        assertThat(resumed.getStatus()).isEqualTo("CONVERSATION_CONTINUE");
        assertThat(resumed.getSessionId()).isEqualTo("s-report");
        assertThat(resumed.getMetadata()).containsEntry("sessionId", "s-report").containsEntry("taskResumed", true);
        assertThat(resumed.getMessage()).contains("工序任务ID").contains("实际产量：0").doesNotContain("actualQuantity");
        assertThat(resumed.getConfirmableAction()).isNull();
        assertThat(report.getStatus()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void anotherUsersCallCannotResumeMyTask() {
        taskState.setEnabled(true);
        continuation("先看下库存，回头再报工");
        IntentExecuteResponse resumed;
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 9999L, F, "operator"))) {
            resumed = ReflectionTestUtils.invokeMethod(orchestrator, "handleTaskResume", F,
                    IntentExecuteRequest.builder().userInput("继续").build(), 9999L);
        }
        assertThat(resumed).isNull();
        assertThat(report.getStatus()).isEqualTo(SessionStatus.SUSPENDED);
    }

    private IntentExecuteResponse continuation(String input) {
        try (AuthenticatedActor.ActorScope ignored = actor()) {
            return ReflectionTestUtils.invokeMethod(orchestrator, "handleConversationContinuation", F,
                    IntentExecuteRequest.builder().sessionId("s-report").userInput(input).build(), USER, "operator");
        }
    }

    private static AuthenticatedActor.ActorScope actor() {
        return AuthenticatedActor.trustedAsyncScope(
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, USER, F, "operator"));
    }
}
