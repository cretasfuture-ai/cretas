package com.cretas.aims.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.pattern.ValidatePattern;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reject migration drift before startup without rewriting schema history.
 *
 * <p>Validation must not count a <em>pending</em> migration as drift: a migration that exists
 * in the jar but not yet in the history table is exactly what {@code migrate()} is about to
 * apply. Flyway's {@code validate()} reports pending migrations as invalid unless
 * {@code *:pending} is in {@code ignoreMigrationPatterns}, so a bare {@code validate()} here
 * made every release that carries a new migration fail to start (2026-10-03: V20261101_48 →
 * "Detected resolved migration not applied to database"; blue/green kept the old slot live).
 * Checksum mismatches, missing and failed migrations still fail startup, and nothing is ever
 * repaired automatically.
 */
@org.springframework.context.annotation.Configuration
public class FlywayConfig {

    public static final String STARTUP_SAFETY_MARKER = "FLYWAY_VALIDATE_WITHOUT_REPAIR_V2_PENDING_OK";

    @Bean
    public FlywayMigrationStrategy migrationStrategy() {
        return (Flyway flyway) -> {
            // Explicit validation also protects profiles configured with validateOnMigrate=false.
            // A mismatch must fail startup; reconciling deployed history requires a separate review.
            pendingTolerantValidator(flyway).validate();
            flyway.migrate();
        };
    }

    /** Same configuration (data source, locations, placeholders, ...) plus {@code *:pending}. */
    static Flyway pendingTolerantValidator(Flyway flyway) {
        Configuration configuration = flyway.getConfiguration();
        List<ValidatePattern> patterns = new ArrayList<>();
        if (configuration.getIgnoreMigrationPatterns() != null) {
            patterns.addAll(Arrays.asList(configuration.getIgnoreMigrationPatterns()));
        }
        patterns.add(ValidatePattern.fromPattern("*:pending"));
        return Flyway.configure(configuration.getClassLoader())
                .configuration(configuration)
                .ignoreMigrationPatterns(patterns.toArray(new ValidatePattern[0]))
                .load();
    }
}
