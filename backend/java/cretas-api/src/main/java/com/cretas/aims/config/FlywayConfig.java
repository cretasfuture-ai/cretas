package com.cretas.aims.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Reject migration drift before startup without rewriting schema history. */
@Configuration
public class FlywayConfig {

    public static final String STARTUP_SAFETY_MARKER = "FLYWAY_VALIDATE_WITHOUT_REPAIR_V1";

    @Bean
    public FlywayMigrationStrategy migrationStrategy() {
        return (Flyway flyway) -> {
            // Explicit validation also protects profiles configured with validateOnMigrate=false.
            // A mismatch must fail startup; reconciling deployed history requires a separate review.
            flyway.validate();
            flyway.migrate();
        };
    }
}
