package com.cretas.aims.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class FinishedGoodsStocktakePostgresIntegrationTest {

    @Test
    void migrationPreservesRawRowsAndEnforcesExclusiveFinishedGoodsIdentity() throws Exception {
        String schema = "fg_stocktake_" + UUID.randomUUID().toString().replace("-", "");
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        String user = requiredEnv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
            statement.execute("""
                    CREATE TABLE factory_stocktakes (
                      id varchar(64) primary key, factory_id varchar(64) not null,
                      warehouse_id varchar(64) not null, period_month varchar(7) not null,
                      status varchar(30) not null
                    )
                    """);
            statement.execute("""
                    CREATE TABLE factory_stocktake_items (
                      id varchar(64) primary key, stocktake_id varchar(64) not null,
                      material_batch_id varchar(191) not null
                    )
                    """);
            statement.execute("INSERT INTO factory_stocktakes VALUES ('S-RAW','F006','WH','2026-08','INITIATED')");
            statement.execute("INSERT INTO factory_stocktake_items VALUES ('I-RAW','S-RAW','MB-1')");

            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/flyway/V20261030_24__finished_goods_stocktake_scope.sql"));

            try (ResultSet rs = statement.executeQuery(
                    "SELECT inventory_scope FROM factory_stocktakes WHERE id='S-RAW'")) {
                assertTrue(rs.next());
                assertEquals("RAW_MATERIAL", rs.getString(1));
            }
            statement.execute("""
                    INSERT INTO factory_stocktakes
                      (id,factory_id,warehouse_id,period_month,status,inventory_scope)
                    VALUES ('S-FG','F006','WH','2026-08','INITIATED','FINISHED_GOODS')
                    """);
            statement.execute("""
                    INSERT INTO factory_stocktake_items
                      (id,stocktake_id,finished_goods_batch_id,product_type_id,quantity_unit)
                    VALUES ('I-FG','S-FG','FG-1','P-1','盒')
                    """);
            SQLException invalidBoth = assertThrows(SQLException.class, () -> statement.execute("""
                    INSERT INTO factory_stocktake_items
                      (id,stocktake_id,material_batch_id,finished_goods_batch_id)
                    VALUES ('I-BAD','S-FG','MB-1','FG-1')
                    """));
            assertEquals("23514", invalidBoth.getSQLState());
        } finally {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set");
        return value;
    }
}
