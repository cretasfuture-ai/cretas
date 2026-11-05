package com.cretas.aims.service.impl;

import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.conversation.EntitySlot;
import com.cretas.aims.entity.conversation.ConversationMemory;
import com.cretas.aims.repository.conversation.ConversationMemoryRepository;
import com.cretas.aims.security.*;
import com.cretas.aims.service.*;
import com.cretas.aims.service.execution.SalesRankingConversationSupport;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.support.PersistenceAnnotationBeanPostProcessor;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL + Hibernate + Spring transactions. Never points at production.
 * CI without the isolated database explicitly skips; local receipt is required evidence. */
@SpringJUnitConfig(ConversationReadVersionContractTest.Config.class)
@EnabledIfEnvironmentVariable(named="CRETAS_SEMANTIC_JPA_URL", matches="jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/semantic_context_test[?]stringtype=unspecified")
class ConversationReadVersionContractTest {
    @Autowired ConversationMemoryService memory;
    @Autowired ConversationService conversations;
    @Autowired SalesRankingConversationSupport reads;
    @Autowired PlatformTransactionManager tx;
    static final String KEY = ConversationMemoryService.READ_QUERY_VERSION_KEY;
    static AuthenticatedActor.ActorScope actor() { return AuthenticatedActor.trustedAsyncScope(
            new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 7L, "F006", "sales_manager")); }
    String seed() {
        String session=UUID.randomUUID().toString(); memory.getOrCreateContext("F006",7L,session);
        memory.updateEntitySlot(session, EntitySlot.SlotType.READ_QUERY, slot("ORDER_LIST","v0")); return session;
    }
    static EntitySlot slot(String intent,String version) { return EntitySlot.builder().type(EntitySlot.SlotType.READ_QUERY)
            .id(intent).metadata(Map.of(KEY,version,"status","CONFIRMED")).build(); }

    @Test void staleVersionAndForeignActorCannotReplaceCurrentRead() {
        try (var ignored=actor()) {
            String session=seed();
            assertThat(memory.compareAndSetReadQuery("F006",7L,session,"v0",slot("ORDER_LIST","v1"))).isTrue();
            assertThat(memory.compareAndSetReadQuery("F006",7L,session,"v0",slot("MATERIAL_BATCH_QUERY","stale"))).isFalse();
            assertThat(memory.compareAndSetReadQuery("F999",99L,session,"v1",slot("ORDER_LIST","foreign"))).isFalse();
            assertThat(memory.getContext(session).getSlot(EntitySlot.SlotType.READ_QUERY).getMetadata()).containsEntry(KEY,"v1");
        }
    }

    @Test void concurrentOldWorkerCannotOverwriteCommittedNewVersion() throws Exception {
        String session;
        try (var ignored=actor()) { session=seed(); }
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first=workers.submit(() -> { try (var ignored=actor()) {
                return new TransactionTemplate(tx).execute(status -> {
                    boolean changed=memory.compareAndSetReadQuery("F006",7L,session,"v0",slot("ORDER_LIST","v1"));
                    locked.countDown();
                    try { if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("release timeout"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                    return changed;
                });
            }});
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            Future<Boolean> stale=workers.submit(() -> { try (var ignored=actor()) {
                return memory.compareAndSetReadQuery("F006",7L,session,"v0",slot("ORDER_LIST","late"));
            }});
            release.countDown();
            assertThat(first.get(5,TimeUnit.SECONDS)).isTrue();
            assertThat(stale.get(5,TimeUnit.SECONDS)).isFalse();
            try (var ignored=actor()) {
                assertThat(memory.getContext(session).getSlot(EntitySlot.SlotType.READ_QUERY).getMetadata()).containsEntry(KEY,"v1");
            }
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    @Test void cancellingConversationFailureRollsBackReadSlotInSameTransaction() {
        try (var ignored=actor()) {
            String session=seed();
            when(conversations.getSession("F006",7L,session)).thenReturn(Optional.empty());
            when(conversations.cancelConversation("F006",7L,session)).thenThrow(new IllegalStateException("controlled failure"));
            var request=IntentExecuteRequest.builder().sessionId(session)
                    .semanticPreviousRead(Map.of("id","ORDER_LIST",KEY,"v0")).build();
            assertThatThrownBy(() -> reads.cancelRead("F006",7L,request)).isInstanceOf(IllegalStateException.class);
            assertThat(memory.getContext(session).getSlot(EntitySlot.SlotType.READ_QUERY).getId()).isEqualTo("ORDER_LIST");
            assertThat(memory.getContext(session).getSlot(EntitySlot.SlotType.READ_QUERY).getMetadata()).containsEntry(KEY,"v0");
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false,true})
    void finishAndCancellationHaveOneTerminalWinner(boolean cancelFirst) throws Exception {
        String session;
        try (var ignored=actor()) { session=seed(); }
        var resultRequest=IntentExecuteRequest.builder().sessionId(session).semanticReadVersion("v0")
                .context(Map.of(KEY,"v0")).semanticParameters(Map.of()).build();
        var cancelRequest=IntentExecuteRequest.builder().sessionId(session)
                .semanticPreviousRead(Map.of("id","ORDER_LIST",KEY,"v0")).build();
        var finished=new CountDownLatch(1); var cancelled=new CountDownLatch(1);
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> finish=workers.submit(() -> {
                try (var ignored=actor()) {
                    if (cancelFirst && !cancelled.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("cancel timeout");
                    return reads.finishRead("F006",7L,resultRequest,"SUCCESS");
                } finally { finished.countDown(); }
            });
            Future<String> cancel=workers.submit(() -> {
                try (var ignored=actor()) {
                    if (!cancelFirst && !finished.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("finish timeout");
                    return reads.cancelRead("F006",7L,cancelRequest).getStatus();
                } finally { cancelled.countDown(); }
            });
            assertThat(finish.get(7,TimeUnit.SECONDS)).isEqualTo(!cancelFirst);
            assertThat(cancel.get(7,TimeUnit.SECONDS)).isEqualTo(cancelFirst ? "CANCELLED" : "TASK_CHANGED");
            try (var ignored=actor()) {
                var slot=memory.getContext(session).getSlot(EntitySlot.SlotType.READ_QUERY);
                assertThat(slot.getId()).isEqualTo(cancelFirst ? "CLEARED" : "ORDER_LIST");
                if (!cancelFirst) assertThat(slot.getMetadata()).containsEntry("taskStatus","COMPLETED");
                assertThat(slot.getMetadata().get(KEY)).isNotEqualTo("v0");
            }
        } finally { finished.countDown(); cancelled.countDown(); workers.shutdownNow(); }
    }

    @Configuration @EnableTransactionManagement
    static class Config {
        @Bean static PersistenceAnnotationBeanPostProcessor persistence() { return new PersistenceAnnotationBeanPostProcessor(); }
        @Bean DataSource dataSource() {
            String url=System.getenv("CRETAS_SEMANTIC_JPA_URL");
            if(url==null || !url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/semantic_context_test[?]stringtype=unspecified"))
                throw new IllegalStateException("Requires dedicated loopback semantic_context_test database");
            var ds=new DriverManagerDataSource(url,"semantic_test",""); ds.setDriverClassName("org.postgresql.Driver"); return ds;
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            var bean=new LocalContainerEntityManagerFactoryBean(); bean.setDataSource(ds);
            bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            bean.setManagedTypes(PersistenceManagedTypes.of(ConversationMemory.class.getName()));
            bean.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","create-drop","hibernate.show_sql","false")); return bean;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
        @Bean ConversationMemoryRepository repository(EntityManagerFactory factory) {
            return new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory)).getRepository(ConversationMemoryRepository.class);
        }
        @Bean ConversationMemoryService memory(ConversationMemoryRepository repository) { return new ConversationMemoryServiceImpl(repository,null); }
        @Bean ConversationService conversations() { return mock(ConversationService.class); }
        @Bean com.cretas.aims.service.consent.LlmEgressGuard egress() {
            return com.cretas.aims.service.consent.LlmEgressGuard.standalone();
        }
        @Bean SalesRankingConversationSupport reads(ConversationMemoryService memory, ConversationService conversations) {
            return new SalesRankingConversationSupport(memory,conversations);
        }
    }
}
