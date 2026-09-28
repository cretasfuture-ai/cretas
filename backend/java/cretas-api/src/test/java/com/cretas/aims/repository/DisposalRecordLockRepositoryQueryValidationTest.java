package com.cretas.aims.repository;

import com.cretas.aims.entity.DisposalRecord;
import com.cretas.aims.entity.Factory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** PostgreSQL row-lock gate for competing disposal approval and rejection. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class DisposalRecordLockRepositoryQueryValidationTest {
    @Autowired private DisposalRecordRepository records;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void rejectedRecordIsAbsentFromPendingQueueAndCount() {
        Factory factory = new Factory();
        factory.setId("DISPOSAL-PENDING-FACTORY");
        factory.setName("Disposal pending fixture factory");
        entityManager.persist(factory);
        DisposalRecord rejected = new DisposalRecord();
        rejected.setFactoryId(factory.getId());
        rejected.setDisposalType("SCRAP");
        rejected.setDisposalQuantity(BigDecimal.ONE);
        rejected.reject(null, "test", "rejected");
        records.saveAndFlush(rejected);

        assertThat(records.findPendingApprovals(factory.getId())).isEmpty();
        assertThat(records.countByFactoryIdAndApprovalStatus(factory.getId(), "PENDING")).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void secondDecisionWaitsForFirstAndSeesCommittedStatus() throws Exception {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        Long id = transactions.execute(status -> {
            Factory factory = new Factory();
            factory.setId("DISPOSAL-LOCK-FACTORY");
            factory.setName("Disposal lock fixture factory");
            entityManager.persist(factory);
            DisposalRecord record = new DisposalRecord();
            record.setFactoryId(factory.getId());
            record.setDisposalType("SCRAP");
            record.setDisposalQuantity(BigDecimal.ONE);
            return records.saveAndFlush(record).getId();
        });

        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> transactions.execute(status -> {
                DisposalRecord record = records.findByIdAndFactoryIdForUpdate(id, "DISPOSAL-LOCK-FACTORY")
                        .orElseThrow();
                firstLocked.countDown();
                try {
                    if (!allowCommit.await(5, TimeUnit.SECONDS)) throw new AssertionError("timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                record.approve(null, "test");
                records.saveAndFlush(record);
                return record.getStatus();
            }));
            assertThat(firstLocked.await(5, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> transactions.execute(status -> {
                secondStarted.countDown();
                return records.findByIdAndFactoryIdForUpdate(id, "DISPOSAL-LOCK-FACTORY")
                        .orElseThrow().getStatus();
            }));
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(200);
            assertThat(second.isDone()).isFalse();
            allowCommit.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("APPROVED");
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("APPROVED");
        } finally {
            allowCommit.countDown();
            pool.shutdownNow();
            transactions.executeWithoutResult(status -> {
                records.deleteById(id);
                entityManager.flush();
                entityManager.createQuery("DELETE FROM Factory f WHERE f.id = :id")
                        .setParameter("id", "DISPOSAL-LOCK-FACTORY").executeUpdate();
            });
        }
    }
}
