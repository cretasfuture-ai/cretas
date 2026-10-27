package com.cretas.aims.integration;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.repository.ProductionBatchRepository;
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
 * Opt-in disposable PostgreSQL proof for the batch terminal-state row lock.
 * The target guard is mandatory so this test can never point at production.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
@EnableJpaRepositories(basePackageClasses = ProductionBatchRepository.class)
class ProductionBatchRepositoryPostgresConcurrencyIT {

    private static final String FACTORY = "REVERSAL-PG-BATCH-F001";
    private static final String BATCH_NUMBER = "REVERSAL-PG-BATCH-LOCK-001";

    @Autowired
    private ProductionBatchRepository repository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    @BeforeEach
    void createExactTargetTableAndSeedOneBatch() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS production_batches CASCADE");
        jdbcTemplate.execute("""
                CREATE TABLE production_batches (
                    id BIGSERIAL PRIMARY KEY,
                    factory_id VARCHAR(50) NOT NULL,
                    batch_number VARCHAR(50) NOT NULL UNIQUE,
                    production_plan_id VARCHAR(191),
                    product_type_id VARCHAR(100) NOT NULL,
                    workflow_selection_mode VARCHAR(16),
                    selected_workflow_id BIGINT,
                    selected_workflow_version INTEGER,
                    selected_workflow_revision_id BIGINT,
                    selected_workflow_revision_hash VARCHAR(64),
                    selected_bom_family_id VARCHAR(64),
                    selected_bom_recipe_ids_by_product JSONB,
                    selected_bom_versions_by_product JSONB,
                    workflow_output_units_by_product JSONB,
                    target_finished_good_ids JSONB,
                    product_name VARCHAR(100),
                    planned_quantity NUMERIC(12,2),
                    quantity NUMERIC(10,2) NOT NULL,
                    unit VARCHAR(20) NOT NULL,
                    actual_quantity NUMERIC(12,2),
                    good_quantity NUMERIC(12,2),
                    defect_quantity NUMERIC(12,2),
                    status VARCHAR(20) NOT NULL,
                    quality_status VARCHAR(30),
                    start_time TIMESTAMP,
                    end_time TIMESTAMP,
                    equipment_id BIGINT,
                    equipment_name VARCHAR(100),
                    supervisor_id BIGINT,
                    supervisor_name VARCHAR(50),
                    worker_count INTEGER,
                    work_duration_minutes INTEGER,
                    material_cost NUMERIC(12,2),
                    labor_cost NUMERIC(12,2),
                    equipment_cost NUMERIC(12,2),
                    other_cost NUMERIC(12,2),
                    total_cost NUMERIC(12,2),
                    cost_incomplete BOOLEAN NOT NULL DEFAULT FALSE,
                    unit_cost NUMERIC(12,4),
                    yield_rate NUMERIC(12,2),
                    efficiency NUMERIC(5,2),
                    notes TEXT,
                    created_by BIGINT,
                    photo_required BOOLEAN DEFAULT FALSE,
                    sop_config_id VARCHAR(50),
                    photo_completed_stages TEXT,
                    custom_fields JSONB DEFAULT '{}'::jsonb,
                    planned_unit VARCHAR(20),
                    batch_type VARCHAR(20) NOT NULL DEFAULT 'REGULAR',
                    is_trial BOOLEAN DEFAULT FALSE,
                    trial_sample_id VARCHAR(191),
                    ai_invocation_metadata JSONB,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL,
                    deleted_at TIMESTAMP
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO production_batches
                    (factory_id, batch_number, product_type_id, quantity, unit, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, FACTORY, BATCH_NUMBER, "PT-LOCK", 10.00, "kg", "IN_PROGRESS");
    }

    @AfterEach
    void dropExactTargetTable() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS production_batches CASCADE");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentTerminalTransitionsHaveExactlyOneWinner() throws Exception {
        Long id = new TransactionTemplate(transactionManager)
                .execute(status -> repository.findByFactoryIdAndBatchNumber(FACTORY, BATCH_NUMBER).orElseThrow().getId());
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                ProductionBatch batch = repository.findByIdAndFactoryIdForUpdate(id, FACTORY).orElseThrow();
                firstLocked.countDown();
                batch.setStatus(ProductionBatchStatus.COMPLETED);
                repository.saveAndFlush(batch);
                await(releaseFirst);
                return true;
            }));
            Future<Boolean> second = pool.submit(() -> {
                await(firstLocked);
                secondAttempted.countDown();
                return new TransactionTemplate(transactionManager).execute(status -> {
                    ProductionBatch batch = repository.findByIdAndFactoryIdForUpdate(id, FACTORY).orElseThrow();
                    if (batch.getStatus() != ProductionBatchStatus.IN_PROGRESS) {
                        return false;
                    }
                    batch.setStatus(ProductionBatchStatus.CANCELLED);
                    repository.saveAndFlush(batch);
                    return true;
                });
            });

            assertThat(secondAttempted.await(10, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(250L);
            releaseFirst.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(20, TimeUnit.SECONDS)).isFalse();
            ProductionBatch committed = new TransactionTemplate(transactionManager)
                    .execute(status -> repository.findById(id).orElseThrow());
            assertThat(committed.getStatus()).isEqualTo(ProductionBatchStatus.COMPLETED);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for lock test phase");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("lock test interrupted", e);
        }
    }
}
