package com.cretas.aims.repository;

import com.cretas.aims.integration.DisposablePostgresTargetGuard;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工艺保密: 两条跨批汇总的原生 SQL 在真实 PostgreSQL 上按计划排除 (独立审查第三轮 B、第四轮 M1)。
 * 半成品那条用 jsonb {@code ->>}, H2 跑不了, 所以只在 CI 的 PG 车道跑。
 * 守的行为: 牵涉无权保密产品的计划整张不进汇总 —— 兄弟产出 (不受保护的 Q-OPEN) 的行也不进; 哨兵 = 不排除。
 */
@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class ProcessSecretAggregateExclusionPostgresRepositoryQueryValidationTest {

    private static final String FACTORY = "IT-PS-AGG";
    private static final String SCHEMA = "ps_agg_" + UUID.randomUUID().toString().replace("-", "");
    private static final List<String> NONE = List.of("__none__");
    private static final LocalDate FROM = LocalDate.of(1900, 1, 1);
    private static final LocalDate TO = LocalDate.of(2999, 12, 31);

    @Autowired private ProductionReportRepository repository;
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
        jdbcTemplate.execute("DROP TABLE IF EXISTS production_reports, work_process_tasks, work_processes, "
                + "production_batches, process_sheet_rows CASCADE");
        jdbcTemplate.execute("CREATE TABLE production_batches (id BIGINT PRIMARY KEY, production_plan_id VARCHAR(191))");
        jdbcTemplate.execute("CREATE TABLE work_processes (id VARCHAR(50) PRIMARY KEY, process_name VARCHAR(100), "
                + "unit VARCHAR(20), output_unit VARCHAR(20), deleted_at TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE work_process_tasks (id BIGINT PRIMARY KEY, factory_id VARCHAR(50) NOT NULL, "
                + "work_process_id VARCHAR(50) NOT NULL, product_type_id VARCHAR(50) NOT NULL, "
                + "process_order INTEGER NOT NULL, deleted_at TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE production_reports (id BIGINT PRIMARY KEY, factory_id VARCHAR(50) NOT NULL, "
                + "batch_id BIGINT, work_process_task_id BIGINT, report_type VARCHAR(20), report_date DATE, "
                + "input_quantity NUMERIC(12,2), output_quantity NUMERIC(12,2), deleted_at TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE process_sheet_rows (id BIGINT PRIMARY KEY, factory_id VARCHAR(50) NOT NULL, "
                + "plan_id VARCHAR(64) NOT NULL, batch_id BIGINT, batch_number VARCHAR(100), row_payload JSONB NOT NULL, "
                + "row_status VARCHAR(20) NOT NULL, interim_settled_at TIMESTAMP, deleted_at TIMESTAMP)");

        // 两张计划共用一道工序, 都在产不受保护的 Q-OPEN; plan-hidden 还牵涉无权的保密产品 (由服务层算出)
        jdbcTemplate.update("INSERT INTO work_processes VALUES ('WP-MIX', '拌料', 'kg', 'kg', NULL)");
        batchWithReport(1L, "plan-hidden", "100", "80");
        batchWithReport(2L, "plan-open", "100", "90");
        sheetRow(1L, "plan-hidden", "50", "40");
        sheetRow(2L, "plan-open", "50", "45");
    }

    private void batchWithReport(long id, String planId, String input, String output) {
        jdbcTemplate.update("INSERT INTO production_batches VALUES (?, ?)", id, planId);
        jdbcTemplate.update("INSERT INTO work_process_tasks VALUES (?, ?, 'WP-MIX', 'Q-OPEN', 1, NULL)", id, FACTORY);
        jdbcTemplate.update("INSERT INTO production_reports VALUES (?, ?, ?, ?, 'YIELD', DATE '2026-10-20', ?, ?, NULL)",
                id, FACTORY, id, id, new BigDecimal(input), new BigDecimal(output));
    }

    private void sheetRow(long id, String planId, String input, String output) {
        jdbcTemplate.update("INSERT INTO process_sheet_rows VALUES (?, ?, ?, ?, NULL, CAST(? AS JSONB), 'SAVED', "
                        + "TIMESTAMP '2026-10-20 10:00:00', NULL)", id, FACTORY, planId, id,
                "{\"productTypeId\":\"SEMI-1\",\"finished\":\"false\",\"inputQuantity\":\"" + input
                        + "\",\"outputQuantity\":\"" + output + "\",\"unit\":\"kg\"}");
    }

    @Test
    void byProcessAggregateLeavesOutEveryRowOfAnExcludedPlanIncludingUnprotectedSiblingOutputs() {
        List<Map<String, Object>> all = repository.aggregateYieldByProcess(FACTORY, FROM, TO, "", NONE, NONE);
        assertThat(all).hasSize(1);
        assertThat(new BigDecimal(all.get(0).get("total_input").toString())).isEqualByComparingTo("200");
        assertThat(((Number) all.get(0).get("batch_count")).intValue()).isEqualTo(2);

        List<Map<String, Object>> withoutHiddenPlan = repository.aggregateYieldByProcess(FACTORY, FROM, TO, "", NONE,
                List.of("plan-hidden"));
        assertThat(withoutHiddenPlan).hasSize(1);
        assertThat(new BigDecimal(withoutHiddenPlan.get(0).get("total_input").toString())).isEqualByComparingTo("100");
        assertThat(new BigDecimal(withoutHiddenPlan.get(0).get("total_output").toString())).isEqualByComparingTo("90");
        assertThat(((Number) withoutHiddenPlan.get(0).get("batch_count")).intValue()).isEqualTo(1);

        // 按产品排除照旧
        assertThat(repository.aggregateYieldByProcess(FACTORY, FROM, TO, "", List.of("Q-OPEN"), NONE)).isEmpty();
    }

    @Test
    void semiFinishedAggregateLeavesOutRowsOfExcludedPlans() {
        ProductionReportRepository.SemiFinishedYieldAggregate all =
                repository.aggregateSettledSemiFinishedYield(FACTORY, "SEMI-1", NONE);
        assertThat(all.getTotalInputKg()).isEqualByComparingTo("100");
        assertThat(all.getTotalOutputKg()).isEqualByComparingTo("85");
        assertThat(all.getBatchCount()).isEqualTo(2L);

        ProductionReportRepository.SemiFinishedYieldAggregate withoutHiddenPlan =
                repository.aggregateSettledSemiFinishedYield(FACTORY, "SEMI-1", List.of("plan-hidden"));
        assertThat(withoutHiddenPlan.getTotalInputKg()).isEqualByComparingTo("50");
        assertThat(withoutHiddenPlan.getTotalOutputKg()).isEqualByComparingTo("45");
        assertThat(withoutHiddenPlan.getBatchCount()).isEqualTo(1L);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set when PostgreSQL verification is enabled");
        return value;
    }
}
