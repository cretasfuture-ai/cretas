package com.cretas.aims.service.impl;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.entity.conversation.ConversationSession;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.repository.conversation.ConversationSessionRepository;
import com.cretas.aims.service.ExpressionLearningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Unit contract for pause/resume state protection and context retention. */
class ConversationPauseResumeContractTest {
    private static final String FACTORY = "F-PAUSE";
    private static final Long USER = 17L;
    private ConversationSessionRepository repository;
    private ConversationServiceImpl service;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=ConversationSession.SessionStatus.class,
            names={"COMPLETED","CANCELLED","PAUSED","TIMEOUT"})
    void nonActiveSessionCannotReturnACompletedCommandForReplay(ConversationSession.SessionStatus status) {
        var closed=session("already-terminal",status);
        when(repository.findBySessionIdAndFactoryIdAndUserIdForUpdate("already-terminal",FACTORY,USER))
                .thenReturn(Optional.of(closed));
        var response=service.continueConversation(FACTORY,USER,"already-terminal","继续");
        assertThat(response.isCompleted()).isFalse();
        assertThat(response.isTaskChanged()).isTrue();
        assertThat(response.getIntentCode()).isNull();
        verify(repository,never()).save(any());
    }

    @BeforeEach
    void setUp() {
        repository = mock(ConversationSessionRepository.class);
        service = new ConversationServiceImpl(repository, mock(AIIntentConfigRepository.class),
                mock(ExpressionLearningService.class), mock(DashScopeClient.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class));
        ReflectionTestUtils.setField(service, "entityManager", mock(jakarta.persistence.EntityManager.class));
    }

    @Test
    void pauseRetainsParametersAndMessagesAndResumeOnlyChangesLifecycleFields() {
        ConversationSession session = session("pause-1", ConversationSession.SessionStatus.ACTIVE);
        session.setRequiredParameters(List.of(ConversationSession.RequiredParameter.builder()
                .name("batchId").value("B-1").collected(true).build()));
        session.setCollectedParameters(java.util.Map.of("batchId", "B-1"));
        session.setMessages(List.of(ConversationSession.Message.user("原始问题"),
                ConversationSession.Message.assistant("请补充批次")));
        when(repository.findByFactoryIdAndUserIdForUpdate(FACTORY, USER)).thenReturn(List.of(session));

        assertThat(service.pauseConversation(FACTORY, USER, session.getSessionId())).isTrue();
        assertThat(session.getStatus()).isEqualTo(ConversationSession.SessionStatus.PAUSED);
        String messages = session.getMessagesJson();
        String parameters = session.getCollectedParametersJson();

        assertThat(service.resumeConversation(FACTORY, USER, session.getSessionId())).isTrue();
        assertThat(session.getStatus()).isEqualTo(ConversationSession.SessionStatus.ACTIVE);
        assertThat(session.getMessagesJson()).isEqualTo(messages);
        assertThat(session.getCollectedParametersJson()).isEqualTo(parameters);
        verify(repository, times(2)).save(session);
    }

    @Test
    void resumeRequiresOwnedExplicitPausedIdAndDoesNotReviveTerminalState() {
        ConversationSession completed = session("done", ConversationSession.SessionStatus.COMPLETED);
        when(repository.findByFactoryIdAndUserIdForUpdate(FACTORY, USER)).thenReturn(List.of(completed));

        assertThat(service.resumeConversation(FACTORY, USER, null)).isFalse();
        assertThat(service.resumeConversation(FACTORY, USER, "latest")).isFalse();
        assertThat(service.resumeConversation(FACTORY, USER, completed.getSessionId())).isFalse();
        assertThat(completed.getStatus()).isEqualTo(ConversationSession.SessionStatus.COMPLETED);
        verify(repository, never()).save(any());
    }

    @Test
    void foreignOwnerAndActiveSiblingCannotResumePausedSession() {
        ConversationSession paused = session("paused", ConversationSession.SessionStatus.PAUSED);
        ConversationSession activeSibling = session("active", ConversationSession.SessionStatus.ACTIVE);
        when(repository.findByFactoryIdAndUserIdForUpdate(FACTORY, USER))
                .thenReturn(List.of(activeSibling, paused));

        assertThat(service.resumeConversation(FACTORY, USER, paused.getSessionId())).isFalse();
        assertThat(paused.getStatus()).isEqualTo(ConversationSession.SessionStatus.PAUSED);
        assertThat(service.resumeConversation("OTHER", USER, paused.getSessionId())).isFalse();
    }

    @Test
    void expiredPausedSessionBecomesTimeoutInsteadOfBeingResumed() {
        ConversationSession paused = session("expired", ConversationSession.SessionStatus.PAUSED);
        paused.setLastActiveAt(LocalDateTime.now().minusMinutes(20));
        when(repository.findByFactoryIdAndUserIdForUpdate(FACTORY, USER)).thenReturn(List.of(paused));

        assertThat(service.resumeConversation(FACTORY, USER, paused.getSessionId())).isFalse();
        assertThat(paused.getStatus()).isEqualTo(ConversationSession.SessionStatus.TIMEOUT);
        verify(repository).save(paused);
    }

    @Test
    void revisionUsesFingerprintAndChangesOnlyAllowedCollectedParameter() {
        ConversationSession session = session("revision", ConversationSession.SessionStatus.ACTIVE);
        session.setSessionMode(ConversationSession.SessionMode.PARAMETER_COLLECTION);
        session.setKnownIntentCode("INTENT");
        session.setRequiredParameters(List.of(
                ConversationSession.RequiredParameter.builder().name("quantity").type("number")
                        .collected(true).value("50").build(),
                ConversationSession.RequiredParameter.builder().name("batchId").type("string")
                        .collected(true).value("B-1").build()));
        session.setCollectedParameters(java.util.Map.of("quantity", "50", "batchId", "B-1"));
        when(repository.findByFactoryIdAndUserIdForUpdate(FACTORY, USER)).thenReturn(List.of(session));

        String fingerprint = com.cretas.aims.service.ConversationService.draftRevisionFingerprint(session);
        assertThat(service.reviseDraft(FACTORY, USER, session.getSessionId(),
                java.util.Map.of("quantity", "80"), fingerprint)).isTrue();
        assertThat(session.getCollectedParameters())
                .containsEntry("quantity", "80").containsEntry("batchId", "B-1");
        assertThat(service.reviseDraft(FACTORY, USER, session.getSessionId(),
                java.util.Map.of("quantity", "90"), fingerprint)).isFalse();
        assertThat(service.reviseDraft(FACTORY, USER, session.getSessionId(),
                java.util.Map.of("factoryId", "OTHER"),
                com.cretas.aims.service.ConversationService.draftRevisionFingerprint(session))).isFalse();
    }

    private ConversationSession session(String id, ConversationSession.SessionStatus status) {
        LocalDateTime now = LocalDateTime.now();
        return ConversationSession.builder()
                .sessionId(id).factoryId(FACTORY).userId(USER).originalInput("原始问题")
                .currentRound(1).maxRounds(5).status(status)
                .sessionMode(ConversationSession.SessionMode.PARAMETER_COLLECTION)
                .timeoutMinutes(10).createdAt(now).lastActiveAt(now)
                .messagesJson("[]").candidatesJson("[]").build();
    }
}
