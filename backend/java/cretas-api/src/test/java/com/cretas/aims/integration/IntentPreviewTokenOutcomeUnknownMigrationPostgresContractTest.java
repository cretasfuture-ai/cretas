package com.cretas.aims.integration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V20261112_40 on real PostgreSQL after V20261028_75: OUTCOME_UNKNOWN becomes a legal token status,
 * unknown statuses stay rejected, the EXECUTING-needs-a-claim check survives, and the script re-runs.
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class IntentPreviewTokenOutcomeUnknownMigrationPostgresContractTest {

    private static final String BASE = "db/flyway/V20261028_75__intent_preview_token_atomic_confirmation.sql";
    private static final String MIGRATION = "db/flyway/V20261112_40__intent_preview_token_outcome_unknown.sql";

    @Test
    void outcomeUnknownIsAcceptedAndEverythingElseStaysConstrained() throws Exception {
        String schema = "ipt_unknown_" + UUID.randomUUID().toString().replace("-", "");
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        String user = requiredEnv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
            statement.execute(script(BASE)); // DO $$ blocks: run the file whole, as Flyway does
            String claimed = "INSERT INTO intent_preview_tokens (token, factory_id, tenant_id, user_id, intent_code,"
                    + " tool_name, descriptor_version, execution_mode, parameters_hash, command_digest, status,"
                    + " expires_at, claim_id, claimed_at) VALUES ('%s', 'F1', 'F1', 7, 'X', 't', '1', 'EXECUTE',"
                    + " repeat('a', 64), repeat('b', 64), 'EXECUTING', now() + interval '5 minutes', 'c', now())";
            statement.execute(String.format(claimed, "t-before"));
            assertThrows(SQLException.class, () -> statement.execute(
                    "UPDATE intent_preview_tokens SET status = 'OUTCOME_UNKNOWN' WHERE token = 't-before'"),
                    "before the migration the old constraint rejects the new status");

            statement.execute(script(MIGRATION));
            statement.execute(script(MIGRATION)); // idempotent

            assertDoesNotThrow(() -> statement.execute(
                    "UPDATE intent_preview_tokens SET status = 'OUTCOME_UNKNOWN', resolved_at = now() WHERE token = 't-before'"));
            assertThrows(SQLException.class, () -> statement.execute(
                    "UPDATE intent_preview_tokens SET status = 'MAYBE' WHERE token = 't-before'"), "unknown status");
            assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO intent_preview_tokens (token, factory_id, user_id, intent_code, status, expires_at)"
                            + " VALUES ('t-no-claim', 'F1', 7, 'X', 'EXECUTING', now())"),
                    "EXECUTING without a claim is still refused");
            try (ResultSet row = statement.executeQuery("SELECT count(*) FROM pg_constraint WHERE conrelid ="
                    + " 'intent_preview_tokens'::regclass AND conname IN ('ck_ipt_atomic_status', 'ck_ipt_executing_claim')")) {
                assertTrue(row.next());
                assertEquals(2, row.getInt(1), "status check replaced in place, executing-claim check untouched");
            }
        } finally {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static String script(String path) throws java.io.IOException {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
