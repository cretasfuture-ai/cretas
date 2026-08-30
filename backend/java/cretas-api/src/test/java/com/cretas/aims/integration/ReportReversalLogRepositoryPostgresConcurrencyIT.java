package com.cretas.aims.integration;

import com.cretas.aims.entity.ReportReversalLog;
import com.cretas.aims.repository.ReportReversalLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in PostgreSQL proof that two decisions against one reversal log serialize at the row lock.
 * The URL guard only permits an explicitly disposable local database.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
@EnableJpaRepositories(basePackageClasses = ReportReversalLogRepository.class)
class ReportReversalLogRepositoryPostgresConcurrencyIT {

    private static final String FACTORY = "REVERSAL-PG-F001";

    @Autowired
    private ReportReversalLogRepository repository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void createExactTargetTable() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS report_reversal_logs CASCADE");
        jdbcTemplate.execute("""
                CREATE TABLE report_reversal_logs (
                    id BIGSERIAL PRIMARY KEY,
                    factory_id VARCHAR(50) NOT NULL,
                    batch_id BIGINT NOT NULL,
                    plan_id VARCHAR(191),
                    reversal_scope VARCHAR(20) NOT NULL,
                    submitted_by BIGINT NOT NULL,
                    approved_by BIGINT,
                    reason VARCHAR(500),
                    status VARCHAR(20) NOT NULL,
                    reverted_txn_ids JSONB,
                    approved_at TIMESTAMP,
                    fast_path BOOLEAN,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL,
                    deleted_at TIMESTAMP,
                    CONSTRAINT uq_reversal_batch UNIQUE (batch_id, reversal_scope)
                )
                """);
        jdbcTemplate.execute("CREATE INDEX idx_rrl_factory_batch "
                + "ON report_reversal_logs (factory_id, batch_id)");
        jdbcTemplate.execute("CREATE INDEX idx_rrl_status "
                + "ON report_reversal_logs (factory_id, status)");
    }

    @AfterEach
    void dropExactTargetTable() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS report_reversal_logs CASCADE");
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> requiredEnv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set when PostgreSQL verification is enabled");
        }
        return value;
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentApproveAndRejectHaveExactlyOneCommittedWinner() throws Exception {
        Long id = new TransactionTemplate(transactionManager).execute(status -> {
            ReportReversalLog log = ReportReversalLog.builder()
                    .factoryId(FACTORY).batchId(1001L).submittedBy(7L)
                    .status(ReportReversalLog.ReversalStatus.PENDING).build();
            return repository.saveAndFlush(log).getId();
        });

        CountDownLatch firstLocked = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ReportReversalLog.ReversalStatus> first = pool.submit(() ->
                    new TransactionTemplate(transactionManager).execute(status -> {
                        ReportReversalLog log = repository.findByIdAndFactoryIdForUpdate(id, FACTORY)
                                .orElseThrow();
                        firstLocked.countDown();
                        log.setStatus(ReportReversalLog.ReversalStatus.APPROVED);
                        repository.save(log);
                        return log.getStatus();
                    }));
            Future<ReportReversalLog.ReversalStatus> second = pool.submit(() -> {
                firstLocked.await(10, TimeUnit.SECONDS);
                return new TransactionTemplate(transactionManager).execute(status -> {
                    ReportReversalLog log = repository.findByIdAndFactoryIdForUpdate(id, FACTORY)
                            .orElseThrow();
                    if (log.getStatus() == ReportReversalLog.ReversalStatus.PENDING) {
                        log.setStatus(ReportReversalLog.ReversalStatus.REJECTED);
                        repository.save(log);
                    }
                    return log.getStatus();
                });
            });

            assertThat(first.get(20, TimeUnit.SECONDS))
                    .isEqualTo(ReportReversalLog.ReversalStatus.APPROVED);
            assertThat(second.get(20, TimeUnit.SECONDS))
                    .isEqualTo(ReportReversalLog.ReversalStatus.APPROVED);
            ReportReversalLog committed = new TransactionTemplate(transactionManager)
                    .execute(status -> repository.findById(id).orElseThrow());
            assertThat(committed.getStatus()).isEqualTo(ReportReversalLog.ReversalStatus.APPROVED);
        } finally {
            pool.shutdownNow();
        }
    }
}
