package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.conversation.ConversationSession;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.ConversationService;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationTaskRepairContractTest {
    private static final String FACTORY = "F-REPAIR";
    private static final Long USER = 71L;
    private ConversationService conversations;
    private DashScopeClient llm;
    private PreviewTokenService previews;
    private ConversationTaskRepairSupport support;
    private ToolExecutor tool;
    private UnitContractService units;

    @BeforeEach
    void setUp() {
        conversations = mock(ConversationService.class);
        llm = mock(DashScopeClient.class);
        previews = mock(PreviewTokenService.class);
        support = new ConversationTaskRepairSupport(conversations, llm, previews);
        ReflectionTestUtils.setField(support, "transitionBoundary", new ConversationDraftTransitionBoundary());
        IntentConfigManagementService intents = mock(IntentConfigManagementService.class);
        ToolRegistry registry = mock(ToolRegistry.class);
        tool = mock(ToolExecutor.class);
        units = mock(UnitContractService.class);
        ReflectionTestUtils.setField(support, "intentConfigManagementService", intents);
        ReflectionTestUtils.setField(support, "toolRegistry", registry);
        ReflectionTestUtils.setField(support, "unitContractService", units);
        when(intents.getIntentByCode(eq(FACTORY), eq("INTENT"))).thenReturn(Optional.of(
                AIIntentConfig.builder().intentCode("INTENT").toolName("tool").build()));
        when(registry.getExecutor("tool")).thenReturn(Optional.of(tool));
        when(tool.getParametersSchema()).thenReturn(Map.of(
                "type", "object",
                "properties", Map.of("quantity", Map.of("type", "number"), "unit", Map.of("type", "string"))));
        when(llm.isAvailable()).thenReturn(true);
        when(previews.invalidateForRevision(anyString(), anyLong(), anyString())).thenReturn(true);
        when(previews.invalidateDraftForRevision(anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(true);
    }

    @Test
    void ordinaryQueryWithoutDraftDoesNotCallModel() {
        IntentExecuteRequest request = request("查询今天订单");
        withActor(() -> {
            assertThat(support.prepare(FACTORY, USER, request)).isNull();
            verifyNoInteractions(llm);
        });
    }

    @Test
    void existingIntentRecognitionConversationIsLeftToItsOriginalPath() {
        ConversationSession session=draft("legacy-recognition",ConversationSession.SessionStatus.ACTIVE,Map.of());
        session.setSessionMode(ConversationSession.SessionMode.INTENT_RECOGNITION);
        when(conversations.getActiveSession(FACTORY,USER)).thenReturn(Optional.of(session));
        var request=request("继续说明目标");
        withActor(() -> assertThat(support.prepare(FACTORY,USER,request)).isNull());
        assertThat(request.getSemanticTaskAction()).isNull();
        verifyNoInteractions(llm);
    }

    @Test
    void correctionChangesQuantityAndPreservesOtherCollectedFields() {
        ConversationSession session = draft("draft-real", ConversationSession.SessionStatus.ACTIVE,
                Map.of("quantity", "50", "unit", "kg"));
        when(conversations.getSession(FACTORY, USER, session.getSessionId())).thenReturn(Optional.of(session));
        when(conversations.getActiveSession(FACTORY, USER)).thenReturn(Optional.of(session));
        when(conversations.getPausedSessions(FACTORY, USER)).thenReturn(List.of());
        when(units.areEquivalent(FACTORY, "kg", "kg")).thenReturn(true);
        when(conversations.reviseDraft(anyString(), anyLong(), anyString(), anyMap(), anyString(), anyBoolean())).thenReturn(true);
        llmResponse("{\"taskAction\":\"CORRECT\",\"taskReference\":\"draft-1\",\"spokenUnit\":\"kg\",\"changes\":{\"quantity\":\"80\"}}");

        IntentExecuteRequest request = request("改成80kg");
        request.setSessionId(session.getSessionId());
        withActor(() -> assertThat(support.prepare(FACTORY, USER, request)).isNull());

        verify(conversations).reviseDraft(eq(FACTORY), eq(USER), eq(session.getSessionId()),
                eq(Map.of("quantity", "80")), eq(ConversationService.draftRevisionFingerprint(session)), eq(false));
        assertThat(request.getSemanticTaskAction()).isEqualTo("CONTINUE");
        assertThat(request.getSessionId()).isEqualTo(session.getSessionId());
    }

    @Test
    void quantityCorrectionMayRetainConfirmedUnitWhenModelOmitsSpokenUnit() {
        ConversationSession session = draft("draft-real", ConversationSession.SessionStatus.ACTIVE,
                Map.of("quantity", "50", "unit", "kg"));
        when(conversations.getActiveSession(FACTORY, USER)).thenReturn(Optional.of(session));
        when(conversations.getPausedSessions(FACTORY, USER)).thenReturn(List.of());
        when(units.areEquivalent(FACTORY, "kg", "kg")).thenReturn(true);
        when(conversations.reviseDraft(anyString(), anyLong(), anyString(), anyMap(), anyString(), anyBoolean()))
                .thenReturn(true);
        llmResponse("{\"taskAction\":\"CORRECT\",\"taskReference\":\"draft-1\",\"spokenUnit\":\"\",\"changes\":{\"quantity\":\"80\"}}");

        IntentExecuteRequest request = request("数量改成80，其他不变");
        request.setSessionId(session.getSessionId());
        withActor(() -> assertThat(support.prepare(FACTORY, USER, request)).isNull());

        verify(conversations).reviseDraft(eq(FACTORY), eq(USER), eq(session.getSessionId()),
                eq(Map.of("quantity", "80")), eq(ConversationService.draftRevisionFingerprint(session)), eq(false));
    }

    @Test
    void incompatibleSpokenUnitDoesNotApplyQuantityCorrection() {
        ConversationSession session = draft("draft-real", ConversationSession.SessionStatus.ACTIVE,
                Map.of("quantity", "80", "unit", "kg"));
        when(conversations.getActiveSession(FACTORY, USER)).thenReturn(Optional.of(session));
        when(conversations.getPausedSessions(FACTORY, USER)).thenReturn(List.of());
        when(units.areEquivalent(FACTORY, "kg", "箱")).thenReturn(false);
        llmResponse("{\"taskAction\":\"CORRECT\",\"taskReference\":\"draft-1\",\"spokenUnit\":\"箱\",\"changes\":{\"quantity\":\"80\"}}");

        IntentExecuteResponse result = withActorResult(() -> support.prepare(FACTORY, USER, request("80箱")));
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("NEED_MORE_INFO");
        verify(conversations, never()).reviseDraft(anyString(), anyLong(), anyString(), anyMap(), anyString());
    }

    @Test
    void explicitIncompatibleUnitIsRejectedEvenWhenModelOmitsSpokenUnit() {
        ConversationSession session = draft("draft-real", ConversationSession.SessionStatus.ACTIVE,
                Map.of("quantity", "50", "unit", "kg"));
        when(conversations.getActiveSession(FACTORY, USER)).thenReturn(Optional.of(session));
        when(conversations.getPausedSessions(FACTORY, USER)).thenReturn(List.of());
        when(units.areEquivalent(FACTORY, "kg", "kg")).thenReturn(true);
        when(units.areEquivalent(FACTORY, "kg", "箱")).thenReturn(false);
        llmResponse("{\"taskAction\":\"CORRECT\",\"taskReference\":\"draft-1\",\"spokenUnit\":\"\",\"changes\":{\"quantity\":\"80\"}}");

        IntentExecuteResponse result = withActorResult(() -> support.prepare(FACTORY, USER, request("改成80箱")));
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("NEED_MORE_INFO");
        verify(conversations, never()).reviseDraft(anyString(), anyLong(), anyString(), anyMap(), anyString());
    }

    @Test
    void acceptedUnitFieldMustMatchConfirmedUnitEvenWhenSpokenUnitMatchesUtterance() {
        ConversationSession session = draft("draft-real", ConversationSession.SessionStatus.ACTIVE,
                Map.of("quantity", "50", "unit", "kg"));
        when(conversations.getActiveSession(FACTORY, USER)).thenReturn(Optional.of(session));
        when(conversations.getPausedSessions(FACTORY, USER)).thenReturn(List.of());
        when(units.areEquivalent(FACTORY, "kg", "kg")).thenReturn(true);
        when(units.areEquivalent(FACTORY, "kg", "箱")).thenReturn(false);
        llmResponse("{\"taskAction\":\"CORRECT\",\"taskReference\":\"draft-1\",\"spokenUnit\":\"公斤\",\"changes\":{\"quantity\":\"80\",\"unit\":\"箱\"}}");

        IntentExecuteResponse result = withActorResult(() -> support.prepare(FACTORY, USER,
                request("改成80公斤")));
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("NEED_MORE_INFO");
        verify(conversations, never()).reviseDraft(anyString(), anyLong(), anyString(), anyMap(), anyString());
    }

    @Test
    void unknownConfirmedUnitRejectsUnitlessQuantityCorrection() {
        ConversationSession session = draft("draft-real", ConversationSession.SessionStatus.ACTIVE,
                Map.of("quantity", "50", "unit", "未登记单位"));
        when(conversations.getActiveSession(FACTORY, USER)).thenReturn(Optional.of(session));
        when(conversations.getPausedSessions(FACTORY, USER)).thenReturn(List.of());
        when(units.areEquivalent(FACTORY, "未登记单位", "未登记单位")).thenReturn(false);
        llmResponse("{\"taskAction\":\"CORRECT\",\"taskReference\":\"draft-1\",\"spokenUnit\":\"\",\"changes\":{\"quantity\":\"80\"}}");

        IntentExecuteResponse result = withActorResult(() -> support.prepare(FACTORY, USER, request("数量改成80")));
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("NEED_MORE_INFO");
        verify(conversations, never()).reviseDraft(anyString(), anyLong(), anyString(), anyMap(), anyString());
    }

    @Test
    void interruptPausesDraftWithoutDroppingCollectedParameters() {
        ConversationSession session = draft("draft-real", ConversationSession.SessionStatus.ACTIVE,
                Map.of("quantity", "50", "unit", "kg"));
        when(conversations.getActiveSession(FACTORY, USER)).thenReturn(Optional.of(session));
        when(conversations.getPausedSessions(FACTORY, USER)).thenReturn(List.of());
        llmResponse("{\"taskAction\":\"INTERRUPT\",\"taskReference\":\"draft-1\"}");
        when(conversations.pauseConversation(FACTORY, USER, session.getSessionId(), false)).thenReturn(true);

        IntentExecuteRequest request = request("先问个别的");
        IntentExecuteResponse result = withActorResult(() -> support.prepare(FACTORY, USER, request));
        assertThat(result).isNull();
        assertThat(request.getSemanticTaskAction()).isEqualTo("INTERRUPT");
        assertThat(request.getSessionId()).isNull();
        verify(conversations).pauseConversation(FACTORY, USER, session.getSessionId(), false);
        assertThat(session.getCollectedParameters()).containsEntry("quantity", "50");
    }

    @Test
    void interruptDoesNotInventResumeAfterReadWithoutCurrentRead() {
        ConversationSession session = draft("draft-real", ConversationSession.SessionStatus.ACTIVE,
                Map.of("quantity", "50", "unit", "kg"));
        when(conversations.getActiveSession(FACTORY, USER)).thenReturn(Optional.of(session));
        when(conversations.getPausedSessions(FACTORY, USER)).thenReturn(List.of());
        when(conversations.pauseConversation(FACTORY, USER, session.getSessionId(), false)).thenReturn(true);
        llmResponse("{\"taskAction\":\"INTERRUPT\",\"taskReference\":\"draft-1\",\"resumeAfterRead\":true}");

        IntentExecuteRequest request = request("先处理另一个目标");
        IntentExecuteResponse result = withActorResult(() -> support.prepare(FACTORY, USER, request));
        assertThat(result).isNull();
        assertThat(request.getSemanticResumeDraftId()).isNull();
        assertThat(request.getSemanticResumeDraftRevision()).isNull();
    }

    @Test
    void plannedReadResumeRequiresUserEvidenceEvenBeforeReadExists() {
        ConversationSession session = draft("draft-real", ConversationSession.SessionStatus.ACTIVE,
                Map.of("quantity", "50", "unit", "kg"));
        when(conversations.getActiveSession(FACTORY, USER)).thenReturn(Optional.of(session));
        when(conversations.getPausedSessions(FACTORY, USER)).thenReturn(List.of());
        when(conversations.pauseConversation(FACTORY, USER, session.getSessionId(), false))
                .thenAnswer(invocation -> {
                    session.setStatus(ConversationSession.SessionStatus.PAUSED);
                    return true;
                });
        when(conversations.getSession(FACTORY, USER, session.getSessionId())).thenReturn(Optional.of(session));
        llmResponse("{\"taskAction\":\"INTERRUPT\",\"taskReference\":\"draft-1\",\"resumeAfterRead\":true,\"resumeEvidence\":\"查完继续刚才的草稿\"}");

        IntentExecuteRequest request = request("先查库存，查完继续刚才的草稿");
        IntentExecuteResponse result = withActorResult(() -> support.prepare(FACTORY, USER, request));
        assertThat(result).isNull();
        assertThat(request.getSemanticResumeDraftId()).isEqualTo(session.getSessionId());
        assertThat(request.getSemanticResumeDraftRevision()).isNotBlank();
    }

    @Test
    void multiplePausedDraftsRequireExplicitResumeChoice() {
        ConversationSession first = draft("one", ConversationSession.SessionStatus.PAUSED, Map.of("quantity", "1"));
        ConversationSession second = draft("two", ConversationSession.SessionStatus.PAUSED, Map.of("quantity", "2"));
        when(conversations.getActiveSession(FACTORY, USER)).thenReturn(Optional.empty());
        when(conversations.getPausedSessions(FACTORY, USER)).thenReturn(List.of(first, second));
        llmResponse("{\"taskAction\":\"RESUME\"}");

        IntentExecuteResponse result = withActorResult(() -> support.prepare(FACTORY, USER, request("继续暂停任务")));
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("NEED_MORE_INFO");
        verify(conversations, never()).resumeConversation(anyString(), anyLong(), anyString());
    }

    @Test
    void committedServerActionCannotBeCorrectedAsAReplayedDraft() {
        IntentExecuteRequest request = request("把刚才数量改成80");
        request.setSemanticContext(Map.of("lastActionCommitted", Map.of(
                "intentCode", "INTENT", "toolName", "tool", "status", "COMMITTED",
                "parameters", Map.of("quantity", 50))));
        when(conversations.getActiveSession(FACTORY, USER)).thenReturn(Optional.empty());
        when(conversations.getPausedSessions(FACTORY, USER)).thenReturn(List.of());
        llmResponse("{\"taskAction\":\"CORRECT\",\"changes\":{\"quantity\":\"80\"}}");

        IntentExecuteResponse result = withActorResult(() -> support.prepare(FACTORY, USER, request));
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("UNSUPPORTED");
        verify(conversations, never()).reviseDraft(anyString(), anyLong(), anyString(), anyMap(), anyString());
    }

    @Test
    void completedDraftRequiresPendingPreviewProofBeforeCorrection() {
        ConversationSession session = draft("completed-preview", ConversationSession.SessionStatus.COMPLETED,
                Map.of("quantity", "50", "unit", "kg"));
        when(conversations.getActiveSession(FACTORY, USER)).thenReturn(Optional.empty());
        when(conversations.getPausedSessions(FACTORY, USER)).thenReturn(List.of());
        when(units.areEquivalent(FACTORY, "kg", "kg")).thenReturn(true);
        String revision = ConversationService.draftRevisionFingerprint(session);
        when(previews.hasPendingDraftPreview(FACTORY, USER, session.getSessionId(), revision)).thenReturn(true);
        when(conversations.reviseDraft(anyString(), anyLong(), anyString(), anyMap(), anyString(), eq(true)))
                .thenReturn(true);
        llmResponse("{\"taskAction\":\"CORRECT\",\"taskReference\":\"draft-1\",\"spokenUnit\":\"kg\",\"changes\":{\"quantity\":\"80\"}}");

        IntentExecuteRequest request = request("改成80kg");
        request.setSessionId(session.getSessionId());
        when(conversations.getSession(FACTORY, USER, session.getSessionId())).thenReturn(Optional.of(session));
        IntentExecuteResponse result = withActorResult(() -> support.prepare(FACTORY, USER, request));
        assertThat(result).isNull();
        assertThat(request.getSemanticTaskAction()).isEqualTo("CONTINUE");
        verify(conversations).reviseDraft(eq(FACTORY), eq(USER), eq(session.getSessionId()),
                eq(Map.of("quantity", "80")), eq(revision), eq(true));
    }

    @Test
    void committedServerActionAllowsExplicitSwitchToNewTarget() {
        IntentExecuteRequest request = request("查询另一项库存");
        request.setSemanticContext(Map.of("lastActionCommitted", Map.of(
                "intentCode", "INTENT", "toolName", "tool", "status", "COMMITTED")));
        when(conversations.getActiveSession(FACTORY, USER)).thenReturn(Optional.empty());
        when(conversations.getPausedSessions(FACTORY, USER)).thenReturn(List.of());
        llmResponse("{\"taskAction\":\"SWITCH\",\"message\":\"开始新目标\"}");

        IntentExecuteResponse result = withActorResult(() -> support.prepare(FACTORY, USER, request));
        assertThat(result).isNull();
        assertThat(request.getSemanticTaskAction()).isEqualTo("SWITCH");
        verify(conversations, never()).cancelConversation(anyString(), anyLong(), anyString());
    }

    @Test
    void currentReadCancelDoesNotCancelAnUnrelatedPausedDraft() {
        ConversationSession paused = draft("paused-read", ConversationSession.SessionStatus.PAUSED,
                Map.of("quantity", "50", "unit", "kg"));
        when(conversations.getActiveSession(FACTORY, USER)).thenReturn(Optional.empty());
        when(conversations.getPausedSessions(FACTORY, USER)).thenReturn(List.of(paused));
        llmResponse("{\"taskAction\":\"CANCEL\",\"taskReference\":\"current-read\"}");
        IntentExecuteRequest request = request("不用查了");
        request.setSemanticContext(Map.of("contextStatus", "PRESENT", "references", List.of(
                Map.of("type", "READ_QUERY", "reference", "entity-0", "id", "ORDER_LIST"))));

        IntentExecuteResponse result = withActorResult(() -> support.prepare(FACTORY, USER, request));
        assertThat(result).isNull();
        assertThat(request.getSemanticTaskAction()).isEqualTo("READ_CONTINUE");
        verify(conversations, never()).cancelConversation(anyString(), anyLong(), anyString(), anyBoolean());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void currentReadPresencePreventsUnselectedDraftCancellation(boolean carriesOldTransportSession) {
        ConversationSession paused = draft("paused-read", ConversationSession.SessionStatus.PAUSED,
                Map.of("quantity", "50", "unit", "kg"));
        when(conversations.getActiveSession(FACTORY, USER)).thenReturn(Optional.empty());
        when(conversations.getPausedSessions(FACTORY, USER)).thenReturn(List.of(paused));
        llmResponse("{\"taskAction\":\"CANCEL\",\"taskReference\":\"draft-1\"}");
        IntentExecuteRequest request = request("不用查了");
        if(carriesOldTransportSession) request.setSessionId(paused.getSessionId());
        request.setSemanticContext(Map.of("contextStatus", "PRESENT", "references", List.of(
                Map.of("type", "READ_QUERY", "reference", "entity-0", "id", "UNRESOLVED_READ",
                        "taskStatus", "UNRESOLVED"))));

        IntentExecuteResponse result = withActorResult(() -> support.prepare(FACTORY, USER, request));
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("NEED_MORE_INFO");
        verify(conversations, never()).cancelConversation(anyString(), anyLong(), anyString(), anyBoolean());
    }

    @Test
    void committedOnlyCurrentReadReferenceWithoutRealReadIsUnsupported() {
        IntentExecuteRequest request = request("把刚才提交的数量改成80");
        request.setSemanticContext(Map.of("lastActionCommitted", Map.of(
                "intentCode", "INTENT", "toolName", "tool", "status", "COMMITTED")));
        when(conversations.getActiveSession(FACTORY, USER)).thenReturn(Optional.empty());
        when(conversations.getPausedSessions(FACTORY, USER)).thenReturn(List.of());
        llmResponse("{\"taskAction\":\"CORRECT\",\"taskReference\":\"current-read\",\"changes\":{\"quantity\":\"80\"}}");

        IntentExecuteResponse result = withActorResult(() -> support.prepare(FACTORY, USER, request));
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("UNSUPPORTED");
        verify(conversations, never()).reviseDraft(anyString(), anyLong(), anyString(), anyMap(), anyString());
    }

    @Test
    void successfulReadResumesOnlyTheUnchangedPausedRevision() {
        ConversationSession paused = draft("paused-read", ConversationSession.SessionStatus.PAUSED,
                Map.of("quantity", "50"));
        IntentExecuteRequest request = request("查库存");
        request.setSemanticResumeDraftId(paused.getSessionId());
        String revision = ConversationService.draftRevisionFingerprint(paused);
        request.setSemanticResumeDraftRevision(revision);
        when(conversations.getSession(FACTORY, USER, paused.getSessionId())).thenReturn(Optional.of(paused));
        when(conversations.resumeConversation(FACTORY, USER, paused.getSessionId(), revision)).thenReturn(true);
        IntentExecuteResponse response = IntentExecuteResponse.builder().status("COMPLETED").build();

        IntentExecuteResponse result = withActorResult(
                () -> support.afterRead(FACTORY, USER, request, response));
        assertThat(result.getMetadata()).containsEntry("resumeAfterRead", "RESUMED");
        verify(conversations).resumeConversation(FACTORY, USER, paused.getSessionId(), revision);
    }

    @Test
    void cancellationDecisionIsModelBoundNotTriggeredByNegatedObjectPhrase() {
        ConversationSession session = draft("draft-real", ConversationSession.SessionStatus.ACTIVE,
                Map.of("quantity", "50"));
        when(conversations.getActiveSession(FACTORY, USER)).thenReturn(Optional.of(session));
        when(conversations.getPausedSessions(FACTORY, USER)).thenReturn(List.of());
        llmResponse("{\"taskAction\":\"CLARIFY\",\"taskReference\":\"draft-1\",\"message\":\"请明确是否取消当前任务\"}");

        IntentExecuteResponse result = withActorResult(() -> support.prepare(FACTORY, USER,
                request("不是取消客户，只是不要改数量")));
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("NEED_MORE_INFO");
        verify(conversations, never()).cancelConversation(anyString(), anyLong(), anyString());
    }

    private IntentExecuteRequest request(String input) {
        return IntentExecuteRequest.builder().userInput(input).build();
    }

    private ConversationSession draft(String id, ConversationSession.SessionStatus status,
                                      Map<String, String> values) {
        LocalDateTime now = LocalDateTime.now();
        List<ConversationSession.RequiredParameter> required = values.keySet().stream()
                .map(key -> ConversationSession.RequiredParameter.builder().name(key).type(
                        key.equals("quantity") ? "number" : "string").collected(true).value(values.get(key)).build())
                .toList();
        ConversationSession session = ConversationSession.builder().sessionId(id).factoryId(FACTORY).userId(USER)
                .originalInput("draft").currentRound(1).maxRounds(5).status(status)
                .sessionMode(ConversationSession.SessionMode.PARAMETER_COLLECTION).knownIntentCode("INTENT")
                .timeoutMinutes(10).createdAt(now).lastActiveAt(now).build();
        session.setRequiredParameters(required);
        session.setCollectedParameters(values);
        return session;
    }

    private boolean llmResponse(String json) {
        ToolCall call = ToolCall.of("repair", "conversation_task_repair", json);
        ChatCompletionResponse response = new ChatCompletionResponse();
        response.setChoices(List.of(new ChatCompletionResponse.Choice(0,
                new ChatCompletionResponse.Message("assistant", null, null, List.of(call)), null, "tool_calls")));
        when(llm.chatWithTools(anyString(), anyString(), anyList())).thenReturn(response);
        when(llm.hasToolCalls(response)).thenReturn(true);
        when(llm.getFirstToolCall(response)).thenReturn(call);
        return true;
    }

    private void withActor(Runnable action) {
        try (var ignored = AuthenticatedActor.trustedAsyncScope(
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, USER, FACTORY, "sales_manager"))) {
            action.run();
        }
    }

    private <T> T withActorResult(java.util.function.Supplier<T> action) {
        try (var ignored = AuthenticatedActor.trustedAsyncScope(
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, USER, FACTORY, "sales_manager"))) {
            return action.get();
        }
    }
}
