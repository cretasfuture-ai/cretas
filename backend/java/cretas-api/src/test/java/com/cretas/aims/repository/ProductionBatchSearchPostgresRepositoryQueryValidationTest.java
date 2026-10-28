package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.integration.DisposablePostgresTargetGuard;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in PostgreSQL proof for nullable batch-search filters and plan-number matching. */
@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class ProductionBatchSearchPostgresRepositoryQueryValidationTest {

    private static final String FACTORY = "IT-BATCH-SEARCH-PG";
    private static final String SCHEMA = "batch_search_" + UUID.randomUUID().toString().replace("-", "");
    private static final PageRequest PAGE = PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "createdAt"));

    @Autowired private ProductionBatchRepository repository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void createIsolatedSchema() throws Exception {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        try (var connection = DriverManager.getConnection(url, requiredEnv("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"" + SCHEMA + "\"");
        }
    }

    @AfterAll
    static void dropIsolatedSchema() throws Exception {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        try (var connection = DriverManager.getConnection(url, requiredEnv("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS \"" + SCHEMA + "\" CASCADE");
        }
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> requiredEnv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO \"" + SCHEMA + "\"");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @BeforeEach
    void createFixture() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS process_sheet_rows, production_plans, production_batches CASCADE");
        jdbcTemplate.execute("""
                CREATE TABLE production_batches (
                  id BIGSERIAL PRIMARY KEY, factory_id VARCHAR(50) NOT NULL,
                  batch_number VARCHAR(50) NOT NULL UNIQUE, production_plan_id VARCHAR(191),
                  product_type_id VARCHAR(100) NOT NULL, workflow_selection_mode VARCHAR(16),
                  selected_workflow_id BIGINT, selected_workflow_version INTEGER,
                  selected_workflow_revision_id BIGINT, selected_workflow_revision_hash VARCHAR(64),
                  selected_bom_family_id VARCHAR(64), selected_bom_recipe_ids_by_product JSONB,
                  selected_bom_versions_by_product JSONB, workflow_output_units_by_product JSONB,
                  target_finished_good_ids JSONB, product_name VARCHAR(100), planned_quantity NUMERIC(12,2),
                  quantity NUMERIC(10,2) NOT NULL, unit VARCHAR(20) NOT NULL,
                  actual_quantity NUMERIC(12,2), good_quantity NUMERIC(12,2), defect_quantity NUMERIC(12,2),
                  status VARCHAR(20) NOT NULL, quality_status VARCHAR(30), start_time TIMESTAMP,
                  end_time TIMESTAMP, equipment_id BIGINT, equipment_name VARCHAR(100), supervisor_id BIGINT,
                  supervisor_name VARCHAR(50), worker_count INTEGER, work_duration_minutes INTEGER,
                  material_cost NUMERIC(12,2), labor_cost NUMERIC(12,2), equipment_cost NUMERIC(12,2),
                  other_cost NUMERIC(12,2), total_cost NUMERIC(12,2), unit_cost NUMERIC(12,4),
                  cost_incomplete BOOLEAN NOT NULL DEFAULT FALSE,
                  yield_rate NUMERIC(12,2), efficiency NUMERIC(5,2), notes TEXT, created_by BIGINT,
                  photo_required BOOLEAN DEFAULT FALSE, sop_config_id VARCHAR(50), photo_completed_stages TEXT,
                  custom_fields JSONB DEFAULT '{}'::jsonb, planned_unit VARCHAR(20), batch_type VARCHAR(20) NOT NULL DEFAULT 'REGULAR',
                  is_trial BOOLEAN DEFAULT FALSE, trial_sample_id VARCHAR(191), ai_invocation_metadata JSONB,
                  created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL, deleted_at TIMESTAMP
                )
                """);
        jdbcTemplate.execute("CREATE TABLE production_plans (id VARCHAR(191) PRIMARY KEY, factory_id VARCHAR(50) NOT NULL, plan_number VARCHAR(100) NOT NULL, deleted_at TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE process_sheet_rows (id BIGSERIAL PRIMARY KEY, batch_id BIGINT, plan_id VARCHAR(191), deleted_at TIMESTAMP)");
        jdbcTemplate.update("INSERT INTO production_plans(id,factory_id,plan_number) VALUES (?,?,?)", "PLAN-PG-ONLY", FACTORY, "PB-PG-PLAN-ONLY");
        save("PB-PG-NULL-1", ProductionBatchStatus.IN_PROGRESS, null, FACTORY);
        save("PB-PG-NULL-2", ProductionBatchStatus.COMPLETED, null, FACTORY);
        save("PB-PG-SUP-3", ProductionBatchStatus.IN_PROGRESS, 9001L, FACTORY);
        save("PB-PG-OTHER", ProductionBatchStatus.IN_PROGRESS, null, "IT-BATCH-SEARCH-OTHER");
        ProductionBatch clerk = save("PB-PG-CLERK", ProductionBatchStatus.IN_PROGRESS, null, FACTORY, "CLERK_WIP");
        jdbcTemplate.update("INSERT INTO process_sheet_rows(batch_id,plan_id) VALUES (?,?)", clerk.getId(), "PLAN-PG-ONLY");
    }

    @Test
    void omittedStatusAndSupervisorBindAsTypedNulls() {
        Page<ProductionBatch> result = repository.searchByFactoryIdAndBatchNumber(FACTORY, null, null, true, "PB-PG-NULL", PAGE);
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).extracting(ProductionBatch::getFactoryId).containsOnly(FACTORY);
    }

    @Test
    void explicitStatusAndSupervisorRemainAndFilters() {
        Page<ProductionBatch> status = repository.searchByFactoryIdAndBatchNumber(FACTORY, ProductionBatchStatus.COMPLETED, null, true, "PB-PG-", PAGE);
        assertThat(status.getTotalElements()).isEqualTo(1);
        assertThat(status.getContent().getFirst().getBatchNumber()).isEqualTo("PB-PG-NULL-2");
        Page<ProductionBatch> supervisor = repository.searchByFactoryIdAndBatchNumber(FACTORY, null, 9001L, true, "PB-PG-SUP", PAGE);
        assertThat(supervisor.getTotalElements()).isEqualTo(1);
        assertThat(supervisor.getContent().getFirst().getBatchNumber()).isEqualTo("PB-PG-SUP-3");
    }

    @Test
    void crossFactoryPlanNumberClerkWipNegativeAndPaginationRemainScoped() {
        Page<ProductionBatch> planMatch = repository.searchByFactoryIdAndBatchNumber(FACTORY, null, null, true, "PB-PG-PLAN-ONLY", PAGE);
        assertThat(planMatch.getTotalElements()).isEqualTo(1);
        assertThat(planMatch.getContent().getFirst().getBatchNumber()).isEqualTo("PB-PG-CLERK");
        Page<ProductionBatch> excluded = repository.searchByFactoryIdAndBatchNumber(FACTORY, null, null, false, "PB-PG-CLERK", PAGE);
        assertThat(excluded).isEmpty();
        Page<ProductionBatch> crossFactory = repository.searchByFactoryIdAndBatchNumber(FACTORY, null, null, true, "PB-PG-OTHER", PAGE);
        assertThat(crossFactory).isEmpty();
        Page<ProductionBatch> paged = repository.searchByFactoryIdAndBatchNumber(FACTORY, null, null, true, "PB-PG-", PAGE);
        assertThat(paged.getTotalElements()).isEqualTo(4);
        assertThat(paged.getContent()).hasSize(2);
        assertThat(paged.getTotalPages()).isEqualTo(2);
    }

    @Test
    void localBatchCannotBeFoundThroughAnotherFactoryPlanNumber() {
        ProductionBatch local = save("WIP-LOCAL-MISLINK", ProductionBatchStatus.IN_PROGRESS, null, FACTORY, "CLERK_WIP");
        jdbcTemplate.update("INSERT INTO production_plans(id,factory_id,plan_number) VALUES (?,?,?)",
                "PLAN-FOREIGN-MISLINK", "IT-BATCH-SEARCH-OTHER", "PRIVATE-FOREIGN-PLAN-872");
        jdbcTemplate.update("INSERT INTO process_sheet_rows(batch_id,plan_id) VALUES (?,?)",
                local.getId(), "PLAN-FOREIGN-MISLINK");

        Page<ProductionBatch> localMatch = repository.searchByFactoryIdAndBatchNumber(
                FACTORY, null, null, true, "WIP-LOCAL-MISLINK", PAGE);
        assertThat(localMatch.getContent()).extracting(ProductionBatch::getId).containsExactly(local.getId());
        Page<ProductionBatch> foreignPlanMatch = repository.searchByFactoryIdAndBatchNumber(
                FACTORY, null, null, true, "PRIVATE-FOREIGN-PLAN-872", PAGE);
        assertThat(foreignPlanMatch.getTotalElements()).isZero();
        assertThat(foreignPlanMatch.getContent()).isEmpty();
    }

    private ProductionBatch save(String number, ProductionBatchStatus status, Long supervisorId, String factory) {
        ProductionBatch batch = new ProductionBatch();
        batch.setFactoryId(factory); batch.setBatchNumber(number); batch.setProductTypeId("IT-BATCH-SEARCH-PT");
        batch.setQuantity(BigDecimal.ONE); batch.setUnit("kg"); batch.setStatus(status); batch.setBatchType("REGULAR");
        batch.setSupervisorId(supervisorId); batch.setCreatedAt(LocalDateTime.now()); batch.setUpdatedAt(LocalDateTime.now());
        return repository.saveAndFlush(batch);
    }

    private ProductionBatch save(String number, ProductionBatchStatus status, Long supervisorId, String factory, String batchType) {
        ProductionBatch batch = save(number, status, supervisorId, factory);
        batch.setBatchType(batchType);
        return repository.saveAndFlush(batch);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set when PostgreSQL verification is enabled");
        return value;
    }
}
