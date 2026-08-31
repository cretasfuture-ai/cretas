package com.cretas.aims.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Prevents Hibernate/Flyway from silently reintroducing table-wide plan uniqueness. */
class ProductionSettlementPartialUniqueIndexContractTest {

    private static final Path ENTITY = Path.of(
            "src/main/java/com/cretas/aims/entity/ProductionSettlement.java");
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/flyway/V20261101_21__production_settlement_active_plan_unique.sql");

    @Test
    void entityDoesNotDeclareFullPlanUniqueConstraintAndMigrationUsesDeletedPredicate() throws Exception {
        String entity = Files.readString(ENTITY);
        String migration = Files.readString(MIGRATION);

        assertThat(entity).doesNotContain("uq_prod_settlement_plan");
        assertThat(entity).contains("uq_prod_settlement_idem");
        assertThat(migration).contains("DROP CONSTRAINT IF EXISTS uq_prod_settlement_plan");
        assertThat(migration).contains("CREATE UNIQUE INDEX IF NOT EXISTS uq_prod_settlement_plan_active");
        assertThat(migration).contains("WHERE deleted_at IS NULL");
        assertThat(migration).doesNotContain("UNIQUE (factory_id, production_plan_id)");
    }
}
