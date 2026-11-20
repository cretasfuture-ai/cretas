package com.cretas.aims.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 真 PostgreSQL: A15 客供料收货允许按超收上限多收 —— 数据库约束必须跟着放开 (独立审查 2026-11-11 发现)。
 *
 * <p>原表 (V20261028_102) 有 {@code CHECK (received_quantity <= expected_quantity)}: 应用层放行的每一笔超收
 * 在提交时都会被数据库拒绝。Mockito 单测看不到约束, H2 create-drop 也不会建它, 只有执行<b>真实迁移文件</b>才量得到。
 * 本测试在一次性库的独立 schema 里按顺序执行 V20261028_102 与 V20261112_21:
 * 之前超收被拒 (阳性对照, 证明约束真的在), 之后超收写得进去, 负数仍被拒。
 *
 * <p>开关: {@code CRETAS_WORKFLOW_PG_VERIFY=true} + {@code CRETAS_WORKFLOW_PG_URL} (见 {@link DisposablePostgresTargetGuard})。
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class CustomerSuppliedReceivedQuantityConstraintPostgresContractTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/flyway");
    private Connection connection;
    private String schema;

    @BeforeEach
    void open() throws Exception {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(System.getenv("CRETAS_WORKFLOW_PG_URL"));
        connection = DriverManager.getConnection(url, System.getenv("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        connection.setAutoCommit(true);
        schema = "a15_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE SCHEMA " + schema);
            st.execute("SET search_path TO " + schema);
            // 原迁移引用的父表: 只建主键, 本测试只关心客供料表自身的约束
            for (String table : new String[]{"factories", "customers", "sales_orders", "raw_material_types",
                    "factory_warehouses"}) {
                st.execute("CREATE TABLE " + table + " (id VARCHAR(191) PRIMARY KEY)");
            }
            st.execute("CREATE TABLE sales_order_items (id BIGINT PRIMARY KEY)");
            st.execute("INSERT INTO factories VALUES ('F006')");
            st.execute("INSERT INTO customers VALUES ('QA-A15-C')");
            st.execute("INSERT INTO sales_orders VALUES ('QA-A15-SO')");
            st.execute("INSERT INTO raw_material_types VALUES ('QA-A15-M')");
            st.execute("INSERT INTO factory_warehouses VALUES ('QA-A15-W')");
        }
    }

    @AfterEach
    void close() throws Exception {
        if (connection != null) {
            try (Statement st = connection.createStatement()) {
                st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
            connection.close();
        }
    }

    private void runMigration(String fileName) throws Exception {
        String sql = Files.readString(MIGRATIONS.resolve(fileName), StandardCharsets.UTF_8);
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        }
    }

    private void insertTask() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("INSERT INTO sales_order_supplied_material_requirements (id, factory_id, customer_id, "
                    + "sales_order_id, material_type_id, material_name, expected_quantity, received_quantity, unit, "
                    + "expected_arrival_at, target_warehouse_id, status) VALUES ('QA-A15-T', 'F006', 'QA-A15-C', "
                    + "'QA-A15-SO', 'QA-A15-M', 'QA-A15-客供鸡胸', 10, 4, 'kg', now(), 'QA-A15-W', 'PARTIALLY_RECEIVED')");
        }
    }

    private void setReceived(String quantity) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("UPDATE sales_order_supplied_material_requirements SET received_quantity = " + quantity
                    + ", status = 'COMPLETED' WHERE id = 'QA-A15-T'");
        }
    }

    private String receivedQuantity() throws SQLException {
        try (Statement st = connection.createStatement();
             var rs = st.executeQuery("SELECT received_quantity FROM sales_order_supplied_material_requirements "
                     + "WHERE id = 'QA-A15-T'")) {
            rs.next();
            return rs.getBigDecimal(1).stripTrailingZeros().toPlainString();
        }
    }

    @Test
    void overReceiptIsRejectedByTheOriginalConstraintAndAcceptedAfterTheMigration() throws Exception {
        runMigration("V20261028_102__sales_order_supplied_material_requirements.sql");
        insertTask();

        // 阳性对照: 原约束真的在, 应收 10 记成 11 被数据库拒绝 —— 这正是 A15 若不改迁移会在生产上撞的
        SQLException before = catchThrowableOfType(() -> setReceived("11"), SQLException.class);
        assertThat((Throwable) before).isNotNull();
        assertThat(before.getMessage()).contains("chk_sosmr_received_quantity");
        assertThat(receivedQuantity()).isEqualTo("4");

        runMigration("V20261112_21__customer_supplied_received_may_exceed_expected.sql");

        assertThatCode(() -> setReceived("11")).doesNotThrowAnyException();
        assertThat(receivedQuantity()).isEqualTo("11");

        // 阴性对照: 下界仍在
        SQLException negative = catchThrowableOfType(() -> setReceived("-1"), SQLException.class);
        assertThat((Throwable) negative).isNotNull();
        assertThat(negative.getMessage()).contains("chk_sosmr_received_quantity");
        assertThat(receivedQuantity()).isEqualTo("11");
    }

    @Test
    void migrationIsIdempotentOnARepeatedRun() throws Exception {
        runMigration("V20261028_102__sales_order_supplied_material_requirements.sql");
        runMigration("V20261112_21__customer_supplied_received_may_exceed_expected.sql");
        assertThatCode(() -> runMigration("V20261112_21__customer_supplied_received_may_exceed_expected.sql"))
                .doesNotThrowAnyException();
        insertTask();
        assertThatCode(() -> setReceived("12.5")).doesNotThrowAnyException();
    }
}
