package com.cretas.aims.service.unit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LengthQuantityUsageScopeMigrationContractTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/flyway/V20261030_22__length_quantity_usage_scope.sql");

    @Test
    void lengthUnitsGainGovernedQuantityScopesWithoutDroppingExistingScopes() throws IOException {
        String sql = Files.readString(MIGRATION);

        assertThat(sql).contains("coalesce(usage_scopes_json, '[]'::jsonb)");
        assertThat(sql).contains("INVENTORY_QUANTITY");
        assertThat(sql).contains("PURCHASE_QUANTITY");
        assertThat(sql).contains("BOM_QUANTITY");
        assertThat(sql).contains("SPECIFICATION");
        assertThat(sql).contains("upper(coalesce(category, '')) = 'LENGTH'");
        assertThat(sql).doesNotContain("UPDATE raw_material_types");
    }
}
