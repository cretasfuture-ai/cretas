package com.cretas.aims.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MaterialBatchCostCorrectionMigrationContractTest {
    @Test
    void migrationKeepsImmutableOldNewActorReasonLedger() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/flyway/V20261101_11__material_batch_cost_corrections.sql"));
        assertThat(sql).contains("old_unit_price", "new_unit_price", "reason", "corrected_by",
                "chk_mbcc_positive_price", "chk_mbcc_changed");
    }
}
