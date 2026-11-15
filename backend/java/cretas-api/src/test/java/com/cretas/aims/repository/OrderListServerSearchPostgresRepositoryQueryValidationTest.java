package com.cretas.aims.repository;

import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.integration.DisposablePostgresTargetGuard;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL proof for the list-page server-side search
 * ({@link SalesOrderRepository#searchListPage} / {@link PurchaseOrderRepository#searchListPage}).
 *
 * <p>Before: the web-admin sales / purchase order lists filtered keyword (and, for purchase, date) in the browser
 * on the current page only, so a record that lives on page 2 could never be found. Here the target record is the
 * oldest of 7 rows with page size 3, i.e. it is NOT on page 1 of the unfiltered list, yet the keyword search finds
 * it on page 1; another factory's row with the same keyword never shows up; totals follow the filter.
 */
@DataJpaTest(showSql = false, properties = {
        "spring.jpa.hibernate.ddl-auto=create", "spring.flyway.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class OrderListServerSearchPostgresRepositoryQueryValidationTest {

    private static final String FACTORY = "IT-OLS-PG";
    private static final String OTHER = "IT-OLS-PG-OTHER";
    private static final String SCHEMA = "ols_" + UUID.randomUUID().toString().replace("-", "");
    private static final PageRequest PAGE1 = PageRequest.of(0, 3);

    @Autowired private SalesOrderRepository sales;
    @Autowired private PurchaseOrderRepository purchases;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;

    @BeforeAll
    static void createIsolatedSchema() throws Exception {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        try (var connection = DriverManager.getConnection(url, requiredEnv("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"" + SCHEMA + "\"");
        }
    }

    @AfterAll
    static void dropIsolatedSchema() throws Exception {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        try (var connection = DriverManager.getConnection(url, requiredEnv("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS \"" + SCHEMA + "\" CASCADE");
        }
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> requiredEnv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO \"" + SCHEMA + "\"");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @BeforeEach
    void seed() {
        // Drop the FKs Hibernate generated in this disposable schema: only the rows under test are seeded.
        jdbc.execute("""
                DO $$ DECLARE r record; BEGIN
                  FOR r IN SELECT c.conname, c.conrelid::regclass AS t FROM pg_constraint c
                           JOIN pg_namespace n ON n.oid = c.connamespace
                           WHERE c.contype = 'f' AND n.nspname = current_schema() LOOP
                    EXECUTE 'ALTER TABLE ' || r.t || ' DROP CONSTRAINT ' || quote_ident(r.conname);
                  END LOOP;
                END $$""");
        jdbc.update("DELETE FROM sales_orders");
        jdbc.update("DELETE FROM purchase_orders");
        jdbc.update("DELETE FROM customers");
        jdbc.update("DELETE FROM suppliers");

        customer("C-ALPHA", FACTORY, "阿尔法食品");
        customer("C-BETA", FACTORY, "贝塔超市");
        supplier("S-ALPHA", FACTORY, "阿尔法供应");
        supplier("S-BETA", FACTORY, "贝塔供应");

        // 6 recent rows + 1 target that is the OLDEST => never on page 1 (size 3) of the unfiltered list.
        for (int i = 1; i <= 6; i++) {
            sale("SO-PG-0" + i, FACTORY, "C-BETA", SalesOrderStatus.DRAFT, LocalDate.of(2026, 3, i), 7L);
            purchase("PO-PG-0" + i, FACTORY, "S-BETA", PurchaseOrderStatus.DRAFT, LocalDate.of(2026, 3, i), 7L);
        }
        sale("SO-PG-TARGET", FACTORY, "C-ALPHA", SalesOrderStatus.CONFIRMED, LocalDate.of(2026, 1, 5), 8L);
        purchase("PO-PG-TARGET", FACTORY, "S-ALPHA", PurchaseOrderStatus.APPROVED, LocalDate.of(2026, 1, 5), 8L);
        sale("SO-PG-PCT%X", FACTORY, "C-BETA", SalesOrderStatus.DRAFT, LocalDate.of(2026, 3, 20), 7L);
        // Another factory, same keyword.
        customer("C-OTHER", OTHER, "阿尔法食品");
        supplier("S-OTHER", OTHER, "阿尔法供应");
        sale("SO-PG-TARGET-OTHER", OTHER, "C-OTHER", SalesOrderStatus.CONFIRMED, LocalDate.of(2026, 1, 5), 8L);
        purchase("PO-PG-TARGET-OTHER", OTHER, "S-OTHER", PurchaseOrderStatus.APPROVED, LocalDate.of(2026, 1, 5), 8L);

        em.flush();
        // Make the targets the oldest rows by creation time (lists sort createdAt DESC).
        jdbc.update("UPDATE sales_orders SET created_at = TIMESTAMP '2020-01-01 00:00:00' WHERE id = 'SO-PG-TARGET'");
        jdbc.update("UPDATE purchase_orders SET created_at = TIMESTAMP '2020-01-01 00:00:00' WHERE id = 'PO-PG-TARGET'");
        em.clear();
    }

    // ---------------- sales ----------------

    @Test
    void salesTargetIsNotOnPageOneButKeywordFindsItThere() {
        Page<SalesOrder> plain = sales.searchListPage(FACTORY, null, null, null, null, null, PAGE1);
        assertThat(plain.getTotalElements()).isEqualTo(8);
        assertThat(plain.getContent()).extracting(SalesOrder::getId).doesNotContain("SO-PG-TARGET");

        Page<SalesOrder> byNumber = sales.searchListPage(FACTORY, null, null, "target", null, null, PAGE1);
        assertThat(byNumber.getContent()).extracting(SalesOrder::getId).containsExactly("SO-PG-TARGET");
        assertThat(byNumber.getTotalElements()).isEqualTo(1);
    }

    @Test
    void salesKeywordMatchesCustomerNameAndNeverOtherFactory() {
        Page<SalesOrder> page = sales.searchListPage(FACTORY, null, null, "阿尔法", null, null, PAGE1);
        assertThat(page.getContent()).extracting(SalesOrder::getId).containsExactly("SO-PG-TARGET");
        assertThat(page.getContent()).extracting(SalesOrder::getFactoryId).containsOnly(FACTORY);
        assertThat(sales.searchListPage(OTHER, null, null, "阿尔法", null, null, PAGE1).getContent())
                .extracting(SalesOrder::getId).containsExactly("SO-PG-TARGET-OTHER");
    }

    @Test
    void salesLikeWildcardsAreLiteral() {
        // service layer escapes % _ \ ; the repository contract is "escapedKeyword" with ESCAPE '\'
        Page<SalesOrder> percent = sales.searchListPage(FACTORY, null, null, "PCT\\%", null, null, PAGE1);
        assertThat(percent.getContent()).extracting(SalesOrder::getId).containsExactly("SO-PG-PCT%X");
        assertThat(sales.searchListPage(FACTORY, null, null, "SO-PG-0\\_", null, null, PAGE1).getTotalElements())
                .isZero();
    }

    @Test
    void salesDateStatusAndScopeCombineBeforePaging() {
        assertThat(sales.searchListPage(FACTORY, null, null, null,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), PAGE1).getContent())
                .extracting(SalesOrder::getId).containsExactly("SO-PG-TARGET");
        assertThat(sales.searchListPage(FACTORY, null, SalesOrderStatus.CONFIRMED, null, null, null, PAGE1)
                .getTotalElements()).isEqualTo(1);
        assertThat(sales.searchListPage(FACTORY, List.of(8L), null, null, null, null, PAGE1).getContent())
                .extracting(SalesOrder::getId).containsExactly("SO-PG-TARGET");
        assertThat(sales.searchListPage(FACTORY, List.of(), null, null, null, null, PAGE1).getTotalElements())
                .isZero();
    }

    // ---------------- purchase ----------------

    @Test
    void purchaseTargetIsNotOnPageOneButKeywordFindsItThere() {
        Page<PurchaseOrder> plain = purchases.searchListPage(FACTORY, null, null, null, null, null, PAGE1);
        assertThat(plain.getTotalElements()).isEqualTo(7);
        assertThat(plain.getContent()).extracting(PurchaseOrder::getId).doesNotContain("PO-PG-TARGET");

        Page<PurchaseOrder> byNumber = purchases.searchListPage(FACTORY, null, null, "target", null, null, PAGE1);
        assertThat(byNumber.getContent()).extracting(PurchaseOrder::getId).containsExactly("PO-PG-TARGET");
        assertThat(byNumber.getTotalElements()).isEqualTo(1);
    }

    @Test
    void purchaseKeywordMatchesSupplierNameAndNeverOtherFactory() {
        Page<PurchaseOrder> page = purchases.searchListPage(FACTORY, null, null, "阿尔法", null, null, PAGE1);
        assertThat(page.getContent()).extracting(PurchaseOrder::getId).containsExactly("PO-PG-TARGET");
        assertThat(page.getContent()).extracting(PurchaseOrder::getFactoryId).containsOnly(FACTORY);
        assertThat(purchases.searchListPage(OTHER, null, null, "阿尔法", null, null, PAGE1).getContent())
                .extracting(PurchaseOrder::getId).containsExactly("PO-PG-TARGET-OTHER");
    }

    @Test
    void purchaseDateStatusAndOwnersCombineBeforePaging() {
        Page<PurchaseOrder> byDate = purchases.searchListPage(FACTORY, null, null, null,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), PAGE1);
        assertThat(byDate.getContent()).extracting(PurchaseOrder::getId).containsExactly("PO-PG-TARGET");
        assertThat(byDate.getTotalElements()).isEqualTo(1);

        // open-ended bounds
        assertThat(purchases.searchListPage(FACTORY, null, null, null, LocalDate.of(2026, 3, 1), null, PAGE1)
                .getTotalElements()).isEqualTo(6);
        assertThat(purchases.searchListPage(FACTORY, null, null, null, null, LocalDate.of(2026, 1, 31), PAGE1)
                .getTotalElements()).isEqualTo(1);

        assertThat(purchases.searchListPage(FACTORY, null, PurchaseOrderStatus.APPROVED, null, null, null, PAGE1)
                .getTotalElements()).isEqualTo(1);
        assertThat(purchases.searchListPage(FACTORY, List.of(8L), null, null, null, null, PAGE1).getContent())
                .extracting(PurchaseOrder::getId).containsExactly("PO-PG-TARGET");
        assertThat(purchases.searchListPage(FACTORY, List.of(), null, null, null, null, PAGE1).getTotalElements())
                .isZero();
    }

    // ---------------- seed helpers ----------------

    private void customer(String id, String factoryId, String name) {
        Customer c = new Customer();
        c.setId(id);
        c.setFactoryId(factoryId);
        c.setCode(id);
        c.setCustomerCode(id);
        c.setName(name);
        c.setCreatedBy(1L);
        em.persist(c);
    }

    private void supplier(String id, String factoryId, String name) {
        Supplier s = new Supplier();
        s.setId(id);
        s.setFactoryId(factoryId);
        s.setCode(id);
        s.setSupplierCode(id);
        s.setName(name);
        s.setCreatedBy(1L);
        em.persist(s);
    }

    private void sale(String number, String factoryId, String customerId, SalesOrderStatus status,
                      LocalDate orderDate, long createdBy) {
        SalesOrder o = new SalesOrder();
        o.setId(number);
        o.setFactoryId(factoryId);
        o.setOrderNumber(number);
        o.setCustomerId(customerId);
        o.setOrderDate(orderDate);
        o.setStatus(status);
        o.setCreatedBy(createdBy);
        em.persist(o);
    }

    private void purchase(String number, String factoryId, String supplierId, PurchaseOrderStatus status,
                          LocalDate orderDate, long createdBy) {
        PurchaseOrder o = new PurchaseOrder();
        o.setId(number);
        o.setFactoryId(factoryId);
        o.setOrderNumber(number);
        o.setSupplierId(supplierId);
        o.setOrderDate(orderDate);
        o.setStatus(status);
        o.setCreatedBy(createdBy);
        em.persist(o);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set when PostgreSQL verification is enabled");
        }
        return value;
    }
}
