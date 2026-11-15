package com.cretas.aims.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * V20261115_21 (数字班组) 在真实 PostgreSQL 上: 脚本可重复执行; 额度四态的 CHECK 兜底 ——
 * 只有已办成能扣额度且必须扣 1、有确认人和真实日期; 没办成 / 结果不明必须有日期; 员工 key 与原因受约束。
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class DigitalCrewMigrationPostgresContractTest {

    private static final String MIGRATION = "db/flyway/V20261115_21__digital_crew.sql";

    private static final String INSERT = "INSERT INTO digital_crew_work_items (factory_id, member_key, intent_code, title,"
            + " user_input, required_permission, module, status, quota_cost, created_by_user_id, created_by_name,"
            + " confirmed_by_user_id, resolved_date) VALUES ('F1', '%s', 'X', 't', 'u', 'production:read_write',"
            + " 'production', '%s', %d, 7, 'n', %s, %s)";

    private static String row(String member, String status, int quota, String confirmer, String date) {
        return String.format(INSERT, member, status, quota, confirmer, date);
    }

    @Test
    void quotaOnlyForReadBackDoneAndScriptReruns() throws Exception {
        String schema = "crew_" + UUID.randomUUID().toString().replace("-", "");
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        String user = requiredEnv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement st = connection.createStatement()) {
            st.execute("CREATE SCHEMA " + schema);
            st.execute("SET search_path TO " + schema);
            st.execute(script());
            st.execute(script()); // idempotent

            assertDoesNotThrow(() -> st.execute(row("planner", "DELEGATED", 0, "NULL", "NULL")));
            assertDoesNotThrow(() -> st.execute(row("planner", "DONE", 1, "8", "CURRENT_DATE")));
            assertDoesNotThrow(() -> st.execute(row("planner", "NOT_DONE", 0, "8", "CURRENT_DATE")));
            assertDoesNotThrow(() -> st.execute(row("planner", "OUTCOME_UNKNOWN", 0, "8", "CURRENT_DATE")));

            assertThrows(SQLException.class, () -> st.execute(row("planner", "DONE", 0, "8", "CURRENT_DATE")), "done must charge 1");
            assertThrows(SQLException.class, () -> st.execute(row("planner", "DONE", 1, "NULL", "CURRENT_DATE")), "done needs confirmer");
            assertThrows(SQLException.class, () -> st.execute(row("planner", "DONE", 1, "8", "NULL")), "done needs real date");
            assertThrows(SQLException.class, () -> st.execute(row("planner", "DELEGATED", 1, "NULL", "NULL")), "delegated never charges");
            assertThrows(SQLException.class, () -> st.execute(row("planner", "OUTCOME_UNKNOWN", 1, "8", "CURRENT_DATE")), "unknown never charges");
            assertThrows(SQLException.class, () -> st.execute(row("planner", "NOT_DONE", 0, "8", "NULL")), "not done needs date");
            assertThrows(SQLException.class, () -> st.execute(row("planner", "MAYBE", 0, "NULL", "NULL")), "unknown status");
            assertThrows(SQLException.class, () -> st.execute(row("robot", "DELEGATED", 0, "NULL", "NULL")), "unknown member");
            assertThrows(SQLException.class, () -> st.execute("INSERT INTO digital_crew_setting_logs (factory_id, change_kind,"
                    + " actor_name, reason, before_json, after_json) VALUES ('F1', 'MEMBERS', 'n', ' 好 ', '{}', '{}')"), "reason >= 2");
            assertThrows(SQLException.class, () -> st.execute("INSERT INTO digital_crew_member_settings (factory_id, member_key,"
                    + " enabled) VALUES ('F1', 'planner', true), ('F1', 'planner', false)"), "one row per member");
        } finally {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 Statement st = connection.createStatement()) {
                st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static String script() throws java.io.IOException {
        try (var in = new ClassPathResource(MIGRATION).getInputStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
