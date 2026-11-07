package com.cretas.aims.support;

import com.cretas.aims.repository.conversation.ConversationSessionRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * H2-only test substitute for the PostgreSQL advisory owner mutex.
 *
 * The repository remains a real Spring Data repository and every method other than the one
 * PostgreSQL-specific mutex method delegates to the database. PostgreSQL contexts do not install
 * this substitute and therefore exercise the native advisory-lock query unchanged.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ConversationOwnerLockH2TestConfiguration {

    private static final ConcurrentMap<String, ReentrantLock> OWNER_LOCKS = new ConcurrentHashMap<>();

    @Bean
    static org.springframework.beans.factory.SmartInitializingSingleton h2OwnerLock(
            ConversationSessionRepository repository, DataSource dataSource) {
        return () -> {
            if (!isH2(dataSource)) {
                return;
            }
            doAnswer(invocation -> {
                if (!TransactionSynchronizationManager.isActualTransactionActive()
                        || !TransactionSynchronizationManager.isSynchronizationActive()) {
                    throw new IllegalStateException(
                            "H2 owner lock requires an active synchronized transaction");
                }
                String ownerKey = invocation.getArgument(0, String.class);
                ReentrantLock lock = OWNER_LOCKS.computeIfAbsent(ownerKey, ignored -> new ReentrantLock());
                lock.lock();
                try {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            lock.unlock();
                        }
                    });
                    return 1;
                } catch (RuntimeException e) {
                    lock.unlock();
                    throw e;
                }
            }).when(repository).lockActorAdvisory(anyString());
        };
    }

    private static boolean isH2(DataSource dataSource) {
        try (var connection = DataSourceUtils.getConnection(dataSource)) {
            return "H2".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName());
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to identify test database for owner-lock fixture", e);
        }
    }
}
