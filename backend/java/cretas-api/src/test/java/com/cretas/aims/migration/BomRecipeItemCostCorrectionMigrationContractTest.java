package com.cretas.aims.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BomRecipeItemCostCorrectionMigrationContractTest {
    @Test
    void migrationKeepsImmutableOldNewActorReasonLedger() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/flyway/V20261101_12__bom_recipe_item_cost_corrections.sql"));
        assertThat(sql).contains("old_unit_price", "new_unit_price", "old_item_cost", "new_item_cost",
                "reason", "corrected_by", "chk_bricc_positive_price", "chk_bricc_changed");
    }
}
