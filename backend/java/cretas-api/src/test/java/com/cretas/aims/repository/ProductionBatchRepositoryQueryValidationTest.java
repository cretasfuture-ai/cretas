package com.cretas.aims.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Boots Hibernate so the tenant-scoped pessimistic batch query is parsed. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ProductionBatchRepositoryQueryValidationTest {

    @Autowired
    private ProductionBatchRepository repository;

    @Test
    void tenantScopedBatchLockQueryBoots() {
        assertThat(repository).isNotNull();
    }

    @Test
    void cleanupLineageLookupIncludesWipMethodAndParses() {
        assertThat(repository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                "__missing_factory__", "__missing_plan__")).isEmpty();
    }

    @Test
    @Transactional
    void cleanupLineageLookupIncludesClerkWipWhileFormalFinderExcludesIt() {
        ProductionBatch regular = batch("repo-contract-regular", "repo-plan", "REGULAR");
        ProductionBatch wip = batch("repo-contract-wip", "repo-plan", "CLERK_WIP");
        ProductionBatch otherFactory = batch("repo-contract-other", "repo-plan", "REGULAR");
        otherFactory.setFactoryId("other-factory");
        repository.saveAll(List.of(regular, wip, otherFactory));
        repository.flush();

        assertThat(repository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull("repo-factory", "repo-plan"))
                .extracting(ProductionBatch::getBatchType)
                .containsExactlyInAnyOrder("REGULAR", "CLERK_WIP");
        assertThat(repository.findByFactoryIdAndProductionPlanId("repo-factory", "repo-plan"))
                .extracting(ProductionBatch::getBatchType)
                .containsExactly("REGULAR");
    }

    private static ProductionBatch batch(String number, String plan, String type) {
        return ProductionBatch.builder()
                .factoryId("repo-factory").batchNumber(number).productionPlanId(plan)
                .productTypeId("repo-product").quantity(BigDecimal.ONE).unit("kg")
                .status(ProductionBatchStatus.PLANNED).batchType(type).build();
    }
}
