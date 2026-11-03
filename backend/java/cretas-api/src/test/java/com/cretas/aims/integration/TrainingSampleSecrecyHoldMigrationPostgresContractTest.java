package com.cretas.aims.integration;

import com.cretas.aims.entity.learning.TrainingSampleSecrecyHold;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.tool.schema.spi.SchemaManagementException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * V20261104_11 (训练样本挂起待重判) 在真 PG 上:
 * <ul>
 *   <li>实体 {@link TrainingSampleSecrecyHold} 与迁移建出来的表<b>逐列一致</b> (Hibernate schema validate 只扫这一个实体);</li>
 *   <li>样本类别只认 INTENT / ACTIVE_LEARNING, 工厂不能为空白, 尝试次数不能为负。</li>
 * </ul>
 * 阴性对照: 把迁移里的一列改名后, 同一个 validate 必须失败 (证明 validate 真在比列)。
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class TrainingSampleSecrecyHoldMigrationPostgresContractTest {

    private static final String MIGRATION = "db/flyway/V20261104_11__training_sample_secrecy_holds.sql";
    private static final String ROW = "INSERT INTO training_sample_secrecy_holds (sample_kind, factory_id, payload,"
            + " authorized_at_hold, attempts, created_at) VALUES ";

    @Test
    void migrationMatchesTheEntityAndRejectsUnknownValues() throws Exception {
        String schema = "tssh_" + UUID.randomUUID().toString().replace("-", "");
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        String user = requiredEnv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(MIGRATION));
            // idempotent: Flyway repair / re-run must not fail
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(MIGRATION));

            assertDoesNotThrow(() -> validateEntity(url, user, password, schema), "entity == migrated table");

            assertDoesNotThrow(() -> statement.execute(ROW + "('INTENT', 'F006', '{}', false, 0, now())"));
            assertDoesNotThrow(() -> statement.execute(ROW + "('ACTIVE_LEARNING', 'F006', '{}', true, 3, now())"));
            assertThrows(SQLException.class, () -> statement.execute(ROW + "('GUESS', 'F006', '{}', false, 0, now())"),
                    "unknown sample kind");
            assertThrows(SQLException.class, () -> statement.execute(ROW + "('INTENT', '  ', '{}', false, 0, now())"),
                    "blank factory");
            assertThrows(SQLException.class, () -> statement.execute(ROW + "('INTENT', 'F006', '{}', false, -1, now())"),
                    "negative attempts");
            assertThrows(SQLException.class, () -> statement.execute(ROW + "('INTENT', 'F006', NULL, false, 0, now())"),
                    "payload required");

            // negative control: a renamed column makes the same validation fail
            statement.execute("ALTER TABLE training_sample_secrecy_holds RENAME COLUMN payload TO payload_text");
            assertThrows(SchemaManagementException.class, () -> validateEntity(url, user, password, schema));
        } finally {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static void validateEntity(String url, String user, String password, String schema) {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", url)
                .applySetting("hibernate.connection.username", user)
                .applySetting("hibernate.connection.password", password)
                .applySetting("hibernate.default_schema", schema)
                .applySetting("hibernate.hbm2ddl.auto", "validate")
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .build();
        try {
            new MetadataSources(registry).addAnnotatedClass(TrainingSampleSecrecyHold.class)
                    .buildMetadata().buildSessionFactory().close();
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
