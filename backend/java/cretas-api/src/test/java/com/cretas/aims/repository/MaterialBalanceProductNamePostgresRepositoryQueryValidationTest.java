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
 * 物料平衡查询 findYieldReportsForBalance 在真实 PostgreSQL 上的产品名 (BP 缺陷 21, 2026-11-18)。
 * 多产出计划的 product_type_id 指向内部锚点产品 (名字 __RAW_WORKFLOW_OWNER__<原料id>) 或档案行已不存在,
 * 查询要回退到同 id 的原料名; 真实产品名原样; 原料名也查不到时保持锚点名 (由评估器当占位丢掉)。
 * 查询用 jsonb {@code ->>}, H2 跑不了, 只在 CI 的 PG 车道跑。
 */
@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class MaterialBalanceProductNamePostgresRepositoryQueryValidationTest {

    private static final String FACTORY = "IT-MB-PN";
    private static final String SCHEMA = "mb_pn_" + UUID.randomUUID().toString().replace("-", "");
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
                + "production_batches, production_plans, product_types, raw_material_types CASCADE");
        jdbcTemplate.execute("CREATE TABLE product_types (id VARCHAR(100) PRIMARY KEY, name VARCHAR(200))");
        jdbcTemplate.execute("CREATE TABLE raw_material_types (id VARCHAR(100) PRIMARY KEY, factory_id VARCHAR(50) NOT NULL, "
                + "name VARCHAR(200), deleted_at TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE production_plans (id VARCHAR(191) PRIMARY KEY, factory_id VARCHAR(50) NOT NULL, "
                + "plan_number VARCHAR(100), product_type_id VARCHAR(100))");
        jdbcTemplate.execute("CREATE TABLE production_batches (id BIGINT PRIMARY KEY, production_plan_id VARCHAR(191), "
                + "batch_number VARCHAR(100))");
        jdbcTemplate.execute("CREATE TABLE work_processes (id VARCHAR(50) PRIMARY KEY, process_name VARCHAR(100), "
                + "unit VARCHAR(20), output_unit VARCHAR(20), deleted_at TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE work_process_tasks (id BIGINT PRIMARY KEY, production_batch_id BIGINT, "
                + "work_process_id VARCHAR(50), product_type_id VARCHAR(100), process_order INTEGER, deleted_at TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE production_reports (id BIGINT PRIMARY KEY, factory_id VARCHAR(50) NOT NULL, "
                + "batch_id BIGINT, work_process_task_id BIGINT, report_type VARCHAR(20), report_date DATE, "
                + "process_order INTEGER, process_category VARCHAR(100), custom_fields JSONB, "
                + "input_quantity NUMERIC(12,2), input_unit VARCHAR(20), output_quantity NUMERIC(12,2), output_unit VARCHAR(20), "
                + "waste_quantity NUMERIC(12,2), sample_retain_quantity NUMERIC(12,2), byproducts JSONB, deleted_at TIMESTAMP)");

        jdbcTemplate.update("INSERT INTO work_processes VALUES ('WP-1', '拌料', 'kg', 'kg', NULL)");
        // A 锚点产品 + 有原料名; B 锚点产品 + 原料档案没有; C 真实产品 (同 id 也有原料行, 不得被覆盖);
        // D 产品档案行不存在 + 有原料名
        jdbcTemplate.update("INSERT INTO product_types VALUES ('RMT_A', '__RAW_WORKFLOW_OWNER__RMT_A')");
        jdbcTemplate.update("INSERT INTO product_types VALUES ('RMT_B', '__RAW_WORKFLOW_OWNER__RMT_B')");
        jdbcTemplate.update("INSERT INTO product_types VALUES ('PT_C', '酱牛肉250g')");
        jdbcTemplate.update("INSERT INTO raw_material_types VALUES ('RMT_A', ?, '安格斯碎肉', NULL)", FACTORY);
        jdbcTemplate.update("INSERT INTO raw_material_types VALUES ('PT_C', ?, '不该出现的原料名', NULL)", FACTORY);
        jdbcTemplate.update("INSERT INTO raw_material_types VALUES ('RMT_D', ?, '牛前腱', NULL)", FACTORY);
        plan(1L, "PLAN-A", "RMT_A");
        plan(2L, "PLAN-B", "RMT_B");
        plan(3L, "PLAN-C", "PT_C");
        plan(4L, "PLAN-D", "RMT_D");
    }

    private void plan(long id, String planNumber, String productTypeId) {
        jdbcTemplate.update("INSERT INTO production_plans VALUES (?, ?, ?, ?)", "plan-" + id, FACTORY, planNumber, productTypeId);
        jdbcTemplate.update("INSERT INTO production_batches VALUES (?, ?, ?)", id, "plan-" + id, "B-" + id);
        jdbcTemplate.update("INSERT INTO work_process_tasks VALUES (?, ?, 'WP-1', ?, 1, NULL)", id, id, productTypeId);
        jdbcTemplate.update("INSERT INTO production_reports VALUES (?, ?, ?, ?, 'YIELD', DATE '2026-11-10', 1, NULL, NULL, "
                + "100, 'kg', 90, 'kg', NULL, NULL, NULL, NULL)", id, FACTORY, id, id);
    }

    private Map<String, String> productNameByPlan() {
        List<Map<String, Object>> rows = repository.findYieldReportsForBalance(FACTORY, FROM, TO, "", NONE, NONE);
        Map<String, String> out = new java.util.HashMap<>();
        for (Map<String, Object> r : rows) {
            out.put((String) r.get("plan_number"), (String) r.get("product_name"));
        }
        return out;
    }

    @Test
    void anchorProductFallsBackToRawMaterialName() {
        Map<String, String> names = productNameByPlan();
        assertThat(names).hasSize(4); // 必发生: 四张计划的行都读到了
        assertThat(names.get("PLAN-A")).isEqualTo("安格斯碎肉");
    }

    @Test
    void missingProductRowFallsBackToRawMaterialName() {
        assertThat(productNameByPlan().get("PLAN-D")).isEqualTo("牛前腱");
    }

    @Test
    void anchorWithoutRawMaterialKeepsAnchorNameForEvaluatorToDrop() {
        assertThat(productNameByPlan().get("PLAN-B")).isEqualTo("__RAW_WORKFLOW_OWNER__RMT_B");
    }

    @Test
    void realProductNameIsNeverReplacedByRawMaterialName() {
        assertThat(productNameByPlan().get("PLAN-C")).isEqualTo("酱牛肉250g");
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set when PostgreSQL verification is enabled");
        return value;
    }
}
