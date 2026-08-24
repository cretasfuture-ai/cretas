package com.cretas.aims.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PartialProductionOutputReceiptStatusMigrationContractTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/flyway/"
                    + "V20261030_23__allow_partial_production_output_receipt_status.sql");

    @Test
    void allowsOnlyReportedPartialAndFullyReceivedOutputStates() throws IOException {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("ALTER TABLE production_settlement_output_lines")
                .contains("DROP CONSTRAINT IF EXISTS ck_pso_status")
                .contains("ADD CONSTRAINT ck_pso_status")
                .contains("CHECK (status IN ('REPORTED', 'PARTIALLY_RECEIVED', 'RECEIVED'))")
                .doesNotContain("NOT VALID");
    }
}
