package com.cretas.aims.integration;

import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.enums.ArApApprovalStatus;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.service.finance.impl.ArApServiceImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real-service PostgreSQL proof for per-item batch transactions. Repository
 * doubles use the same transaction-bound JdbcTemplate, so rollback and version
 * CAS are exercised against PostgreSQL without an all-entity Hibernate schema.
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class BatchAdjustmentAtomicityPostgresContractTest {
    private static final String FACTORY = "BATCH-TX";
    private static final String SUPPLIER = "SUP-BATCH";
    private static final String CUSTOMER = "CUS-BATCH";
    private static final String SCHEMA = "batch_finance_" + UUID.randomUUID().toString().replace("-", "");

    private static String baseUrl;
    private static String schemaUrl;
    private static JdbcTemplate jdbc;
    private static PlatformTransactionManager transactionManager;

    @BeforeAll
    static void createIsolatedSchema() throws Exception {
        baseUrl = DisposablePostgresTargetGuard.requireSafeUrl(required("CRETAS_WORKFLOW_PG_URL"));
        String user = required("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(baseUrl, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
        }
        schemaUrl = baseUrl + "?currentSchema=" + SCHEMA;
        DataSource dataSource = new DriverManagerDataSource(schemaUrl, user, password);
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        jdbc.execute("CREATE TABLE suppliers (id VARCHAR(191) PRIMARY KEY, factory_id VARCHAR(191) NOT NULL, "
                + "current_balance NUMERIC(15,2), version BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE customers (id VARCHAR(191) PRIMARY KEY, factory_id VARCHAR(191) NOT NULL, "
                + "current_balance NUMERIC(15,2), version BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE ar_ap_transactions (id VARCHAR(191) PRIMARY KEY, factory_id VARCHAR(191) NOT NULL, "
                + "transaction_type VARCHAR(40) NOT NULL, counterparty_type VARCHAR(40) NOT NULL, "
                + "counterparty_id VARCHAR(191) NOT NULL, amount NUMERIC(15,2) NOT NULL, "
                + "balance_after NUMERIC(15,2), approval_status VARCHAR(40) NOT NULL, operated_by BIGINT, "
                + "approved_by BIGINT, approved_at TIMESTAMP, remark VARCHAR(500), version BIGINT NOT NULL, "
                + "transaction_date DATE NOT NULL DEFAULT CURRENT_DATE, deleted_at TIMESTAMP)");
    }

    @AfterAll
    static void dropIsolatedSchema() throws Exception {
        if (baseUrl == null) return;
        try (Connection connection = DriverManager.getConnection(baseUrl,
                required("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @BeforeEach
    void resetRows() {
        jdbc.update("DELETE FROM ar_ap_transactions");
        jdbc.update("DELETE FROM suppliers");
        jdbc.update("DELETE FROM customers");
        jdbc.update("INSERT INTO suppliers(id,factory_id,current_balance,version) VALUES (?,?,?,0)",
                SUPPLIER, FACTORY, new BigDecimal("10.00"));
        jdbc.update("INSERT INTO customers(id,factory_id,current_balance,version) VALUES (?,?,?,0)",
                CUSTOMER, FACTORY, new BigDecimal("10.00"));
    }

    @Test
    void supplierSaveThenAdjustmentSaveFailureRollsBackWholeItem() {
        insertTransaction("FAIL-SUP", CounterpartyType.SUPPLIER, SUPPLIER, "5.00", 1L);
        ArApServiceImpl service = service("FAIL-SUP", null);

        var result = service.batchApproveAdjustments(FACTORY, List.of("FAIL-SUP"), 2L);

        assertThat(result).containsEntry("approvedCount", 0).containsEntry("failedCount", 1);
        assertThat(balance("suppliers", SUPPLIER)).isEqualByComparingTo("10.00");
        assertThat(status("FAIL-SUP")).isEqualTo("PENDING");
    }

    @Test
    void customerSaveThenAdjustmentSaveFailureRollsBackWholeItem() {
        insertTransaction("FAIL-CUS", CounterpartyType.CUSTOMER, CUSTOMER, "5.00", 1L);
        ArApServiceImpl service = service("FAIL-CUS", null);

        var result = service.batchApproveAdjustments(FACTORY, List.of("FAIL-CUS"), 2L);

        assertThat(result).containsEntry("approvedCount", 0).containsEntry("failedCount", 1);
        assertThat(balance("customers", CUSTOMER)).isEqualByComparingTo("10.00");
        assertThat(status("FAIL-CUS")).isEqualTo("PENDING");
    }

    @Test
    void batchItemsCommitIndependently() {
        insertTransaction("GOOD", CounterpartyType.SUPPLIER, SUPPLIER, "5.00", 1L);
        insertTransaction("BAD", CounterpartyType.SUPPLIER, SUPPLIER, "7.00", 1L);
        ArApServiceImpl service = service("BAD", null);

        var result = service.batchApproveAdjustments(FACTORY, List.of("GOOD", "BAD"), 2L);

        assertThat(result).containsEntry("approvedCount", 1).containsEntry("failedCount", 1);
        assertThat(balance("suppliers", SUPPLIER)).isEqualByComparingTo("15.00");
        assertThat(status("GOOD")).isEqualTo("APPROVED");
        assertThat(status("BAD")).isEqualTo("PENDING");
    }

    @Test
    void concurrentApproveAndRejectHasOneWinnerAndNoDuplicateBalance() throws Exception {
        insertTransaction("RACE", CounterpartyType.SUPPLIER, SUPPLIER, "5.00", 1L);
        ArApServiceImpl service = service(null, null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> approve = pool.submit(() -> service.batchApproveAdjustments(FACTORY, List.of("RACE"), 2L));
            Future<?> reject = pool.submit(() -> service.batchRejectAdjustments(FACTORY, List.of("RACE"), 3L, "并发驳回"));
            approve.get();
            reject.get();
        } finally {
            pool.shutdownNow();
        }
        String finalStatus = status("RACE");
        assertThat(finalStatus).isIn("APPROVED", "REJECTED");
        assertThat(balance("suppliers", SUPPLIER))
                .isEqualByComparingTo("APPROVED".equals(finalStatus) ? "15.00" : "10.00");
    }

    @Test
    void concurrentApproveBatchesHaveOneWinner() throws Exception {
        insertTransaction("APPROVE-RACE", CounterpartyType.SUPPLIER, SUPPLIER, "5.00", 1L);
        ArApServiceImpl service = service(null, null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> service.batchApproveAdjustments(FACTORY, List.of("APPROVE-RACE"), 2L));
            Future<?> second = pool.submit(() -> service.batchApproveAdjustments(FACTORY, List.of("APPROVE-RACE"), 3L));
            first.get();
            second.get();
        } finally {
            pool.shutdownNow();
        }
        assertThat(status("APPROVE-RACE")).isEqualTo("APPROVED");
        assertThat(balance("suppliers", SUPPLIER)).isEqualByComparingTo("15.00");
    }

    @Test
    void concurrentDifferentAdjustmentsCannotLoseSupplierBalanceUpdate() throws Exception {
        insertTransaction("ADJ-A", CounterpartyType.SUPPLIER, SUPPLIER, "5.00", 1L);
        insertTransaction("ADJ-B", CounterpartyType.SUPPLIER, SUPPLIER, "7.00", 1L);
        ArApServiceImpl service = service(null, null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> service.batchApproveAdjustments(FACTORY, List.of("ADJ-A"), 2L));
            var second = pool.submit(() -> service.batchApproveAdjustments(FACTORY, List.of("ADJ-B"), 3L));
            assertThat(first.get()).containsEntry("approvedCount", 1).containsEntry("failedCount", 0);
            assertThat(second.get()).containsEntry("approvedCount", 1).containsEntry("failedCount", 0);
        } finally {
            pool.shutdownNow();
        }
        BigDecimal balance = balance("suppliers", SUPPLIER);
        assertThat(balance).isEqualByComparingTo("22.00");
        long approved = List.of("ADJ-A", "ADJ-B").stream().filter(id -> "APPROVED".equals(status(id))).count();
        assertThat(approved).isEqualTo(2);
    }

    @Test
    void fourEyesAndCrossFactoryRejectBeforeAnyWrite() {
        insertTransaction("GUARD", CounterpartyType.SUPPLIER, SUPPLIER, "5.00", 2L);
        ArApServiceImpl service = service(null, null);

        var sameActor = service.batchApproveAdjustments(FACTORY, List.of("GUARD"), 2L);
        var foreignFactory = service.batchApproveAdjustments("OTHER", List.of("GUARD"), 3L);

        assertThat(sameActor).containsEntry("approvedCount", 0).containsEntry("failedCount", 1);
        assertThat(foreignFactory).containsEntry("approvedCount", 0).containsEntry("failedCount", 1);
        assertThat(balance("suppliers", SUPPLIER)).isEqualByComparingTo("10.00");
        assertThat(status("GUARD")).isEqualTo("PENDING");
    }

    private ArApServiceImpl service(String failTransactionId, String unused) {
        ArApTransactionRepository transactions = mock(ArApTransactionRepository.class);
        SupplierRepository suppliers = mock(SupplierRepository.class);
        CustomerRepository customers = mock(CustomerRepository.class);
        when(transactions.findById(anyString())).thenAnswer(invocation -> findTransaction(invocation.getArgument(0)));
        when(suppliers.findByIdAndFactoryId(anyString(), anyString()))
                .thenAnswer(invocation -> findSupplier(invocation.getArgument(0), invocation.getArgument(1)));
        when(customers.findByIdAndFactoryId(anyString(), anyString()))
                .thenAnswer(invocation -> findCustomer(invocation.getArgument(0), invocation.getArgument(1)));
        when(suppliers.save(any(Supplier.class))).thenAnswer(invocation -> saveSupplier(invocation.getArgument(0)));
        when(customers.save(any(Customer.class))).thenAnswer(invocation -> saveCustomer(invocation.getArgument(0)));
        when(transactions.save(any(ArApTransaction.class))).thenAnswer(invocation -> {
            ArApTransaction transaction = invocation.getArgument(0);
            if (failTransactionId != null && failTransactionId.equals(transaction.getId())) {
                throw new IllegalStateException("simulated adjustment save failure");
            }
            return saveTransaction(transaction);
        });
        ArApServiceImpl service = new ArApServiceImpl(transactions, customers, suppliers);
        ReflectionTestUtils.setField(service, "transactionManager", transactionManager);
        ReflectionTestUtils.setField(service, "entityManager", transactionBoundEntityManager());
        ReflectionTestUtils.setField(service, "accountingPeriodService",
                mock(com.cretas.aims.service.finance.AccountingPeriodService.class));
        return service;
    }

    /** Execute the service's real advisory/row locks on the item's JDBC transaction. */
    private jakarta.persistence.EntityManager transactionBoundEntityManager() {
        var em = mock(jakarta.persistence.EntityManager.class);
        var named = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
        when(em.createNativeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            var parameters = new java.util.HashMap<String, Object>();
            var query = mock(jakarta.persistence.Query.class);
            when(query.setParameter(anyString(), any())).thenAnswer(binding -> {
                parameters.put(binding.getArgument(0), binding.getArgument(1));
                return query;
            });
            when(query.getResultList()).thenAnswer(ignored -> named.queryForList(sql, parameters).stream()
                    .map(row -> row.values().iterator().next()).toList());
            when(query.getSingleResult()).thenAnswer(ignored -> named.queryForObject(sql, parameters, Object.class));
            return query;
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            ArApTransaction target = invocation.getArgument(0);
            org.springframework.beans.BeanUtils.copyProperties(findTransaction(target.getId()).orElseThrow(), target);
            return null;
        }).when(em).refresh(any(ArApTransaction.class));
        return em;
    }

    private Optional<ArApTransaction> findTransaction(String id) {
        return jdbc.query("SELECT id,factory_id,transaction_type,counterparty_type,counterparty_id,amount,"
                        + "approval_status,operated_by,balance_after,approved_by,approved_at,remark,version,transaction_date "
                        + "FROM ar_ap_transactions WHERE id=?", rows -> rows.next() ? Optional.of(transaction(rows)) : Optional.empty(), id);
    }

    private Optional<Supplier> findSupplier(String id, String factoryId) {
        return jdbc.query("SELECT id,factory_id,current_balance,version FROM suppliers WHERE id=? AND factory_id=?",
                rows -> rows.next() ? Optional.of(supplier(rows)) : Optional.empty(), id, factoryId);
    }

    private Optional<Customer> findCustomer(String id, String factoryId) {
        return jdbc.query("SELECT id,factory_id,current_balance,version FROM customers WHERE id=? AND factory_id=?",
                rows -> rows.next() ? Optional.of(customer(rows)) : Optional.empty(), id, factoryId);
    }

    private Supplier saveSupplier(Supplier value) {
        int updated = jdbc.update("UPDATE suppliers SET current_balance=?,version=version+1 "
                        + "WHERE id=? AND factory_id=? AND version=?", value.getCurrentBalance(), value.getId(),
                value.getFactoryId(), value.getVersion());
        if (updated != 1) throw new jakarta.persistence.OptimisticLockException("supplier version conflict");
        return value;
    }

    private Customer saveCustomer(Customer value) {
        int updated = jdbc.update("UPDATE customers SET current_balance=?,version=version+1 "
                        + "WHERE id=? AND factory_id=? AND version=?", value.getCurrentBalance(), value.getId(),
                value.getFactoryId(), value.getVersion());
        if (updated != 1) throw new jakarta.persistence.OptimisticLockException("customer version conflict");
        return value;
    }

    private ArApTransaction saveTransaction(ArApTransaction value) {
        int updated = jdbc.update("UPDATE ar_ap_transactions SET approval_status=?,balance_after=?,approved_by=?,"
                        + "approved_at=?,remark=?,version=version+1 WHERE id=? AND version=?",
                value.getApprovalStatus().name(), value.getBalanceAfter(), value.getApprovedBy(), value.getApprovedAt(),
                value.getRemark(), value.getId(), value.getVersion());
        if (updated != 1) throw new jakarta.persistence.OptimisticLockException("transaction version conflict");
        return value;
    }

    private ArApTransaction transaction(java.sql.ResultSet rows) throws java.sql.SQLException {
        ArApTransaction value = new ArApTransaction();
        value.setId(rows.getString("id"));
        value.setFactoryId(rows.getString("factory_id"));
        value.setTransactionType(ArApTransactionType.valueOf(rows.getString("transaction_type")));
        value.setCounterpartyType(CounterpartyType.valueOf(rows.getString("counterparty_type")));
        value.setCounterpartyId(rows.getString("counterparty_id"));
        value.setAmount(rows.getBigDecimal("amount"));
        value.setApprovalStatus(ArApApprovalStatus.valueOf(rows.getString("approval_status")));
        value.setOperatedBy(rows.getLong("operated_by"));
        value.setBalanceAfter(rows.getBigDecimal("balance_after"));
        value.setApprovedBy((Long) rows.getObject("approved_by"));
        Timestamp approvedAt = rows.getTimestamp("approved_at");
        value.setApprovedAt(approvedAt == null ? null : approvedAt.toLocalDateTime());
        value.setRemark(rows.getString("remark"));
        value.setVersion(rows.getLong("version"));
        value.setTransactionDate(rows.getDate("transaction_date").toLocalDate());
        return value;
    }

    private Supplier supplier(java.sql.ResultSet rows) throws java.sql.SQLException {
        Supplier value = new Supplier();
        value.setId(rows.getString("id")); value.setFactoryId(rows.getString("factory_id"));
        value.setCurrentBalance(rows.getBigDecimal("current_balance")); value.setVersion(rows.getLong("version"));
        return value;
    }

    private Customer customer(java.sql.ResultSet rows) throws java.sql.SQLException {
        Customer value = new Customer();
        value.setId(rows.getString("id")); value.setFactoryId(rows.getString("factory_id"));
        value.setCurrentBalance(rows.getBigDecimal("current_balance")); value.setVersion(rows.getLong("version"));
        return value;
    }

    private void insertTransaction(String id, CounterpartyType type, String counterpartyId, String amount, long operatedBy) {
        jdbc.update("INSERT INTO ar_ap_transactions(id,factory_id,transaction_type,counterparty_type,counterparty_id,"
                        + "amount,balance_after,approval_status,operated_by,version) VALUES (?,?,?,?,?,?,?,?,?,0)",
                id, FACTORY, ArApTransactionType.AP_ADJUSTMENT.name(), type.name(), counterpartyId,
                new BigDecimal(amount), new BigDecimal("10.00"), ArApApprovalStatus.PENDING.name(), operatedBy);
    }

    private BigDecimal balance(String table, String id) {
        return jdbc.queryForObject("SELECT current_balance FROM " + table + " WHERE id=?", BigDecimal.class, id);
    }

    private String status(String id) {
        return jdbc.queryForObject("SELECT approval_status FROM ar_ap_transactions WHERE id=?", String.class, id);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set");
        return value;
    }
}
