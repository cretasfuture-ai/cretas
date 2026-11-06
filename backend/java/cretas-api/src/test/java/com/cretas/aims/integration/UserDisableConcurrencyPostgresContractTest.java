package com.cretas.aims.integration;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in PostgreSQL barrier: a concurrent role promotion commits while the
 * deactivation UPDATE waits on the same row, and the UPDATE must re-evaluate
 * its protected-role predicate and change zero rows.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserDisableConcurrencyPostgresContractTest {
    private static final String SCHEMA = "user_disable_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired UserRepository repository;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        String raw = System.getenv("CRETAS_WORKFLOW_PG_URL");
        assumeTrue(raw != null && !raw.isBlank());
        String url = DisposablePostgresTargetGuard.requireSafeUrl(raw);
        String user = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"" + SCHEMA + "\"");
        } catch (Exception e) {
            throw new IllegalStateException("unable to create isolated user-disable schema", e);
        }
        String separator = url.contains("?") ? "&" : "?";
        registry.add("spring.datasource.url", () -> url + separator
                + "currentSchema=" + SCHEMA + "&stringtype=unspecified"
                + "&ApplicationName=user-disable-barrier-jpa");
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        // The application entity graph contains legacy duplicate index names and cross-domain
        // FKs that cannot be generated safely in a disposable schema.  The barrier below only
        // needs the real User/Factory mappings; create those two mapped tables explicitly after
        // the JPA context starts, while Hibernate still parses and executes the repository query.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.jpa.properties.hibernate.jdbc.lob.non_contextual_creation", () -> "true");
    }

    @AfterAll
    static void dropSchema() throws Exception {
        String raw = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (raw == null || raw.isBlank()) return;
        String url = DisposablePostgresTargetGuard.requireSafeUrl(raw);
        try (Connection connection = DriverManager.getConnection(url,
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS \"" + SCHEMA + "\" CASCADE");
        }
    }

    @Test
    void waitingAtomicDeactivationRechecksCommittedProtectedRole() throws Exception {
        try (Connection metadata = DriverManager.getConnection(jdbcUrl("user-disable-barrier-metadata"),
                pgUser(), pgPassword())) {
            assertThat(metadata.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
        createMappedTables();
        persistFactory("F-USER-DISABLE-PG");
        User target = new User();
        target.setFactoryId("F-USER-DISABLE-PG");
        target.setUsername("pg-disable-target");
        target.setPasswordHash("test-only");
        target.setRoleCode("operator");
        target.setIsActive(true);
        repository.saveAndFlush(target);
        long id = target.getId();

        CountDownLatch rowLocked = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (Connection locker = DriverManager.getConnection(jdbcUrl("user-disable-barrier-locker"),
                pgUser(), pgPassword());
             Statement statement = locker.createStatement()) {
            locker.setAutoCommit(false);
            statement.executeQuery("SELECT id FROM users WHERE id=" + id + " FOR UPDATE").close();
            statement.execute("UPDATE users SET role_code='factory_super_admin' WHERE id=" + id);
            rowLocked.countDown();

            var future = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status ->
                    repository.deactivateIfEligible("F-USER-DISABLE-PG", id, LocalDateTime.now())));
            assertThat(rowLocked.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitDatabaseLockWait(10)).as("database reports the JPA UPDATE backend waiting on the row lock")
                    .isPositive();
            assertThat(future.isDone()).as("deactivation waits for the role update row lock").isFalse();
            locker.commit();
            assertThat(future.get(10, TimeUnit.SECONDS)).isZero();
        } finally {
            executor.shutdownNow();
        }

        entityManager.clear();
        User reloaded = repository.findById(id).orElseThrow();
        assertThat(reloaded.getIsActive()).isTrue();
        assertThat(reloaded.getRoleCode()).isEqualTo("factory_super_admin");
    }

    private void persistFactory(String id) {
        Factory factory = new Factory();
        factory.setId(id);
        factory.setName(id);
        factory.setType(FactoryType.FACTORY);
        factory.setLevel(0);
        factory.setIsActive(true);
        factory.setManuallyVerified(false);
        factory.setAiWeeklyQuota(20);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.persist(factory);
            entityManager.flush();
        });
    }

    private void createMappedTables() throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl("user-disable-barrier-ddl"),
                pgUser(), pgPassword()); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS users CASCADE");
            statement.execute("DROP TABLE IF EXISTS factories CASCADE");
            statement.execute("CREATE TABLE factories ("
                    + "id varchar(255) PRIMARY KEY, name varchar(255) NOT NULL, "
                    + "type varchar(32) NOT NULL, parent_id varchar(191), level integer NOT NULL, "
                    + "industry varchar(255), address varchar(255), employee_count integer, "
                    + "subscription_plan varchar(255), survey_company_id varchar(255), "
                    + "contact_name varchar(255), contact_phone varchar(255), contact_email varchar(255), "
                    + "is_active boolean NOT NULL, confidence real, factory_year integer, "
                    + "industry_code varchar(255), inference_data jsonb, legacy_id varchar(255), "
                    + "manually_verified boolean NOT NULL, region_code varchar(255), sequence_number integer, "
                    + "ai_weekly_quota integer NOT NULL, created_at timestamp NOT NULL, "
                    + "updated_at timestamp NOT NULL, deleted_at timestamp)");
            statement.execute("CREATE TABLE users ("
                    + "id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, factory_id varchar(255) NOT NULL, "
                    + "username varchar(255) NOT NULL UNIQUE, password_hash varchar(255) NOT NULL, "
                    + "phone varchar(255), email varchar(100), full_name varchar(255), "
                    + "is_active boolean NOT NULL, department varchar(255), position varchar(255), "
                    + "role_code varchar(255), level integer, platform_type varchar(20), reports_to bigint, "
                    + "secondary_reports_to bigint, last_login timestamp, monthly_salary numeric(10,2), "
                    + "expected_work_minutes integer, ccr_rate numeric(8,4), employee_code varchar(10), "
                    + "hire_type varchar(20), contract_end_date date, skill_levels jsonb, "
                    + "hourly_rate numeric(10,2), avatar_url varchar(255), hire_date date, "
                    + "created_at timestamp NOT NULL, updated_at timestamp NOT NULL, deleted_at timestamp)");
        }
    }

    private long awaitDatabaseLockWait(int seconds) throws Exception {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try (Connection observer = DriverManager.getConnection(jdbcUrl("user-disable-barrier-observer"),
                    pgUser(), pgPassword());
                 Statement statement = observer.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT pid FROM pg_stat_activity "
                                 + "WHERE application_name = 'user-disable-barrier-jpa' "
                                 + "AND wait_event_type = 'Lock' AND state = 'active' LIMIT 1")) {
                if (result.next()) {
                    return result.getLong(1);
                }
            }
            Thread.sleep(50);
        }
        return 0;
    }

    private static String jdbcUrl(String applicationName) {
        String raw = System.getenv("CRETAS_WORKFLOW_PG_URL");
        String separator = raw.contains("?") ? "&" : "?";
        return raw + separator + "currentSchema=" + SCHEMA
                + "&stringtype=unspecified&ApplicationName=" + applicationName;
    }

    private static String pgUser() {
        return System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci");
    }

    private static String pgPassword() {
        return System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
    }
}
