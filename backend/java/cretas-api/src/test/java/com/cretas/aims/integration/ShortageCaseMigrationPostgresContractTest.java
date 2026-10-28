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
 * V20261103_96 在真 PG 上: 同一来源只能有一个未关闭的缺料实例 (部分唯一索引), 关了才能再开;
 * 阶段 / 来源 / 留痕类型只认登记过的值; 缺口行数量不能为负, 一个实例里同一物料只一行。
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class ShortageCaseMigrationPostgresContractTest {

    private static final String CASE = "INSERT INTO shortage_cases (id, factory_id, source_type, source_id, stage, owner_role,"
            + " gap_line_count, input_fingerprint, last_refreshed_at, last_refresh_reason, created_at, updated_at) VALUES ";

    private static String caseRow(String id, String factory, String source, String stage) {
        return CASE + "('" + id + "', '" + factory + "', 'SALES_ORDER', '" + source + "', '" + stage
                + "', 'procurement_manager', 1, 'fp', now(), 'SO_FINANCE_APPROVED', now(), now())";
    }

    @Test
    void oneOpenCasePerSourceAndOnlyKnownValues() throws Exception {
        String schema = "shortage_case_" + UUID.randomUUID().toString().replace("-", "");
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        String user = requiredEnv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/flyway/V20261103_96__shortage_cases.sql"));

            statement.execute(caseRow("C1", "F1", "SO-1", "DETECTED"));
            assertThrows(SQLException.class, () -> statement.execute(caseRow("C2", "F1", "SO-1", "DETECTED")),
                    "second open case for the same sales order");
            assertDoesNotThrow(() -> statement.execute(caseRow("C3", "F2", "SO-1", "DETECTED")), "other factory");
            statement.execute("UPDATE shortage_cases SET closed_at = now(), stage = 'CLOSED' WHERE id = 'C1'");
            assertDoesNotThrow(() -> statement.execute(caseRow("C4", "F1", "SO-1", "DETECTED")), "closed case frees the source");

            assertThrows(SQLException.class, () -> statement.execute(caseRow("C5", "F1", "SO-2", "SOLVED")), "unknown stage");
            assertThrows(SQLException.class, () -> statement.execute(CASE
                    + "('C6', 'F1', 'GUESS', 'SO-3', 'DETECTED', 'procurement_manager', 0, 'fp', now(), 'x', now(), now())"),
                    "unknown source type");

            String line = "INSERT INTO shortage_case_lines (case_id, factory_id, material_type_id, unit, required_qty,"
                    + " released_qty, inspecting_qty, on_order_qty, gap_qty, created_at, updated_at) VALUES ";
            statement.execute(line + "('C4', 'F1', 'M1', 'kg', 10, 4, 2, 3, 6, now(), now())");
            assertThrows(SQLException.class, () -> statement.execute(line + "('C4', 'F1', 'M1', 'kg', 1, 0, 0, 0, 1, now(), now())"),
                    "same material twice in one case");
            assertThrows(SQLException.class, () -> statement.execute(line + "('C4', 'F1', 'M2', 'kg', 1, 0, 0, 0, -1, now(), now())"),
                    "negative gap");
            assertThrows(SQLException.class, () -> statement.execute(line + "('NOPE', 'F1', 'M3', 'kg', 1, 0, 0, 0, 1, now(), now())"),
                    "line for a case that does not exist");
            assertDoesNotThrow(() -> statement.execute(line + "('C4', 'F1', 'M4', NULL, 1, 0, 0, 0, 1, now(), now())"),
                    "unit may be unknown (shown as 单位未知, never defaulted)");

            String ev = "INSERT INTO shortage_case_events (case_id, factory_id, event_type, trigger_reason, actor_type, created_at, updated_at) VALUES ";
            statement.execute(ev + "('C4', 'F1', 'CREATED', 'SO_FINANCE_APPROVED', 'SYSTEM', now(), now())");
            assertThrows(SQLException.class, () -> statement.execute(ev + "('C4', 'F1', 'GUESSED', 'x', 'SYSTEM', now(), now())"),
                    "unknown event type");
            assertThrows(SQLException.class, () -> statement.execute(ev + "('C4', 'F1', 'NOTE', 'x', 'ROBOT', now(), now())"),
                    "unknown actor type");
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
