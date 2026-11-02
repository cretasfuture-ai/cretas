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
 * V20261103_129 在真 PG 上: 重排方案挂在实例下、状态受限、已应用必须带候选与时间;
 * 执行台账接受 RESCHEDULE_APPLIED (且必须带方案 id), 原有的两种动作照旧, 未知动作照旧被拒。
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class RescheduleProposalMigrationPostgresContractTest {

    private static final String ACT = "INSERT INTO shortage_case_actions (case_id, factory_id, action_type, material_type_id,"
            + " status, result_ref, quantity, unit, decision, reason, actor_type, actor_id, created_at, updated_at) VALUES ";
    private static final String PROP = "INSERT INTO reschedule_proposals (id, factory_id, case_id, status, baseline, candidates,"
            + " applied_candidate, expires_at, created_by_type, created_by_id, applied_at) VALUES ";

    @Test
    void proposalsBelongToACaseAndTheLedgerAcceptsAppliedReschedules() throws Exception {
        String schema = "reschedule_" + UUID.randomUUID().toString().replace("-", "");
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        String user = requiredEnv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/flyway/V20261103_123__shortage_cases.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/flyway/V20261103_124__shortage_case_actions.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/flyway/V20261103_129__reschedule_proposals.sql"));
            statement.execute("INSERT INTO shortage_cases (id, factory_id, source_type, source_id, stage, owner_role,"
                    + " gap_line_count, input_fingerprint, last_refreshed_at, last_refresh_reason, created_at, updated_at)"
                    + " VALUES ('C1', 'F1', 'SALES_ORDER', 'SO-1', 'DETECTED', 'procurement_manager', 1, 'fp', now(), 'x', now(), now())");

            assertDoesNotThrow(() -> statement.execute(PROP
                    + "('P1', 'F1', 'C1', 'OPEN', '[]', '[]', NULL, now(), 'FACTORY_USER', 7, NULL)"));
            assertDoesNotThrow(() -> statement.execute(PROP
                    + "('P2', 'F1', 'C1', 'APPLIED', '[]', '[]', 'SAME_LINE_POSTPONE', now(), 'FACTORY_USER', 7, now())"));
            assertThrows(SQLException.class, () -> statement.execute(PROP
                    + "('P3', 'F1', 'C1', 'APPLIED', '[]', '[]', NULL, now(), 'FACTORY_USER', 7, NULL)"),
                    "applied without a candidate");
            assertThrows(SQLException.class, () -> statement.execute(PROP
                    + "('P4', 'F1', 'C1', 'GUESS', '[]', '[]', NULL, now(), 'FACTORY_USER', 7, NULL)"),
                    "unknown status");
            assertThrows(SQLException.class, () -> statement.execute(PROP
                    + "('P5', 'F1', 'NOPE', 'OPEN', '[]', '[]', NULL, now(), 'FACTORY_USER', 7, NULL)"),
                    "proposal for a case that does not exist");

            assertDoesNotThrow(() -> statement.execute(ACT
                    + "('C1', 'F1', 'RESCHEDULE_APPLIED', NULL, 'DONE', 'P2', NULL, NULL, NULL, '同线顺延', 'FACTORY_USER', 7, now(), now())"));
            assertThrows(SQLException.class, () -> statement.execute(ACT
                    + "('C1', 'F1', 'RESCHEDULE_APPLIED', NULL, 'DONE', NULL, NULL, NULL, NULL, 'x', 'FACTORY_USER', 7, now(), now())"),
                    "applied reschedule without a proposal");
            assertDoesNotThrow(() -> statement.execute(ACT
                    + "('C1', 'F1', 'DECISION', NULL, 'DONE', NULL, NULL, NULL, 'MANUAL', '人工跟进', 'FACTORY_USER', 7, now(), now())"),
                    "existing action types still accepted");
            assertThrows(SQLException.class, () -> statement.execute(ACT
                    + "('C1', 'F1', 'TELEPORT', NULL, 'DONE', 'x', NULL, NULL, NULL, 'x', 'FACTORY_USER', 7, now(), now())"),
                    "unknown action type still refused");
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
