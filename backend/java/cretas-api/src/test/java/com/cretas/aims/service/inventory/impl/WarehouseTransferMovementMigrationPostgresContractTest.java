package com.cretas.aims.service.inventory.impl;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Applies the actual V77 and validates its Entity against PostgreSQL, never Hibernate create DDL. */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class WarehouseTransferMovementMigrationPostgresContractTest {
    TransferWarehouseDeliveryPostgresContractTest.Fixture f;
    @BeforeEach void start() throws Exception { f = new TransferWarehouseDeliveryPostgresContractTest.Fixture(); }
    @AfterEach void stop() throws Exception { if (f != null) f.close(); }

    @Test void actualMigrationHasAllForeignKeysChecksAndPermanentUniqueBusinessKey() {
        assertEquals("20261103.77", f.jdbc.queryForObject("select version from flyway_schema_history where type='SQL'", String.class));
        assertNotNull(f.jdbc.queryForObject("select checksum from flyway_schema_history where type='SQL'", Integer.class));
        assertEquals(3, f.jdbc.queryForObject("select count(*) from information_schema.table_constraints where table_schema=? "
                + "and table_name='warehouse_transfer_movements' and constraint_type='FOREIGN KEY'", Integer.class, f.schema));
        assertEquals(1, f.jdbc.queryForObject("select count(*) from information_schema.table_constraints where table_schema=? "
                + "and table_name='warehouse_transfer_movements' and constraint_name='uq_wtm_request_item'", Integer.class, f.schema));
        String sql = "insert into warehouse_transfer_movements(factory_id,transfer_id,transfer_item_id,kind,operation_id,request_key,quantity,unit) values (?,?,?,?,?,?,?,?)";
        assertThrows(RuntimeException.class, () -> f.jdbc.update(sql, "F1", "T1", 7L, "RECEIPT", "op", "badzero", 0, "kg"));
        assertThrows(RuntimeException.class, () -> f.jdbc.update(sql, "F1", "T1", 7L, "INVALID", "op", "badkind", 1, "kg"));
        assertThrows(RuntimeException.class, () -> f.jdbc.update(sql, "MISSING", "T1", 7L, "RECEIPT", "op", "badtenant", 1, "kg"));
        assertThrows(RuntimeException.class, () -> f.jdbc.update(sql, "F1", "MISSING", 7L, "RECEIPT", "op", "badtransfer", 1, "kg"));
        assertThrows(RuntimeException.class, () -> f.jdbc.update(sql, "F1", "T1", 99L, "RECEIPT", "op", "baditem", 1, "kg"));
        assertEquals(0, f.count("warehouse_transfer_movements"));
        f.dispatch(); UUID key = UUID.randomUUID(); f.receive("0.04", key);
        f.jdbc.update("update warehouse_transfer_movements set deleted_at=current_timestamp where request_key=?", key.toString());
        assertThrows(RuntimeException.class, () -> f.jdbc.update(sql, "F1", "T1", 7L, "RECEIPT", "op", key.toString(), 1, "kg"));
        assertEquals(2, f.count("warehouse_transfer_movements"));
        assertEquals(1, f.run(() -> f.service.getWarehouseDelivery("F1", "T1")).getItems().get(0).getReceipts().size());
    }

    @Test void postgresRollsBackAllV77DdlWhenAPrecedingForeignTableIsMissing() {
        String failedSchema = f.schema + "_fail";
        f.jdbc.execute("create schema " + failedSchema);
        var ds = new DriverManagerDataSource(f.baseUrl + (f.baseUrl.contains("?") ? "&" : "?")
                + "currentSchema=" + failedSchema, f.user, f.password);
        var jdbc = new JdbcTemplate(ds);
        try {
            jdbc.execute("create table factories(id varchar(191) primary key)");
            var flyway = Flyway.configure().dataSource(ds).schemas(failedSchema).defaultSchema(failedSchema)
                    .locations("classpath:db/flyway").baselineOnMigrate(true).baselineVersion("20261103.74")
                    .target("20261103.77").outOfOrder(false).load();
            assertThrows(org.flywaydb.core.api.FlywayException.class, flyway::migrate);
            assertEquals(0, jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=? "
                    + "and table_name='warehouse_transfer_movements'", Integer.class, failedSchema));
            assertEquals(0, jdbc.queryForObject("select count(*) from flyway_schema_history where version='20261103.77'", Integer.class));
        } finally { f.jdbc.execute("drop schema " + failedSchema + " cascade"); }
    }
}
