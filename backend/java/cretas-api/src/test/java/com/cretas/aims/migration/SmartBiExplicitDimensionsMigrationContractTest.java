package com.cretas.aims.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real PostgreSQL contract for V20261103.83; no H2 fallback is representative here. */
class SmartBiExplicitDimensionsMigrationContractTest {
    private static final String ENV = "FACTORY_SELECTION_TEST_PG_URL";
    private static final String BASELINE = "20261103.82";
    private static final String TARGET = "20261103.83";

    @Test
    void discoversAppliesAndRepeatsTheExplicitDimensionsMigration() throws Exception {
        String rawUrl = System.getenv(ENV);
        Assumptions.assumeTrue(rawUrl != null && !rawUrl.isBlank(),
                ENV + " is required for the disposable PostgreSQL contract test");
        Properties credentials = postgresCredentials(rawUrl);
        String schema = "smartbi_dim_" + UUID.randomUUID().toString().replace("-", "");

        try (Connection connection = DriverManager.getConnection(credentials.getProperty("jdbcUrl"), credentials)) {
            createFixture(connection, schema);

            Flyway flyway = Flyway.configure()
                    .dataSource(credentials.getProperty("jdbcUrl"), credentials.getProperty("user"), credentials.getProperty("password"))
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/flyway")
                    .baselineVersion(BASELINE)
                    .baselineOnMigrate(true)
                    .outOfOrder(false)
                    .load();

            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(currentVersion(connection, schema)).isEqualTo(TARGET);
            assertThat(historySuccess(connection, schema)).isTrue();
            assertThat(column(connection, schema, "sales_orders", "cf_smartbi_sales_channel", "data_type"))
                    .isEqualTo("text");
            assertThat(column(connection, schema, "sales_orders", "cf_smartbi_sales_channel", "is_nullable"))
                    .isEqualTo("YES");
            assertThat(column(connection, schema, "sales_orders", "cf_smartbi_sales_channel", "column_default"))
                    .isNull();
            assertThat(column(connection, schema, "sales_orders", "cf_smartbi_department_id", "data_type"))
                    .isEqualTo("text");
            assertThat(column(connection, schema, "sales_orders", "cf_smartbi_department_id", "is_nullable"))
                    .isEqualTo("YES");

            assertThat((BigDecimal) scalar(connection, "SELECT total_amount FROM " + schema + ".sales_orders WHERE id='F006-ORDER'"))
                    .isEqualByComparingTo(new BigDecimal("42.00"));
            assertThat((BigDecimal) scalar(connection, "SELECT total_amount FROM " + schema + ".sales_orders WHERE id='F001-ORDER'"))
                    .isEqualByComparingTo(new BigDecimal("99.00"));
            assertThat(scalar(connection, "SELECT cf_smartbi_sales_channel FROM " + schema + ".sales_orders WHERE id='F006-ORDER'"))
                    .isNull();

            assertThat(count(connection, "SELECT count(*) FROM " + schema
                    + ".canvas_dynamic_field WHERE module_code='sales_order' AND field_code IN ('smartbi_sales_channel','smartbi_department_id')"))
                    .isEqualTo(3L);
            assertThat(scalar(connection, "SELECT label FROM " + schema
                    + ".canvas_dynamic_field WHERE factory_id='F006' AND field_code='smartbi_sales_channel'"))
                    .isEqualTo("KEEP-ME");
            assertThat(scalar(connection, "SELECT config->>'preserve' FROM " + schema
                    + ".canvas_dynamic_field WHERE factory_id='F006' AND field_code='smartbi_sales_channel'"))
                    .isEqualTo("true");
            assertThat(count(connection, "SELECT count(*) FROM " + schema
                    + ".canvas_dynamic_field WHERE factory_id='F001' AND field_code='smartbi_department_id'"))
                    .isEqualTo(0L);

            assertThat(count(connection, "SELECT count(*) FROM " + schema
                    + ".canvas_dynamic_field WHERE factory_id='F006' AND field_code='smartbi_department_id'"))
                    .isEqualTo(1L);
            assertThat(scalar(connection, "SELECT config->'options'->0->>'value' FROM " + schema
                    + ".canvas_dynamic_field WHERE factory_id='F006' AND field_code='smartbi_department_id'"))
                    .isEqualTo("10");
            assertThat(scalar(connection, "SELECT config->'options'->0->>'label' FROM " + schema
                    + ".canvas_dynamic_field WHERE factory_id='F006' AND field_code='smartbi_department_id'"))
                    .isEqualTo("生产");
            assertThat(count(connection, "SELECT count(*) FROM " + schema
                    + ".canvas_dynamic_field WHERE factory_id='F006' AND field_code='smartbi_department_id'"
                    + " AND config::text LIKE '%停用%'"))
                    .isEqualTo(0L);

            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(0);
            assertThat(count(connection, "SELECT count(*) FROM " + schema
                    + ".flyway_schema_history WHERE version='" + TARGET + "'"))
                    .isEqualTo(1L);
            assertThat(currentVersion(connection, schema)).isEqualTo(TARGET);
        } finally {
            dropSchema(credentials, schema);
        }
    }

    private static Properties postgresCredentials(String rawUrl) {
        URI uri = URI.create(rawUrl);
        if (!"postgresql".equalsIgnoreCase(uri.getScheme())
                || !("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()))
                || uri.getPort() != 55439) {
            throw new IllegalStateException(ENV + " must point to owned localhost:55439");
        }
        String user = uri.getUserInfo() == null || uri.getUserInfo().isBlank()
                ? "codex" : uri.getUserInfo();
        String password = System.getenv().getOrDefault("FACTORY_SELECTION_TEST_PG_PASSWORD", "");
        Properties properties = new Properties();
        properties.setProperty("jdbcUrl", "jdbc:postgresql://" + uri.getHost() + ":" + uri.getPort()
                + (uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/postgres" : uri.getRawPath()));
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        return properties;
    }

    private static void createFixture(Connection connection, String schema) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
            statement.execute("CREATE TABLE factories (id VARCHAR(255) PRIMARY KEY)");
            statement.execute("CREATE TABLE sales_orders ("
                    + "id VARCHAR(191) PRIMARY KEY, factory_id VARCHAR(191) NOT NULL, "
                    + "order_date DATE NOT NULL, total_amount NUMERIC(15,2) DEFAULT 0, "
                    + "status VARCHAR(32) DEFAULT 'DRAFT' NOT NULL, deleted_at TIMESTAMP)");
            statement.execute("CREATE TABLE departments ("
                    + "id INTEGER PRIMARY KEY, factory_id VARCHAR(191) NOT NULL, name VARCHAR(100) NOT NULL, "
                    + "is_active BOOLEAN NOT NULL, deleted_at TIMESTAMP)");
            statement.execute("CREATE TABLE canvas_dynamic_field ("
                    + "id UUID PRIMARY KEY DEFAULT gen_random_uuid(), factory_id VARCHAR(50), "
                    + "module_code VARCHAR(50) NOT NULL, field_code VARCHAR(100) NOT NULL, "
                    + "field_type VARCHAR(20) NOT NULL, label VARCHAR(200) NOT NULL, "
                    + "config JSONB NOT NULL DEFAULT '{}'::jsonb, sort_order INTEGER DEFAULT 0, "
                    + "status VARCHAR(20) DEFAULT 'PENDING_DDL', column_name VARCHAR(100), "
                    + "UNIQUE(factory_id,module_code,field_code))");
            statement.execute("INSERT INTO factories(id) VALUES ('F006'),('F001')");
            statement.execute("INSERT INTO sales_orders(id,factory_id,order_date,total_amount,status) VALUES "
                    + "('F006-ORDER','F006','2026-10-01',42.00,'CONFIRMED'),"
                    + "('F001-ORDER','F001','2026-10-01',99.00,'CONFIRMED')");
            statement.execute("INSERT INTO departments(id,factory_id,name,is_active,deleted_at) VALUES "
                    + "(10,'F006','生产',true,NULL),(11,'F006','停用',false,NULL),"
                    + "(12,'F006','删除',true,CURRENT_TIMESTAMP),(20,'F001','外部',true,NULL)");
            statement.execute("INSERT INTO canvas_dynamic_field(id,factory_id,module_code,field_code,field_type,label,config,sort_order,status,column_name) VALUES "
                    + "('00000000-0000-0000-0000-000000000006','F006','sales_order','smartbi_sales_channel','TEXT','KEEP-ME',"
                    + "'{\"preserve\":true,\"old\":\"value\"}'::jsonb,7,'INACTIVE','legacy_channel'),"
                    + "('00000000-0000-0000-0000-000000000001','F001','sales_order','smartbi_sales_channel','TEXT','F001-KEEP',"
                    + "'{\"preserve\":true}'::jsonb,8,'ACTIVE','legacy_channel')");
        }
    }

    private static void dropSchema(Properties credentials, String schema) {
        try (Connection cleanup = DriverManager.getConnection(credentials.getProperty("jdbcUrl"), credentials);
             Statement statement = cleanup.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } catch (SQLException error) {
            throw new IllegalStateException("failed to drop the owned test schema " + schema, error);
        }
    }

    private static Object scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getObject(1);
        }
    }

    private static long count(Connection connection, String sql) throws SQLException {
        return ((Number) scalar(connection, sql)).longValue();
    }

    private static String currentVersion(Connection connection, String schema) throws SQLException {
        return String.valueOf(scalar(connection, "SELECT version FROM " + schema
                + ".flyway_schema_history WHERE success=true ORDER BY installed_rank DESC LIMIT 1"));
    }

    private static boolean historySuccess(Connection connection, String schema) throws SQLException {
        return Boolean.TRUE.equals(scalar(connection, "SELECT success FROM " + schema
                + ".flyway_schema_history WHERE version='" + TARGET + "'"));
    }

    private static String column(Connection connection, String schema, String table, String name, String field)
            throws SQLException {
        Object value = scalar(connection, "SELECT " + field + " FROM information_schema.columns WHERE table_schema='"
                + schema + "' AND table_name='" + table + "' AND column_name='" + name + "'");
        return value == null ? null : value.toString();
    }
}
