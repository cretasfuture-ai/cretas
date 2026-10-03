package com.cretas.aims.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FlywayNoAutomaticRepairContractTest {
    @TempDir
    Path migrations;

    @Test
    void validatesBeforeMigratingAndNeverRepairsHistory() throws Exception {
        Files.writeString(migrations.resolve("V1__synthetic_probe.sql"), "CREATE TABLE synthetic_ok (value INT);");
        String url = "jdbc:h2:mem:no_repair_order;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE";
        Flyway flyway = spy(Flyway.configure().dataSource(url, "sa", "")
                .locations("filesystem:" + migrations).load());
        new FlywayConfig().migrationStrategy().migrate(flyway);
        verify(flyway, never()).repair();
        verify(flyway).migrate();
    }

    /**
     * The normal release: one migration already applied, one new migration shipped in the jar.
     * Validation must not treat the new (pending) migration as drift — 2026-10-03 every release
     * with a migration failed to start because of exactly this (V20261101_48).
     */
    @Test
    void pendingMigrationWithoutDriftIsAppliedAtStartup() throws Exception {
        Files.writeString(migrations.resolve("V1__synthetic_probe.sql"),
                "CREATE TABLE synthetic_pending (value INT); INSERT INTO synthetic_pending VALUES (1);");
        String url = "jdbc:h2:mem:pending_ok;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE";
        Flyway flyway = Flyway.configure().dataSource(url, "sa", "")
                .locations("filesystem:" + migrations).load();
        flyway.migrate();
        Files.writeString(migrations.resolve("V2__synthetic_pending.sql"),
                "INSERT INTO synthetic_pending VALUES (2);");

        new FlywayConfig().migrationStrategy().migrate(flyway);

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            try (ResultSet applied = connection.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '2' AND \"success\"")) {
                assertTrue(applied.next());
                assertEquals(1, applied.getInt(1), "the pending V2 must be recorded as applied");
            }
            try (ResultSet rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM synthetic_pending")) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1), "the pending V2 must have executed");
            }
        }
    }

    @Test
    void validationIgnoresOnlyPendingAndKeepsConfiguredPatterns() {
        Flyway flyway = Flyway.configure().dataSource("jdbc:h2:mem:patterns", "sa", "")
                .ignoreMigrationPatterns("*:missing").load();
        Flyway validator = FlywayConfig.pendingTolerantValidator(flyway);
        String patterns = Arrays.toString(validator.getConfiguration().getIgnoreMigrationPatterns());
        assertTrue(patterns.contains("*:missing"), patterns);
        assertTrue(patterns.contains("*:pending"), patterns);
        assertFalse(patterns.contains("*:failed"), patterns);
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
