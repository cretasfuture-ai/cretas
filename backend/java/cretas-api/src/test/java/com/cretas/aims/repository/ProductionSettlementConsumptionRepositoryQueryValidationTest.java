package com.cretas.aims.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Hibernate startup/query parsing gate for production-settlement consumption rows.
 *
 * <p>{@link #findByFactoryIdAndProductionPlanIdInAndDeletedAtIsNull} backs
 * {@code CustomerSuppliedSettlementCoverageGuard}'s cross-plan cumulative-consumption
 * check (缺陷 48) — a derived (JPQL) query, so it is not caught by the compiler and must be
 * validated against a real JPA metamodel here.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class ProductionSettlementConsumptionRepositoryQueryValidationTest {

    @Autowired
    ProductionSettlementConsumptionRepository repository;

    @Test
    @Transactional
    void derivedFindersBootAgainstRealJpaContext() {
        assertThat(repository.findBySettlementIdAndDeletedAtIsNull("SETTLEMENT-NOT-FOUND"))
                .isEmpty();
        assertThat(repository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                        "F-JPA-SETTLEMENT-CONSUMPTION", "PLAN-NOT-FOUND"))
                .isEmpty();
        assertThat(repository.findByFactoryIdAndProductionPlanIdInAndDeletedAtIsNull(
                        "F-JPA-SETTLEMENT-CONSUMPTION", List.of("PLAN-NOT-FOUND-1", "PLAN-NOT-FOUND-2")))
                .isEmpty();
    }
}
