package com.cretas.aims.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class CustomerPriceHistoryQuantityMigrationPostgresContractTest {

    private static final String HISTORY = "V20260516_15__customer_price_history.sql";
    private static final String PREVIOUS = "V20261121_02__purchase_order_item_price_trace.sql";
    private static final String MIGRATION = "V20261121_03__customer_price_history_quantity.sql";

    @TempDir Path migrations;

    @Test
    void upgradesExistingRowsBackfillsOnlyUnambiguousSourcesAndCanRunAgain() throws Exception {
        String schema = "trade_quantity_" + UUID.randomUUID().toString().replace("-", "");
        String url = DisposablePostgresTargetGuard.requireSafeUrl(System.getenv("CRETAS_WORKFLOW_PG_URL"));
        String user = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");

        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                statement.execute("SET search_path TO " + schema);
                statement.execute("""
                        CREATE TABLE sales_orders (id VARCHAR(191) PRIMARY KEY, factory_id VARCHAR(191),
                            customer_id VARCHAR(191), deleted_at TIMESTAMP);
                        CREATE TABLE sales_order_items (sales_order_id VARCHAR(191), product_type_id VARCHAR(191),
                            unit_price NUMERIC(15,4), quantity NUMERIC(15,4), deleted_at TIMESTAMP, unit VARCHAR(20),
                            packaging_unit VARCHAR(20), packaging_base_unit VARCHAR(20), packaging_factor NUMERIC(20,8));
                        CREATE TABLE purchase_order_items (id BIGINT PRIMARY KEY);
                        """);
                copyMigration(HISTORY);
                copyMigration(PREVIOUS);
                var prior = flyway(url, user, password, schema);
                // Fixtures stand in for earlier unrelated migrations while Flyway applies the real prior scripts.
                prior.baseline();
                assertThat(prior.migrate().migrationsExecuted).isEqualTo(2);
                assertThat(prior.info().current().getVersion().getVersion()).isEqualTo("20261121.02");
                seedLegacyData(statement);

                copyMigration(MIGRATION);
                var upgraded = flyway(url, user, password, schema);
                assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
                assertThat(upgraded.info().current().getVersion().getVersion()).isEqualTo("20261121.03");

                var after = readHistory(statement);
                assertThat(after).containsOnlyKeys("matched", "ambiguous", "foreign", "wrong-customer", "missing", "deleted");
                assertThat(after.get("matched").quantity()).isEqualByComparingTo("3.1250");
                assertThat(after.get("matched").unit()).isEqualTo("case");
                assertThat(after.get("matched").pack()).isEqualTo("箱");
                assertThat(after.get("matched").base()).isEqualTo("个");
                assertThat(after.get("matched").factor()).isEqualByComparingTo("30");
                for (String id : new String[]{"ambiguous", "foreign", "wrong-customer", "missing", "deleted"}) {
                    assertThat(after.get(id).quantity()).as(id).isNull();
                }
                assertThat(after.values()).allSatisfy(row -> assertThat(row.unitPrice()).isEqualByComparingTo("11"));
                assertThat(upgraded.migrate().migrationsExecuted).isZero();
                statement.execute(Files.readString(migrations.resolve(MIGRATION)));
                assertThat(readHistory(statement)).isEqualTo(after);
                try (var rs = statement.executeQuery("SELECT price_deviation_pct FROM purchase_order_items WHERE id=1")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("1.25");
                }
                assertThat(statement.executeUpdate("UPDATE purchase_order_items SET price_deviation_pct=9999900 WHERE id=1"))
                        .isEqualTo(1);
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static void seedLegacyData(Statement statement) throws SQLException {
        statement.execute("""
                INSERT INTO sales_orders VALUES
                    ('so-match','F','C',NULL), ('so-ambiguous','F','C',NULL),
                    ('so-foreign','OTHER','C',NULL), ('so-wrong-customer','F','OTHER',NULL),
                    ('so-deleted','F','C',CURRENT_TIMESTAMP);
                INSERT INTO sales_order_items (sales_order_id,product_type_id,unit_price,quantity,deleted_at) VALUES
                    ('so-match','P',11,3.1250,NULL), ('so-match','P',13,100,NULL),
                    ('so-match','P',11,100,CURRENT_TIMESTAMP),
                    ('so-ambiguous','P',11,4,NULL), ('so-ambiguous','P',11,5,NULL),
                    ('so-foreign','P',11,20,NULL), ('so-wrong-customer','P',11,30,NULL),
                    ('so-deleted','P',11,40,NULL);
                UPDATE sales_order_items SET unit='case', packaging_unit='箱', packaging_base_unit='个', packaging_factor=30
                    WHERE sales_order_id='so-match' AND unit_price=11 AND deleted_at IS NULL;
                INSERT INTO customer_price_history
                    (id,factory_id,customer_id,product_type_id,unit_price,source_sales_order_id,order_date)
                VALUES ('matched','F','C','P',11,'so-match',CURRENT_DATE),
                    ('ambiguous','F','C','P',11,'so-ambiguous',CURRENT_DATE),
                    ('foreign','F','C','P',11,'so-foreign',CURRENT_DATE),
                    ('wrong-customer','F','C','P',11,'so-wrong-customer',CURRENT_DATE),
                    ('missing','F','C','P',11,'so-missing',CURRENT_DATE),
                    ('deleted','F','C','P',11,'so-deleted',CURRENT_DATE);
                INSERT INTO purchase_order_items (id,price_deviation_pct) VALUES (1,1.25);
                """);
    }

    private record HistoryRow(BigDecimal unitPrice, BigDecimal quantity, String unit, String pack, String base,
                              BigDecimal factor, String updatedAt) {}

    private static Map<String, HistoryRow> readHistory(Statement statement) throws SQLException {
        Map<String, HistoryRow> rows = new LinkedHashMap<>();
        try (var rs = statement.executeQuery("""
                SELECT id, unit_price, quantity, quantity_unit, packaging_unit, packaging_base_unit, packaging_factor,
                    updated_at FROM customer_price_history
                """)) {
            while (rs.next()) {
                rows.put(rs.getString(1), new HistoryRow(rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getBigDecimal(7), rs.getString(8)));
            }
        }
        return rows;
    }

    private void copyMigration(String name) throws java.io.IOException {
        try (var source = new ClassPathResource("db/flyway/" + name).getInputStream()) {
            Files.copy(source, migrations.resolve(name));
        }
    }

    private Flyway flyway(String url, String user, String password, String schema) {
        return Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema)
                .locations("filesystem:" + migrations.toAbsolutePath()).outOfOrder(false).load();
    }
}
