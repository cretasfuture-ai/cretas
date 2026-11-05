package com.cretas.aims.service.impl;

import com.cretas.aims.entity.conversation.ConversationSession;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.entity.intent.IntentPreviewToken.TokenStatus;
import com.cretas.aims.repository.conversation.ConversationSessionRepository;
import com.cretas.aims.repository.intent.IntentPreviewTokenRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.ConversationService;
import com.cretas.aims.service.PreviewTokenService.BoundTokenRequest;
import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PreviewRevisionInvalidationContractTest {

    private IntentPreviewTokenRepository repository;
    private ConversationSessionRepository conversationSessions;
    private PreviewTokenServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(IntentPreviewTokenRepository.class);
        service = new PreviewTokenServiceImpl(repository, new ObjectMapper());
        ReflectionTestUtils.setField(service, "entityManager", mock(EntityManager.class));
        conversationSessions = mock(ConversationSessionRepository.class);
        ReflectionTestUtils.setField(service, "conversationSessionRepository", conversationSessions);
    }

    @Test
    void pendingTicketIsCancelledAndConditionalClaimCannotWinAfterRevisionInvalidation() {
        IntentPreviewToken token = token("pending", TokenStatus.PENDING, "F001", 7L);
        when(repository.findActiveByFactoryIdAndUserIdAndIntentCodeForUpdate(
                "F001", 7L, "ORDER_CREATE", TokenStatus.PENDING, TokenStatus.EXECUTING))
                .thenReturn(List.of(token));
        when(repository.cancelPendingToken(eq("pending"), eq("F001"), eq(7L), any(), any(),
                eq(TokenStatus.PENDING), eq(TokenStatus.CANCELLED))).thenReturn(1);

        try (var ignored = actor("F001", 7L)) {
            assertThat(service.invalidateForRevision("F001", 7L, "ORDER_CREATE")).isTrue();
        }

        verify(repository).cancelPendingToken(eq("pending"), eq("F001"), eq(7L), any(), any(),
                eq(TokenStatus.PENDING), eq(TokenStatus.CANCELLED));
        verify(repository, never()).claimForExecution(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void executingTicketRejectsRevisionInvalidationWithoutCancellation() {
        IntentPreviewToken token = token("executing", TokenStatus.EXECUTING, "F001", 7L);
        when(repository.findActiveByFactoryIdAndUserIdAndIntentCodeForUpdate(
                "F001", 7L, "ORDER_CREATE", TokenStatus.PENDING, TokenStatus.EXECUTING))
                .thenReturn(List.of(token));

        try (var ignored = actor("F001", 7L)) {
            assertThat(service.invalidateForRevision("F001", 7L, "ORDER_CREATE")).isFalse();
        }

        verify(repository, never()).cancelPendingToken(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void missingTicketIsASuccessfulNoOp() {
        when(repository.findActiveByFactoryIdAndUserIdAndIntentCodeForUpdate(
                "F001", 7L, "ORDER_CREATE", TokenStatus.PENDING, TokenStatus.EXECUTING))
                .thenReturn(List.of());

        try (var ignored = actor("F001", 7L)) {
            assertThat(service.invalidateForRevision("F001", 7L, "ORDER_CREATE")).isTrue();
        }
        verify(repository, never()).cancelPendingToken(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void invalidOwnerScopeFailsClosedAndLeavesTicketUntouched() {
        IntentPreviewToken token = token("foreign", TokenStatus.PENDING, "F001", 7L);
        token.setTenantId("F999");
        when(repository.findActiveByFactoryIdAndUserIdAndIntentCodeForUpdate(
                "F001", 7L, "ORDER_CREATE", TokenStatus.PENDING, TokenStatus.EXECUTING))
                .thenReturn(List.of(token));

        try (var ignored = actor("F001", 7L)) {
            assertThat(service.invalidateForRevision("F001", 7L, "ORDER_CREATE")).isFalse();
        }
        verify(repository, never()).cancelPendingToken(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void conditionalCancellationLossThrowsSoTransactionalCallerRollsBackAllCancellations() {
        IntentPreviewToken first = token("first", TokenStatus.PENDING, "F001", 7L);
        IntentPreviewToken second = token("second", TokenStatus.PENDING, "F001", 7L);
        when(repository.findActiveByFactoryIdAndUserIdAndIntentCodeForUpdate(
                "F001", 7L, "ORDER_CREATE", TokenStatus.PENDING, TokenStatus.EXECUTING))
                .thenReturn(List.of(first, second));
        when(repository.cancelPendingToken(eq("first"), eq("F001"), eq(7L), any(), any(),
                eq(TokenStatus.PENDING), eq(TokenStatus.CANCELLED))).thenReturn(1);
        when(repository.cancelPendingToken(eq("second"), eq("F001"), eq(7L), any(), any(),
                eq(TokenStatus.PENDING), eq(TokenStatus.CANCELLED))).thenReturn(0);

        try (var ignored = actor("F001", 7L)) {
            assertThatThrownBy(() -> service.invalidateForRevision("F001", 7L, "ORDER_CREATE"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void missingActorPlatformActorAndForeignFactoryActorDoNotTouchRepository() {
        assertThat(service.invalidateForRevision("F001", 7L, "ORDER_CREATE")).isFalse();
        try (var ignored = AuthenticatedActor.trustedAsyncScope(new AuthenticatedActor(
                AuthenticatedActorType.PLATFORM_ADMIN, 7L, null, "platform_admin"))) {
            assertThat(service.invalidateForRevision("F001", 7L, "ORDER_CREATE")).isFalse();
        }
        try (var ignored = actor("F999", 7L)) {
            assertThat(service.invalidateForRevision("F001", 7L, "ORDER_CREATE")).isFalse();
        }
        verifyNoInteractions(repository);
    }

    @Test
    void missingEntityManagerFailsClosedAfterActorValidation() {
        ReflectionTestUtils.setField(service, "entityManager", null);
        try (var ignored = actor("F001", 7L)) {
            assertThatThrownBy(() -> service.invalidateForRevision("F001", 7L, "ORDER_CREATE"))
                    .isInstanceOf(IllegalStateException.class);
        }
        verifyNoInteractions(repository);
    }

    @Test
    void lateDraftMintIsRejectedWhenTheLockedSessionRevisionChanged() {
        ConversationSession session = draftSession("draft-1", "F001", 7L);
        String expected = ConversationService.draftRevisionFingerprint(session);
        when(conversationSessions.lockActorAdvisory("4:F001:7")).thenReturn(1);
        when(conversationSessions.findByFactoryIdAndUserIdForUpdate("F001", 7L))
                .thenReturn(List.of(session));
        when(conversationSessions.findBySessionIdAndFactoryIdAndUserIdForUpdate(
                "draft-1", "F001", 7L)).thenReturn(java.util.Optional.of(session));
        session.addCollectedParameter("sku", "changed");

        try (var ignored = actor("F001", 7L)) {
            assertThatThrownBy(() -> service.createDraftBoundToken(
                    boundRequest(), "draft-1", expected))
                    .isInstanceOf(IllegalStateException.class);
        }
        verify(repository, never()).save(any());
    }

    @Test
    void cancelledDraftBindingCannotBeClaimedAgain() {
        ConversationSession session = draftSession("draft-2", "F001", 7L);
        String revision = ConversationService.draftRevisionFingerprint(session);
        when(conversationSessions.lockActorAdvisory("4:F001:7")).thenReturn(1);
        when(conversationSessions.findByFactoryIdAndUserIdForUpdate("F001", 7L))
                .thenReturn(List.of(session));
        when(conversationSessions.findBySessionIdAndFactoryIdAndUserIdForUpdate(
                "draft-2", "F001", 7L)).thenReturn(java.util.Optional.of(session));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        IntentPreviewToken token;
        try (var ignored = actor("F001", 7L)) {
            token = service.createDraftBoundToken(boundRequest(), "draft-2", revision);
        }
        when(repository.findActiveByFactoryIdAndUserIdAndIntentCodeForUpdate(
                "F001", 7L, "ORDER_CREATE", TokenStatus.PENDING, TokenStatus.EXECUTING))
                .thenReturn(List.of(token));
        when(repository.cancelPendingToken(eq(token.getToken()), eq("F001"), eq(7L), any(), any(),
                eq(TokenStatus.PENDING), eq(TokenStatus.CANCELLED))).thenAnswer(invocation -> {
                    token.setStatus(TokenStatus.CANCELLED);
                    return 1;
                });
        when(repository.findByToken(token.getToken())).thenReturn(java.util.Optional.of(token));
        try (var ignored = actor("F001", 7L)) {
            assertThat(service.invalidateForRevision("F001", 7L, "ORDER_CREATE")).isTrue();
            assertThat(service.claimToken(token.getToken(), "F001", 7L).isSuccess()).isFalse();
        }
    }

    @Test
    void draftCollectedQuantityMustMatchMintParameters() {
        ConversationSession session = draftSession("draft-qty", "F001", 7L);
        session.addCollectedParameter("quantity", "50");
        String revision = ConversationService.draftRevisionFingerprint(session);
        when(conversationSessions.lockActorAdvisory("4:F001:7")).thenReturn(1);
        when(conversationSessions.findByFactoryIdAndUserIdForUpdate("F001", 7L))
                .thenReturn(List.of(session));
        when(conversationSessions.findBySessionIdAndFactoryIdAndUserIdForUpdate(
                "draft-qty", "F001", 7L)).thenReturn(java.util.Optional.of(session));

        try (var ignored = actor("F001", 7L)) {
            assertThatThrownBy(() -> service.createDraftBoundToken(
                    boundRequest(Map.of("quantity", 80)), "draft-qty", revision))
                    .isInstanceOf(IllegalStateException.class);
        }
        verify(repository, never()).save(any());
    }

    private AuthenticatedActor.ActorScope actor(String factory, long user) {
        return AuthenticatedActor.trustedAsyncScope(new AuthenticatedActor(
                AuthenticatedActorType.FACTORY_USER, user, factory, "sales_manager"));
    }

    private BoundTokenRequest boundRequest() {
        return boundRequest(Map.of("quantity", 1));
    }

    private BoundTokenRequest boundRequest(Map<String, Object> parameters) {
        return new BoundTokenRequest("F001", 7L, "tester", "ORDER_CREATE", "Create order",
                "order_create", "1.0", ToolExecutionMode.EXECUTE, "ORDER", "O1", "CREATE",
                parameters, Map.of("visible", "value"), Map.of(), 300);
    }

    private ConversationSession draftSession(String id, String factory, long user) {
        LocalDateTime now = LocalDateTime.now();
        return ConversationSession.builder().sessionId(id).factoryId(factory).userId(user)
                .originalInput("create order").currentRound(1).maxRounds(5)
                .status(ConversationSession.SessionStatus.ACTIVE)
                .sessionMode(ConversationSession.SessionMode.PARAMETER_COLLECTION)
                .knownIntentCode("ORDER_CREATE").requiredParametersJson("[]")
                .collectedParametersJson("{\"quantity\":\"1\"}").messagesJson("[]").candidatesJson("[]")
                .timeoutMinutes(10).createdAt(now).updatedAt(now).lastActiveAt(now).build();
    }

    private IntentPreviewToken token(String token, TokenStatus status, String factory, Long user) {
        return IntentPreviewToken.builder()
                .token(token)
                .factoryId(factory)
                .tenantId(factory)
                .userId(user)
                .intentCode("ORDER_CREATE")
                .status(status)
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .build();
    }
}
