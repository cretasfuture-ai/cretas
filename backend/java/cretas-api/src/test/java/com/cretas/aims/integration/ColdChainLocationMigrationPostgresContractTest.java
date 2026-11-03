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
 * V20261103_160 在真 PG 上 (缺口 15): 一台设备同一时刻只有一条「还在生效」的库位, 关掉后才能开下一条;
 * 时段结束必须晚于开始; 设备 / 仓库 / 告警都要真实存在; 同一告警同一批次只转一次质检。
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class ColdChainLocationMigrationPostgresContractTest {

    @Test
    void oneOpenLocationPerEquipmentAndOneHoldPerAlertBatch() throws Exception {
        String schema = "cold_chain_loc_" + UUID.randomUUID().toString().replace("-", "");
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        String user = requiredEnv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
            // 被引用的三张表只建主键 (真表由更早的迁移建, 这里只验本迁移的约束)
            statement.execute("CREATE TABLE cold_chain_equipment (id BIGSERIAL PRIMARY KEY)");
            statement.execute("CREATE TABLE factory_warehouses (id VARCHAR(64) PRIMARY KEY)");
            statement.execute("CREATE TABLE cold_chain_alerts (id BIGSERIAL PRIMARY KEY)");
            statement.execute("INSERT INTO cold_chain_equipment (id) VALUES (1), (2)");
            statement.execute("INSERT INTO factory_warehouses (id) VALUES ('W1'), ('W2')");
            statement.execute("INSERT INTO cold_chain_alerts (id) VALUES (10)");
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/flyway/V20261103_160__cold_chain_equipment_locations.sql"));

            String loc = "INSERT INTO cold_chain_equipment_locations (factory_id, equipment_id, warehouse_id,"
                    + " effective_from, effective_to) VALUES ";
            statement.execute(loc + "('F1', 1, 'W1', '2026-10-01 00:00', NULL)");
            assertThrows(SQLException.class, () -> statement.execute(loc + "('F1', 1, 'W2', '2026-10-05 00:00', NULL)"),
                    "second open location for the same equipment");
            assertDoesNotThrow(() -> statement.execute(loc + "('F1', 2, 'W2', '2026-10-05 00:00', NULL)"), "other equipment");
            statement.execute("UPDATE cold_chain_equipment_locations SET effective_to = '2026-10-05 00:00'"
                    + " WHERE equipment_id = 1 AND effective_to IS NULL");
            assertDoesNotThrow(() -> statement.execute(loc + "('F1', 1, 'W2', '2026-10-05 00:00', NULL)"),
                    "closed span frees the equipment");
            assertThrows(SQLException.class, () -> statement.execute(loc + "('F1', 1, 'W1', '2026-10-09 00:00', '2026-10-08 00:00')"),
                    "span ends before it starts");
            assertThrows(SQLException.class, () -> statement.execute(loc + "('F1', 99, 'W1', '2026-10-09 00:00', '2026-10-10 00:00')"),
                    "equipment that does not exist");
            assertThrows(SQLException.class, () -> statement.execute(loc + "('F1', 2, 'NOPE', '2026-10-09 00:00', '2026-10-10 00:00')"),
                    "warehouse that does not exist");

            String hold = "INSERT INTO cold_chain_excursion_holds (factory_id, alert_id, target_type, target_id,"
                    + " exposure_minutes, previous_status, held_status, held_by) VALUES ";
            statement.execute(hold + "('F1', 10, 'RAW_MATERIAL_BATCH', 'B', 180, 'AVAILABLE', 'INSPECTING', 9)");
            assertThrows(SQLException.class, () -> statement.execute(
                    hold + "('F1', 10, 'RAW_MATERIAL_BATCH', 'B', 180, 'AVAILABLE', 'INSPECTING', 9)"), "same batch twice for one alert");
            assertThrows(SQLException.class, () -> statement.execute(
                    hold + "('F1', 10, 'GUESS', 'C', 30, 'AVAILABLE', 'INSPECTING', 9)"), "unknown target type");
            assertThrows(SQLException.class, () -> statement.execute(
                    hold + "('F1', 10, 'FINISHED_GOODS', 'D', -1, 'AVAILABLE', 'PENDING_INSPECTION', 9)"), "negative exposure");
            assertDoesNotThrow(() -> statement.execute(
                    hold + "('F1', 10, 'FINISHED_GOODS', 'U', NULL, 'AVAILABLE', 'PENDING_INSPECTION', 9)"),
                    "uncertain exposure is stored as unknown, never defaulted to 0");
            assertThrows(SQLException.class, () -> statement.execute(
                    hold + "('F1', 404, 'FINISHED_GOODS', 'E', 1, 'AVAILABLE', 'PENDING_INSPECTION', 9)"), "alert that does not exist");
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
