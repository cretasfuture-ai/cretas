package com.cretas.aims.integration;

import com.cretas.aims.entity.learning.TrainingSampleSecrecyHold;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
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
 * 本 PR 的两个迁移在真 PG 上 (实体与迁移建出来的表<b>逐列一致</b>, Hibernate schema validate 只扫对应实体):
 * <ul>
 *   <li>V20261104_22 训练样本挂起表: 样本类别 / 复核状态只认登记值, REJECTED 必须带时间且不留原文, 工厂不能空白, 尝试次数不能为负;</li>
 *   <li>V20261104_23 保密策略的专属别名列 (Q1): 只收 JSON 数组; 实体 {@link ProcessSecretPolicy} 与
 *       V20261103_72 + V20261104_23 建出的表一致。</li>
 * </ul>
 * 阴性对照: 把迁移里的一列改名后, 同一个 validate 必须失败 (证明 validate 真在比列)。
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class TrainingSampleSecrecyHoldMigrationPostgresContractTest {

    private static final String HOLDS = "db/flyway/V20261104_22__training_sample_secrecy_holds.sql";
    private static final String POLICIES = "db/flyway/V20261103_72__process_secret_access.sql";
    private static final String ALIASES = "db/flyway/V20261104_23__process_secret_policy_aliases.sql";
    private static final String ROW = "INSERT INTO training_sample_secrecy_holds (sample_kind, factory_id, payload,"
            + " review_status, rejected_at, attempts, created_at) VALUES ";
    private static final String POLICY = "INSERT INTO process_secret_policies (factory_id, product_type_id, storage_mode,"
            + " owner_user_id, policy_version, created_by, created_at, updated_at, aliases) VALUES ";

    @Test
    void migrationsMatchTheEntitiesAndRejectUnknownValues() throws Exception {
        String schema = "tssh_" + UUID.randomUUID().toString().replace("-", "");
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        String user = requiredEnv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
            for (String m : new String[]{HOLDS, HOLDS, POLICIES, ALIASES, ALIASES}) {   // re-runs must not fail
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(m));
            }

            assertDoesNotThrow(() -> validate(url, user, password, schema, TrainingSampleSecrecyHold.class),
                    "hold entity == migrated table");
            assertDoesNotThrow(() -> validate(url, user, password, schema, ProcessSecretPolicy.class),
                    "policy entity (with aliases) == migrated table");

            // ---- holds ----
            assertDoesNotThrow(() -> statement.execute(ROW + "('INTENT', 'F006', '{}', 'PENDING', NULL, 0, now())"));
            assertDoesNotThrow(() -> statement.execute(ROW + "('ACTIVE_LEARNING', 'F006', '"
                    + TrainingSampleSecrecyHold.DROPPED_PAYLOAD + "', 'REJECTED', now(), 1, now())"));
            // second review, item 1: a REJECTED row keeps no raw text (ck_tssh_rejected_no_raw)
            assertThrows(SQLException.class, () -> statement.execute(ROW
                            + "('INTENT', 'F006', '{\"userInput\":\"398礼盒的配方改一下\"}', 'REJECTED', now(), 1, now())"),
                    "rejected row still carrying the raw text");
            assertThrows(SQLException.class, () -> statement.execute(ROW + "('GUESS', 'F006', '{}', 'PENDING', NULL, 0, now())"),
                    "unknown sample kind");
            assertThrows(SQLException.class, () -> statement.execute(ROW + "('INTENT', 'F006', '{}', 'DELETED', NULL, 0, now())"),
                    "unknown review status");
            assertThrows(SQLException.class, () -> statement.execute(ROW + "('INTENT', 'F006', '{}', 'REJECTED', NULL, 0, now())"),
                    "rejected without a time");
            assertThrows(SQLException.class, () -> statement.execute(ROW + "('INTENT', '  ', '{}', 'PENDING', NULL, 0, now())"),
                    "blank factory");
            assertThrows(SQLException.class, () -> statement.execute(ROW + "('INTENT', 'F006', '{}', 'PENDING', NULL, -1, now())"),
                    "negative attempts");
            assertThrows(SQLException.class, () -> statement.execute(ROW + "('INTENT', 'F006', NULL, 'PENDING', NULL, 0, now())"),
                    "payload required");

            // ---- aliases ----
            assertDoesNotThrow(() -> statement.execute(POLICY + "('F006', 'P1', 'CLOUD_RESTRICTED', 1, 1, 1, now(), now(), '[\"别名\"]')"));
            assertDoesNotThrow(() -> statement.execute(POLICY + "('F006', 'P2', 'CLOUD_RESTRICTED', 1, 1, 1, now(), now(), NULL)"));
            assertThrows(SQLException.class, () -> statement.execute(
                    POLICY + "('F006', 'P3', 'CLOUD_RESTRICTED', 1, 1, 1, now(), now(), '别名')"), "aliases must be a JSON array");

            // negative controls: a renamed column makes the same validation fail
            statement.execute("ALTER TABLE training_sample_secrecy_holds RENAME COLUMN review_status TO status");
            assertThrows(RuntimeException.class, () -> validate(url, user, password, schema, TrainingSampleSecrecyHold.class));
            statement.execute("ALTER TABLE process_secret_policies RENAME COLUMN aliases TO alias_list");
            assertThrows(RuntimeException.class, () -> validate(url, user, password, schema, ProcessSecretPolicy.class));
        } finally {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static void validate(String url, String user, String password, String schema, Class<?> entity) {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", url)
                .applySetting("hibernate.connection.username", user)
                .applySetting("hibernate.connection.password", password)
                .applySetting("hibernate.default_schema", schema)
                .applySetting("hibernate.hbm2ddl.auto", "validate")
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .build();
        try {
            new MetadataSources(registry).addAnnotatedClass(entity).buildMetadata().buildSessionFactory().close();
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
