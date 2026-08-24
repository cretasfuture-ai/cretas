package com.cretas.aims.entity;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FinishedGoodsStocktakeMigrationContractTest {
    @Test
    void migrationScopesTasksAndEnforcesExactlyOneBatchIdentity() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/flyway/"
                + "V20261030_24__finished_goods_stocktake_scope.sql")).toLowerCase();
        assertThat(sql).contains("inventory_scope", "finished_goods_batch_id", "product_type_id",
                "quantity_unit", "chk_factory_stocktake_item_identity");
        assertThat(sql).contains("material_batch_id is not null and finished_goods_batch_id is null");
        assertThat(sql).contains("material_batch_id is null and finished_goods_batch_id is not null");
        assertThat(sql).doesNotContain("delete from", "update finished_goods_batches");
    }
}
