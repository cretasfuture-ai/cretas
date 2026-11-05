package com.cretas.aims.service.impl;

import com.cretas.aims.ai.tool.gateway.ToolCommandDigest;
import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.entity.conversation.ConversationSession;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.entity.intent.IntentPreviewToken.TokenStatus;
import com.cretas.aims.repository.conversation.ConversationSessionRepository;
import com.cretas.aims.repository.intent.IntentPreviewTokenRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.ConversationService;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.service.PreviewTokenService.BoundTokenRequest;
import com.cretas.aims.service.execution.ConversationDraftTransitionBoundary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.support.PersistenceAnnotationBeanPostProcessor;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/** Real PostgreSQL/Hibernate startup and row-lock lifecycle guard; never points at production. */
@SpringJUnitConfig(PreviewRevisionInvalidationStartupGuardTest.Config.class)
@EnabledIfEnvironmentVariable(
        named = "CRETAS_SEMANTIC_JPA_URL",
        matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/semantic_context_test[?]stringtype=unspecified")
class PreviewRevisionInvalidationStartupGuardTest {
    @Autowired IntentPreviewTokenRepository repository;
    @Autowired ConversationSessionRepository conversationSessions;
    @Autowired PreviewTokenService previewTokens;
    @Autowired PlatformTransactionManager transactionManager;
    @PersistenceContext EntityManager entityManager;
    @Autowired ObjectMapper objectMapper;
    @Autowired ConversationDraftTransitionBoundary transitionBoundary;

    @Test
    void transactionProxyTargetHasRequiredPersistenceContext() throws Exception {
        Object target = AopTestUtils.getTargetObject(previewTokens);
        assertThat(ReflectionTestUtils.getField(target, "entityManager")).isNotNull();
    }

    @Test
    void pendingIsCancelledBeforeClaimAndClaimReturnsZero() {
        IntentPreviewToken token = token("pending-" + suffix(), "F-REV", 701L, TokenStatus.PENDING);
        write(() -> repository.saveAndFlush(token));

        try (var ignored = actor("F-REV", 701L)) {
            assertThat(previewTokens.invalidateForRevision("F-REV", 701L, "ORDER_CREATE")).isTrue();
        }
        assertThat(read(token.getToken()).getStatus()).isEqualTo(TokenStatus.CANCELLED);

        int claimed = writeResult(() -> repository.claimForExecution(
                token.getToken(), "F-REV", "F-REV", 701L, token.getCommandDigest(),
                token.getParametersHash(), token.getToolName(), token.getDescriptorVersion(),
                token.getExecutionMode(), "late-claim", LocalDateTime.now(), LocalDateTime.now(),
                TokenStatus.PENDING, TokenStatus.EXECUTING));
        assertThat(claimed).isZero();
    }

    @Test
    void claimWinnerBlocksRevisionInvalidationAndForeignOwnerCannotCancel() {
        IntentPreviewToken token = token("executing-" + suffix(), "F-REV", 702L, TokenStatus.PENDING);
        write(() -> repository.saveAndFlush(token));
        try (var ignored = actor("F-REV", 702L)) {
            assertThat(previewTokens.claimToken(token.getToken(), "F-REV", 702L, token.getCommandDigest())
                    .isSuccess()).isTrue();
            assertThat(previewTokens.invalidateForRevision("F-REV", 702L, "ORDER_CREATE")).isFalse();
        }
        assertThat(read(token.getToken()).getStatus()).isEqualTo(TokenStatus.EXECUTING);

        IntentPreviewToken foreign = token("foreign-" + suffix(), "F-REV", 703L, TokenStatus.PENDING);
        write(() -> repository.saveAndFlush(foreign));
        try (var ignored = actor("F-REV", 702L)) {
            assertThat(previewTokens.invalidateForRevision("F-REV", 702L, "ORDER_CREATE")).isFalse();
        }
        assertThat(read(foreign.getToken()).getStatus()).isEqualTo(TokenStatus.PENDING);
    }

    @Test
    void staleManagedPendingSnapshotIsRefreshedBeforeRevisionDecision() throws Exception {
        IntentPreviewToken token = token("stale-" + suffix(), "F-REV", 704L, TokenStatus.PENDING);
        write(() -> repository.saveAndFlush(token));

        CountDownLatch outerRead = new CountDownLatch(1);
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> result = workers.submit(() -> new TransactionTemplate(transactionManager)
                    .execute(status -> {
                        try (var ignored = actor("F-REV", 704L)) {
                            IntentPreviewToken managed = repository.findByToken(token.getToken()).orElseThrow();
                            assertThat(managed.getStatus()).isEqualTo(TokenStatus.PENDING);
                            outerRead.countDown();
                            return awaitClaimThenInvalidate(token);
                        }
                    }));
            assertThat(outerRead.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(result.get(7, TimeUnit.SECONDS)).isFalse();
        } finally {
            workers.shutdownNow();
        }
        assertThat(read(token.getToken()).getStatus()).isEqualTo(TokenStatus.EXECUTING);
    }

    @Test
    void simultaneousClaimAndRevisionInvalidationHaveExactlyOneWinner() throws Exception {
        IntentPreviewToken token = token("race-" + suffix(), "F-REV", 705L, TokenStatus.PENDING);
        write(() -> repository.saveAndFlush(token));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> claim = workers.submit(() -> {
                try (var ignored = actor("F-REV", 705L)) {
                    ready.countDown();
                    await(start);
                    return previewTokens.claimToken(token.getToken(), "F-REV", 705L,
                            token.getCommandDigest()).isSuccess();
                }
            });
            Future<Boolean> invalidate = workers.submit(() -> {
                try (var ignored = actor("F-REV", 705L)) {
                    ready.countDown();
                    await(start);
                    return previewTokens.invalidateForRevision("F-REV", 705L, "ORDER_CREATE");
                }
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            boolean claimWon = claim.get(7, TimeUnit.SECONDS);
            boolean invalidateWon = invalidate.get(7, TimeUnit.SECONDS);
            assertThat(claimWon).isNotEqualTo(invalidateWon);
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
        assertThat(read(token.getToken()).getStatus())
                .isIn(TokenStatus.EXECUTING, TokenStatus.CANCELLED);
    }

    @Test
    void realFirstCancellationRollsBackWhenControlledSecondDmlFails() {
        IntentPreviewToken first = token("rollback-a-" + suffix(), "F-REV", 706L, TokenStatus.PENDING);
        IntentPreviewToken second = token("rollback-b-" + suffix(), "F-REV", 706L, TokenStatus.PENDING);
        write(() -> repository.saveAllAndFlush(java.util.List.of(first, second)));

        IntentPreviewTokenRepository faulty = mock(IntentPreviewTokenRepository.class, delegatesTo(repository));
        AtomicInteger cancellationCalls = new AtomicInteger();
        doAnswer(invocation -> {
            if (cancellationCalls.incrementAndGet() == 1) {
                int updated = repository.cancelPendingToken(
                        invocation.getArgument(0, String.class),
                        invocation.getArgument(1, String.class),
                        invocation.getArgument(2, Long.class),
                        invocation.getArgument(3, String.class),
                        invocation.getArgument(4, LocalDateTime.class),
                        invocation.getArgument(5, TokenStatus.class),
                        invocation.getArgument(6, TokenStatus.class));
                assertThat(updated).isEqualTo(1);
                return updated;
            }
            throw new IllegalStateException("controlled second cancellation failure");
        }).when(faulty).cancelPendingToken(anyString(), anyString(), any(), anyString(), any(),
                eq(TokenStatus.PENDING), eq(TokenStatus.CANCELLED));
        PreviewTokenServiceImpl faultyService = new PreviewTokenServiceImpl(faulty, objectMapper);
        ReflectionTestUtils.setField(faultyService, "entityManager", entityManager);
        ReflectionTestUtils.setField(faultyService, "conversationSessionRepository", conversationSessions);

        try (var ignored = actor("F-REV", 706L)) {
            assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> faultyService.invalidateForRevision(
                            "F-REV", 706L, "ORDER_CREATE")))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(cancellationCalls.get()).isEqualTo(2);
        assertThat(read(first.getToken()).getStatus()).isEqualTo(TokenStatus.PENDING);
        assertThat(read(second.getToken()).getStatus()).isEqualTo(TokenStatus.PENDING);
    }

    @Test
    void lateDraftMintIsRejectedAfterTheSessionRevisionChanges() {
        ConversationSession session = draftSession("late-mint-" + suffix(), "F-REV", 707L);
        session = persistAndReload(session);
        String expected = ConversationService.draftRevisionFingerprint(session);
        String sessionId = session.getSessionId();
        session.addCollectedParameter("sku", "changed");
        session = persistAndReload(session);

        try (var ignored = actor("F-REV", 707L)) {
            assertThatThrownBy(() -> previewTokens.createDraftBoundToken(
                    boundRequest("F-REV", 707L), sessionId, expected))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(repository.findByFactoryIdAndUserIdAndStatus(
                "F-REV", 707L, TokenStatus.PENDING)).isEmpty();
    }

    @Test
    void draftCollectedQuantityFiftyCannotMintWithCallerQuantityEighty() {
        ConversationSession session = draftSession("qty-mismatch-" + suffix(), "F-REV", 711L);
        session.addCollectedParameter("quantity", "50");
        session = persistAndReload(session);
        String revision = ConversationService.draftRevisionFingerprint(session);
        String sessionId = session.getSessionId();

        try (var ignored = actor("F-REV", 711L)) {
            assertThatThrownBy(() -> previewTokens.createDraftBoundToken(
                    boundRequest("F-REV", 711L, Map.of("quantity", 80)), sessionId, revision))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(repository.findByFactoryIdAndUserIdAndStatus(
                "F-REV", 711L, TokenStatus.PENDING)).isEmpty();
    }

    @Test
    void draftCollectedBusinessKeysRejectUnexpectedSupplierExtra() {
        ConversationSession session = draftSession("extra-key-" + suffix(), "F-REV", 713L);
        session = persistAndReload(session);
        String revision = ConversationService.draftRevisionFingerprint(session);
        String sessionId = session.getSessionId();

        try (var ignored = actor("F-REV", 713L)) {
            assertThatThrownBy(() -> previewTokens.createDraftBoundToken(
                    boundRequest("F-REV", 713L, Map.of("quantity", 1, "supplierId", "S-1")),
                    sessionId, revision))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(repository.findByFactoryIdAndUserIdAndStatus(
                "F-REV", 713L, TokenStatus.PENDING)).isEmpty();
    }

    @Test
    void cancelledDraftBindingCannotBeClaimedAgain() {
        ConversationSession session = draftSession("cancelled-" + suffix(), "F-REV", 708L);
        session = persistAndReload(session);
        String revision = ConversationService.draftRevisionFingerprint(session);
        IntentPreviewToken token;
        try (var ignored = actor("F-REV", 708L)) {
            token = previewTokens.createDraftBoundToken(
                    boundRequest("F-REV", 708L), session.getSessionId(), revision);
            assertThat(previewTokens.invalidateForRevision("F-REV", 708L, "ORDER_CREATE")).isTrue();
            assertThat(previewTokens.claimToken(token.getToken(), "F-REV", 708L).isSuccess()).isFalse();
        }
        assertThat(read(token.getToken()).getStatus()).isEqualTo(TokenStatus.CANCELLED);
    }

    @Test
    void trustedCompletedDraftCanMintFirstPreviewButCancellationStillBlocksClaim() {
        ConversationSession session = draftSession("completed-" + suffix(), "F-REV", 709L);
        session.setStatus(ConversationSession.SessionStatus.COMPLETED);
        session.setCompletedAt(LocalDateTime.now());
        session = persistAndReload(session);
        String revision = ConversationService.draftRevisionFingerprint(session);
        IntentPreviewToken token;
        try (var ignored = actor("F-REV", 709L)) {
            token = previewTokens.createDraftBoundToken(
                    boundRequest("F-REV", 709L), session.getSessionId(), revision);
            assertThat(previewTokens.invalidateForRevision("F-REV", 709L, "ORDER_CREATE")).isTrue();
            assertThat(previewTokens.claimToken(token.getToken(), "F-REV", 709L).isSuccess()).isFalse();
        }
        assertThat(read(token.getToken()).getStatus()).isEqualTo(TokenStatus.CANCELLED);
    }

    @Test
    void completedDraftInvalidationRequiresAnExactLivePendingTicket() throws Exception {
        ConversationSession session = draftSession("completed-invalidate-" + suffix(), "F-REV", 714L);
        session.setStatus(ConversationSession.SessionStatus.COMPLETED);
        session.setCompletedAt(LocalDateTime.now());
        session = persistAndReload(session);
        String revision = ConversationService.draftRevisionFingerprint(session);

        try (var ignored = actor("F-REV", 714L)) {
            assertThat(previewTokens.invalidateDraftForRevision(
                    "F-REV", 714L, "ORDER_CREATE", session.getSessionId(), revision)).isFalse();
        }

        IntentPreviewToken expired = token("completed-expired-" + suffix(), "F-REV", 714L,
                TokenStatus.PENDING);
        expired.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        expired.setCurrentValues(objectMapper.writeValueAsString(Map.of(
                "_conversationDraft", Map.of("sessionId", session.getSessionId(), "revision", revision))));
        write(() -> repository.saveAndFlush(expired));
        try (var ignored = actor("F-REV", 714L)) {
            assertThat(previewTokens.invalidateDraftForRevision(
                    "F-REV", 714L, "ORDER_CREATE", session.getSessionId(), revision)).isFalse();
            assertThat(previewTokens.hasPendingDraftPreview(
                    "F-REV", 714L, session.getSessionId(), revision)).isFalse();
        }
        assertThat(read(expired.getToken()).getStatus()).isEqualTo(TokenStatus.PENDING);

        IntentPreviewToken live = token("completed-live-" + suffix(), "F-REV", 714L,
                TokenStatus.PENDING);
        live.setCurrentValues(objectMapper.writeValueAsString(Map.of(
                "_conversationDraft", Map.of("sessionId", session.getSessionId(), "revision", revision))));
        write(() -> repository.saveAndFlush(live));
        try (var ignored = actor("F-REV", 714L)) {
            assertThat(previewTokens.invalidateDraftForRevision(
                    "F-REV", 714L, "ORDER_CREATE", session.getSessionId(), revision)).isTrue();
        }
        assertThat(read(live.getToken()).getStatus()).isEqualTo(TokenStatus.CANCELLED);
        assertThat(read(expired.getToken()).getStatus()).isEqualTo(TokenStatus.PENDING);
    }

    @Test
    void draftInvalidationDoesNotTouchSiblingDraftOfTheSameIntent() throws Exception {
        ConversationSession firstSession = draftSession("sibling-a-" + suffix(), "F-REV", 710L);
        ConversationSession secondSession = draftSession("sibling-b-" + suffix(), "F-REV", 710L);
        var siblingSessions = java.util.List.of(firstSession, secondSession);
        write(() -> conversationSessions.saveAllAndFlush(siblingSessions));
        firstSession = readSession(firstSession.getSessionId(), "F-REV", 710L);
        secondSession = readSession(secondSession.getSessionId(), "F-REV", 710L);
        String firstRevision = ConversationService.draftRevisionFingerprint(firstSession);
        String secondRevision = ConversationService.draftRevisionFingerprint(secondSession);
        IntentPreviewToken first = token("sibling-token-a-" + suffix(), "F-REV", 710L, TokenStatus.PENDING);
        first.setCurrentValues(objectMapper.writeValueAsString(Map.of(
                "_conversationDraft", Map.of("sessionId", firstSession.getSessionId(), "revision", firstRevision))));
        IntentPreviewToken second = token("sibling-token-b-" + suffix(), "F-REV", 710L, TokenStatus.PENDING);
        second.setCurrentValues(objectMapper.writeValueAsString(Map.of(
                "_conversationDraft", Map.of("sessionId", secondSession.getSessionId(), "revision", secondRevision))));
        write(() -> repository.saveAllAndFlush(java.util.List.of(first, second)));

        try (var ignored = actor("F-REV", 710L)) {
            assertThat(previewTokens.invalidateDraftForRevision(
                    "F-REV", 710L, "ORDER_CREATE", firstSession.getSessionId(), firstRevision)).isTrue();
        }
        assertThat(read(first.getToken()).getStatus()).isEqualTo(TokenStatus.CANCELLED);
        assertThat(read(second.getToken()).getStatus()).isEqualTo(TokenStatus.PENDING);
    }

    @Test
    void compoundBoundaryRollsBackDraftInvalidationAndSessionMutationTogether() {
        ConversationSession session = draftSession("boundary-" + suffix(), "F-REV", 712L);
        session = persistAndReload(session);
        String revision = ConversationService.draftRevisionFingerprint(session);
        String sessionId = session.getSessionId();
        IntentPreviewToken token;
        try (var ignored = actor("F-REV", 712L)) {
            token = previewTokens.createDraftBoundToken(
                    boundRequest("F-REV", 712L), sessionId, revision);
        }

        try (var ignored = actor("F-REV", 712L)) {
            assertThatThrownBy(() -> transitionBoundary.run(() -> {
                if (!previewTokens.invalidateDraftForRevision(
                        "F-REV", 712L, "ORDER_CREATE", sessionId, revision)) {
                    throw new IllegalStateException("draft invalidation did not win");
                }
                ConversationSession locked = conversationSessions
                        .findBySessionIdAndFactoryIdAndUserIdForUpdate(
                                sessionId, "F-REV", 712L).orElseThrow();
                locked.addCollectedParameter("quantity", "50");
                conversationSessions.saveAndFlush(locked);
                throw new IllegalStateException("controlled boundary failure");
            })).isInstanceOf(IllegalStateException.class);
        }
        assertThat(read(token.getToken()).getStatus()).isEqualTo(TokenStatus.PENDING);
        ConversationSession restored = new TransactionTemplate(transactionManager)
                .execute(status -> conversationSessions.findBySessionIdAndFactoryIdAndUserId(
                        sessionId, "F-REV", 712L).orElseThrow());
        assertThat(restored.getCollectedParameters()).containsEntry("quantity", "1");
    }

    private boolean awaitClaimThenInvalidate(IntentPreviewToken token) {
        ExecutorService claimer = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> claim = claimer.submit(() -> {
                try (var ignored = actor("F-REV", 704L)) {
                    return previewTokens.claimToken(token.getToken(), "F-REV", 704L,
                            token.getCommandDigest()).isSuccess();
                }
            });
            try {
                assertThat(claim.get(7, TimeUnit.SECONDS)).isTrue();
            } catch (Exception e) {
                throw new IllegalStateException("claim worker failed", e);
            }
            try (var ignored = actor("F-REV", 704L)) {
                return previewTokens.invalidateForRevision("F-REV", 704L, "ORDER_CREATE");
            }
        } finally {
            claimer.shutdownNow();
        }
    }

    private AuthenticatedActor.ActorScope actor(String factory, long user) {
        return AuthenticatedActor.trustedAsyncScope(new AuthenticatedActor(
                AuthenticatedActorType.FACTORY_USER, user, factory, "sales_manager"));
    }

    private void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("race start timeout");
        }
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private IntentPreviewToken token(String token, String factory, long user, TokenStatus status) {
        JsonNode parameters = new ObjectMapper().createObjectNode().put("quantity", 1);
        return IntentPreviewToken.builder().token(token).factoryId(factory).tenantId(factory)
                .userId(user).username("semantic-test").intentCode("ORDER_CREATE")
                .intentName("create order").toolName("order_create").descriptorVersion("1.0")
                .executionMode(ToolExecutionMode.EXECUTE)
                .parametersHash(ToolCommandDigest.parametersHash(parameters))
                .commandDigest(ToolCommandDigest.commandDigest(
                        factory, user, "order_create", "1.0", ToolExecutionMode.EXECUTE, parameters))
                .previewData(parameters.toString()).status(status)
                .expiresAt(LocalDateTime.now().plusMinutes(5)).build();
    }

    private BoundTokenRequest boundRequest(String factory, long user) {
        return boundRequest(factory, user, Map.of("quantity", 1));
    }

    private BoundTokenRequest boundRequest(String factory, long user, Map<String, Object> parameters) {
        return new BoundTokenRequest(factory, user, "semantic-test", "ORDER_CREATE", "create order",
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

    private void write(Runnable operation) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> operation.run());
    }

    private ConversationSession persistAndReload(ConversationSession session) {
        write(() -> conversationSessions.saveAndFlush(session));
        return readSession(session.getSessionId(), session.getFactoryId(), session.getUserId());
    }

    private ConversationSession readSession(String sessionId, String factoryId, long userId) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> conversationSessions.findBySessionIdAndFactoryIdAndUserId(
                        sessionId, factoryId, userId).orElseThrow());
    }

    private int writeResult(java.util.function.Supplier<Integer> operation) {
        return new TransactionTemplate(transactionManager).execute(status -> operation.get());
    }

    private IntentPreviewToken read(String token) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> repository.findByToken(token).orElseThrow());
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean static PersistenceAnnotationBeanPostProcessor persistenceAnnotationBeanPostProcessor() {
            return new PersistenceAnnotationBeanPostProcessor();
        }

        @Bean DataSource dataSource() {
            String url = System.getenv("CRETAS_SEMANTIC_JPA_URL");
            if (url == null || !url.matches(
                    "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/semantic_context_test[?]stringtype=unspecified")) {
                throw new IllegalStateException("Requires dedicated loopback semantic_context_test database");
            }
            DriverManagerDataSource dataSource = new DriverManagerDataSource(url, "semantic_test", "");
            dataSource.setDriverClassName("org.postgresql.Driver");
            return dataSource;
        }

        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean bean = new LocalContainerEntityManagerFactoryBean();
            bean.setDataSource(dataSource);
            bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            bean.setManagedTypes(PersistenceManagedTypes.of(
                    IntentPreviewToken.class.getName(), ConversationSession.class.getName()));
            bean.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop",
                    "hibernate.show_sql", "false"));
            return bean;
        }

        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean IntentPreviewTokenRepository repository(EntityManagerFactory factory) {
            return new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory))
                    .getRepository(IntentPreviewTokenRepository.class);
        }

        @Bean ConversationSessionRepository conversationSessionRepository(EntityManagerFactory factory) {
            return new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory))
                    .getRepository(ConversationSessionRepository.class);
        }

        @Bean ConversationDraftTransitionBoundary conversationDraftTransitionBoundary() {
            return new ConversationDraftTransitionBoundary();
        }

        @Bean ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean PreviewTokenService previewTokens(IntentPreviewTokenRepository repository,
                                                ObjectMapper objectMapper) {
            return new PreviewTokenServiceImpl(repository, objectMapper);
        }
    }
}
