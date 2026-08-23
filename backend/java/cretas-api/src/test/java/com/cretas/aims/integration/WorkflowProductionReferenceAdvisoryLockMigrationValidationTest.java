package com.cretas.aims.integration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static migration gate for the shared Workflow/production reference lock contract. */
class WorkflowProductionReferenceAdvisoryLockMigrationValidationTest {

    private static final String VERSION = "V20261030_21";
    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/flyway");

    @Test
    void migrationVersionIsUniqueAndCoversBothReferenceTables() throws Exception {
        List<Path> matches;
        try (var files = Files.list(MIGRATION_DIR)) {
            matches = files.filter(path -> path.getFileName().toString().startsWith(VERSION + "__"))
                    .toList();
        }
        assertEquals(1, matches.size(), "Flyway version must be unique");

        String sql = Files.readString(matches.getFirst());
        assertTrue(sql.contains("pg_advisory_xact_lock"));
        assertTrue(sql.contains("production_plans"));
        assertTrue(sql.contains("production_batches"));
        assertTrue(sql.contains("BEFORE INSERT OR UPDATE OF status, selected_workflow_id"));
        assertTrue(sql.contains("E'\\x1f'"));
        assertTrue(sql.contains("IN_PROGRESS"));
        assertTrue(sql.contains("PRODUCING"));
        assertTrue(sql.contains("PAUSED"));
    }
}
