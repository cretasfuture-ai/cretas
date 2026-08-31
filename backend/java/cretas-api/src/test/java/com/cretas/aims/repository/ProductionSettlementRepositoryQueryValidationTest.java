package com.cretas.aims.repository;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Hibernate startup/query parsing gate for settlement active-row lookups. */
@DataJpaTest
@AutoConfigureTestDatabase
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackageClasses = ProductionSettlementRepository.class)
class ProductionSettlementRepositoryQueryValidationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private ProductionSettlementRepository repository;

    @Test
    void activeAndIdempotencyFindersBootAgainstRealJpaContext() {
        assertThat(repository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                "F-JPA-SETTLEMENT", "PLAN-NOT-FOUND")).isEmpty();
        assertThat(repository.findByFactoryIdAndProductionPlanIdAndIdempotencyKeyAndDeletedAtIsNull(
                "F-JPA-SETTLEMENT", "PLAN-NOT-FOUND", "idem")).isEmpty();
    }
}
