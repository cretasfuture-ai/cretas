package com.cretas.aims.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FlywayNoAutomaticRepairContractTest {
    @TempDir
    Path migrations;

    @Test
    void validatesBeforeMigratingAndNeverRepairsHistory() {
        Flyway flyway = mock(Flyway.class);
        new FlywayConfig().migrationStrategy().migrate(flyway);
        verify(flyway, never()).repair();
        InOrder order = inOrder(flyway);
        order.verify(flyway).validate();
        order.verify(flyway).migrate();
    }

    @Test
    void validationFailureStopsBeforeAnyMigrationOrRepair() {
        Flyway flyway = mock(Flyway.class);
        doThrow(new IllegalStateException("synthetic checksum mismatch"))
                .when(flyway).validate();
        assertThrows(IllegalStateException.class,
                () -> new FlywayConfig().migrationStrategy().migrate(flyway));
        verify(flyway, never()).repair();
        verify(flyway, never()).migrate();
    }

    @Test
    void realChecksumDriftCannotRewriteHistoryOrApplyPendingMigrationEvenWhenImplicitValidationIsOff()
            throws Exception {
        Path applied = migrations.resolve("V1__synthetic_probe.sql");
        Files.writeString(applied, "CREATE TABLE synthetic_probe (value INT); INSERT INTO synthetic_probe VALUES (1);");
        String url = "jdbc:h2:mem:no_repair_contract;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE";
        Flyway flyway = Flyway.configure().dataSource(url, "sa", "")
                .locations("filesystem:" + migrations).validateOnMigrate(false).load();
        flyway.migrate();
        List<String> before = history(url);
        Files.writeString(applied, "CREATE TABLE synthetic_probe (value INT); INSERT INTO synthetic_probe VALUES (2);");
        Files.writeString(migrations.resolve("V2__synthetic_pending.sql"),
                "INSERT INTO synthetic_probe VALUES (3);");

        assertThrows(org.flywaydb.core.api.exception.FlywayValidateException.class,
                () -> new FlywayConfig().migrationStrategy().migrate(flyway));

        assertEquals(before, history(url), "Validation must leave every schema-history field unchanged");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             ResultSet result = connection.createStatement().executeQuery("SELECT COUNT(*), MIN(value) FROM synthetic_probe")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1), "The pending migration must not execute");
            assertEquals(1, result.getInt(2));
        }
    }

    @Test
    void productionProfileEnablesValidationOnMigrate() throws Exception {
        Properties properties = new Properties();
        try (InputStream stream = getClass().getResourceAsStream("/application-pg-prod.properties")) {
            assertNotNull(stream);
            properties.load(stream);
        }
        assertEquals("true", properties.getProperty("spring.flyway.validate-on-migrate"));
    }

    private List<String> history(String url) throws Exception {
        List<String> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             ResultSet result = connection.createStatement().executeQuery(
                     "SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"")) {
            while (result.next()) {
                StringBuilder row = new StringBuilder();
                for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
                    row.append(result.getObject(column)).append('|');
                }
                rows.add(row.toString());
            }
        }
        return rows;
    }
}
