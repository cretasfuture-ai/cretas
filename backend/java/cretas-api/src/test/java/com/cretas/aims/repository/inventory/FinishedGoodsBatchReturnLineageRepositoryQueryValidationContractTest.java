package com.cretas.aims.repository.inventory;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class FinishedGoodsBatchReturnLineageRepositoryQueryValidationContractTest {

    @Autowired private FinishedGoodsBatchRepository repository;
    @Autowired private EntityManager entityManager;

    @Test
    void returnLineageMappingAndDerivedQueryBootInRealJpaContext() {
        assertNotNull(repository);
        assertNotNull(entityManager.getMetamodel().entity(
                com.cretas.aims.entity.inventory.FinishedGoodsBatch.class)
                .getAttribute("returnOrderItemId"));
        assertThatNoException().isThrownBy(() ->
                repository.findByFactoryIdAndReturnOrderItemId("F006", 42L));
    }
}
