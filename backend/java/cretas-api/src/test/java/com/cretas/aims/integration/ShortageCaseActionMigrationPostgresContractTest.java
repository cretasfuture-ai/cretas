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
 * V20261103_124 在真 PG 上: 一个实例一种物料只能有一张在走的采购草稿 (作废后才能再建);
 * 采购草稿必须带物料和采购单; 决定必须带原因。
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class ShortageCaseActionMigrationPostgresContractTest {

    private static final String ACT = "INSERT INTO shortage_case_actions (case_id, factory_id, action_type, material_type_id,"
            + " status, result_ref, quantity, unit, decision, reason, actor_type, actor_id, created_at, updated_at) VALUES ";

    @Test
    void oneLiveDraftPerCaseAndMaterialAndDecisionsNeedAReason() throws Exception {
        String schema = "shortage_action_" + UUID.randomUUID().toString().replace("-", "");
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        String user = requiredEnv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/flyway/V20261103_123__shortage_cases.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/flyway/V20261103_124__shortage_case_actions.sql"));
            statement.execute("INSERT INTO shortage_cases (id, factory_id, source_type, source_id, stage, owner_role,"
                    + " gap_line_count, input_fingerprint, last_refreshed_at, last_refresh_reason, created_at, updated_at)"
                    + " VALUES ('C1', 'F1', 'SALES_ORDER', 'SO-1', 'DETECTED', 'procurement_manager', 1, 'fp', now(), 'x', now(), now())");

            String draft = ACT + "('C1', 'F1', 'PURCHASE_DRAFT', 'M1', 'DONE', 'PO-1', 20, 'kg', NULL, NULL, 'FACTORY_USER', 9, now(), now())";
            statement.execute(draft);
            assertThrows(SQLException.class, () -> statement.execute(
                    ACT + "('C1', 'F1', 'PURCHASE_DRAFT', 'M1', 'DONE', 'PO-2', 20, 'kg', NULL, NULL, 'FACTORY_USER', 9, now(), now())"),
                    "second live draft for the same case + material");
            assertDoesNotThrow(() -> statement.execute(
                    ACT + "('C1', 'F1', 'PURCHASE_DRAFT', 'M2', 'DONE', 'PO-3', 5, 'kg', NULL, NULL, 'FACTORY_USER', 9, now(), now())"),
                    "another material");
            statement.execute("UPDATE shortage_case_actions SET status = 'SUPERSEDED' WHERE result_ref = 'PO-1'");
            assertDoesNotThrow(() -> statement.execute(
                    ACT + "('C1', 'F1', 'PURCHASE_DRAFT', 'M1', 'DONE', 'PO-4', 20, 'kg', NULL, NULL, 'FACTORY_USER', 9, now(), now())"),
                    "superseded draft frees the slot");

            assertThrows(SQLException.class, () -> statement.execute(
                    ACT + "('C1', 'F1', 'PURCHASE_DRAFT', 'M5', 'DONE', NULL, 20, 'kg', NULL, NULL, 'FACTORY_USER', 9, now(), now())"),
                    "draft without a purchase order");
            assertThrows(SQLException.class, () -> statement.execute(
                    ACT + "('C1', 'F1', 'PURCHASE_DRAFT', 'M6', 'DONE', 'PO-9', 0, 'kg', NULL, NULL, 'FACTORY_USER', 9, now(), now())"),
                    "zero quantity");
            assertThrows(SQLException.class, () -> statement.execute(
                    ACT + "('C1', 'F1', 'DECISION', NULL, 'DONE', NULL, NULL, NULL, 'CANCEL', '  ', 'FACTORY_USER', 9, now(), now())"),
                    "decision without a reason");
            assertThrows(SQLException.class, () -> statement.execute(
                    ACT + "('C1', 'F1', 'DECISION', NULL, 'DONE', NULL, NULL, NULL, 'GUESS', 'x', 'FACTORY_USER', 9, now(), now())"),
                    "unknown decision");
            assertDoesNotThrow(() -> statement.execute(
                    ACT + "('C1', 'F1', 'DECISION', NULL, 'DONE', NULL, NULL, NULL, 'MANUAL', '人工跟进', 'FACTORY_USER', 9, now(), now())"));
            assertThrows(SQLException.class, () -> statement.execute(
                    ACT + "('NOPE', 'F1', 'DECISION', NULL, 'DONE', NULL, NULL, NULL, 'MANUAL', 'x', 'FACTORY_USER', 9, now(), now())"),
                    "action for a case that does not exist");
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
