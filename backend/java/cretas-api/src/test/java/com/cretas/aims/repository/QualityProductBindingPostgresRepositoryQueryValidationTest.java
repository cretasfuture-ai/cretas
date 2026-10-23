package com.cretas.aims.repository;

import com.cretas.aims.integration.DisposablePostgresTargetGuard;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in PostgreSQL proof for the factory-scoped product lookup used by quality binding.
 * The test uses a disposable guarded database and a random schema; it never changes the
 * repository/entity contract or points at a shared database.
 */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackageClasses = ProductTypeRepository.class)
class QualityProductBindingPostgresRepositoryQueryValidationTest {

    private static final String SCHEMA = "qpb_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final String FACTORY = "F006";
    private static final String OTHER_FACTORY = "F007";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProductTypeRepository repository;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        createSchema(url);
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> requiredEnv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO " + SCHEMA);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set for PostgreSQL verification");
        return value;
    }

    private static void createSchema(String url) {
        try (var connection = DriverManager.getConnection(url, requiredEnv("CRETAS_WORKFLOW_PG_USER"), System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to create isolated PostgreSQL schema", error);
        }
    }

    @AfterAll
    static void dropSchema() {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url == null || url.isBlank()) return;
        try (var connection = DriverManager.getConnection(DisposablePostgresTargetGuard.requireSafeUrl(url),
                requiredEnv("CRETAS_WORKFLOW_PG_USER"), System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        } catch (Exception error) {
            throw new IllegalStateException("Unable to clean isolated PostgreSQL schema", error);
        }
    }

    @BeforeEach
    void createExactProductTableAndSeedRows() {
        jdbc.execute("""
                CREATE TABLE product_types (
                    id VARCHAR(100) PRIMARY KEY, factory_id VARCHAR(50) NOT NULL, code VARCHAR(50) NOT NULL,
                    name VARCHAR(255) NOT NULL, category VARCHAR(50), unit VARCHAR(20) NOT NULL,
                    unit_price NUMERIC(10,2), production_time_minutes INTEGER, shelf_life_days INTEGER,
                    single_pot_capacity NUMERIC(14,3), form_template_id VARCHAR(100), default_sop_config_id VARCHAR(50),
                    work_hours NUMERIC(10,2), processing_steps JSONB, skill_requirements JSONB, equipment_ids JSONB,
                    quality_check_ids JSONB, complexity_score INTEGER, custom_schema_overrides TEXT, package_spec VARCHAR(100),
                    product_category VARCHAR(50), specification VARCHAR(200), related_customer VARCHAR(100), image_url VARCHAR(500),
                    temperature_zone VARCHAR(20), box_conversion_coefficient NUMERIC(10,4), grams_per_unit NUMERIC(10,2),
                    net_content_quantity NUMERIC(15,6), net_content_unit VARCHAR(20), wip_to_fg_yield NUMERIC(5,4),
                    quoted_labor_cost_per_kg NUMERIC(12,4), inventory_warning_threshold NUMERIC(15,2), minimum_order_quantity NUMERIC(15,2),
                    brand VARCHAR(100), settlement_method VARCHAR(50), tax_included_unit_price NUMERIC(15,4), tax_rate VARCHAR(10),
                    template_id VARCHAR(100), customer_id VARCHAR(191), recipe_version VARCHAR(50), level1_unit VARCHAR(20),
                    base_product_name VARCHAR(200), standard_cost NUMERIC(12,4), target_gross_margin NUMERIC(6,4),
                    rework_purity_level INTEGER, accepts_rework BOOLEAN, is_active BOOLEAN NOT NULL, notes TEXT, created_by BIGINT,
                    actor_type VARCHAR(32), actor_id BIGINT, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL, deleted_at TIMESTAMP
                )
                """);
        jdbc.update("INSERT INTO product_types (id,factory_id,code,name,unit,is_active,created_at,updated_at) VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", "active", FACTORY, "ACTIVE", "Active", "kg", true);
        jdbc.update("INSERT INTO product_types (id,factory_id,code,name,unit,is_active,created_at,updated_at) VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", "foreign", OTHER_FACTORY, "FOREIGN", "Foreign", "kg", true);
        jdbc.update("INSERT INTO product_types (id,factory_id,code,name,unit,is_active,created_at,updated_at,deleted_at) VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", "deleted", FACTORY, "DELETED", "Deleted", "kg", true);
    }

    @Test
    void lookupRequiresSameFactoryAndExcludesSoftDeletedProduct() {
        assertThat(repository.existsByIdAndFactoryId("active", FACTORY)).isTrue();
        assertThat(repository.existsByIdAndFactoryId("foreign", FACTORY)).isFalse();
        assertThat(repository.existsByIdAndFactoryId("deleted", FACTORY)).isFalse();
        assertThat(repository.existsByIdAndFactoryId("missing", FACTORY)).isFalse();
    }
}
