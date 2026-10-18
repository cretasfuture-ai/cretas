import com.cretas.aims.entity.enums.*;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.finance.impl.ArApServiceImpl;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Opt-in local PG proof. Compile outside target/classes; never use a business schema. */
public class ArApStatementPgConcurrencyProbe {
    private static final String FACTORY = "SYNTHETIC-P9-A";
    private static final LocalDate ORIGINAL = LocalDate.of(2026, 10, 1);
    private static final LocalDate START = LocalDate.of(2026, 10, 10);
    private static final LocalDate END = LocalDate.of(2026, 10, 31);

    public static void main(String[] args) throws Exception {
        require(Arrays.equals(args, new String[]{"NON_PRODUCTION_ONLY"}), "Explicit local probe flag required");
        String url = Objects.requireNonNull(System.getenv("P9_PG_URL"));
        require(url.equals("jdbc:postgresql://127.0.0.1:55447/vision_entry_probe_20261015"), "Unapproved database target");
        String user = Objects.requireNonNull(System.getenv("P9_PG_USER"));
        var ds = new DriverManagerDataSource(url, user, System.getenv().getOrDefault("P9_PG_PASSWORD", ""));
        String schema = "p9_refund_probe_" + UUID.randomUUID().toString().replace("-", "");
        require(schema.matches("p9_refund_probe_[a-f0-9]{32}"), "Invalid probe schema");
        boolean created = false;
        LocalContainerEntityManagerFactoryBean factory = null;
        try {
            try (Connection c = ds.getConnection(); var s = c.createStatement()) {
                try (var r = s.executeQuery("select current_database()")) {
                    require(r.next() && r.getString(1).equals("vision_entry_probe_20261015"), "Database identity mismatch");
                }
                s.execute("CREATE SCHEMA " + schema);
                created = true;
            }
            System.out.println("PROBE_SCHEMA_CREATED=" + schema);
            factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(ds);
            factory.setPersistenceUnitName("p9-refund-isolated");
            factory.setManagedTypes(PersistenceManagedTypes.of(ArApTransaction.class.getName(),
                    com.cretas.aims.entity.finance.ArApPaymentAllocation.class.getName()));
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create",
                    "hibernate.default_schema", schema, "hibernate.jdbc.batch_size", "100"));
            factory.afterPropertiesSet();
            EntityManagerFactory emf = Objects.requireNonNull(factory.getObject());
            EntityManager em = SharedEntityManagerCreator.createSharedEntityManager(emf);
            var transactions = new JpaTransactionManager(emf);
            var repo = new JpaRepositoryFactory(em).getRepository(ArApTransactionRepository.class);
            exercise("RR", false, repo, em, transactions);
            exercise("RC", true, repo, em, transactions);
            System.out.println("PROBE_RESULT=PASS");
        } finally {
            if (factory != null) factory.destroy();
            if (created) {
                // Cleanup is limited to this invocation's randomly named, owned schema.
                try (Connection c = ds.getConnection(); var p = c.prepareStatement(
                        "select n.nspname from pg_namespace n join pg_roles r on r.oid=n.nspowner where n.nspname=? and r.rolname=current_user")) {
                    p.setString(1, schema);
                    try (var r = p.executeQuery()) { require(r.next(), "Schema owner mismatch; cleanup refused"); }
                    try (var s = c.createStatement()) { s.execute("DROP SCHEMA " + schema + " CASCADE"); }
                }
                System.out.println("PROBE_SCHEMA_REMOVED=" + schema);
            }
        }
    }

    private static void exercise(String cp, boolean weakControl, ArApTransactionRepository real,
                                 EntityManager em, JpaTransactionManager manager) throws Exception {
        var write = new TransactionTemplate(manager);
        write.executeWithoutResult(tx -> {
            var rows = new ArrayList<ArApTransaction>();
            for (int i = 0; i < 502; i++) rows.add(row(cp, cp + "-old-" + String.format("%04d", i),
                    ArApTransactionType.AR_INVOICE, "1", ORIGINAL, ArApApprovalStatus.APPROVED));
            rows.add(row(cp, cp + "-refund", ArApTransactionType.AR_REFUND, "15", ORIGINAL, ArApApprovalStatus.APPROVED));
            rows.add(row(cp, cp + "-period", ArApTransactionType.AR_INVOICE, "60", LocalDate.of(2026, 10, 15), ArApApprovalStatus.APPROVED));
            rows.add(row(cp, cp + "-pending", ArApTransactionType.AR_ADJUSTMENT, "-900", ORIGINAL, ArApApprovalStatus.PENDING));
            rows.add(row(cp, cp + "-rejected", ArApTransactionType.AR_REFUND, "800", ORIGINAL, ArApApprovalStatus.REJECTED));
            var deleted = row(cp, cp + "-deleted", ArApTransactionType.AR_INVOICE, "9999", ORIGINAL, ArApApprovalStatus.APPROVED);
            deleted.setDeletedAt(LocalDateTime.of(2026, 10, 2, 0, 0)); rows.add(deleted);
            var other = row(cp, cp + "-foreign", ArApTransactionType.AR_INVOICE, "7777", ORIGINAL, ArApApprovalStatus.APPROVED);
            other.setFactoryId("SYNTHETIC-P9-B"); rows.add(other);
            real.saveAllAndFlush(rows);
        });
        var firstPage = new CountDownLatch(1);
        var writerCommitted = new CountDownLatch(1);
        var injected = new AtomicBoolean();
        var isolation = new AtomicReference<String>();
        var readOnly = new AtomicReference<String>();
        var query = mock(ArApTransactionRepository.class);
        when(query.findByFactoryIdAndCounterpartyTypeAndCounterpartyIdOrderByTransactionDateDesc(
                eq(FACTORY), eq(CounterpartyType.CUSTOMER), eq(cp), any(Pageable.class)))
                .thenAnswer(inv -> {
                    Pageable page = inv.getArgument(3);
                    Page<ArApTransaction> result = real.findByFactoryIdAndCounterpartyTypeAndCounterpartyIdOrderByTransactionDateDesc(
                            FACTORY, CounterpartyType.CUSTOMER, cp, page);
                    if (page.getPageNumber() == 0 && injected.compareAndSet(false, true)) {
                        isolation.set((String) em.createNativeQuery("show transaction_isolation").getSingleResult());
                        readOnly.set((String) em.createNativeQuery("show transaction_read_only").getSingleResult());
                        require(result.getContent().size() == 500 && result.hasNext(), "Probe never crossed a real page boundary");
                        firstPage.countDown();
                        require(writerCommitted.await(20, TimeUnit.SECONDS), "Writer did not commit before page two");
                    }
                    return result;
                });
        when(query.findByCounterpartyAndDateRange(FACTORY, CounterpartyType.CUSTOMER, cp, START, END))
                .thenAnswer(inv -> real.findByCounterpartyAndDateRange(FACTORY, CounterpartyType.CUSTOMER, cp, START, END));
        var target = new ArApServiceImpl(query, mock(CustomerRepository.class), mock(SupplierRepository.class));
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        if (weakControl) {
            var attributes = new NameMatchTransactionAttributeSource();
            var attribute = new RuleBasedTransactionAttribute();
            attribute.setReadOnly(true); attribute.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            attributes.addTransactionalMethod("getStatement", attribute);
            interceptor.setTransactionAttributeSource(attributes);
        } else {
            // Read the actual production method annotation, rather than reproducing its isolation setting.
            interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        }
        var proxy = new ProxyFactory(target); proxy.setInterfaces(ArApService.class); proxy.addAdvice(interceptor);
        ArApService service = (ArApService) proxy.getProxy();
        var pool = Executors.newSingleThreadExecutor();
        Map<String, Object> during;
        try {
            Future<Map<String, Object>> reader = pool.submit(() -> service.getStatement(FACTORY, CounterpartyType.CUSTOMER, cp, START, END));
            try {
                if (!firstPage.await(20, TimeUnit.SECONDS)) {
                    if (reader.isDone()) reader.get();
                    throw new AssertionError("Reader never reached page boundary");
                }
                write.executeWithoutResult(tx -> {
                    var reversal = row(cp, cp + "-zz-reversal", ArApTransactionType.AR_ADJUSTMENT, "-15", ORIGINAL, ArApApprovalStatus.APPROVED);
                    reversal.setCreatedAt(LocalDateTime.of(2026, 11, 1, 10, 0));
                    real.saveAllAndFlush(List.of(reversal, row(cp, cp + "-new-period", ArApTransactionType.AR_INVOICE,
                            "77", LocalDate.of(2026, 10, 20), ArApApprovalStatus.APPROVED)));
                });
            } finally { writerCommitted.countDown(); }
            during = reader.get(20, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); require(pool.awaitTermination(5, TimeUnit.SECONDS), "Reader did not terminate"); }
        boolean coherentOld = equal(during, "openingBalance", "517") && equal(during, "periodDebit", "60")
                && equal(during, "periodCredit", "0") && equal(during, "closingBalance", "577");
        require(readOnly.get().equals("on"), "Read-only transaction was not applied");
        if (weakControl) {
            require(isolation.get().equals("read committed") && !coherentOld, "Weak isolation control did not break the same-snapshot assertion");
        } else {
            require(isolation.get().equals("repeatable read") && coherentOld, "Production statement mixed concurrent snapshots: " + during);
        }
        var after = service.getStatement(FACTORY, CounterpartyType.CUSTOMER, cp, START, END);
        require(equal(after, "openingBalance", "502") && equal(after, "periodDebit", "137")
                && equal(after, "periodCredit", "0") && equal(after, "closingBalance", "639"), "Next read did not see committed rows: " + after);
        System.out.println("CASE=" + cp + " isolation=" + isolation.get() + " readOnly=" + readOnly.get()
                + " duringOpening=" + during.get("openingBalance") + " duringDebit=" + during.get("periodDebit")
                + " duringClosing=" + during.get("closingBalance") + " nextClosing=" + after.get("closingBalance")
                + " sameSnapshot=" + coherentOld + " result=PASS");
    }

    private static ArApTransaction row(String cp, String id, ArApTransactionType type, String amount,
                                      LocalDate date, ArApApprovalStatus status) {
        var row = new ArApTransaction(); row.setId(id); row.setFactoryId(FACTORY); row.setTransactionNumber(id);
        row.setTransactionType(type); row.setCounterpartyType(CounterpartyType.CUSTOMER); row.setCounterpartyId(cp);
        row.setCounterpartyName("isolated concurrency fixture"); row.setAmount(new BigDecimal(amount));
        row.setTransactionDate(date); row.setApprovalStatus(status); row.setBalanceAfter(new BigDecimal("999999"));
        return row;
    }

    private static boolean equal(Map<String, Object> row, String key, String value) {
        return ((BigDecimal) row.get(key)).compareTo(new BigDecimal(value)) == 0;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
