package com.cretas.aims.service.impl;

import com.cretas.aims.entity.conversation.ConversationSession;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.repository.conversation.ConversationSessionRepository;
import com.cretas.aims.repository.intent.IntentPreviewTokenRepository;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Always-on Hibernate proxy/query startup. PostgreSQL locking is separately tested by the opt-in guards. */
@SpringJUnitConfig(SemanticConversationRepositoryQueryValidationTest.Config.class)
class SemanticConversationRepositoryQueryValidationTest {
    @Autowired ConversationSessionRepository conversations;
    @Autowired IntentPreviewTokenRepository previews;
    @Autowired PlatformTransactionManager transactions;

    @Test void declaredQueriesStartAndOwnedPausedRowsRemainIsolated() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var row=ConversationSession.builder().sessionId("semantic-query-startup").factoryId("F-STARTUP")
                    .userId(901L).originalInput("query startup").status(ConversationSession.SessionStatus.PAUSED)
                    .lastActiveAt(LocalDateTime.now()).timeoutMinutes(10).build();
            conversations.saveAndFlush(row);
            assertThat(conversations.findPausedByFactoryAndUser("F-STARTUP",901L)).hasSize(1);
            assertThat(conversations.findPausedByFactoryAndUser("F-OTHER",901L)).isEmpty();
            assertThat(conversations.findBySessionIdAndFactoryIdAndUserIdForUpdate(row.getSessionId(),"F-STARTUP",901L)).isPresent();
            assertThat(previews.findByToken("absent")).isEmpty();
        });
    }

    @Configuration @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource(){return new DriverManagerDataSource("jdbc:h2:mem:semantic-query-startup;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");}
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source){
            var f=new LocalContainerEntityManagerFactoryBean();f.setDataSource(source);f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            f.setManagedTypes(PersistenceManagedTypes.of(ConversationSession.class.getName(),IntentPreviewToken.class.getName()));
            f.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","create-drop"));return f;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory){return new JpaTransactionManager(factory);}
        @Bean ConversationSessionRepository conversations(EntityManagerFactory factory){return new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory)).getRepository(ConversationSessionRepository.class);}
        @Bean IntentPreviewTokenRepository previews(EntityManagerFactory factory){return new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory)).getRepository(IntentPreviewTokenRepository.class);}
    }
}
