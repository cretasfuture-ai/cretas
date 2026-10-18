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
                assertThat(jdbc.queryForObject("SELECT " + FinanceOwnershipSql.arApOwner("ledger")
                                + " FROM ar_ap_transactions ledger WHERE ledger.id='REFUND_OWN'",
                        Long.class)).isEqualTo(11L);
                String predicate = " FROM ar_ap_transactions t"
                        + " WHERE t.factory_id=? AND t.deleted_at IS NULL AND " + owner + "=?";

                Integer owner11Count = jdbc.queryForObject(
                        "SELECT count(*)" + predicate, Integer.class, "F1", 11L);
                assertThat(owner11Count).isEqualTo(9);
                BigDecimal owner11Amount = jdbc.queryForObject(
                        "SELECT COALESCE(SUM(t.amount), 0)" + predicate, BigDecimal.class, "F1", 11L);
                assertThat(owner11Amount).isEqualByComparingTo("50.00");

                List<String> owner11Ids = jdbc.query(
                        "SELECT t.id" + predicate + " ORDER BY t.id",
                        (rs, row) -> rs.getString(1), "F1", 11L);
                assertThat(owner11Ids).containsExactly(
                        "AP_SETTLED", "DELIVERY_OWN", "MANUAL_OWN", "RECEIVE_OWN", "REFUND_OWN",
                        "REFUND_REVERSAL", "RETURN_OWN",
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
                                + " AND t.id IN ('UNKNOWN_SOURCE', 'BROKEN_DELIVERY', 'CROSS_FACTORY', 'MISMATCHED_DELIVERY', 'MISMATCHED_RETURN', 'REFUND_BAD_TYPE', 'REFUND_DELETED', 'REFUND_MISSING', 'REFUND_BAD_SO_T', 'REFUND_BAD_CUSTOMER_T', 'REFUND_BAD_PURCHASE_T', 'REFUND_BAD_RETURN_T')"
                                + " AND " + owner + " IS NULL",
                        Integer.class, "F1")).isEqualTo(12);

                // Mutate one link at a time: no surviving direct-order/operator fallback is allowed.
                String[][] brokenLinks = {
                    {"payment_requests","factory_id","REFUND_PR","'F2'","'F1'"},
                    {"payment_requests","deleted_at","REFUND_PR","CURRENT_TIMESTAMP","NULL"},
                    {"payment_requests","source_type","REFUND_PR","'PURCHASE'","'SALES'"},
                    {"payment_requests","purchase_order_id","REFUND_PR","'PO_OWN'","NULL"},
                    {"payment_requests","sales_order_id","REFUND_PR","'SO_HIDDEN'","'SO_OWN'"},
                    {"payment_requests","customer_id","REFUND_PR","'C2'","'C1'"},
                    {"return_orders","factory_id","RETURN_OWN","'F2'","'F1'"},
                    {"return_orders","deleted_at","RETURN_OWN","CURRENT_TIMESTAMP","NULL"},
                    {"return_orders","counterparty_id","RETURN_OWN","'C2'","'C1'"},
                    {"return_orders","source_order_id","RETURN_OWN","'SO_HIDDEN'","'SO_OWN'"},
                    {"return_orders","return_type","RETURN_OWN","'PURCHASE_RETURN'","'SALES_RETURN'"},
                    {"sales_orders","factory_id","SO_OWN","'F2'","'F1'"},
                    {"sales_orders","deleted_at","SO_OWN","CURRENT_TIMESTAMP","NULL"},
                    {"ar_ap_transactions","purchase_order_id","REFUND_OWN","'PO_OWN'","NULL"},
                    {"ar_ap_transactions","sales_order_id","REFUND_OWN","'SO_HIDDEN'","'SO_OWN'"},
                    {"ar_ap_transactions","transaction_type","REFUND_OWN","'AR_INVOICE'","'AR_REFUND'"},
                    {"ar_ap_transactions","counterparty_type","REFUND_OWN","'SUPPLIER'","'CUSTOMER'"},
                    {"ar_ap_transactions","counterparty_id","REFUND_OWN","'C2'","'C1'"}
                };
                for (String[] link : brokenLinks) {
                    String update="UPDATE " + link[0] + " SET " + link[1] + "=";
                    jdbc.update(update + link[3] + " WHERE id=?", link[2]);
                    assertThat(jdbc.query("SELECT " + owner + " FROM ar_ap_transactions t WHERE id='REFUND_OWN'",
                            (rs,n)->rs.getObject(1,Long.class)).get(0)).as(link[0]+"."+link[1]).isNull();
                    jdbc.update(update + link[4] + " WHERE id=?", link[2]);
                    assertThat(jdbc.query("SELECT " + owner + " FROM ar_ap_transactions t WHERE id='REFUND_OWN'",
                            (rs,n)->rs.getObject(1,Long.class)).get(0)).isEqualTo(11L);
                }

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
                + "return_type varchar(32), source_order_id varchar(64), counterparty_id varchar(64),"
                + "created_by bigint, deleted_at timestamp NULL)");
        statement.execute("CREATE TABLE payment_requests ("
                + "id varchar(64) PRIMARY KEY, factory_id varchar(64) NOT NULL, source_type varchar(32),"
                + "sales_order_id varchar(64), purchase_order_id varchar(64), return_order_id varchar(64),"
                + "customer_id varchar(64), deleted_at timestamp NULL)");
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
        jdbc.update("INSERT INTO return_orders VALUES (?, ?, ?, ?, ?, ?, NULL), (?, ?, ?, ?, ?, ?, NULL)",
                "RETURN_OWN", "F1", "SALES_RETURN", "SO_OWN", "C1", 99L,
                "RETURN_MISMATCH", "F1", "SALES_RETURN", "SO_OWN", "C1", 99L);
        jdbc.update("INSERT INTO return_orders(id,factory_id,return_type,source_order_id,counterparty_id,created_by) VALUES ('REFUND_WRONG_RETURN','F1','SALES_RETURN','SO_HIDDEN','C1',99)");
        jdbc.update("INSERT INTO payment_requests (id,factory_id,source_type,sales_order_id,purchase_order_id,return_order_id,customer_id,deleted_at) VALUES "
                + "('REFUND_PR','F1','SALES','SO_OWN',NULL,'RETURN_OWN','C1',NULL),"
                + "('REFUND_PR_BAD','F1','PURCHASE','SO_OWN',NULL,'RETURN_OWN','C1',NULL),"
                + "('REFUND_PR_DELETED','F1','SALES','SO_OWN',NULL,'RETURN_OWN','C1',CURRENT_TIMESTAMP)");

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
                        + " (?, ?, NULL, ?, NULL, 'RETURN_REFUND', ?, ?, CURRENT_DATE, 15),"
                        + " (?, ?, NULL, ?, NULL, 'RETURN_REFUND_REVERSAL', ?, ?, CURRENT_DATE, -15),"
                        + " (?, ?, NULL, ?, NULL, 'RETURN_REFUND', ?, ?, CURRENT_DATE, 15),"
                        + " (?, ?, NULL, ?, NULL, 'RETURN_REFUND', ?, ?, CURRENT_DATE, 15),"
                        + " (?, ?, NULL, ?, NULL, 'RETURN_REFUND', ?, ?, CURRENT_DATE, 15),"
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
                "REFUND_OWN", "F1", "SO_OWN", "REFUND_PR", 99L,
                "REFUND_REVERSAL", "F1", "SO_OWN", "REFUND_PR", 99L,
                "REFUND_BAD_TYPE", "F1", "SO_OWN", "REFUND_PR_BAD", 99L,
                "REFUND_DELETED", "F1", "SO_OWN", "REFUND_PR_DELETED", 99L,
                "REFUND_MISSING", "F1", "SO_OWN", "REFUND_PR_MISSING", 99L,
                "UNKNOWN_SOURCE", "F1", "UNKNOWN_ID", 11L,
                "BROKEN_DELIVERY", "F1", "MISSING_DELIVERY", 11L,
                "MISMATCHED_DELIVERY", "F1", "SO_HIDDEN", "DELIVERY_OWN", 11L,
                "MISMATCHED_RETURN", "F1", "SO_HIDDEN", "RETURN_MISMATCH", 11L);
        jdbc.update("UPDATE ar_ap_transactions SET transaction_type='AR_INVOICE' "
                + "WHERE factory_id='F1' AND transaction_type IS NULL");
        jdbc.update("UPDATE ar_ap_transactions SET transaction_type='AR_REFUND' WHERE id='REFUND_OWN'");
        jdbc.update("UPDATE ar_ap_transactions SET transaction_type='AR_ADJUSTMENT' WHERE id='REFUND_REVERSAL'");
        jdbc.update("UPDATE ar_ap_transactions SET transaction_type='AR_REFUND' WHERE id IN ('REFUND_BAD_TYPE','REFUND_DELETED','REFUND_MISSING')");
        jdbc.update("INSERT INTO payment_requests (id,factory_id,source_type,sales_order_id,purchase_order_id,return_order_id,customer_id,deleted_at) VALUES "
                + "('REFUND_BAD_SO_PR','F1','SALES','SO_HIDDEN',NULL,'RETURN_OWN','C1',NULL),"
                + "('REFUND_BAD_CUSTOMER_PR','F1','SALES','SO_OWN',NULL,'RETURN_OWN','C2',NULL),"
                + "('REFUND_BAD_PURCHASE_PR','F1','SALES','SO_OWN','PO_OWN','RETURN_OWN','C1',NULL),"
                + "('REFUND_BAD_RETURN_PR','F1','SALES','SO_OWN',NULL,'REFUND_WRONG_RETURN','C1',NULL)");
        jdbc.update("INSERT INTO ar_ap_transactions (id,factory_id,sales_order_id,purchase_order_id,source_type,source_id,operated_by,transaction_type,transaction_date,amount,counterparty_type,counterparty_id) VALUES "
                + "('REFUND_BAD_SO_T','F1','SO_OWN',NULL,'RETURN_REFUND','REFUND_BAD_SO_PR',99,'AR_REFUND',CURRENT_DATE,1,'CUSTOMER','C1'),"
                + "('REFUND_BAD_CUSTOMER_T','F1','SO_OWN',NULL,'RETURN_REFUND','REFUND_BAD_CUSTOMER_PR',99,'AR_REFUND',CURRENT_DATE,1,'CUSTOMER','C2'),"
                + "('REFUND_BAD_PURCHASE_T','F1','SO_OWN','PO_OWN','RETURN_REFUND','REFUND_BAD_PURCHASE_PR',99,'AR_REFUND',CURRENT_DATE,1,'CUSTOMER','C1'),"
                + "('REFUND_BAD_RETURN_T','F1','SO_OWN',NULL,'RETURN_REFUND','REFUND_BAD_RETURN_PR',99,'AR_REFUND',CURRENT_DATE,1,'CUSTOMER','C1')");

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
