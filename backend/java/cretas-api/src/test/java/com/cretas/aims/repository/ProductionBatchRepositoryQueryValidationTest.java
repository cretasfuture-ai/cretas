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

    @Autowired
    private com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository policyRepository;

    @Test
    @Transactional
    void crossFactoryCountsLeaveOutProcessSecretProducts() {
        long totalBefore = repository.countExcludingProcessSecretProducts();
        long completedBefore = repository.countByStatusExcludingProcessSecretProducts(ProductionBatchStatus.COMPLETED);

        ProductionBatch secret = batch("repo-secret", "repo-plan", "REGULAR");
        secret.setProductTypeId("repo-secret-product");
        secret.setStatus(ProductionBatchStatus.COMPLETED);
        ProductionBatch open = batch("repo-open", "repo-plan", "REGULAR");
        open.setStatus(ProductionBatchStatus.COMPLETED);
        // same product id in another factory is NOT protected there
        ProductionBatch otherFactory = batch("repo-other-secret-id", "repo-plan", "REGULAR");
        otherFactory.setFactoryId("other-factory");
        otherFactory.setProductTypeId("repo-secret-product");
        repository.saveAll(List.of(secret, open, otherFactory));
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        policyRepository.save(com.cretas.aims.entity.processsecret.ProcessSecretPolicy.builder()
                .factoryId("repo-factory").productTypeId("repo-secret-product")
                .storageMode(com.cretas.aims.entity.processsecret.ProcessSecretStorageMode.CLOUD_RESTRICTED)
                .ownerUserId(1L).createdBy(1L).createdAt(now).updatedAt(now).build());
        repository.flush();
        policyRepository.flush();

        assertThat(repository.countExcludingProcessSecretProducts() - totalBefore).isEqualTo(2);
        assertThat(repository.countByStatusExcludingProcessSecretProducts(ProductionBatchStatus.COMPLETED)
                - completedBefore).isEqualTo(1);
        assertThat(repository.count()).isGreaterThanOrEqualTo(totalBefore + 3);   // control: all 3 exist
    }

    private static ProductionBatch batch(String number, String plan, String type) {
        return ProductionBatch.builder()
                .factoryId("repo-factory").batchNumber(number).productionPlanId(plan)
                .productTypeId("repo-product").quantity(BigDecimal.ONE).unit("kg")
                .status(ProductionBatchStatus.PLANNED).batchType(type).build();
    }
}
