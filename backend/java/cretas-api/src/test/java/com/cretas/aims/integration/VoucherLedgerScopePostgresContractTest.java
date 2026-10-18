package com.cretas.aims.integration;

import com.cretas.aims.service.finance.VoucherRowScopeQuery;
import com.cretas.aims.service.finance.VoucherOwnershipSql;
import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.DriverManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Opt-in native PG proof that voucher visibility follows source ownership and fails closed. */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class VoucherLedgerScopePostgresContractTest {

    @Test
    void sourceOwnerScopeExcludesOtherOwnersAndDamagedSources() throws Exception {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(System.getenv("CRETAS_WORKFLOW_PG_URL"));
        String schema = "voucher_scope_verify_" + java.util.UUID.randomUUID().toString().replace("-", "");
        String user = System.getenv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv("CRETAS_WORKFLOW_PG_PASSWORD");
        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            connection.setSchema(schema);
            try {
                statement.execute("CREATE TABLE sales_orders(id varchar PRIMARY KEY, factory_id varchar, created_by bigint, deleted_at timestamp NULL)");
                statement.execute("CREATE TABLE purchase_orders(id varchar PRIMARY KEY, factory_id varchar, created_by bigint, deleted_at timestamp NULL)");
                statement.execute("CREATE TABLE sales_delivery_records(id varchar PRIMARY KEY, factory_id varchar, sales_order_id varchar, deleted_at timestamp NULL)");
                statement.execute("CREATE TABLE purchase_receive_records(id varchar PRIMARY KEY, factory_id varchar, purchase_order_id varchar, deleted_at timestamp NULL)");
                statement.execute("CREATE TABLE return_orders(id varchar PRIMARY KEY, factory_id varchar, return_type varchar, source_order_id varchar, created_by bigint, deleted_at timestamp NULL)");
                statement.execute("ALTER TABLE return_orders ADD COLUMN counterparty_id varchar");
                statement.execute("CREATE TABLE payment_requests(id varchar PRIMARY KEY, factory_id varchar, return_order_id varchar, sales_order_id varchar, source_type varchar, purchase_order_id varchar, customer_id varchar, deleted_at timestamp NULL)");
                statement.execute("CREATE TABLE internal_transfers(id varchar PRIMARY KEY, source_factory_id varchar, target_factory_id varchar, requested_by bigint, deleted_at timestamp NULL)");
                statement.execute("CREATE TABLE production_plans(id varchar PRIMARY KEY, factory_id varchar, created_by bigint, deleted_at timestamp NULL)");
                statement.execute("CREATE TABLE ar_ap_transactions(id varchar PRIMARY KEY, factory_id varchar, sales_order_id varchar NULL, purchase_order_id varchar NULL, source_type varchar NULL, source_id varchar NULL, operated_by bigint NULL, deleted_at timestamp NULL)");
                statement.execute("ALTER TABLE ar_ap_transactions ADD COLUMN transaction_type varchar NULL");
                statement.execute("CREATE TABLE ar_ap_payment_allocations(id varchar PRIMARY KEY, factory_id varchar NOT NULL, payment_transaction_id varchar NOT NULL, payable_transaction_id varchar NOT NULL, deleted_at timestamp NULL)");
                statement.execute("CREATE TABLE vouchers(id varchar PRIMARY KEY, factory_id varchar, deleted_at timestamp NULL, source_business_type varchar NULL, source_business_id varchar NULL, created_by bigint NULL, voucher_date date, created_at timestamp NULL, original_voucher_id varchar NULL)");
                statement.execute("CREATE TABLE voucher_entries(voucher_id varchar, debit numeric, credit numeric, deleted_at timestamp NULL)");
                statement.execute("INSERT INTO sales_orders VALUES "
                        + "('OWN-SO','F1',56,NULL),('OTHER-SO','F1',66,NULL),('BROKEN-SO','F1',NULL,NULL),"
                        + "('CROSS-SO','F2',56,NULL),('DELETED-SO','F1',56,current_timestamp)");
                statement.execute("INSERT INTO purchase_orders VALUES "
                        + "('OWN-PO','F1',56,NULL),('OTHER-PO','F1',66,NULL)");
                statement.execute("INSERT INTO sales_delivery_records VALUES ('DEL-OWN','F1','OWN-SO',NULL),('DEL-OTHER','F1','OTHER-SO',NULL)");
                statement.execute("INSERT INTO purchase_receive_records VALUES ('REC-OWN','F1','OWN-PO',NULL),('REC-OTHER','F1','OTHER-PO',NULL)");
                statement.execute("INSERT INTO return_orders(id,factory_id,return_type,source_order_id,created_by,deleted_at) VALUES "
                        + "('OWN-RETURN','F1','SALES_RETURN','OWN-SO',999,NULL),"
                        + "('OTHER-RETURN','F1','SALES_RETURN','OTHER-SO',56,NULL),"
                        + "('OWN-PRETURN','F1','PURCHASE_RETURN','OWN-PO',999,NULL),"
                        + "('OTHER-PRETURN','F1','PURCHASE_RETURN','OTHER-PO',56,NULL),"
                        + "('BROKEN-RETURN','F1','SALES_RETURN',NULL,56,NULL),"
                        + "('DELETED-RETURN','F1','SALES_RETURN','OWN-SO',56,current_timestamp)");
                statement.execute("INSERT INTO ar_ap_transactions(id,factory_id,sales_order_id,purchase_order_id,source_type,source_id,operated_by,deleted_at) VALUES "
                        + "('AR-DEL-OWN','F1',NULL,NULL,'SALES_DELIVERY','DEL-OWN',999,NULL),"
                        + "('AR-DEL-OTHER','F1',NULL,NULL,'SALES_DELIVERY','DEL-OTHER',56,NULL),"
                        + "('AR-REC-OWN','F1',NULL,NULL,'PURCHASE_RECEIVE','REC-OWN',999,NULL),"
                        + "('AR-REC-OTHER','F1',NULL,NULL,'PURCHASE_RECEIVE','REC-OTHER',56,NULL),"
                        + "('AR-RETURN-OWN','F1',NULL,NULL,'RETURN_ORDER','OWN-RETURN',999,NULL),"
                        + "('AR-PRETURN-OWN','F1',NULL,NULL,'RETURN_ORDER','OWN-PRETURN',999,NULL),"
                        + "('AR-RETURN-DELETED','F1',NULL,NULL,'RETURN_ORDER','DELETED-RETURN',56,NULL)");
                statement.execute("INSERT INTO ar_ap_transactions(id,factory_id,sales_order_id,purchase_order_id,source_type,source_id,operated_by,deleted_at,transaction_type) VALUES "
                        + "('AR-REFUND','F1','OWN-SO',NULL,'RETURN_REFUND','REFUND-PR',56,NULL,'AR_REFUND')");
                statement.execute("ALTER TABLE ar_ap_transactions ADD COLUMN counterparty_type varchar DEFAULT 'CUSTOMER', ADD COLUMN counterparty_id varchar DEFAULT 'C1'");
                statement.execute("INSERT INTO payment_requests VALUES "
                        + "('REFUND-PR','F1','OWN-RETURN','OWN-SO','SALES',NULL,'C1',NULL),"
                        + "('REFUND-PR-OTHER','F1','OTHER-RETURN','OTHER-SO','SALES',NULL,'C1',NULL)");
                statement.execute("UPDATE return_orders SET counterparty_id='C1'");
                statement.execute("INSERT INTO vouchers(id,factory_id,deleted_at,source_business_type,source_business_id,created_by,voucher_date,created_at) VALUES "
                        + "('OWN','F1',NULL,'SALES_ORDER','OWN-SO',999,current_date,current_timestamp),"
                        + "('OTHER','F1',NULL,'SALES_ORDER','OTHER-SO',56,current_date,current_timestamp),"
                        + "('BROKEN','F1',NULL,'SALES_ORDER','BROKEN-SO',56,current_date,current_timestamp),"
                        + "('ORPHAN','F1',NULL,'SALES_ORDER','MISSING',56,current_date,current_timestamp),"
                        + "('CROSS','F1',NULL,'SALES_ORDER','CROSS-SO',56,current_date,current_timestamp),"
                        + "('DELETED','F1',NULL,'SALES_ORDER','DELETED-SO',56,current_date,current_timestamp),"
                        + "('RETURN-OWN','F1',NULL,'RETURN_ORDER','OWN-RETURN',999,current_date,current_timestamp),"
                        + "('RETURN-OTHER','F1',NULL,'RETURN_ORDER','OTHER-RETURN',56,current_date,current_timestamp),"
                        + "('RETURN-PURCHASE-OWN','F1',NULL,'RETURN_ORDER','OWN-PRETURN',999,current_date,current_timestamp),"
                        + "('RETURN-PURCHASE-OTHER','F1',NULL,'RETURN_ORDER','OTHER-PRETURN',56,current_date,current_timestamp),"
                        + "('RETURN-BROKEN','F1',NULL,'RETURN_ORDER','BROKEN-RETURN',56,current_date,current_timestamp),"
                        + "('RETURN-DELETED','F1',NULL,'RETURN_ORDER','DELETED-RETURN',56,current_date,current_timestamp),"
                        + "('DEL-OWN','F1',NULL,'CASH_RECEIPT','AR-DEL-OWN',56,current_date,current_timestamp),"
                        + "('DEL-OTHER','F1',NULL,'CASH_RECEIPT','AR-DEL-OTHER',56,current_date,current_timestamp),"
                        + "('REC-OWN','F1',NULL,'CASH_PAYMENT','AR-REC-OWN',56,current_date,current_timestamp),"
                        + "('REC-OTHER','F1',NULL,'CASH_PAYMENT','AR-REC-OTHER',56,current_date,current_timestamp),"
                        + "('AR-RETURN-OWN','F1',NULL,'CASH_RECEIPT','AR-RETURN-OWN',56,current_date,current_timestamp),"
                        + "('AR-PRETURN-OWN','F1',NULL,'CASH_RECEIPT','AR-PRETURN-OWN',56,current_date,current_timestamp),"
                        + "('AR-RETURN-DELETED','F1',NULL,'CASH_RECEIPT','AR-RETURN-DELETED',56,current_date,current_timestamp),"
                        + "('REFUND','F1',NULL,'RETURN_REFUND','REFUND-PR',56,current_date,current_timestamp),"
                        + "('MANUAL','F1',NULL,NULL,NULL,56,current_date,current_timestamp)");
                statement.execute("INSERT INTO vouchers VALUES "
                        + "('REV-OWN','F1',NULL,'VOUCHER_REVERSAL','OWN',999,current_date,current_timestamp,'OWN'),"
                        + "('REV-OTHER','F1',NULL,'VOUCHER_REVERSAL','OTHER',56,current_date,current_timestamp,'OTHER'),"
                        + "('REV-DELETED','F1',NULL,'VOUCHER_REVERSAL','DELETED',56,current_date,current_timestamp,'DELETED'),"
                        + "('REV-ORPHAN','F1',NULL,'VOUCHER_REVERSAL','MISSING',56,current_date,current_timestamp,'MISSING')");
                statement.execute("INSERT INTO vouchers(id,factory_id,deleted_at,source_business_type,source_business_id,created_by,voucher_date,created_at) VALUES "
                        + "('REFUND-OTHER','F1',NULL,'RETURN_REFUND','REFUND-PR-OTHER',56,current_date,current_timestamp)");
                statement.execute("INSERT INTO vouchers(id,factory_id,deleted_at,source_business_type,source_business_id,created_by,voucher_date,created_at) VALUES "
                        + "('REFUND-BROKEN','F1',NULL,'RETURN_REFUND','MISSING-REFUND-PR',56,current_date,current_timestamp)");
                statement.execute("INSERT INTO vouchers VALUES ('REV-REFUND','F1',NULL,'VOUCHER_REVERSAL','REFUND',56,current_date,current_timestamp,'REFUND')");
                statement.execute("INSERT INTO voucher_entries VALUES "
                        + "('OWN',100,0,NULL),('REV-OWN',0,100,NULL),('REFUND',15,0,NULL),('REV-REFUND',0,15,NULL)");

                JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                VoucherRowScopeQuery scopeQuery = new VoucherRowScopeQuery(jdbc);
                for (String id : List.of("MANUAL", "OWN", "DEL-OWN", "REC-OWN", "RETURN-OWN",
                        "RETURN-PURCHASE-OWN", "AR-RETURN-OWN", "AR-PRETURN-OWN", "REFUND", "REV-REFUND", "REV-OWN")) {
                    assertThat(scopeQuery.isVoucherVisible("F1", id, List.of(56L))).isTrue();
                }
                for (String id : List.of("OTHER", "BROKEN", "ORPHAN", "CROSS", "DELETED", "RETURN-OTHER",
                        "RETURN-PURCHASE-OTHER", "RETURN-BROKEN", "RETURN-DELETED", "REV-OTHER",
                        "REV-DELETED", "REV-ORPHAN", "REFUND-OTHER", "REFUND-BROKEN")) {
                    assertThat(scopeQuery.isVoucherVisible("F1", id, List.of(56L))).isFalse();
                }
                assertThatCode(() -> scopeQuery.requireSourceAccess(
                        "F1", "RETURN_ORDER", "OWN-RETURN", List.of(56L)))
                        .doesNotThrowAnyException();
                assertThatCode(() -> scopeQuery.requireSourceAccess(
                        "F1", "RETURN_REFUND", "REFUND-PR", List.of(56L)))
                        .doesNotThrowAnyException();
                assertThatThrownBy(() -> scopeQuery.requireSourceAccess(
                        "F1", "RETURN_REFUND", "REFUND-PR-OTHER", List.of(56L)))
                        .isInstanceOf(BusinessException.class);
                assertThatThrownBy(() -> scopeQuery.requireSourceAccess(
                        "F1", "RETURN_REFUND", "MISSING-REFUND-PR", List.of(56L)))
                        .isInstanceOf(BusinessException.class);
                assertThatCode(() -> scopeQuery.requireSourceAccess(
                        "F1", "RETURN_ORDER", "OWN-PRETURN", List.of(56L)))
                        .doesNotThrowAnyException();
                assertThatThrownBy(() -> scopeQuery.requireSourceAccess(
                        "F1", "RETURN_ORDER", "OTHER-RETURN", List.of(56L)))
                        .isInstanceOf(BusinessException.class);
                assertThatThrownBy(() -> scopeQuery.requireSourceAccess(
                        "F1", "RETURN_ORDER", "BROKEN-RETURN", List.of(56L)))
                        .isInstanceOf(BusinessException.class);
                assertThat(scopeQuery.isVoucherVisible("F1", "REV-OWN", List.of(56L))).isTrue();
                assertThat(scopeQuery.isVoucherVisible("F1", "REV-OTHER", List.of(56L))).isFalse();
                assertThatCode(() -> scopeQuery.requireVoucherAccess("F1", "REFUND", List.of(56L)))
                        .doesNotThrowAnyException();
                assertThatCode(() -> scopeQuery.requireVoucherAccess("F1", "REV-REFUND", List.of(56L)))
                        .doesNotThrowAnyException();
                assertThatThrownBy(() -> scopeQuery.requireVoucherAccess("F1", "REFUND-OTHER", List.of(56L)))
                        .isInstanceOf(BusinessException.class);
                assertThatThrownBy(() -> scopeQuery.requireVoucherAccess("F1", "REFUND-BROKEN", List.of(56L)))
                        .isInstanceOf(BusinessException.class);
                assertThat(scopeQuery.isVoucherVisible("F1", "REV-DELETED", List.of(56L))).isFalse();
                BigDecimal net = jdbc.queryForObject(
                        "SELECT COALESCE(SUM(e.debit-e.credit),0) FROM voucher_entries e "
                                + "JOIN vouchers v ON v.id=e.voucher_id AND v.factory_id='F1' "
                                + "WHERE v.id IN ('OWN','REV-OWN','REFUND','REV-REFUND') AND v.deleted_at IS NULL",
                        BigDecimal.class);
                assertThat(net).isEqualByComparingTo("0");

                var refundIds=namedQuery(jdbc).queryForList(VoucherOwnershipSql.OWNER_SCOPE_CTE
                        + "SELECT id FROM scoped_vouchers WHERE factory_id='F1' AND id IN ('REFUND','REV-REFUND','REFUND-OTHER','REFUND-BROKEN')",
                        Map.of("ownerIdsJson","[56]"),String.class);
                assertThat(refundIds).containsExactlyInAnyOrder("REFUND","REV-REFUND");
                BigDecimal scopedNet = namedQuery(jdbc).queryForObject(
                        VoucherOwnershipSql.OWNER_SCOPE_CTE
                                + "SELECT COALESCE(SUM(e.debit-e.credit),0) FROM voucher_entries e "
                                + "JOIN scoped_vouchers sv ON sv.id=e.voucher_id AND sv.factory_id='F1' "
                                + "WHERE sv.factory_id='F1' AND e.deleted_at IS NULL",
                        Map.of("ownerIdsJson", "[56]"), BigDecimal.class);
                assertThat(scopedNet).isEqualByComparingTo("0");

                statement.execute("INSERT INTO vouchers(id,factory_id,deleted_at,source_business_type,source_business_id,created_by,voucher_date,created_at) "
                        + "SELECT 'MANY-' || n, 'F1', NULL, NULL, NULL, 56, current_date, current_timestamp "
                        + "FROM generate_series(1,66000) AS g(n)");
                NamedParameterJdbcTemplate named = namedQuery(jdbc);
                List<String> page = named.query(
                        VoucherOwnershipSql.OWNER_SCOPE_CTE
                                + "SELECT v.id FROM vouchers v JOIN scoped_vouchers sv ON sv.id=v.id AND sv.factory_id=v.factory_id "
                                + "WHERE v.factory_id='F1' ORDER BY v.id LIMIT 20",
                        Map.of("ownerIdsJson", "[56]"), (rs, rowNum) -> rs.getString(1));
                assertThat(page).hasSize(20);
                Long scopedRows = named.queryForObject(
                        VoucherOwnershipSql.OWNER_SCOPE_CTE
                                + "SELECT COUNT(*) FROM scoped_vouchers WHERE factory_id='F1'",
                        Map.of("ownerIdsJson", "[56]"), Long.class);
                assertThat(scopedRows).isGreaterThan(65535L);
                // Execute the actual repository annotation SQL: owner-filtered P&L keeps only current COGS.
                statement.execute("ALTER TABLE vouchers ADD COLUMN status varchar, ADD COLUMN voucher_type varchar");
                statement.execute("ALTER TABLE voucher_entries ADD COLUMN id bigint, ADD COLUMN subject_code varchar, ADD COLUMN subject_name varchar");
                statement.execute("INSERT INTO vouchers(id,factory_id,created_by,voucher_date,status,voucher_type,original_voucher_id) VALUES "
                        +"('COGS-OLD','F1',56,current_date,'REVERSED','COST_CARRYOVER',NULL),"
                        +"('COGS-RED','F1',56,current_date,'POSTED','COST_CARRYOVER','COGS-OLD'),"
                        +"('COGS-NEW','F1',56,current_date,'POSTED','COST_CARRYOVER',NULL),"
                        +"('COGS-HIDDEN','F1',66,current_date,'POSTED','COST_CARRYOVER',NULL)");
                statement.execute("INSERT INTO voucher_entries(voucher_id,debit,credit,id,subject_code,subject_name) VALUES "
                        +"('COGS-OLD',265.95,0,1,'6401','cost'),('COGS-RED',0,265.95,2,'6401','cost'),"
                        +"('COGS-NEW',270.95,0,3,'6401','cost'),('COGS-HIDDEN',999,0,4,'6401','cost')");
                String aggregateSql=com.cretas.aims.repository.VoucherEntryRepository.class.getMethod(
                        "aggregateBySubjectExcludingDraftAndClosingInOwnerScopeNative",String.class,
                        java.time.LocalDate.class,java.time.LocalDate.class,String.class)
                        .getAnnotation(org.springframework.data.jpa.repository.Query.class).value();
                var params=new java.util.HashMap<String,Object>();params.put("factoryId","F1");
                params.put("startDate",java.time.LocalDate.now());params.put("endDate",java.time.LocalDate.now());
                params.put("ownerIdsJson","[56]");
                var costs=named.query(aggregateSql,params,(rs,n)->rs.getBigDecimal(3).subtract(rs.getBigDecimal(4)));
                assertThat(costs).hasSize(1);assertThat(costs.get(0)).isEqualByComparingTo("270.95");
                params.put("ownerIdsJson","[]");assertThat(named.queryForList(aggregateSql,params)).isEmpty();

            } finally {
                connection.setSchema("public");
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static NamedParameterJdbcTemplate namedQuery(JdbcTemplate jdbc) {
        return new NamedParameterJdbcTemplate(jdbc);
    }
}
