package com.cretas.aims.integration;

import com.cretas.aims.service.finance.FinanceOwnershipSql;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in PostgreSQL proof for the finance owner expression.  The fixture lives in a unique
 * disposable schema so it cannot read or mutate the shared/public business tables.
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class FinanceScopePostgresContractTest {

    @Test
    void ownerProjectionRejectsUnknownAndCrossFactorySourcesAndKeepsCountPageConsistent() throws Exception {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        String user = requiredEnv("CRETAS_WORKFLOW_PG_USER");
        String password = requiredEnv("CRETAS_WORKFLOW_PG_PASSWORD");
        String schema = "finance_scope_verify_" + UUID.randomUUID().toString().replace("-", "");

        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"" + schema + "\"");
            connection.setSchema(schema);
            try {
                createFixture(statement);
                JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                insertFixture(jdbc);

                String owner = FinanceOwnershipSql.arApOwner("t");
                String predicate = " FROM ar_ap_transactions t"
                        + " WHERE t.factory_id=? AND t.deleted_at IS NULL AND " + owner + "=?";

                Integer owner11Count = jdbc.queryForObject(
                        "SELECT count(*)" + predicate, Integer.class, "F1", 11L);
                assertThat(owner11Count).isEqualTo(7);
                BigDecimal owner11Amount = jdbc.queryForObject(
                        "SELECT COALESCE(SUM(t.amount), 0)" + predicate, BigDecimal.class, "F1", 11L);
                assertThat(owner11Amount).isEqualByComparingTo("50.00");

                List<String> owner11Ids = jdbc.query(
                        "SELECT t.id" + predicate + " ORDER BY t.id",
                        (rs, row) -> rs.getString(1), "F1", 11L);
                assertThat(owner11Ids).containsExactly(
                        "AP_SETTLED", "DELIVERY_OWN", "MANUAL_OWN", "RECEIVE_OWN", "RETURN_OWN",
                        "SETTLED_PAYMENT", "SO_OWN");

                Integer owner22Count = jdbc.queryForObject(
                        "SELECT count(*)" + predicate, Integer.class, "F1", 22L);
                assertThat(owner22Count).isEqualTo(2);
                assertThat(jdbc.query(
                        "SELECT t.id" + predicate + " ORDER BY t.id",
                        (rs, row) -> rs.getString(1), "F1", 22L))
                        .containsExactly("MANUAL_HIDDEN", "SO_HIDDEN");

                Integer pageCount = jdbc.queryForObject(
                        "SELECT count(*)" + predicate, Integer.class, "F1", 11L);
                List<String> secondPage = jdbc.query(
                        "SELECT t.id" + predicate + " ORDER BY t.id LIMIT 2 OFFSET 2",
                        (rs, row) -> rs.getString(1), "F1", 11L);
                assertThat(pageCount).isEqualTo(owner11Ids.size());
                assertThat(secondPage).containsExactly("MANUAL_OWN", "RECEIVE_OWN");

                assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM ar_ap_transactions t WHERE t.factory_id=?"
                                + " AND t.id IN ('UNKNOWN_SOURCE', 'BROKEN_DELIVERY', 'CROSS_FACTORY', 'MISMATCHED_DELIVERY', 'MISMATCHED_RETURN')"
                                + " AND " + owner + " IS NULL",
                        Integer.class, "F1")).isEqualTo(5);

                jdbc.update("UPDATE ar_ap_transactions SET amount=100, "
                                + "transaction_date=CURRENT_DATE - INTERVAL '1 day' WHERE id='MANUAL_OWN'");
                String openingSql = java.util.Arrays.stream(
                        com.cretas.aims.repository.finance.ArApTransactionRepository.class.getMethods())
                        .filter(method -> method.getName().equals("findScopedOpeningBalance"))
                        .findFirst().orElseThrow().getAnnotation(org.springframework.data.jpa.repository.Query.class).value();
                BigDecimal opening = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc)
                        .queryForObject(openingSql, java.util.Map.of("factoryId","F1","counterpartyType","CUSTOMER",
                                "counterpartyId","C1","startDate",java.time.LocalDate.now(),"owners",List.of(11L)),BigDecimal.class);
                assertThat(opening).isEqualByComparingTo("100.00");
                assertThat(jdbc.queryForObject(
                        "SELECT balance_after FROM ar_ap_transactions WHERE id='MANUAL_OWN'",
                        BigDecimal.class)).isEqualByComparingTo("9999.00");
                jdbc.update("INSERT INTO ar_ap_transactions (id,factory_id,transaction_type,counterparty_type,operated_by,source_type,source_id,purchase_order_id,amount) VALUES "
                        + "('AP_MANUAL','F1','AP_INVOICE','SUPPLIER',11,NULL,NULL,NULL,5),"
                        + "('PAY_MANUAL','F1','AP_PAYMENT','SUPPLIER',99,'PAYABLE_SETTLEMENT','manual-key',NULL,-5),"
                        + "('AP_UNKNOWN','F1','AP_INVOICE','SUPPLIER',11,'UNKNOWN','bad','PO_OWN',5),"
                        + "('PAY_UNKNOWN','F1','AP_PAYMENT','SUPPLIER',11,'PAYABLE_SETTLEMENT','bad-key','PO_OWN',-5),"
                        + "('AP_RECEIVE','F1','AP_INVOICE','SUPPLIER',99,'PURCHASE_RECEIVE','RECEIVE_OWN','PO_OWN',5),"
                        + "('PAY_RECEIVE','F1','AP_PAYMENT','SUPPLIER',99,'PAYABLE_SETTLEMENT','receive-key','PO_OWN',-5)");
                jdbc.update("INSERT INTO ar_ap_payment_allocations (id,factory_id,payment_transaction_id,payable_transaction_id) VALUES "
                        + "('A_MANUAL','F1','PAY_MANUAL','AP_MANUAL'),('A_UNKNOWN','F1','PAY_UNKNOWN','AP_UNKNOWN'),"
                        + "('A_RECEIVE','F1','PAY_RECEIVE','AP_RECEIVE')");
                assertThat(jdbc.query("SELECT t.id FROM ar_ap_transactions t WHERE t.id IN "
                                + "('PAY_MANUAL','PAY_UNKNOWN','PAY_RECEIVE') AND " + owner + "=11 ORDER BY t.id",
                        (rs,row)->rs.getString(1))).containsExactly("PAY_MANUAL","PAY_RECEIVE");
            } finally {
                connection.setSchema("public");
                statement.execute("DROP SCHEMA \"" + schema + "\" CASCADE");
            }
        }
    }

    private static void createFixture(Statement statement) throws Exception {
        statement.execute("CREATE TABLE sales_orders ("
                + "id varchar(64) PRIMARY KEY, factory_id varchar(64) NOT NULL,"
                + "created_by bigint, deleted_at timestamp NULL)");
        statement.execute("CREATE TABLE purchase_orders ("
                + "id varchar(64) PRIMARY KEY, factory_id varchar(64) NOT NULL,"
                + "created_by bigint, deleted_at timestamp NULL)");
        statement.execute("CREATE TABLE sales_delivery_records ("
                + "id varchar(64) PRIMARY KEY, factory_id varchar(64) NOT NULL,"
                + "sales_order_id varchar(64), deleted_at timestamp NULL)");
        statement.execute("CREATE TABLE purchase_receive_records ("
                + "id varchar(64) PRIMARY KEY, factory_id varchar(64) NOT NULL,"
                + "purchase_order_id varchar(64), deleted_at timestamp NULL)");
        statement.execute("CREATE TABLE return_orders ("
                + "id varchar(64) PRIMARY KEY, factory_id varchar(64) NOT NULL,"
                + "return_type varchar(32), source_order_id varchar(64),"
                + "created_by bigint, deleted_at timestamp NULL)");
        statement.execute("CREATE TABLE ar_ap_transactions ("
                + "id varchar(64) PRIMARY KEY, factory_id varchar(64) NOT NULL,"
                + "deleted_at timestamp NULL, sales_order_id varchar(64),"
                + "purchase_order_id varchar(64), source_type varchar(64), source_id varchar(64),"
                        + "operated_by bigint, transaction_type varchar(64), transaction_date date, amount numeric(18,2),"
                + "balance_after numeric(18,2) DEFAULT 9999, counterparty_type varchar DEFAULT 'CUSTOMER', "
                + "counterparty_id varchar DEFAULT 'C1', approval_status varchar DEFAULT 'APPROVED')");
        statement.execute("CREATE TABLE ar_ap_payment_allocations ("
                + "id varchar(64) PRIMARY KEY, factory_id varchar(64) NOT NULL,"
                + "payment_transaction_id varchar(64) NOT NULL, payable_transaction_id varchar(64) NOT NULL,"
                + "deleted_at timestamp NULL)");
    }

    private static void insertFixture(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO sales_orders VALUES (?,?,?,NULL), (?,?,?,NULL), (?,?,?,NULL)",
                "SO_OWN", "F1", 11L, "SO_HIDDEN", "F1", 22L, "SO_CROSS", "F2", 11L);
        jdbc.update("INSERT INTO purchase_orders VALUES (?, ?, ?, NULL)", "PO_OWN", "F1", 11L);
        jdbc.update("INSERT INTO sales_delivery_records VALUES (?, ?, ?, NULL)",
                "DELIVERY_OWN", "F1", "SO_OWN");
        jdbc.update("INSERT INTO purchase_receive_records VALUES (?, ?, ?, NULL)",
                "RECEIVE_OWN", "F1", "PO_OWN");
        jdbc.update("INSERT INTO return_orders VALUES (?, ?, ?, ?, ?, NULL), (?, ?, ?, ?, ?, NULL)",
                "RETURN_OWN", "F1", "SALES_RETURN", "SO_OWN", 99L,
                "RETURN_MISMATCH", "F1", "SALES_RETURN", "SO_OWN", 99L);

        jdbc.update("INSERT INTO ar_ap_transactions "
                        + "(id, factory_id, deleted_at, sales_order_id, purchase_order_id, source_type, source_id, operated_by, transaction_date, amount) VALUES"
                        + " (?, ?, NULL, NULL, NULL, NULL, NULL, ?, CURRENT_DATE, 10),"
                        + " (?, ?, NULL, NULL, NULL, NULL, NULL, ?, CURRENT_DATE, 10),"
                        + " (?, ?, NULL, ?, NULL, NULL, NULL, ?, CURRENT_DATE, 10),"
                        + " (?, ?, NULL, ?, NULL, NULL, NULL, ?, CURRENT_DATE, 10),"
                        + " (?, ?, NULL, ?, NULL, NULL, NULL, ?, CURRENT_DATE, 10),"
                        + " (?, ?, NULL, NULL, NULL, 'SALES_DELIVERY', ?, ?, CURRENT_DATE, 10),"
                        + " (?, ?, NULL, NULL, NULL, 'PURCHASE_RECEIVE', ?, ?, CURRENT_DATE, 10),"
                        + " (?, ?, NULL, NULL, NULL, 'RETURN_ORDER', ?, ?, CURRENT_DATE, 10),"
                        + " (?, ?, NULL, NULL, NULL, 'UNKNOWN', ?, ?, CURRENT_DATE, 10),"
                        + " (?, ?, NULL, NULL, NULL, 'SALES_DELIVERY', ?, ?, CURRENT_DATE, 10),"
                        + " (?, ?, NULL, ?, NULL, 'SALES_DELIVERY', ?, ?, CURRENT_DATE, 10),"
                        + " (?, ?, NULL, ?, NULL, 'RETURN_ORDER', ?, ?, CURRENT_DATE, 10)",
                "MANUAL_OWN", "F1", 11L,
                "MANUAL_HIDDEN", "F1", 22L,
                "SO_OWN", "F1", "SO_OWN", 99L,
                "SO_HIDDEN", "F1", "SO_HIDDEN", 11L,
                "CROSS_FACTORY", "F1", "SO_CROSS", 11L,
                "DELIVERY_OWN", "F1", "DELIVERY_OWN", 99L,
                "RECEIVE_OWN", "F1", "RECEIVE_OWN", 99L,
                "RETURN_OWN", "F1", "RETURN_OWN", 99L,
                "UNKNOWN_SOURCE", "F1", "UNKNOWN_ID", 11L,
                "BROKEN_DELIVERY", "F1", "MISSING_DELIVERY", 11L,
                "MISMATCHED_DELIVERY", "F1", "SO_HIDDEN", "DELIVERY_OWN", 11L,
                "MISMATCHED_RETURN", "F1", "SO_HIDDEN", "RETURN_MISMATCH", 11L);
        jdbc.update("UPDATE ar_ap_transactions SET transaction_type='AR_INVOICE' "
                + "WHERE factory_id='F1' AND transaction_type IS NULL");

        jdbc.update("INSERT INTO ar_ap_transactions "
                        + "(id, factory_id, deleted_at, purchase_order_id, source_type, source_id, operated_by, transaction_type, transaction_date, amount) "
                        + "VALUES (?, ?, NULL, ?, NULL, NULL, ?, 'AP_INVOICE', CURRENT_DATE, 0), "
                        + "(?, ?, NULL, ?, 'PAYABLE_SETTLEMENT', ?, ?, 'AP_PAYMENT', CURRENT_DATE, 0)",
                "AP_SETTLED", "F1", "PO_OWN", 11L,
                "SETTLED_PAYMENT", "F1", "PO_OWN", "settlement-key", 99L);
        jdbc.update("INSERT INTO ar_ap_payment_allocations "
                        + "(id, factory_id, payment_transaction_id, payable_transaction_id, deleted_at) VALUES (?, ?, ?, ?, NULL)",
                "ALLOC_SETTLED", "F1", "SETTLED_PAYMENT", "AP_SETTLED");
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (name.endsWith("_PASSWORD")) return value == null ? "" : value;
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required PostgreSQL verification environment variable: " + name);
        }
        return value;
    }
}
