package com.cretas.aims.integration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * V20261103_92 on real PostgreSQL: one active capacity row per line + product (generic counts as its
 * own key), retiring a row frees the key, and the database refuses an unknown unit, a non-positive
 * rate or an unknown source; one active requirement per product + device.
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class LineCapacityMigrationPostgresContractTest {

    private static final String CAP = "INSERT INTO production_line_capacity_configs (factory_id, production_line_id,"
            + " product_type_id, capacity_per_hour, capacity_unit, source, actor_type, actor_id, configured_by, effective_from, created_at,"
            + " updated_at) VALUES ";

    @Test
    void activeKeysAreUniqueAndOnlyKnownUnitsAndSourcesAreAccepted() throws Exception {
        String schema = "line_capacity_" + UUID.randomUUID().toString().replace("-", "");
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        String user = requiredEnv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/flyway/V20261103_92__line_capacity_and_equipment_requirements.sql"));

            statement.execute(CAP + "('F1', 'L1', 'PT-1', 100, 'PIECE', 'FACTORY_CONFIG', 'FACTORY_USER', 7, 7, now(), now(), now())");
            statement.execute(CAP + "('F1', 'L1', NULL, 80, 'KG', 'CONVERSATIONAL_BUILD', 'FACTORY_USER', 7, 7, now(), now(), now())");
            assertThrows(SQLException.class, () -> statement.execute(
                    CAP + "('F1', 'L1', 'PT-1', 50, 'PIECE', 'FACTORY_CONFIG', 'FACTORY_USER', 7, 7, now(), now(), now())"), "second active line+product");
            assertThrows(SQLException.class, () -> statement.execute(
                    CAP + "('F1', 'L1', NULL, 50, 'BATCH', 'FACTORY_CONFIG', 'FACTORY_USER', 7, 7, now(), now(), now())"), "second active generic row");

            statement.execute("UPDATE production_line_capacity_configs SET deleted_at = now()"
                    + " WHERE production_line_id = 'L1' AND product_type_id = 'PT-1'");
            assertDoesNotThrow(() -> statement.execute(
                    CAP + "('F1', 'L1', 'PT-1', 50, 'PIECE', 'FACTORY_CONFIG', 'FACTORY_USER', 7, 7, now(), now(), now())"), "retired row frees the key");
            assertDoesNotThrow(() -> statement.execute(
                    CAP + "('F2', 'L1', 'PT-1', 50, 'PIECE', 'FACTORY_CONFIG', 'FACTORY_USER', 7, 7, now(), now(), now())"), "other factory");

            assertThrows(SQLException.class, () -> statement.execute(
                    CAP + "('F1', 'L2', NULL, 50, 'TON', 'FACTORY_CONFIG', 'FACTORY_USER', 7, 7, now(), now(), now())"), "unknown unit");
            assertThrows(SQLException.class, () -> statement.execute(
                    CAP + "('F1', 'L2', NULL, 0, 'KG', 'FACTORY_CONFIG', 'FACTORY_USER', 7, 7, now(), now(), now())"), "zero rate");
            assertThrows(SQLException.class, () -> statement.execute(
                    CAP + "('F1', 'L2', NULL, 5, 'KG', 'GUESS', 'FACTORY_USER', 7, 7, now(), now(), now())"), "unknown source");
            assertThrows(SQLException.class, () -> statement.execute(
                    CAP + "('F1', 'L2', NULL, 5, 'KG', 'FACTORY_CONFIG', 'ROBOT', 7, 7, now(), now(), now())"), "unknown actor type");
            assertThrows(SQLException.class, () -> statement.execute(
                    CAP + "('F1', 'L2', NULL, 5, 'KG', 'FACTORY_CONFIG', 'FACTORY_USER', NULL, 7, now(), now(), now())"), "actor id required");
            assertDoesNotThrow(() -> statement.execute(
                    CAP + "('F1', 'L3', NULL, 5, 'KG', 'CONVERSATIONAL_BUILD', 'PLATFORM_ADMIN', 7, NULL, now(), now(), now())"),
                    "a platform admin build leaves configured_by empty");

            String req = "INSERT INTO product_equipment_requirements (factory_id, product_type_id, equipment_id, source,"
                    + " actor_type, actor_id, configured_by, created_at, updated_at)"
                    + " VALUES ('F1', 'PT-1', 11, 'FACTORY_CONFIG', 'FACTORY_USER', 7, 7, now(), now())";
            statement.execute(req);
            assertThrows(SQLException.class, () -> statement.execute(req), "same device twice for one product");
        } finally {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
