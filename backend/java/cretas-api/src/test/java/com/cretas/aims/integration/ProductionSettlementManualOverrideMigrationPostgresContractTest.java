package com.cretas.aims.integration;

import com.cretas.aims.entity.ProductionSettlement;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Actual Flyway + PG + Spring/Hibernate validation of the settlement table projection.
 * The prior projection comes from the repository's deployed DDL resources, not entity-generated DDL.
 * This is not a claim that every application migration was replayed in this fixture.
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class ProductionSettlementManualOverrideMigrationPostgresContractTest {
    private static final String MIGRATION =
            "db/flyway/V20261105_93__production_settlement_manual_override_and_semi_stocking.sql";
    private static final String STOCK_FEED_MIGRATION =
            "db/flyway/V20261109_20__production_settlement_stock_feed_detail.sql";

    @Test
    void upgradePreservesLegacySettlementAndHibernateReadsFalseDefault() throws Exception {
        verify(true);
    }

    @Test
    void emptyPriorProjectionSupportsFreshRowsAndAuditFields() throws Exception {
        verify(false);
    }

    private void verify(boolean legacy) throws Exception {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(System.getenv("CRETAS_WORKFLOW_PG_URL"));
        String user = System.getenv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        String schema = "settle_audit_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        Path directory = Files.createTempDirectory("settlement-audit-flyway-");
        admin.execute("CREATE SCHEMA " + schema);
        String scoped = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        DriverManagerDataSource dataSource = new DriverManagerDataSource(scoped, user, password);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try {
            // Only the settlement table and its independent companion tables are needed here.
            // V30 only relaxes a nullable scalar on this entity; validation does not generate/alter DDL.
            String prior = sql("db/flyway/V20261024_04__production_settlement.sql")
                    + "\n" + sql("db/flyway/V20261024_05__production_warehouse_receipt_transit.sql")
                    + "\n" + sql("db/flyway/V20260827_01__settlement_defect_count_outcome.sql")
                    + "\n" + sql("db/flyway/V20261101_21__production_settlement_active_plan_unique.sql");
            Files.writeString(directory.resolve("V20261105_91__settlement_prior_projection.sql"), prior);
            assertThat(flyway(dataSource, schema, directory).migrate().migrationsExecuted).isEqualTo(1);
            Map<String, Object> before = null;
            if (legacy) {
                insert(jdbc, "legacy");
                before = jdbc.queryForMap("SELECT * FROM production_settlements WHERE id='legacy'");
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns "
                    + "WHERE table_schema=? AND table_name='production_settlements' "
                    + "AND column_name IN ('manual_override','manual_override_reason','semi_stocking_detail')",
                    Integer.class, schema)).isZero();
            Files.writeString(directory.resolve(Path.of(MIGRATION).getFileName()), sql(MIGRATION), StandardCharsets.UTF_8);
            Flyway upgraded = flyway(dataSource, schema, directory);
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(upgraded.migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history "
                    + "WHERE success ORDER BY installed_rank DESC LIMIT 1", String.class)).isEqualTo("20261105.93");
            if (legacy) {
                Map<String, Object> after = new LinkedHashMap<>(jdbc.queryForMap(
                        "SELECT * FROM production_settlements WHERE id='legacy'"));
                assertThat(after.remove("manual_override")).isEqualTo(false);
                assertThat(after.remove("manual_override_reason")).isNull();
                assertThat(after.remove("semi_stocking_detail")).isNull();
                assertThat(after).as("all prior settlement fields remain unchanged").isEqualTo(before);
            }
            // N1 (V20261109_20): 结单投料扣减明细列。实体映射了它, Hibernate validate 之前它必须已由迁移建好;
            // 旧结单读出来是 NULL (= 上线前的结单, 撤回据此判「扣过但没记」)。
            assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns "
                    + "WHERE table_schema=? AND table_name='production_settlements' AND column_name='stock_feed_detail'",
                    Integer.class, schema)).isZero();
            Files.writeString(directory.resolve(Path.of(STOCK_FEED_MIGRATION).getFileName()), sql(STOCK_FEED_MIGRATION),
                    StandardCharsets.UTF_8);
            Flyway withStockFeed = flyway(dataSource, schema, directory);
            assertThat(withStockFeed.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(withStockFeed.migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history "
                    + "WHERE success ORDER BY installed_rank DESC LIMIT 1", String.class)).isEqualTo("20261109.20");
            if (legacy) {
                assertThat(jdbc.queryForObject("SELECT stock_feed_detail FROM production_settlements WHERE id='legacy'",
                        String.class)).as("旧结单没有明细").isNull();
            }
            insert(jdbc, "fresh");
            assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> jdbc.update("UPDATE production_settlements SET manual_override=NULL WHERE id='fresh'"));
            try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                context.registerBean(DataSource.class, () -> dataSource);
                context.register(JpaRuntime.class);
                context.refresh(); // Actual Spring JPA startup; validate only, no automatic schema repair.
                try (var em = context.getBean(EntityManagerFactory.class).createEntityManager()) {
                    ProductionSettlement fresh = em.find(ProductionSettlement.class, "fresh");
                    assertThat(fresh.isManualOverride()).isFalse();
                    assertThat(fresh.getManualOverrideReason()).isNull();
                    assertThat(fresh.getSemiStockingDetail()).isNull();
                    assertThat(fresh.getStockFeedDetail()).isNull();
                    if (legacy) assertThat(em.find(ProductionSettlement.class, "legacy").isManualOverride()).isFalse();
                    em.getTransaction().begin();
                    fresh.setManualOverride(true);
                    fresh.setManualOverrideReason("QA explicit override");
                    fresh.setSemiStockingDetail("[{\"quantity\":0.2}]");
                    fresh.setStockFeedDetail("[]");
                    em.getTransaction().commit();
                }
            }
            assertThat(jdbc.queryForObject("SELECT manual_override FROM production_settlements WHERE id='fresh'",
                    Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("SELECT manual_override_reason FROM production_settlements WHERE id='fresh'",
                    String.class)).isEqualTo("QA explicit override");
            assertThat(jdbc.queryForObject("SELECT semi_stocking_detail FROM production_settlements WHERE id='fresh'",
                    String.class)).isEqualTo("[{\"quantity\":0.2}]");
            assertThat(jdbc.queryForObject("SELECT stock_feed_detail FROM production_settlements WHERE id='fresh'",
                    String.class)).isEqualTo("[]");
        } finally {
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void insert(JdbcTemplate jdbc, String id) {
        jdbc.update("INSERT INTO production_settlements (id,factory_id,production_plan_id,plan_number,"
                + "idempotency_key,planned_quantity,actual_finished_quantity,plan_status_after) "
                + "VALUES (?,'S315-MIGRATION',?,?,?,1,1,'COMPLETED')", id, "P-" + id, "N-" + id, "I-" + id);
    }

    private static String sql(String resource) throws Exception {
        try (var stream = new ClassPathResource(resource).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Flyway flyway(DataSource source, String schema, Path location) {
        return Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                .locations("filesystem:" + location.toAbsolutePath()).outOfOrder(false).load();
    }

    @Configuration(proxyBeanMethods = false)
    static class JpaRuntime {
        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var bean = new LocalContainerEntityManagerFactoryBean();
            bean.setDataSource(source);
            bean.setManagedTypes(PersistenceManagedTypes.of(ProductionSettlement.class.getName()));
            bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            bean.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
            return bean;
        }
    }
}
