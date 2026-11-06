package com.cretas.aims.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** Runs the production migration unchanged on an owned PostgreSQL schema. */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class InsertSlotPreviewMigrationPostgresContractTest {
    @Test
    void additiveMigrationPreservesLegacySlotAndNeverInventsAUnit() throws Exception {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(System.getenv("CRETAS_WORKFLOW_PG_URL"));
        String user = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        String schema = "aps_preview_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
            s.execute("SET search_path TO " + schema);
            s.execute("CREATE TABLE insert_slots(id varchar(36) PRIMARY KEY, factory_id varchar(50), available_capacity numeric, status varchar(20))");
            s.execute("INSERT INTO insert_slots VALUES ('legacy','F1',500,'available')");
            ScriptUtils.executeSqlScript(c, new ClassPathResource("db/flyway/V20261107_1__insert_slot_previews.sql"));
            try (var rows = s.executeQuery("SELECT id,factory_id,available_capacity,status FROM insert_slots")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("legacy");
                assertThat(rows.getString(2)).isEqualTo("F1"); assertThat(rows.getBigDecimal(3)).isEqualByComparingTo("500");
                assertThat(rows.getString(4)).isEqualTo("available"); assertThat(rows.next()).isFalse();
            }
            try (var rows = s.executeQuery("SELECT count(*) FROM insert_slot_previews")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isZero();
            }
            String columns = "(id,factory_id,slot_id,product_type_id,preview_quantity,quantity_unit,planned_unit,production_line_id,window_start,window_end,impact_level,requires_approval,affected_plan_count,candidate_number)";
            String values = "('preview','F1','legacy','P1',17,'PIECE','个','L1',now(),now()+interval '2 hours','none',false,0,1)";
            s.execute("INSERT INTO insert_slot_previews " + columns + " VALUES " + values);
            try (var rows = s.executeQuery("SELECT planned_unit,quantity_unit FROM insert_slot_previews")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("个"); assertThat(rows.getString(2)).isEqualTo("PIECE");
            }
            assertThatThrownBy(() -> s.execute("INSERT INTO insert_slot_previews " + columns + " VALUES " + values.replace("'preview'", "'second'")))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> s.execute("INSERT INTO insert_slot_previews " + columns + " VALUES " + values.replace("'preview'", "'missing-unit'").replace("'PIECE'", "NULL").replace(",1)", ",2)")))
                    .isInstanceOf(SQLException.class);
        } finally {
            try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }
}
