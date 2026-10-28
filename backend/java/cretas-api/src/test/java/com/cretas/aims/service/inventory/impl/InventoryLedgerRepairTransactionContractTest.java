package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.inventory.InventoryLedgerLineDTO;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.enums.PurchaseReceiveStatus;
import com.cretas.aims.entity.enums.SnapshotType;
import com.cretas.aims.entity.finance.AccountingPeriod;
import com.cretas.aims.entity.inventory.InventoryLedgerSnapshot;
import com.cretas.aims.entity.inventory.PurchaseReceiveItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.finance.AccountingPeriodRepository;
import com.cretas.aims.repository.inventory.InventoryLedgerSnapshotRepository;
import com.cretas.aims.service.inventory.InventoryLedgerService;
import com.cretas.aims.service.inventory.InventoryLedgerSnapshotService;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual service transaction/EntityManager against a task-owned PG17 schema. */
class InventoryLedgerRepairTransactionContractTest {
    @Test
    void repairDeclaresRepeatableRead() throws Exception {
        var method = InventoryLedgerSnapshotServiceImpl.class.getMethod(
                "repairMissingInventorySnapshots", String.class, String.class);
        assertThat(method.getAnnotation(Transactional.class).isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    @Test
    void actualRepairKeepsOneCutoffAcrossCommittedInventoryWritesAndLocksItsPeriod() throws Exception {
        String url = System.getenv("SC28_REPAIR_PG_JDBC_URL");
        assumeTrue(url != null && !url.isBlank(), "owned local PostgreSQL fixture required");
        assertThat(url).matches("jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1):55439/cretas_sc28_disposable_test");
        String schema = "sc28_repair_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource source = new DriverManagerDataSource(url, "postgres", "");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("cretas_sc28_disposable_test");
        assertThat(jdbc.queryForObject("show server_version_num", String.class)).startsWith("17");
        jdbc.execute("CREATE SCHEMA " + schema);
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        EntityManagerFactory emf = null;
        var executor = Executors.newSingleThreadExecutor();
        try {
            factory.setDataSource(source);
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setManagedTypes(PersistenceManagedTypes.of(AccountingPeriod.class.getName(),InventoryLedgerSnapshot.class.getName()));
            HashMap<String,Object> properties = new HashMap<>();
            properties.put("hibernate.hbm2ddl.auto", "create-drop");
            properties.put("hibernate.default_schema", schema);
            properties.put("hibernate.show_sql", "false");
            factory.setJpaPropertyMap(properties);
            factory.afterPropertiesSet();
            emf = factory.getObject();
            assertThat(emf).isNotNull();
            var em = SharedEntityManagerCreator.createSharedEntityManager(emf);
            JpaTransactionManager transactions = new JpaTransactionManager(emf);
            transactions.setDataSource(source);
            TransactionTemplate seed = new TransactionTemplate(transactions);
            AccountingPeriod target = period("target", 3);
            AccountingPeriod previous = period("previous", 2);
            seed.executeWithoutResult(status -> { em.persist(target); em.persist(previous); });
            jdbc.execute("CREATE TABLE " + schema + ".source_inventory (id text primary key, qty numeric not null)");
            jdbc.update("INSERT INTO " + schema + ".source_inventory VALUES ('MAT-1',1),('MAT-2',2)");

            var snapshots = mock(InventoryLedgerSnapshotRepository.class);
            var periods = mock(AccountingPeriodRepository.class);
            var materials = mock(RawMaterialTypeRepository.class);
            var ledger = mock(InventoryLedgerService.class);
            when(periods.findById("target")).thenReturn(Optional.of(target));
            when(periods.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull("F006",2026,2)).thenReturn(Optional.of(previous));
            when(materials.findByFactoryId("F006")).thenReturn(List.of(material("MAT-1"),material("MAT-2")));
            when(snapshots.findByFactoryIdAndAccountingPeriodIdAndSnapshotTypeAndDeletedAtIsNull(
                    "F006","target",SnapshotType.PERIOD_CLOSE)).thenReturn(List.of());
            when(snapshots.findByFactoryIdAndAccountingPeriodIdAndSnapshotTypeAndDeletedAtIsNull(
                    "F006","previous",SnapshotType.PERIOD_CLOSE)).thenReturn(List.of(snapshot("MAT-1",1),snapshot("MAT-2",2)));
            when(snapshots.findLatestBeforePeriod(eq("F006"),anyString(),eq(SnapshotType.PERIOD_CLOSE),anyInt()))
                    .thenReturn(List.of());
            List<InventoryLedgerSnapshot> saved = new ArrayList<>();
            when(snapshots.save(any(InventoryLedgerSnapshot.class))).thenAnswer(invocation -> {
                InventoryLedgerSnapshot row=invocation.getArgument(0); em.persist(row);em.flush();saved.add(row);return row;
            });
            when(ledger.getLedger(eq("F006"),eq(LocalDate.of(2026,3,1)),eq(LocalDate.of(2026,3,31)),isNull(),
                    eq(InventoryLedgerService.LedgerReadMode.STRICT_REPAIR))).thenAnswer(invocation -> {
                assertThat(jdbc.queryForObject("show transaction_isolation",String.class)).isEqualTo("repeatable read");
                BigDecimal first = jdbc.queryForObject("SELECT qty FROM " + schema + ".source_inventory WHERE id='MAT-1'",BigDecimal.class);
                var writer = executor.submit(() -> {
                    try (Connection connection=DriverManager.getConnection(url,"postgres","")) {
                        connection.setAutoCommit(false);
                        try (var statement=connection.createStatement()) {
                            statement.executeUpdate("UPDATE " + schema + ".source_inventory SET qty=qty+100");
                        }
                        connection.commit(); // Inventory writers do not take the period lock.
                        try (var statement=connection.createStatement()) {
                            statement.execute("SET LOCAL lock_timeout='200ms'");
                            statement.executeUpdate("UPDATE " + schema + ".accounting_periods SET status='OPEN' WHERE id='target' AND factory_id='F006'");
                            throw new AssertionError("Repair must hold the actual accounting period lock");
                        } catch (SQLException expected) {
                            assertThat(expected.getSQLState()).isEqualTo("55P03");
                            connection.rollback();
                        }
                        return true;
                    }
                });
                assertThat(writer.get(10,TimeUnit.SECONDS)).isTrue();
                BigDecimal second=jdbc.queryForObject("SELECT qty FROM " + schema + ".source_inventory WHERE id='MAT-2'",BigDecimal.class);
                assertThat(first).isEqualByComparingTo("1");
                assertThat(second).isEqualByComparingTo("2");
                return List.of(line("MAT-1",first),line("MAT-2",second));
            });
            var implementation = new InventoryLedgerSnapshotServiceImpl(snapshots,periods,materials,ledger,em);
            TransactionInterceptor interceptor = new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource());
            ProxyFactory proxy = new ProxyFactory(implementation);
            proxy.addAdvice(interceptor);
            InventoryLedgerSnapshotService service=(InventoryLedgerSnapshotService)proxy.getProxy();
            var result=service.repairMissingInventorySnapshots("F006","target");
            assertThat(result.created()).isEqualTo(2);
            assertThat(saved).extracting(InventoryLedgerSnapshot::getClosingQty)
                    .usingComparatorForType(BigDecimal::compareTo,BigDecimal.class)
                    .containsExactly(new BigDecimal("1"),new BigDecimal("2"));
            assertThat(jdbc.queryForObject("SELECT qty FROM " + schema + ".source_inventory WHERE id='MAT-2'",BigDecimal.class)).isEqualByComparingTo("102");
            assertThat(jdbc.queryForObject("SELECT status FROM " + schema + ".accounting_periods WHERE id='target' AND factory_id='F006'",String.class)).isEqualTo("CLOSED");
            verify(ledger,never()).getLedger(anyString(),any(),any(),any());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".inventory_ledger_snapshots WHERE factory_id='F006' AND accounting_period_id='target'",Integer.class)).isEqualTo(2);

            // Independent failure scenario in the same task-owned schema. The
            // first real Hibernate insert is flushed before the second fails.
            jdbc.update("DELETE FROM " + schema + ".inventory_ledger_snapshots WHERE factory_id='F006' AND accounting_period_id='target'");
            doReturn(List.of(line("MAT-1",BigDecimal.ONE),line("MAT-2",new BigDecimal("2"))))
                    .when(ledger).getLedger(eq("F006"),eq(LocalDate.of(2026,3,1)),eq(LocalDate.of(2026,3,31)),isNull(),eq(InventoryLedgerService.LedgerReadMode.STRICT_REPAIR));
            int[] writeCount={0};
            doAnswer(invocation -> {
                if (++writeCount[0] == 2) throw new IllegalStateException("injected second snapshot failure");
                InventoryLedgerSnapshot row=invocation.getArgument(0);
                em.persist(row);em.flush();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".inventory_ledger_snapshots WHERE factory_id='F006' AND accounting_period_id='target'",Integer.class)).isEqualTo(1);
                return row;
            }).when(snapshots).save(any(InventoryLedgerSnapshot.class));
            assertThatThrownBy(() -> service.repairMissingInventorySnapshots("F006","target"))
                    .isInstanceOf(IllegalStateException.class).hasMessage("injected second snapshot failure");
            assertThat(writeCount[0]).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".inventory_ledger_snapshots WHERE factory_id='F006' AND accounting_period_id='target'",Integer.class)).isZero();
        } finally {
            executor.shutdownNow();
            if (emf != null) factory.destroy();
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void actualInboundAggregationIncludesOnlyConfirmedTenantDateAndLiveRows() throws Exception {
        String url = System.getenv("SC28_REPAIR_PG_JDBC_URL");
        assumeTrue(url != null && !url.isBlank(), "owned local PostgreSQL fixture required");
        assertThat(url).matches("jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1):55439/cretas_sc28_disposable_test");
        String schema = "sc28_inbound_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource source = new DriverManagerDataSource(url, "postgres", "");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE SCHEMA " + schema);
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        EntityManagerFactory emf = null;
        try {
            factory.setDataSource(source);
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setPackagesToScan("com.cretas.aims.entity");
            HashMap<String,Object> properties = new HashMap<>();
            properties.put("hibernate.hbm2ddl.auto", "create-drop");
            properties.put("hibernate.default_schema", schema);
            properties.put("hibernate.show_sql", "false");
            factory.setJpaPropertyMap(properties);
            factory.afterPropertiesSet();
            emf = factory.getObject();
            assertThat(emf).isNotNull();
            var em = SharedEntityManagerCreator.createSharedEntityManager(emf);
            JpaTransactionManager transactions = new JpaTransactionManager(emf);
            transactions.setDataSource(source);
            TransactionTemplate tx = new TransactionTemplate(transactions);
            seedInboundRows(tx, em);

            var service = new InventoryLedgerServiceImpl(
                    mock(InventoryLedgerSnapshotRepository.class),
                    mock(RawMaterialTypeRepository.class),
                    mock(AccountingPeriodRepository.class),
                    mock(MaterialBatchRepository.class));
            var entityManagerField = InventoryLedgerServiceImpl.class.getDeclaredField("em");
            entityManagerField.setAccessible(true);
            entityManagerField.set(service, em);

            BigDecimal qty = tx.execute(status -> invokeInbound(service, "aggregateInboundQty"));
            BigDecimal amount = tx.execute(status -> invokeInbound(service, "aggregateInboundAmount"));
            assertThat(qty).isEqualByComparingTo("1");
            assertThat(amount).isEqualByComparingTo("10.00");
        } finally {
            if (emf != null) factory.destroy();
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private static void seedInboundRows(TransactionTemplate tx, jakarta.persistence.EntityManager em) {
        tx.executeWithoutResult(status -> {
            Factory f006 = factory("F006", "Inbound F006");
            Factory f007 = factory("F007", "Inbound F007");
            em.persist(f006);
            em.persist(f007);
            User u006 = user("inbound-f006", "F006");
            User u007 = user("inbound-f007", "F007");
            em.persist(u006);
            em.persist(u007);
            em.flush();
            em.persist(supplier("SUP-F006", "F006", u006.getId()));
            em.persist(supplier("SUP-F007", "F007", u007.getId()));
            RawMaterialType material = new RawMaterialType();
            material.setId("RMT-INBOUND");
            material.setFactoryId("F006");
            material.setCode("RMT-INBOUND");
            material.setName("inbound test salt");
            material.setUnit("kg");
            em.persist(material);
            em.flush();

            persistReceipt(em, "confirmed", "F006", "SUP-F006", u006.getId(),
                    PurchaseReceiveStatus.CONFIRMED, LocalDate.of(2026, 10, 10),
                    BigDecimal.ONE, new BigDecimal("10"), null);
            persistReceipt(em, "draft", "F006", "SUP-F006", u006.getId(),
                    PurchaseReceiveStatus.DRAFT, LocalDate.of(2026, 10, 10),
                    new BigDecimal("5"), new BigDecimal("20"), null);
            persistReceipt(em, "pending", "F006", "SUP-F006", u006.getId(),
                    PurchaseReceiveStatus.PENDING_QC, LocalDate.of(2026, 10, 10),
                    new BigDecimal("6"), new BigDecimal("30"), null);
            persistReceipt(em, "rejected", "F006", "SUP-F006", u006.getId(),
                    PurchaseReceiveStatus.REJECTED, LocalDate.of(2026, 10, 10),
                    new BigDecimal("7"), new BigDecimal("40"), null);
            persistReceipt(em, "cancelled", "F006", "SUP-F006", u006.getId(),
                    PurchaseReceiveStatus.CANCELLED, LocalDate.of(2026, 10, 10),
                    new BigDecimal("8"), new BigDecimal("50"), null);
            persistReceipt(em, "other-factory", "F007", "SUP-F007", u007.getId(),
                    PurchaseReceiveStatus.CONFIRMED, LocalDate.of(2026, 10, 10),
                    new BigDecimal("9"), new BigDecimal("60"), null);
            persistReceipt(em, "out-of-range", "F006", "SUP-F006", u006.getId(),
                    PurchaseReceiveStatus.CONFIRMED, LocalDate.of(2026, 9, 30),
                    new BigDecimal("10"), new BigDecimal("70"), null);
            persistReceipt(em, "soft-deleted", "F006", "SUP-F006", u006.getId(),
                    PurchaseReceiveStatus.CONFIRMED, LocalDate.of(2026, 10, 10),
                    new BigDecimal("11"), new BigDecimal("80"), LocalDateTime.now());
        });
    }

    private static Factory factory(String id, String name) {
        Factory factory = new Factory();
        factory.setId(id);
        factory.setName(name);
        factory.setType(FactoryType.FACTORY);
        factory.setLevel(0);
        factory.setIsActive(true);
        factory.setManuallyVerified(false);
        factory.setAiWeeklyQuota(20);
        return factory;
    }

    private static User user(String username, String factoryId) {
        User user = new User();
        user.setFactoryId(factoryId);
        user.setUsername(username);
        user.setPasswordHash("test-hash");
        user.setIsActive(true);
        return user;
    }

    private static Supplier supplier(String id, String factoryId, Long createdBy) {
        Supplier supplier = new Supplier();
        supplier.setId(id);
        supplier.setFactoryId(factoryId);
        supplier.setCode(id);
        supplier.setSupplierCode(id);
        supplier.setName(id);
        supplier.setCreatedBy(createdBy);
        supplier.setVersion(0L);
        supplier.setIsActive(true);
        return supplier;
    }

    private static void persistReceipt(jakarta.persistence.EntityManager em, String id, String factoryId,
                                       String supplierId, Long receivedBy, PurchaseReceiveStatus status,
                                       LocalDate date, BigDecimal quantity, BigDecimal unitPrice,
                                       LocalDateTime deletedAt) {
        PurchaseReceiveRecord record = new PurchaseReceiveRecord();
        record.setId("receipt-" + id);
        record.setFactoryId(factoryId);
        record.setReceiveNumber("RN-" + id);
        record.setSupplierId(supplierId);
        record.setReceiveDate(date);
        record.setStatus(status);
        record.setReceivedBy(receivedBy);
        record.setDeletedAt(deletedAt);
        record.setTotalAmount(quantity.multiply(unitPrice));
        em.persist(record);
        PurchaseReceiveItem item = new PurchaseReceiveItem();
        item.setReceiveRecordId(record.getId());
        item.setMaterialTypeId("RMT-INBOUND");
        item.setReceivedQuantity(quantity);
        item.setUnit("kg");
        item.setPriceUnit("kg");
        item.setUnitPrice(unitPrice);
        em.persist(item);
    }

    private static BigDecimal invokeInbound(InventoryLedgerServiceImpl service, String methodName) {
        try {
            Method method = InventoryLedgerServiceImpl.class.getDeclaredMethod(
                    methodName, String.class, String.class, LocalDate.class, LocalDate.class);
            method.setAccessible(true);
            return (BigDecimal) method.invoke(service, "F006", "RMT-INBOUND",
                    LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        } catch (InvocationTargetException e) {
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static AccountingPeriod period(String id,int month) {
        AccountingPeriod period=new AccountingPeriod();
        period.setId(id);period.setFactoryId("F006");period.setYear(2026);period.setMonth(month);
        period.setStatus(AccountingPeriod.Status.CLOSED);return period;
    }
    private static RawMaterialType material(String id) {
        RawMaterialType material=new RawMaterialType();material.setId(id);material.setFactoryId("F006");material.setUnit("kg");return material;
    }
    private static InventoryLedgerSnapshot snapshot(String id,int quantity) {
        InventoryLedgerSnapshot row=new InventoryLedgerSnapshot();row.setMaterialTypeId(id);row.setUnit("kg");row.setClosingQty(BigDecimal.valueOf(quantity));return row;
    }
    private static InventoryLedgerLineDTO line(String id,BigDecimal quantity) {
        InventoryLedgerLineDTO row=new InventoryLedgerLineDTO();row.setMaterialTypeId(id);row.setUnit("kg");row.setOpeningQty(quantity);row.setClosingQty(quantity);return row;
    }
}
