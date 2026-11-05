package com.cretas.aims.integration;

import com.cretas.aims.entity.MaterialPackagingHierarchy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real PostgreSQL proof for widening the legacy hierarchy material reference.
 *
 * <p>The test starts from the shipped 36-character DDL, adds the representative
 * material FK used by deployed schemas, applies only V20261105_95, then boots
 * Hibernate with validation and persists a generated-style 40-character ID.
 * H2 and generated DDL are deliberately not used as evidence here.</p>
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class MaterialPackagingHierarchyIdWidthPostgresContractTest {
    private static final String PRIOR_MIGRATION =
            "db/flyway/V20260506_02__material_packaging_hierarchy.sql";
    private static final String SPECS_MIGRATION =
            "db/flyway/V20261029_22__material_packaging_specs.sql";
    private static final String WIDTH_MIGRATION =
            "db/flyway/V20261105_95__widen_material_packaging_material_type_id.sql";

    @Test
    void legacySchemaMigratesAndJpaRoundTripsFortyCharacterMaterialId() throws Exception {
        String rawUrl = System.getenv("CRETAS_WORKFLOW_PG_URL");
        assumeTrue(rawUrl != null && !rawUrl.isBlank(), "CRETAS_WORKFLOW_PG_URL is required");
        String url = DisposablePostgresTargetGuard.requireSafeUrl(rawUrl);
        String user = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        String schema = "mph_width_" + UUID.randomUUID().toString().replace("-", "");
        String scopedUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        DriverManagerDataSource dataSource = new DriverManagerDataSource(scopedUrl, user, password);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Path migrationDirectory = Files.createTempDirectory("mph-width-flyway-");
        try {
            createSchema(url, user, password, schema);
            Files.writeString(migrationDirectory.resolve(Path.of(PRIOR_MIGRATION).getFileName()),
                    resource(PRIOR_MIGRATION), StandardCharsets.UTF_8);

            Flyway prior = flyway(dataSource, schema, migrationDirectory);
            assertThat(prior.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(columnLength(jdbc)).isEqualTo(36);

            // The shipped legacy DDL predates the deployed FK. Add it to the
            // old projection so the in-place ALTER is proved not to discard it.
            jdbc.execute("CREATE TABLE raw_material_types (id VARCHAR(191) PRIMARY KEY)");
            jdbc.execute("ALTER TABLE material_packaging_hierarchy "
                    + "ADD CONSTRAINT fk_mph_material_type FOREIGN KEY (material_type_id) "
                    + "REFERENCES raw_material_types(id)");

            String legacy36MaterialId = "LEGACY_" + "l".repeat(29);
            jdbc.update("INSERT INTO raw_material_types (id) VALUES (?)", legacy36MaterialId);
            insertHierarchyWithNotes(jdbc, "legacy-hierarchy", legacy36MaterialId,
                    "legacy-note");
            Files.writeString(migrationDirectory.resolve(Path.of(SPECS_MIGRATION).getFileName()),
                    resource(SPECS_MIGRATION), StandardCharsets.UTF_8);
            Flyway specs = flyway(dataSource, schema, migrationDirectory);
            assertThat(specs.migrate().migrationsExecuted).isEqualTo(1);
            Map<String, Object> legacyBeforeWidth = legacySnapshot(jdbc, legacy36MaterialId);
            Map<String, Object> legacySpecBeforeWidth = legacySpecSnapshot(jdbc, legacy36MaterialId);

            Set<String> constraintsBefore = constraintNames(jdbc);

            String materialId = "RMT_" + "x".repeat(36);
            jdbc.update("INSERT INTO raw_material_types (id) VALUES (?)", materialId);
            String rollbackMaterialId = "RMT_" + "r".repeat(36);
            jdbc.update("INSERT INTO raw_material_types (id) VALUES (?)", rollbackMaterialId);
            String checkMaterialId = "RMT_" + "c".repeat(36);
            jdbc.update("INSERT INTO raw_material_types (id) VALUES (?)", checkMaterialId);
            String atomicMaterialId = "RMT_" + "a".repeat(36);
            jdbc.update("INSERT INTO raw_material_types (id) VALUES (?)", atomicMaterialId);

            Files.writeString(migrationDirectory.resolve(Path.of(WIDTH_MIGRATION).getFileName()),
                    resource(WIDTH_MIGRATION), StandardCharsets.UTF_8);
            Flyway upgraded = flyway(dataSource, schema, migrationDirectory);
            assertMigrationTimesOutBehindShareLock(dataSource, jdbc, upgraded, constraintsBefore);
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(upgraded.migrate().migrationsExecuted).isZero();
            assertThat(columnLength(jdbc)).isEqualTo(191);
            assertThat(constraintNames(jdbc)).isEqualTo(constraintsBefore);
            assertLegacySnapshotUnchanged(jdbc, legacy36MaterialId, legacyBeforeWidth,
                    legacySpecBeforeWidth);

            String longHierarchyId = persistAndReadWithHibernate(dataSource, materialId);
            validateAndReadWithLegacyHibernate(dataSource, materialId, longHierarchyId);
            validateAndReadWithLegacyHibernate(dataSource, legacy36MaterialId,
                    "legacy-hierarchy");
            assertThat(jdbc.queryForObject(
                    "SELECT material_type_id FROM material_packaging_hierarchy WHERE material_type_id = ?",
                    String.class, materialId)).isEqualTo(materialId);
            assertRollbackLeavesNoHierarchy(jdbc, rollbackMaterialId);

            assertSqlState("23505", () -> insertHierarchy(jdbc, "duplicate", materialId, "kg"));
            assertSqlState("23514", () -> jdbc.update(
                    "INSERT INTO material_packaging_hierarchy "
                            + "(id,factory_id,material_type_id,level1_unit,level1_per_level2,"
                            + "created_at,updated_at) VALUES (?,?,?,'kg',10,now(),now())",
                    "bad-check", "F-WIDTH", checkMaterialId));
            assertSqlState("23503", () -> jdbc.update(
                    "INSERT INTO material_packaging_hierarchy "
                            + "(id,factory_id,material_type_id,level1_unit,level1_per_level2,"
                            + "level2_unit,created_at,updated_at) VALUES (?,?,?,'kg',10,'box',now(),now())",
                    "bad-fk", "F-WIDTH", "RMT_" + "z".repeat(36)));
            assertAtomicHierarchyAndSpecRollback(jdbc, atomicMaterialId);
        } finally {
            dropSchema(url, user, password, schema);
            try (var paths = Files.walk(migrationDirectory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static String persistAndReadWithHibernate(DataSource dataSource, String materialId) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DataSource.class, () -> dataSource);
            context.register(JpaRuntime.class);
            context.refresh();
            EntityManagerFactory emf = context.getBean(EntityManagerFactory.class);
            EntityManager entityManager = emf.createEntityManager();
            try {
                entityManager.getTransaction().begin();
                MaterialPackagingHierarchy hierarchy = new MaterialPackagingHierarchy();
                hierarchy.setFactoryId("F-WIDTH");
                hierarchy.setMaterialTypeId(materialId);
                hierarchy.setLevel1Unit("kg");
                hierarchy.setLevel1PerLevel2(new BigDecimal("10"));
                hierarchy.setLevel2Unit("box");
                entityManager.persist(hierarchy);
                entityManager.getTransaction().commit();
                String id = hierarchy.getId();

                entityManager.clear();
                MaterialPackagingHierarchy loaded = entityManager.find(MaterialPackagingHierarchy.class, id);
                assertThat(loaded).isNotNull();
                assertThat(loaded.getMaterialTypeId()).isEqualTo(materialId);
                return id;
            } finally {
                if (entityManager.getTransaction().isActive()) entityManager.getTransaction().rollback();
                entityManager.close();
            }
        }
    }

    private static void insertHierarchy(JdbcTemplate jdbc, String id, String materialId, String unit) {
        jdbc.update("INSERT INTO material_packaging_hierarchy "
                        + "(id,factory_id,material_type_id,level1_unit,level1_per_level2,"
                        + "level2_unit,created_at,updated_at) VALUES (?,?,?, ?,10,?,now(),now())",
                id, "F-WIDTH", materialId, unit, "box");
    }

    private static void insertHierarchyWithNotes(JdbcTemplate jdbc, String id, String materialId,
                                                  String notes) {
        jdbc.update("INSERT INTO material_packaging_hierarchy "
                        + "(id,factory_id,material_type_id,level1_unit,level1_per_level2,"
                        + "level2_unit,notes,created_at,updated_at) "
                        + "VALUES (?,?,?,'kg',10,'box',?,now(),now())",
                id, "F-WIDTH", materialId, notes);
    }

    private static Map<String, Object> legacySnapshot(JdbcTemplate jdbc, String materialId) {
        return jdbc.queryForMap("SELECT id, material_type_id, level1_unit, level1_per_level2, "
                + "level2_unit, notes FROM material_packaging_hierarchy "
                + "WHERE material_type_id = ?", materialId);
    }

    private static Map<String, Object> legacySpecSnapshot(JdbcTemplate jdbc, String materialId) {
        return jdbc.queryForMap("SELECT id, material_type_id, package_unit, base_unit, "
                + "conversion_factor FROM material_packaging_specs "
                + "WHERE material_type_id = ?", materialId);
    }

    private static void assertLegacySnapshotUnchanged(JdbcTemplate jdbc, String materialId,
                                                       Map<String, Object> hierarchyBefore,
                                                       Map<String, Object> specBefore) {
        Map<String, Object> hierarchyAfter = legacySnapshot(jdbc, materialId);
        Map<String, Object> specAfter = legacySpecSnapshot(jdbc, materialId);
        assertThat(hierarchyAfter.get("id")).isEqualTo(hierarchyBefore.get("id"));
        assertThat(hierarchyAfter.get("material_type_id"))
                .isEqualTo(hierarchyBefore.get("material_type_id"));
        assertThat(hierarchyAfter.get("level1_unit")).isEqualTo(hierarchyBefore.get("level1_unit"));
        assertThat(hierarchyAfter.get("level2_unit")).isEqualTo(hierarchyBefore.get("level2_unit"));
        assertThat(hierarchyAfter.get("notes")).isEqualTo(hierarchyBefore.get("notes"));
        assertThat((BigDecimal) hierarchyAfter.get("level1_per_level2"))
                .isEqualByComparingTo((BigDecimal) hierarchyBefore.get("level1_per_level2"));
        assertThat(specAfter.get("id")).isEqualTo(specBefore.get("id"));
        assertThat(specAfter.get("material_type_id")).isEqualTo(specBefore.get("material_type_id"));
        assertThat(specAfter.get("package_unit")).isEqualTo(specBefore.get("package_unit"));
        assertThat(specAfter.get("base_unit")).isEqualTo(specBefore.get("base_unit"));
        assertThat((BigDecimal) specAfter.get("conversion_factor"))
                .isEqualByComparingTo((BigDecimal) specBefore.get("conversion_factor"));
    }

    private static void assertAtomicHierarchyAndSpecRollback(JdbcTemplate jdbc, String materialId) {
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(
                Objects.requireNonNull(jdbc.getDataSource())));
        assertSqlState("23505", () -> transaction.executeWithoutResult(status -> {
            insertHierarchy(jdbc, "atomic-hierarchy", materialId, "kg");
            jdbc.update("INSERT INTO material_packaging_specs "
                            + "(id,factory_id,material_type_id,name,package_unit,base_unit,"
                            + "conversion_factor,is_default,is_active,sort_order,version,created_at,updated_at) "
                            + "VALUES (?,?,?,'atomic','box','kg',10,FALSE,TRUE,0,0,now(),now())",
                    "atomic-spec", "F-WIDTH", materialId);
            // Let the exception leave the Spring transaction; no manual rollback.
            insertHierarchy(jdbc, "atomic-duplicate", materialId, "kg");
        }));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM material_packaging_hierarchy WHERE material_type_id = ?",
                Integer.class, materialId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM material_packaging_specs WHERE material_type_id = ?",
                Integer.class, materialId)).isZero();
    }

    private static void assertRollbackLeavesNoHierarchy(JdbcTemplate jdbc, String materialId) throws SQLException {
        boolean failed = false;
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO material_packaging_hierarchy "
                            + "(id,factory_id,material_type_id,level1_unit,level1_per_level2,"
                            + "level2_unit,created_at,updated_at) VALUES (?,?,?,'kg',10,'box',now(),now())")) {
                insert.setString(1, "rollback-one");
                insert.setString(2, "F-WIDTH");
                insert.setString(3, materialId);
                insert.executeUpdate();
                insert.setString(1, "rollback-two");
                try {
                    insert.executeUpdate();
                } catch (SQLException expectedUniqueFailure) {
                    failed = true;
                    assertThat(expectedUniqueFailure.getSQLState()).isEqualTo("23505");
                    connection.rollback();
                }
            }
        }
        assertThat(failed).as("duplicate hierarchy transaction must fail").isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM material_packaging_hierarchy WHERE material_type_id = ?",
                Integer.class, materialId)).isZero();
    }

    private static int columnLength(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT character_maximum_length FROM information_schema.columns "
                + "WHERE table_schema=current_schema() AND table_name='material_packaging_hierarchy' "
                + "AND column_name='material_type_id'", Integer.class);
    }

    private static Set<String> constraintNames(JdbcTemplate jdbc) {
        return new LinkedHashSet<>(jdbc.queryForList("SELECT c.conname FROM pg_constraint c "
                + "JOIN pg_class r ON r.oid=c.conrelid "
                + "JOIN pg_namespace n ON n.oid=r.relnamespace "
                + "WHERE n.nspname=current_schema() AND r.relname='material_packaging_hierarchy' "
                + "ORDER BY c.conname",
                String.class));
    }

    private static void assertSqlState(String expectedSqlState, JdbcOperation operation) {
        Throwable failure = null;
        try {
            operation.run();
        } catch (Throwable caught) {
            failure = caught;
        }
        assertThat(failure).as("operation must fail with SQLSTATE %s", expectedSqlState).isNotNull();
        SQLException sqlException = findSqlException(failure);
        assertThat((Object) sqlException).as("failure must retain the PostgreSQL SQLException").isNotNull();
        assertThat(sqlException.getSQLState()).isEqualTo(expectedSqlState);
    }

    private static void assertMigrationTimesOutBehindShareLock(DataSource dataSource,
                                                                JdbcTemplate jdbc,
                                                                Flyway upgraded,
                                                                Set<String> constraintsBefore)
            throws SQLException {
        try (Connection lockConnection = dataSource.getConnection()) {
            lockConnection.setAutoCommit(false);
            try (PreparedStatement lock = lockConnection.prepareStatement(
                    "LOCK TABLE material_packaging_hierarchy IN ACCESS SHARE MODE")) {
                lock.execute();
            }
            assertSqlState("55P03", upgraded::migrate);
            assertThat(columnLength(jdbc)).isEqualTo(36);
            assertThat(constraintNames(jdbc)).isEqualTo(constraintsBefore);
            lockConnection.rollback();
        }
    }

    private static void validateAndReadWithLegacyHibernate(DataSource dataSource,
                                                            String materialId,
                                                            String hierarchyId) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DataSource.class, () -> dataSource);
            context.register(LegacyJpaRuntime.class);
            context.refresh();
            EntityManagerFactory emf = context.getBean(EntityManagerFactory.class);
            EntityManager entityManager = emf.createEntityManager();
            try {
                LegacyMaterialPackagingHierarchy loaded = entityManager.find(
                        LegacyMaterialPackagingHierarchy.class, hierarchyId);
                assertThat(loaded).isNotNull();
                assertThat(loaded.materialTypeId).isEqualTo(materialId);
            } finally {
                entityManager.close();
            }
        }
    }

    private static SQLException findSqlException(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = current.getCause();
        }
        return null;
    }

    @FunctionalInterface
    private interface JdbcOperation {
        void run();
    }

    private static void createSchema(String url, String user, String password, String schema) {
        new JdbcTemplate(new DriverManagerDataSource(url, user, password))
                .execute("CREATE SCHEMA \"" + schema + "\"");
    }

    private static void dropSchema(String url, String user, String password, String schema) {
        new JdbcTemplate(new DriverManagerDataSource(url, user, password))
                .execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
    }

    private static Flyway flyway(DataSource dataSource, String schema, Path directory) {
        return Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("filesystem:" + directory.toAbsolutePath()).outOfOrder(false).load();
    }

    private static String resource(String path) throws Exception {
        try (var stream = new ClassPathResource(path).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class JpaRuntime {
        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var bean = new LocalContainerEntityManagerFactoryBean();
            bean.setDataSource(dataSource);
            bean.setManagedTypes(PersistenceManagedTypes.of(MaterialPackagingHierarchy.class.getName()));
            bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            bean.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
            return bean;
        }
    }

    @Entity(name = "LegacyMaterialPackagingHierarchy")
    @Table(name = "material_packaging_hierarchy")
    public static class LegacyMaterialPackagingHierarchy {
        @Id
        @Column(length = 36)
        private String id;

        @Column(name = "material_type_id", nullable = false, length = 36)
        private String materialTypeId;
    }

    @Configuration(proxyBeanMethods = false)
    static class LegacyJpaRuntime {
        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var bean = new LocalContainerEntityManagerFactoryBean();
            bean.setDataSource(dataSource);
            bean.setManagedTypes(PersistenceManagedTypes.of(
                    LegacyMaterialPackagingHierarchy.class.getName()));
            bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            bean.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
            return bean;
        }
    }
}
