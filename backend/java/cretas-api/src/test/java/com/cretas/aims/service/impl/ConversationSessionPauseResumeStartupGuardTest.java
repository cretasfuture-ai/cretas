package com.cretas.aims.service.impl;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.entity.conversation.ConversationSession;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.repository.conversation.ConversationSessionRepository;
import com.cretas.aims.service.ConversationService;
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
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.support.PersistenceAnnotationBeanPostProcessor;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

/** Real PostgreSQL/Hibernate startup and lifecycle guard; never points at production. */
@SpringJUnitConfig(ConversationSessionPauseResumeStartupGuardTest.Config.class)
@EnabledIfEnvironmentVariable(
        named = "CRETAS_SEMANTIC_JPA_URL",
        matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/semantic_context_test[?]stringtype=unspecified")
class ConversationSessionPauseResumeStartupGuardTest {
    @Autowired ConversationSessionRepository repository;
    @Autowired ConversationService conversations;
    @PersistenceContext EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DashScopeClient llm;

    @Test
    void entityManagerIsRequiredAndInjectedForRefresh() throws Exception {
        assertThat(entityManager).isNotNull();
        Object target = org.springframework.test.util.AopTestUtils.getTargetObject(conversations);
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(target, "entityManager"))
                .isInstanceOf(EntityManager.class);
    }

    @Test
    void pausedRowsPersistWithOwnerIsolationAndQueriesStart() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String factory = "F-PAUSE-" + suffix;
        long user = 701L;
        ConversationSession paused = session("paused-" + suffix, factory, user,
                ConversationSession.SessionStatus.PAUSED, LocalDateTime.now());
        write(() -> repository.saveAndFlush(paused));

        assertThat(repository.findPausedByFactoryAndUser(factory, user))
                .extracting(ConversationSession::getSessionId)
                .containsExactly(paused.getSessionId());
        assertThat(repository.findPausedByFactoryAndUser(factory, user + 1)).isEmpty();
        assertThat(conversations.resumeConversation(factory, user + 1, paused.getSessionId())).isFalse();
        assertThat(conversations.resumeConversation(factory, user, paused.getSessionId())).isTrue();
        assertThat(read(paused.getSessionId()).getStatus())
                .isEqualTo(ConversationSession.SessionStatus.ACTIVE);
    }

    @Test
    void concurrentResumesHaveOneWinnerAndNeverCreateTwoActiveRows() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String factory = "F-PAUSE-CONCURRENT-" + suffix;
        long user = 702L;
        ConversationSession first = session("first-" + suffix, factory, user,
                ConversationSession.SessionStatus.PAUSED, LocalDateTime.now());
        ConversationSession second = session("second-" + suffix, factory, user,
                ConversationSession.SessionStatus.PAUSED, LocalDateTime.now());
        write(() -> repository.saveAllAndFlush(List.of(first, second)));

        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> resumeFirst = () -> conversations.resumeConversation(factory, user, first.getSessionId());
            Callable<Boolean> resumeSecond = () -> conversations.resumeConversation(factory, user, second.getSessionId());
            Future<Boolean> a = workers.submit(resumeFirst);
            Future<Boolean> b = workers.submit(resumeSecond);
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            workers.shutdownNow();
        }
        assertThat(repository.findActiveByFactoryAndUser(factory, user)).hasSize(1);
    }

    @Test
    void expirationAndSuccessRateExcludePausedFromTheDenominator() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String factory = "F-PAUSE-STATS-" + suffix;
        long user = 703L;
        LocalDateTime now = LocalDateTime.now();
        ConversationSession expired = session("expired-" + suffix, factory, user,
                ConversationSession.SessionStatus.PAUSED, now.minusMinutes(30));
        ConversationSession completed = session("completed-" + suffix, factory, user,
                ConversationSession.SessionStatus.COMPLETED, now);
        write(() -> repository.saveAllAndFlush(List.of(expired, completed)));

        assertThat(repository.findExpiredSessions(now.minusMinutes(10)))
                .extracting(ConversationSession::getSessionId).contains(expired.getSessionId());
        Object[] pausedRate = repository.getSuccessRate(factory, now.minusHours(1)).get(0);
        assertThat(((Number) pausedRate[0]).longValue()).isEqualTo(1L);
        assertThat(((Number) pausedRate[4]).longValue()).isEqualTo(1L);
        assertThat(conversations.processExpiredSessions()).isGreaterThanOrEqualTo(1);
        assertThat(read(expired.getSessionId()).getStatus())
                .isEqualTo(ConversationSession.SessionStatus.TIMEOUT);
    }

    @Test
    void concurrentStartsWithNoPriorRowsStillSerializeToOneActiveRow() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String factory = "F-PAUSE-EMPTY-" + suffix;
        long user = 704L;
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<ConversationService.ConversationResponse> first = workers.submit(
                    () -> conversations.startParameterCollection(factory, user, "intent-a", "A",
                            List.of(), List.of()));
            Future<ConversationService.ConversationResponse> second = workers.submit(
                    () -> conversations.startParameterCollection(factory, user, "intent-b", "B",
                            List.of(), List.of()));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }
        assertThat(repository.findActiveByFactoryAndUser(factory, user)).hasSize(1);
    }

    @Test
    void concurrentResumeAndCancelNeverReviveCancelledSession() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String factory = "F-PAUSE-RACE-" + suffix;
        long user = 705L;
        ConversationSession paused = session("race-" + suffix, factory, user,
                ConversationSession.SessionStatus.PAUSED, LocalDateTime.now());
        write(() -> repository.saveAndFlush(paused));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        boolean resumed = false;
        boolean cancelled = false;
        try {
            Future<Boolean> resume = workers.submit(
                    () -> conversations.resumeConversation(factory, user, paused.getSessionId()));
            Future<Boolean> cancel = workers.submit(
                    () -> conversations.cancelConversation(factory, user, paused.getSessionId()));
            resumed = resume.get(10, TimeUnit.SECONDS);
            cancelled = cancel.get(10, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }
        ConversationSession finalRow = read(paused.getSessionId());
        if (cancelled) {
            assertThat(finalRow.getStatus()).isEqualTo(ConversationSession.SessionStatus.CANCELLED);
        } else if (resumed) {
            assertThat(finalRow.getStatus()).isEqualTo(ConversationSession.SessionStatus.ACTIVE);
        }
        assertThat(repository.findActiveByFactoryAndUser(factory, user)).hasSizeLessThanOrEqualTo(1);
    }

    @Test
    void modelPhaseDoesNotHoldActorLockAndLateResultCannotOverwritePause() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String factory = "F-PAUSE-MODEL-" + suffix;
        long user = 706L;
        CountDownLatch enteredModel = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        when(llm.isAvailable()).thenReturn(true);
        when(llm.chatFast(anyString(), anyString())).thenAnswer(invocation -> {
            enteredModel.countDown();
            assertThat(releaseModel.await(10, TimeUnit.SECONDS)).isTrue();
            return "{\"is_confirmed\":false,\"confidence\":0.4,\"message\":\"继续澄清\"}";
        });

        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<ConversationService.ConversationResponse> start = workers.submit(
                    () -> conversations.startConversation(factory, user, "模型等待锁测试"));
            assertThat(enteredModel.await(10, TimeUnit.SECONDS)).isTrue();
            ConversationSession reserved = repository.findActiveByFactoryAndUser(factory, user)
                    .stream().findFirst().orElseThrow();
            long startedAt = System.nanoTime();
            assertThat(conversations.pauseConversation(factory, user, reserved.getSessionId())).isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                    .isLessThan(Duration.ofSeconds(3));
            releaseModel.countDown();
            start.get(10, TimeUnit.SECONDS);
            assertThat(read(reserved.getSessionId()).getStatus())
                    .isEqualTo(ConversationSession.SessionStatus.PAUSED);
        } finally {
            releaseModel.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void openEntityManagerViewStartRefreshesAfterCrossThreadCancel() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String factory = "F-PAUSE-OIV-START-" + suffix;
        long user = 710L;
        CountDownLatch enteredModel = new CountDownLatch(1);
        CountDownLatch sessionIdReady = new CountDownLatch(1);
        CountDownLatch managedChecked = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        AtomicReference<EntityManager> heldEntityManager = new AtomicReference<>();
        AtomicReference<String> sessionId = new AtomicReference<>();
        AtomicReference<Throwable> managedFailure = new AtomicReference<>();
        when(llm.isAvailable()).thenReturn(true);
        when(llm.chatFast(anyString(), anyString())).thenAnswer(invocation -> {
            enteredModel.countDown();
            assertThat(sessionIdReady.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                EntityManager bound = ((EntityManagerHolder) TransactionSynchronizationManager
                        .getResource(entityManagerFactory)).getEntityManager();
                assertThat(bound).isSameAs(heldEntityManager.get());
                ConversationSession loaded = bound.find(ConversationSession.class, sessionId.get());
                assertThat(loaded).isNotNull();
                assertThat(loaded.getStatus()).isEqualTo(ConversationSession.SessionStatus.ACTIVE);
                assertThat(bound.contains(loaded)).isTrue();
            } catch (Throwable failure) {
                managedFailure.set(failure);
            } finally {
                managedChecked.countDown();
            }
            assertThat(releaseModel.await(10, TimeUnit.SECONDS)).isTrue();
            return "{\"is_confirmed\":false,\"confidence\":0.4,\"message\":\"继续澄清\"}";
        });

        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            Future<ConversationService.ConversationResponse> start = workers.submit(
                    () -> {
                        EntityManager held = entityManagerFactory.createEntityManager();
                        heldEntityManager.set(held);
                        TransactionSynchronizationManager.bindResource(
                                entityManagerFactory, new EntityManagerHolder(held));
                        try {
                            return conversations.startConversation(factory, user, "OIV启动并发取消");
                        } finally {
                            unbindAndClose(held);
                        }
                    });
            assertThat(enteredModel.await(10, TimeUnit.SECONDS)).isTrue();
            String reservedId = repository.findActiveByFactoryAndUser(factory, user).get(0).getSessionId();
            sessionId.set(reservedId);
            sessionIdReady.countDown();
            assertThat(managedChecked.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(managedFailure.get()).isNull();
            assertThat(conversations.cancelConversation(factory, user, reservedId)).isTrue();
            releaseModel.countDown();
            ConversationService.ConversationResponse result = start.get(10, TimeUnit.SECONDS);
            assertThat(result.isCompleted()).isFalse();
            assertThat(read(reservedId).getStatus()).isEqualTo(ConversationSession.SessionStatus.CANCELLED);
        } finally {
            sessionIdReady.countDown();
            managedChecked.countDown();
            releaseModel.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void openEntityManagerViewContinueRefreshesAfterCrossThreadCancel() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String factory = "F-PAUSE-OIV-CONTINUE-" + suffix;
        long user = 711L;
        ConversationSession active = session("oiv-continue-" + suffix, factory, user,
                ConversationSession.SessionStatus.ACTIVE, LocalDateTime.now());
        write(() -> repository.saveAndFlush(active));

        CountDownLatch enteredModel = new CountDownLatch(1);
        CountDownLatch sessionIdReady = new CountDownLatch(1);
        CountDownLatch managedChecked = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        AtomicReference<EntityManager> heldEntityManager = new AtomicReference<>();
        AtomicReference<Throwable> managedFailure = new AtomicReference<>();
        when(llm.isAvailable()).thenReturn(true);
        when(llm.chatFast(anyString(), anyString())).thenAnswer(invocation -> {
            enteredModel.countDown();
            assertThat(sessionIdReady.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                EntityManager bound = ((EntityManagerHolder) TransactionSynchronizationManager
                        .getResource(entityManagerFactory)).getEntityManager();
                assertThat(bound).isSameAs(heldEntityManager.get());
                ConversationSession loaded = bound.find(ConversationSession.class, active.getSessionId());
                assertThat(loaded).isNotNull();
                assertThat(loaded.getStatus()).isEqualTo(ConversationSession.SessionStatus.ACTIVE);
                assertThat(bound.contains(loaded)).isTrue();
            } catch (Throwable failure) {
                managedFailure.set(failure);
            } finally {
                managedChecked.countDown();
            }
            assertThat(releaseModel.await(10, TimeUnit.SECONDS)).isTrue();
            return "{\"is_confirmed\":false,\"confidence\":0.4,\"message\":\"继续澄清\"}";
        });

        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            Future<ConversationService.ConversationResponse> continued = workers.submit(
                    () -> {
                        EntityManager held = entityManagerFactory.createEntityManager();
                        heldEntityManager.set(held);
                        TransactionSynchronizationManager.bindResource(
                                entityManagerFactory, new EntityManagerHolder(held));
                        try {
                            return conversations.continueConversation(factory, user, active.getSessionId(), "继续");
                        } finally {
                            unbindAndClose(held);
                        }
                    });
            assertThat(enteredModel.await(10, TimeUnit.SECONDS)).isTrue();
            sessionIdReady.countDown();
            assertThat(managedChecked.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(managedFailure.get()).isNull();
            assertThat(conversations.cancelConversation(factory, user, active.getSessionId())).isTrue();
            releaseModel.countDown();
            ConversationService.ConversationResponse result = continued.get(10, TimeUnit.SECONDS);
            assertThat(result.isCompleted()).isFalse();
            assertThat(read(active.getSessionId()).getStatus())
                    .isEqualTo(ConversationSession.SessionStatus.CANCELLED);
        } finally {
            sessionIdReady.countDown();
            managedChecked.countDown();
            releaseModel.countDown();
            workers.shutdownNow();
        }
    }

    private void unbindAndClose(EntityManager heldEntityManager) {
        if (TransactionSynchronizationManager.hasResource(entityManagerFactory)) {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory);
        }
        if (heldEntityManager != null && heldEntityManager.isOpen()) {
            heldEntityManager.close();
        }
    }

    @Test
    void normalModelResultCommitsWithoutFalseStaleCas() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String factory = "F-PAUSE-MODEL-OK-" + suffix;
        long user = 707L;
        when(llm.isAvailable()).thenReturn(true);
        when(llm.chatFast(anyString(), anyString())).thenReturn(
                "{\"is_confirmed\":false,\"confidence\":0.4,\"message\":\"继续澄清\"}");

        ConversationService.ConversationResponse result =
                conversations.startConversation(factory, user, "正常模型结果测试");

        assertThat(result.getStatus()).isEqualTo(ConversationSession.SessionStatus.ACTIVE);
        assertThat(result.getMessage()).isEqualTo("继续澄清");
        assertThat(repository.findActiveByFactoryAndUser(factory, user)).hasSize(1);
    }

    @Test
    void persistedLifecycleTimesRoundToPostgresMicros() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String factory = "F-PAUSE-MICROS-" + suffix;
        long user = 708L;
        LocalDateTime value = LocalDateTime.of(2026, 11, 6, 12, 0, 0, 123_456_789);
        ConversationSession row = session("micros-" + suffix, factory, user,
                ConversationSession.SessionStatus.PAUSED, value);
        row.setCreatedAt(value);
        row.setLastActiveAt(value);
        row.setCompletedAt(value);
        write(() -> repository.saveAndFlush(row));

        ConversationSession readBack = read(row.getSessionId());
        assertThat(readBack.getCreatedAt().getNano()).isEqualTo(123_457_000);
        assertThat(readBack.getLastActiveAt().getNano()).isEqualTo(123_457_000);
        assertThat(readBack.getCompletedAt().getNano()).isEqualTo(123_457_000);
    }

    @Test
    void repeatedNormalModelStartsDoNotFalseStale() {
        when(llm.isAvailable()).thenReturn(true);
        when(llm.chatFast(anyString(), anyString())).thenReturn(
                "{\"is_confirmed\":false,\"confidence\":0.4,\"message\":\"继续澄清\"}");
        for (int i = 0; i < 5; i++) {
            String factory = "F-PAUSE-REPEAT-" + UUID.randomUUID().toString().substring(0, 8);
            ConversationService.ConversationResponse result =
                    conversations.startConversation(factory, 709L + i, "重复模型结果测试");
            assertThat(result.getStatus()).isEqualTo(ConversationSession.SessionStatus.ACTIVE);
            assertThat(result.getMessage()).isEqualTo("继续澄清");
        }
    }

    private void write(Runnable operation) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> operation.run());
    }

    private ConversationSession read(String id) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> repository.findById(id).orElseThrow());
    }

    private ConversationSession session(String id, String factory, long user,
                                        ConversationSession.SessionStatus status,
                                        LocalDateTime activeAt) {
        return ConversationSession.builder().sessionId(id).factoryId(factory).userId(user)
                .originalInput("暂停测试").currentRound(1).maxRounds(5).status(status)
                .sessionMode(ConversationSession.SessionMode.INTENT_RECOGNITION)
                .timeoutMinutes(10).createdAt(activeAt).updatedAt(activeAt).lastActiveAt(activeAt)
                .messagesJson("[]").candidatesJson("[]").build();
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
            bean.setManagedTypes(PersistenceManagedTypes.of(ConversationSession.class.getName()));
            bean.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop",
                    "hibernate.show_sql", "false"));
            return bean;
        }

        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean ConversationSessionRepository repository(EntityManagerFactory factory) {
            return new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory))
                    .getRepository(ConversationSessionRepository.class);
        }

        @Bean AIIntentConfigRepository intentConfigRepository() {
            AIIntentConfigRepository repository = mock(AIIntentConfigRepository.class);
            when(repository.findActiveByFactoryIdWithPriority(anyString())).thenReturn(List.of());
            return repository;
        }

        @Bean DashScopeClient dashScopeClient() {
            return mock(DashScopeClient.class);
        }

        @Bean com.cretas.aims.service.consent.LlmEgressGuard egressGuard() {
            return com.cretas.aims.service.consent.LlmEgressGuard.standalone();
        }

        @Bean ConversationServiceImpl conversationService(ConversationSessionRepository repository,
                                                          AIIntentConfigRepository intentConfigRepository,
                                                          PlatformTransactionManager transactionManager,
                                                          DashScopeClient dashScopeClient) {
            return new ConversationServiceImpl(repository, intentConfigRepository, null, dashScopeClient,
                    transactionManager);
        }
    }
}
