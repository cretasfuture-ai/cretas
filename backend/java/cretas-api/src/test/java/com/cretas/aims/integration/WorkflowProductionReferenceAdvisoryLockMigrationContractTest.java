package com.cretas.aims.integration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static migration gate for the shared Workflow/production reference lock contract. */
class WorkflowProductionReferenceAdvisoryLockMigrationContractTest {

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
        assertTrue(sql.contains("product_process_workflows"));
        assertTrue(sql.contains("workflow.product_type_id"));
        assertTrue(sql.contains("trg_z_guard_workflow_activation_deactivation"));
        assertTrue(sql.contains("deactivate rejected after recheck"));
        assertTrue(sql.contains("BEFORE INSERT OR UPDATE OF status, selected_workflow_id"));
        assertTrue(sql.contains("E'\\\\x1f'"));
        assertTrue(!sql.contains("E'\\x1f'"),
                "V21 must produce the literal backslash separator, not ASCII control U+001F");
        assertTrue(sql.contains("IN_PROGRESS"));
        assertTrue(sql.contains("PRODUCING"));
        assertTrue(sql.contains("PAUSED"));
    }

    /**
     * The function is replaced by later migrations (V20261101_55 adds the start guard).
     * Whatever migration defines it last is what production runs, so that body must keep
     * both decisions: deactivation rechecks running references, and a queued start refuses
     * the version that was just deactivated.
     */
    @Test
    void latestFunctionDefinitionKeepsBothSidesOfTheRace() throws Exception {
        List<Path> definitions;
        try (var files = Files.list(MIGRATION_DIR)) {
            definitions = files
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .filter(path -> readUnchecked(path).contains(
                            "CREATE OR REPLACE FUNCTION public.lock_workflow_production_reference()"))
                    .sorted(Comparator.comparing(
                            WorkflowProductionReferenceAdvisoryLockMigrationContractTest::flywayVersion,
                            WorkflowProductionReferenceAdvisoryLockMigrationContractTest::compareVersions))
                    .toList();
        }
        // Positive control: V21 and the start-guard migration both define it.
        assertTrue(definitions.size() >= 2, "expected V21 and V20261101_55, found " + definitions);
        assertTrue(definitions.getFirst().getFileName().toString().startsWith(VERSION + "__"),
                "V21 must be the first definition, found " + definitions);

        Path latest = definitions.getLast();
        String sql = Files.readString(latest);
        assertTrue(sql.contains("deactivate rejected after recheck"), latest.toString());
        assertTrue(sql.contains("ERRCODE = 'P0001'"), latest.toString());
        assertTrue(sql.contains("was deactivated; production cannot start"), latest.toString());
        assertTrue(sql.contains("activation.enabled IS FALSE"), latest.toString());
        assertTrue(sql.contains("activation.active_workflow_id = NEW.selected_workflow_id"),
                latest.toString());
        assertTrue(sql.contains("ERRCODE = '23514'"), latest.toString());
        assertTrue(sql.contains("E'\\\\x1f'"), latest.toString());
        assertTrue(!sql.contains("E'\\x1f'"), latest.toString());
        // The guard must run after the start acquired the key, not before it.
        assertTrue(sql.lastIndexOf("pg_advisory_xact_lock")
                        < sql.indexOf("was deactivated; production cannot start"),
                "start guard must be evaluated under the advisory key: " + latest);
    }

    private static String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }

    private static long[] flywayVersion(Path path) {
        String name = path.getFileName().toString();
        String version = name.substring(1, name.indexOf("__"));
        return Arrays.stream(version.split("[._]")).mapToLong(Long::parseLong).toArray();
    }

    private static int compareVersions(long[] left, long[] right) {
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            int compared = Long.compare(left[i], right[i]);
            if (compared != 0) return compared;
        }
        return Integer.compare(left.length, right.length);
    }
}
