package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.inventory.WarehouseTransferReceiptRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.*;
import com.cretas.aims.entity.inventory.*;
import com.cretas.aims.repository.*;
import com.cretas.aims.repository.inventory.*;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.lineage.LineageEdgeRecorder;
import jakarta.persistence.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual service, Hibernate movement repository, migration, PG row locks and rollback.
 * Unchanged legacy repositories use transaction-bound JDBC adapters, not a full app context.
 * This is a separate required PG execution: an environment-disabled run is NOT a pass.
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class TransferWarehouseDeliveryPostgresContractTest {
    Fixture f;
    @BeforeEach void start() throws Exception { f = new Fixture(); }
    @AfterEach void stop() throws Exception { if (f != null) f.close(); }

    @Test void actualDispatchThenTwoDeltaReceiptsConserveStockAndPreserveLineage() {
        f.dispatch();
        f.assertDecimal("0.10", "select used_quantity from material_batches where id='SB1'");
        assertEquals("SHIPPED", f.jdbc.queryForObject("select status from internal_transfers where id='T1'", String.class));
        assertEquals(1, f.count("material_consumptions")); assertEquals(1, f.count("warehouse_transfer_movements"));
        f.receive("0.04", UUID.randomUUID());
        f.assertDecimal("0.04", "select sum(receipt_quantity) from material_batches where source_doc_id='T1'");
        f.assertDecimal("0.04", "select received_quantity from internal_transfer_items where id=7");
        var partial = f.run(() -> f.service.getWarehouseDelivery("F1", "T1"));
        assertEquals(0, new BigDecimal("0.06").compareTo(partial.getInTransitByUnit().get("kg")));
        assertEquals("SHIPPED", partial.getStage());
        f.receive("0.06", UUID.randomUUID());
        f.assertDecimal("0.10", "select sum(receipt_quantity) from material_batches where source_doc_id='T1'");
        assertEquals(2, f.jdbc.queryForObject("select count(*) from material_batches where source_doc_id='T1'", Integer.class));
        assertEquals(2, f.count("lineage_test")); assertEquals(3, f.count("warehouse_transfer_movements"));
        var complete = f.run(() -> f.service.getWarehouseDelivery("F1", "T1"));
        assertEquals("CONFIRMED", complete.getStage()); assertTrue(complete.getInTransitByUnit().isEmpty());
        assertEquals(2, complete.getItems().get(0).getReceipts().size());
        verify(f.events, times(1)).publishEvent(any(com.cretas.aims.event.TransferConfirmedEvent.class));
    }

    @Test void failuresAfterInventoryAndMovementWritesRollbackEverySideEffect() {
        f.dispatch();
        doThrow(new IllegalStateException("injected final-event failure")).when(f.events)
                .publishEvent(any(com.cretas.aims.event.TransferConfirmedEvent.class));
        UUID key = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> f.receive("0.10", key));
        assertEquals(1, f.count("material_batches")); assertEquals(1, f.count("warehouse_transfer_movements"));
        assertEquals(0, f.count("lineage_test"));
        f.assertDecimal("0", "select received_quantity from internal_transfer_items where id=7");
        assertEquals("SHIPPED", f.jdbc.queryForObject("select status from internal_transfers where id='T1'", String.class));
        reset(f.events); f.receive("0.10", key);
        assertEquals(2, f.count("warehouse_transfer_movements"));
    }

    @Test void concurrentDispatchAndLegacyConfirmationCannotBothDeductTheOrder() throws Exception {
        List<Boolean> results = concurrent(() -> { f.dispatch(); return true; }, () -> {
            f.run(() -> f.service.confirmTransfer("F1", "T1", 9L)); return true;
        });
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        f.assertDecimal("0.10", "select used_quantity from material_batches where id='SB1'");
        assertEquals(1, f.count("material_consumptions"));
    }

    @Test void concurrentReceiptsCannotOverReceiveAndDuplicateKeySurvivesTombstone() throws Exception {
        f.dispatch(); UUID key = UUID.randomUUID();
        List<Boolean> duplicate = concurrent(() -> { f.receive("0.04", key); return true; },
                () -> { f.receive("0.04", key); return true; });
        assertEquals(1, duplicate.stream().filter(Boolean::booleanValue).count());
        f.jdbc.update("update warehouse_transfer_movements set deleted_at=current_timestamp where request_key=?", key.toString());
        assertThrows(RuntimeException.class, () -> f.receive("0.06", key));
        List<Boolean> over = concurrent(() -> { f.receive("0.04", UUID.randomUUID()); return true; },
                () -> { f.receive("0.04", UUID.randomUUID()); return true; });
        assertEquals(1, over.stream().filter(Boolean::booleanValue).count());
        f.assertDecimal("0.08", "select received_quantity from internal_transfer_items where id=7");
        f.assertDecimal("0.08", "select sum(receipt_quantity) from material_batches where source_doc_id='T1'");
        assertEquals(3, f.count("warehouse_transfer_movements"));
    }

    @Test void foreignTenantInvalidItemAndUnshippedStateHaveZeroEffects() {
        assertThrows(RuntimeException.class, () -> f.run(() -> f.service.dispatchWarehouseTransfer("F2", "T1", 9L)));
        assertThrows(RuntimeException.class, () -> f.receive("0.04", UUID.randomUUID()));
        f.dispatch();
        assertThrows(RuntimeException.class, () -> f.run(() -> f.service.receiveWarehouseTransfer("F1", "T1", 9L,
                new WarehouseTransferReceiptRequest(UUID.randomUUID(), Map.of(99L, BigDecimal.ONE)))));
        assertEquals(1, f.count("material_batches")); assertEquals(1, f.count("warehouse_transfer_movements"));
        f.assertDecimal("0", "select received_quantity from internal_transfer_items where id=7");
    }

    private List<Boolean> concurrent(Callable<Boolean> a, Callable<Boolean> b) throws Exception {
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var futures = new ArrayList<Future<Boolean>>();
            for (Callable<Boolean> action : List.of(a, b)) futures.add(pool.submit(() -> {
                ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS));
                try { return action.call(); } catch (com.cretas.aims.exception.BusinessException ex) { return false; }
            }));
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            return List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    /** Migration fixture builds only relevant preceding-table columns; no global DB or production access. */
    static class Fixture implements AutoCloseable {
        final String baseUrl, user, password, schema;
        final JdbcTemplate jdbc;
        final LocalContainerEntityManagerFactoryBean bean;
        final EntityManager em;
        final JpaTransactionManager manager;
        final TransferServiceImpl service;
        final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        Fixture() throws Exception {
            baseUrl = Objects.requireNonNull(System.getenv("CRETAS_WORKFLOW_PG_URL"), "PG URL required");
            if (!baseUrl.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/(s308_contract|cretas_workflow_verify_test)(\\?.*)?"))
                throw new IllegalArgumentException("Disposable local PostgreSQL database required");
            user = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "postgres");
            password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
            schema = "s308_tx_" + UUID.randomUUID().toString().replace("-", "");
            try (var c = DriverManager.getConnection(baseUrl, user, password); var s = c.createStatement()) {
                s.execute("create schema " + schema);
            }
            var ds = new DriverManagerDataSource(baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
            jdbc = new JdbcTemplate(ds);
            jdbc.execute("create table factories(id varchar(191) primary key)");
            jdbc.execute("create table internal_transfers(id varchar(191) primary key, source_factory_id varchar(191), target_factory_id varchar(191), status varchar(32), deleted_at timestamp)");
            jdbc.execute("create table internal_transfer_items(id bigint primary key, transfer_id varchar(191), quantity numeric(18,6), received_quantity numeric(18,6), target_batch_id varchar(191), unit_price numeric(18,6))");
            jdbc.execute("create table factory_warehouses(id varchar(64) primary key, factory_id varchar(191), is_active boolean, deleted_at timestamp)");
            jdbc.execute("create table material_batches(id varchar(191) primary key, factory_id varchar(191), warehouse_id varchar(64), material_type_id varchar(191), receipt_quantity numeric(18,6), used_quantity numeric(18,6), reserved_quantity numeric(18,6), quantity_unit varchar(20), status varchar(32), unit_price numeric(18,6), supplier_id varchar(191), source_doc_id varchar(191), batch_number varchar(64) unique)");
            jdbc.execute("create table material_consumptions(id bigserial primary key, batch_id varchar(191), quantity numeric(18,6), source_type varchar(32))");
            jdbc.execute("create table lineage_test(ref_key varchar(191) primary key, source_id varchar(191), target_id varchar(191), quantity numeric(18,6))");
            String migration = Files.readString(Path.of("src/main/resources/db/flyway/V20261103_77__warehouse_transfer_movements.sql"));
            for (String statement : migration.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
            jdbc.update("insert into factories values ('F1'),('F2')");
            jdbc.update("insert into internal_transfers values ('T1','F1','F1','APPROVED',null)");
            jdbc.update("insert into internal_transfer_items values (7,'T1',0.10,0,null,null)");
            jdbc.update("insert into factory_warehouses values ('W1','F1',true,null),('W2','F1',true,null)");
            jdbc.update("insert into material_batches values ('SB1','F1','W1','M1',0.10,0,0,'kg','AVAILABLE',2.5,'SUP1',null,'SOURCE')");
            bean = new LocalContainerEntityManagerFactoryBean(); bean.setDataSource(ds);
            bean.setManagedTypes(PersistenceManagedTypes.of(WarehouseTransferMovement.class.getName()));
            bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            bean.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect"));
            bean.afterPropertiesSet(); var emf = Objects.requireNonNull(bean.getObject());
            em = SharedEntityManagerCreator.createSharedEntityManager(emf);
            manager = new JpaTransactionManager(emf); manager.setDataSource(ds);
            var movements = new JpaRepositoryFactory(em).getRepository(WarehouseTransferMovementRepository.class);
            var transfers = mock(InternalTransferRepository.class);
            when(transfers.findByIdAndEitherFactoryId(anyString(), anyString())).thenAnswer(inv -> {
                String id = inv.getArgument(0), tenant = inv.getArgument(1);
                return jdbc.query("select * from internal_transfers where id=? and (source_factory_id=? or target_factory_id=?) and deleted_at is null", (rs,n) -> {
                    var t = new InternalTransfer(); t.setId(id); t.setTransferNumber("TRF-TEST");
                    t.setSourceFactoryId(rs.getString("source_factory_id")); t.setTargetFactoryId(rs.getString("target_factory_id"));
                    t.setSourceWarehouseId("W1"); t.setTargetWarehouseId("W2"); t.setStatus(TransferStatus.valueOf(rs.getString("status")));
                    t.setItems(new ArrayList<>(jdbc.query("select * from internal_transfer_items where transfer_id=?", (row,k) -> {
                        var item = new InternalTransferItem(); item.setId(row.getLong("id")); item.setTransferId(id);
                        item.setItemType(TransferItemType.RAW_MATERIAL); item.setMaterialTypeId("M1"); item.setSourceBatchId("SB1");
                        item.setItemName("raw"); item.setUnit("kg"); item.setQuantity(row.getBigDecimal("quantity"));
                        item.setReceivedQuantity(row.getBigDecimal("received_quantity")); item.setTargetBatchId(row.getString("target_batch_id"));
                        item.setUnitPrice(row.getBigDecimal("unit_price")); return item;
                    }, id))); return t;
                }, id, tenant, tenant).stream().findFirst();
            });
            when(transfers.save(any())).thenAnswer(inv -> { InternalTransfer t = inv.getArgument(0);
                jdbc.update("update internal_transfers set status=? where id=?", t.getStatus().name(), t.getId()); return t; });
            var items = mock(InternalTransferItemRepository.class);
            when(items.saveAll(any())).thenAnswer(inv -> { Iterable<InternalTransferItem> rows = inv.getArgument(0);
                var result = new ArrayList<InternalTransferItem>(); for (var i : rows) {
                    jdbc.update("update internal_transfer_items set received_quantity=?,target_batch_id=?,unit_price=? where id=?",
                            i.getReceivedQuantity(), i.getTargetBatchId(), i.getUnitPrice(), i.getId()); result.add(i); } return result; });
            var batches = mock(MaterialBatchRepository.class);
            when(batches.findByIdAndFactoryIdForUpdate(anyString(), anyString())).thenAnswer(inv -> batch(inv.getArgument(0), inv.getArgument(1), true));
            when(batches.findByIdAndFactoryId(anyString(), anyString())).thenAnswer(inv -> batch(inv.getArgument(0), inv.getArgument(1), false));
            when(batches.findAvailableBatchesFEFOByWarehouse(anyString(), anyString(), anyString()))
                    .thenAnswer(inv -> batch("SB1", "F1", true).stream().toList());
            when(batches.saveAndFlush(any())).thenAnswer(inv -> { MaterialBatch b = inv.getArgument(0);
                jdbc.update("update material_batches set used_quantity=?,status=? where id=?", b.getUsedQuantity(), b.getStatus().name(), b.getId()); return b; });
            when(batches.save(any())).thenAnswer(inv -> { MaterialBatch b = inv.getArgument(0);
                jdbc.update("insert into material_batches values (?,?,?,?,?,?,?,?,?,?,?,?,?)", b.getId(), b.getFactoryId(), b.getWarehouseId(), b.getMaterialTypeId(),
                        b.getReceiptQuantity(), b.getUsedQuantity(), b.getReservedQuantity(), b.getQuantityUnit(), b.getStatus().name(), b.getUnitPrice(), b.getSupplierId(), b.getSourceDocId(), b.getBatchNumber()); return b; });
            var consumption = mock(MaterialConsumptionRepository.class);
            when(consumption.save(any())).thenAnswer(inv -> { com.cretas.aims.entity.MaterialConsumption c = inv.getArgument(0);
                jdbc.update("insert into material_consumptions(batch_id,quantity,source_type) values (?,?,?)", c.getBatchId(), c.getQuantity(), c.getSourceType()); return c; });
            var lineage = mock(LineageEdgeRecorder.class);
            when(lineage.recordMaterialTransfer(anyString(), anyString(), anyString(), any(), anyString(), anyLong(), anyString(), isNull()))
                    .thenAnswer(inv -> { jdbc.update("insert into lineage_test values (?,?,?,?)", inv.getArgument(6), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)); return null; });
            service = new TransferServiceImpl(transfers, items, batches, mock(FinishedGoodsBatchRepository.class), events,
                    mock(MaterialBatchService.class), mock(RawMaterialTypeRepository.class));
            ReflectionTestUtils.setField(service, "entityManager", em);
            ReflectionTestUtils.setField(service, "warehouseMovementRepository", movements);
            ReflectionTestUtils.setField(service, "materialConsumptionRepository", consumption);
            ReflectionTestUtils.setField(service, "inventoryLowStockEventPublisher", mock(com.cretas.aims.service.alerts.InventoryLowStockEventPublisher.class));
            ReflectionTestUtils.setField(service, "lineageEdgeRecorder", lineage);
        }
        Optional<MaterialBatch> batch(String id, String tenant, boolean lock) {
            return jdbc.query("select * from material_batches where id=? and factory_id=?" + (lock ? " for update" : ""), (r,n) -> {
                var b = new MaterialBatch(); b.setId(id); b.setFactoryId(tenant); b.setWarehouseId(r.getString("warehouse_id"));
                b.setMaterialTypeId(r.getString("material_type_id")); b.setReceiptQuantity(r.getBigDecimal("receipt_quantity"));
                b.setUsedQuantity(r.getBigDecimal("used_quantity")); b.setReservedQuantity(r.getBigDecimal("reserved_quantity"));
                b.setQuantityUnit(r.getString("quantity_unit")); b.setStatus(MaterialBatchStatus.valueOf(r.getString("status")));
                b.setOwnership(InventoryOwnership.COMPANY_OWNED); b.setSupplierId(r.getString("supplier_id")); b.setUnitPrice(r.getBigDecimal("unit_price")); return b;
            }, id, tenant).stream().findFirst();
        }
        <T> T run(Supplier<T> action) { return new TransactionTemplate(manager).execute(s -> action.get()); }
        void dispatch() { run(() -> service.dispatchWarehouseTransfer("F1", "T1", 9L)); }
        void receive(String qty, UUID key) { run(() -> service.receiveWarehouseTransfer("F1", "T1", 9L,
                new WarehouseTransferReceiptRequest(key, Map.of(7L, new BigDecimal(qty))))); }
        int count(String table) { return Objects.requireNonNull(jdbc.queryForObject("select count(*) from " + table, Integer.class)); }
        void assertDecimal(String expected, String sql) { assertEquals(0, new BigDecimal(expected).compareTo(jdbc.queryForObject(sql, BigDecimal.class))); }
        @Override public void close() throws Exception {
            bean.destroy(); try (var c = DriverManager.getConnection(baseUrl, user, password); var s = c.createStatement()) {
                s.execute("drop schema " + schema + " cascade");
            }
        }
    }
}
