package com.cretas.aims.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

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
}
