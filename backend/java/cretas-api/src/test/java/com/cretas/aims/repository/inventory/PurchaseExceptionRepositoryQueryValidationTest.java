package com.cretas.aims.integration;

import com.cretas.aims.entity.inventory.PurchaseException;
import com.cretas.aims.entity.enums.ReceiveExceptionType;
import com.cretas.aims.repository.inventory.PurchaseExceptionRepository;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManager;
import javax.sql.DataSource;
import java.util.Optional;
import java.util.UUID;
import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real Hibernate/Spring Data repository lock contract against a disposable PostgreSQL schema. */
class PurchaseExceptionRepositoryQueryValidationTest {
    private static final String FACTORY = "JPA-LOCK-F1";
    private static final String EXCEPTION_ID = "EXC-JPA-1";

    @Test
    void realRepositoryUsesTenantPredicateAndBlocksUntilLockedRowCommits() throws Exception {
        String baseUrl = System.getenv("CRETAS_WORKFLOW_PG_URL");
        assumeTrue(baseUrl != null && !baseUrl.isBlank(), "disposable PostgreSQL required");
        baseUrl = DisposablePostgresTargetGuard.requireSafeUrl(baseUrl);
        String user = required("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        String schema = "cretas_workflow_verify_pe_jpa_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(baseUrl, user, password));
        admin.execute("CREATE SCHEMA \"" + schema + "\"");
        // Match application JDBC typing: polymorphic SQL functions must type their bind parameters.
        String schemaUrl = baseUrl + (baseUrl.contains("?") ? "&" : "?")
                + "currentSchema=" + schema + "&stringtype=unspecified";
        DataSource dataSource = new DriverManagerDataSource(schemaUrl, user, password);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        SessionFactory sessionFactory = null;
        StandardServiceRegistry registry = null;
        try {
            createTable(jdbc);
            insertPending(jdbc);

            registry = new StandardServiceRegistryBuilder()
                    .applySetting(AvailableSettings.JAKARTA_JDBC_URL, schemaUrl)
                    .applySetting(AvailableSettings.JAKARTA_JDBC_USER, user)
                    .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, password)
                    .applySetting(AvailableSettings.HBM2DDL_AUTO, "none")
                    .applySetting(AvailableSettings.DIALECT, "org.hibernate.dialect.PostgreSQLDialect")
                    .build();
            sessionFactory = new MetadataSources(registry)
                    .addAnnotatedClass(PurchaseException.class)
                    .buildMetadata()
                    .buildSessionFactory();

            EntityManager sharedEntityManager = SharedEntityManagerCreator.createSharedEntityManager(sessionFactory);
            PurchaseExceptionRepository repository = new JpaRepositoryFactory(sharedEntityManager)
                    .getRepository(PurchaseExceptionRepository.class);
            TransactionTemplate transaction = new TransactionTemplate(new JpaTransactionManager(sessionFactory));

            Optional<PurchaseException> wrongFactory = transaction.execute(status ->
                    repository.findByIdAndFactoryIdForUpdate(EXCEPTION_ID, "OTHER-FACTORY"));
            assertTrue(wrongFactory.isEmpty(), "lock query must enforce its factory predicate");
            Optional<PurchaseException> loaded = transaction.execute(status ->
                    repository.findByIdAndFactoryIdForUpdate(EXCEPTION_ID, FACTORY));
            assertTrue(loaded.isPresent());
            assertEquals("PENDING", loaded.orElseThrow().getStatus());

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch secondStarted = new CountDownLatch(1);
            Future<?> first = pool.submit(() -> transaction.execute(status -> {
                repository.lockExceptionNumberSequence("EX-JPA-LOCK");
                PurchaseException exception = repository.findByIdAndFactoryIdForUpdate(EXCEPTION_ID, FACTORY)
                        .orElseThrow();
                exception.setStatus("RESOLVED");
                repository.save(exception);
                sharedEntityManager.flush();
                locked.countDown();
                await(release);
                return null;
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS), "first transaction did not acquire row lock");

            Future<LockRead> second = pool.submit(() -> {
                secondStarted.countDown();
                long started = System.nanoTime();
                PurchaseException exception = transaction.execute(status ->
                        repository.findByIdAndFactoryIdForUpdate(EXCEPTION_ID, FACTORY).orElseThrow());
                return new LockRead(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), exception.getStatus());
            });
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
            Thread.sleep(250L);
            assertFalse(second.isDone(), "second repository call must wait for the first row lock");
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            LockRead readAfterCommit = second.get(10, TimeUnit.SECONDS);
            pool.shutdownNow();

            assertTrue(readAfterCommit.waitedMillis() >= 150L,
                    "second repository call did not observe a database lock wait");
            assertEquals("RESOLVED", readAfterCommit.status());

            jdbc.execute("CREATE UNIQUE INDEX purchase_exception_jpa_number_uq "
                    + "ON purchase_exceptions(factory_id, exception_number) WHERE deleted_at IS NULL");
            String sequencePrefix = "EX-JPA-SEQ-";
            ExecutorService sequencePool = Executors.newFixedThreadPool(2);
            try {
                Future<String> firstNumber = sequencePool.submit(() -> allocateNumber(repository, transaction, sequencePrefix));
                Future<String> secondNumber = sequencePool.submit(() -> allocateNumber(repository, transaction, sequencePrefix));
                String numberA = firstNumber.get(10, TimeUnit.SECONDS);
                String numberB = secondNumber.get(10, TimeUnit.SECONDS);
                assertNotEquals(numberA, numberB, "advisory transaction lock must serialize number allocation");
                assertEquals(2, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM purchase_exceptions WHERE factory_id=? AND exception_number LIKE ?",
                        Integer.class, FACTORY, sequencePrefix + "%"));
            } finally {
                sequencePool.shutdownNow();
            }
        } finally {
            if (sessionFactory != null) {
                sessionFactory.close();
            }
            if (registry != null) {
                StandardServiceRegistryBuilder.destroy(registry);
            }
            admin.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
        }
    }

    private static void createTable(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE purchase_exceptions ("
                + "id varchar(191) primary key, factory_id varchar(191) not null, exception_number varchar(50) not null,"
                + "receive_record_id varchar(191) not null, purchase_order_id varchar(191) not null, supplier_id varchar(191),"
                + "material_type_id varchar(191), material_name varchar(200), exception_type varchar(32) not null,"
                + "po_quantity numeric(15,4) not null, received_quantity numeric(15,4) not null, exception_qty numeric(15,4) not null,"
                + "unit varchar(20), decision varchar(32), decision_by bigint, decision_at timestamp, decision_notes text,"
                + "status varchar(32) not null, created_by bigint not null, owner_user_id bigint, owner_name varchar(100),"
                + "return_order_id varchar(191), version bigint not null, created_at timestamp not null,"
                + "updated_at timestamp not null, deleted_at timestamp)");
    }

    private static void insertPending(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO purchase_exceptions (id,factory_id,exception_number,receive_record_id,purchase_order_id,"
                        + "supplier_id,material_type_id,material_name,exception_type,po_quantity,received_quantity,exception_qty,unit,"
                        + "status,created_by,owner_user_id,version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                EXCEPTION_ID, FACTORY, "EX-JPA-0001", "REC-JPA-1", "PO-JPA-1", "SUP-JPA-1", "MAT-JPA-1", "material",
                "OVER_RECEIVE", 10, 12, 2, "kg", "PENDING", 7, 7, 0,
                java.sql.Timestamp.from(java.time.Instant.now()), java.sql.Timestamp.from(java.time.Instant.now()));
    }

    private static String allocateNumber(PurchaseExceptionRepository repository,
                                         TransactionTemplate transaction,
                                         String prefix) {
        return transaction.execute(status -> {
            repository.lockExceptionNumberSequence(prefix);
            Optional<String> highest = repository.findHighestNumberForPrefix(FACTORY, prefix).stream().findFirst();
            long next = highest.map(value -> Long.parseLong(value.substring(prefix.length())) + 1L).orElse(1L);
            String number = prefix + String.format("%04d", next);
            PurchaseException exception = new PurchaseException();
            exception.setFactoryId(FACTORY);
            exception.setExceptionNumber(number);
            exception.setReceiveRecordId("REC-" + UUID.randomUUID());
            exception.setPurchaseOrderId("PO-" + UUID.randomUUID());
            exception.setSupplierId("SUP-JPA-1");
            exception.setMaterialTypeId("MAT-JPA-1");
            exception.setExceptionType(ReceiveExceptionType.OVER_RECEIVE);
            exception.setPoQuantity(BigDecimal.TEN);
            exception.setReceivedQuantity(BigDecimal.valueOf(12));
            exception.setExceptionQty(BigDecimal.valueOf(2));
            exception.setStatus("PENDING");
            exception.setCreatedBy(7L);
            repository.save(exception);
            return number;
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private record LockRead(long waitedMillis, String status) { }
}
