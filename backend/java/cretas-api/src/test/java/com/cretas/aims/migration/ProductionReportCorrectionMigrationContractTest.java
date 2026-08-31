package com.cretas.aims.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionReportCorrectionMigrationContractTest {
    private static final Path SQL = Path.of("src", "main", "resources", "db", "flyway",
            "V20261101_22__production_report_corrections.sql");

    private String sql() throws Exception {
        assertThat(SQL).exists();
        return Files.readString(SQL).replaceAll("\\s+", " ");
    }

    @Test
    void keepsOnePendingCorrectionPerReportButUnlimitedTerminalHistory() throws Exception {
        String sql = sql();
        assertThat(sql).contains("CREATE UNIQUE INDEX IF NOT EXISTS uq_prod_report_correction_pending")
                .contains("(factory_id, production_report_id)")
                .contains("WHERE status = 'PENDING_APPROVAL'");
    }

    @Test
    void persistsPermanentMarkerAndAllRequiredAuditFields() throws Exception {
        assertThat(sql()).contains("data_corrected BOOLEAN NOT NULL DEFAULT FALSE")
                .contains("original_output_quantity").contains("proposed_output_quantity")
                .contains("correction_reason").contains("submitted_by").contains("submitted_at")
                .contains("workflow_instance_id").contains("decided_by").contains("decided_at")
                .contains("decision_notes").contains("applied_at");
    }
}
